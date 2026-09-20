"""Network input/output encoding for the neural stages (torch-free).

Everything the policy/value network will consume is defined here so that the
rules engine, the self-play generator and the training code agree on one
contract:

* ``encode_position(pos)`` -> flat ``list[float]`` of ``NUM_PLANES x 10 x 9``
  values (row-major planes).  The board is always presented from the side to
  move's point of view: for Han the board is flipped top-to-bottom so that
  "my" pieces start at the bottom, exactly like Cho's do.
* ``move_to_policy_index(m, side)`` / ``policy_index_to_move(i, side)`` map a
  move to one of ``POLICY_SIZE`` logits (from-square x to-square, plus one
  slot for 한수쉼), applying the same rotation.
* ``legal_policy_mask(pos)`` -> ``list[int]`` of 0/1 over the policy vector.

Value target convention: +1 = side to move wins, 0 = draw, -1 = loses, and
the network's scalar output is squashed with ``tanh`` — the same sign
convention the alpha-beta ``evaluate`` uses (side-to-move perspective), so a
value head can be dropped straight into ``Analyzer`` as a replacement for
``Calibrator.win_probability`` via ``(v + 1) / 2``.
"""
from __future__ import annotations

from engine.board import (
    CHO, HAN, NUM_SQUARES, PASS_MOVE, PIECE_TYPES, Position,
)
from engine.geometry import MIRROR_SQ
from engine.movegen import generate_legal_moves

# planes: 7 own piece types, 7 opponent piece types, then scalar planes
PLANE_OWN = 0
PLANE_OPP = 7
PLANE_IN_CHECK = 14
PLANE_BIKJANG = 15
PLANE_PASSES = 16      # consecutive 한수쉼 so far / 2
PLANE_REPETITION = 17  # (occurrences of this position - 1) / 2
PLANE_SIDE = 18        # 1.0 when the side to move is Cho (lets the net learn colour-specific rules if any)
NUM_PLANES = 19
POLICY_SIZE = NUM_SQUARES * NUM_SQUARES + 1   # 8100 from-to pairs + 한수쉼
PASS_INDEX = POLICY_SIZE - 1


def oriented_square(s: int, side: int) -> int:
    """Flip the board top-to-bottom for Han so the mover is always at the bottom.

    Janggi's rules are left-right symmetric, so a vertical flip is a true
    symmetry; it is the same transform as ``Position.mirrored`` and the
    evaluation's ``MIRROR_SQ``, which keeps every component of the engine on
    one orientation convention.
    """
    return s if side == CHO else MIRROR_SQ[s]


def encode_position(pos: Position) -> list[float]:
    planes = [0.0] * (NUM_PLANES * NUM_SQUARES)
    side = pos.side
    for s, p in enumerate(pos.board):
        if not p:
            continue
        t = p & 7
        own = (p >> 3) == side
        plane = (PLANE_OWN if own else PLANE_OPP) + (t - 1)
        planes[plane * NUM_SQUARES + oriented_square(s, side)] = 1.0
    if pos.in_check():
        _fill(planes, PLANE_IN_CHECK, 1.0)
    if pos.bikjang:
        _fill(planes, PLANE_BIKJANG, 1.0)
    if pos.passes:
        _fill(planes, PLANE_PASSES, min(pos.passes, 2) / 2.0)
    rep = pos.repetition_count() - 1
    if rep > 0:
        _fill(planes, PLANE_REPETITION, min(rep, 2) / 2.0)
    if side == CHO:
        _fill(planes, PLANE_SIDE, 1.0)
    return planes


def _fill(planes: list[float], plane: int, value: float) -> None:
    start = plane * NUM_SQUARES
    for i in range(start, start + NUM_SQUARES):
        planes[i] = value


def move_to_policy_index(m: int, side: int) -> int:
    if m == PASS_MOVE:
        return PASS_INDEX
    frm, to = divmod(m, NUM_SQUARES)
    return oriented_square(frm, side) * NUM_SQUARES + oriented_square(to, side)


def policy_index_to_move(i: int, side: int) -> int:
    if i == PASS_INDEX:
        return PASS_MOVE
    frm, to = divmod(i, NUM_SQUARES)
    return oriented_square(frm, side) * NUM_SQUARES + oriented_square(to, side)


def legal_policy_mask(pos: Position) -> list[int]:
    mask = [0] * POLICY_SIZE
    for m in generate_legal_moves(pos):
        mask[move_to_policy_index(m, pos.side)] = 1
    return mask


def value_target(winner: int | None, side: int) -> float:
    """Game result from ``side``'s point of view: +1 win, 0 draw, -1 loss."""
    if winner is None:
        return 0.0
    return 1.0 if winner == side else -1.0


__all__ = [
    "NUM_PLANES", "POLICY_SIZE", "PASS_INDEX", "encode_position", "move_to_policy_index",
    "policy_index_to_move", "legal_policy_mask", "value_target", "oriented_square", "PIECE_TYPES", "HAN",
]
