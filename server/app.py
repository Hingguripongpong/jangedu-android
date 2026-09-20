"""Janggi analysis API.

    GET  /                      analysis UI (ui/index.html)
    GET  /api/new_game          fresh position (query: cho=, han= setup names)
    POST /api/state             {start_fen?, moves[]} -> position, status, legal moves, eval
    POST /api/analyze           {fen|position|start_fen+moves, time_limit_ms, max_depth} -> all moves
    POST /analyze               same as /api/analyze (spec alias)
    POST /api/bestmove          engine picks a move ({fen|moves, time_limit_ms})
    POST /api/eval              evaluation breakdown
    GET  /api/perft             move-generator node counts (debug)
    WS   /ws/analyze            streaming analysis: {type:"analyze", id, fen|moves, time_limit_ms, max_depth}
                                                    {type:"stop"}

The engine is pure Python and single threaded; a process-wide lock serialises
searches so that concurrent requests queue instead of fighting for the CPU.
"""
from __future__ import annotations

import asyncio
import os
import threading
import time
from collections import OrderedDict
from typing import Any

from fastapi import FastAPI, HTTPException, WebSocket, WebSocketDisconnect
from fastapi.responses import HTMLResponse, JSONResponse
from pydantic import BaseModel, Field

from engine.analysis import Analyzer
from engine.board import NUM_SQUARES, PASS_MOVE, SETUPS, Position
from engine.calibration import Calibrator
from engine.evaluation import evaluate_breakdown
from engine.explain import briefing as make_briefing, explain_top
from engine.movegen import generate_legal_moves, gives_check, perft
from engine.notation import (
    SIDE_KOREAN, SIDE_NAME, describe_move, move_to_str, piece_hanja, square_to_str, str_to_move,
)
from engine.rules import BIKJANG_MODES, REPETITION_MODES, RuleConfig, game_status, material_points, replay
from engine.search import SearchConfig, Searcher
from engine.uci_engine import (
    SUPPORTED_BIKJANG_MODES, UciEngine, UciEngineError, default_threads, find_binary, suggested_asset_name,
)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
UI_PATH = os.path.join(ROOT, "ui", "index.html")
CALIB_PATH = os.path.join(ROOT, "data", "calibration.json")
ENGINES_DIR = os.path.join(ROOT, "engines")

MAX_TIME_MS = 600_000
MAX_DEPTH = 64
EXPLAIN_TOP_N = 5

# Final analyses so that a briefing of the move just played can reuse the analysis the client watched
# while thinking, instead of searching again.  The key is the *game state*, not the FEN: the same board
# with a pending 한수쉼, a standing 빅장, a different repetition history or different rules can legitimately
# have different analyses (see ``Position.state_key`` and tests/test_server.py::test_cache_key_is_state_aware).
ANALYSIS_CACHE: "OrderedDict[tuple, dict]" = OrderedDict()
ANALYSIS_CACHE_MAX = 300
CACHE_LOCK = threading.Lock()


def cache_key(pos: Position, rules: RuleConfig, eid: str) -> tuple:
    return pos.state_key(rules_tag=(rules.bikjang, rules.repetition, rules.max_plies), engine_id=eid)


def cache_put(result: dict, pos: Position, rules: RuleConfig, eid: str) -> None:
    if not result.get("final") or result.get("aborted") and result.get("depth", 0) < 2:
        return
    if result.get("fen") != pos.to_fen():
        return
    with CACHE_LOCK:
        ANALYSIS_CACHE[cache_key(pos, rules, eid)] = result
        while len(ANALYSIS_CACHE) > ANALYSIS_CACHE_MAX:
            ANALYSIS_CACHE.popitem(last=False)


def cache_get(pos: Position, rules: RuleConfig, eid: str) -> dict | None:
    with CACHE_LOCK:
        return ANALYSIS_CACHE.get(cache_key(pos, rules, eid))


def finish_result(result: dict, pos: Position, rules: RuleConfig, eid: str) -> dict:
    """Attach explanations for the top moves and remember the final analysis."""
    try:
        result["explanations"] = explain_top(pos, result, EXPLAIN_TOP_N)
    except Exception as exc:  # explanations are a convenience; never fail the analysis because of them
        result["explanations"] = []
        result["explain_error"] = f"{type(exc).__name__}: {exc}"
    cache_put(result, pos, rules, eid)
    return result

ENGINE_LOCK = threading.Lock()

