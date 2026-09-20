"""Position representation and make/unmake.

Piece encoding: ``piece = type | (colour << 3)`` so Cho pieces are 1..7 and
Han pieces are 9..15.  Empty squares are 0.

Move encoding: ``move = from_sq * 90 + to_sq`` (an int, cheap to hash and
compare).  ``PASS_MOVE = 8100`` encodes 한수쉼 (passing the turn).

State that cannot be derived from the board alone (consecutive passes,
bikjang flag, position history for repetition) is kept on the Position and
restored by ``unmake_move``.
"""
from __future__ import annotations

from .geometry import (
    CHO,
    HAN,
    COL_OF,
    COLS,
    ELEPHANT_ATTACKS,
    HORSE_ATTACKS,
    IN_PALACE,
    NUM_SQUARES,
    PALACE_STEPS,
    PAWN_ATTACKS,
    RAYS_DIAG,
    RAYS_ORTH,
    ROWS,
    rc,
    sq,
)
from .hashing import ZOBRIST_PIECE, ZOBRIST_SIDE, compute_hash

EMPTY = 0
KING, GUARD, CHARIOT, CANNON, HORSE, ELEPHANT, PAWN = 1, 2, 3, 4, 5, 6, 7
PIECE_TYPES = (KING, GUARD, CHARIOT, CANNON, HORSE, ELEPHANT, PAWN)

PASS_MOVE = NUM_SQUARES * NUM_SQUARES  # 8100
NO_MOVE = 0  # from 0 to 0 is never a legal move, so 0 doubles as "none"


def make_piece(color: int, ptype: int) -> int:
    return ptype | (color << 3)


def piece_color(p: int) -> int:
    return p >> 3


def piece_type(p: int) -> int:
    return p & 7


def encode_move(frm: int, to: int) -> int:
    return frm * NUM_SQUARES + to


def move_from(m: int) -> int:
    return m // NUM_SQUARES


def move_to(m: int) -> int:
    return m % NUM_SQUARES


# FEN letters (Fairy-Stockfish compatible): upper = Cho (bottom, 'w'), lower = Han ('b')
_FEN_LETTER = {KING: "k", GUARD: "a", CHARIOT: "r", CANNON: "c", HORSE: "n", ELEPHANT: "b", PAWN: "p"}
_LETTER_TYPE = {v: k for k, v in _FEN_LETTER.items()}

# Korean setup notation: 4 letters for (horse/elephant) slots seen from the
# owning player's left to right.  마=horse(M), 상=elephant(S)
SETUPS = {
    "마상상마": "MSSM",
    "상마상마": "SMSM",
    "마상마상": "MSMS",
    "상마마상": "SMMS",
}
DEFAULT_SETUP = "마상상마"


