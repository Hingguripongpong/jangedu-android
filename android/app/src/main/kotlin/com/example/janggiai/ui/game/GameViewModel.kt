package com.example.janggiai.ui.game

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.janggiai.coach.CoachComment
import com.example.janggiai.coach.CoachController
import com.example.janggiai.coach.CoachPolicy
import com.example.janggiai.coach.CoachRequest
import com.example.janggiai.coach.CoachState
import com.example.janggiai.di.GameDeps
import com.example.janggiai.engine.api.EngineMove
import com.example.janggiai.engine.api.SearchInfo
import com.example.janggiai.game.Board
import com.example.janggiai.game.BoardOrientation
import com.example.janggiai.game.Difficulty
import com.example.janggiai.game.GameConfig
import com.example.janggiai.game.GameSession
import com.example.janggiai.game.Move
import com.example.janggiai.game.Notation
import com.example.janggiai.game.Position
import com.example.janggiai.game.ResolvedGameConfig
import com.example.janggiai.game.SetupChoice
import com.example.janggiai.game.SideChoice
import com.example.janggiai.ui.common.AppForeground
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class GameMode(val id: String, val label: String) { HUMAN_VS_AI("hva", "AI와 대국"), AI_VS_AI("ava", "AI 대 AI");
    companion object { fun fromId(id: String?): GameMode = entries.firstOrNull { it.id == id } ?: HUMAN_VS_AI }
}

data class GameUiState(
    val session: GameSession,
    val mode: GameMode,
    /** Side the human plays in HUMAN_VS_AI (Board.CHO / Board.HAN). The AI plays the other side. */
    val humanSide: Int,
    val difficulty: Difficulty,
    /** Setup names (as in Position.SETUPS) each side started with; fixed for the whole game. */
    val humanSetup: String = Position.DEFAULT_SETUP,
    val aiSetup: String = Position.DEFAULT_SETUP,
    val coachEnabled: Boolean = true,
    /** Presentation only: which side is drawn at the bottom. Never touches the game state. */
    val flipped: Boolean = false,
    val selected: Int? = null,
    val legalTargets: Set<Int> = emptySet(),
    val thinking: Boolean = false,
    val thinkingInfo: SearchInfo? = null,
    val lastEngineMove: EngineMove? = null,
    /** AI vs AI: paused by the user. */
    val paused: Boolean = false,
    val showGameOver: Boolean = false,
    val showNewGame: Boolean = false,
    val coach: CoachState = CoachState(),
    /** Ply whose coach verdict is shown (null = the latest one). */
    val coachViewPly: Int? = null,
    /** "최선수 보기": show the pre-move position of this verdict with played vs best move. */
    val previewPly: Int? = null,
    val error: String? = null,
) {
    val aiSide: Int get() = humanSide xor 1
    val humanTurn: Boolean get() = mode == GameMode.HUMAN_VS_AI && session.position.side == humanSide && !session.status.isOver
    val coachActive: Boolean get() = mode == GameMode.HUMAN_VS_AI && coachEnabled

    /** Verdict currently displayed in the coach card. */
    val coachComment: CoachComment? get() = coach.comments[coachViewPly ?: coach.comments.keys.maxOrNull() ?: -1]
    val previewComment: CoachComment? get() = previewPly?.let { coach.comments[it] }
    /** Index (in session.moves) of the human's most recent move, or null. */
    val lastHumanPly: Int? get() = ((session.moves.size - 1) downTo 0).firstOrNull { moverOf(it) == humanSide }
    /** Side that played move [ply]: Cho always starts (rules engine), so parity decides. */
    fun moverOf(ply: Int): Int = if (ply % 2 == 0) Board.CHO else Board.HAN
}

/**
 * Human-vs-AI and AI-vs-AI games.  The AI move runs on the engine's own threads via
 * [com.example.janggiai.engine.api.JanggiEngine.bestMove] (play mode, MultiPV = 1); the result is
 * applied only if the game has not changed in the meantime (undo/new game while thinking).
 *
 * Coach: when the human moves, the pre-move session + move are remembered.  The AI reply is searched
 * first (MultiPV = 1, full strength); only after it is applied does [CoachController] analyse the
 * pre-move position (MultiPV = every legal move) on the same engine, so the two searches never overlap.
 */
class GameViewModel(private val handle: SavedStateHandle, private val deps: GameDeps, initialMode: GameMode, initialHumanSide: Int, configure: Boolean = false) : ViewModel() {
    private val settings get() = deps.settingsFlow.value
    private val coach = CoachController(deps.engine, viewModelScope)

    private val _state = MutableStateFlow(restore(initialMode, initialHumanSide, configure))
    val state: StateFlow<GameUiState> = _state.asStateFlow()