# Running websocket analyses.  A REST engine call (AI 착수, /analyze) must not queue behind an
# unlimited streaming analysis, so it stops them first; each affected client receives a
# ``final`` (aborted) and re-requests analysis for its next position as usual.
ACTIVE_JOBS: set = set()
ACTIVE_JOBS_LOCK = threading.Lock()
HEARTBEAT_S = 2.0


def _preempt_ws_jobs() -> None:
    with ACTIVE_JOBS_LOCK:
        for job in list(ACTIVE_JOBS):
            job.stop.set()

app = FastAPI(title="Janggi AI analysis", version="0.4.0")


def load_calibrator() -> Calibrator:
    if os.path.exists(CALIB_PATH):
        try:
            return Calibrator.load(CALIB_PATH)
        except Exception:  # pragma: no cover - corrupt file falls back to the prior
            pass
    return Calibrator()


CALIBRATOR = load_calibrator()


# --------------------------------------------------------------------------- engine backends
ENGINE_IDS = ("python", "fsf")
_UCI: dict = {"engine": None, "path": None}
_UCI_LOCK = threading.Lock()


def fsf_path() -> str | None:
    return find_binary(search_dirs=[ENGINES_DIR])


def get_uci_engine() -> UciEngine:
    """Shared Fairy-Stockfish process (started on first use); raises 503 if no binary."""
    path = fsf_path()
    if path is None:
        raise HTTPException(status_code=503, detail=f"Fairy-Stockfish not found: put {suggested_asset_name()} into "
                                                    f"{ENGINES_DIR} (python scripts/get_fairy_stockfish.py) or set JANGGI_FSF_PATH")
    with _UCI_LOCK:
        eng = _UCI["engine"]
        if eng is None or _UCI["path"] != path or (eng.proc is not None and eng.proc.poll() is not None):
            if eng is not None:
                eng.close()
            eng = UciEngine(path, threads=default_threads(), hash_mb=int(os.environ.get("JANGGI_FSF_HASH_MB", "128")))
            eng.start()
            _UCI["engine"], _UCI["path"] = eng, path
        return eng


def engine_list() -> list[dict]:
    path = fsf_path()
    out = [{"id": "python", "name": "내장 엔진 (Python alpha-beta)", "available": True,
            "rules": list(BIKJANG_MODES), "note": "느리지만 규칙 옵션을 모두 지원"}]
    fsf = {"id": "fsf", "name": "Fairy-Stockfish", "available": path is not None, "path": path,
           "rules": list(SUPPORTED_BIKJANG_MODES), "note": "훨씬 강함. 빅장 규칙은 '없음' 또는 '반드시 풀기'만 지원",
           "threads": default_threads(), "started": False}
    if path is None:
        fsf["hint"] = (f"{suggested_asset_name()} 파일을 {ENGINES_DIR} 폴더에 넣으세요 "
                       f"(python scripts/get_fairy_stockfish.py 로 자동 다운로드)")
    else:
        # do not start the process here (a broken binary would stall page loads); report if running
        eng = _UCI["engine"]
        if eng is not None and eng.proc is not None and eng.proc.poll() is None:
            fsf["name"] = eng.id_name or "Fairy-Stockfish"
            fsf["started"] = True
            fsf["threads"] = eng.threads
        else:
            fsf["name"] = "Fairy-Stockfish (" + os.path.basename(path) + ")"
    out.append(fsf)
    return out


def load_engine(req: Any) -> str:
    eid = (req.get("engine") if isinstance(req, dict) else getattr(req, "engine", None)) or "python"
    if eid not in ENGINE_IDS:
        raise HTTPException(status_code=400, detail=f"engine must be one of {ENGINE_IDS}")
    return eid


def rules_for_engine(eid: str, rules: RuleConfig) -> RuleConfig:
    """Fairy-Stockfish knows two bikjang rules (forced -> janggi, off -> janggicasual); the classic
    'any move, unresolved ends the game' variants are mapped to forced, the nearest rule with a bikjang."""
    if eid == "fsf" and rules.bikjang not in SUPPORTED_BIKJANG_MODES:
        return RuleConfig(bikjang="forced", repetition=rules.repetition, max_plies=rules.max_plies)
    return rules


# --------------------------------------------------------------------------- models
class RulesRequest(BaseModel):
    bikjang: str = "draw"        # draw | points | off
    repetition: str = "draw"     # draw | off


