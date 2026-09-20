package com.example.janggiai

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.janggiai.data.ThemeMode
import com.example.janggiai.game.Difficulty
import com.example.janggiai.game.GameConfig
import com.example.janggiai.game.SetupChoice
import com.example.janggiai.game.SideChoice
import com.example.janggiai.ui.game.GameMode
import com.example.janggiai.ui.game.NewGameDialog
import com.example.janggiai.ui.theme.JanggiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The new-game dialog offers side + both setups + difficulty + coach, and reports the chosen configuration. */
@RunWith(AndroidJUnit4::class)
class NewGameDialogTest {
    @get:Rule val rule = createComposeRule()

    @Test fun sideAndSetupChoicesAreOfferedAndReported() {
        var started: GameConfig? = null
        rule.setContent {
            JanggiTheme(ThemeMode.LIGHT) {
                NewGameDialog(GameMode.HUMAN_VS_AI, GameConfig(SideChoice.CHO, SetupChoice.MSMS, SetupChoice.SMSM, Difficulty.BEGINNER, coachEnabled = true),
                    onStart = { _, cfg -> started = cfg }, onDismiss = {})
            }
        }
        rule.onNodeWithTag("human_setup").assertIsDisplayed()
        rule.onNodeWithTag("ai_setup").assertIsDisplayed()
        rule.onNodeWithTag("coach_switch").assertIsDisplayed()
        rule.onNodeWithTag("side_han").performClick()
        rule.onNodeWithTag("start_game").performClick()
        rule.waitForIdle()
        assertNotNull(started)
        assertEquals(SideChoice.HAN, started!!.humanSide)
        assertEquals(SetupChoice.MSMS, started!!.humanSetup)      // the two setups stay independent of the side
        assertEquals(SetupChoice.SMSM, started!!.aiSetup)
        assertEquals(Difficulty.BEGINNER, started!!.difficulty)
    }
}
