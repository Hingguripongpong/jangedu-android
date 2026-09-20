package com.example.janggiai.ui.analysis

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.janggiai.di.AppContainer
import com.example.janggiai.engine.api.MoveAnalysis
import com.example.janggiai.game.AnalysisController
import com.example.janggiai.game.AnalysisStrength
import com.example.janggiai.game.AnalysisUiState
import com.example.janggiai.game.GameSession
import com.example.janggiai.game.Move
import com.example.janggiai.game.Piece
import com.example.janggiai.ui.common.AppForeground
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AnalysisScreenState(
    val session: GameSession,
    val selected: Int? = null,
    val legalTargets: Set<Int> = emptySet(),
    val analysis: AnalysisUiState = AnalysisUiState(),
    /** Candidate whose PV is highlighted (encoded move). */
    val focusedMove: Int? = null,
    val strength: AnalysisStrength,
    /** User switched analysis off (stop button). */
    val autoAnalyze: Boolean = true,
    /** Presentation only: board orientation override (null = the global 장기판 뒤집기 setting). */
    val flipOverride: Boolean? = null,
    /** Candidate list shows every move (default) or only the top 5. Never affects the analysis itself. */
    val showAllCandidates: Boolean = true,
    /** Coach verdicts stored with an opened record (by ply); shown read-only next to the move list. */
    val recordCoach: Map<Int, com.example.janggiai.coach.CoachComment> = emptyMap(),
    val error: String? = null,
    val message: String? = null,
) {
    /** Snapshot only if it belongs to the position on screen. */
    val currentSnapshot get() = analysis.snapshot?.takeIf { analysis.stateKey == session.position.stateKey() }
    val focused: MoveAnalysis? get() = currentSnapshot?.moves?.firstOrNull { it.move == focusedMove }
}

/**
 * Analysis mode: the user plays both sides (or steps through a record) and the engine scores every
 * legal move of the displayed position (MultiPV = legal move count).  Stale results are impossible
 * by construction: the snapshot is shown only if its state key matches the position on screen, and
 * the controller additionally drops superseded analysis ids.
 */
class AnalysisViewModel(private val handle: SavedStateHandle, private val container: AppContainer, recordId: String?) : ViewModel() {
    private val settings get() = container.settings.settings.value
    private val controller = AnalysisController(container.engines.get(), viewModelScope)

    private val _state = MutableStateFlow(restore())
    val state: StateFlow<AnalysisScreenState> = _state.asStateFlow()

    init {
        viewModelScope.launch { controller.state.collect { a -> _state.update { it.copy(analysis = a) } } }
        viewModelScope.launch { AppForeground.state.collect { fg -> if (fg) analyzeCurrent() else controller.stop() } }
        if (recordId != null && handle.get<String>("startFen") == null) {
            viewModelScope.launch {
                val rec = container.records.list().firstOrNull { it.id == recordId }
                if (rec != null) { setSession(rec.toSession().first()); _state.update { it.copy(recordCoach = rec.coachComments) } }
            }
        }
    }

    private fun restore(): AnalysisScreenState {
        val rules = settings.rules
        val moves = handle.get<String>("moves")?.takeIf { it.isNotEmpty() }?.split(",") ?: emptyList()
        val fen = handle.get<String>("startFen")
        val view = handle.get<Int>("viewIndex") ?: moves.size
        val session = if (fen != null) runCatching { GameSession(startFen = fen, moves = moves, viewIndex = view.coerceIn(0, moves.size), rules = rules).also { it.position } }.getOrNull() else null
        return AnalysisScreenState(
            session = session ?: GameSession.newGame(rules),
            strength = AnalysisStrength.fromId(handle.get<String>("strength") ?: settings.analysisStrength.id),
            autoAnalyze = handle.get<Boolean>("auto") ?: true,
            flipOverride = handle.get<Boolean>("flip"),
            showAllCandidates = handle.get<Boolean>("showAll") ?: true,
        )
    }

    private fun persist(s: AnalysisScreenState) {
        handle["startFen"] = s.session.startFen
        handle["moves"] = s.session.moves.joinToString(",")
        handle["viewIndex"] = s.session.viewIndex
        handle["strength"] = s.strength.id
        handle["auto"] = s.autoAnalyze
        if (s.flipOverride != null) handle["flip"] = s.flipOverride
        handle["showAll"] = s.showAllCandidates
    }

