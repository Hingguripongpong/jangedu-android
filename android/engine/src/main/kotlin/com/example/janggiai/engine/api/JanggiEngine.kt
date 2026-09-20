package com.example.janggiai.engine.api

import com.example.janggiai.game.Position
import com.example.janggiai.game.RuleConfig
import com.example.janggiai.game.Rules
import kotlinx.coroutines.flow.Flow

/**
 * Engine abstraction used by the app.  Two modes with different resource strategies:
 *
 *  * [analyze] — **analysis mode**: every legal move gets its own score, win rate and PV
 *    (Fairy-Stockfish: `MultiPV = number of legal moves`).  Results stream as the search deepens.
 *  * [bestMove] — **play mode**: one move, as strong as the budget allows (`MultiPV = 1`).
 *
 * Implementations must never block the caller's thread; all work happens on engine threads.
 */
interface JanggiEngine {
    val name: String

    /**
     * Streams [AnalysisSnapshot]s for [position]; the last emitted snapshot has `final = true`.
     * Cancelling the collecting coroutine stops the search.  Every snapshot carries [analysisId]
     * so callers can drop stale results after the position changed.
     */
    fun analyze(position: EnginePosition, limits: AnalysisLimits, analysisId: Long): Flow<AnalysisSnapshot>

    /** Single best move within [limits] (MultiPV = 1). Returns a move of null when the game is over. */
    suspend fun bestMove(position: EnginePosition, limits: SearchLimits, onInfo: ((SearchInfo) -> Unit)? = null): EngineMove

    /** Asks a running search to finish immediately (its final result still arrives). */
    fun stop()

    /** Tells the engine a new game starts (clears its hash tables). */
    suspend fun newGame()

    /** Releases the engine.  The instance must not be used afterwards. */
    fun close()
}

class EngineException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** A position given as start FEN + moves in RC notation ("95-73", "pass") so the engine sees repetitions. */
data class EnginePosition(
    val startFen: String = Position.START_FEN,
    val moves: List<String> = emptyList(),
    val rules: RuleConfig = RuleConfig.DEFAULT,
) {
    fun toPosition(): Position = Rules.replay(startFen, moves, rules)

    companion object {
        fun of(pos: Position, rules: RuleConfig): EnginePosition {
            val root = pos.copy(); root.unwindTo(0)
            return EnginePosition(root.toFen(), pos.movesPlayed().map { com.example.janggiai.game.Notation.moveToString(it) }, rules)
        }
    }
}

/** Analysis budget.  `null` time + depth + nodes = infinite (until [JanggiEngine.stop]). */
data class AnalysisLimits(
    val timeMs: Long? = 1000,
    val depth: Int? = null,
    val nodes: Long? = null,
    val threads: Int = 1,
    val hashMb: Int = 32,
    /** Cap on MultiPV (safety; the number of legal moves is usually 30-50). */
    val maxMultiPv: Int = 500,
)

/** Play budget.  [skillLevel] 0..20 maps to Fairy-Stockfish `Skill Level` (20 = full strength). */
data class SearchLimits(
    val timeMs: Long? = 1000,
    val depth: Int? = null,
    val nodes: Long? = null,
    val threads: Int = 1,
    val hashMb: Int = 32,
    val skillLevel: Int = 20,
)

data class MoveAnalysis(
    val rank: Int,
    /** Encoded move (game module). */
    val move: Int,
    val moveText: String,
    val notation: String,
    val from: Int?,
    val to: Int?,
    /** Centipawns from the side to move; mate scores are mapped to ±(100000 - n). */
    val scoreCp: Int?,
    /** Mate in n (negative: gets mated), or null. */
    val mateIn: Int?,
    /** Win probability for the side to move, or null when not yet scored. */
    val winRate: Double?,
    val depth: Int,
    val seldepth: Int,
    val nodes: Long,
    /** PV including the move itself. */
    val pv: List<Int>,
    val pvText: List<String>,
    val bound: String?,
    val givesCheck: Boolean,
    val isCapture: Boolean,
) {
    val scored: Boolean get() = scoreCp != null
}

data class AnalysisSnapshot(
    val analysisId: Long,
    val fen: String,
    val stateKey: String,
    val sideToMove: Int,
    /** Sorted best-first; moves not yet scored come last. */
    val moves: List<MoveAnalysis>,
    /** Depth completed for *all* legal moves (0 until every move has a line). */
    val depth: Int,
    val maxDepth: Int,
    val nodes: Long,
    val nps: Long,
    val timeMs: Long,
    val final: Boolean,
    val aborted: Boolean,
    val error: String? = null,
    val engineName: String,
    val multiPv: Int,
    val legalMoveCount: Int,
) {
    val best: MoveAnalysis? get() = moves.firstOrNull { it.scored }
}

data class SearchInfo(val depth: Int, val seldepth: Int, val scoreCp: Int?, val mateIn: Int?, val nodes: Long, val nps: Long, val timeMs: Long, val pv: List<String>)

data class EngineMove(
    val move: Int?,
    val moveText: String?,
    val notation: String?,
    val scoreCp: Int?,
    val mateIn: Int?,
    val winRate: Double?,
    val depth: Int,
    val nodes: Long,
    val nps: Long,
    val timeMs: Long,
    val pv: List<Int>,
    val engineName: String,
    val aborted: Boolean,
)
