package com.example.janggiai

import androidx.lifecycle.SavedStateHandle
import com.example.janggiai.coach.CoachComment
import com.example.janggiai.data.Settings
import com.example.janggiai.di.GameDeps
import com.example.janggiai.engine.api.AnalysisLimits
import com.example.janggiai.engine.api.AnalysisSnapshot
import com.example.janggiai.engine.api.EngineMove
import com.example.janggiai.engine.api.EnginePosition
import com.example.janggiai.engine.api.JanggiEngine
import com.example.janggiai.engine.api.MoveAnalysis
import com.example.janggiai.engine.api.SearchInfo
import com.example.janggiai.engine.api.SearchLimits
import com.example.janggiai.game.Board
import com.example.janggiai.game.CoachBudget
import com.example.janggiai.game.GameSession
import com.example.janggiai.game.Move
import com.example.janggiai.game.MoveGen
import com.example.janggiai.game.Notation
import com.example.janggiai.ui.common.AppForeground
import com.example.janggiai.ui.game.GameMode
import com.example.janggiai.ui.game.GameViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fake engine that answers like the real one at the API level and records every search:
 * events ("bestMove:start/end", "analyze:start/end"), the positions searched, and the maximum number of
 * simultaneous searches (must stay 1 — one UCI stream).  bestMove returns the first legal non-pass move;
 * analyze scores every legal move of the requested position (02-83 best when available).
 */
private class SerialCheckEngine : JanggiEngine {
    override val name = "fake"
    val events: MutableList<String> = Collections.synchronizedList(ArrayList())
    val bestMovePositions = Collections.synchronizedList(ArrayList<EnginePosition>())
    val analyzePositions = Collections.synchronizedList(ArrayList<EnginePosition>())
    private val active = AtomicInteger(0)
    val maxActive = AtomicInteger(0)

    private fun enter(tag: String) { events += "$tag:start"; val n = active.incrementAndGet(); maxActive.updateAndGet { maxOf(it, n) } }
    private fun exit(tag: String) { active.decrementAndGet(); events += "$tag:end" }

    override suspend fun bestMove(position: EnginePosition, limits: SearchLimits, onInfo: ((SearchInfo) -> Unit)?): EngineMove {
        enter("bestMove"); bestMovePositions += position
        try {
            delay(40)
            val pos = position.toPosition()
            val m = MoveGen.legalMoves(pos).first { it != Move.PASS }
            return EngineMove(m, Notation.moveToString(m), Notation.describe(pos.board, m), 10, null, 0.52, 8, 1000, 50000, 40, listOf(m), name, false)
        } finally { exit("bestMove") }
    }

    override fun analyze(position: EnginePosition, limits: AnalysisLimits, analysisId: Long): Flow<AnalysisSnapshot> = flow {
        enter("analyze"); analyzePositions += position
        try {
            val pos = position.toPosition()
            val legal = MoveGen.legalMoves(pos)
            val preferred = Notation.parseMove("02-83")
            val ranked = legal.sortedByDescending { m -> if (m == Move.PASS) -1 else if (m == preferred) 40 else 16 }
            fun snap(final: Boolean): AnalysisSnapshot {
                val moves = ranked.mapIndexed { i, m ->
                    val cp = if (m == Move.PASS) -50 else if (m == preferred) 40 else 16
                    MoveAnalysis(i + 1, m, Notation.moveToString(m), Notation.describe(pos.board, m), if (m == Move.PASS) null else Move.from(m), if (m == Move.PASS) null else Move.to(m),
                        cp, null, 0.5 + cp / 400.0, 10, 12, 1000, listOf(m), listOf(Notation.describe(pos.board, m)), null, false, false)
                }
                return AnalysisSnapshot(analysisId, pos.toFen(), pos.stateKey(), pos.side, moves, 10, 12, 10000, 100000, 100, final, false, null, name, moves.size, moves.size)
            }
            delay(30); emit(snap(false))
            delay(30); emit(snap(true))
        } finally { exit("analyze") }
    }

    override fun stop() {}
    override suspend fun newGame() {}
    override fun close() {}
}

