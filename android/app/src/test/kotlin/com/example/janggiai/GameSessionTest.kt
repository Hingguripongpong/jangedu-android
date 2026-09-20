package com.example.janggiai

import com.example.janggiai.game.BikjangRule
import com.example.janggiai.game.GameEnd
import com.example.janggiai.game.GameSession
import com.example.janggiai.game.Move
import com.example.janggiai.game.Notation
import com.example.janggiai.game.Position
import com.example.janggiai.game.RuleConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameSessionTest {
    @Test fun playUndoAndNavigate() {
        var s = GameSession.newGame(RuleConfig.DEFAULT)
        assertEquals(Position.START_FEN, s.startFen)
        assertEquals(32, s.legalMoves.size)
        s = s.play(Notation.parseMove("02-83"))
        s = s.play(Notation.parseMove("18-37"))
        assertEquals(listOf("02-83", "18-37"), s.moves)
        assertTrue(s.atTip)
        assertEquals(Notation.parseMove("18-37"), s.lastMove)
        s = s.previous()
        assertEquals(1, s.viewIndex); assertFalse(s.atTip)
        assertEquals(listOf("02-83"), s.enginePosition().moves)
        s = s.first(); assertEquals(0, s.viewIndex); assertNull(s.lastMove)
        s = s.last(); assertEquals(2, s.viewIndex)
        s = s.undo(); assertEquals(listOf("02-83"), s.moves); assertEquals(1, s.viewIndex)
        // playing from a past position starts a new branch
        s = s.play(Notation.parseMove("18-37")).previous().play(Notation.parseMove("12-33"))
        assertEquals(listOf("02-83", "12-33"), s.moves)
        assertEquals(listOf("馬 02→83", "馬 12→33"), s.moveTexts())
        try { s.play(Notation.parseMove("02-84")); throw AssertionError("illegal accepted") } catch (e: IllegalArgumentException) {}
    }

    @Test fun legalTargetsAndPass() {
        val s = GameSession.newGame(RuleConfig.DEFAULT)
        val horse = Notation.parseSquare("02")
        assertEquals(setOf(Notation.parseSquare("81"), Notation.parseSquare("83")), s.legalTargets(horse).toSet())   // 馬 02: 81, 83
        assertTrue(s.legalMoves.contains(Move.PASS))
        val over = GameSession(moves = listOf("pass", "pass"), viewIndex = 2, rules = RuleConfig.DEFAULT)
        assertEquals(GameEnd.DRAW_PASSES, over.status.end)
        assertTrue(over.legalMoves.isEmpty())
    }

    @Test fun forcedRuleChangesLegalMoves() {
        val s = GameSession(moves = listOf("95-94", "25-24"), viewIndex = 2, rules = RuleConfig(bikjang = BikjangRule.FORCED))
        assertEquals(setOf("pass", "94-95", "75-74", "73-74"), s.legalMoves.map { Notation.moveToString(it) }.toSet())
        assertTrue(s.status.bikjang)
    }

    @Test fun fromFenValidates() {
        val s = GameSession.fromFen("4k4/9/9/9/9/9/9/9/r3K4/9 w - - 0 1", RuleConfig.DEFAULT)
        assertTrue(s.status.inCheck)
        try { GameSession.fromFen("garbage", RuleConfig.DEFAULT); throw AssertionError() } catch (e: IllegalArgumentException) {}
    }
}
