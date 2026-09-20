package com.example.janggiai.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.janggiai.engine.api.AnalysisLimits
import com.example.janggiai.engine.api.EnginePosition
import com.example.janggiai.engine.api.SearchLimits
import com.example.janggiai.engine.fsf.FairyStockfishEngine
import com.example.janggiai.engine.fsf.FairyStockfishNative
import com.example.janggiai.engine.fsf.JniUciTransport
import com.example.janggiai.game.BikjangRule
import com.example.janggiai.game.MoveGen
import com.example.janggiai.game.Position
import com.example.janggiai.game.RuleConfig
import com.example.janggiai.game.Uci
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the real native engine on the device/emulator.  Mirrors tools/desktop-jni-test, which runs
 * the same checks on a development machine.
 */
@RunWith(AndroidJUnit4::class)
class FairyStockfishInstrumentedTest {
    private val engine = FairyStockfishEngine(JniUciTransport())

    @After fun tearDown() = engine.close()

    @Test fun engineStartsWithJanggiVariant() = runBlocking {
        engine.newGame()
        assertTrue(engine.name, engine.name.startsWith("Fairy-Stockfish"))
    }

    @Test fun analysisScoresEveryLegalMoveWithMultiPvN() = runBlocking {
        val pos = EnginePosition(rules = RuleConfig(bikjang = BikjangRule.FORCED))
        val snaps = withTimeout(20_000) { engine.analyze(pos, AnalysisLimits(timeMs = 1500), 42).toList() }
        val last = snaps.last()
        assertTrue(last.final && !last.aborted && last.error == null)
        assertEquals(32, last.legalMoveCount)
        assertTrue(last.moves.all { it.scored })
        assertTrue(last.depth >= 4)
        assertTrue(snaps.size >= 2)                                   // streamed, not just final
        assertTrue(engine.sentCommands.contains("setoption name MultiPV value 32"))
        val pass = last.moves.first { it.moveText == "pass" }
        assertTrue(pass.mateIn != null && pass.mateIn!! < 0)           // pass at start loses on points under janggi
        assertEquals(0.0, pass.winRate!!, 0.0)
        assertTrue(last.best!!.pv.size >= 2)
    }

    @Test fun playModeUsesMultiPvOne() = runBlocking {
        val mv = withTimeout(20_000) { engine.bestMove(EnginePosition(), SearchLimits(timeMs = 800, threads = 1)) }
        assertNotNull(mv.move)
        assertTrue(mv.depth >= 6)
        assertTrue(engine.sentCommands.contains("setoption name MultiPV value 1"))
        assertTrue(MoveGen.legalMoves(Position.initial()).contains(mv.move!!))
    }

    @Test fun stopEndsInfiniteAnalysisQuickly() = runBlocking {
        val job = launch { engine.analyze(EnginePosition(), AnalysisLimits(timeMs = null), 1).toList() }
        delay(500)
        val t0 = System.currentTimeMillis()
        job.cancel(); job.join()
        val mv = withTimeout(20_000) { engine.bestMove(EnginePosition(), SearchLimits(timeMs = 200)) }
        assertNotNull(mv.move)
        assertTrue(System.currentTimeMillis() - t0 < 5000)
    }

    @Test fun legalMovesAgreeWithEnginePerftDivide() {
        // single source of truth check: Kotlin legal moves == Fairy-Stockfish `go perft 1` move list
        FairyStockfishNative.ensureLoaded()
        assertTrue(FairyStockfishNative.nativeStart())
        fun readUntil(p: (String) -> Boolean): List<String> {
            val out = ArrayList<String>(); val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline) { val l = FairyStockfishNative.nativeReadLine(100) ?: continue; out += l; if (p(l)) return out }
            throw AssertionError("timeout")
        }
        FairyStockfishNative.nativeSend("uci"); readUntil { it == "uciok" }
        FairyStockfishNative.nativeSend("setoption name UCI_Variant value janggi")
        for (fen in listOf(Position.START_FEN, "r1ba1ab1r/4k1n2/1c2n2c1/p3p1p1p/2p6/6P2/P1P1P3P/1C2NC3/4K4/RNBA1AB1R w - - 0 1")) {
            FairyStockfishNative.nativeSend("position fen $fen"); FairyStockfishNative.nativeSend("go perft 1")
            val lines = readUntil { it.startsWith("Nodes searched") }
            val engineMoves = lines.filter { it.contains(": ") && !it.startsWith("Nodes") }.map { it.substringBefore(":").trim() }.toSet()
            val pos = RuleConfig.TRADITIONAL.apply(Position.fromFen(fen))
            val ours = MoveGen.legalMoves(pos).map { Uci.moveToUci(pos, it) }.toSet()
            assertEquals(fen, engineMoves, ours)
        }
        FairyStockfishNative.nativeShutdown(5000)
    }

    @Test fun engineSurvivesInvalidPositionAndRestart() = runBlocking {
        val bad = runCatching { engine.bestMove(EnginePosition(startFen = "this/is/not/a/fen w - - 0 1"), SearchLimits(timeMs = 100)) }
        assertTrue(bad.isFailure)                                      // rejected by our FEN parser, engine untouched
        val ok = withTimeout(20_000) { engine.bestMove(EnginePosition(), SearchLimits(timeMs = 200)) }
        assertNotNull(ok.move)
        engine.close()
        val again = FairyStockfishEngine(JniUciTransport())
        val ok2 = withTimeout(20_000) { again.bestMove(EnginePosition(), SearchLimits(timeMs = 200)) }
        assertNotNull(ok2.move)
        again.close()
    }
}