private class FakeDeps(override val engine: SerialCheckEngine) : GameDeps {
    private val s = MutableStateFlow(Settings(threads = 1, coachEnabled = true, coachBudget = CoachBudget.FAST))
    override val settingsFlow: StateFlow<Settings> get() = s
    override fun updateSettings(transform: (Settings) -> Settings) { s.value = transform(s.value) }
    override suspend fun saveGameRecord(session: GameSession, title: String, result: String, humanSide: Int?, coachComments: Map<Int, CoachComment>): String = title
}

/**
 * Regression test for the missing coach verdicts seen on device: the coach used to be started from inside
 * the still-active AI coroutine and bailed out on `aiJob.isActive`.  Exercises the real GameViewModel:
 * human move → AI bestMove → AI reply applied → coach analysis of the pre-move position → CoachComment,
 * and checks that the two searches never overlap and run in the order bestMove, then analyze.
 */
class GameViewModelCoachFlowTest {
    @Before fun setUp() { Dispatchers.setMain(Dispatchers.Default); AppForeground.set(true) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun tap(vm: GameViewModel, move: String) { vm.onSquareTap(Notation.parseSquare(move.substringBefore('-'))); vm.onSquareTap(Notation.parseSquare(move.substringAfter('-'))) }

    @Test fun humanMoveGetsAVerdictAfterTheAiReplyAndSearchesNeverOverlap() = runBlocking {
        val engine = SerialCheckEngine()
        val vm = GameViewModel(SavedStateHandle(), FakeDeps(engine), GameMode.HUMAN_VS_AI, Board.CHO, configure = false)
        vm.start()
        assertTrue(vm.state.value.humanTurn)

        tap(vm, "02-83")
        val afterAi = withTimeout(5000) { vm.state.first { it.session.moves.size == 2 && !it.thinking } }
        assertEquals(Board.CHO, afterAi.session.position.side)                       // back to the human
        val withVerdict = withTimeout(5000) { vm.state.first { it.coach.comments.containsKey(0) } }
        val c = withVerdict.coach.comments[0]!!
        assertEquals(Notation.parseMove("02-83"), c.playedMove)
        assertEquals(Board.CHO, c.humanSide)
        assertTrue(c.playedIsBest)                                                    // the fake ranks 02-83 best
        assertNull(withVerdict.coach.analyzingPly)

        // Order and exclusivity on the engine: the AI search finished before the coach search began.
        assertEquals(listOf("bestMove:start", "bestMove:end", "analyze:start", "analyze:end"), engine.events.toList())
        assertEquals(1, engine.maxActive.get())
        assertEquals(1, engine.bestMovePositions[0].moves.size)                        // AI searched the position after the human move
        assertEquals(0, engine.analyzePositions[0].moves.size)                         // coach analysed the position BEFORE it
        assertEquals(setOf(0), withVerdict.coach.comments.keys)                         // the AI's own move (ply 1) is not reviewed

        // A second human move repeats the cycle; the AI job must have been released after the first reply.
        val next = vm.state.value.session
        val second = next.legalMoves.first { it != Move.PASS }
        vm.onSquareTap(Move.from(second)); vm.onSquareTap(Move.to(second))
        withTimeout(5000) { vm.state.first { it.session.moves.size == 4 && !it.thinking } }
        val two = withTimeout(5000) { vm.state.first { it.coach.comments.containsKey(2) } }
        assertEquals(setOf(0, 2), two.coach.comments.keys)
        val ev = engine.events.toList()
        assertEquals(listOf("bestMove:start", "bestMove:end", "analyze:start", "analyze:end", "bestMove:start", "bestMove:end", "analyze:start", "analyze:end"), ev)
        assertEquals(1, engine.maxActive.get())

        // Undo removes the human move and its verdict together with the AI reply.
        vm.undo()
        val undone = withTimeout(5000) { vm.state.first { it.session.moves.size == 2 } }
        assertEquals(setOf(0), undone.coach.comments.keys)
        assertFalse(undone.thinking)
    }
}
