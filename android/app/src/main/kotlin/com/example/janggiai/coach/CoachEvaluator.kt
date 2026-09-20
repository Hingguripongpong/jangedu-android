package com.example.janggiai.coach

import com.example.janggiai.engine.api.AnalysisSnapshot
import com.example.janggiai.engine.api.MoveAnalysis
import com.example.janggiai.game.Board
import com.example.janggiai.game.Geometry
import com.example.janggiai.game.Move
import com.example.janggiai.game.MoveGen
import com.example.janggiai.game.Notation
import com.example.janggiai.game.Piece
import com.example.janggiai.game.Position
import com.example.janggiai.game.Rules
import com.example.janggiai.game.WinRateFormat

/**
 * Turns the engine's full-width analysis of the position BEFORE the human's move into a graded
 * [CoachComment].  The snapshot must have been produced for that pre-move position (MultiPV = every
 * legal move), so both the played move and the engine's best move carry their own evaluation.
 */
object CoachEvaluator {

    /**
     * @param pre        position before the human moved (side to move = human)
     * @param playedMove the move the human actually chose
     * @param snapshot   analysis of [pre]
     * @param humanSide  Board.CHO or Board.HAN — all win rates are converted to this side's view
     * @return null when the snapshot does not contain a scored evaluation of the played move (e.g. the
     *         search was aborted before reaching it); the caller shows "no result" instead of guessing.
     */
    fun evaluate(pre: Position, playedMove: Int, snapshot: AnalysisSnapshot, humanSide: Int, ply: Int): CoachComment? {
        val played = snapshot.moves.firstOrNull { it.move == playedMove && it.scored } ?: return null
        val best = snapshot.best ?: return null
        val flip = snapshot.sideToMove != humanSide          // engine scores are side-to-move relative

        val playedWin = humanWinRate(played, flip)
        val bestWin = humanWinRate(best, flip)
        val playedMate = played.mateIn?.let { if (flip) -it else it }
        val bestMate = best.mateIn?.let { if (flip) -it else it }
        val playedCp = played.scoreCp?.let { if (flip) -it else it }
        val bestCp = best.scoreCp?.let { if (flip) -it else it }
        val loss = ((bestWin ?: 0.0) - (playedWin ?: 0.0)).coerceAtLeast(0.0)
        val playedIsBest = best.move == played.move

        // Mate scores are decided separately from the win-rate scale.
        val grade = when {
            playedIsBest -> CoachGrade.BEST
            playedMate != null && playedMate > 0 -> CoachGrade.BEST                       // the human mates too (perhaps slower)
            bestMate != null && bestMate > 0 && (playedMate == null || playedMate <= 0) -> CoachGrade.BLUNDER   // missed a forced mate
            playedMate != null && playedMate < 0 && (bestMate == null || bestMate >= 0) -> CoachGrade.BLUNDER   // walked into a forced mate
            else -> CoachThresholds.gradeForLoss(loss, playedIsBest = false)
        }

        val explanation = CoachExplanationGenerator.explain(
            pre = pre, playedMove = played.move, bestMove = best.move, grade = grade, winRateLoss = loss,
            playedWin = playedWin, bestWin = bestWin, playedMate = playedMate, bestMate = bestMate,
            playedPv = played.pv, bestPv = best.pv, humanSide = humanSide,
        )
        return CoachComment(
            ply = ply, humanSide = humanSide, playedMove = played.move, playedNotation = played.notation,
            bestMove = best.move, bestNotation = best.notation,
            playedWinRate = playedWin, bestWinRate = bestWin, winRateLoss = loss,
            playedScoreCp = playedCp, playedMateIn = playedMate, bestScoreCp = bestCp, bestMateIn = bestMate,
            depth = played.depth, grade = grade, headline = explanation.headline, summary = explanation.summary,
            reasons = explanation.reasons, pv = best.pv, pvText = best.pvText, playedPv = played.pv,
        )
    }

    private fun humanWinRate(ma: MoveAnalysis, flip: Boolean): Double? = ma.winRate?.let { if (flip) 1.0 - it else it }
}

/**
 * Deterministic, fact-based explanations.  Everything here is derived from the board and the
 * engine's principal variations — no invented strategy.  When no concrete reason can be established the
 * text falls back to the numbers ("the engine rates this move X %p lower; best was A, expecting B").
 */
object CoachExplanationGenerator {
    data class Explanation(val headline: String, val summary: String, val reasons: List<String>)

    /** Material worth (점수제) below which a capture is not worth mentioning. */
    private const val MENTION_VALUE = 3

