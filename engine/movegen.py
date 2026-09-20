"""Move generation.

``generate_pseudo_moves`` produces every move that follows the movement
rules of the pieces but may leave the mover's own king in check.
``generate_legal_moves`` filters those with make/unmake and appends
한수쉼 (PASS_MOVE) when the side to move is not in check.

Rules encoded here:
  * 차 slides orthogonally; inside a palace it may also slide along the
    marked diagonals (corner - centre - corner).
  * 포 moves orthogonally (and along palace diagonals) by jumping exactly one
    screen piece.  The screen may not be a cannon (either colour) and a
    cannon may never capture a cannon.
  * 마 : one orthogonal step then one diagonal step outward; the orthogonal
    square must be empty.
  * 상 : one orthogonal step then two diagonal steps outward; both
    intermediate squares must be empty.
  * 졸/병 : one step forward or sideways, never backward; forward diagonals
    along palace lines when standing in a palace.
  * 궁/사 : one step along any palace line, confined to the palace.
  * 한수쉼 (pass) is legal whenever the side to move is not in check.
"""
from __future__ import annotations

from .board import (
    CANNON,
    CHARIOT,
    ELEPHANT,
    GUARD,
    HORSE,
    KING,
    NUM_SQUARES,
    PASS_MOVE,
    PAWN,
    Position,
)
from .geometry import (
    ELEPHANT_MOVES,
    HORSE_MOVES,
    PALACE_STEPS,
    PAWN_MOVES,
    RAYS_ALL,
)

_SLIDER = CHARIOT
_JUMPER = CANNON


def generate_pseudo_moves(pos: Position, captures_only: bool = False) -> list[int]:
    board = pos.board
    side = pos.side
    moves: list[int] = []
    push = moves.append
    pawn_tbl = PAWN_MOVES[side]

    for frm in range(NUM_SQUARES):
        p = board[frm]
        if not p or (p >> 3) != side:
            continue
        t = p & 7
        base = frm * NUM_SQUARES

        if t == CHARIOT:
            for ray in RAYS_ALL[frm]:
                for to in ray:
                    q = board[to]
                    if not q:
                        if not captures_only:
                            push(base + to)
                    else:
                        if (q >> 3) != side:
                            push(base + to)
                        break

        elif t == CANNON:
            for ray in RAYS_ALL[frm]:
                screen = False
                for to in ray:
                    q = board[to]
                    if not screen:
                        if q:
                            if (q & 7) == CANNON:
                                break
                            screen = True
                    elif not q:
                        if not captures_only:
                            push(base + to)
                    else:
                        if (q >> 3) != side and (q & 7) != CANNON:
                            push(base + to)
                        break

        elif t == HORSE:
            for block, to in HORSE_MOVES[frm]:
                if board[block]:
                    continue
                q = board[to]
                if not q:
                    if not captures_only:
                        push(base + to)
                elif (q >> 3) != side:
                    push(base + to)

        elif t == ELEPHANT:
            for b1, b2, to in ELEPHANT_MOVES[frm]:
                if board[b1] or board[b2]:
                    continue
                q = board[to]
                if not q:
                    if not captures_only:
                        push(base + to)
                elif (q >> 3) != side:
                    push(base + to)

        elif t == PAWN:
            for to in pawn_tbl[frm]:
                q = board[to]
                if not q:
                    if not captures_only:
                        push(base + to)
                elif (q >> 3) != side:
                    push(base + to)

        else:  # KING or GUARD
            for to in PALACE_STEPS[frm]:
                q = board[to]
                if not q:
                    if not captures_only:
                        push(base + to)
                elif (q >> 3) != side:
                    push(base + to)

    return moves


def is_legal_after_make(pos: Position, mover: int) -> bool:
    """Call right after ``pos.make_move``: was the move legal for ``mover``?"""
    return not pos.attacked_by(pos.king_sq[mover], mover ^ 1)


def _must_resolve_bikjang(pos: Position) -> bool:
    """Forced-bikjang variant: the side to move faces a 빅장 and is not in check."""
    return pos.forced_bikjang and pos.bikjang and not pos.in_check()


def generate_legal_moves(pos: Position, include_pass: bool = True) -> list[int]:
    side = pos.side
    legal: list[int] = []
    must_resolve = _must_resolve_bikjang(pos)
    for m in generate_pseudo_moves(pos):
        pos.make_move(m)
        ok = not pos.attacked_by(pos.king_sq[side], side ^ 1)
        if ok and must_resolve and pos.kings_facing():
            ok = False          # leaving the kings facing is not a legal answer to a 빅장
        pos.unmake_move()
        if ok:
            legal.append(m)
    if include_pass and not pos.in_check():
        legal.append(PASS_MOVE)     # under a forced 빅장 the pass accepts the ending
    return legal


def is_legal_move(pos: Position, m: int) -> bool:
    if m == PASS_MOVE:
        return not pos.in_check()
    return m in generate_pseudo_moves(pos) and m in generate_legal_moves(pos, include_pass=False)


def gives_check(pos: Position, m: int) -> bool:
    pos.make_move(m)
    chk = pos.in_check()
    pos.unmake_move()
    return chk


def perft(pos: Position, depth: int, include_pass: bool = False, stop_at_game_end: bool = False) -> int:
    """Count leaf nodes of the legal move tree (pass moves optional).

    ``stop_at_game_end`` treats positions ended by rule (two consecutive passes,
    an accepted/unresolved 빅장) as leaves, which is how Fairy-Stockfish counts;
    with ``include_pass`` and a forced-bikjang position this reproduces its
    janggi perft numbers exactly.
    """
    if depth == 0:
        return 1
    if stop_at_game_end and (pos.passes >= 2 or (pos.bikjang and pos.prev_bikjang())):
        return 0
    side = pos.side
    total = 0
    pseudo = generate_pseudo_moves(pos)
    must_resolve = _must_resolve_bikjang(pos)
    if depth == 1 and not stop_at_game_end and not must_resolve:
        for m in pseudo:
            pos.make_move(m)
            if not pos.attacked_by(pos.king_sq[side], side ^ 1):
                total += 1
            pos.unmake_move()
        if include_pass and not pos.in_check():
            total += 1
        return total
    for m in pseudo:
        pos.make_move(m)
        if not pos.attacked_by(pos.king_sq[side], side ^ 1) and not (must_resolve and pos.kings_facing()):
            total += perft(pos, depth - 1, include_pass, stop_at_game_end)
        pos.unmake_move()
    if include_pass and not pos.in_check():
        pos.make_move(PASS_MOVE)
        total += perft(pos, depth - 1, include_pass, stop_at_game_end)
        pos.unmake_move()
    return total
