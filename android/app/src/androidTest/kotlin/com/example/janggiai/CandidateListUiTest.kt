package com.example.janggiai

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.janggiai.data.ThemeMode
import com.example.janggiai.engine.api.AnalysisSnapshot
import com.example.janggiai.engine.api.MoveAnalysis
import com.example.janggiai.game.AnalysisCandidates
import com.example.janggiai.game.Board
import com.example.janggiai.game.Move
import com.example.janggiai.game.MoveGen
import com.example.janggiai.game.Notation
import com.example.janggiai.game.Position
import com.example.janggiai.game.WinRatePerspective
import com.example.janggiai.ui.analysis.CandidateList
import com.example.janggiai.ui.theme.JanggiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The candidate list is a lazy list of every legal move: 31 moves → 31 rows, the last one reachable by
 * scrolling (programmatically and with a real swipe), and the Top-5 toggle stays available.
 */
@RunWith(AndroidJUnit4::class)
class CandidateListUiTest {
    @get:Rule val rule = createComposeRule()

    private fun snapshot(): AnalysisSnapshot {
        val pos = Position.initial()
        val legal = MoveGen.legalMoves(pos).filter { it != Move.PASS }
        val moves = legal.mapIndexed { i, m ->
            val cp = 50 - i * 4
            MoveAnalysis(i + 1, m, Notation.moveToString(m), Notation.describe(pos.board, m), Move.from(m), Move.to(m), cp, null, 0.5 + cp / 1000.0,
                12, 15, 1000, listOf(m), listOf(Notation.describe(pos.board, m)), null, false, false)
        }
        return AnalysisSnapshot(1, pos.toFen(), pos.stateKey(), Board.CHO, moves, 12, 15, 100000, 100000, 1000, true, false, null, "fake", 31, 31)
    }

    @Test fun allThirtyOneCandidatesAreListedAndScrollable() {
        val snap = snapshot()
        val all = AnalysisCandidates.fromSnapshot(snap, WinRatePerspective.SIDE_TO_MOVE)
        assertEquals(31, all.size)                                        // the model itself is complete
        rule.setContent {
            JanggiTheme(ThemeMode.LIGHT) {
                CandidateList(snapshot = snap, candidates = all, focusedMove = null, advanced = false, showAll = true,
                    onToggleShowAll = {}, onFocus = {}, onPlay = {}, modifier = Modifier.fillMaxSize())
            }
        }
        val list = rule.onNodeWithTag("candidate_list")
        rule.onNodeWithTag("candidate_1").assertExists().assertIsDisplayed()
        rule.onNodeWithText("Top 5만").assertIsDisplayed()               // default = every move; the toggle offers Top 5

        // Programmatic scroll to the last candidate: it must exist and be on screen.
        list.performScrollToNode(hasTestTag("candidate_31"))
        rule.onNodeWithTag("candidate_31").assertExists().assertIsDisplayed()

        // Back to the top, then a real user swipe: the first row leaves the screen, i.e. the list really scrolls.
        list.performScrollToNode(hasTestTag("candidate_1"))
        rule.onNodeWithTag("candidate_1").assertIsDisplayed()
        list.performTouchInput { swipeUp() }
        rule.waitForIdle()
        rule.onNodeWithTag("candidate_1").assertIsNotDisplayed()
        list.performScrollToNode(hasTestTag("candidate_toggle"))
        rule.onNodeWithTag("candidate_toggle").assertIsDisplayed()
    }
}
