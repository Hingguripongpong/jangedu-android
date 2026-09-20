package com.example.janggiai.game

import com.example.janggiai.game.Board.CHO
import com.example.janggiai.game.Board.COLS
import com.example.janggiai.game.Board.HAN
import com.example.janggiai.game.Board.NUM_SQUARES
import com.example.janggiai.game.Board.ROWS
import com.example.janggiai.game.Board.sq
import com.example.janggiai.game.Piece.CANNON
import com.example.janggiai.game.Piece.CHARIOT
import com.example.janggiai.game.Piece.ELEPHANT
import com.example.janggiai.game.Piece.EMPTY
import com.example.janggiai.game.Piece.GUARD
import com.example.janggiai.game.Piece.HORSE
import com.example.janggiai.game.Piece.KING
import com.example.janggiai.game.Piece.PAWN

/**
 * Mutable position with make/unmake (port of `engine/board.py`).
 *
 * State that cannot be derived from the board alone — consecutive passes, the 빅장 flag, the
 * hash history for repetition — is kept here and restored by [unmakeMove].
 */
class Position {
    val board = IntArray(NUM_SQUARES)
    var side = CHO
    var hash = 0L
    val kingSq = intArrayOf(-1, -1)
    /** Consecutive passes so far (2 = game over). */
    var passes = 0
    /** Kings face each other on an open file and the side to move is not in check. */
    var bikjang = false
    var ply = 0
    /** Hashes of all positions since the start, including the current one. */
    val history = ArrayList<Long>()
    /** Index into [history] of the position reached by the last capture (repetition scan start). */
    var irrev = 0
    /**
     * Rule variant carried with the position because it changes the legal move set: when true the
     * side facing a 빅장 may only resolve it or pass (Fairy-Stockfish `janggi`); false = any move.
     */
    var forcedBikjang = false

    private class Undo(val move: Int, val captured: Int, val hash: Long, val passes: Int,
                       val bikjang: Boolean, val irrev: Int)

    private val stack = ArrayList<Undo>()

    // ------------------------------------------------------------------ setup
    companion object {
        val SETUPS: Map<String, String> = linkedMapOf(
            "마상상마" to "MSSM", "상마상마" to "SMSM", "마상마상" to "MSMS", "상마마상" to "SMMS",
        )
        const val DEFAULT_SETUP = "마상상마"
        const val START_FEN = "rnba1abnr/4k4/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/4K4/RNBA1ABNR w - - 0 1"

        fun initial(choSetup: String = DEFAULT_SETUP, hanSetup: String = DEFAULT_SETUP): Position {
            val pos = Position()
            pos.placeBackRank(CHO, 9, choSetup)
            pos.placeBackRank(HAN, 0, hanSetup)
            val b = pos.board
            b[sq(8, 4)] = Piece.make(CHO, KING)
            b[sq(1, 4)] = Piece.make(HAN, KING)
            for (c in intArrayOf(1, 7)) {
                b[sq(7, c)] = Piece.make(CHO, CANNON)
                b[sq(2, c)] = Piece.make(HAN, CANNON)
            }
            for (c in intArrayOf(0, 2, 4, 6, 8)) {
                b[sq(6, c)] = Piece.make(CHO, PAWN)
                b[sq(3, c)] = Piece.make(HAN, PAWN)
            }
            pos.finishSetup()
            return pos
        }

        /** Parses a Fairy-Stockfish compatible janggi FEN (upper case = Cho, `w` = Cho to move). */
        fun fromFen(fen: String): Position {
            val parts = fen.trim().split(Regex("\\s+"))
            if (parts.isEmpty() || parts[0].isEmpty()) throw IllegalArgumentException("empty FEN")
            val rows = parts[0].split("/")
            if (rows.size != ROWS) throw IllegalArgumentException("FEN must have $ROWS ranks, got ${rows.size}")
            val pos = Position()
            for ((r, row) in rows.withIndex()) {
                var c = 0
                for (ch in row) {
                    if (ch.isDigit()) {
                        c += ch - '0'
                    } else {
                        if (c >= COLS) throw IllegalArgumentException("rank $r too long")
                        val t = Piece.typeOfFenLetter(ch) ?: throw IllegalArgumentException("unknown piece letter '$ch'")
                        val color = if (ch.isUpperCase()) CHO else HAN
                        pos.board[sq(r, c)] = Piece.make(color, t)
                        c += 1
                    }
                }
                if (c != COLS) throw IllegalArgumentException("rank $r has $c files, expected $COLS")
            }
            if (parts.size > 1) {
                pos.side = when (parts[1].lowercase()) {
                    "w", "cho", "c", "초" -> CHO
                    "b", "han", "h", "한" -> HAN
                    else -> throw IllegalArgumentException("unknown side '${parts[1]}'")
                }
            }
            pos.finishSetup()
            if (parts.size > 5 && parts[5].all { it.isDigit() }) {
                val fullmove = maxOf(1, parts[5].toInt())
                pos.ply = (fullmove - 1) * 2 + (if (pos.side == HAN) 1 else 0)
            }
            return pos
        }

        /** Builds a position from explicit pieces (tests / position editor). */
        fun build(pieces: Map<Int, Int>, side: Int = CHO): Position {
            val pos = Position()
            for ((s, p) in pieces) pos.board[s] = p
            pos.side = side
            pos.finishSetup()
            return pos
        }
    }

