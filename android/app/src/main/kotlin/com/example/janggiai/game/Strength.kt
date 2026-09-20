package com.example.janggiai.game

import com.example.janggiai.engine.api.AnalysisLimits
import com.example.janggiai.engine.api.SearchLimits

/** Analysis budget presets (Settings → 분석 강도). Time is the primary knob on phones (heat/battery). */
enum class AnalysisStrength(val id: String, val label: String, val timeMs: Long?) {
    FAST("fast", "빠름", 300),
    NORMAL("normal", "보통", 1000),
    DEEP("deep", "깊게", 3000),
    MAX("max", "최대", null);

    fun limits(threads: Int, hashMb: Int): AnalysisLimits = AnalysisLimits(timeMs = timeMs, threads = threads, hashMb = hashMb)

    companion object { fun fromId(id: String?): AnalysisStrength = entries.firstOrNull { it.id == id } ?: NORMAL }
}

/**
 * AI opponent presets.  Weaker levels combine Fairy-Stockfish's `Skill Level` (0..20, which picks
 * among the top moves with a controlled probability) with a small time/depth budget; 최강 runs at
 * skill 20 with MultiPV=1 for the full configured time.
 */
enum class Difficulty(val id: String, val label: String, val skill: Int, val timeMs: Long, val depth: Int?) {
    BEGINNER("beginner", "초보", 2, 200, 4),
    INTERMEDIATE("intermediate", "중수", 8, 400, 8),
    ADVANCED("advanced", "고수", 15, 800, null),
    MAXIMUM("maximum", "최강", 20, 2000, null);

    fun limits(threads: Int, hashMb: Int, maxTimeMs: Long? = null): SearchLimits =
        SearchLimits(timeMs = if (this == MAXIMUM && maxTimeMs != null) maxTimeMs else timeMs, depth = depth,
                     threads = threads, hashMb = hashMb, skillLevel = skill)

    companion object { fun fromId(id: String?): Difficulty = entries.firstOrNull { it.id == id } ?: INTERMEDIATE }
}

/** Whose point of view win rates are shown from. */
enum class WinRatePerspective(val id: String, val label: String) {
    SIDE_TO_MOVE("stm", "둘 차례"), CHO("cho", "초"), HAN("han", "한");
    companion object { fun fromId(id: String?): WinRatePerspective = entries.firstOrNull { it.id == id } ?: SIDE_TO_MOVE }
}

object WinRateFormat {
    /** Converts a side-to-move win rate/score to the requested perspective. */
    fun fromPerspective(winRate: Double?, scoreCp: Int?, sideToMove: Int, perspective: WinRatePerspective): Pair<Double?, Int?> {
        val flip = when (perspective) {
            WinRatePerspective.SIDE_TO_MOVE -> false
            WinRatePerspective.CHO -> sideToMove != Board.CHO
            WinRatePerspective.HAN -> sideToMove != Board.HAN
        }
        return if (!flip) winRate to scoreCp else (winRate?.let { 1.0 - it }) to (scoreCp?.let { -it })
    }

    fun percent(p: Double?): String = if (p == null) "—" else String.format(java.util.Locale.US, "%.1f%%", p * 100)

    fun score(scoreCp: Int?, mateIn: Int?): String = when {
        mateIn != null -> if (mateIn > 0) "M$mateIn" else "-M${-mateIn}"
        scoreCp == null -> "—"
        else -> String.format(java.util.Locale.US, "%+.2f", scoreCp / 100.0)
    }
}

/** Time the coach spends re-analysing the position the human just moved from. Separate from the AI's own search budget. */
enum class CoachBudget(val id: String, val label: String, val timeMs: Long) {
    FAST("fast", "빠름", 300), NORMAL("normal", "보통", 800), PRECISE("precise", "정밀", 1500);

    fun limits(threads: Int, hashMb: Int): com.example.janggiai.engine.api.AnalysisLimits =
        com.example.janggiai.engine.api.AnalysisLimits(timeMs = timeMs, threads = threads, hashMb = hashMb)

    companion object { fun fromId(id: String?): CoachBudget = entries.firstOrNull { it.id == id } ?: NORMAL }
}
