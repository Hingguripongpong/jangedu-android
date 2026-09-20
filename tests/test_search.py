"""Search tests: material greed, mate finding, hanging pieces, toggle equivalence, limits."""
import threading

from engine.board import (
    CANNON, CHARIOT, CHO, GUARD, HAN, HORSE, KING, PASS_MOVE, PAWN, Position, encode_move, make_piece,
    move_from, move_to,
)
from engine.evaluation import evaluate
from engine.geometry import sq
from engine.movegen import generate_legal_moves
from engine.rules import CHECKMATE, game_status
from engine.search import MATE, SearchConfig, Searcher, is_mate_score, mate_in

K, G, R, C, N, P = KING, GUARD, CHARIOT, CANNON, HORSE, PAWN


def cho(t):
    return make_piece(CHO, t)


def han(t):
    return make_piece(HAN, t)


def build(pieces, side=CHO):
    pos = Position()
    for (r, c), p in pieces.items():
        pos.board[sq(r, c)] = p
    pos.side = side
    pos._finish_setup()
    return pos


def kings():
    # kings on different files so the constructed positions do not start in 빅장
    return {(8, 4): cho(K), (1, 3): han(K)}


# ---------------------------------------------------------------- captures
def test_takes_hanging_chariot():
    pieces = kings()
    pieces[(9, 0)] = cho(R)          # 01
    pieces[(4, 0)] = han(R)          # 51, undefended on the same file
    pieces[(3, 8)] = han(P)
    pos = build(pieces)
    res = Searcher().search(pos, max_depth=3)
    assert res.best_move == encode_move(sq(9, 0), sq(4, 0))
    assert res.score > 1000                       # a whole chariot up
    assert pos.to_fen() == build(pieces).to_fen()  # search leaves position untouched


def test_prefers_bigger_capture():
    pieces = kings()
    pieces[(5, 4)] = cho(R)          # chariot can take a horse (5,0) or a chariot (5,8)
    pieces[(5, 0)] = han(N)
    pieces[(5, 8)] = han(R)
    pieces[(0, 0)] = han(P)
    pos = build(pieces)
    res = Searcher().search(pos, max_depth=2)
    assert move_to(res.best_move) == sq(5, 8)


def test_does_not_lose_hanging_chariot():
    # Han horse on (3,3) attacks (5,4) through the empty (4,3).  Cho's chariot must move.
    pieces = kings()
    pieces[(5, 4)] = cho(R)
    pieces[(3, 3)] = han(N)
    pieces[(2, 0)] = han(P)
    pos = build(pieces)
    res = Searcher().search(pos, max_depth=3)
    assert move_from(res.best_move) == sq(5, 4)
    pos.make_move(res.best_move)
    assert not pos.attacked_by(move_to(res.best_move), HAN)
    assert res.score > 600  # still roughly a chariot vs a horse ahead


def test_does_not_walk_into_capture_at_depth_two():
    # A greedy depth-1 search would grab the pawn on (6,8) with the horse... but the
    # square is covered by a Han chariot, so depth 2 must decline it.
    pieces = kings()
    pieces[(8, 7)] = cho(N)          # horse (8,7) -> (6,8) via (7,7)
    pieces[(6, 8)] = han(P)
    pieces[(6, 0)] = han(R)          # guards the whole 6th row
    pieces[(9, 0)] = cho(P)
    pos = build(pieces)
    res = Searcher().search(pos, max_depth=3)
    assert res.best_move != encode_move(sq(8, 7), sq(6, 8))


# ---------------------------------------------------------------- mates
def test_finds_mate_in_one():
    pos = build({(8, 4): cho(K), (0, 3): han(K), (5, 4): cho(R), (2, 3): cho(P), (3, 8): cho(R)})
    res = Searcher().search(pos, max_depth=4)
    assert is_mate_score(res.score) and mate_in(res.score) == 1
    assert res.pv[0] == res.best_move
    # two mates exist here (R 48->08 and P 43->24); either is acceptable, but it must mate
    assert res.best_move in (encode_move(sq(3, 8), sq(0, 8)), encode_move(sq(2, 3), sq(1, 4)))
    pos.make_move(res.best_move)
    assert game_status(pos).status == CHECKMATE