class Position:
    __slots__ = (
        "board",
        "side",
        "hash",
        "king_sq",
        "passes",
        "bikjang",
        "ply",
        "history",
        "_stack",
        "forced_bikjang",
        "irrev",
    )

    def __init__(self) -> None:
        self.board: list[int] = [EMPTY] * NUM_SQUARES
        self.side: int = CHO
        self.hash: int = 0
        self.king_sq: list[int] = [-1, -1]
        self.passes: int = 0  # consecutive passes so far
        self.bikjang: bool = False  # kings face each other, side to move not in check
        self.ply: int = 0
        self.history: list[int] = []  # hashes of all positions incl. current
        self._stack: list[tuple] = []
        # Rule variant carried with the position because it changes the legal move set:
        # when True, a side facing a 빅장 may only resolve it (or pass to accept the ending),
        # exactly like Fairy-Stockfish's janggi variant.  False = any move is legal.
        self.forced_bikjang: bool = False
        # index into ``history`` of the last irreversible move (a capture): positions before it
        # can never repeat, so repetition checks scan only history[irrev:].
        self.irrev: int = 0

    # ------------------------------------------------------------------ setup
    @classmethod
    def initial(cls, cho_setup: str = DEFAULT_SETUP, han_setup: str = DEFAULT_SETUP) -> "Position":
        pos = cls()
        pos._place_back_rank(CHO, 9, cho_setup)
        pos._place_back_rank(HAN, 0, han_setup)
        b = pos.board
        b[sq(8, 4)] = make_piece(CHO, KING)
        b[sq(1, 4)] = make_piece(HAN, KING)
        for c in (1, 7):
            b[sq(7, c)] = make_piece(CHO, CANNON)
            b[sq(2, c)] = make_piece(HAN, CANNON)
        for c in (0, 2, 4, 6, 8):
            b[sq(6, c)] = make_piece(CHO, PAWN)
            b[sq(3, c)] = make_piece(HAN, PAWN)
        pos._finish_setup()
        return pos

    def _place_back_rank(self, color: int, row: int, setup: str) -> None:
        code = SETUPS.get(setup, setup).upper()
        if len(code) != 4 or sorted(code) != ["M", "M", "S", "S"]:
            raise ValueError(f"invalid setup {setup!r}; use one of {list(SETUPS)}")
        slots = [HORSE if ch == "M" else ELEPHANT for ch in code]
        # From the owner's own left to right.  Cho sits at the bottom so its
        # left is file 0; Han sits at the top so its left is file 8.
        order = [CHARIOT, slots[0], slots[1], GUARD, EMPTY, GUARD, slots[2], slots[3], CHARIOT]
        cols = range(COLS) if color == CHO else range(COLS - 1, -1, -1)
        for ptype, c in zip(order, cols):
            if ptype:
                self.board[sq(row, c)] = make_piece(color, ptype)

    def _finish_setup(self) -> None:
        self.king_sq = [-1, -1]
        for s, p in enumerate(self.board):
            if p and (p & 7) == KING:
                self.king_sq[p >> 3] = s
        if -1 in self.king_sq:
            raise ValueError("both kings must be on the board")
        for color in (CHO, HAN):
            if IN_PALACE[self.king_sq[color]] != color:
                raise ValueError("king outside its palace")
        self.hash = compute_hash(self.board, self.side)
        self.history = [self.hash]
        self._stack = []
        self.irrev = 0
        self.ply = 0
        self.passes = 0
        self.bikjang = self.kings_facing() and not self.in_check()

    # ------------------------------------------------------------------ FEN
    @classmethod
    def from_fen(cls, fen: str) -> "Position":
        parts = fen.strip().split()
        if not parts:
            raise ValueError("empty FEN")
        rows = parts[0].split("/")
        if len(rows) != ROWS:
            raise ValueError(f"FEN must have {ROWS} ranks, got {len(rows)}")
        pos = cls()
        for r, row in enumerate(rows):
            c = 0
            for ch in row:
                if ch.isdigit():
                    c += int(ch)
                else:
                    if c >= COLS:
                        raise ValueError(f"rank {r} too long")
                    ptype = _LETTER_TYPE.get(ch.lower())
                    if ptype is None:
                        raise ValueError(f"unknown piece letter {ch!r}")
                    color = CHO if ch.isupper() else HAN
                    pos.board[sq(r, c)] = make_piece(color, ptype)
                    c += 1
            if c != COLS:
                raise ValueError(f"rank {r} has {c} files, expected {COLS}")
        if len(parts) > 1:
            side = parts[1].lower()
            if side in ("w", "cho", "c", "초"):
                pos.side = CHO
            elif side in ("b", "han", "h", "한"):
                pos.side = HAN
            else:
                raise ValueError(f"unknown side {parts[1]!r}")
        pos._finish_setup()
        if len(parts) > 5 and parts[5].isdigit():
            fullmove = max(1, int(parts[5]))
            pos.ply = (fullmove - 1) * 2 + (1 if pos.side == HAN else 0)
        return pos

    def to_fen(self) -> str:
        out = []
        for r in range(ROWS):
            run = 0
            row = ""
            for c in range(COLS):
                p = self.board[sq(r, c)]
                if p:
                    if run:
                        row += str(run)
                        run = 0
                    letter = _FEN_LETTER[p & 7]
                    row += letter.upper() if (p >> 3) == CHO else letter
                else:
                    run += 1
            if run:
                row += str(run)
            out.append(row)
        side = "w" if self.side == CHO else "b"
        return f"{'/'.join(out)} {side} - - 0 {self.ply // 2 + 1}"

    def copy(self) -> "Position":
        pos = Position()
        pos.board = self.board[:]
        pos.side = self.side
        pos.hash = self.hash
        pos.king_sq = self.king_sq[:]
        pos.passes = self.passes
        pos.bikjang = self.bikjang
        pos.ply = self.ply
        pos.history = self.history[:]
        pos._stack = self._stack[:]
        pos.forced_bikjang = self.forced_bikjang
        pos.irrev = self.irrev
        return pos

    def mirrored(self) -> "Position":
        """Colour-and-row flipped copy (Cho <-> Han).  Used for symmetry tests."""
        pos = Position()
        for s, p in enumerate(self.board):
            if p:
                r, c = rc(s)
                pos.board[sq(ROWS - 1 - r, c)] = make_piece((p >> 3) ^ 1, p & 7)
        pos.side = self.side ^ 1
        pos._finish_setup()
        pos.passes = self.passes
        pos.forced_bikjang = self.forced_bikjang
        return pos

    # ------------------------------------------------------------------ queries
    def piece_at(self, s: int) -> int:
        return self.board[s]

    def kings_facing(self) -> bool:
        k0, k1 = self.king_sq
        if COL_OF[k0] != COL_OF[k1]:
            return False
        lo, hi = (k0, k1) if k0 < k1 else (k1, k0)
        board = self.board
        for s in range(lo + COLS, hi, COLS):
            if board[s]:
                return False
        return True

    def attacked_by(self, target: int, color: int) -> bool:
        """Is ``target`` attacked by a piece of ``color``?

        Encodes: chariot slides (incl. palace diagonals), cannon jumps over a
        single non-cannon screen (incl. palace diagonals) and never captures
        a cannon, horse/elephant leg blocking, pawn forward/side/palace
        diagonal, king/guard palace steps.
        """
        board = self.board
        cbit = color << 3
        target_is_cannon = (board[target] & 7) == CANNON
        chariot = CHARIOT | cbit
        cannon = CANNON | cbit

        for rays in (RAYS_ORTH[target], RAYS_DIAG[target]):
            for ray in rays:
                screen = False
                for s in ray:
                    p = board[s]
                    if not p:
                        continue
                    if not screen:
                        if p == chariot:
                            return True
                        if (p & 7) == CANNON:
                            break  # a cannon can neither be jumped nor slide
                        screen = True
                    else:
                        if p == cannon and not target_is_cannon:
                            return True
                        break

        horse = HORSE | cbit
        for block, origin in HORSE_ATTACKS[target]:
            if board[origin] == horse and not board[block]:
                return True

        elephant = ELEPHANT | cbit
        for b1, b2, origin in ELEPHANT_ATTACKS[target]:
            if board[origin] == elephant and not board[b1] and not board[b2]:
                return True

        pawn = PAWN | cbit
        for origin in PAWN_ATTACKS[color][target]:
            if board[origin] == pawn:
                return True

        king = KING | cbit
        guard = GUARD | cbit
        for origin in PALACE_STEPS[target]:
            p = board[origin]
            if p == king or p == guard:
                return True
        return False

    def in_check(self, color: int | None = None) -> bool:
        if color is None:
            color = self.side
        return self.attacked_by(self.king_sq[color], color ^ 1)

    def prev_bikjang(self) -> bool:
        return self._stack[-1][4] if self._stack else False

    def is_repetition(self) -> bool:
        """True if the current position occurred before (2-fold, search use).

        Only positions since the last capture are examined (captures are
        irreversible), so the check stays O(1)-ish deep into long games.
        """
        h = self.hash
        hist = self.history
        stop = self.irrev - 1
        for i in range(len(hist) - 3, stop, -2):
            if hist[i] == h:
                return True
        return False

    def repetition_count(self) -> int:
        h = self.hash
        hist = self.history
        n = 0
        for i in range(len(hist) - 1, self.irrev - 1, -2):   # same side to move only
            if hist[i] == h:
                n += 1
        return n

    def stack_depth(self) -> int:
        return len(self._stack)

    def repetition_context(self) -> tuple[int, ...]:
        """Canonical (sorted) multiset of position hashes since the last capture.

        Every one of these can still become a repetition, so two positions with the same
        board but a different context may be analysed differently (a line that repeats is
        a draw in one and not in the other).  Used by state-aware analysis caches.
        """
        return tuple(sorted(self.history[self.irrev:]))

    def state_key(self, rules_tag: tuple = (), engine_id: str = "", limit_horizon: int = 96) -> tuple:
        """Hashable identity of the *game state* (not just the board) for analysis caches.

        Components: board hash (includes side), pending pass, 빅장 flags, repetition context,
        plies left before the move limit while that is within ``limit_horizon``, the legality
        variant stamped on the position, plus caller-supplied rule and engine tags.
        """
        left = None
        if rules_tag and isinstance(rules_tag[-1], int):
            remaining = rules_tag[-1] - self.ply
            if remaining <= limit_horizon:
                left = max(remaining, 0)
        return (self.hash, self.side, self.passes, self.bikjang, self.prev_bikjang(), self.repetition_context(),
                left, self.forced_bikjang, tuple(rules_tag), engine_id)

    # ------------------------------------------------------------------ make / unmake
    def make_move(self, m: int) -> None:
        board = self.board
        side = self.side
        h = self.hash
        if m == PASS_MOVE:
            self._stack.append((m, EMPTY, h, self.passes, self.bikjang, self.irrev))
            self.passes += 1
        else:
            frm, to = divmod(m, NUM_SQUARES)
            p = board[frm]
            cap = board[to]
            self._stack.append((m, cap, h, self.passes, self.bikjang, self.irrev))
            zp = ZOBRIST_PIECE[p]
            h ^= zp[frm] ^ zp[to]
            if cap:
                h ^= ZOBRIST_PIECE[cap][to]
                self.irrev = len(self.history)       # index the post-capture position will get in history
            board[to] = p
            board[frm] = EMPTY
            if (p & 7) == KING:
                self.king_sq[side] = to
            self.passes = 0
        h ^= ZOBRIST_SIDE
        self.hash = h
        self.side = side ^ 1
        self.ply += 1
        self.history.append(h)
        ks = self.king_sq
        if COL_OF[ks[0]] == COL_OF[ks[1]] and self.kings_facing() and not self.attacked_by(ks[side ^ 1], side):
            self.bikjang = True
        else:
            self.bikjang = False

    def unmake_move(self) -> None:
        m, cap, prev_hash, prev_passes, prev_bikjang, prev_irrev = self._stack.pop()
        self.irrev = prev_irrev
        self.history.pop()
        self.ply -= 1
        self.side ^= 1
        if m != PASS_MOVE:
            frm, to = divmod(m, NUM_SQUARES)
            board = self.board
            p = board[to]
            board[frm] = p
            board[to] = cap
            if (p & 7) == KING:
                self.king_sq[self.side] = frm
        self.hash = prev_hash
        self.passes = prev_passes
        self.bikjang = prev_bikjang

    def unwind_to(self, depth: int) -> None:
        """Undo moves until the undo stack has ``depth`` entries (used when a
        search is aborted mid-tree)."""
        while len(self._stack) > depth:
            self.unmake_move()

    def last_move(self) -> int | None:
        return self._stack[-1][0] if self._stack else None

    # ------------------------------------------------------------------ debug
    def ascii(self) -> str:
        from .notation import piece_hanja

        lines = []
        lines.append("    " + "  ".join(str(c + 1) for c in range(COLS)))
        for r in range(ROWS):
            cells = []
            for c in range(COLS):
                p = self.board[sq(r, c)]
                cells.append(piece_hanja(p) if p else "・")
            lines.append(f"{(r + 1) % 10:>2}  " + " ".join(cells))
        lines.append(f"side: {'초(Cho)' if self.side == CHO else '한(Han)'}  ply: {self.ply}"
                     f"  passes: {self.passes}  bikjang: {self.bikjang}")
        return "\n".join(lines)