class PositionRequest(BaseModel):
    fen: str | None = None
    position: str | None = None          # alias used by the spec
    start_fen: str | None = None
    moves: list[str] | None = None
    rules: RulesRequest | None = None
    engine: str | None = None            # python (default) | fsf


class AnalyzeRequest(PositionRequest):
    time_limit_ms: float | None = Field(default=1000, ge=0, le=MAX_TIME_MS)
    max_depth: int = Field(default=MAX_DEPTH, ge=1, le=MAX_DEPTH)


class BestMoveRequest(PositionRequest):
    time_limit_ms: float | None = Field(default=1000, ge=0, le=MAX_TIME_MS)
    max_depth: int = Field(default=MAX_DEPTH, ge=1, le=MAX_DEPTH)


class BriefingRequest(PositionRequest):
    time_limit_ms: float | None = Field(default=800, ge=0, le=MAX_TIME_MS)
    prev_analysis: dict | None = None     # the client's analysis of the position before the move (optional)


# --------------------------------------------------------------------------- helpers
def load_position(req: Any, rules: RuleConfig | None = None) -> Position:
    """Build a Position from any of the accepted request shapes, under ``rules``
    (which decide move legality in the forced-bikjang variant)."""
    if rules is None:
        rules = load_rules(req)
    if isinstance(req, dict):
        fen = req.get("fen") or req.get("position")
        start_fen = req.get("start_fen")
        moves = req.get("moves")
    else:
        fen = req.fen or req.position
        start_fen, moves = req.start_fen, req.moves
    try:
        if moves is not None:
            return replay(start_fen or fen, list(moves), rules)
        if fen:
            return rules.apply(Position.from_fen(fen))
        return rules.apply(Position.initial())
    except (ValueError, KeyError, IndexError) as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


def load_rules(req: Any) -> RuleConfig:
    """RuleConfig from a request model or a raw websocket dict (400 on bad values)."""
    if isinstance(req, dict):
        raw = req.get("rules")
    else:
        raw = req.rules.model_dump() if getattr(req, "rules", None) is not None else None
    try:
        return RuleConfig.from_dict(raw if isinstance(raw, dict) else None)
    except (ValueError, TypeError) as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


def legal_move_dicts(pos: Position) -> list[dict]:
    board = pos.board
    out = []
    for m in generate_legal_moves(pos):
        if m == PASS_MOVE:
            out.append({"move": "pass", "from": None, "to": None, "from_sq": None, "to_sq": None,
                        "piece": None, "capture": None, "gives_check": False, "notation": describe_move(board, m)})
            continue
        frm, to = divmod(m, NUM_SQUARES)
        out.append({
            "move": move_to_str(m),
            "from": square_to_str(frm),
            "to": square_to_str(to),
            "from_sq": frm,
            "to_sq": to,
            "piece": piece_hanja(board[frm]),
            "capture": piece_hanja(board[to]) if board[to] else None,
            "gives_check": gives_check(pos, m),
            "notation": describe_move(board, m),
        })
    return out


def state_dict(pos: Position, rules: RuleConfig = RuleConfig()) -> dict:
    legal = generate_legal_moves(pos)
    st = game_status(pos, legal, rules=rules)
    last = pos.last_move()
    board = []
    for s in range(NUM_SQUARES):
        p = pos.board[s]
        board.append(None if not p else {"type": p & 7, "color": p >> 3, "hanja": piece_hanja(p)})
    return {
        "fen": pos.to_fen(),
        "side": SIDE_NAME[pos.side],
        "side_korean": SIDE_KOREAN[pos.side],
        "ply": pos.ply,
        "status": st.to_dict(),
        "board": board,
        "king_sq": pos.king_sq,
        "last_move": None if last is None else move_to_str(last),
        "legal_moves": legal_move_dicts(pos),
        "material_points": material_points(pos),
        "eval": evaluate_breakdown(pos),
        "hash": f"{pos.hash:016x}",
        "rules": rules.to_dict(),
    }


def _clamp_time(ms: float | None) -> float | None:
    if ms is None or ms <= 0:
        return None
    return min(ms, MAX_TIME_MS)


# --------------------------------------------------------------------------- routes
@app.get("/", response_class=HTMLResponse)
def index() -> HTMLResponse:
    if not os.path.exists(UI_PATH):
        return HTMLResponse("<h1>ui/index.html missing</h1>", status_code=500)
    with open(UI_PATH, encoding="utf-8") as f:
        return HTMLResponse(f.read())