    fun explain(
        pre: Position, playedMove: Int, bestMove: Int, grade: CoachGrade, winRateLoss: Double,
        playedWin: Double?, bestWin: Double?, playedMate: Int?, bestMate: Int?,
        playedPv: List<Int>, bestPv: List<Int>, humanSide: Int,
    ): Explanation {
        val reasons = ArrayList<String>()
        val opp = humanSide xor 1
        val playedText = Notation.describe(pre.board, playedMove)
        val bestText = Notation.describe(pre.board, bestMove)
        val lossPct = winRateLoss * 100
        val playedIsBest = playedMove == bestMove

        // ---- facts about the two moves in the pre-move position
        val playedCapture = captureValue(pre, playedMove)
        val bestCapture = captureValue(pre, bestMove)
        val playedCheck = playedMove != Move.PASS && MoveGen.givesCheck(pre, playedMove)
        val bestCheck = bestMove != Move.PASS && MoveGen.givesCheck(pre, bestMove)
        val wasInCheck = pre.inCheck(humanSide)

        // ---- mate facts first: they dominate everything else
        if (playedMate != null && playedMate > 0) reasons += "이 수로 ${playedMate}수 안에 외통을 만들 수 있습니다."
        else if (bestMate != null && bestMate > 0) reasons += "최선수 $bestText 는 ${bestMate}수 안에 외통으로 이어지는 수였습니다."
        if (playedMate != null && playedMate < 0 && (bestMate == null || bestMate >= 0)) reasons += "이 수 이후 상대가 ${-playedMate}수 안에 외통을 만들 수 있습니다."

        // ---- immediate tactics: captures, checks, check evasion
        if (wasInCheck) reasons += if (playedIsBest) "장군을 가장 잘 회피한 수입니다." else "장군 회피 자체는 맞지만 최선수는 $bestText 였습니다."
        if (playedCapture >= MENTION_VALUE) reasons += "${pieceNameAt(pre, Move.to(playedMove))}(${playedCapture}점)을(를) 잡습니다."
        if (!playedIsBest && bestCapture >= MENTION_VALUE && bestCapture > playedCapture)
            reasons += "잡을 수 있는 ${pieceNameAt(pre, Move.to(bestMove))}(${bestCapture}점)을(를) 놓쳤습니다. 최선수는 $bestText."
        if (playedCheck && !playedIsBest && !bestCheck) reasons += "장군을 불렀지만 엔진은 장군보다 $bestText 를 더 높게 평가합니다."
        if (bestCheck && !playedCheck && !playedIsBest) reasons += "최선수 $bestText 는 장군을 부르는 수입니다."

        // ---- what the engine expects to happen next (principal variations)
        val playedLoss = firstMaterialLoss(pre, playedMove, playedPv, humanSide)
        val bestLoss = firstMaterialLoss(pre, bestMove, bestPv, humanSide)
        if (playedLoss != null && (bestLoss == null || bestLoss.value < playedLoss.value)) {
            reasons += "엔진 예상 진행에서 ${playedLoss.plyText} 상대가 ${playedLoss.pieceName}(${playedLoss.value}점)을(를) 잡습니다" +
                (if (bestLoss == null) " — 최선수 $bestText 는 그 손실을 피합니다." else ".")
        }
        val playedGain = firstMaterialGain(pre, playedMove, playedPv, humanSide)
        val bestGain = firstMaterialGain(pre, bestMove, bestPv, humanSide)
        if (!playedIsBest && bestGain != null && (playedGain == null || playedGain.value < bestGain.value) && bestCapture < MENTION_VALUE)
            reasons += "최선수 진행에서는 ${bestGain.plyText} ${bestGain.pieceName}(${bestGain.value}점)을(를) 얻습니다."

        // ---- palace pressure (only as a plain fact about the target square)
        if (!playedIsBest && Geometry.IN_PALACE[Move.to(bestMove)] == opp && Geometry.IN_PALACE[Move.to(playedMove)] != opp && bestMove != Move.PASS)
            reasons += "최선수 $bestText 는 상대 궁성 안으로 들어가는 수입니다."

        // ---- near-equal evaluation
        if (!playedIsBest && winRateLoss < CoachThresholds.EXCELLENT_MAX) reasons += "최선수와의 평가 차이가 ${fmtPct(lossPct)}p로 실질적으로 같은 수입니다."

        val headline = when (grade) {
            CoachGrade.BEST -> if (playedIsBest) "엔진 최선수입니다" else "최선수와 동등한 수입니다"
            CoachGrade.EXCELLENT -> "훌륭한 수입니다"
            CoachGrade.GOOD -> "좋은 수입니다"
            CoachGrade.OK -> "큰 문제는 없는 수입니다"
            CoachGrade.INACCURACY -> "조금 부정확한 수입니다"
            CoachGrade.MISTAKE -> "실수입니다"
            CoachGrade.BLUNDER -> "큰 실수입니다"
        }
        val numbers = "이 수의 승률 ${WinRateFormat.percent(playedWin)}, 최선수 $bestText ${WinRateFormat.percent(bestWin)} (차이 -${fmtPct(lossPct)}p)."
        val summary = when {
            playedIsBest -> "$playedText 는 엔진이 이 국면에서 가장 높게 평가한 수입니다."
            reasons.isEmpty() -> "엔진 평가에서는 이 수 이후 승률이 약 ${fmtPct(lossPct)}p 감소합니다. 최선수는 $bestText 이며 예상 진행은 ${pvPreview(pre, bestMove, bestPv)} 입니다."
            else -> numbers
        }
        return Explanation(headline, summary, reasons)
    }

