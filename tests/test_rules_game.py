"""Game-rule tests: check, mate, 빅장, 한수쉼, repetition, make/unmake integrity, perft."""
import copy
import random

from engine.board import (
    CANNON, CHARIOT, CHO, GUARD, HAN, HORSE, KING, PASS_MOVE, PAWN, Position, encode_move, make_piece,
)
from engine.geometry import sq
from engine.hashing import compute_hash
from engine.movegen import generate_legal_moves, generate_pseudo_moves, perft
from engine.rules import (
    CHECKMATE, DRAW_BIKJANG, DRAW_PASSES, DRAW_REPETITION, ONGOING, game_status, replay,
)


def build(pieces, side=CHO):
    pos = Position()
    for (r, c), p in pieces.items():
        pos.board[sq(r, c)] = p
    pos.side = side
    pos._finish_setup()
    return pos


K, G, R, C, N, P = KING, GUARD, CHARIOT, CANNON, HORSE, PAWN


def cho(t):
    return make_piece(CHO, t)


def han(t):
    return make_piece(HAN, t)


# ---------------------------------------------------------------- check
def test_must_escape_check_and_cannot_pass():
    pos = build({(8, 4): cho(K), (1, 4): han(K), (5, 3): han(R)}, side=CHO)
    pos.board[sq(8, 4)] = 0
    pos.board[sq(8, 3)] = cho(K)   # king on file 3, attacked by the chariot
    pos._finish_setup()
    assert pos.in_check()
    legal = generate_legal_moves(pos)
    assert PASS_MOVE not in legal
    for m in legal:
        pos.make_move(m)
        assert not pos.attacked_by(pos.king_sq[CHO], HAN)
        pos.unmake_move()
    assert legal  # can escape


