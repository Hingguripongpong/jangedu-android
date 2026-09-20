"""Zobrist hashing.

Keys are generated from a fixed seed so hashes are reproducible across runs
(useful for debugging, regression tests and, later, opening books).
"""
from __future__ import annotations

import random

from .geometry import NUM_SQUARES

_SEED = 20260916
_rng = random.Random(_SEED)


def _key() -> int:
    return _rng.getrandbits(64)


# piece codes are 0..15 (type | colour << 3); index 0 is never used
ZOBRIST_PIECE: list[list[int]] = [[_key() for _ in range(NUM_SQUARES)] for _ in range(16)]
ZOBRIST_SIDE: int = _key()

# --- search-state keys ---------------------------------------------------------
# ``Position.hash`` identifies the *board + side to move* only and is what repetition
# detection needs.  A search/TT key additionally has to distinguish states that lead to
# different game results from the same board:
#   * one pending 한수쉼 (a second pass ends the game),
#   * a standing 빅장 (the next move may end the game / the legal move set changes),
#   * proximity to the move limit.
# These keys are XOR-ed into the board hash by ``Searcher.tt_key``; they are *not* part of
# ``Position.hash`` so repetition detection keeps working on the plain board identity.
ZOBRIST_PASS_PENDING: int = _key()
ZOBRIST_BIKJANG: int = _key()
ZOBRIST_PLIES_LEFT: list[int] = [_key() for _ in range(128)]


def compute_hash(board: list[int], side: int) -> int:
    """Full recomputation — used for verification of the incremental update."""
    h = 0
    for s, p in enumerate(board):
        if p:
            h ^= ZOBRIST_PIECE[p][s]
    if side:
        h ^= ZOBRIST_SIDE
    return h