    private fun placeBackRank(color: Int, row: Int, setup: String) {
        val code = (SETUPS[setup] ?: setup).uppercase()
        if (code.length != 4 || code.toCharArray().sorted().joinToString("") != "MMSS")
            throw IllegalArgumentException("invalid setup '$setup'; use one of ${SETUPS.keys}")
        val slots = code.map { if (it == 'M') HORSE else ELEPHANT }
        // From the owner's own left to right: Cho's left is file 0, Han's left is file 8.
        val order = intArrayOf(CHARIOT, slots[0], slots[1], GUARD, EMPTY, GUARD, slots[2], slots[3], CHARIOT)
        val cols = if (color == CHO) (0 until COLS).toList() else (COLS - 1 downTo 0).toList()
        for (i in order.indices) if (order[i] != EMPTY) board[sq(row, cols[i])] = Piece.make(color, order[i])
    }

    /** Recomputes derived state after the board/side were edited directly. */
    fun finishSetup() {
        kingSq[0] = -1; kingSq[1] = -1
        for (s in 0 until NUM_SQUARES) {
            val p = board[s]
            if (p != 0 && Piece.typeOf(p) == KING) kingSq[Piece.colorOf(p)] = s
        }
        if (kingSq[0] < 0 || kingSq[1] < 0) throw IllegalArgumentException("both kings must be on the board")
        for (color in intArrayOf(CHO, HAN))
            if (Geometry.IN_PALACE[kingSq[color]] != color) throw IllegalArgumentException("king outside its palace")
        hash = Zobrist.compute(board, side)
        history.clear(); history += hash
        stack.clear()
        irrev = 0
        ply = 0
        passes = 0
        bikjang = kingsFacing() && !inCheck()
    }

    fun toFen(): String {
        val sb = StringBuilder()
        for (r in 0 until ROWS) {
            var run = 0
            for (c in 0 until COLS) {
                val p = board[sq(r, c)]
                if (p != 0) {
                    if (run > 0) { sb.append(run); run = 0 }
                    val letter = Piece.fenLetter(Piece.typeOf(p))
                    sb.append(if (Piece.colorOf(p) == CHO) letter.uppercaseChar() else letter)
                } else run++
            }
            if (run > 0) sb.append(run)
            if (r < ROWS - 1) sb.append('/')
        }
        sb.append(' ').append(if (side == CHO) 'w' else 'b').append(" - - 0 ").append(ply / 2 + 1)
        return sb.toString()
    }

    fun copy(): Position {
        val pos = Position()
        board.copyInto(pos.board)
        pos.side = side
        pos.hash = hash
        kingSq.copyInto(pos.kingSq)
        pos.passes = passes
        pos.bikjang = bikjang
        pos.ply = ply
        pos.history.addAll(history)
        pos.stack.addAll(stack)
        pos.forcedBikjang = forcedBikjang
        pos.irrev = irrev
        return pos
    }

    // ------------------------------------------------------------------ queries
    fun pieceAt(s: Int): Int = board[s]

    fun kingsFacing(): Boolean {
        val k0 = kingSq[0]; val k1 = kingSq[1]
        if (Geometry.COL_OF[k0] != Geometry.COL_OF[k1]) return false
        val lo = minOf(k0, k1); val hi = maxOf(k0, k1)
        var s = lo + COLS
        while (s < hi) { if (board[s] != 0) return false; s += COLS }
        return true
    }