    private var aiJob: Job? = null
    /**
     * True from the moment an AI turn is scheduled until its bestMove search has returned.  The coach must never
     * hand the engine a second search while this is set.  (Checking `aiJob.isActive` is wrong here: the coach is
     * started from inside that very coroutine, which is of course still active — the bug behind the missing verdicts.)
     */
    @Volatile private var aiSearching = false
    private var generation = 0L   // bumps on every user action that invalidates a running search
    /** Human move waiting for its coach analysis (started once the AI has replied). */
    private var pendingCoach: CoachRequest? = null
    /** Last request handed to the coach (for "다시 분석"). */
    private var lastCoachRequest: CoachRequest? = null

    init {
        coach.restore(CoachComment.decodeAll(handle.get<String>("coach")), _state.value.session)
        viewModelScope.launch { coach.state.collect { c -> _state.update { it.copy(coach = c) }; handle["coach"] = CoachComment.encodeAll(c.comments.values) } }
        viewModelScope.launch {
            AppForeground.state.collect { fg -> if (fg) { maybeStartAi(); startPendingCoach() } else pauseEngine() }
        }
    }

    private fun restore(mode: GameMode, humanSide: Int, configure: Boolean): GameUiState {
        val rules = settings.rules
        val moves = handle.get<String>("moves")?.takeIf { it.isNotEmpty() }?.split(",") ?: emptyList()
        val fen = handle.get<String>("startFen")
        // .position forces replay validation now, so a saved game that no longer fits the rules falls back to a new one
        val session = if (fen != null) runCatching { GameSession(startFen = fen, moves = moves, viewIndex = moves.size, rules = rules).also { it.position } }.getOrNull() else null
        val side = handle.get<Int>("humanSide") ?: humanSide
        return GameUiState(
            session = session ?: GameSession.newGame(rules),
            mode = GameMode.fromId(handle.get<String>("mode") ?: mode.id),
            humanSide = side,
            difficulty = Difficulty.fromId(handle.get<String>("difficulty") ?: settings.difficulty.id),
            humanSetup = handle.get<String>("humanSetup") ?: Position.DEFAULT_SETUP,
            aiSetup = handle.get<String>("aiSetup") ?: Position.DEFAULT_SETUP,
            coachEnabled = handle.get<Boolean>("coachEnabled") ?: settings.coachEnabled,
            flipped = handle.get<Boolean>("flipped") ?: BoardOrientation.defaultFlipped(side),
            paused = handle.get<Boolean>("paused") ?: false,
            // A fresh screen opened from Home shows the configuration dialog first.
            showNewGame = handle.get<Boolean>("showNewGame") ?: (configure && session == null),
        )
    }

    private fun persist(s: GameUiState) {
        handle["startFen"] = s.session.startFen
        handle["moves"] = s.session.moves.joinToString(",")
        handle["mode"] = s.mode.id
        handle["humanSide"] = s.humanSide
        handle["difficulty"] = s.difficulty.id
        handle["humanSetup"] = s.humanSetup
        handle["aiSetup"] = s.aiSetup
        handle["coachEnabled"] = s.coachEnabled
        handle["flipped"] = s.flipped
        handle["paused"] = s.paused
        handle["showNewGame"] = s.showNewGame
    }

    private fun setSession(session: GameSession) {
        generation++
        aiJob?.cancel(); aiJob = null
        deps.engine.stop()
        coach.sync(session)
        // Publish the synced coach state in the SAME emission as the new session, so an undone move never shows its
        // old verdict for a frame while the coach collector catches up.
        _state.update { it.copy(session = session, selected = null, legalTargets = emptySet(), thinking = false, thinkingInfo = null,
            showGameOver = session.status.isOver, previewPly = null, coachViewPly = null, error = null, coach = coach.state.value) }
        persist(_state.value)
    }

    // ------------------------------------------------------------------ user actions
    fun onSquareTap(sq: Int) {
        val s = _state.value
        if (s.previewPly != null) { closePreview(); return }
        if (!s.humanTurn) return
        val session = s.session
        val sel = s.selected
        if (sel != null && sq in s.legalTargets) { playHuman(Move.encode(sel, sq)); return }
        val piece = session.position.board[sq]
        if (piece != 0 && com.example.janggiai.game.Piece.colorOf(piece) == session.position.side) {
            _state.update { it.copy(selected = sq, legalTargets = session.legalTargets(sq).toSet()) }
        } else {
            _state.update { it.copy(selected = null, legalTargets = emptySet()) }
        }
    }

    fun pass() { if (_state.value.humanTurn && _state.value.session.legalMoves.contains(Move.PASS)) playHuman(Move.PASS) }