@app.get("/api/health")
def health() -> dict:
    return {"ok": True, "engine": "janggi-ai 0.1 (python)", "calibration": CALIBRATOR.describe()}


@app.get("/api/setups")
def setups() -> dict:
    return {"setups": list(SETUPS), "rules": {"bikjang": list(BIKJANG_MODES), "repetition": list(REPETITION_MODES)},
            "engines": engine_list()}


@app.get("/api/engines")
def engines() -> dict:
    return {"engines": engine_list(), "default": "python"}


@app.get("/api/new_game")
def new_game(cho: str = "마상상마", han: str = "마상상마", bikjang: str = "draw", repetition: str = "draw") -> dict:
    try:
        rules = RuleConfig(bikjang=bikjang, repetition=repetition)
        pos = rules.apply(Position.initial(cho, han))
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    d = state_dict(pos, rules)
    d["start_fen"] = pos.to_fen()
    return d


@app.post("/api/state")
def state(req: PositionRequest) -> dict:
    return state_dict(load_position(req), load_rules(req))


@app.post("/api/eval")
def eval_breakdown(req: PositionRequest) -> dict:
    pos = load_position(req)
    return {"fen": pos.to_fen(), "side": SIDE_NAME[pos.side], "eval": evaluate_breakdown(pos),
            "material_points": material_points(pos), "hash": f"{pos.hash:016x}"}


@app.get("/api/perft")
def perft_endpoint(fen: str | None = None, depth: int = 3) -> dict:
    if depth < 1 or depth > 4:
        raise HTTPException(status_code=400, detail="depth must be 1..4")
    pos = load_position({"fen": fen})
    t0 = time.perf_counter()
    nodes = perft(pos, depth)
    return {"fen": pos.to_fen(), "depth": depth, "nodes": nodes,
            "time_ms": round((time.perf_counter() - t0) * 1000, 1)}


def _rest_time(ms: float | None) -> float:
    """REST calls always get a deadline (0/None = the maximum); only the websocket may run unlimited."""
    return MAX_TIME_MS if not ms or ms <= 0 else min(ms, MAX_TIME_MS)


def _run_analysis(pos: Position, time_limit_ms: float | None, max_depth: int, rules: RuleConfig,
                  eid: str = "python") -> dict:
    _preempt_ws_jobs()
    if eid == "fsf":
        eng = get_uci_engine()
        res = eng.analyze(pos, time_limit_ms=_rest_time(time_limit_ms), max_depth=max_depth, rules=rules)
        return finish_result(res, pos, rules, eid)
    with ENGINE_LOCK:
        analyzer = Analyzer(SearchConfig(rules=rules), calibrator=CALIBRATOR)
        res = analyzer.analyze(pos, time_limit_ms=_rest_time(time_limit_ms), max_depth=max_depth)
        res["engine"] = {"name": "janggi-ai python", "backend": "python"}
        return finish_result(res, pos, rules, eid)


@app.post("/api/briefing")
async def briefing_endpoint(req: BriefingRequest) -> dict:
    """Assess the last move of ``moves``: rank, gap to the best move, what it does well and risks."""
    if not req.moves:
        raise HTTPException(status_code=400, detail="moves must contain at least the move to assess")
    eid = load_engine(req)
    rules = rules_for_engine(eid, load_rules(req))
    prev = load_position({"start_fen": req.start_fen, "fen": req.fen or req.position,
                          "moves": list(req.moves[:-1]), "rules": rules.to_dict()}, rules)
    played = req.moves[-1]
    try:
        m = str_to_move(played)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    if m not in generate_legal_moves(prev):
        raise HTTPException(status_code=400, detail=f"illegal move: {played}")
    analysis = None
    pa = req.prev_analysis
    if isinstance(pa, dict) and pa.get("fen") == prev.to_fen() and pa.get("moves"):
        analysis = pa
        source = "client"
    if analysis is None:
        analysis = cache_get(prev, rules, eid)
        source = "cache"
    if analysis is None:
        analysis = await asyncio.to_thread(_run_analysis, prev.copy(), min(req.time_limit_ms or 800, 5000), MAX_DEPTH,
                                           rules, eid)
        source = "search"
    result = make_briefing(prev, move_to_str(m), analysis)
    result["source"] = source
    result["fen_before"] = prev.to_fen()
    result["analysis_depth"] = analysis.get("depth")
    return result