    /**
     * Is [target] attacked by a piece of [color]?  Chariot slides (incl. palace diagonals), cannon
     * jumps exactly one non-cannon screen (incl. palace diagonals) and never captures a cannon,
     * horse/elephant leg blocking, pawn forward/side/palace diagonal, king/guard palace steps.
     */
    fun attackedBy(target: Int, color: Int): Boolean {
        val cbit = color shl 3
        val targetIsCannon = Piece.typeOf(board[target]) == CANNON
        val chariot = CHARIOT or cbit
        val cannon = CANNON or cbit

        for (rays in arrayOf(Geometry.RAYS_ORTH[target], Geometry.RAYS_DIAG[target])) {
            for (ray in rays) {
                var screen = false
                for (s in ray) {
                    val p = board[s]
                    if (p == 0) continue
                    if (!screen) {
                        if (p == chariot) return true
                        if (Piece.typeOf(p) == CANNON) break   // a cannon can neither be jumped nor slide
                        screen = true
                    } else {
                        if (p == cannon && !targetIsCannon) return true
                        break
                    }
                }
            }
        }
        val horse = HORSE or cbit
        for (ho in Geometry.HORSE_ATTACKS[target]) if (board[ho[1]] == horse && board[ho[0]] == 0) return true
        val elephant = ELEPHANT or cbit
        for (eo in Geometry.ELEPHANT_ATTACKS[target])
            if (board[eo[2]] == elephant && board[eo[0]] == 0 && board[eo[1]] == 0) return true
        val pawn = PAWN or cbit
        for (o in Geometry.PAWN_ATTACKS[color][target]) if (board[o] == pawn) return true
        val king = KING or cbit
        val guard = GUARD or cbit
        for (o in Geometry.PALACE_STEPS[target]) { val p = board[o]; if (p == king || p == guard) return true }
        return false
    }

    fun inCheck(color: Int = side): Boolean = attackedBy(kingSq[color], color xor 1)

    fun prevBikjang(): Boolean = if (stack.isEmpty()) false else stack[stack.size - 1].bikjang

    /** True if the current position occurred before since the last capture (2-fold, search use). */
    fun isRepetition(): Boolean {
        val h = hash
        var i = history.size - 3
        while (i >= irrev) { if (history[i] == h) return true; i -= 2 }
        return false
    }

    /** How many times the current position (same side to move) has occurred since the last capture. */
    fun repetitionCount(): Int {
        val h = hash
        var n = 0
        var i = history.size - 1
        while (i >= irrev) { if (history[i] == h) n++; i -= 2 }
        return n
    }

    fun stackDepth(): Int = stack.size

    fun lastMove(): Int? = if (stack.isEmpty()) null else stack[stack.size - 1].move

    /** Moves played since the position was set up, oldest first. */
    fun movesPlayed(): List<Int> = stack.map { it.move }

    /** Canonical repetition context: sorted hashes since the last capture (state-aware cache keys). */
    fun repetitionContext(): List<Long> = history.subList(irrev, history.size).sorted()

    /** Identity of the game *state* (not just the board) for analysis caches. */
    fun stateKey(): String =
        "$hash|$side|$passes|$bikjang|${prevBikjang()}|$forcedBikjang|${repetitionContext().joinToString(",")}"

    // ------------------------------------------------------------------ make / unmake
    fun makeMove(m: Int) {
        val mover = side
        var h = hash
        if (m == Move.PASS) {
            stack += Undo(m, EMPTY, h, passes, bikjang, irrev)
            passes += 1
        } else {
            val from = Move.from(m); val to = Move.to(m)
            val p = board[from]
            val cap = board[to]
            stack += Undo(m, cap, h, passes, bikjang, irrev)
            val zp = Zobrist.PIECE[p]
            h = h xor zp[from] xor zp[to]
            if (cap != 0) {
                h = h xor Zobrist.PIECE[cap][to]
                irrev = history.size          // index the post-capture position gets in history
            }
            board[to] = p
            board[from] = EMPTY
            if (Piece.typeOf(p) == KING) kingSq[mover] = to
            passes = 0
        }
        h = h xor Zobrist.SIDE
        hash = h
        side = mover xor 1
        ply += 1
        history += h
        val k0 = kingSq[0]; val k1 = kingSq[1]
        bikjang = Geometry.COL_OF[k0] == Geometry.COL_OF[k1] && kingsFacing() && !attackedBy(kingSq[mover xor 1], mover)
    }

    fun unmakeMove() {
        val u = stack.removeAt(stack.size - 1)
        irrev = u.irrev
        history.removeAt(history.size - 1)
        ply -= 1
        side = side xor 1
        if (u.move != Move.PASS) {
            val from = Move.from(u.move); val to = Move.to(u.move)
            val p = board[to]
            board[from] = p
            board[to] = u.captured
            if (Piece.typeOf(p) == KING) kingSq[side] = from
        }
        hash = u.hash
        passes = u.passes
        bikjang = u.bikjang
    }

    fun unwindTo(depth: Int) { while (stack.size > depth) unmakeMove() }

    /** Colour-and-row flipped copy (Cho <-> Han) for symmetry tests. */
    fun mirrored(): Position {
        val pos = Position()
        for (s in 0 until NUM_SQUARES) {
            val p = board[s]
            if (p != 0) pos.board[Geometry.MIRROR_SQ[s]] = Piece.make(Piece.colorOf(p) xor 1, Piece.typeOf(p))
        }
        pos.side = side xor 1
        pos.finishSetup()
        pos.passes = passes
        pos.forcedBikjang = forcedBikjang
        return pos
    }

    override fun toString(): String = toFen()
}
