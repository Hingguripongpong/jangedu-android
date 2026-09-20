"""Game-level rules on top of move generation.

Terminal conditions implemented:
  * 외통수 (checkmate): side to move is in check and has no legal move.
    (Stalemate cannot occur: 한수쉼 is always available when not in check.)
  * 빅장: the two kings face each other on an open file while the side to
    move is not in check.  The player who receives a bikjang must resolve it
    with the next move; if the position after that move is again bikjang the
    game ends (the responder declined to break it).  A move that gives
    check takes precedence: bikjang is void while the receiver is in check.
    How it ends is a ``RuleConfig`` choice: ``"draw"`` (classic), ``"points"``
    (the side with more material points wins, 한 +1.5 included, so this is
    never a tie unless the totals are exactly equal), ``"forced"`` (the
    responder may only resolve it or pass; the pass accepts the ending, which
    is decided on material points — this is Fairy-Stockfish's ``janggi``
    variant, and it makes our legal-move set identical to its perft counts)
    or ``"off"`` (facing kings are legal and nothing happens).
  * 한수쉼: two consecutive passes end the game — as a draw, or under
    ``"forced"`` decided on material points like Fairy-Stockfish.
  * 반복: the same position (incl. side to move) three times is a draw
    (``RuleConfig.repetition = "draw"``) or is ignored (``"off"``).
  * move limit (default 400 plies) as a practical safeguard.
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
    PASS_MOVE,
    Position,
)
from .movegen import generate_legal_moves
from .notation import SIDE_NAME, str_to_move

MAX_PLIES = 400

ONGOING = "ongoing"
CHECKMATE = "checkmate"
DRAW_BIKJANG = "draw_bikjang"
BIKJANG_POINTS = "bikjang_points"   # unresolved bikjang decided on material points
DRAW_REPETITION = "draw_repetition"
DRAW_PASSES = "draw_passes"
PASSES_POINTS = "passes_points"     # two consecutive passes decided on material points ("forced" rule)
DRAW_MOVE_LIMIT = "draw_move_limit"

BIKJANG_MODES = ("draw", "points", "forced", "off")
REPETITION_MODES = ("draw", "off")


@dataclass
class RuleConfig:
    """Selectable endings.  The defaults are the classic rules."""
    bikjang: str = "draw"       # draw | points | forced | off
    repetition: str = "draw"    # draw | off
    max_plies: int = MAX_PLIES

    def __post_init__(self) -> None:
        if self.bikjang not in BIKJANG_MODES:
            raise ValueError(f"bikjang must be one of {BIKJANG_MODES}, got {self.bikjang!r}")
        if self.repetition not in REPETITION_MODES:
            raise ValueError(f"repetition must be one of {REPETITION_MODES}, got {self.repetition!r}")

    @classmethod
    def from_dict(cls, d: dict | None) -> "RuleConfig":
        d = d or {}
        return cls(bikjang=str(d.get("bikjang", "draw")), repetition=str(d.get("repetition", "draw")),
                   max_plies=int(d.get("max_plies", MAX_PLIES)))

    def to_dict(self) -> dict:
        return {"bikjang": self.bikjang, "repetition": self.repetition, "max_plies": self.max_plies}

    @property
    def forced_bikjang(self) -> bool:
        return self.bikjang == "forced"

    def apply(self, pos: Position) -> Position:
        """Stamp the legality-affecting part of the rules onto a position (returns it)."""
        pos.forced_bikjang = self.forced_bikjang
        return pos


DEFAULT_RULES = RuleConfig()

# 점수제 (KJA): 차13 포7 마5 상3 사3 졸2, 한 +1.5 for moving second
POINT_VALUE = {KING: 0, GUARD: 3, CHARIOT: 13, CANNON: 7, HORSE: 5, ELEPHANT: 3, PAWN: 2}
HAN_BONUS_POINTS = 1.5


@dataclass
class GameStatus:
    status: str
    winner: int | None  # CHO / HAN / None
    in_check: bool
    bikjang: bool
    legal_move_count: int
    repetition_count: int = 1
    points: dict = field(default_factory=dict)

    @property
    def is_over(self) -> bool:
        return self.status != ONGOING

    def to_dict(self) -> dict:
        return {
            "status": self.status,
            "winner": SIDE_NAME[self.winner] if self.winner is not None else None,
            "in_check": self.in_check,
            "bikjang": self.bikjang,
            "legal_move_count": self.legal_move_count,
            "repetition_count": self.repetition_count,
            "points": self.points,
            "is_over": self.is_over,
        }


def bikjang_unresolved(pos: Position) -> bool:
    """The side that just moved received a bikjang and left the kings facing."""
    return pos.bikjang and pos.prev_bikjang()


def points_winner(pos: Position) -> int | None:
    mp = material_points(pos)
    if mp["cho"] > mp["han"]:
        return CHO
    if mp["han"] > mp["cho"]:
        return HAN
    return None


def rule_ending(pos: Position, rules: RuleConfig = DEFAULT_RULES) -> tuple[str, int | None] | None:
    """Non-mate ending for this position, as (status, winner) or None."""
    if pos.passes >= 2:
        if rules.bikjang == "forced":
            return PASSES_POINTS, points_winner(pos)
        return DRAW_PASSES, None
    if bikjang_unresolved(pos):
        if rules.bikjang == "draw":
            return DRAW_BIKJANG, None
        if rules.bikjang in ("points", "forced"):
            return BIKJANG_POINTS, points_winner(pos)
    if rules.repetition == "draw" and pos.repetition_count() >= 3:
        return DRAW_REPETITION, None
    if pos.ply >= rules.max_plies:
        return DRAW_MOVE_LIMIT, None
    return None


def is_draw_by_rule(pos: Position, max_plies: int = MAX_PLIES) -> str | None:
    """Classic-rules helper kept for compatibility: draw status or None."""
    end = rule_ending(pos, RuleConfig(max_plies=max_plies))
    return end[0] if end is not None else None


def game_status(pos: Position, legal: list[int] | None = None, max_plies: int | None = None,
                rules: RuleConfig = DEFAULT_RULES) -> GameStatus:
    if max_plies is not None and max_plies != rules.max_plies:
        rules = RuleConfig(rules.bikjang, rules.repetition, max_plies)
    in_check = pos.in_check()
    if legal is None:
        legal = generate_legal_moves(pos)
    pts = material_points(pos)
    if in_check and not legal:
        return GameStatus(CHECKMATE, pos.side ^ 1, True, False, 0, pos.repetition_count(), pts)
    end = rule_ending(pos, rules)
    if end is not None:
        return GameStatus(end[0], end[1], in_check, pos.bikjang, len(legal), pos.repetition_count(), pts)
    return GameStatus(ONGOING, None, in_check, pos.bikjang, len(legal), pos.repetition_count(), pts)


def material_points(pos: Position) -> dict[str, float]:
    pts = {CHO: 0.0, HAN: HAN_BONUS_POINTS}
    for p in pos.board:
        if p:
            pts[p >> 3] += POINT_VALUE[p & 7]
    return {"cho": pts[CHO], "han": pts[HAN]}


def replay(start_fen: str | None, moves: list[str], rules: RuleConfig = DEFAULT_RULES) -> Position:
    """Rebuild a position from a start FEN and a list of move strings,
    validating every move under ``rules``.  Raises ValueError on an illegal move."""
    pos = Position.from_fen(start_fen) if start_fen else Position.initial()
    rules.apply(pos)
    for i, text in enumerate(moves):
        m = str_to_move(text)
        legal = generate_legal_moves(pos)
        if m not in legal:
            raise ValueError(f"illegal move #{i + 1}: {text}")
        pos.make_move(m)
    return pos
