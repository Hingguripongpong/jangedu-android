package com.example.janggiai.data

import android.content.Context
import android.content.SharedPreferences
import com.example.janggiai.game.AnalysisStrength
import com.example.janggiai.game.BikjangRule
import com.example.janggiai.game.BoardCandidatesMode
import com.example.janggiai.game.CoachBudget
import com.example.janggiai.game.Difficulty
import com.example.janggiai.game.RepetitionRule
import com.example.janggiai.game.RuleConfig
import com.example.janggiai.game.WinRatePerspective
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(val id: String, val label: String) { SYSTEM("system", "시스템"), LIGHT("light", "밝게"), DARK("dark", "어둡게");
    companion object { fun fromId(id: String?): ThemeMode = entries.firstOrNull { it.id == id } ?: SYSTEM }
}

data class Settings(
    val analysisStrength: AnalysisStrength = AnalysisStrength.NORMAL,
    val difficulty: Difficulty = Difficulty.INTERMEDIATE,
    /** Thinking time for 최강 (ms). */
    val maxThinkMs: Long = 2000,
    val threads: Int = defaultThreads(),
    val hashMb: Int = 32,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val advancedAnalysis: Boolean = false,
    val showWinRateOnBoard: Boolean = true,
    /** Analysis board: every legal move (default) or only the top 5 — never affects what is analysed. */
    val boardCandidates: BoardCandidatesMode = BoardCandidatesMode.ALL,
    /** Coach (human-vs-AI post-move review) on/off and its own time budget. */
    val coachEnabled: Boolean = true,
    val coachBudget: CoachBudget = CoachBudget.NORMAL,
    val perspective: WinRatePerspective = WinRatePerspective.SIDE_TO_MOVE,
    val bikjang: BikjangRule = BikjangRule.OFF,
    val repetition: RepetitionRule = RepetitionRule.DRAW,
    val flipBoard: Boolean = false,
) {
    val rules: RuleConfig get() = RuleConfig(bikjang = bikjang, repetition = repetition)

    companion object {
        /** Half the cores, at least 1, at most 4: keeps the UI fluid and the phone cool. */
        fun defaultThreads(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
    }
}

/** SharedPreferences-backed settings exposed as a StateFlow (no extra libraries needed). */
class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("janggi_settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private fun load(): Settings = Settings(
        analysisStrength = AnalysisStrength.fromId(prefs.getString("analysis_strength", null)),
        difficulty = Difficulty.fromId(prefs.getString("difficulty", null)),
        maxThinkMs = prefs.getLong("max_think_ms", 2000),
        threads = prefs.getInt("threads", Settings.defaultThreads()),
        hashMb = prefs.getInt("hash_mb", 32),
        theme = ThemeMode.fromId(prefs.getString("theme", null)),
        advancedAnalysis = prefs.getBoolean("advanced_analysis", false),
        showWinRateOnBoard = prefs.getBoolean("winrate_on_board", true),
        boardCandidates = BoardCandidatesMode.fromId(prefs.getString("board_candidates", null)),
        coachEnabled = prefs.getBoolean("coach_enabled", true),
        coachBudget = CoachBudget.fromId(prefs.getString("coach_budget", null)),
        perspective = WinRatePerspective.fromId(prefs.getString("perspective", null)),
        bikjang = BikjangRule.fromId(prefs.getString("bikjang", "off") ?: "off"),
        repetition = RepetitionRule.fromId(prefs.getString("repetition", "draw") ?: "draw"),
        flipBoard = prefs.getBoolean("flip_board", false),
    )

    fun update(transform: (Settings) -> Settings) {
        val s = transform(_settings.value)
        prefs.edit()
            .putString("analysis_strength", s.analysisStrength.id)
            .putString("difficulty", s.difficulty.id)
            .putLong("max_think_ms", s.maxThinkMs)
            .putInt("threads", s.threads)
            .putInt("hash_mb", s.hashMb)
            .putString("theme", s.theme.id)
            .putBoolean("advanced_analysis", s.advancedAnalysis)
            .putBoolean("winrate_on_board", s.showWinRateOnBoard)
            .putString("board_candidates", s.boardCandidates.id)
            .putBoolean("coach_enabled", s.coachEnabled)
            .putString("coach_budget", s.coachBudget.id)
            .putString("perspective", s.perspective.id)
            .putString("bikjang", s.bikjang.id)
            .putString("repetition", s.repetition.id)
            .putBoolean("flip_board", s.flipBoard)
            .apply()
        _settings.value = s
    }
}
