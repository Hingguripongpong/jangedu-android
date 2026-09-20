"""Human-readable notation.

Squares use the Korean two-digit convention ``RC``: R is the row counted
from Han's side 1..9 then 0 for the 10th row, C is the file 1..9 counted
from Cho's left.  Cho's king therefore starts on ``95``, Han's on ``25``.

Canonical machine move string: ``"95-73"`` (or ``"pass"``).
"""
from __future__ import annotations

import re

from .board import (
    CANNON,
    CHARIOT,
    CHO,
    ELEPHANT,
    GUARD,
    HORSE,
    KING,
    PASS_MOVE,
    PAWN,
    encode_move,
    move_from,
    move_to,
    piece_color,
    piece_type,
)
from .geometry import COLS, rc, sq

HANJA = {
    CHO: {KING: "楚", GUARD: "士", CHARIOT: "車", CANNON: "包", HORSE: "馬", ELEPHANT: "象", PAWN: "卒"},
    1: {KING: "漢", GUARD: "士", CHARIOT: "車", CANNON: "包", HORSE: "馬", ELEPHANT: "象", PAWN: "兵"},
}
KOREAN = {
    CHO: {KING: "궁", GUARD: "사", CHARIOT: "차", CANNON: "포", HORSE: "마", ELEPHANT: "상", PAWN: "졸"},
    1: {KING: "궁", GUARD: "사", CHARIOT: "차", CANNON: "포", HORSE: "마", ELEPHANT: "상", PAWN: "병"},
}
ENGLISH = {KING: "king", GUARD: "guard", CHARIOT: "chariot", CANNON: "cannon",
           HORSE: "horse", ELEPHANT: "elephant", PAWN: "pawn"}
SIDE_NAME = {CHO: "cho", 1: "han"}
SIDE_KOREAN = {CHO: "초", 1: "한"}


def piece_hanja(p: int) -> str:
    return HANJA[piece_color(p)][piece_type(p)]


def piece_korean(p: int) -> str:
    return KOREAN[piece_color(p)][piece_type(p)]


def square_to_str(s: int) -> str:
    r, c = rc(s)
    return f"{(r + 1) % 10}{c + 1}"


def str_to_square(text: str) -> int:
    text = text.strip()
    if len(text) != 2 or not text.isdigit():
        raise ValueError(f"bad square {text!r}")
    r = (int(text[0]) - 1) % 10
    c = int(text[1]) - 1
    if not 0 <= c < COLS:
        raise ValueError(f"bad square {text!r}")
    return sq(r, c)


def move_to_str(m: int) -> str:
    """Canonical machine form, e.g. ``95-73`` or ``pass``."""
    if m == PASS_MOVE:
        return "pass"
    return f"{square_to_str(move_from(m))}-{square_to_str(move_to(m))}"


_MOVE_RE = re.compile(r"^\s*(\d\d)\s*(?:-|→|->|>)?\s*(\d\d)\s*$")


def str_to_move(text: str) -> int:
    t = text.strip().lower()
    if t in ("pass", "한수쉼", "쉼", "p", "--"):
        return PASS_MOVE
    m = _MOVE_RE.match(t)
    if not m:
        raise ValueError(f"bad move {text!r}")
    return encode_move(str_to_square(m.group(1)), str_to_square(m.group(2)))


def describe_move(board: list[int], m: int) -> str:
    """Pretty form with the moving piece: ``馬 92→73`` / ``車 01x04`` / ``한수쉼``."""
    if m == PASS_MOVE:
        return "한수쉼"
    frm, to = move_from(m), move_to(m)
    p = board[frm]
    name = piece_hanja(p) if p else "?"
    sep = "x" if board[to] else "→"
    return f"{name} {square_to_str(frm)}{sep}{square_to_str(to)}"
