package com.example.janggiai.coach

/** Verdict on one human move, from best to worst. */
enum class CoachGrade(val id: String, val label: String) {
    BEST("best", "최선"),
    EXCELLENT("excellent", "훌륭함"),
    GOOD("good", "좋음"),
    OK("ok", "무난"),
    INACCURACY("inaccuracy", "부정확"),
    MISTAKE("mistake", "실수"),
    BLUNDER("blunder", "큰 실수");

    companion object { fun fromId(id: String?): CoachGrade = entries.firstOrNull { it.id == id } ?: OK }
}

/**
 * The single place where the grading heuristic lives.  Δ = bestWinRate − playedWinRate, both from the
 * HUMAN player's perspective, as a fraction (0.12 = 12 %p).  Initial values only; tune here.
 */
object CoachThresholds {
    /** Played move is the engine's best move, or within this of it: 최선 / 훌륭함. */
    const val EXCELLENT_MAX = 0.01
    /** 1–3 %p: 좋음. */
    const val GOOD_MAX = 0.03
    /** 3–5 %p: 무난. */
    const val OK_MAX = 0.05
    /** 5–7 %p: 부정확. */
    const val INACCURACY_MAX = 0.07
    /** 7–15 %p: 실수; above: 큰 실수. */
    const val MISTAKE_MAX = 0.15
    /** Losses below this are treated as "no difference" for the explanation text. */
    const val NEGLIGIBLE = 0.005

    fun gradeForLoss(loss: Double, playedIsBest: Boolean): CoachGrade = when {
        playedIsBest || loss <= NEGLIGIBLE -> CoachGrade.BEST
        loss < EXCELLENT_MAX -> CoachGrade.EXCELLENT
        loss < GOOD_MAX -> CoachGrade.GOOD
        loss < OK_MAX -> CoachGrade.OK
        loss < INACCURACY_MAX -> CoachGrade.INACCURACY
        loss < MISTAKE_MAX -> CoachGrade.MISTAKE
        else -> CoachGrade.BLUNDER
    }
}
