package com.example.janggiai

import com.example.janggiai.game.Board
import com.example.janggiai.game.Difficulty
import com.example.janggiai.game.WinRateFormat
import com.example.janggiai.game.WinRatePerspective
import org.junit.Assert.assertEquals
import org.junit.Test

class WinRateFormatTest {
    @Test fun perspectiveFlipsForTheOtherSide() {
        assertEquals(0.6 to 100, WinRateFormat.fromPerspective(0.6, 100, Board.HAN, WinRatePerspective.SIDE_TO_MOVE))
        assertEquals(0.6 to 100, WinRateFormat.fromPerspective(0.6, 100, Board.HAN, WinRatePerspective.HAN))
        val (w, cp) = WinRateFormat.fromPerspective(0.6, 100, Board.HAN, WinRatePerspective.CHO)
        assertEquals(0.4, w!!, 1e-9); assertEquals(-100, cp)
        assertEquals("63.2%", WinRateFormat.percent(0.632)); assertEquals("—", WinRateFormat.percent(null))
        assertEquals("+1.92", WinRateFormat.score(192, null)); assertEquals("-M3", WinRateFormat.score(-99997, -3)); assertEquals("M1", WinRateFormat.score(99999, 1))
    }

    @Test fun difficultyMapsToSkillAndTime() {
        val max = Difficulty.MAXIMUM.limits(threads = 2, hashMb = 64, maxTimeMs = 5000)
        assertEquals(20, max.skillLevel); assertEquals(5000L, max.timeMs); assertEquals(2, max.threads)
        val beginner = Difficulty.BEGINNER.limits(1, 16)
        assertEquals(2, beginner.skillLevel); assertEquals(4, beginner.depth); assertEquals(200L, beginner.timeMs)
    }
}