    private fun playHuman(move: Int) {
        val s = _state.value
        if (!s.session.legalMoves.contains(move)) return
        // Remember the position BEFORE the move and the move itself; the coach analyses exactly that pair.
        val request = if (CoachPolicy.reviews(s.session.position.side, s.humanSide, s.coachActive, move)) CoachRequest(pre = s.session, playedMove = move, humanSide = s.humanSide) else null
        coach.cancel()
        val next = s.session.play(move)
        setSession(next)
        pendingCoach = request
        if (next.status.isOver) startPendingCoach() else maybeStartAi()
    }

    /** Undo the last human move together with the AI reply (HUMAN_VS_AI) or one move (AI_VS_AI). */
    fun undo() {
        val s = _state.value
        var session = s.session
        if (!session.canUndo) return
        session = session.undo()
        if (s.mode == GameMode.HUMAN_VS_AI && session.position.side != s.humanSide && session.canUndo) session = session.undo()
        pendingCoach = null
        coach.cancel()
        setSession(session)   // coach.sync drops verdicts of undone moves
        maybeStartAi()
    }

    fun openNewGame() { _state.update { it.copy(showNewGame = true) }; persist(_state.value) }
    fun dismissNewGame() { _state.update { it.copy(showNewGame = false) }; persist(_state.value) }

    /** Starts a game from the dialog's choices. RANDOM side/setups are resolved here, once, for the whole game. */
    fun startNewGame(mode: GameMode, config: GameConfig) {
        val resolved: ResolvedGameConfig = config.resolve()
        generation++
        aiJob?.cancel(); aiJob = null
        deps.engine.stop()
        pendingCoach = null; lastCoachRequest = null
        coach.clear()
        viewModelScope.launch { runCatching { deps.engine.newGame() } }
        val session = resolved.newSession(settings.rules)
        coach.sync(session)
        _state.value = GameUiState(
            session = session, mode = mode, humanSide = resolved.humanSide, difficulty = resolved.difficulty,
            humanSetup = resolved.humanSetup, aiSetup = resolved.aiSetup, coachEnabled = resolved.coachEnabled,
            flipped = if (mode == GameMode.HUMAN_VS_AI) BoardOrientation.defaultFlipped(resolved.humanSide) else settings.flipBoard,
            coach = coach.state.value,
        )
        persist(_state.value)
        deps.updateSettings { it.copy(difficulty = resolved.difficulty, coachEnabled = resolved.coachEnabled) }
        maybeStartAi()    // if the human took Han, Cho (the AI) moves first
    }

    /** Current choices for the new-game dialog, derived from the running game. */
    fun currentConfig(): GameConfig = _state.value.let { s ->
        GameConfig(
            humanSide = if (s.humanSide == Board.CHO) SideChoice.CHO else SideChoice.HAN,
            humanSetup = SetupChoice.ofSetupName(s.humanSetup) ?: SetupChoice.MSSM,
            aiSetup = SetupChoice.ofSetupName(s.aiSetup) ?: SetupChoice.MSSM,
            difficulty = s.difficulty, coachEnabled = s.coachEnabled,
        )
    }

    fun setDifficulty(d: Difficulty) { _state.update { it.copy(difficulty = d) }; persist(_state.value); deps.updateSettings { it.copy(difficulty = d) } }

    /** Presentation only: rotate the board 180°. */
    fun toggleFlip() { _state.update { it.copy(flipped = !it.flipped) }; persist(_state.value) }

    fun togglePause() {
        _state.update { it.copy(paused = !it.paused) }
        persist(_state.value)
        if (_state.value.paused) pauseEngine() else maybeStartAi()
    }

    fun dismissGameOver() { _state.update { it.copy(showGameOver = false) } }

    // ------------------------------------------------------------------ coach
    /** Show the verdict of the human move at [ply] (tap in the move list); null = latest. */
    fun viewCoach(ply: Int?) { _state.update { it.copy(coachViewPly = ply?.takeIf { p -> it.coach.comments.containsKey(p) }, previewPly = null) } }

    /** "최선수 보기": preview the pre-move position with played vs best move; game state is untouched. */
    fun togglePreview() {
        _state.update { s -> val c = s.coachComment; s.copy(previewPly = if (s.previewPly != null || c == null) null else c.ply) }
    }
    fun closePreview() { _state.update { it.copy(previewPly = null) } }

    fun retryCoach() { lastCoachRequest?.let { req -> if (req.stillValidIn(_state.value.session)) startCoach(req) } }

    private fun startPendingCoach() {
        val req = pendingCoach ?: return
        if (!AppForeground.state.value) return
        if (aiSearching) return                       // the AI reply comes first; the AI turn calls us again when its search is done
        pendingCoach = null
        startCoach(req)
    }