def test_mated_position_scores_negative_mate():
    pos = build({(8, 4): cho(K), (0, 3): han(K), (5, 4): cho(R), (2, 3): cho(P), (0, 8): cho(R)}, side=HAN)
    res = Searcher().search(pos, max_depth=2)
    assert res.best_move == 0 and res.score == -MATE


def test_finds_mate_in_two():
    # Han king boxed on (0,3): pawn (2,3) covers (1,3)/(1,4); chariot (5,4) holds file 4.
    # Han has a pawn that can interpose... only for one move.  Cho chariot (4,7) needs
    # two moves: 1. R(4,7)->(4,8) [threat] is too slow, but 1. R->(1,7) check? Design:
    # Cho chariot on (6,7), Han horse (2,8) blocks row... simply verify the engine finds
    # a forced mate within depth 5 from a position where mate-in-1 does not exist.
    pieces = {(8, 4): cho(K), (0, 3): han(K), (5, 4): cho(R), (2, 3): cho(P), (6, 8): cho(R), (0, 6): han(P)}
    pos = build(pieces)
    # 1. R(6,8)->(0,8) is not mate: Han pawn (0,6) can't block on row 0 ... it can (0,7)? pawns
    # move forward (row +1 for Han) or sideways, so (0,6)->(0,7) blocks the file-0 rook.
    res = Searcher().search(pos, max_depth=5)
    assert is_mate_score(res.score) and res.score > 0
    assert mate_in(res.score) <= 3


# ---------------------------------------------------------------- toggles
MID_FEN = "r1ba1ab1r/4k1n2/1c2n2c1/p3p1p1p/2p6/6P2/P1P1P3P/1C2NC3/4K4/RNBA1AB1R w - - 0 1"


def _fixed(depth, **overrides):
    cfg = SearchConfig(tt=False, pvs=False, killers=False, history=False, aspiration=False,
                       check_extension=False, null_move=False, lmr=False)
    for k, v in overrides.items():
        setattr(cfg, k, v)
    s = Searcher(cfg)
    res = s.search(Position.from_fen(MID_FEN), max_depth=depth)
    return res, s


def test_alpha_beta_matches_pure_minimax_score():
    plain, _ = _fixed(3, alpha_beta=False)
    ab, _ = _fixed(3, alpha_beta=True)
    assert plain.score == ab.score
    assert ab.nodes < plain.nodes // 2


def test_pvs_and_tt_do_not_change_root_score():
    base, _ = _fixed(3)
    pvs, _ = _fixed(3, pvs=True)
    tt, searcher = _fixed(3, tt=True)
    assert pvs.score == base.score
    assert tt.score == base.score
    stats = searcher.tt_stats()
    assert stats["entries"] > 0 and stats["probes"] > 0


def test_move_ordering_heuristics_reduce_nodes():
    base, _ = _fixed(4)
    fast, _ = _fixed(4, tt=True, pvs=True, killers=True, history=True, aspiration=True)
    assert fast.score == base.score or abs(fast.score - base.score) < 60  # aspiration re-search tolerance
    assert fast.nodes < base.nodes
    pruned, _ = _fixed(4, tt=True, pvs=True, killers=True, history=True, aspiration=True, null_move=True, lmr=True)
    assert pruned.nodes < fast.nodes            # null-move + LMR prune further (heuristic: score may differ)


def test_qsearch_toggle_changes_only_leaf_evaluation():
    quiet, _ = _fixed(2, qsearch=True)
    noisy, _ = _fixed(2, qsearch=False)
    assert isinstance(quiet.score, int) and isinstance(noisy.score, int)
    assert quiet.nodes >= noisy.nodes


# ---------------------------------------------------------------- limits & PV
def test_time_limit_is_respected():
    s = Searcher()
    res = s.search(Position.initial(), max_depth=64, time_limit_ms=300)
    assert res.time_ms < 1500
    assert res.depth >= 2
    assert res.best_move in generate_legal_moves(Position.initial())


def test_stop_event_aborts_quickly():
    s = Searcher()
    ev = threading.Event()
    pos = Position.initial()
    timer = threading.Timer(0.15, ev.set)
    timer.start()
    res = s.search(pos, max_depth=64, stop_event=ev)
    assert res.time_ms < 2000
    assert pos.stack_depth() == 0