    // ------------------------------------------------------------------ board facts

    /** Point value of the piece captured by [m] in [pos], 0 for none. */
    fun captureValue(pos: Position, m: Int): Int {
        if (m == Move.PASS) return 0
        val p = pos.board[Move.to(m)]
        return if (p == 0) 0 else Rules.pointValue(Piece.typeOf(p))
    }

    private fun pieceNameAt(pos: Position, sq: Int): String { val p = pos.board[sq]; return if (p == 0) "기물" else Notation.hanja(p) }

    data class MaterialEvent(val plyIndex: Int, val pieceName: String, val value: Int) {
        /** "다음 수에" for the immediate reply, "n수 뒤" otherwise. */
        val plyText: String get() = if (plyIndex == 1) "다음 수에" else "${plyIndex}수 뒤"
    }

    /**
     * Walks [firstMove] + [pv] (first element must equal [firstMove]) and returns the first capture of a
     * HUMAN piece by the opponent, up to 6 plies deep.  Null when the PV contains no such capture.
     */
    fun firstMaterialLoss(pre: Position, firstMove: Int, pv: List<Int>, humanSide: Int): MaterialEvent? =
        firstCapture(pre, firstMove, pv) { mover, victimColor -> mover != humanSide && victimColor == humanSide }

    /** First capture of an OPPONENT piece by the human within the PV. */
    fun firstMaterialGain(pre: Position, firstMove: Int, pv: List<Int>, humanSide: Int): MaterialEvent? =
        firstCapture(pre, firstMove, pv) { mover, victimColor -> mover == humanSide && victimColor != humanSide }

    private fun firstCapture(pre: Position, firstMove: Int, pv: List<Int>, accept: (mover: Int, victimColor: Int) -> Boolean): MaterialEvent? {
        val line = if (pv.isNotEmpty() && pv[0] == firstMove) pv else listOf(firstMove) + pv
        val pos = pre.copy()
        for ((i, m) in line.take(6).withIndex()) {
            if (m == Move.PASS) { if (!pos.legalMovesContain(m)) return null; pos.makeMove(m); continue }
            if (!pos.legalMovesContain(m)) return null      // PV diverged from what the rules allow: stop, never guess
            val victim = pos.board[Move.to(m)]
            val mover = pos.side
            if (victim != 0 && Piece.typeOf(victim) != Piece.KING && accept(mover, Piece.colorOf(victim))) {
                val value = Rules.pointValue(Piece.typeOf(victim))
                if (value >= MENTION_VALUE) return MaterialEvent(i, Notation.hanja(victim), value)
            }
            pos.makeMove(m)
        }
        return null
    }

    private fun Position.legalMovesContain(m: Int): Boolean = MoveGen.legalMoves(this).contains(m)

    private fun pvPreview(pre: Position, firstMove: Int, pv: List<Int>): String {
        val line = if (pv.isNotEmpty() && pv[0] == firstMove) pv else listOf(firstMove) + pv
        val pos = pre.copy()
        val out = ArrayList<String>()
        for (m in line.take(4)) {
            if (!MoveGen.legalMoves(pos).contains(m)) break
            out += Notation.describe(pos.board, m); pos.makeMove(m)
        }
        return out.joinToString(" → ")
    }

    private fun fmtPct(v: Double): String = String.format(java.util.Locale.US, "%.1f%%", v)
}
