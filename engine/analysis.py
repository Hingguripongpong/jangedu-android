"""Root analysis: an exact score for *every* legal move.

Plain alpha-beta only yields an exact score for the best root move; the rest
fail low against alpha.  For a Go-style analysis mode we instead treat each
root move as its own aspiration search:

    for depth in 1..N:
        for each root move m (best-first from the previous iteration):
            score[m] = -negamax(P_m, depth-1, window around score[m] at depth-1)
            widen and re-search on fail-low / fail-high

The transposition table is shared by all root moves, so later moves reuse
work done by earlier ones.  Per-move node counts, depth, PV and the
estimated win rate are kept in ``MoveAnalysis`` and streamed to a callback
after every completed move so a UI can update while the search deepens.
"""
from __future__ import annotations

import threading
import time
from dataclasses import dataclass, field

from .board import NUM_SQUARES, PASS_MOVE, Position, piece_type
from .calibration import Calibrator
from .evaluation import EvalConfig
from .movegen import generate_legal_moves, gives_check
from .notation import SIDE_KOREAN, SIDE_NAME, describe_move, move_to_str, piece_hanja, square_to_str
from .rules import game_status
from .search import INF, MATE_BOUND, SearchAborted, SearchConfig, Searcher, is_points_score, mate_in


@dataclass
class MoveAnalysis:
    move: int
    score: int | None = None      # centipawns, side-to-move perspective
    depth: int = 0
    nodes: int = 0
    pv: list[int] = field(default_factory=list)
    gives_check: bool = False
    capture: int = 0
    time_ms: float = 0.0

    def to_dict(self, pos: Position, calib: Calibrator, rank: int) -> dict:
        board = pos.board
        m = self.move
        score = self.score if self.score is not None else 0
        mate = mate_in(score) if self.score is not None else None
        decisive = None            # "mate" | "points" | None  (how the line ends by force)
        if self.score is None:
            winrate = None
            wdl = None
        elif score > MATE_BOUND:
            winrate, wdl, decisive = 1.0, (1.0, 0.0, 0.0), "mate"
        elif score < -MATE_BOUND:
            winrate, wdl, decisive = 0.0, (0.0, 0.0, 1.0), "mate"
        elif is_points_score(score):
            decisive = "points"
            winrate, wdl = (1.0, (1.0, 0.0, 0.0)) if score > 0 else (0.0, (0.0, 0.0, 1.0))
        else:
            winrate = calib.win_probability(score)
            wdl = calib.wdl(score)
        pv_strs = []
        pv_pretty = []
        pos_copy_needed = bool(self.pv)
        if pos_copy_needed:
            for pm in self.pv:
                pv_strs.append(move_to_str(pm))
                pv_pretty.append(describe_move(pos.board, pm))
                pos.make_move(pm)
            for _ in self.pv:
                pos.unmake_move()
        d = {
            "rank": rank,
            "move": move_to_str(m),
            "notation": describe_move(board, m),
            "from": None if m == PASS_MOVE else square_to_str(m // NUM_SQUARES),
            "to": None if m == PASS_MOVE else square_to_str(m % NUM_SQUARES),
            "from_sq": None if m == PASS_MOVE else m // NUM_SQUARES,
            "to_sq": None if m == PASS_MOVE else m % NUM_SQUARES,
            "piece": None if m == PASS_MOVE else piece_hanja(board[m // NUM_SQUARES]),
            "capture": piece_hanja(self.capture) if self.capture else None,
            "gives_check": self.gives_check,
            "score_cp": self.score,
            "score": None if self.score is None else round(score / 100.0, 2),
            "mate": mate,
            "decisive": decisive,
            "estimated_winrate": None if winrate is None else round(winrate, 4),
            "wdl": None if wdl is None else [round(x, 4) for x in wdl],
            "depth": self.depth,
            "nodes": self.nodes,
            "time_ms": round(self.time_ms, 1),
            "pv": pv_strs,
            "pv_pretty": pv_pretty,
        }
        return d


class Analyzer:
    def __init__(self, search_config: SearchConfig | None = None, eval_config: EvalConfig | None = None,
                 calibrator: Calibrator | None = None, max_pv_len: int = 12) -> None:
        self.searcher = Searcher(search_config, eval_config)
        self.calibrator = calibrator or Calibrator()
        self.max_pv_len = max_pv_len
        # live state, readable from another thread while analyze() runs (plain attribute reads)
        self.live: dict = {"running": False, "depth_in_progress": 0, "completed_depth": 0,
                           "moves_done_at_depth": 0, "move_count": 0, "started_at": 0.0}

    def analyze(self, pos: Position, time_limit_ms: float | None = 1000, max_depth: int = 64,
                stop_event: threading.Event | None = None, progress=None,
                progress_interval_ms: float = 100.0, node_limit: int | None = None) -> dict:
        """Analyse every legal move of ``pos``.

        ``progress(result_dict, final=False)`` is invoked (throttled) after
        root moves complete and after every finished depth.  Returns the
        final result dict.
        """
        start = time.perf_counter()
        deadline = start + time_limit_ms / 1000.0 if time_limit_ms else None
        s = self.searcher
        s.new_search(deadline, stop_event, node_limit)

        legal = generate_legal_moves(pos)
        status = game_status(pos, legal, rules=s.cfg.rules)
        stats: dict[int, MoveAnalysis] = {}
        board = pos.board
        for m in legal:
            ma = MoveAnalysis(move=m)
            if m != PASS_MOVE:
                ma.capture = board[m % NUM_SQUARES]
            ma.gives_check = gives_check(pos, m)
            stats[m] = ma

        base_depth = pos.stack_depth()
        completed_depth = 0
        aborted = False
        last_emit = 0.0
        cfg = s.cfg

        def emit(final: bool = False, force: bool = False) -> None:
            nonlocal last_emit
            if progress is None:
                return
            now = time.perf_counter()
            if not final and not force and (now - last_emit) * 1000.0 < progress_interval_ms:
                return
            last_emit = now
            progress(self._result(pos, stats, status, completed_depth, start, final, aborted), final)

        if legal and not status.is_over:
            self.live.update(running=True, depth_in_progress=0, completed_depth=0, moves_done_at_depth=0,
                             move_count=len(stats), started_at=start)
            for depth in range(1, max_depth + 1):
                order = sorted(stats.values(),
                               key=lambda a: (-(a.score if a.score is not None else -INF), a.move))
                depth_done = True
                self.live.update(depth_in_progress=depth, moves_done_at_depth=0)
                for ma in order:
                    m = ma.move
                    prev = ma.score
                    window = cfg.aspiration_window
                    if cfg.aspiration and prev is not None and depth > 2 and abs(prev) < MATE_BOUND:
                        alpha, beta = prev - window, prev + window
                    else:
                        alpha, beta = -INF, INF
                    nodes_before = s.nodes
                    t0 = time.perf_counter()
                    try:
                        pos.make_move(m)
                        while True:
                            score = -s.negamax(pos, depth - 1, -beta, -alpha, 1)
                            if score <= alpha and alpha > -INF:
                                alpha = max(-INF, alpha - window * 4)
                                window *= 4
                            elif score >= beta and beta < INF:
                                beta = min(INF, beta + window * 4)
                                window *= 4
                            else:
                                break
                        pv = s.extract_pv(pos, self.max_pv_len)
                        pos.unmake_move()
                    except SearchAborted:
                        pos.unwind_to(base_depth)
                        aborted = True
                        depth_done = False
                        break
                    ma.score = score
                    ma.depth = depth
                    ma.nodes += s.nodes - nodes_before
                    ma.time_ms += (time.perf_counter() - t0) * 1000.0
                    ma.pv = [m] + pv
                    self.live["moves_done_at_depth"] += 1
                    emit()
                if not depth_done:
                    break
                completed_depth = depth
                self.live["completed_depth"] = depth
                emit(force=True)
                if all(a.score is not None and (abs(a.score) > MATE_BOUND or is_points_score(a.score))
                       for a in stats.values()):
                    break
                if deadline is not None and (time.perf_counter() - start) > (deadline - start) * 0.5:
                    break
                if node_limit is not None and s.nodes >= node_limit:
                    break

        self.live["running"] = False
        result = self._result(pos, stats, status, completed_depth, start, True, aborted)
        if progress is not None:
            progress(result, True)
        return result

    def _result(self, pos: Position, stats: dict[int, MoveAnalysis], status, completed_depth: int,
                start: float, final: bool, aborted: bool) -> dict:
        s = self.searcher
        elapsed_ms = (time.perf_counter() - start) * 1000.0
        ordered = sorted(stats.values(),
                         key=lambda a: (-(a.score if a.score is not None else -INF), a.move))
        moves = [ma.to_dict(pos, self.calibrator, i + 1) for i, ma in enumerate(ordered)]
        return {
            "final": final,
            "aborted": aborted,
            "fen": pos.to_fen(),
            "side": SIDE_NAME[pos.side],
            "side_korean": SIDE_KOREAN[pos.side],
            "status": status.to_dict(),
            "depth": completed_depth,
            "seldepth": s.seldepth,
            "nodes": s.nodes,
            "qnodes": s.qnodes,
            "time_ms": round(elapsed_ms, 1),
            "nps": round(s.nodes / (elapsed_ms / 1000.0)) if elapsed_ms > 0 else 0,
            "tt": s.tt_stats(),
            "calibration": self.calibrator.describe(),
            "rules": s.cfg.rules.to_dict(),
            "engine": {"name": "janggi-ai python", "backend": "python"},
            "moves": moves,
        }


def format_analysis(result: dict, top_n: int | None = None) -> str:
    lines = [
        f"현재 차례: {result['side_korean']}   depth {result['depth']}  nodes {result['nodes']:,}"
        f"  {result['nps']:,} nps  {result['time_ms']:.0f} ms"
    ]
    label = result["calibration"]["label"]
    for md in result["moves"][: top_n or None]:
        if md["score"] is None:
            continue
        wr = f"{md['estimated_winrate'] * 100:5.1f}%"
        if md["mate"]:
            ev = f"M{md['mate']}"
        elif md.get("decisive") == "points":
            ev = "점수승" if md["score_cp"] > 0 else "점수패"
        else:
            ev = f"{md['score']:+.2f}"
        lines.append(f"{md['rank']:>2}. {md['notation']:<10} {label} {wr}  eval {ev:>6}"
                     f"  depth {md['depth']:>2}  nodes {md['nodes']:>9,}")
        if md["pv_pretty"]:
            lines.append("     PV: " + " ".join(md["pv_pretty"][:8]))
    return "\n".join(lines)