@app.post("/api/analyze")
async def analyze(req: AnalyzeRequest) -> dict:
    eid = load_engine(req)
    rules = rules_for_engine(eid, load_rules(req))
    pos = load_position(req, rules)
    try:
        return await asyncio.to_thread(_run_analysis, pos, req.time_limit_ms, req.max_depth, rules, eid)
    except UciEngineError as exc:
        raise HTTPException(status_code=503, detail=f"Fairy-Stockfish error: {exc}") from exc


@app.post("/analyze")
async def analyze_alias(req: AnalyzeRequest) -> dict:
    return await analyze(req)


def _run_bestmove(pos: Position, time_limit_ms: float | None, max_depth: int, rules: RuleConfig,
                  eid: str = "python") -> dict:
    _preempt_ws_jobs()
    if eid == "fsf":
        return get_uci_engine().bestmove(pos, time_limit_ms=_rest_time(time_limit_ms), max_depth=max_depth, rules=rules)
    with ENGINE_LOCK:
        searcher = Searcher(SearchConfig(rules=rules))
        res = searcher.search(pos, max_depth=max_depth, time_limit_ms=_rest_time(time_limit_ms))
    move = None if res.best_move == 0 else move_to_str(res.best_move)
    return {
        "fen": pos.to_fen(),
        "move": move,
        "notation": describe_move(pos.board, res.best_move) if move else None,
        "score_cp": res.score,
        "score": round(res.score / 100, 2),
        "estimated_winrate": round(CALIBRATOR.win_probability(res.score), 4),
        "depth": res.depth,
        "nodes": res.nodes,
        "time_ms": round(res.time_ms, 1),
        "nps": round(res.nps),
        "pv": [move_to_str(m) for m in res.pv],
        "tt": searcher.tt_stats(),
        "engine": {"name": "janggi-ai python", "backend": "python"},
    }


@app.post("/api/bestmove")
async def bestmove(req: BestMoveRequest) -> dict:
    eid = load_engine(req)
    rules = rules_for_engine(eid, load_rules(req))
    pos = load_position(req, rules)
    if game_status(pos, rules=rules).is_over:
        raise HTTPException(status_code=400, detail="game is over")
    try:
        return await asyncio.to_thread(_run_bestmove, pos, req.time_limit_ms, req.max_depth, rules, eid)
    except UciEngineError as exc:
        raise HTTPException(status_code=503, detail=f"Fairy-Stockfish error: {exc}") from exc


# --------------------------------------------------------------------------- websocket
class _Job:
    def __init__(self, job_id: Any) -> None:
        self.id = job_id
        self.stop = threading.Event()
        self.task: asyncio.Task | None = None


