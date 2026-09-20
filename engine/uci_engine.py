"""UCI adapter: run Fairy-Stockfish (variant ``janggi``) as an alternative analysis backend.

Fairy-Stockfish speaks UCI with files a–i and ranks 1–10 (rank 1 = Cho's back
rank), a pass is written as the king's square twice (``e2e2``), and its
``janggi`` variant enforces the *forced* 빅장 rule (see ``engine.rules``).  Our
FEN is compatible, so a position is sent as ``position fen <start> moves ...``
which also gives the engine the repetition history.

Two search modes, separated at UCI-command level:

* ``UciEngine.analyze``  – *analysis mode*: ``MultiPV = number of legal moves`` so that every
  legal move gets its own score/PV (the Go-style all-moves view).
* ``UciEngine.bestmove`` – *play mode*: ``MultiPV = 1`` so the whole time budget goes into the
  single best line (maximum playing strength).  It never calls ``analyze``.

``UciEngine.analyze`` returns the same dictionary as ``Analyzer.analyze`` (all
legal moves with score, estimated win rate, depth, nodes, PV) so the server and
the UI do not care which backend produced it.  Win rates use our logistic
``Calibrator`` on the engine's centipawns (Fairy-Stockfish's own WDL model is
fitted for chess, so it is only exposed as ``engine_wdl``); its centipawn scale
is smaller than ours (a chariot ≈ 800 cp vs 1300), hence the default K=300.

Download: https://github.com/fairy-stockfish/Fairy-Stockfish/releases — the
``fairy-stockfish-largeboard_*`` build is required for 9x10 boards.
"""
from __future__ import annotations

import glob
import os
import platform
import queue
import subprocess
import sys
import threading
import time
from typing import Callable

from .board import NUM_SQUARES, PASS_MOVE, Position
from .calibration import Calibrator
from .movegen import generate_legal_moves, gives_check
from .notation import SIDE_KOREAN, SIDE_NAME, describe_move, move_to_str, piece_hanja, square_to_str
from .rules import RuleConfig, game_status

FILES = "abcdefghi"
DEFAULT_FSF_K = 300.0

# Our bikjang rule -> Fairy-Stockfish variant.  "janggi": the receiver of a 빅장 must resolve it or
# pass (accept, decided on points).  "janggicasual": no bikjang rule, two passes draw — exactly our "off".
VARIANT_FOR_BIKJANG = {"forced": "janggi", "off": "janggicasual"}
SUPPORTED_BIKJANG_MODES = tuple(VARIANT_FOR_BIKJANG)


def variant_for_rules(rules: RuleConfig) -> str:
    try:
        return VARIANT_FOR_BIKJANG[rules.bikjang]
    except KeyError:
        raise ValueError(f"Fairy-Stockfish supports bikjang rules {SUPPORTED_BIKJANG_MODES}, not {rules.bikjang!r}") from None


# --------------------------------------------------------------------------- notation
def sq_to_uci(s: int) -> str:
    r, c = divmod(s, 9)
    return f"{FILES[c]}{10 - r}"


def uci_to_sq(text: str) -> int:
    c = FILES.index(text[0])
    r = 10 - int(text[1:])
    if not 0 <= r < 10:
        raise ValueError(f"bad UCI square {text!r}")
    return r * 9 + c


def move_to_uci(pos: Position, m: int) -> str:
    if m == PASS_MOVE:
        k = pos.king_sq[pos.side]
        return sq_to_uci(k) * 2
    frm, to = divmod(m, NUM_SQUARES)
    return sq_to_uci(frm) + sq_to_uci(to)


def uci_to_move(text: str) -> int:
    """UCI move -> our int move (a square repeated = 한수쉼)."""
    text = text.strip()
    # squares are 2 or 3 chars ("e2" / "e10"): the second square starts at the next letter
    i = 1
    while i < len(text) and not text[i].isalpha():
        i += 1
    frm, to = uci_to_sq(text[:i]), uci_to_sq(text[i:])
    if frm == to:
        return PASS_MOVE
    return frm * NUM_SQUARES + to


def moves_to_uci(start: Position, moves: list[int]) -> list[str]:
    pos = start.copy()
    out = []
    for m in moves:
        out.append(move_to_uci(pos, m))
        pos.make_move(m)
    return out


