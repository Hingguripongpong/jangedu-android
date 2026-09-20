"""Rule tests: legal move sets for each piece and the classic illegal cases."""
from engine.board import (
    CANNON, CHARIOT, CHO, ELEPHANT, GUARD, HAN, HORSE, KING, PASS_MOVE, PAWN,
    Position, encode_move, make_piece,
)
from engine.geometry import PALACE_STEPS, RAYS_DIAG, diag_step_ok, sq
from engine.movegen import generate_legal_moves
from engine.notation import move_to_str, str_to_move, square_to_str, str_to_square


def empty_with_kings(side=CHO):
    pos = Position()
    pos.board[sq(8, 4)] = make_piece(CHO, KING)
    pos.board[sq(1, 3)] = make_piece(HAN, KING)  # off-file so no bikjang
    pos.side = side
    return pos


def moves_of(pos, frm):
    pos._finish_setup()
    return {m % 90 for m in generate_legal_moves(pos, include_pass=False) if m // 90 == frm}


def squares(*rcs):
    return {sq(r, c) for r, c in rcs}


# ---------------------------------------------------------------- geometry
def test_palace_diagonals_only_through_centre():
    assert diag_step_ok(2, 3, 1, 4)
    assert diag_step_ok(1, 4, 0, 5)
    assert not diag_step_ok(2, 4, 1, 3)   # edge midpoint has no diagonal
    assert not diag_step_ok(2, 5, 3, 4)   # leaves the palace
    assert not diag_step_ok(7, 3, 6, 4)
    assert RAYS_DIAG[sq(2, 3)] == [(sq(1, 4), sq(0, 5))]
    assert sorted(RAYS_DIAG[sq(1, 4)]) == sorted([(sq(0, 3),), (sq(0, 5),), (sq(2, 3),), (sq(2, 5),)])
    assert RAYS_DIAG[sq(1, 3)] == []
    assert set(PALACE_STEPS[sq(7, 3)]) == squares((8, 3), (7, 4), (8, 4))
    assert set(PALACE_STEPS[sq(8, 4)]) == squares((7, 4), (9, 4), (8, 3), (8, 5), (7, 3), (7, 5), (9, 3), (9, 5))


def test_notation_roundtrip():
    assert square_to_str(sq(8, 4)) == "95"
    assert square_to_str(sq(9, 0)) == "01"
    assert square_to_str(sq(0, 8)) == "19"
    for s in range(90):
        assert str_to_square(square_to_str(s)) == s
    m = encode_move(sq(9, 1), sq(7, 2))
    assert move_to_str(m) == "02-83"
    assert str_to_move("02-83") == m and str_to_move("02→83") == m and str_to_move("0283") == m
    assert str_to_move("pass") == PASS_MOVE


# ---------------------------------------------------------------- start position / FEN
def test_start_position_and_fen():
    pos = Position.initial()
    assert pos.to_fen().startswith("rnba1abnr/4k4/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/4K4/RNBA1ABNR w")
    assert Position.from_fen(pos.to_fen()).to_fen() == pos.to_fen()
    assert Position.from_fen(pos.to_fen()).hash == pos.hash
    counts = {}
    for p in pos.board:
        if p:
            counts[p] = counts.get(p, 0) + 1
    for color in (CHO, HAN):
        assert counts[make_piece(color, KING)] == 1
        assert counts[make_piece(color, GUARD)] == 2
        assert counts[make_piece(color, CHARIOT)] == 2
        assert counts[make_piece(color, CANNON)] == 2
        assert counts[make_piece(color, HORSE)] == 2
        assert counts[make_piece(color, ELEPHANT)] == 2
        assert counts[make_piece(color, PAWN)] == 5


def test_start_position_legal_moves():
    pos = Position.initial()
    legal = generate_legal_moves(pos)
    assert len(legal) == 32  # 31 piece moves + 한수쉼
    assert PASS_MOVE in legal
    # elephants and cannons are blocked in the 마상상마 setup
    for frm in (sq(9, 2), sq(9, 6), sq(7, 1), sq(7, 7)):
        assert not [m for m in legal if m // 90 == frm]
    assert len([m for m in legal if m // 90 == sq(8, 4)]) == 6


def test_setup_variants():
    pos = Position.initial("상마상마", "상마마상")
    assert pos.board[sq(9, 1)] == make_piece(CHO, ELEPHANT)
    assert pos.board[sq(9, 2)] == make_piece(CHO, HORSE)
    assert pos.board[sq(9, 6)] == make_piece(CHO, ELEPHANT)
    assert pos.board[sq(9, 7)] == make_piece(CHO, HORSE)
    # Han's "left" is file 8
    assert pos.board[sq(0, 7)] == make_piece(HAN, ELEPHANT)
    assert pos.board[sq(0, 6)] == make_piece(HAN, HORSE)
    assert pos.board[sq(0, 2)] == make_piece(HAN, HORSE)
    assert pos.board[sq(0, 1)] == make_piece(HAN, ELEPHANT)
    # 상마상마 lets the elephants out at once
    legal = generate_legal_moves(pos)
    assert [m for m in legal if m // 90 == sq(9, 1)]


# ---------------------------------------------------------------- 차
def test_chariot_orthogonal_and_palace_diagonal():
    pos = empty_with_kings()
    pos.board[sq(5, 4)] = make_piece(CHO, CHARIOT)
    pos.board[sq(5, 7)] = make_piece(HAN, PAWN)     # capturable
    pos.board[sq(5, 1)] = make_piece(CHO, PAWN)     # own piece blocks
    dests = moves_of(pos, sq(5, 4))
    expected = squares((4, 4), (3, 4), (2, 4), (5, 5), (5, 6), (5, 7), (5, 3), (5, 2), (6, 4), (7, 4))
    # column 4 upward stops at (2,4)? no: (1,4) is empty (Han king at (1,3)), (0,4) too
    expected |= squares((1, 4), (0, 4))
    assert dests == expected

    # chariot on a palace corner: full diagonal corner-centre-corner
    pos = Position()
    pos.board[sq(8, 3)] = make_piece(CHO, KING)
    pos.board[sq(2, 4)] = make_piece(HAN, KING)      # not attacked from (0,3)
    pos.board[sq(0, 3)] = make_piece(CHO, CHARIOT)
    d = moves_of(pos, sq(0, 3))
    expected = squares((1, 4), (2, 5))                                   # diagonal
    expected |= squares(*[(r, 3) for r in range(1, 8)])                 # file, stops before own king (8,3)
    expected |= squares((0, 0), (0, 1), (0, 2), (0, 4), (0, 5), (0, 6), (0, 7), (0, 8))
    assert d == expected


def test_check_detection_from_chariot():
    from engine.rules import game_status
    pos = empty_with_kings(side=HAN)
    pos.board[sq(2, 3)] = make_piece(CHO, CHARIOT)   # attacks (1,3) along the file
    pos._finish_setup()
    assert pos.in_check()
    assert game_status(pos).in_check
    pos.board[sq(2, 3)] = 0
    pos.board[sq(0, 5)] = make_piece(CHO, CHARIOT)   # (0,5)-(1,4)-(2,3) diagonal does not touch (1,3)
    pos._finish_setup()
    assert not pos.in_check()


def test_chariot_diagonal_blocked_by_centre_piece():
    pos = empty_with_kings()
    pos.board[sq(7, 3)] = make_piece(CHO, CHARIOT)
    pos.board[sq(8, 4)] = make_piece(CHO, KING)  # centre occupied
    d = moves_of(pos, sq(7, 3))
    assert sq(9, 5) not in d and sq(8, 4) not in d
    assert sq(7, 4) in d and sq(7, 5) in d


# ---------------------------------------------------------------- 포
def test_cannon_needs_exactly_one_screen():
    pos = empty_with_kings()
    pos.board[sq(7, 1)] = make_piece(CHO, CANNON)
    pos.board[sq(5, 1)] = make_piece(HAN, PAWN)      # screen
    pos.board[sq(2, 1)] = make_piece(HAN, HORSE)     # target beyond screen
    d = moves_of(pos, sq(7, 1))
    assert squares((4, 1), (3, 1), (2, 1)) <= d
    assert sq(6, 1) not in d and sq(5, 1) not in d     # no jump without / onto screen
    assert sq(1, 1) not in d                          # cannot pass the second piece
    # sideways: no screen on row 7 -> nothing
    assert not {s for s in d if s // 9 == 7}


def test_cannon_cannot_jump_or_capture_cannon():
    pos = empty_with_kings()
    pos.board[sq(7, 1)] = make_piece(CHO, CANNON)
    pos.board[sq(5, 1)] = make_piece(HAN, CANNON)    # cannon as screen: forbidden
    d = moves_of(pos, sq(7, 1))
    assert not {s for s in d if s % 9 == 1}
    pos = empty_with_kings()
    pos.board[sq(7, 1)] = make_piece(CHO, CANNON)
    pos.board[sq(5, 1)] = make_piece(HAN, PAWN)
    pos.board[sq(3, 1)] = make_piece(HAN, CANNON)    # capture target is a cannon: forbidden
    d = moves_of(pos, sq(7, 1))
    assert sq(4, 1) in d and sq(3, 1) not in d


def test_cannon_palace_diagonal_jump():
    pos = empty_with_kings()
    pos.board[sq(2, 3)] = make_piece(CHO, CANNON)
    pos.board[sq(1, 4)] = make_piece(HAN, GUARD)     # screen at centre
    d = moves_of(pos, sq(2, 3))
    assert sq(0, 5) in d
    pos.board[sq(0, 5)] = make_piece(HAN, CANNON)
    assert sq(0, 5) not in moves_of(pos, sq(2, 3))
    pos.board[sq(0, 5)] = make_piece(HAN, ELEPHANT)
    assert sq(0, 5) in moves_of(pos, sq(2, 3))
    pos.board[sq(1, 4)] = make_piece(HAN, CANNON)    # cannon screen
    assert sq(0, 5) not in moves_of(pos, sq(2, 3))


def test_cannon_start_position_immobile():
    pos = Position.initial()
    assert not [m for m in generate_legal_moves(pos) if m // 90 in (sq(7, 1), sq(7, 7))]


# ---------------------------------------------------------------- 마 / 상
def test_horse_moves_and_leg_block():
    pos = empty_with_kings()
    pos.board[sq(5, 4)] = make_piece(CHO, HORSE)
    d = moves_of(pos, sq(5, 4))
    assert d == squares((3, 3), (3, 5), (7, 3), (7, 5), (4, 2), (6, 2), (4, 6), (6, 6))
    pos.board[sq(4, 4)] = make_piece(HAN, PAWN)      # blocks the upward leg
    d = moves_of(pos, sq(5, 4))
    assert sq(3, 3) not in d and sq(3, 5) not in d and len(d) == 6
    pos.board[sq(3, 3)] = make_piece(HAN, CHARIOT)
    pos.board[sq(4, 4)] = 0
    pos.board[sq(7, 3)] = make_piece(CHO, PAWN)      # own piece on target
    d = moves_of(pos, sq(5, 4))
    assert sq(3, 3) in d and sq(7, 3) not in d


def test_elephant_moves_and_blocks():
    pos = empty_with_kings()
    pos.board[sq(5, 4)] = make_piece(CHO, ELEPHANT)
    d = moves_of(pos, sq(5, 4))
    assert d == squares((2, 2), (2, 6), (8, 2), (8, 6), (3, 1), (7, 1), (3, 7), (7, 7))
    pos.board[sq(3, 3)] = make_piece(HAN, PAWN)      # second leg toward (2,2)
    assert sq(2, 2) not in moves_of(pos, sq(5, 4))
    pos.board[sq(3, 3)] = 0
    pos.board[sq(4, 4)] = make_piece(HAN, PAWN)      # first leg upward
    d = moves_of(pos, sq(5, 4))
    assert sq(2, 2) not in d and sq(2, 6) not in d


# ---------------------------------------------------------------- 졸 / 병
def test_pawn_forward_side_never_back_and_palace_diagonals():
    pos = empty_with_kings()
    pos.board[sq(5, 4)] = make_piece(CHO, PAWN)
    assert moves_of(pos, sq(5, 4)) == squares((4, 4), (5, 3), (5, 5))
    pos = empty_with_kings(side=HAN)
    pos.board[sq(4, 0)] = make_piece(HAN, PAWN)
    assert moves_of(pos, sq(4, 0)) == squares((5, 0), (4, 1))
    # Cho pawn at Han palace corner
    pos = empty_with_kings()
    pos.board[sq(2, 5)] = make_piece(CHO, PAWN)
    assert moves_of(pos, sq(2, 5)) == squares((1, 5), (2, 4), (2, 6), (1, 4))
    pos = empty_with_kings()
    pos.board[sq(1, 4)] = make_piece(CHO, PAWN)
    pos.board[sq(1, 3)] = 0
    pos.board[sq(2, 3)] = make_piece(HAN, KING)      # behind the pawn: not attacked
    # from the centre: forward, both sides, both forward diagonals
    assert moves_of(pos, sq(1, 4)) == squares((0, 4), (1, 3), (1, 5), (0, 3), (0, 5))
    # pawn on the last rank only slides sideways
    pos = empty_with_kings()
    pos.board[sq(0, 6)] = make_piece(CHO, PAWN)
    assert moves_of(pos, sq(0, 6)) == squares((0, 5), (0, 7))
    # edge midpoint of the palace has no diagonal
    pos = empty_with_kings()
    pos.board[sq(2, 4)] = make_piece(CHO, PAWN)
    assert moves_of(pos, sq(2, 4)) == squares((1, 4), (2, 3), (2, 5))


# ---------------------------------------------------------------- 궁 / 사
def test_king_and_guard_confined_to_palace_lines():
    pos = empty_with_kings()
    pos.board[sq(8, 4)] = 0
    pos.board[sq(7, 4)] = make_piece(CHO, KING)     # front-centre edge
    assert moves_of(pos, sq(7, 4)) == squares((8, 4), (7, 3), (7, 5))   # no (6,4), no diagonals
    pos = empty_with_kings()
    pos.board[sq(9, 3)] = make_piece(CHO, GUARD)
    assert moves_of(pos, sq(9, 3)) == squares((9, 4), (8, 3), (8, 4)) - {sq(8, 4)}  # centre holds own king
    pos.board[sq(8, 4)] = 0
    pos.board[sq(7, 4)] = make_piece(CHO, KING)
    assert sq(8, 4) in moves_of(pos, sq(9, 3))


def test_king_cannot_move_into_attack():
    pos = empty_with_kings()
    pos.board[sq(5, 3)] = make_piece(HAN, CHARIOT)  # controls file 3
    d = moves_of(pos, sq(8, 4))
    assert sq(7, 3) not in d and sq(8, 3) not in d and sq(9, 3) not in d
    assert sq(7, 5) in d