    /** Rotate the board 180° (presentation only; side to move and the position are untouched). */
    fun toggleFlip(currentDefault: Boolean) {
        _state.update { it.copy(flipOverride = !(it.flipOverride ?: currentDefault)) }
        persist(_state.value)
    }

    fun setShowAllCandidates(all: Boolean) { _state.update { it.copy(showAllCandidates = all) }; persist(_state.value) }

    private fun setSession(session: GameSession) {
        _state.update { it.copy(session = session, selected = null, legalTargets = emptySet(), focusedMove = null, error = null, message = null) }
        persist(_state.value)
        analyzeCurrent()
    }

    private fun analyzeCurrent() {
        val s = _state.value
        if (!s.autoAnalyze || !AppForeground.state.value) return
        if (s.session.status.isOver) { controller.clear(); return }
        val cfg = settings
        controller.request(s.session.enginePosition(), s.strength.limits(cfg.threads, cfg.hashMb), s.session.position.stateKey())
    }

    // ------------------------------------------------------------------ board interaction
    fun onSquareTap(sq: Int) {
        val s = _state.value
        val session = s.session
        if (session.status.isOver) return
        val sel = s.selected
        if (sel != null && sq in s.legalTargets) { playMove(Move.encode(sel, sq)); return }
        val piece = session.position.board[sq]
        if (piece != 0 && Piece.colorOf(piece) == session.position.side) {
            _state.update { it.copy(selected = sq, legalTargets = session.legalTargets(sq).toSet()) }
        } else {
            _state.update { it.copy(selected = null, legalTargets = emptySet()) }
        }
    }

    fun playMove(move: Int) {
        val s = _state.value
        if (!s.session.legalMoves.contains(move)) return
        setSession(s.session.play(move))
    }

    fun pass() = playMove(Move.PASS)

    fun focusCandidate(move: Int?) { _state.update { it.copy(focusedMove = if (it.focusedMove == move) null else move) } }

    fun undo() { val s = _state.value; if (s.session.canUndo) setSession(s.session.undo()) }
    fun first() = setSession(_state.value.session.first())
    fun previous() = setSession(_state.value.session.previous())
    fun next() = setSession(_state.value.session.next())
    fun last() = setSession(_state.value.session.last())
    fun goTo(index: Int) = setSession(_state.value.session.goTo(index))

    fun newGame() { viewModelScope.launch { runCatching { container.engines.get().newGame() } }; setSession(GameSession.newGame(settings.rules)) }

    /** Returns null on success, or an error message. */
    fun loadFen(fen: String): String? {
        return try { setSession(GameSession.fromFen(fen.trim(), settings.rules)); null }
        catch (e: IllegalArgumentException) { "FEN 오류: ${e.message}" }
    }

    fun setStrength(strength: AnalysisStrength) {
        _state.update { it.copy(strength = strength) }
        persist(_state.value)
        container.settings.update { it.copy(analysisStrength = strength) }
        controller.stop()
        analyzeCurrent()
    }

    fun toggleAnalysis() {
        val s = _state.value
        if (s.autoAnalyze) { controller.stop(); _state.update { it.copy(autoAnalyze = false) } }
        else { _state.update { it.copy(autoAnalyze = true) }; analyzeCurrent() }
        persist(_state.value)
    }

    /** Re-run the current position ignoring the cache (e.g. after changing strength to MAX). */
    fun reanalyze() {
        val s = _state.value
        if (s.session.status.isOver) return
        val cfg = settings
        _state.update { it.copy(autoAnalyze = true) }
        controller.request(s.session.enginePosition(), s.strength.limits(cfg.threads, cfg.hashMb), s.session.position.stateKey(), useCache = false)
    }

    fun saveRecord() {
        viewModelScope.launch {
            val s = _state.value
            val rec = container.records.save(s.session.last(), "분석 · ${s.session.moves.size}수", s.session.last().status.end.id)
            _state.update { it.copy(message = "저장됨: ${rec.title}") }
        }
    }

    fun clearMessage() { _state.update { it.copy(message = null) } }

    override fun onCleared() { controller.stop() }
}