def test_pinned_piece_cannot_move_off_line():
    pos = build({(8, 4): cho(K), (1, 3): han(K), (6, 4): cho(N), (3, 4): han(R)}, side=CHO)
    # horse shields the king from the chariot on file 4; it may not leave
    assert not [m for m in generate_legal_moves(pos) if m // 90 == sq(6, 4)]


def test_checkmate_position():
    # Han king cornered on (0,3); Cho chariot (5,4) holds file 4, pawn (2,3) covers (1,3)/(1,4)
    pos = build({(8, 4): cho(K), (0, 3): han(K), (5, 4): cho(R), (2, 3): cho(P), (0, 8): cho(R)}, side=HAN)
    st = game_status(pos)
    assert st.status == CHECKMATE and st.winner == CHO
    assert generate_legal_moves(pos) == []


def test_mate_in_one_exists_from_cho_side():
    pos = build({(8, 4): cho(K), (0, 3): han(K), (5, 4): cho(R), (2, 3): cho(P), (3, 8): cho(R)}, side=CHO)
    assert game_status(pos).status == ONGOING
    m = encode_move(sq(3, 8), sq(0, 8))
    assert m in generate_legal_moves(pos)
    pos.make_move(m)
    assert game_status(pos).status == CHECKMATE


# ---------------------------------------------------------------- 빅장
def test_bikjang_flag_and_draw_when_not_resolved():
    # kings on the same open file, Cho to move -> bikjang stands for Cho
    pos = build({(8, 4): cho(K), (1, 4): han(K), (9, 0): cho(R), (0, 8): han(R)}, side=CHO)
    assert pos.kings_facing() and pos.bikjang
    assert game_status(pos).bikjang
    assert game_status(pos).status == ONGOING
    # Cho ignores it (moves the chariot): draw
    pos.make_move(encode_move(sq(9, 0), sq(9, 1)))
    assert pos.bikjang and pos.prev_bikjang()
    assert game_status(pos).status == DRAW_BIKJANG
    pos.unmake_move()
    # Cho breaks it by moving the king off the file: game goes on
    pos.make_move(encode_move(sq(8, 4), sq(8, 3)))
    assert not pos.bikjang
    assert game_status(pos).status == ONGOING
    pos.unmake_move()
    # or by interposing a piece
    pos.make_move(encode_move(sq(9, 0), sq(9, 4)))   # chariot behind the king does NOT interpose
    assert pos.bikjang
    pos.unmake_move()
    pos.make_move(encode_move(sq(9, 0), sq(5, 0)))
    pos.make_move(encode_move(sq(0, 8), sq(0, 7)))   # Han keeps it standing -> Han declined to break: draw
    # note: after Cho's chariot move the bikjang still stood, so it is already a draw
    assert game_status(pos).status == DRAW_BIKJANG


def test_check_takes_precedence_over_bikjang():
    # moving the king onto the open file creates bikjang for the opponent
    pos = build({(8, 3): cho(K), (1, 4): han(K), (5, 0): cho(R)}, side=CHO)
    pos.make_move(encode_move(sq(8, 3), sq(8, 4)))
    assert pos.kings_facing() and pos.bikjang
    pos.unmake_move()
    # kings already face; Cho gives check along rank 1 -> aligned but check wins, bikjang void
    pos2 = build({(8, 4): cho(K), (1, 4): han(K), (4, 0): cho(R)}, side=CHO)
    assert pos2.kings_facing() and pos2.bikjang
    pos2.make_move(encode_move(sq(4, 0), sq(1, 0)))
    assert pos2.in_check()
    assert pos2.kings_facing() and not pos2.bikjang
    assert game_status(pos2).status == ONGOING


# ---------------------------------------------------------------- 한수쉼
def test_pass_allowed_only_out_of_check_and_double_pass_draws():
    pos = Position.initial()
    assert PASS_MOVE in generate_legal_moves(pos)
    pos.make_move(PASS_MOVE)
    assert pos.passes == 1 and pos.side == HAN
    assert game_status(pos).status == ONGOING
    pos.make_move(PASS_MOVE)
    assert game_status(pos).status == DRAW_PASSES
    pos.unmake_move()
    pos.make_move(encode_move(sq(3, 0), sq(4, 0)))
    assert pos.passes == 0
    assert game_status(pos).status == ONGOING


# ---------------------------------------------------------------- repetition
def test_threefold_repetition():
    pos = Position.initial()
    cycle = ["02-83", "18-37", "83-02", "37-18"]
    from engine.notation import str_to_move
    for _ in range(2):
        for s in cycle:
            pos.make_move(str_to_move(s))
    assert pos.repetition_count() == 3
    assert game_status(pos).status == DRAW_REPETITION
    pos.unmake_move()
    assert game_status(pos).status == ONGOING
    assert pos.is_repetition()


# ---------------------------------------------------------------- make / unmake integrity
def test_make_unmake_restores_everything_random_playouts():
    rng = random.Random(7)
    for game in range(20):
        pos = Position.initial(rng.choice(list(__import__("engine.board", fromlist=["SETUPS"]).SETUPS)))
        for ply in range(60):
            legal = generate_legal_moves(pos)
            if not legal:
                break
            snapshot = (pos.board[:], pos.side, pos.hash, pos.king_sq[:], pos.passes, pos.bikjang,
                        pos.ply, pos.history[:])
            m = rng.choice(legal)
            pos.make_move(m)
            assert pos.hash == compute_hash(pos.board, pos.side), "incremental hash drifted"
            assert pos.king_sq[CHO] >= 0 and (pos.board[pos.king_sq[CHO]] & 7) == KING
            assert (pos.board[pos.king_sq[HAN]] & 7) == KING
            pos.unmake_move()
            assert (pos.board, pos.side, pos.hash, pos.king_sq, pos.passes, pos.bikjang,
                    pos.ply, pos.history) == snapshot
            pos.make_move(m)
            if game_status(pos).is_over:
                break


def test_fen_roundtrip_after_random_moves():
    rng = random.Random(3)
    pos = Position.initial()
    for _ in range(40):
        legal = [m for m in generate_legal_moves(pos) if m != PASS_MOVE]
        pos.make_move(rng.choice(legal))
    fen = pos.to_fen()
    again = Position.from_fen(fen)
    assert again.to_fen() == fen and again.board == pos.board and again.hash == pos.hash


# ---------------------------------------------------------------- perft
def _slow_perft(pos, depth):
    """Independent state handling: copies the position instead of unmaking."""
    if depth == 0:
        return 1
    total = 0
    for m in generate_pseudo_moves(pos):
        child = copy.deepcopy(pos)
        child.make_move(m)
        if child.attacked_by(child.king_sq[pos.side], pos.side ^ 1):
            continue
        total += _slow_perft(child, depth - 1)
    return total


def test_perft_start_position_regression():
    pos = Position.initial()
    assert perft(pos, 1) == 31
    assert perft(pos, 1, include_pass=True) == 32
    assert perft(pos, 2) == 961
    assert perft(pos, 3) == 30353
    assert pos.to_fen() == Position.initial().to_fen()   # search left the board untouched


def test_perft_fast_equals_slow_copy_based():
    pos = Position.initial("상마상마", "마상마상")
    for d in (1, 2):
        assert perft(pos, d) == _slow_perft(pos, d)
    mid = Position.from_fen("r1ba1ab1r/4k1n2/1c2n2c1/p3p1p1p/2p6/6P2/P1P1P3P/1C2NC3/4K4/RNBA1AB1R w - - 0 1")
    for d in (1, 2):
        assert perft(mid, d) == _slow_perft(mid, d)


def test_replay_validates_moves():
    pos = replay(None, ["02-83", "18-37"])
    assert pos.ply == 2
    try:
        replay(None, ["02-84"])
    except ValueError as e:
        assert "illegal" in str(e)
    else:
        raise AssertionError("illegal move accepted")
