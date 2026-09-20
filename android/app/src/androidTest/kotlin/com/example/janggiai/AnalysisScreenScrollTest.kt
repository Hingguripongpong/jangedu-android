package com.example.janggiai

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.janggiai.data.ThemeMode
import com.example.janggiai.game.MoveGen
import com.example.janggiai.game.Position
import com.example.janggiai.ui.analysis.AnalysisScreen
import com.example.janggiai.ui.common.AppForeground
import com.example.janggiai.ui.theme.JanggiTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real analysis screen with the real engine (same harness as GameScreenCoachTest: createComposeRule +
 * setContent).  The start position has 32 legal moves (31 + pass); the engine scores all of them (MultiPV = 32),
 * every one becomes a candidate row, and the user can scroll the screen down to the 31st candidate.
 *
 * Lazy-list principle: the screen is one LazyColumn (status, board, controls, candidates).  Items below the fold are
 * not composed until scrolled into view, so the test never polls for an off-screen node's existence — it scrolls
 * analysis_list to the item first (analysis_controls, then candidate_31, candidate_1, janggi_board).
 */
@RunWith(AndroidJUnit4::class)
class AnalysisScreenScrollTest {
    @get:Rule val rule = createComposeRule()

    @Before fun foreground() { AppForeground.set(true) }   // MainActivity normally does this in onStart()

    /**
     * Text of the analysis status line ("분석 준비 중…" / "분석 중 · 깊이 d · k/N수" / "분석 완료 · …"), or "" while it is
     * not composed (it lives in the "controls" lazy item below the board).
     */
    private fun statusText(): String = rule.onAllNodesWithTag("analysis_status").fetchSemanticsNodes(atLeastOneRootRequired = false)
        .flatMap { it.config.getOrNull(SemanticsProperties.Text) ?: emptyList() }.joinToString { it.text }

    private fun waitStage(stage: String, timeoutMs: Long, condition: () -> Boolean) {
        try { rule.waitUntil(timeoutMillis = timeoutMs) { condition() } }
        catch (e: ComposeTimeoutException) { throw AssertionError("Timed out at stage: $stage (status line was: '${statusText()}')", e) }
    }

    @Test fun screenScrollsToTheLastOfAllLegalMoves() {
        rule.setContent { JanggiTheme(ThemeMode.LIGHT) { AnalysisScreen(recordId = null, onBack = {}) } }
        rule.waitForIdle()

        // 1. The screen's lazy list exists; then bring the controls item (which holds the status line) into view.
        waitStage("analysis screen composed (analysis_list present)", 15_000) {
            rule.onAllNodesWithTag("analysis_list").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        rule.onNodeWithTag("analysis_list").performScrollToNode(hasTestTag("analysis_controls"))
        waitStage("analysis controls composed (analysis_status present)", 15_000) {
            rule.onAllNodesWithTag("analysis_status").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }

        // 2. The engine analysis started and its first snapshot reached the screen.
        waitStage("engine analysis started (first snapshot on screen)", 20_000) {
            val t = statusText(); t.contains("분석 중") || t.contains("분석 완료")
        }
        // 3. Every legal move has a score in the UI model (MultiPV = N): the "k/N수" counter reaches N/N (first full
        //    MultiPV round — independent of the analysis budget, even "최대 ∞"), or the search already completed.
        val legal = MoveGen.legalMoves(Position.initial()).size            // ~32 with the pass move (for the message)
        val counter = Regex("""(\d+)/(\d+)수""")
        waitStage("all legal moves (~$legal) scored in the UI model", 20_000) {
            val t = statusText()
            counter.find(t)?.let { it.groupValues[1] == it.groupValues[2] } == true || t.contains("분석 완료")
        }

        // 4. Candidate rows are lazy items: scrolling reaches the 31st and it is displayed, then back to the 1st.
        rule.onNodeWithTag("analysis_list").performScrollToNode(hasTestTag("candidate_31"))
        rule.onNodeWithTag("candidate_31").assertExists().assertIsDisplayed()
        rule.onNodeWithTag("analysis_list").performScrollToNode(hasTestTag("candidate_1"))
        rule.onNodeWithTag("candidate_1").assertExists().assertIsDisplayed()
        // 5. And back up to the board.
        rule.onNodeWithTag("analysis_list").performScrollToNode(hasTestTag("janggi_board"))
        rule.onNodeWithTag("janggi_board").assertIsDisplayed()
    }
}
