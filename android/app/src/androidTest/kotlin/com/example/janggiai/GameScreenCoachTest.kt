package com.example.janggiai

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.janggiai.data.ThemeMode
import com.example.janggiai.game.Board
import com.example.janggiai.ui.common.AppForeground
import com.example.janggiai.ui.game.GameMode
import com.example.janggiai.ui.game.GameScreen
import com.example.janggiai.ui.theme.JanggiTheme
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End to end on the device with the real engine: the human plays 馬 02→83, the AI replies (MultiPV = 1),
 * then the coach reviews the human move (MultiPV = N) and the verdict appears in the card above the record.
 */
@RunWith(AndroidJUnit4::class)
class GameScreenCoachTest {
    @get:Rule val rule = createComposeRule()

    @Before fun foreground() { AppForeground.set(true) }   // MainActivity normally does this in onStart

    @Test fun humanMoveGetsACoachVerdictAfterTheAiReply() {
        rule.setContent { JanggiTheme(ThemeMode.LIGHT) { GameScreen(mode = GameMode.HUMAN_VS_AI, humanSide = Board.CHO, configure = false, onBack = {}) } }
        rule.onNodeWithTag("coach_card").assertIsDisplayed()
        val coachTop = rule.onNodeWithTag("coach_card").getBoundsInRoot().top
        val recordTop = rule.onNodeWithTag("game_record").getBoundsInRoot().top
        assertTrue(coachTop < recordTop)

        val board = rule.onNodeWithTag("janggi_board")
        val size = board.fetchSemanticsNode().size
        val cell = size.width / Board.COLS.toFloat(); val margin = cell / 2f
        board.performTouchInput { click(Offset(margin + 1 * cell, margin + 9 * cell)) }   // 02 (Cho horse)
        board.performTouchInput { click(Offset(margin + 2 * cell, margin + 7 * cell)) }   // 83
        // AI reply (≤ 2 s at any difficulty) + coach budget (≤ 1.5 s) + engine start-up.
        rule.waitUntil(timeoutMillis = 30_000) { rule.onAllNodesWithTag("coach_grade").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("coach_grade").assertIsDisplayed()
    }
}