    private fun startCoach(req: CoachRequest) {
        if (!req.stillValidIn(_state.value.session)) return
        lastCoachRequest = req
        val cfg = settings
        coach.evaluate(req, cfg.coachBudget.limits(cfg.threads, cfg.hashMb))
    }

    /** Title + detail for the game-over dialog. */
    fun resultText(): String {
        val st = _state.value.session.status
        val winner = st.winner?.let { Notation.sideKorean(it) }
        return when (st.end) {
            com.example.janggiai.game.GameEnd.CHECKMATE -> "외통 · $winner 승"
            com.example.janggiai.game.GameEnd.BIKJANG_POINTS -> "빅장 · 점수로 $winner 승 (초 ${st.points.cho} : 한 ${st.points.han})"
            com.example.janggiai.game.GameEnd.PASSES_POINTS -> "연속 한수쉼 · 점수로 $winner 승 (초 ${st.points.cho} : 한 ${st.points.han})"
            com.example.janggiai.game.GameEnd.DRAW_BIKJANG -> "빅장 · 무승부"
            com.example.janggiai.game.GameEnd.DRAW_PASSES -> "연속 한수쉼 · 무승부"
            com.example.janggiai.game.GameEnd.DRAW_REPETITION -> "동형 반복 · 무승부"
            com.example.janggiai.game.GameEnd.DRAW_MOVE_LIMIT -> "수 제한 · 무승부"
            com.example.janggiai.game.GameEnd.ONGOING -> "진행 중"
        }
    }

    suspend fun saveRecord(): String {
        val s = _state.value
        val title = if (s.mode == GameMode.AI_VS_AI) "AI 대 AI · ${s.difficulty.label}" else "${Notation.sideKorean(s.humanSide)} vs AI · ${s.difficulty.label}"
        val human = if (s.mode == GameMode.HUMAN_VS_AI) s.humanSide else null
        return deps.saveGameRecord(s.session, title, resultText(), human, s.coach.comments)
    }

    // ------------------------------------------------------------------ AI
    private fun pauseEngine() {
        aiJob?.cancel(); aiJob = null
        coach.cancel()
        deps.engine.stop()
        _state.update { it.copy(thinking = false) }
    }

    private fun maybeStartAi() {
        val s = _state.value
        if (!AppForeground.state.value) return
        if (s.session.status.isOver) return
        if (s.mode == GameMode.HUMAN_VS_AI && s.session.position.side == s.humanSide) return
        if (s.mode == GameMode.AI_VS_AI && s.paused) return
        if (aiJob?.isActive == true) return
        val gen = generation
        aiSearching = true
        aiJob = viewModelScope.launch {
            _state.update { it.copy(thinking = true, thinkingInfo = null) }
            try {
                if (s.mode == GameMode.AI_VS_AI) delay(350)   // let the previous move be seen
                val cfg = settings
                val limits = s.difficulty.limits(cfg.threads, cfg.hashMb, cfg.maxThinkMs)
                val result = try {
                    deps.engine.bestMove(s.session.enginePosition(), limits) { info ->
                        if (generation == gen) _state.update { it.copy(thinkingInfo = info) }
                    }
                } finally {
                    aiSearching = false                        // the engine is idle again whatever happened
                }
                if (generation != gen) return@launch
                val m = result.move
                if (m == null || !s.session.legalMoves.contains(m)) {
                    _state.update { it.copy(thinking = false, error = if (result.aborted) null else "엔진이 수를 찾지 못했습니다") }
                    return@launch
                }
                val next = s.session.play(m)
                coach.sync(next)
                _state.update { it.copy(session = next, thinking = false, lastEngineMove = result, selected = null, legalTargets = emptySet(), showGameOver = next.status.isOver, coach = coach.state.value) }
                persist(_state.value)
                // The AI has replied and its search is over (aiSearching == false): the coach may use the engine now.
                if (s.mode == GameMode.HUMAN_VS_AI) startPendingCoach()
                if (next.status.isOver) return@launch
                if (s.mode == GameMode.AI_VS_AI) { aiJob = null; maybeStartAi() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                aiSearching = false                            // cancelled before/while searching: nothing is in flight any more
                throw e
            } catch (e: Exception) {
                aiSearching = false
                if (generation == gen) _state.update { it.copy(thinking = false, error = e.message ?: e.toString()) }
            }
        }
    }

    fun start() { maybeStartAi() }

    override fun onCleared() {
        aiJob?.cancel()
        coach.cancel()
        deps.engine.stop()
    }

    companion object { const val DEFAULT_HUMAN_SIDE = Board.CHO }
}
