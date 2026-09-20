package com.example.janggiai

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.janggiai.game.Board
import com.example.janggiai.game.Notation
import com.example.janggiai.game.Position
import com.example.janggiai.ui.board.BoardOverlay
import com.example.janggiai.ui.board.JanggiBoard
import com.example.janggiai.ui.theme.JanggiTheme
import com.example.janggiai.data.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The board reports the tapped intersection; a tap on 馬 02 followed by 83 yields that move. */
@RunWith(AndroidJUnit4::class)
class BoardUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun tappingTwoIntersectionsSelectsAMove() {
        val taps = ArrayList<Int>()
        val pos = Position.initial()
        rule.setContent { JanggiTheme(ThemeMode.LIGHT) { JanggiBoard(board = pos.board, overlay = BoardOverlay(), flipped = false, onSquareTap = { taps += it }) } }
        val node = rule.onNodeWithTag("janggi_board")
        node.assertIsDisplayed()
        val size = node.fetchSemanticsNode().size
        val cell = size.width / Board.COLS.toFloat()
        val margin = cell / 2f
        fun tap(row: Int, col: Int) = node.performTouchInput { click(Offset(margin + col * cell, margin + row * cell)) }
        tap(9, 1)   // 02 (Cho horse)
        tap(7, 2)   // 83
        rule.waitForIdle()
        assertEquals(listOf(Notation.parseSquare("02"), Notation.parseSquare("83")), taps)
    }

    @Test fun flippedBoardMapsTapsToMirroredSquares() {
        val taps = ArrayList<Int>()
        val pos = Position.initial()
        rule.setContent { JanggiTheme(ThemeMode.DARK) { JanggiBoard(board = pos.board, overlay = BoardOverlay(), flipped = true, onSquareTap = { taps += it }) } }
        val node = rule.onNodeWithTag("janggi_board")
        val size = node.fetchSemanticsNode().size
        val cell = size.width / Board.COLS.toFloat()
        node.performTouchInput { click(Offset(cell / 2f, cell / 2f)) }   // top-left on screen = Cho's 09 when flipped
        rule.waitForIdle()
        assertEquals(listOf(Notation.parseSquare("09")), taps)
    }
}
