package com.example.janggiai.game

/**
 * Board constants and piece encoding — identical to the Python reference engine
 * (`engine/board.py`, `engine/geometry.py`):
 *
 *  * squares `0..89` as `row * 9 + col`; row 0 is Han's back rank (top), row 9 Cho's (bottom)
 *  * `CHO = 0` (moves first, upper-case FEN letters, UCI side `w`), `HAN = 1`
 *  * `piece = type | (colour shl 3)`: Cho pieces 1..7, Han pieces 9..15, empty 0
 */
object Board {
    const val ROWS = 10
    const val COLS = 9
    const val NUM_SQUARES = ROWS * COLS

    const val CHO = 0
    const val HAN = 1

    fun sq(row: Int, col: Int): Int = row * COLS + col
    fun rowOf(sq: Int): Int = sq / COLS
    fun colOf(sq: Int): Int = sq % COLS
    fun onBoard(row: Int, col: Int): Boolean = row in 0 until ROWS && col in 0 until COLS
    fun opposite(side: Int): Int = side xor 1
}

object Piece {
    const val EMPTY = 0
    const val KING = 1
    const val GUARD = 2
    const val CHARIOT = 3
    const val CANNON = 4
    const val HORSE = 5
    const val ELEPHANT = 6
    const val PAWN = 7

    val TYPES = intArrayOf(KING, GUARD, CHARIOT, CANNON, HORSE, ELEPHANT, PAWN)

    fun make(color: Int, type: Int): Int = type or (color shl 3)
    fun colorOf(p: Int): Int = p shr 3
    fun typeOf(p: Int): Int = p and 7

    /** FEN letters (Fairy-Stockfish janggi): k a r c n b p — upper case = Cho. */
    fun fenLetter(type: Int): Char = when (type) {
        KING -> 'k'; GUARD -> 'a'; CHARIOT -> 'r'; CANNON -> 'c'; HORSE -> 'n'; ELEPHANT -> 'b'; PAWN -> 'p'
        else -> throw IllegalArgumentException("bad piece type $type")
    }

    fun typeOfFenLetter(c: Char): Int? = when (c.lowercaseChar()) {
        'k' -> KING; 'a' -> GUARD; 'r' -> CHARIOT; 'c' -> CANNON; 'n' -> HORSE; 'b' -> ELEPHANT; 'p' -> PAWN
        else -> null
    }
}

/** Move encoding: `from * 90 + to`; [PASS] = 한수쉼; [NONE] = no move (0 is never a legal move). */
object Move {
    const val PASS = Board.NUM_SQUARES * Board.NUM_SQUARES  // 8100
    const val NONE = 0

    fun encode(from: Int, to: Int): Int = from * Board.NUM_SQUARES + to
    fun from(m: Int): Int = m / Board.NUM_SQUARES
    fun to(m: Int): Int = m % Board.NUM_SQUARES
    fun isPass(m: Int): Boolean = m == PASS
}
