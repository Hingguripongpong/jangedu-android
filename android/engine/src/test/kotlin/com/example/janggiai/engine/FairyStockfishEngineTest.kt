package com.example.janggiai.engine

import com.example.janggiai.engine.api.AnalysisLimits
import com.example.janggiai.engine.api.EnginePosition
import com.example.janggiai.engine.api.SearchLimits
import com.example.janggiai.engine.fsf.FairyStockfishEngine
import com.example.janggiai.game.BikjangRule
import com.example.janggiai.game.Move
import com.example.janggiai.game.RuleConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Protocol-level behaviour of [FairyStockfishEngine] against the scripted transport (no native code). */
class FairyStockfishEngineTest {
    private fun startPos(rule: BikjangRule = BikjangRule.OFF) = EnginePosition(rules = RuleConfig(bikjang = rule))

    @Test fun analysisUsesOneMultiPvPerLegalMoveAndPlayUsesOne() = runBlocking {
        val t = FakeUciTransport()
        val eng = FairyStockfishEngine(t)
        val snaps = withTimeout(5000) { eng.analyze(startPos(), AnalysisLimits(timeMs = 100), analysisId = 7).toList() }
        assertTrue(t.sent.contains("setoption name MultiPV value 32"))
        assertTrue(t.sent.contains("setoption name UCI_Variant value janggicasual"))
        assertTrue(t.sent.any { it.startsWith("go movetime 100") })
        val last = snaps.last()
        assertTrue(last.final && !last.aborted)
        assertEquals(7L, last.analysisId)
        assertEquals(32, last.legalMoveCount)
        assertEquals(32, last.moves.size)
        assertEquals("02-83", last.best!!.moveText)                 // b1c3
        assertEquals(1, last.best!!.rank)
        assertTrue(last.moves.first().scoreCp!! >= last.moves[1].scoreCp!!)
        assertEquals("pass", last.moves[1].moveText)                // e2e2 = 한수쉼, second line
        assertEquals(7, last.depth)
        assertTrue(snaps.all { it.analysisId == 7L })

        t.sent.clear()
        val mv = withTimeout(5000) { eng.bestMove(startPos(), SearchLimits(timeMs = 300)) }
        assertTrue(t.sent.contains("setoption name MultiPV value 1"))
        assertFalse(t.sent.any { it.startsWith("setoption name MultiPV value 32") })
        assertTrue(t.sent.contains("setoption name Skill Level value 20"))
        assertEquals("02-83", mv.moveText)
        assertEquals(16, mv.scoreCp)               // cp - 1 for multipv 1
        assertEquals(7, mv.depth)
        assertEquals(Move.NONE + mv.move!!, mv.pv[0])

        t.sent.clear()
        eng.bestMove(startPos(), SearchLimits(timeMs = 300))
        assertFalse(t.sent.contains("setoption name MultiPV value 1"))  // unchanged -> not re-sent
        eng.close()
    }

    @Test fun variantFollowsRulesAndPythonOnlyRulesCoerceToJanggi() = runBlocking {
        val t = FakeUciTransport()
        val eng = FairyStockfishEngine(t)
        eng.bestMove(startPos(BikjangRule.FORCED), SearchLimits(timeMs = 10))
        assertTrue(t.sent.contains("setoption name UCI_Variant value janggi"))
        t.sent.clear()
        eng.bestMove(startPos(BikjangRule.DRAW), SearchLimits(timeMs = 10))
        assertFalse(t.sent.any { it.startsWith("setoption name UCI_Variant") })   // still janggi
        eng.bestMove(startPos(BikjangRule.OFF), SearchLimits(timeMs = 10))
        assertTrue(t.sent.contains("setoption name UCI_Variant value janggicasual"))
        eng.close()
    }

    @Test fun cancellationStopsInfiniteAnalysisAndReleasesTheEngine() = runBlocking {
        val t = FakeUciTransport()
        val eng = FairyStockfishEngine(t)
        val job = launch { eng.analyze(startPos(), AnalysisLimits(timeMs = null), analysisId = 1).toList() }
        delay(250)
        job.cancel(); job.join()
        assertTrue(t.sent.any { it.startsWith("go infinite") })
        assertTrue(t.sent.contains("stop"))
        // the next search must not start before the previous bestmove arrived; here it simply works
        val mv = withTimeout(5000) { eng.bestMove(startPos(), SearchLimits(timeMs = 50)) }
        assertNotNull(mv.move)
        eng.close()
    }

    @Test fun gameOverPositionsProduceNoSearch() = runBlocking {
        val t = FakeUciTransport()
        val eng = FairyStockfishEngine(t)
        val over = EnginePosition(moves = listOf("pass", "pass"), rules = RuleConfig(bikjang = BikjangRule.FORCED))
        val snap = eng.analyze(over, AnalysisLimits(timeMs = 100), analysisId = 3).first()
        assertTrue(snap.final && snap.moves.isEmpty() && snap.legalMoveCount == 0)
        val mv = eng.bestMove(over, SearchLimits(timeMs = 100))
        assertNull(mv.move)
        assertFalse(t.sent.any { it.startsWith("go") })
        eng.close()
    }

    @Test fun positionCommandCarriesHistoryForRepetitions() = runBlocking {
        val t = FakeUciTransport()
        val eng = FairyStockfishEngine(t)
        eng.bestMove(EnginePosition(moves = listOf("02-83", "pass")), SearchLimits(timeMs = 10))
        assertTrue(t.sent.any { it == "position fen rnba1abnr/4k4/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/4K4/RNBA1ABNR w - - 0 1 moves b1c3 e9e9" })
        eng.close()
    }

    /**
     * The coach flow: the AI answers first in play mode (MultiPV = 1), then the human's pre-move position is
     * reviewed in analysis mode (MultiPV = every legal move).  Verified on the command stream: each `go` is
     * preceded by its own MultiPV setting and the previous search's `bestmove` has been consumed.
     */
    @Test fun aiReplyUsesMultiPvOneAndCoachReviewUsesMultiPvN() = runBlocking {
        val t = FakeUciTransport()
        val eng = FairyStockfishEngine(t)
        val pre = startPos()
        // human plays 02-83 (b1c3); the AI replies from the resulting position
        val afterHuman = EnginePosition(pre.startFen, pre.moves + "02-83", pre.rules)
        withTimeout(5000) { eng.bestMove(afterHuman, SearchLimits(timeMs = 200)) }
        val afterAi = t.sent.size
        val review = withTimeout(5000) { eng.analyze(pre, AnalysisLimits(timeMs = 300), analysisId = 42).toList() }
        val cmds = t.sent
        val goIdx = cmds.withIndex().filter { it.value.startsWith("go") }.map { it.index }
        assertEquals(2, goIdx.size)
        fun lastMultiPvBefore(i: Int) = cmds.subList(0, i).lastOrNull { it.startsWith("setoption name MultiPV value ") }?.substringAfterLast(' ')?.toInt()
        assertEquals(1, lastMultiPvBefore(goIdx[0]))                     // AI move: play mode
        assertEquals(32, lastMultiPvBefore(goIdx[1]))                    // coach: MultiPV = legal moves of the pre-move position
        assertTrue(goIdx[1] >= afterAi)                                   // the review only started after the AI's search returned
        assertTrue(cmds.subList(0, goIdx[1]).any { it.startsWith("position") && it.endsWith("moves b1c3") })   // AI searched the post-move position
        assertEquals(32, review.last().legalMoveCount)
        assertTrue(review.last().final)
        assertTrue(review.all { it.analysisId == 42L })
    }
}
