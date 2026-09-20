package com.example.janggiai

import com.example.janggiai.engine.api.AnalysisLimits
import com.example.janggiai.engine.api.AnalysisSnapshot
import com.example.janggiai.engine.api.EngineMove
import com.example.janggiai.engine.api.EnginePosition
import com.example.janggiai.engine.api.JanggiEngine
import com.example.janggiai.engine.api.MoveAnalysis
import com.example.janggiai.engine.api.SearchInfo
import com.example.janggiai.engine.api.SearchLimits
import com.example.janggiai.game.AnalysisController
import com.example.janggiai.game.GameSession
import com.example.janggiai.game.Notation
import com.example.janggiai.game.RuleConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** A slow fake engine: emits 3 snapshots 80 ms apart, then a final one. */
private class SlowFakeEngine(private val reportedTimeMs: Long = 0) : JanggiEngine {
    override val name = "fake"
    val stops = AtomicInteger(0)
    val started = ArrayList<String>()

    override fun analyze(position: EnginePosition, limits: AnalysisLimits, analysisId: Long): Flow<AnalysisSnapshot> = flow {
        started += position.moves.joinToString(" ")
        val fen = position.toPosition().toFen()
        for (d in 1..3) { delay(80); emit(snap(analysisId, fen, d, false)) }
        delay(80); emit(snap(analysisId, fen, 4, true))
    }

    private fun snap(id: Long, fen: String, depth: Int, final: Boolean): AnalysisSnapshot {
        val m = Notation.parseMove("02-83")
        val ma = MoveAnalysis(1, m, "02-83", "馬 02→83", 82, 65, 10, null, 0.51, depth, depth, 100, listOf(m), listOf("馬 02→83"), null, false, false)
        return AnalysisSnapshot(id, fen, fen, 0, listOf(ma), depth, depth, 100, 1000, reportedTimeMs, final, false, null, name, 1, 1)
    }

    override suspend fun bestMove(position: EnginePosition, limits: SearchLimits, onInfo: ((SearchInfo) -> Unit)?): EngineMove =
        EngineMove(null, null, null, null, null, null, 0, 0, 0, 0, emptyList(), name, false)
    override fun stop() { stops.incrementAndGet() }
    override suspend fun newGame() {}
    override fun close() {}
}

class AnalysisControllerTest {
    @Test fun staleSnapshotsNeverOverwriteANewerPosition() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = SlowFakeEngine()
        val ctl = AnalysisController(engine, scope, debounceMs = 10)
        val s0 = GameSession.newGame(RuleConfig.DEFAULT)
        val s1 = s0.play(Notation.parseMove("02-83"))
        ctl.request(s0.enginePosition(), AnalysisLimits(timeMs = 500), s0.position.stateKey())
        delay(150)                                                   // first search underway
        ctl.request(s1.enginePosition(), AnalysisLimits(timeMs = 500), s1.position.stateKey())
        val finalState = withTimeout(5000) { ctl.state.first { !it.running && it.snapshot?.final == true } }
        assertEquals(s1.position.toFen(), finalState.snapshot!!.fen)
        assertEquals(s1.position.stateKey(), finalState.stateKey)
        assertEquals(ctl.currentAnalysisId, finalState.snapshot!!.analysisId)
        assertEquals(listOf("", "02-83"), engine.started)
        scope.cancel()
    }

    @Test fun cachedFinalAnalysisIsReusedForTheSameState() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = SlowFakeEngine()
        val ctl = AnalysisController(engine, scope, debounceMs = 10)
        val s0 = GameSession.newGame(RuleConfig.DEFAULT)
        ctl.request(s0.enginePosition(), AnalysisLimits(timeMs = 0), s0.position.stateKey())
        withTimeout(5000) { ctl.state.first { !it.running && it.snapshot?.final == true } }
        ctl.request(s0.enginePosition(), AnalysisLimits(timeMs = 0), s0.position.stateKey())
        assertFalse(ctl.state.value.running)                         // served from cache, no new search
        assertEquals(1, engine.started.size)
        // same board, different state (repetition context after a horse dance) -> new search
        val passed = GameSession(moves = listOf("02-83", "18-37", "83-02", "37-18"), viewIndex = 4, rules = RuleConfig.DEFAULT)
        assertEquals(s0.position.toFen().substringBefore(' '), passed.position.toFen().substringBefore(' '))
        ctl.request(passed.enginePosition(), AnalysisLimits(timeMs = 0), passed.position.stateKey())
        assertTrue(ctl.state.value.running)
        ctl.stop()
        assertTrue(engine.stops.get() >= 1)
        scope.cancel()
    }

    /**
     * Regression check for the cache budget rule (the expression that failed to compile under Gradle's module
     * boundaries): no time limit or a cached search that used >= 80 % of the requested budget is reused; a cached
     * search with a smaller budget triggers a new search.
     */
    @Test fun cacheHonoursTheRequestedTimeBudget() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = SlowFakeEngine(reportedTimeMs = 800)
        val ctl = AnalysisController(engine, scope, debounceMs = 10)
        val s0 = GameSession.newGame(RuleConfig.DEFAULT)
        val key = s0.position.stateKey()
        ctl.request(s0.enginePosition(), AnalysisLimits(timeMs = 800), key)
        withTimeout(5000) { ctl.state.first { !it.running && it.snapshot?.final == true } }
        assertEquals(1, engine.started.size)

        ctl.request(s0.enginePosition(), AnalysisLimits(timeMs = null), key)     // unlimited budget: cached result is fine
        assertFalse(ctl.state.value.running)
        ctl.request(s0.enginePosition(), AnalysisLimits(timeMs = 1000), key)     // 800 >= 1000 * 0.8 -> reuse
        assertFalse(ctl.state.value.running)
        assertEquals(1, engine.started.size)

        ctl.request(s0.enginePosition(), AnalysisLimits(timeMs = 1200), key)     // 800 < 1200 * 0.8 -> new search
        assertTrue(ctl.state.value.running)
        withTimeout(5000) { ctl.state.first { !it.running && it.snapshot?.final == true } }
        assertEquals(2, engine.started.size)
        scope.cancel()
    }
}
