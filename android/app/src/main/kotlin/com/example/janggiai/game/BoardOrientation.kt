package com.example.janggiai.game

/**
 * The only place that maps engine squares to what is drawn on screen.  Game state, moves, FEN and
 * notation always use the canonical engine coordinates (row 0 = Han's back rank at the top); the
 * board UI asks this object for the display position and for the engine square under a tap.
 *
 * Flipped = 180° rotation of the 9x10 board: row -> 9 - row, col -> 8 - col, which on the
 * row-major index is simply 89 - square.
 */
object BoardOrientation {
    fun engineToDisplay(square: Int, flipped: Boolean): Int = if (flipped) Board.NUM_SQUARES - 1 - square else square
    fun displayToEngine(displaySquare: Int, flipped: Boolean): Int = engineToDisplay(displaySquare, flipped)   // an involution

    /** Display (row, col) of an engine square. */
    fun displayRowCol(square: Int, flipped: Boolean): Pair<Int, Int> {
        val d = engineToDisplay(square, flipped)
        return Board.rowOf(d) to Board.colOf(d)
    }

    /** Engine square at a display (row, col), or null when off the board. */
    fun squareAt(displayRow: Int, displayCol: Int, flipped: Boolean): Int? {
        if (displayRow !in 0 until Board.ROWS || displayCol !in 0 until Board.COLS) return null
        return displayToEngine(Board.sq(displayRow, displayCol), flipped)
    }

    /** Default orientation for a human-vs-AI game: the human's pieces at the bottom. */
    fun defaultFlipped(humanSide: Int): Boolean = humanSide == Board.HAN
}
