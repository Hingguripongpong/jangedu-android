"""Static evaluation (centipawns, from the side to move's point of view).

Every term is a separate function so it can be weighted, switched off, or
replaced by a learned evaluator later.  ``evaluate`` is the fast path used
inside the search; ``evaluate_breakdown`` returns the same total split into
terms for the debug UI.  A test asserts both agree.

Material follows the customary Janggi point values x100:
    차 13, 포 7, 마 5, 상 3, 사 3, 졸/병 2.
"""
from __future__ import annotations

from dataclasses import dataclass, field

from .board import (
    CANNON,
    CHARIOT,
    CHO,
    ELEPHANT,
    GUARD,
    HAN,
    HORSE,
    KING,
    PAWN,
    Position,
)
from .geometry import (
    COL_OF,
    ELEPHANT_MOVES,
    HORSE_MOVES,
    MIRROR_SQ,
    NUM_SQUARES,
    PALACE_STEPS,
    RAYS_ALL,
    ROW_OF,
    rc,
    sq,
)


@dataclass
class EvalConfig:
    material: dict[int, int] = field(default_factory=lambda: {
        KING: 0, GUARD: 300, CHARIOT: 1300, CANNON: 700, HORSE: 500, ELEPHANT: 300, PAWN: 200,
    })
    pst_weight: int = 100          # percent
    pawn_connected: int = 12       # bonus per connected pawn pair
    guard_adjacent: int = 10       # bonus per guard adjacent to own king
    king_danger: dict[int, int] = field(default_factory=lambda: {
        CHARIOT: 24, CANNON: 18, HORSE: 14, ELEPHANT: 8, PAWN: 10,
    })
    mobility: bool = True
    mobility_weight: dict[int, int] = field(default_factory=lambda: {
        CHARIOT: 2, CANNON: 3, HORSE: 3, ELEPHANT: 2,
    })
    tempo: int = 10


def _build_pst() -> list[list[list[int]]]:
    """PST[color][type][sq] from Cho's perspective (advancing = row decreasing),
    mirrored for Han."""
    pst = [[[0] * NUM_SQUARES for _ in range(8)] for _ in range(2)]
    pawn_row = {9: 0, 8: 0, 7: 0, 6: 0, 5: 8, 4: 16, 3: 26, 2: 36, 1: 44, 0: 14}
    for s in range(NUM_SQUARES):
        r, c = rc(s)
        centre_file = 3 <= c <= 5
        inner_file = 1 <= c <= 7

        pawn = pawn_row[r]
        if centre_file and r <= 3:
            pawn += 6

        horse = 0
        if inner_file:
            horse += 6
        if 2 <= c <= 6:
            horse += 6
        if 2 <= r <= 7:
            horse += 6
        if r == 9:
            horse -= 4

        elephant = 0
        if inner_file:
            elephant += 4
        if 2 <= r <= 7:
            elephant += 4

        chariot = 0
        if r <= 3:
            chariot += 8
        if 0 <= r <= 2 and centre_file:
            chariot += 6

        cannon = 0
        if (r, c) == (7, 4):      # 면포: cannon on the front-centre of own palace
            cannon += 30
        elif c == 4 and r >= 5:
            cannon += 10

        king = 0
        if r == 7:                # front row of own palace is exposed
            king -= 10

        guard = 0
        if s in (sq(9, 3), sq(9, 5)):
            guard += 4

        vals = {PAWN: pawn, HORSE: horse, ELEPHANT: elephant, CHARIOT: chariot,
                CANNON: cannon, KING: king, GUARD: guard}
        for t, v in vals.items():
            pst[CHO][t][s] = v
            pst[HAN][t][MIRROR_SQ[s]] = v
    return pst


PST = _build_pst()

# squares that count as "near" each king's palace for the danger term
DANGER_ZONE = [[False] * NUM_SQUARES for _ in range(2)]
for _s in range(NUM_SQUARES):
    _r, _c = rc(_s)
    if 2 <= _c <= 6:
        if _r >= 5:
            DANGER_ZONE[CHO][_s] = True
        if _r <= 4:
            DANGER_ZONE[HAN][_s] = True


def _mobility(board: list[int], frm: int, t: int, side: int) -> int:
    """Number of pseudo-legal destinations for a chariot/cannon/horse/elephant."""
    n = 0
    if t == CHARIOT:
        for ray in RAYS_ALL[frm]:
            for to in ray:
                q = board[to]
                if not q:
                    n += 1
                else:
                    if (q >> 3) != side:
                        n += 1
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
                    n += 1
                else:
                    if (q >> 3) != side and (q & 7) != CANNON:
                        n += 1
                    break
    elif t == HORSE:
        for block, to in HORSE_MOVES[frm]:
            if not board[block]:
                q = board[to]
                if not q or (q >> 3) != side:
                    n += 1
    else:  # ELEPHANT
        for b1, b2, to in ELEPHANT_MOVES[frm]:
            if not board[b1] and not board[b2]:
                q = board[to]
                if not q or (q >> 3) != side:
                    n += 1
    return n