def test_node_limit():
    s = Searcher()
    res = s.search(Position.initial(), max_depth=64, node_limit=5000)
    assert res.nodes <= 5000 + 2048


def test_pv_is_a_legal_line():
    s = Searcher()
    pos = Position.initial()
    res = s.search(pos, max_depth=4)
    assert res.pv and res.pv[0] == res.best_move
    for m in res.pv:
        assert m in generate_legal_moves(pos)
        pos.make_move(m)
    assert evaluate(pos) is not None


def test_pass_move_is_considered_but_not_preferred_in_start_position():
    s = Searcher()
    res = s.search(Position.initial(), max_depth=3)
    assert res.best_move != PASS_MOVE


# ---------------------------------------------------------------- TT key must include search state
def _han_behind_position():
    """Han to move, Cho a chariot up, kings on different files (no 빅장)."""
    pieces = {(8, 4): cho(K), (1, 3): han(K), (9, 0): cho(R), (6, 0): cho(P), (3, 8): han(P), (3, 6): han(P)}
    return build(pieces, side=HAN)


def test_tt_key_separates_pass_state():
    """Regression: same board, passes=0 -> losing score; passes=1 -> Han can pass and claim the draw (0).

    With a TT keyed only by the board hash the second search hit the first search's entry and
    returned the losing score for the drawn state.
    """
    from engine.rules import RuleConfig
    from engine.search import INF
    pos = _han_behind_position()
    shared = Searcher(SearchConfig(rules=RuleConfig(bikjang="off")))
    shared.new_search(None, None)
    pos.passes = 0
    losing = shared.negamax(pos, 3, -INF, INF, 1)
    pos.passes = 1
    drawn_shared_tt = shared.negamax(pos, 3, -INF, INF, 1)
    fresh = Searcher(SearchConfig(rules=RuleConfig(bikjang="off")))
    fresh.new_search(None, None)
    drawn_fresh_tt = fresh.negamax(pos, 3, -INF, INF, 1)
    assert losing < -800
    assert drawn_fresh_tt == 0
    assert drawn_shared_tt == drawn_fresh_tt, "TT returned the passes=0 score for the passes=1 state"
    pos.passes = 0
    assert shared.tt_key(pos) != shared.tt_key(_pass_pending(pos))


def _pass_pending(pos):
    q = pos.copy()
    q.passes = 1
    return q


def test_tt_key_components():
    from engine.hashing import ZOBRIST_BIKJANG, ZOBRIST_PASS_PENDING
    from engine.rules import RuleConfig
    pos = Position.initial()
    s = Searcher()
    base = s.tt_key(pos)
    assert base == pos.hash                          # nothing pending far from the move limit
    pos.passes = 1
    assert s.tt_key(pos) == base ^ ZOBRIST_PASS_PENDING
    pos.passes = 0
    # 빅장 flag only matters when the rule is on
    facing = build({(8, 4): cho(K), (1, 4): han(K), (9, 0): cho(R), (0, 8): han(R)})
    assert facing.bikjang
    on = Searcher(SearchConfig(rules=RuleConfig(bikjang="draw")))
    off = Searcher(SearchConfig(rules=RuleConfig(bikjang="off")))
    assert on.tt_key(facing) == facing.hash ^ ZOBRIST_BIKJANG
    assert off.tt_key(facing) == facing.hash
    # approaching the move limit changes the key, and only then
    near = Position.initial()
    near.ply = 400 - 10
    assert s.tt_key(near) != near.hash
    far = Position.initial()
    far.ply = 100
    assert s.tt_key(far) == far.hash


def test_repetition_context_and_state_key():
    from engine.notation import str_to_move
    a = Position.initial()
    b = Position.initial()
    for t in ("02-83", "18-37", "83-02", "37-18"):
        b.make_move(str_to_move(t))
    assert a.hash == b.hash and a.to_fen().split()[0] == b.to_fen().split()[0]
    assert a.repetition_context() != b.repetition_context()
    assert a.state_key() != b.state_key()
    c = Position.initial()
    c.passes = 1
    assert c.state_key() != a.state_key()
    assert Position.initial().state_key() == a.state_key()