@app.websocket("/ws/analyze")
async def ws_analyze(ws: WebSocket) -> None:
    await ws.accept()
    loop = asyncio.get_running_loop()
    analyzer = Analyzer(calibrator=CALIBRATOR)   # TT persists across positions of this session
    current: _Job | None = None
    send_lock = asyncio.Lock()

    async def send(payload: dict) -> None:
        async with send_lock:
            await ws.send_json(payload)

    async def run_job(job: _Job, pos: Position, time_limit_ms: float | None, max_depth: int,
                      rules: RuleConfig, eid: str = "python") -> None:
        queue: asyncio.Queue = asyncio.Queue()
        backend = analyzer if eid == "python" else None   # set below for fsf

        def progress(result: dict, final: bool) -> None:
            loop.call_soon_threadsafe(queue.put_nowait, (result, final))

        def work() -> None:
            nonlocal backend
            with ACTIVE_JOBS_LOCK:
                ACTIVE_JOBS.add(job)
            try:
                if eid == "fsf":
                    eng = get_uci_engine()
                    backend = eng
                    if job.stop.is_set():
                        progress({"final": True, "aborted": True, "superseded": True, "fen": pos.to_fen(),
                                  "depth": 0, "nodes": 0, "moves": []}, True)
                        return
                    eng.analyze(pos, time_limit_ms=_clamp_time(time_limit_ms), max_depth=max_depth,
                                stop_event=job.stop, progress=progress, progress_interval_ms=100, rules=rules)
                    return
                with ENGINE_LOCK:
                    if job.stop.is_set():
                        progress({"final": True, "aborted": True, "superseded": True, "fen": pos.to_fen(),
                                  "depth": 0, "nodes": 0, "moves": []}, True)
                        return
                    if analyzer.searcher.cfg.rules != rules:
                        analyzer.searcher.cfg.rules = rules
                        analyzer.searcher.clear_tt()      # scores under other endings are not reusable
                    analyzer.analyze(pos, time_limit_ms=_clamp_time(time_limit_ms), max_depth=max_depth,
                                     stop_event=job.stop, progress=progress, progress_interval_ms=100)
            except HTTPException as exc:
                analyzer.live["running"] = False
                progress({"final": True, "aborted": True, "error": str(exc.detail),
                          "fen": pos.to_fen(), "depth": 0, "nodes": 0, "moves": []}, True)
            except Exception as exc:  # never leave the client without a final message
                analyzer.live["running"] = False
                progress({"final": True, "aborted": True, "error": f"{type(exc).__name__}: {exc}",
                          "fen": pos.to_fen(), "depth": 0, "nodes": 0, "moves": []}, True)
            finally:
                with ACTIVE_JOBS_LOCK:
                    ACTIVE_JOBS.discard(job)

        worker = asyncio.create_task(asyncio.to_thread(work))
        try:
            while True:
                try:
                    result, final = await asyncio.wait_for(queue.get(), timeout=HEARTBEAT_S)
                except asyncio.TimeoutError:
                    if worker.done():
                        # the thread ended without a final (should not happen); tell the client anyway
                        await send({"type": "final", "id": job.id, "final": True, "aborted": True,
                                    "error": "analysis thread ended unexpectedly", "moves": [], "depth": 0,
                                    "nodes": 0, "fen": pos.to_fen()})
                        break
                    src = backend if backend is not None else analyzer
                    live = src.live
                    nodes = src.searcher.nodes if hasattr(src, "searcher") else 0
                    elapsed = time.perf_counter() - live["started_at"] if live["running"] else 0.0
                    await send({"type": "heartbeat", "id": job.id, "nodes": nodes,
                                "depth_in_progress": live["depth_in_progress"],
                                "completed_depth": live["completed_depth"],
                                "moves_done_at_depth": live["moves_done_at_depth"], "move_count": live["move_count"],
                                "elapsed_ms": round(elapsed * 1000), "waiting_for_engine": not live["running"]})
                    continue
                result["type"] = "final" if final else "update"
                result["id"] = job.id
                if final and result.get("moves") and not result.get("superseded"):
                    finish_result(result, pos, rules, eid)
                await send(result)
                if final:
                    break
        finally:
            await worker

    try:
        while True:
            msg = await ws.receive_json()
            mtype = msg.get("type", "analyze")
            if mtype == "stop":
                if current is not None:
                    current.stop.set()
                continue
            if mtype == "ping":
                await send({"type": "pong"})
                continue
            if mtype != "analyze":
                await send({"type": "error", "id": msg.get("id"), "detail": f"unknown message type {mtype!r}"})
                continue
            if current is not None and current.task is not None and not current.task.done():
                current.stop.set()
                try:
                    await current.task
                except Exception:  # pragma: no cover
                    pass
            try:
                eid = load_engine(msg)
                rules = rules_for_engine(eid, load_rules(msg))
                pos = load_position(msg, rules)
            except HTTPException as exc:
                await send({"type": "error", "id": msg.get("id"), "detail": exc.detail})
                continue
            time_limit = msg.get("time_limit_ms", 1000)
            try:
                time_limit = None if time_limit in (None, 0, "0") else float(time_limit)
                max_depth = max(1, min(MAX_DEPTH, int(msg.get("max_depth", MAX_DEPTH))))
            except (TypeError, ValueError):
                await send({"type": "error", "id": msg.get("id"), "detail": "bad time_limit_ms/max_depth"})
                continue
            job = _Job(msg.get("id"))
            job.task = asyncio.create_task(run_job(job, pos, time_limit, max_depth, rules, eid))
            current = job
    except WebSocketDisconnect:
        pass
    finally:
        if current is not None:
            current.stop.set()
            if current.task is not None and not current.task.done():
                try:
                    await current.task
                except Exception:  # pragma: no cover
                    pass


@app.exception_handler(ValueError)
async def value_error_handler(_, exc: ValueError):  # pragma: no cover - defensive
    return JSONResponse(status_code=400, content={"detail": str(exc)})