# --------------------------------------------------------------------------- discovery
def find_binary(explicit: str | None = None, search_dirs: list[str] | None = None) -> str | None:
    """Locate a Fairy-Stockfish largeboard binary: explicit path, $JANGGI_FSF_PATH, then engines/."""
    candidates = []
    if explicit:
        candidates.append(explicit)
    env = os.environ.get("JANGGI_FSF_PATH")
    if env:
        candidates.append(env)
    for d in search_dirs or []:
        candidates += sorted(glob.glob(os.path.join(d, "fairy-stockfish*")))
    for c in candidates:
        if os.path.isfile(c) and os.access(c, os.X_OK | os.R_OK):
            return c
    return None


def default_threads() -> int:
    """Half the logical cores (at most 8) unless JANGGI_FSF_THREADS says otherwise; keeps the UI fluid."""
    env = os.environ.get("JANGGI_FSF_THREADS")
    if env and env.isdigit():
        return max(1, int(env))
    return max(1, min(8, (os.cpu_count() or 2) // 2))


def suggested_asset_name() -> str:
    """Release asset best suited to this machine (largeboard build is mandatory for janggi)."""
    ext = ".exe" if platform.system() == "Windows" else ""
    return f"fairy-stockfish-largeboard_x86-64{ext}"


# --------------------------------------------------------------------------- engine
class UciEngineError(RuntimeError):
    pass


class UciEngine:
    def __init__(self, path: str, variant: str = "janggi", threads: int = 1, hash_mb: int = 128,
                 calibrator: Calibrator | None = None, max_pv_len: int = 12) -> None:
        self.path = path
        self.variant = variant
        self.threads = max(1, threads)
        self.hash_mb = max(1, hash_mb)
        self.calibrator = calibrator or Calibrator(k=DEFAULT_FSF_K, source=f"default prior for {os.path.basename(path)} (uncalibrated)")
        self.max_pv_len = max_pv_len
        self.proc: subprocess.Popen | None = None
        self._active_variant: str | None = None
        self.lines: queue.Queue[str | None] = queue.Queue()
        self.name = os.path.basename(path)
        self.id_name = ""
        self._lock = threading.Lock()
        self.live: dict = {"running": False, "depth_in_progress": 0, "completed_depth": 0,
                           "moves_done_at_depth": 0, "move_count": 0, "started_at": 0.0}
        # the last commands written to the engine (protocol-level tests assert on these)
        self.sent: list[str] = []
        self.sent_max = 64
        self._multipv: int | None = None

    # ----------------------------------------------------------------- process
    def start(self) -> None:
        if self.proc is not None and self.proc.poll() is None:
            return
        # Run the engine at lowered priority so that the browser and the server stay responsive
        # while it uses several cores (especially in unlimited analysis).
        popen_kwargs: dict = {}
        if sys.platform == "win32":
            popen_kwargs["creationflags"] = getattr(subprocess, "BELOW_NORMAL_PRIORITY_CLASS", 0)
        else:
            popen_kwargs["preexec_fn"] = lambda: os.nice(5)
        self.proc = subprocess.Popen([self.path], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=subprocess.STDOUT, text=True, bufsize=1,
                                     cwd=os.path.dirname(os.path.abspath(self.path)), **popen_kwargs)
        self.lines = queue.Queue()
        threading.Thread(target=self._reader, args=(self.proc,), daemon=True).start()
        self._send("uci")
        for line in self._read_until(lambda l: l.startswith("uciok"), timeout=10):
            if line.startswith("id name"):
                self.id_name = line[8:].strip()
        self._multipv = None
        self._send(f"setoption name UCI_Variant value {self.variant}")
        self._active_variant = self.variant
        self._send(f"setoption name Threads value {self.threads}")
        self._send(f"setoption name Hash value {self.hash_mb}")
        self._send("setoption name UCI_ShowWDL value true")
        self._sync()

    def close(self) -> None:
        if self.proc is None:
            return
        try:
            self._send("quit")
            self.proc.wait(timeout=3)
        except Exception:
            self.proc.kill()
        self.proc = None

    def _reader(self, proc: subprocess.Popen) -> None:
        for line in proc.stdout:  # type: ignore[union-attr]
            self.lines.put(line.rstrip("\n"))
        self.lines.put(None)

    def _send(self, cmd: str) -> None:
        if self.proc is None or self.proc.stdin is None:
            raise UciEngineError("engine not running")
        self.sent.append(cmd)
        if len(self.sent) > self.sent_max:
            del self.sent[: len(self.sent) - self.sent_max]
        self.proc.stdin.write(cmd + "\n")
        self.proc.stdin.flush()

    def _set_multipv(self, n: int) -> None:
        """Send ``MultiPV`` only when it changes (an option change is cheap but noisy in logs)."""
        n = max(1, min(int(n), 500))
        if n != self._multipv:
            self._send(f"setoption name MultiPV value {n}")
            self._multipv = n

    @staticmethod
    def _go_command(time_limit_ms: float | None, max_depth: int, node_limit: int | None) -> str:
        go = ["go"]
        if max_depth < 64:
            go += ["depth", str(max_depth)]
        if time_limit_ms:
            go += ["movetime", str(int(time_limit_ms))]
        if node_limit:
            go += ["nodes", str(int(node_limit))]
        if len(go) == 1:
            go.append("infinite")
        return " ".join(go)

    def _read_until(self, pred: Callable[[str], bool], timeout: float) -> list[str]:
        deadline = time.monotonic() + timeout
        out = []
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise UciEngineError("engine timed out")
            try:
                line = self.lines.get(timeout=remaining)
            except queue.Empty:
                raise UciEngineError("engine timed out") from None
            if line is None:
                raise UciEngineError("engine process exited")
            out.append(line)
            if pred(line):
                return out

    def _sync(self) -> None:
        self._send("isready")
        self._read_until(lambda l: l == "readyok", timeout=30)

    def _drain(self) -> None:
        while True:
            try:
                self.lines.get_nowait()
            except queue.Empty:
                return

    def _position_cmd(self, pos: Position) -> str:
        """Send the position with its history so repetitions are visible to the engine."""
        if pos._stack:
            root = pos.copy()
            moves = []
            while root._stack:
                moves.append(root.last_move())
                root.unmake_move()
            moves.reverse()
            return f"position fen {root.to_fen()} moves {' '.join(moves_to_uci(root, moves))}"
        return f"position fen {pos.to_fen()}"

    # ----------------------------------------------------------------- analysis
    def analyze(self, pos: Position, time_limit_ms: float | None = 1000, max_depth: int = 64,
                stop_event: threading.Event | None = None, progress=None,
                progress_interval_ms: float = 100.0, node_limit: int | None = None,
                rules: RuleConfig | None = None) -> dict:
        with self._lock:
            return self._analyze(pos, time_limit_ms, max_depth, stop_event, progress, progress_interval_ms,
                                 node_limit, rules or RuleConfig(bikjang="forced"))

    def set_variant(self, variant: str) -> None:
        """Switch the engine's variant (clears its hash); no-op when unchanged."""
        if variant != self._active_variant:
            self._send(f"setoption name UCI_Variant value {variant}")
            self._active_variant = variant
            self._sync()

    def _analyze(self, pos, time_limit_ms, max_depth, stop_event, progress, progress_interval_ms, node_limit,
                 rules) -> dict:
        self.start()
        self.set_variant(variant_for_rules(rules))
        start = time.perf_counter()
        legal = generate_legal_moves(pos)
        status = game_status(pos, legal, rules=rules)
        lines: dict[int, dict] = {}          # multipv index -> parsed info
        info = {"depth": 0, "seldepth": 0, "nodes": 0, "nps": 0, "time": 0}
        aborted = False
        last_emit = 0.0
        self.live.update(running=True, depth_in_progress=0, completed_depth=0, moves_done_at_depth=0,
                         move_count=len(legal), started_at=start)

        def result(final: bool) -> dict:
            return self._result(pos, legal, status, lines, info, start, final, aborted, rules)

        def emit(final: bool = False, force: bool = False) -> None:
            nonlocal last_emit
            if progress is None:
                return
            now = time.perf_counter()
            if not final and not force and (now - last_emit) * 1000.0 < progress_interval_ms:
                return
            last_emit = now
            progress(result(final), final)

        if not legal or status.is_over:
            self.live["running"] = False
            res = result(True)
            if progress:
                progress(res, True)
            return res

        self._drain()
        self._set_multipv(len(legal))                 # analysis mode: one PV per legal move
        self._send(self._position_cmd(pos))
        self._sync()
        self._send(self._go_command(time_limit_ms, max_depth, node_limit))

        stopped = False
        deadline = None if not time_limit_ms else start + time_limit_ms / 1000.0 + 5.0
        while True:
            try:
                line = self.lines.get(timeout=0.05)
            except queue.Empty:
                if stop_event is not None and stop_event.is_set() and not stopped:
                    self._send("stop")
                    stopped = True
                    aborted = True
                if deadline is not None and time.monotonic() > deadline + 30:
                    raise UciEngineError("engine did not answer")
                continue
            if line is None:
                raise UciEngineError("engine process exited")
            if line.startswith("bestmove"):
                break
            if line.startswith("info ") and " pv " in line and " multipv " in line:
                parsed = self._parse_info(line)
                if parsed is None:
                    continue
                idx = parsed["multipv"]
                prev = lines.get(idx)
                lines[idx] = parsed
                info.update({k: parsed[k] for k in ("nodes", "nps", "time") if k in parsed})
                info["seldepth"] = max(info["seldepth"], parsed.get("seldepth", 0))
                depths = [v["depth"] for v in lines.values()]
                if len(lines) >= len(legal):
                    info["depth"] = min(depths)
                self.live.update(depth_in_progress=max(depths), completed_depth=info["depth"],
                                 moves_done_at_depth=sum(1 for d in depths if d == max(depths)))
                if idx == len(legal) or (prev is not None and parsed["depth"] > prev["depth"]):
                    emit(force=idx == len(legal))
                else:
                    emit()
            if stop_event is not None and stop_event.is_set() and not stopped:
                self._send("stop")
                stopped = True
                aborted = True
        self.live["running"] = False
        res = result(True)
        if progress:
            progress(res, True)
        return res

    @staticmethod
    def _parse_info(line: str) -> dict | None:
        tok = line.split()
        out: dict = {}
        i = 1
        while i < len(tok):
            t = tok[i]
            if t in ("depth", "seldepth", "multipv", "nodes", "nps", "time", "hashfull", "tbhits"):
                out[t] = int(tok[i + 1]); i += 2
            elif t == "score":
                kind, val = tok[i + 1], int(tok[i + 2])
                out["score_kind"] = kind
                out["score_val"] = val
                i += 3
                if i < len(tok) and tok[i] in ("lowerbound", "upperbound"):
                    out["bound"] = tok[i]; i += 1
            elif t == "wdl":
                out["wdl"] = (int(tok[i + 1]), int(tok[i + 2]), int(tok[i + 3])); i += 4
            elif t == "pv":
                out["pv"] = tok[i + 1:]
                break
            elif t == "string":
                return None
            else:
                i += 1
        if "pv" not in out or "multipv" not in out or "score_kind" not in out:
            return None
        return out

    def _result(self, pos: Position, legal: list[int], status, lines: dict[int, dict], info: dict,
                start: float, final: bool, aborted: bool, rules: RuleConfig) -> dict:
        elapsed_ms = (time.perf_counter() - start) * 1000.0
        legal_set = set(legal)
        by_move: dict[int, dict] = {}
        for ln in lines.values():
            try:
                m = uci_to_move(ln["pv"][0])
            except (ValueError, IndexError):
                continue
            if m in legal_set:
                by_move[m] = ln
        entries = []
        for m in legal:
            ln = by_move.get(m)
            if ln is None:
                entries.append((m, None))
            else:
                entries.append((m, ln))

        def sort_key(e):
            m, ln = e
            if ln is None:
                return (1, 0, move_to_str(m))
            return (0, -self._cp_or_mate_value(ln), move_to_str(m))

        entries.sort(key=sort_key)
        moves = [self._move_dict(pos, m, ln, i + 1) for i, (m, ln) in enumerate(entries)]
        return {
            "final": final,
            "aborted": aborted,
            "engine": {"name": self.id_name or self.name, "backend": "uci", "path": self.path,
                       "variant": self._active_variant or self.variant, "threads": self.threads,
                       "mode": "analysis", "multipv": len(legal)},
            "fen": pos.to_fen(),
            "side": SIDE_NAME[pos.side],
            "side_korean": SIDE_KOREAN[pos.side],
            "status": status.to_dict(),
            "depth": info["depth"],
            "seldepth": info["seldepth"],
            "nodes": info["nodes"],
            "qnodes": 0,
            "time_ms": round(elapsed_ms, 1),
            "nps": info["nps"],
            "tt": {"entries": None, "probes": 0, "hits": 0, "cutoffs": 0, "hit_rate": None,
                   "hashfull": None},
            "calibration": self.calibrator.describe(),
            "rules": rules.to_dict(),
            "moves": moves,
        }

    @staticmethod
    def _cp_or_mate_value(ln: dict) -> int:
        if ln["score_kind"] == "mate":
            n = ln["score_val"]
            return (100_000 - abs(n)) if n > 0 else -(100_000 - abs(n))
        return ln["score_val"]

    def _move_dict(self, pos: Position, m: int, ln: dict | None, rank: int) -> dict:
        board = pos.board
        frm, to = (None, None) if m == PASS_MOVE else divmod(m, NUM_SQUARES)
        d = {
            "rank": rank,
            "move": move_to_str(m),
            "notation": describe_move(board, m),
            "from": None if frm is None else square_to_str(frm),
            "to": None if to is None else square_to_str(to),
            "from_sq": frm,
            "to_sq": to,
            "piece": None if frm is None else piece_hanja(board[frm]),
            "capture": piece_hanja(board[to]) if to is not None and board[to] else None,
            "gives_check": gives_check(pos, m),
            "score_cp": None, "score": None, "mate": None, "decisive": None,
            "estimated_winrate": None, "wdl": None, "engine_wdl": None,
            "depth": 0, "nodes": 0, "time_ms": 0.0, "pv": [move_to_str(m)], "pv_pretty": [describe_move(board, m)],
            "bound": None,
        }
        if ln is None:
            return d
        if ln["score_kind"] == "mate":
            n = ln["score_val"]
            d["mate"] = n
            d["decisive"] = "mate"
            d["score_cp"] = self._cp_or_mate_value(ln)
            d["score"] = round(d["score_cp"] / 100.0, 2)
            d["estimated_winrate"], d["wdl"] = (1.0, [1.0, 0.0, 0.0]) if n > 0 else (0.0, [0.0, 0.0, 1.0])
        else:
            cp = ln["score_val"]
            d["score_cp"] = cp
            d["score"] = round(cp / 100.0, 2)
            d["estimated_winrate"] = round(self.calibrator.win_probability(cp), 4)
            d["wdl"] = [round(x, 4) for x in self.calibrator.wdl(cp)]
        if "wdl" in ln:
            w, dr, l = ln["wdl"]
            d["engine_wdl"] = [w / 1000.0, dr / 1000.0, l / 1000.0]
        d["bound"] = ln.get("bound")
        d["depth"] = ln["depth"]
        d["nodes"] = ln.get("nodes", 0)
        d["time_ms"] = float(ln.get("time", 0))
        pv_moves, pv_pretty = self._convert_pv(pos, ln["pv"])
        if pv_moves:
            d["pv"], d["pv_pretty"] = pv_moves, pv_pretty
        return d

    def _convert_pv(self, pos: Position, pv: list[str]) -> tuple[list[str], list[str]]:
        cur = pos.copy()
        out_moves, out_pretty = [], []
        for text in pv[: self.max_pv_len]:
            try:
                m = uci_to_move(text)
            except ValueError:
                break
            if m not in generate_legal_moves(cur):
                break
            out_moves.append(move_to_str(m))
            out_pretty.append(describe_move(cur.board, m))
            cur.make_move(m)
        return out_moves, out_pretty

    # ----------------------------------------------------------------- single best move (play mode)
    def bestmove(self, pos: Position, time_limit_ms: float | None = 1000, max_depth: int = 64,
                 rules: RuleConfig | None = None, stop_event: threading.Event | None = None,
                 node_limit: int | None = None, on_info=None) -> dict:
        """Play mode: ``MultiPV = 1`` — the whole budget goes into the best line.

        Returns ``{"move", "notation", "score_cp", "score", "mate", "estimated_winrate", "depth",
        "seldepth", "nodes", "time_ms", "nps", "pv", "engine", ...}``; ``move`` is ``None`` when the
        position has no legal move or the game is over.  ``on_info(dict)`` receives every parsed
        ``info`` line (depth, score, pv, nodes, ...) for live display.
        """
        with self._lock:
            return self._bestmove(pos, time_limit_ms, max_depth, rules or RuleConfig(bikjang="forced"),
                                  stop_event, node_limit, on_info)

    def _bestmove(self, pos, time_limit_ms, max_depth, rules, stop_event, node_limit, on_info) -> dict:
        self.start()
        self.set_variant(variant_for_rules(rules))
        start = time.perf_counter()
        legal = generate_legal_moves(pos)
        status = game_status(pos, legal, rules=rules)
        legal_set = set(legal)
        engine_desc = {"name": self.id_name or self.name, "backend": "uci", "path": self.path,
                       "variant": self._active_variant or self.variant, "threads": self.threads,
                       "mode": "play", "multipv": 1}
        empty = {"fen": pos.to_fen(), "move": None, "notation": None, "score_cp": None, "score": None,
                 "mate": None, "estimated_winrate": None, "depth": 0, "seldepth": 0, "nodes": 0,
                 "time_ms": 0.0, "nps": 0, "pv": [], "pv_pretty": [], "engine": engine_desc,
                 "tt": {"entries": None, "probes": 0, "hits": 0, "cutoffs": 0, "hit_rate": None},
                 "status": status.to_dict(), "rules": rules.to_dict(), "aborted": False}
        if not legal or status.is_over:
            return empty

        self._drain()
        self._set_multipv(1)                          # play mode: single best line
        self._send(self._position_cmd(pos))
        self._sync()
        self._send(self._go_command(time_limit_ms, max_depth, node_limit))

        last: dict | None = None
        best_text: str | None = None
        stopped = aborted = False
        deadline = None if not time_limit_ms else time.monotonic() + time_limit_ms / 1000.0 + 5.0
        while True:
            try:
                line = self.lines.get(timeout=0.05)
            except queue.Empty:
                if stop_event is not None and stop_event.is_set() and not stopped:
                    self._send("stop")
                    stopped = aborted = True
                if deadline is not None and time.monotonic() > deadline + 30:
                    raise UciEngineError("engine did not answer")
                continue
            if line is None:
                raise UciEngineError("engine process exited")
            if line.startswith("bestmove"):
                parts = line.split()
                best_text = parts[1] if len(parts) > 1 else None
                break
            if line.startswith("info ") and " pv " in line:
                parsed = self._parse_info(line)
                if parsed is not None and parsed.get("multipv", 1) == 1:
                    last = parsed
                    if on_info is not None:
                        on_info(parsed)
            if stop_event is not None and stop_event.is_set() and not stopped:
                self._send("stop")
                stopped = aborted = True

        elapsed_ms = (time.perf_counter() - start) * 1000.0
        m = None
        if best_text and best_text != "(none)":
            try:
                cand = uci_to_move(best_text)
            except (ValueError, IndexError):
                cand = None
            if cand in legal_set:
                m = cand
        if m is None and last is not None:            # fall back to the last PV head
            try:
                cand = uci_to_move(last["pv"][0])
                if cand in legal_set:
                    m = cand
            except (ValueError, IndexError):
                pass
        if m is None:
            return dict(empty, time_ms=round(elapsed_ms, 1), aborted=aborted)

        out = dict(empty)
        out.update({"move": move_to_str(m), "notation": describe_move(pos.board, m),
                    "time_ms": round(elapsed_ms, 1), "aborted": aborted, "pv": [move_to_str(m)],
                    "pv_pretty": [describe_move(pos.board, m)]})
        if last is not None:
            if last["score_kind"] == "mate":
                n = last["score_val"]
                out["mate"] = n
                out["score_cp"] = self._cp_or_mate_value(last)
                out["estimated_winrate"] = 1.0 if n > 0 else 0.0
            else:
                cp = last["score_val"]
                out["score_cp"] = cp
                out["estimated_winrate"] = round(self.calibrator.win_probability(cp), 4)
            out["score"] = round(out["score_cp"] / 100.0, 2)
            out["depth"] = last.get("depth", 0)
            out["seldepth"] = last.get("seldepth", 0)
            out["nodes"] = last.get("nodes", 0)
            out["nps"] = last.get("nps", 0)
            pv_moves, pv_pretty = self._convert_pv(pos, last["pv"])
            if pv_moves and pv_moves[0] == out["move"]:
                out["pv"], out["pv_pretty"] = pv_moves, pv_pretty
        return out
