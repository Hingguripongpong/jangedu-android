package com.example.janggiai

import com.example.janggiai.coach.CoachController
import com.example.janggiai.coach.CoachGrade
import com.example.janggiai.coach.CoachRequest
import com.example.janggiai.engine.api.AnalysisLimits
import com.example.janggiai.engine.api.AnalysisSnapshot
import com.example.janggiai.engine.api.EngineMove
import com.example.janggiai.engine.api.EnginePosition
import com.example.janggiai.engine.api.JanggiEngine
import com.example.janggiai.engine.api.MoveAnalysis
import com.example.janggiai.engine.api.SearchInfo
import com.example.janggiai.engine.api.SearchLimits
import com.example.janggiai.game.Board
import com.example.janggiai.game.GameSession
import com.example.janggiai.game.Move
import com.example.janggiai.game.MoveGen
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fake engine for the coach: scores every legal move of the requested position, [bestMove] highest (cp 40),
 * the rest cp 16, pass lowest; emits a partial snapshot after 60 ms and the final one after 120 ms.
 */
private class CoachFakeEngine(private val bestMove: Int = Notation.parseMove("02-83")) : JanggiEngine {
    override val name = "fake"
    val analysed = ArrayList<EnginePosition>()
    val stops = AtomicInteger(0)

    override fun analyze(position: EnginePosition, limits: AnalysisLimits, analysisId: Long): Flow<AnalysisSnapshot> = flow {
        analysed += position
        val pos = position.toPosition()
        val legal = MoveGen.legalMoves(pos)
        fun snap(final: Boolean): AnalysisSnapshot {
            val ranked = legal.sortedByDescending { m -> if (m == Move.PASS) -1 else if (m == bestMove) 40 else 16 }
            val moves = ranked.mapIndexed { i, m ->
                val cp = if (m == Move.PASS) -50 else if (m == bestMove) 40 else 16
                MoveAnalysis(i + 1, m, Notation.moveToString(m), Notation.describe(pos.board, m), if (m == Move.PASS) null else Move.from(m), if (m == Move.PASS) null else Move.to(m),
                    cp, null, 0.5 + cp / 400.0, 10, 12, 1000, listOf(m), listOf(Notation.describe(pos.board, m)), null, false, false)
            }
            return AnalysisSnapshot(analysisId, pos.toFen(), pos.stateKey(), pos.side, moves, 10, 12, 10000, 100000, 100, final, false, null, name, moves.size, moves.size)
        }
        delay(60); emit(snap(false))
        delay(60); emit(snap(true))
    }

    override suspend fun bestMove(position: EnginePosition, limits: SearchLimits, onInfo: ((SearchInfo) -> Unit)?): EngineMove =
        EngineMove(null, null, null, null, null, null, 0, 0, 0, 0, emptyList(), name, false)
    override fun stop() { stops.incrementAndGet() }
    override suspend fun newGame() {}
    override fun close() {}
}

class CoachControllerTest {
    private val rules = RuleConfig.DEFAULT
    private val horse = Notation.parseMove("02-83")
    private val chariot = Notation.parseMove("01-91")

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Test fun reviewsTheHumanMoveAgainstTheFullAnalysisOfThePreMovePosition() = runBlocking {
        val scope = scope(); val engine = CoachFakeEngine(); val coach = CoachController(engine, scope)
        val pre = GameSession.newGame(rules)
        val after = pre.play(chariot)
        coach.sync(after)
        coach.evaluate(CoachRequest(pre, chariot, Board.CHO), AnalysisLimits(timeMs = 300))
        assertEquals(0, coach.state.value.analyzingPly)
        val done = withTimeout(5000) { coach.state.first { it.analyzingPly == null } }
        val c = done.comments[0]!!
        assertEquals(1, engine.analysed.size)
        assertEquals(0, engine.analysed[0].moves.size)                        // the PRE-move position was analysed, not the one after
        assertEquals(chariot, c.playedMove); assertEquals(horse, c.bestMove)   // the fake ranks 02-83 best
        assertEquals(0.06, c.winRateLoss, 1e-9)                                // 0.60 vs 0.54
        assertEquals(CoachGrade.INACCURACY, c.grade)
        scope.cancel()
    }

    @Test fun aStaleResultNeverLandsOnANewerPosition() = runBlocking {
        val scope = scope(); val engine = CoachFakeEngine(); val coach = CoachController(engine, scope)
        val pre = GameSession.newGame(rules)
        coach.sync(pre.play(chariot))
        coach.evaluate(CoachRequest(pre, chariot, Board.CHO), AnalysisLimits(timeMs = 300))
        delay(20)
        // The human undid and played a different move before the coach finished: the old verdict must be discarded.
        val other = pre.play(horse)
        coach.sync(other)
        withTimeout(5000) { coach.state.first { it.analyzingPly == null } }
        assertTrue(coach.state.value.comments.isEmpty())
        // The new move gets its own review.
        coach.evaluate(CoachRequest(pre, horse, Board.CHO), AnalysisLimits(timeMs = 300))
        val done = withTimeout(5000) { coach.state.first { it.analyzingPly == null && it.comments.isNotEmpty() } }
        assertEquals(horse, done.comments[0]!!.playedMove)
        assertEquals(CoachGrade.BEST, done.comments[0]!!.grade)
        scope.cancel()
    }

    @Test fun undoDropsVerdictsOfUndoneMovesAndCancelsAPendingReview() = runBlocking {
        val scope = scope(); val engine = CoachFakeEngine(); val coach = CoachController(engine, scope)
        val s0 = GameSession.newGame(rules)
        val s1 = s0.play(chariot); val s2 = s1.play(Notation.parseMove("18-37")); val s3 = s2.play(Notation.parseMove("91-81"))
        coach.sync(s1); coach.evaluate(CoachRequest(s0, chariot, Board.CHO), AnalysisLimits(timeMs = 300))
        withTimeout(5000) { coach.state.first { it.comments.containsKey(0) } }
        coach.sync(s3); coach.evaluate(CoachRequest(s2, Notation.parseMove("91-81"), Board.CHO), AnalysisLimits(timeMs = 300))
        delay(20)
        coach.sync(s1)                                   // undo back to one move: ply-2 review cancelled, ply-0 verdict kept
        assertNull(coach.state.value.analyzingPly)
        assertEquals(setOf(0), coach.state.value.comments.keys)
        delay(200)                                       // give the cancelled job time to (not) publish
        assertEquals(setOf(0), coach.state.value.comments.keys)
        coach.clear()
        assertTrue(coach.state.value.comments.isEmpty())
        scope.cancel()
    }
}
