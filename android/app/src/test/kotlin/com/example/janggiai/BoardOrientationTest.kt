package com.example.janggiai

import com.example.janggiai.game.Board
import com.example.janggiai.game.BoardOrientation
import com.example.janggiai.game.Move
import com.example.janggiai.game.Notation
import com.example.janggiai.game.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The engine↔display mapping used by the board for drawing AND hit-testing. */
class BoardOrientationTest {
    @Test fun roundTripForEverySquareInBothOrientations() {
        for (flipped in listOf(false, true)) for (sq in 0 until Board.NUM_SQUARES) {
            val d = BoardOrientation.engineToDisplay(sq, flipped)
            assertEquals(sq, BoardOrientation.displayToEngine(d, flipped))
            val (r, c) = BoardOrientation.displayRowCol(sq, flipped)
            assertEquals(sq, BoardOrientation.squareAt(r, c, flipped))
        }
        // unflipped is the identity; flipped is the 180° rotation
        assertEquals(Board.sq(9, 0), BoardOrientation.engineToDisplay(Board.sq(9, 0), false))
        assertEquals(0 to 8, BoardOrientation.displayRowCol(Board.sq(9, 0), true))
        assertEquals(9 to 8, BoardOrientation.displayRowCol(Board.sq(0, 0), true))
        assertNull(BoardOrientation.squareAt(-1, 0, true)); assertNull(BoardOrientation.squareAt(0, 9, false))
    }

    @Test fun touchOnTheFlippedBoardSelectsTheMirroredEnginePiece() {
        val pos = Position.initial()
        // Han at the bottom (human plays Han): the bottom-left intersection on screen is Han's chariot at engine square "18"... 
        val bottomLeft = BoardOrientation.squareAt(9, 0, flipped = true)!!
        assertEquals(Board.sq(0, 8), bottomLeft)
        assertEquals(Board.HAN, com.example.janggiai.game.Piece.colorOf(pos.board[bottomLeft]))
        assertEquals(com.example.janggiai.game.Piece.CHARIOT, com.example.janggiai.game.Piece.typeOf(pos.board[bottomLeft]))
        // Unflipped: same screen point is Cho's chariot "01".
        val cho = BoardOrientation.squareAt(9, 0, flipped = false)!!
        assertEquals(Notation.parseSquare("01"), cho)
        assertEquals(Board.CHO, com.example.janggiai.game.Piece.colorOf(pos.board[cho]))
    }

    @Test fun sameEngineMoveRendersAtMirroredScreenPositions() {
        val m = Notation.parseMove("02-83")   // Cho horse: engine (9,1) -> (7,2)
        assertEquals(9 to 1, BoardOrientation.displayRowCol(Move.from(m), false))
        assertEquals(7 to 2, BoardOrientation.displayRowCol(Move.to(m), false))
        assertEquals(0 to 7, BoardOrientation.displayRowCol(Move.from(m), true))
        assertEquals(2 to 6, BoardOrientation.displayRowCol(Move.to(m), true))
        // The move itself, its notation and FEN never change with the orientation.
        assertEquals("02-83", Notation.moveToString(m))
    }

    @Test fun defaultOrientationPutsTheHumanAtTheBottom() {
        assertFalse(BoardOrientation.defaultFlipped(Board.CHO))
        assertTrue(BoardOrientation.defaultFlipped(Board.HAN))
    }
}
