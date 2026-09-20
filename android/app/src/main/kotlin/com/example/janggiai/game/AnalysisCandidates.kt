package com.example.janggiai.game

import com.example.janggiai.engine.api.AnalysisSnapshot
import com.example.janggiai.engine.api.MoveAnalysis

/** How many candidates the analysis board shows. The default is every legal move. */
enum class BoardCandidatesMode(val id: String, val label: String) {
    ALL("all", "전체"), TOP5("top5", "Top 5");
    companion object { fun fromId(id: String?): BoardCandidatesMode = entries.firstOrNull { it.id == id } ?: ALL }
}

/** One candidate as the board draws it: rank badge on the target square, win rate from the chosen perspective. */
data class CandidateMarker(val move: Int, val rank: Int, val winRate: Double?, val scoreCp: Int?, val mateIn: Int?, val depth: Int, val notation: String, val scored: Boolean) {
    /** Badge text: win rate when it is emphasised, otherwise just the rank. */
    fun label(emphasised: Boolean): String = if (emphasised && winRate != null) WinRateFormat.percent(winRate) else "$rank"
}

/**
 * Pure UI model for the analysis screen. Never truncates: a position with 31 legal moves yields 31
 * entries (unscored ones last, in engine order), so the list can show every move. [BoardCandidatesMode.TOP5]
 * only affects what the board draws, never what is analysed (MultiPV stays = legal move count).
 */
object AnalysisCandidates {
    const val TOP_N = 5

    fun fromSnapshot(snapshot: AnalysisSnapshot, perspective: WinRatePerspective): List<CandidateMarker> =
        snapshot.moves.map { ma ->
            val (wr, cp) = WinRateFormat.fromPerspective(ma.winRate, ma.scoreCp, snapshot.sideToMove, perspective)
            CandidateMarker(ma.move, ma.rank, wr, cp, ma.mateIn?.let { if (wr != ma.winRate) -it else it }, ma.depth, ma.notation, ma.scored)
        }

    /**
     * Candidates the board should draw: scored, non-pass moves; TOP5 keeps ranks 1..5; a selected
     * piece keeps only that piece's moves (so its options are readable); a focused move hides the rest.
     */
    fun forBoard(all: List<CandidateMarker>, mode: BoardCandidatesMode, selectedSquare: Int?, focusedMove: Int?): List<CandidateMarker> {
        if (focusedMove != null) return emptyList()
        var list = all.filter { it.scored && it.move != Move.PASS }
        if (selectedSquare != null) list = list.filter { Move.from(it.move) == selectedSquare }
        if (mode == BoardCandidatesMode.TOP5) list = list.filter { it.rank <= TOP_N }
        return list
    }

    /** Candidates whose badge shows the win rate instead of the rank number. */
    fun emphasised(forBoard: List<CandidateMarker>, selectedSquare: Int?): Set<Int> =
        if (selectedSquare != null) forBoard.map { it.move }.toSet() else forBoard.filter { it.rank <= TOP_N }.map { it.move }.toSet()

    /** List entries: [showAll] = every move (default); otherwise the top [TOP_N] scored moves. */
    fun forList(all: List<CandidateMarker>, showAll: Boolean): List<CandidateMarker> =
        if (showAll) all else all.filter { it.scored }.take(TOP_N)

    /** The MoveAnalysis for a move, if the snapshot has scored it. */
    fun analysisOf(snapshot: AnalysisSnapshot, move: Int): MoveAnalysis? = snapshot.moves.firstOrNull { it.move == move && it.scored }
}
