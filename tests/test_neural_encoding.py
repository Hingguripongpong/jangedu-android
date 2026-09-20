"""Neural encoding contract tests (torch-free)."""
import random

from engine.board import CHO, HAN, NUM_SQUARES, PASS_MOVE, Position
from engine.movegen import generate_legal_moves
from neural.encoding import (
    NUM_PLANES, PASS_INDEX, PLANE_OPP, PLANE_OWN, PLANE_SIDE, POLICY_SIZE, encode_position, legal_policy_mask,
    move_to_policy_index, policy_index_to_move, value_target,
)


def random_position(plies, seed):
    rng = random.Random(seed)
    pos = Position.initial()
    for _ in range(plies):
        legal = [m for m in generate_legal_moves(pos) if m != PASS_MOVE]
        if not legal:
            break
        pos.make_move(rng.choice(legal))
    return pos


def test_shapes_and_piece_counts():
    pos = Position.initial()
    x = encode_position(pos)
    assert len(x) == NUM_PLANES * NUM_SQUARES
    own = sum(x[PLANE_OWN * NUM_SQUARES:(PLANE_OWN + 7) * NUM_SQUARES])
    opp = sum(x[PLANE_OPP * NUM_SQUARES:(PLANE_OPP + 7) * NUM_SQUARES])
    assert own == 16 and opp == 16
    assert all(v == 1.0 for v in x[PLANE_SIDE * NUM_SQUARES:(PLANE_SIDE + 1) * NUM_SQUARES])


def test_han_sees_itself_at_the_bottom():
    """After Cho's first move Han is to move; its own king must be encoded in the bottom palace."""
    pos = Position.initial()
    pos.make_move(generate_legal_moves(pos)[0] if generate_legal_moves(pos)[0] != PASS_MOVE else generate_legal_moves(pos)[1])
    assert pos.side == HAN
    x = encode_position(pos)
    king_plane = x[PLANE_OWN * NUM_SQUARES:(PLANE_OWN + 1) * NUM_SQUARES]
    king_idx = king_plane.index(1.0)
    assert king_idx // 9 >= 7  # bottom three rows
    assert all(v == 0.0 for v in x[PLANE_SIDE * NUM_SQUARES:(PLANE_SIDE + 1) * NUM_SQUARES])


def test_policy_index_roundtrip_and_mask():
    for seed in range(6):
        pos = random_position(20 + seed, seed)
        legal = generate_legal_moves(pos)
        mask = legal_policy_mask(pos)
        assert len(mask) == POLICY_SIZE and sum(mask) == len(legal)
        for m in legal:
            i = move_to_policy_index(m, pos.side)
            assert 0 <= i < POLICY_SIZE and mask[i] == 1
            assert policy_index_to_move(i, pos.side) == m
        assert mask[PASS_INDEX] == (1 if PASS_MOVE in legal else 0)


def test_mirror_consistency_between_colours():
    """The same move, seen from Cho or from mirrored Han, maps to the same policy slot."""
    pos = random_position(15, 42)
    mirror = pos.mirrored()
    assert mirror.side != pos.side
    a = sorted(move_to_policy_index(m, pos.side) for m in generate_legal_moves(pos))
    b = sorted(move_to_policy_index(m, mirror.side) for m in generate_legal_moves(mirror))
    assert a == b
    assert encode_position(pos)[:PLANE_SIDE * NUM_SQUARES] == encode_position(mirror)[:PLANE_SIDE * NUM_SQUARES]


def test_value_target():
    assert value_target(CHO, CHO) == 1.0 and value_target(CHO, HAN) == -1.0 and value_target(None, CHO) == 0.0
