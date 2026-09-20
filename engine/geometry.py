"""Board geometry for Janggi (10 rows x 9 files).

Squares are indexed 0..89 as ``row * 9 + col``.  Row 0 is the top of the
board (Han's back rank), row 9 the bottom (Cho's back rank).  Column 0 is
the left-most file as seen from Cho's side.

Palaces are the 3x3 blocks rows 0-2 / rows 7-9 on files 3-5.  Diagonal
lines exist only between each palace corner and the palace centre.

Every table in this module is precomputed once at import time so that move
generation and attack detection are pure table look-ups.
"""
from __future__ import annotations

ROWS = 10
COLS = 9
NUM_SQUARES = ROWS * COLS

CHO = 0  # 초 (moves first, bottom of the board)
HAN = 1  # 한 (top of the board)


def sq(row: int, col: int) -> int:
    return row * COLS + col


def rc(square: int) -> tuple[int, int]:
    return divmod(square, COLS)


def on_board(row: int, col: int) -> bool:
    return 0 <= row < ROWS and 0 <= col < COLS


def palace_id(row: int, col: int) -> int:
    """0 = Han palace (top), 1 = Cho palace (bottom), -1 = not in a palace."""
    if 3 <= col <= 5:
        if row <= 2:
            return HAN
        if row >= 7:
            return CHO
    return -1


PALACE_CENTER = {HAN: sq(1, 4), CHO: sq(8, 4)}
_CENTER_SET = {(1, 4), (8, 4)}


def is_palace_center(row: int, col: int) -> bool:
    return (row, col) in _CENTER_SET


def diag_step_ok(r1: int, c1: int, r2: int, c2: int) -> bool:
    """True if a one-step diagonal move between the two squares follows a
    marked palace line (corner <-> centre inside the same palace)."""
    if abs(r1 - r2) != 1 or abs(c1 - c2) != 1:
        return False
    p1 = palace_id(r1, c1)
    if p1 < 0 or p1 != palace_id(r2, c2):
        return False
    return is_palace_center(r1, c1) or is_palace_center(r2, c2)


ORTH_DIRS = ((-1, 0), (1, 0), (0, -1), (0, 1))
DIAG_DIRS = ((-1, -1), (-1, 1), (1, -1), (1, 1))

# --- sliding rays -----------------------------------------------------------
RAYS_ORTH: list[list[tuple[int, ...]]] = [[] for _ in range(NUM_SQUARES)]
RAYS_DIAG: list[list[tuple[int, ...]]] = [[] for _ in range(NUM_SQUARES)]
RAYS_ALL: list[list[tuple[int, ...]]] = [[] for _ in range(NUM_SQUARES)]

# --- one-step palace moves (king / guard) -----------------------------------
PALACE_STEPS: list[tuple[int, ...]] = [() for _ in range(NUM_SQUARES)]

# --- horse / elephant: (blocking squares..., destination) --------------------
HORSE_MOVES: list[tuple[tuple[int, int], ...]] = [() for _ in range(NUM_SQUARES)]
ELEPHANT_MOVES: list[tuple[tuple[int, int, int], ...]] = [() for _ in range(NUM_SQUARES)]

# --- pawns, per colour --------------------------------------------------------
PAWN_MOVES: list[list[tuple[int, ...]]] = [[() for _ in range(NUM_SQUARES)] for _ in range(2)]

# reverse tables used by attack detection: "which origins can hit this square"
HORSE_ATTACKS: list[list[tuple[int, int]]] = [[] for _ in range(NUM_SQUARES)]        # (block, origin)
ELEPHANT_ATTACKS: list[list[tuple[int, int, int]]] = [[] for _ in range(NUM_SQUARES)]  # (b1, b2, origin)
PAWN_ATTACKS: list[list[list[int]]] = [[[] for _ in range(NUM_SQUARES)] for _ in range(2)]

IN_PALACE: list[int] = [palace_id(*rc(s)) for s in range(NUM_SQUARES)]
ROW_OF: list[int] = [rc(s)[0] for s in range(NUM_SQUARES)]
COL_OF: list[int] = [rc(s)[1] for s in range(NUM_SQUARES)]
MIRROR_SQ: list[int] = [sq(ROWS - 1 - rc(s)[0], rc(s)[1]) for s in range(NUM_SQUARES)]


def _build() -> None:
    for s in range(NUM_SQUARES):
        r, c = rc(s)

        for dr, dc in ORTH_DIRS:
            ray = []
            rr, cc = r + dr, c + dc
            while on_board(rr, cc):
                ray.append(sq(rr, cc))
                rr += dr
                cc += dc
            if ray:
                RAYS_ORTH[s].append(tuple(ray))

        for dr, dc in DIAG_DIRS:
            ray = []
            pr, pc = r, c
            rr, cc = r + dr, c + dc
            while on_board(rr, cc) and diag_step_ok(pr, pc, rr, cc):
                ray.append(sq(rr, cc))
                pr, pc = rr, cc
                rr += dr
                cc += dc
            if ray:
                RAYS_DIAG[s].append(tuple(ray))

        RAYS_ALL[s] = RAYS_ORTH[s] + RAYS_DIAG[s]

        steps = []
        pid = palace_id(r, c)
        if pid >= 0:
            for dr, dc in ORTH_DIRS:
                rr, cc = r + dr, c + dc
                if on_board(rr, cc) and palace_id(rr, cc) == pid:
                    steps.append(sq(rr, cc))
            for dr, dc in DIAG_DIRS:
                rr, cc = r + dr, c + dc
                if on_board(rr, cc) and diag_step_ok(r, c, rr, cc):
                    steps.append(sq(rr, cc))
        PALACE_STEPS[s] = tuple(steps)

        horse = []
        elephant = []
        for dr, dc in ORTH_DIRS:
            br, bc = r + dr, c + dc
            if not on_board(br, bc):
                continue
            if dr != 0:
                diags = ((dr, -1), (dr, 1))
            else:
                diags = ((-1, dc), (1, dc))
            for sr, sc in diags:
                tr, tc = br + sr, bc + sc
                if on_board(tr, tc):
                    horse.append((sq(br, bc), sq(tr, tc)))
                t2r, t2c = tr + sr, tc + sc
                if on_board(t2r, t2c):
                    elephant.append((sq(br, bc), sq(tr, tc), sq(t2r, t2c)))
        HORSE_MOVES[s] = tuple(horse)
        ELEPHANT_MOVES[s] = tuple(elephant)

        for color in (CHO, HAN):
            fwd = -1 if color == CHO else 1
            dests = []
            if on_board(r + fwd, c):
                dests.append(sq(r + fwd, c))
            for dc in (-1, 1):
                if on_board(r, c + dc):
                    dests.append(sq(r, c + dc))
            for dc in (-1, 1):
                rr, cc = r + fwd, c + dc
                if on_board(rr, cc) and diag_step_ok(r, c, rr, cc):
                    dests.append(sq(rr, cc))
            PAWN_MOVES[color][s] = tuple(dests)

    for s in range(NUM_SQUARES):
        for block, dest in HORSE_MOVES[s]:
            HORSE_ATTACKS[dest].append((block, s))
        for b1, b2, dest in ELEPHANT_MOVES[s]:
            ELEPHANT_ATTACKS[dest].append((b1, b2, s))
        for color in (CHO, HAN):
            for dest in PAWN_MOVES[color][s]:
                PAWN_ATTACKS[color][dest].append(s)


_build()
