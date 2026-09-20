package com.example.janggiai.coach

/**
 * The coach's verdict on one human move.  All win rates are from the human player's perspective
 * (0..1).  [ply] is the index of the move in the session's move list.
 */
data class CoachComment(
    val ply: Int,
    val humanSide: Int,
    val playedMove: Int,
    val playedNotation: String,
    val bestMove: Int?,
    val bestNotation: String?,
    val playedWinRate: Double?,
    val bestWinRate: Double?,
    /** bestWinRate − playedWinRate (≥ 0 in practice; 0 when the played move is the best). */
    val winRateLoss: Double,
    val playedScoreCp: Int?,
    val playedMateIn: Int?,
    val bestScoreCp: Int?,
    val bestMateIn: Int?,
    val depth: Int,
    val grade: CoachGrade,
    val headline: String,
    val summary: String,
    val reasons: List<String>,
    /** Engine's expected continuation after the best move (first element = the best move). */
    val pv: List<Int>,
    val pvText: List<String>,
    /** Engine's expected continuation after the played move. */
    val playedPv: List<Int>,
) {
    val playedIsBest: Boolean get() = bestMove == playedMove

    /** Compact single-line encoding (SavedState / record metadata). No external JSON dependency so it is JVM-testable. */
    fun encode(): String = listOf(
        ply, humanSide, playedMove, playedNotation, bestMove ?: "", bestNotation ?: "",
        playedWinRate ?: "", bestWinRate ?: "", winRateLoss, playedScoreCp ?: "", playedMateIn ?: "", bestScoreCp ?: "", bestMateIn ?: "",
        depth, grade.id, headline, summary, reasons.joinToString(LIST_SEP), pv.joinToString(","), pvText.joinToString(LIST_SEP), playedPv.joinToString(","),
    ).joinToString(FIELD_SEP) { it.toString().replace(FIELD_SEP, " ").replace(LIST_SEP, " ") }

    companion object {
        const val FIELD_SEP = "\u001f"
        const val LIST_SEP = "\u001e"

        fun decode(text: String): CoachComment? {
            val f = text.split(FIELD_SEP)
            if (f.size < 21) return null
            fun str(i: Int): String? = f[i].takeIf { it.isNotEmpty() }
            fun ints(i: Int): List<Int> = f[i].split(",").filter { it.isNotEmpty() }.map { it.toInt() }
            fun strs(i: Int): List<String> = f[i].split(LIST_SEP).filter { it.isNotEmpty() }
            return runCatching {
                CoachComment(
                    ply = f[0].toInt(), humanSide = f[1].toInt(), playedMove = f[2].toInt(), playedNotation = f[3],
                    bestMove = str(4)?.toInt(), bestNotation = str(5), playedWinRate = str(6)?.toDouble(), bestWinRate = str(7)?.toDouble(),
                    winRateLoss = f[8].toDouble(), playedScoreCp = str(9)?.toInt(), playedMateIn = str(10)?.toInt(), bestScoreCp = str(11)?.toInt(),
                    bestMateIn = str(12)?.toInt(), depth = f[13].toInt(), grade = CoachGrade.fromId(f[14]), headline = f[15], summary = f[16],
                    reasons = strs(17), pv = ints(18), pvText = strs(19), playedPv = ints(20),
                )
            }.getOrNull()
        }

        fun encodeAll(comments: Collection<CoachComment>): String = comments.sortedBy { it.ply }.joinToString(RECORD_SEP) { it.encode() }
        fun decodeAll(text: String?): Map<Int, CoachComment> =
            text?.takeIf { it.isNotEmpty() }?.split(RECORD_SEP)?.mapNotNull { decode(it) }?.associateBy { it.ply } ?: emptyMap()
        const val RECORD_SEP = "\u001d"
    }
}
