"""Alpha-beta search.

Negamax with iterative deepening.  Each enhancement is switchable through
``SearchConfig`` so it can be validated independently:

    alpha_beta      pruning (off = plain negamax, for reference tests)
    tt              transposition table (Zobrist keyed)
    pvs             principal variation search (zero-window re-search)
    killers         killer-move ordering
    history         history heuristic ordering
    qsearch         quiescence search on captures (stand-pat is legal here
                    because 한수쉼 really is a legal move)
    check_extension extend by one ply when the side to move is in check
    aspiration      aspiration windows between iterations

Scores are centipawns from the side to move.  Mates are ``MATE - ply``.

Transposition-table key.  ``Position.hash`` is the board identity used for
repetition detection.  The TT is keyed by ``Searcher.tt_key`` = board hash
XOR pass state XOR 빅장 state XOR move-limit proximity, because the same board
scores differently depending on those (e.g. with one pass pending the side to
move can claim the draw by passing; see ``tests/test_search.py::
test_tt_key_separates_pass_state``).  Repetition history is *not* part of the
key (graph-history interaction is accepted, as in every mainstream engine):
a node that *is* a repetition returns before the TT is consulted.
"""
from __future__ import annotations

import itertools
import threading
import time
from dataclasses import dataclass, field

from .board import CANNON, CHARIOT, GUARD, HORSE, KING, NO_MOVE, NUM_SQUARES, PASS_MOVE, Position
from .evaluation import EvalConfig, evaluate
from .hashing import ZOBRIST_BIKJANG, ZOBRIST_PASS_PENDING, ZOBRIST_PLIES_LEFT
from .movegen import generate_legal_moves, generate_pseudo_moves
from .rules import RuleConfig, bikjang_unresolved, points_winner

INF = 1_000_000
MATE = 100_000
MATE_BOUND = MATE - 1_000
POINTS_WIN = 30_000      # game decided on material points (bikjang, "points" rule); below MATE_BOUND
MAX_PLY = 96
DRAW_SCORE = 0

TT_EXACT, TT_LOWER, TT_UPPER = 0, 1, 2

# victim value for MVV-LVA ordering (indexed by piece type)
_ORDER_VALUE = [0, 5000, 300, 1300, 700, 500, 300, 200]


class SearchAborted(Exception):
    pass


@dataclass
class SearchConfig:
    alpha_beta: bool = True
    tt: bool = True
    pvs: bool = True
    killers: bool = True
    history: bool = True
    qsearch: bool = True
    qsearch_max_depth: int = 6
    check_extension: bool = True
    null_move: bool = True        # 한수쉼 is a legal move, so this is a reduced-depth search of a real move
    null_move_reduction: int = 2
    lmr: bool = True              # late move reductions for quiet moves
    aspiration: bool = True
    aspiration_window: int = 40
    tt_max_entries: int = 1_000_000     # ~210 bytes per entry in CPython -> about 210 MB at the cap
    max_plies_draw: int = 400
    rules: RuleConfig = field(default_factory=RuleConfig)


@dataclass
class SearchResult:
    best_move: int
    score: int
    depth: int
    nodes: int
    time_ms: float
    pv: list[int]

    @property
    def nps(self) -> float:
        return self.nodes / (self.time_ms / 1000.0) if self.time_ms > 0 else 0.0


