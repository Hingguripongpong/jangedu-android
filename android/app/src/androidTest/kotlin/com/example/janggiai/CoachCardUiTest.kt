package com.example.janggiai

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.janggiai.coach.CoachComment
import com.example.janggiai.coach.CoachGrade
import com.example.janggiai.data.ThemeMode
import com.example.janggiai.game.Board
import com.example.janggiai.game.Notation
import com.example.janggiai.ui.game.CoachCard
import com.example.janggiai.ui.theme.JanggiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The coach card renders a verdict, offers the best-move preview, and sits above the record section. */
@RunWith(AndroidJUnit4::class)
class CoachCardUiTest {
    @get:Rule val rule = createComposeRule()

    private val comment = CoachComment(
        ply = 0, humanSide = Board.CHO, playedMove = Notation.parseMove("01-91"), playedNotation = "車 01→91",
        bestMove = Notation.parseMove("02-83"), bestNotation = "馬 02→83", playedWinRate = 0.50, bestWinRate = 0.60, winRateLoss = 0.10,
        playedScoreCp = 0, playedMateIn = null, bestScoreCp = 40, bestMateIn = null, depth = 14, grade = CoachGrade.MISTAKE,
        headline = "실수입니다", summary = "이 수의 승률 50.0%, 최선수 馬 02→83 60.0% (차이 -10.0%p).", reasons = listOf("테스트 이유"),
        pv = listOf(Notation.parseMove("02-83")), pvText = listOf("馬 02→83"), playedPv = listOf(Notation.parseMove("01-91")),
    )

    @Test fun verdictPreviewButtonAndOrderAboveTheRecord() {
        var previews = 0
        rule.setContent {
            JanggiTheme(ThemeMode.LIGHT) {
                Column {
                    CoachCard(comment = comment, analyzing = false, failed = false, hasHumanMove = true, previewing = false,
                        onTogglePreview = { previews++ }, onRetry = {})
                    Text("대국 기록", Modifier.testTag("game_record"))
                }
            }
        }
        rule.onNodeWithText("AI 훈수").assertIsDisplayed()
        rule.onNodeWithTag("coach_grade").assertIsDisplayed()
        rule.onNodeWithText("실수").assertIsDisplayed()
        rule.onNodeWithText("차이: -10.0%p").assertIsDisplayed()
        rule.onNodeWithTag("coach_preview_button").assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertEquals(1, previews)
        val coachTop = rule.onNodeWithTag("coach_card").getBoundsInRoot().top
        val recordTop = rule.onNodeWithTag("game_record").getBoundsInRoot().top
        assertTrue("coach card must be above the record section", coachTop < recordTop)
    }

    @Test fun analyzingStateIsShownWhileTheEngineWorks() {
        rule.setContent { JanggiTheme(ThemeMode.LIGHT) { CoachCard(comment = null, analyzing = true, failed = false, hasHumanMove = true, previewing = false, onTogglePreview = {}, onRetry = {}) } }
        rule.onNodeWithText("분석 중…").assertIsDisplayed()
    }
}