def evaluate_breakdown(pos: Position, cfg: EvalConfig | None = None) -> dict[str, int]:
    """Term-by-term evaluation (Cho minus Han, then flipped to side-to-move)."""
    cfg = cfg or DEFAULT_CONFIG
    board = pos.board
    material = 0
    pst = 0
    mobility = 0
    danger = [0, 0]
    pawns: list[list[int]] = [[], []]
    mob_w = cfg.mobility_weight
    kd = cfg.king_danger
    mat = cfg.material
    pw = cfg.pst_weight

    for s in range(NUM_SQUARES):
        p = board[s]
        if not p:
            continue
        t = p & 7
        c = p >> 3
        sign = 1 if c == CHO else -1
        material += sign * mat[t]
        pst += sign * PST[c][t][s] * pw // 100
        if t in kd and DANGER_ZONE[c ^ 1][s]:
            danger[c ^ 1] += kd[t]
        if t == PAWN:
            pawns[c].append(s)
        elif cfg.mobility and t in mob_w:
            mobility += sign * mob_w[t] * _mobility(board, s, t, c)

    pawn_structure = 0
    for c in (CHO, HAN):
        sign = 1 if c == CHO else -1
        ps = set(pawns[c])
        for s in pawns[c]:
            if COL_OF[s] < 8 and (s + 1) in ps:
                pawn_structure += sign * cfg.pawn_connected

    king_safety = danger[HAN] - danger[CHO]
    for c in (CHO, HAN):
        sign = 1 if c == CHO else -1
        guard = GUARD | (c << 3)
        for s in PALACE_STEPS[pos.king_sq[c]]:
            if board[s] == guard:
                king_safety += sign * cfg.guard_adjacent

    tempo = cfg.tempo if pos.side == CHO else -cfg.tempo

    total = material + pst + pawn_structure + king_safety + mobility + tempo
    flip = 1 if pos.side == CHO else -1
    return {
        "material": flip * material,
        "pst": flip * pst,
        "pawn_structure": flip * pawn_structure,
        "king_safety": flip * king_safety,
        "mobility": flip * mobility,
        "tempo": flip * tempo,
        "total": flip * total,
    }


def evaluate(pos: Position, cfg: EvalConfig | None = None) -> int:
    """Fast path: identical result to ``evaluate_breakdown(pos)['total']``."""
    cfg = cfg or DEFAULT_CONFIG
    board = pos.board
    score = 0
    danger0 = 0
    danger1 = 0
    pawns0: list[int] = []
    pawns1: list[int] = []
    mob_w = cfg.mobility_weight
    kd = cfg.king_danger
    mat = cfg.material
    pw = cfg.pst_weight
    use_mob = cfg.mobility
    pst_cho = PST[CHO]
    pst_han = PST[HAN]
    zone_cho = DANGER_ZONE[CHO]
    zone_han = DANGER_ZONE[HAN]

    for s in range(NUM_SQUARES):
        p = board[s]
        if not p:
            continue
        t = p & 7
        if p < 8:  # Cho
            score += mat[t] + pst_cho[t][s] * pw // 100
            if t == PAWN:
                pawns0.append(s)
                if zone_han[s]:
                    danger1 += kd[PAWN]
            else:
                if t in kd and zone_han[s]:
                    danger1 += kd[t]
                if use_mob and t in mob_w:
                    score += mob_w[t] * _mobility(board, s, t, CHO)
        else:
            score -= mat[t] + pst_han[t][s] * pw // 100
            if t == PAWN:
                pawns1.append(s)
                if zone_cho[s]:
                    danger0 += kd[PAWN]
            else:
                if t in kd and zone_cho[s]:
                    danger0 += kd[t]
                if use_mob and t in mob_w:
                    score -= mob_w[t] * _mobility(board, s, t, HAN)

    pc = cfg.pawn_connected
    ps0 = set(pawns0)
    for s in pawns0:
        if COL_OF[s] < 8 and (s + 1) in ps0:
            score += pc
    ps1 = set(pawns1)
    for s in pawns1:
        if COL_OF[s] < 8 and (s + 1) in ps1:
            score -= pc

    score += danger1 - danger0
    ga = cfg.guard_adjacent
    for s in PALACE_STEPS[pos.king_sq[CHO]]:
        if board[s] == GUARD:
            score += ga
    hg = GUARD | 8
    for s in PALACE_STEPS[pos.king_sq[HAN]]:
        if board[s] == hg:
            score -= ga

    if pos.side == CHO:
        return score + cfg.tempo
    return -score + cfg.tempo


DEFAULT_CONFIG = EvalConfig()


def format_breakdown(bd: dict[str, int]) -> str:
    names = {
        "material": "Material", "pst": "Piece-square", "pawn_structure": "Pawn structure",
        "king_safety": "King safety", "mobility": "Mobility", "tempo": "Tempo",
    }
    lines = [f"{names[k]:<15}{v / 100:+.2f}" for k, v in bd.items() if k != "total"]
    lines.append("-" * 21)
    lines.append(f"{'Total':<15}{bd['total'] / 100:+.2f}")
    return "\n".join(lines)