class Searcher:
    def __init__(self, config: SearchConfig | None = None, eval_config: EvalConfig | None = None) -> None:
        self.cfg = config or SearchConfig()
        self.eval_cfg = eval_config
        self.tt: dict[int, tuple[int, int, int, int]] = {}
        self.killers: list[list[int]] = [[NO_MOVE, NO_MOVE] for _ in range(MAX_PLY + 2)]
        self.history_table: list[int] = [0] * (PASS_MOVE + 1)
        self.nodes = 0
        self.qnodes = 0
        self.tt_probes = 0
        self.tt_hits = 0
        self.tt_cutoffs = 0
        self.tt_evictions = 0
        self.deadline: float | None = None
        self.stop_event: threading.Event | None = None
        self.node_limit: int | None = None
        self.seldepth = 0

    # ------------------------------------------------------------------ housekeeping
    def new_search(self, deadline: float | None, stop_event: threading.Event | None,
                   node_limit: int | None = None) -> None:
        self.nodes = 0
        self.qnodes = 0
        self.tt_probes = 0
        self.tt_hits = 0
        self.tt_cutoffs = 0
        self.seldepth = 0
        self.deadline = deadline
        self.stop_event = stop_event
        self.node_limit = node_limit
        for k in self.killers:
            k[0] = k[1] = NO_MOVE
        self.history_table = [0] * (PASS_MOVE + 1)
        if len(self.tt) > self.cfg.tt_max_entries:
            self.tt.clear()

    def clear_tt(self) -> None:
        self.tt.clear()

    def tt_stats(self) -> dict:
        return {
            "entries": len(self.tt),
            "probes": self.tt_probes,
            "hits": self.tt_hits,
            "cutoffs": self.tt_cutoffs,
            "evictions": self.tt_evictions,
            "max_entries": self.cfg.tt_max_entries,
            "hit_rate": (self.tt_hits / self.tt_probes) if self.tt_probes else 0.0,
        }

    def _check_abort(self) -> None:
        if self.stop_event is not None and self.stop_event.is_set():
            raise SearchAborted()
        if self.deadline is not None and time.perf_counter() >= self.deadline:
            raise SearchAborted()
        if self.node_limit is not None and self.nodes >= self.node_limit:
            raise SearchAborted()

    # ------------------------------------------------------------------ TT key
    def tt_key(self, pos: Position) -> int:
        """Search key: board hash plus every state component that changes the game result.

        * ``passes == 1``: the opponent just passed, so passing now ends the game.
        * ``bikjang`` (when the rule is on): the legal move set / next-move ending differ.
        * plies left before the move limit, once the limit is within horizon reach.
        """
        key = pos.hash
        if pos.passes:
            key ^= ZOBRIST_PASS_PENDING
        if pos.bikjang and self.cfg.rules.bikjang != "off":
            key ^= ZOBRIST_BIKJANG
        left = min(self.cfg.max_plies_draw, self.cfg.rules.max_plies) - pos.ply
        if left < len(ZOBRIST_PLIES_LEFT):
            key ^= ZOBRIST_PLIES_LEFT[max(left, 0)]
        return key

    # ------------------------------------------------------------------ ordering
    def _order_moves(self, pos: Position, moves: list[int], tt_move: int, ply: int) -> list[int]:
        board = pos.board
        cfg = self.cfg
        k0, k1 = self.killers[ply] if cfg.killers else (NO_MOVE, NO_MOVE)
        hist = self.history_table
        use_hist = cfg.history

        def key(m: int) -> int:
            if m == tt_move:
                return 10_000_000
            if m == PASS_MOVE:
                return -10_000_000
            frm, to = divmod(m, NUM_SQUARES)
            victim = board[to]
            if victim:
                return 1_000_000 + _ORDER_VALUE[victim & 7] * 8 - _ORDER_VALUE[board[frm] & 7] // 8
            if m == k0:
                return 900_000
            if m == k1:
                return 800_000
            return hist[m] if use_hist else 0

        moves.sort(key=key, reverse=True)
        return moves

    @staticmethod
    def _order_captures(pos: Position, moves: list[int]) -> list[int]:
        board = pos.board

        def key(m: int) -> int:
            frm, to = divmod(m, NUM_SQUARES)
            return _ORDER_VALUE[board[to] & 7] * 8 - _ORDER_VALUE[board[frm] & 7] // 8

        moves.sort(key=key, reverse=True)
        return moves

    # ------------------------------------------------------------------ rule endings
    def _rule_score(self, pos: Position) -> int | None:
        """Score of a position ended by rule (from the side to move), or None if the game goes on.

        Repetition is scored as a draw already at the second occurrence so
        the engine steers away from (or into) repetitions one cycle early.
        """
        rules = self.cfg.rules
        if pos.passes >= 2:
            if rules.bikjang == "forced":
                return self._points_score(pos)
            return DRAW_SCORE
        if bikjang_unresolved(pos):
            if rules.bikjang == "draw":
                return DRAW_SCORE
            if rules.bikjang in ("points", "forced"):
                return self._points_score(pos)
        if rules.repetition == "draw" and pos.is_repetition():
            return DRAW_SCORE
        if pos.ply >= min(self.cfg.max_plies_draw, rules.max_plies):
            return DRAW_SCORE
        return None

    @staticmethod
    def _points_score(pos: Position) -> int:
        w = points_winner(pos)
        if w is None:
            return DRAW_SCORE
        return POINTS_WIN if w == pos.side else -POINTS_WIN

    def _draw_by_rule(self, pos: Position) -> bool:
        return self._rule_score(pos) is not None

    # ------------------------------------------------------------------ core
    def negamax(self, pos: Position, depth: int, alpha: int, beta: int, ply: int) -> int:
        self.nodes += 1
        if (self.nodes & 2047) == 0:
            self._check_abort()
        if ply > self.seldepth:
            self.seldepth = ply

        if ply > 0:
            ended = self._rule_score(pos)
            if ended is not None:
                return ended
        if ply >= MAX_PLY:
            return evaluate(pos, self.eval_cfg)

        cfg = self.cfg
        in_check = pos.in_check()
        if in_check and cfg.check_extension:
            depth += 1

        if depth <= 0:
            if cfg.qsearch:
                return self.qsearch(pos, alpha, beta, ply, 0)
            return evaluate(pos, self.eval_cfg)

        alpha_orig = alpha
        tt_move = NO_MOVE
        key = self.tt_key(pos)
        if cfg.tt:
            self.tt_probes += 1
            entry = self.tt.get(key)
            if entry is not None:
                self.tt_hits += 1
                e_depth, e_flag, e_score, e_move = entry
                tt_move = e_move
                if e_depth >= depth and ply > 0:
                    score = e_score
                    if score > MATE_BOUND:
                        score -= ply
                    elif score < -MATE_BOUND:
                        score += ply
                    if e_flag == TT_EXACT:
                        self.tt_cutoffs += 1
                        return score
                    if e_flag == TT_LOWER and score >= beta:
                        self.tt_cutoffs += 1
                        return score
                    if e_flag == TT_UPPER and score <= alpha:
                        self.tt_cutoffs += 1
                        return score

        must_resolve = pos.forced_bikjang and pos.bikjang and not in_check
        is_pv = beta - alpha > 1

        # Null-move pruning.  Passing is a genuine legal move in janggi, so "doing nothing"
        # is searched as a real move at reduced depth: if even that beats beta, this node does.
        if (cfg.null_move and cfg.alpha_beta and not is_pv and not in_check and not must_resolve
                and depth >= 3 and ply > 0 and pos.passes == 0
                and evaluate(pos, self.eval_cfg) >= beta):
            r = cfg.null_move_reduction + (1 if depth >= 6 else 0)
            pos.make_move(PASS_MOVE)
            null_score = -self.negamax(pos, depth - 1 - r, -beta, -beta + 1, ply + 1)
            pos.unmake_move()
            if null_score >= beta and abs(null_score) < POINTS_WIN - 1_000:
                return beta

        moves = generate_pseudo_moves(pos)
        if not in_check:
            moves.append(PASS_MOVE)
        moves = self._order_moves(pos, moves, tt_move, ply)

        side = pos.side
        best = -INF
        best_move = NO_MOVE
        legal = 0
        use_pvs = cfg.pvs and cfg.alpha_beta
        use_lmr = cfg.lmr and cfg.alpha_beta and depth >= 3 and not in_check
        board = pos.board
        for m in moves:
            is_quiet = m == PASS_MOVE or not board[m % NUM_SQUARES]
            pos.make_move(m)
            if m != PASS_MOVE and pos.attacked_by(pos.king_sq[side], side ^ 1):
                pos.unmake_move()
                continue
            if must_resolve and m != PASS_MOVE and pos.kings_facing():
                pos.unmake_move()          # not a legal answer to a 빅장 under the forced rule
                continue
            legal += 1
            reduce = 1 if (use_lmr and legal > 3 and is_quiet and not pos.in_check()) else 0
            if legal > 1 and (use_pvs or reduce):
                score = -self.negamax(pos, depth - 1 - reduce, -alpha - 1, -alpha, ply + 1)
                if score > alpha and (reduce or score < beta):
                    score = -self.negamax(pos, depth - 1, -beta, -alpha, ply + 1)
            else:
                score = -self.negamax(pos, depth - 1, -beta, -alpha, ply + 1)
            pos.unmake_move()

            if score > best:
                best = score
                best_move = m
                if score > alpha:
                    alpha = score
                    if cfg.alpha_beta and alpha >= beta:
                        if m != PASS_MOVE and not pos.board[m % NUM_SQUARES]:
                            if cfg.killers:
                                k = self.killers[ply]
                                if k[0] != m:
                                    k[1] = k[0]
                                    k[0] = m
                            if cfg.history:
                                self.history_table[m] += depth * depth
                        break

        if legal == 0:
            # with 한수쉼 available this can only happen when in check: mate
            return -MATE + ply if in_check else DRAW_SCORE

        if cfg.tt:
            if best <= alpha_orig:
                flag = TT_UPPER
            elif best >= beta:
                flag = TT_LOWER
            else:
                flag = TT_EXACT
            stored = best
            if stored > MATE_BOUND:
                stored += ply
            elif stored < -MATE_BOUND:
                stored -= ply
            if key not in self.tt and len(self.tt) >= cfg.tt_max_entries:
                self._tt_evict()
            self.tt[key] = (depth, flag, stored, best_move)
        return best

    def _tt_evict(self) -> None:
        """Drop the oldest quarter of the table so an unlimited search stays within memory.

        dicts keep insertion order, so the first keys are the entries first stored
        (re-stores keep their original slot), a cheap approximation of age.
        """
        n = max(1, len(self.tt) // 4)
        for key in list(itertools.islice(self.tt, n)):
            del self.tt[key]
        self.tt_evictions += 1

    def qsearch(self, pos: Position, alpha: int, beta: int, ply: int, qdepth: int) -> int:
        self.nodes += 1
        self.qnodes += 1
        if (self.nodes & 2047) == 0:
            self._check_abort()
        if ply > self.seldepth:
            self.seldepth = ply
        if ply >= MAX_PLY:
            return evaluate(pos, self.eval_cfg)
        # captures are irreversible, so only the bikjang ending can arise inside qsearch
        if pos.bikjang and self.cfg.rules.bikjang != "off" and bikjang_unresolved(pos):
            ended = self._rule_score(pos)
            if ended is not None:
                return ended

        in_check = pos.in_check()
        if in_check:
            if qdepth >= self.cfg.qsearch_max_depth:
                return evaluate(pos, self.eval_cfg)
            moves = generate_pseudo_moves(pos)
            best = -INF
        else:
            stand = evaluate(pos, self.eval_cfg)
            if stand >= beta:
                return stand
            if qdepth >= self.cfg.qsearch_max_depth:
                return stand
            if stand > alpha:
                alpha = stand
            best = stand
            moves = generate_pseudo_moves(pos, captures_only=True)
        moves = self._order_captures(pos, moves)

        side = pos.side
        legal = 0
        must_resolve = pos.forced_bikjang and pos.bikjang and not in_check
        for m in moves:
            pos.make_move(m)
            if pos.attacked_by(pos.king_sq[side], side ^ 1) or (must_resolve and pos.kings_facing()):
                pos.unmake_move()
                continue
            legal += 1
            score = -self.qsearch(pos, -beta, -alpha, ply + 1, qdepth + 1)
            pos.unmake_move()
            if score > best:
                best = score
                if score > alpha:
                    alpha = score
                    if alpha >= beta:
                        break
        if in_check and legal == 0:
            return -MATE + ply
        return best

    # ------------------------------------------------------------------ PV
    def extract_pv(self, pos: Position, max_len: int = 16) -> list[int]:
        pv: list[int] = []
        seen: set[int] = set()
        while len(pv) < max_len:
            key = self.tt_key(pos)
            entry = self.tt.get(key)
            if entry is None or entry[3] == NO_MOVE or key in seen:
                break
            m = entry[3]
            if m not in generate_legal_moves(pos):
                break
            seen.add(key)
            pos.make_move(m)
            pv.append(m)
        for _ in pv:
            pos.unmake_move()
        return pv

    # ------------------------------------------------------------------ driver
    def search(self, pos: Position, max_depth: int = 64, time_limit_ms: float | None = None,
               stop_event: threading.Event | None = None, node_limit: int | None = None,
               on_iteration=None) -> SearchResult:
        """Standard single-best-move iterative deepening (used for play/tests)."""
        start = time.perf_counter()
        deadline = start + time_limit_ms / 1000.0 if time_limit_ms else None
        self.new_search(deadline, stop_event, node_limit)
        root_moves = generate_legal_moves(pos)
        if not root_moves:
            return SearchResult(NO_MOVE, -MATE if pos.in_check() else DRAW_SCORE, 0, 0, 0.0, [])

        best_move = root_moves[0]
        best_score = 0
        completed = 0
        pv: list[int] = []
        base_depth = pos.stack_depth()
        prev_score = 0
        for depth in range(1, max_depth + 1):
            window = self.cfg.aspiration_window
            alpha, beta = (-INF, INF)
            if self.cfg.aspiration and depth > 1 and abs(prev_score) < MATE_BOUND:
                alpha, beta = prev_score - window, prev_score + window
            try:
                while True:
                    score, move = self._root(pos, depth, alpha, beta, best_move)
                    if score <= alpha:
                        alpha = max(-INF, alpha - window * 4)
                        window *= 4
                    elif score >= beta:
                        beta = min(INF, beta + window * 4)
                        window *= 4
                    else:
                        break
            except SearchAborted:
                pos.unwind_to(base_depth)
                break
            best_score, best_move, completed, prev_score = score, move, depth, score
            pv = [best_move] + self._pv_after(pos, best_move)
            if on_iteration:
                on_iteration(depth, best_score, best_move, pv, self.nodes)
            if abs(best_score) > MATE_BOUND:
                break
            if deadline is not None and time.perf_counter() - start > (deadline - start) * 0.5:
                break
        elapsed = (time.perf_counter() - start) * 1000.0
        return SearchResult(best_move, best_score, completed, self.nodes, elapsed, pv)

    def _root(self, pos: Position, depth: int, alpha: int, beta: int, first: int) -> tuple[int, int]:
        moves = generate_legal_moves(pos)
        moves = self._order_moves(pos, moves, first, 0)
        best = -INF
        best_move = moves[0]
        for i, m in enumerate(moves):
            pos.make_move(m)
            if self.cfg.pvs and i > 0:
                score = -self.negamax(pos, depth - 1, -alpha - 1, -alpha, 1)
                if alpha < score < beta:
                    score = -self.negamax(pos, depth - 1, -beta, -alpha, 1)
            else:
                score = -self.negamax(pos, depth - 1, -beta, -alpha, 1)
            pos.unmake_move()
            if score > best:
                best = score
                best_move = m
                if score > alpha:
                    alpha = score
                    if self.cfg.alpha_beta and alpha >= beta:
                        break
        return best, best_move

    def _pv_after(self, pos: Position, m: int, max_len: int = 15) -> list[int]:
        pos.make_move(m)
        try:
            return self.extract_pv(pos, max_len)
        finally:
            pos.unmake_move()


def is_mate_score(score: int) -> bool:
    return abs(score) > MATE_BOUND


def is_points_score(score: int) -> bool:
    """Decided on material points (bikjang under the "points" rule)."""
    return POINTS_WIN - 1_000 <= abs(score) <= MATE_BOUND


def mate_in(score: int) -> int | None:
    """Positive n: side to move mates in n moves; negative: gets mated."""
    if score > MATE_BOUND:
        return (MATE - score + 1) // 2
    if score < -MATE_BOUND:
        return -max(1, (MATE + score + 1) // 2)
    return None
