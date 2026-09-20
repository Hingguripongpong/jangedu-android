package com.example.janggiai.coach

import com.example.janggiai.engine.api.AnalysisLimits
import com.example.janggiai.engine.api.AnalysisSnapshot
import com.example.janggiai.engine.api.JanggiEngine
import com.example.janggiai.game.GameSession
import com.example.janggiai.game.Move
import com.example.janggiai.game.Notation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/** What the coach needs to remember when the human moves: the position BEFORE the move and the move itself. */
data class CoachRequest(
    /** Session as displayed right before the human moved (its tip is the pre-move position). */
    val pre: GameSession,
    val playedMove: Int,
    val humanSide: Int,
) {
    /** Index the played move will have in the session's move list. */
    val ply: Int get() = pre.viewIndex
    val playedText: String get() = Notation.moveToString(playedMove)
    /** True when [session] still contains this exact move at this ply on the same start position. */
    fun stillValidIn(session: GameSession): Boolean =
        session.startFen == pre.startFen && session.moves.size > ply && session.moves[ply] == playedText &&
            session.moves.subList(0, ply) == pre.moves.subList(0, ply)
}

data class CoachState(
    /** Ply currently being analysed, or null. */
    val analyzingPly: Int? = null,
    /** Verdicts by ply (only human moves ever get one). */
    val comments: Map<Int, CoachComment> = emptyMap(),
    val error: String? = null,
    /** Set when the last analysis ended without a usable result (aborted / no score for the played move). */
    val failedPly: Int? = null,
)

/**
 * Runs the coach analysis on the shared engine.  The engine serialises searches internally, so the
 * only scheduling rule here is: never start the coach while the AI still has to reply (the caller
 * invokes [evaluate] after the AI moved, or immediately when the game ended), and cancel it as soon as
 * the position moves on.  Results are guarded twice against staleness: a monotonic id and a check that
 * the analysed move is still part of the current game (undo / new game / new branch discard it).
 *
 * This class never sees AI moves: the ViewModel calls [evaluate] for human moves only.
 */
class CoachController(private val engine: JanggiEngine, private val scope: CoroutineScope) {
    private val ids = AtomicLong(0)
    @Volatile private var currentId = 0L
    private var job: Job? = null
    private var session: GameSession? = null

    private val _state = MutableStateFlow(CoachState())
    val state: StateFlow<CoachState> = _state.asStateFlow()

    /** Tell the controller what the game currently looks like (call on every session change). */
    fun sync(current: GameSession) {
        session = current
        // Drop verdicts for moves that no longer exist (undo, new branch) and a running analysis of such a move.
        val analyzing = _state.value.analyzingPly
        if (analyzing != null && analyzing >= current.moves.size) cancel()
        _state.update { s -> s.copy(comments = s.comments.filterKeys { ply -> ply < current.moves.size }, failedPly = s.failedPly?.takeIf { it < current.moves.size }) }
    }

    /** Analyse [request] with the engine in analysis mode (MultiPV = legal moves of the pre-move position). */
    fun evaluate(request: CoachRequest, limits: AnalysisLimits) {
        cancel()
        val id = ids.incrementAndGet()
        currentId = id
        _state.update { it.copy(analyzingPly = request.ply, error = null, failedPly = null) }
        job = scope.launch {
            var last: AnalysisSnapshot? = null
            try {
                engine.analyze(request.pre.enginePosition(), limits, id)
                    .catch { e -> if (currentId == id) _state.update { it.copy(analyzingPly = null, error = e.message ?: e.toString(), failedPly = request.ply) } }
                    .collect { snap -> if (snap.analysisId == id) last = snap }
            } finally {
                if (currentId == id) finish(id, request, last)
            }
        }
    }

    private fun finish(id: Long, request: CoachRequest, snapshot: AnalysisSnapshot?) {
        val current = session
        val comment = if (snapshot != null && current != null && request.stillValidIn(current) && request.playedMove != Move.PASS)
            CoachEvaluator.evaluate(request.pre.position, request.playedMove, snapshot, request.humanSide, request.ply)
        else null
        _state.update { s ->
            if (currentId != id) s
            else if (comment != null) s.copy(analyzingPly = null, comments = s.comments + (comment.ply to comment), failedPly = null)
            else s.copy(analyzingPly = null, failedPly = if (current != null && request.stillValidIn(current)) request.ply else s.failedPly)
        }
    }

    /** Stop a running coach analysis (its result is discarded). */
    fun cancel() {
        currentId = ids.incrementAndGet()
        job?.cancel(); job = null
        if (_state.value.analyzingPly != null) _state.update { it.copy(analyzingPly = null) }
    }

    fun clear() { cancel(); _state.value = CoachState() }

    /** Restore verdicts (SavedState / record) — only those that fit the given session are kept. */
    fun restore(comments: Map<Int, CoachComment>, current: GameSession) {
        session = current
        _state.update { it.copy(comments = comments.filterKeys { ply -> ply < current.moves.size }) }
    }
}
