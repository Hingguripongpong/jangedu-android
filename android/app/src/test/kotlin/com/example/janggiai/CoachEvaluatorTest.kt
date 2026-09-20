package com.example.janggiai

import com.example.janggiai.coach.CoachComment
import com.example.janggiai.coach.CoachEvaluator
import com.example.janggiai.coach.CoachGrade
import com.example.janggiai.coach.CoachPolicy
import com.example.janggiai.coach.CoachThresholds
import com.example.janggiai.engine.api.AnalysisSnapshot
import com.example.janggiai.engine.api.MoveAnalysis
import com.example.janggiai.game.Board
import com.example.janggiai.game.Move
import com.example.janggiai.game.MoveGen
import com.example.janggiai.game.Notation
import com.example.janggiai.game.Piece
import com.example.janggiai.game.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachEvaluatorTest {
    /** One analysed line: move, win rate (side-to-move view), optional mate distance, optional PV. */
    private data class Line(val move: Int, val win: Double, val mate: Int? = null, val pv: List<Int>? = null)

    /** Snapshot for [pos] with [lines] in rank order; every other legal move is appended with a low score. */
    private fun snap(pos: Position, lines: List<Line>): AnalysisSnapshot {
        val rest = MoveGen.legalMoves(pos).filter { m -> lines.none { it.move == m } }
        val all = lines.map { Triple(it.move, it, true) } + rest.map { Triple(it, Line(it, 0.30), false) }
        val moves = all.mapIndexed { i, (m, ln, _) ->
            val cp = if (ln.mate != null) (if (ln.mate > 0) 100000 - ln.mate else -100000 + (-ln.mate)) else ((ln.win - 0.5) * 1000).toInt()
            MoveAnalysis(i + 1, m, Notation.moveToString(m), Notation.describe(pos.board, m), if (m == Move.PASS) null else Move.from(m), if (m == Move.PASS) null else Move.to(m),
                cp, ln.mate, ln.win, 14, 18, 5000, ln.pv ?: listOf(m), listOf(Notation.describe(pos.board, m)), null, MoveGen.givesCheck(pos, m), m != Move.PASS && pos.board[Move.to(m)] != 0)
        }
        return AnalysisSnapshot(9, pos.toFen(), pos.stateKey(), pos.side, moves, 14, 18, 200000, 100000, 800, true, false, null, "fake", moves.size, moves.size)
    }

    private val start = Position.initial()
    private val best = Notation.parseMove("02-83")     // Cho horse
    private val quiet = Notation.parseMove("01-91")    // Cho chariot up one (row digits count from Han's side: 0 = Cho's back rank)

    @Test fun equalWinRateIsBestAndTenPointLossIsAMistake() {
        val same = CoachEvaluator.evaluate(start, quiet, snap(start, listOf(Line(best, 0.60), Line(quiet, 0.60))), Board.CHO, 0)!!
        assertEquals(CoachGrade.BEST, same.grade)
        assertEquals(0.0, same.winRateLoss, 1e-9)
        assertFalse(same.playedIsBest)

        val played = CoachEvaluator.evaluate(start, best, snap(start, listOf(Line(best, 0.60))), Board.CHO, 0)!!
        assertEquals(CoachGrade.BEST, played.grade); assertTrue(played.playedIsBest); assertEquals("엔진 최선수입니다", played.headline)

        val mistake = CoachEvaluator.evaluate(start, quiet, snap(start, listOf(Line(best, 0.60), Line(quiet, 0.50))), Board.CHO, 0)!!
        assertEquals(CoachGrade.MISTAKE, mistake.grade)
        assertEquals(0.10, mistake.winRateLoss, 1e-9)
        assertEquals(0.60, mistake.bestWinRate!!, 1e-9); assertEquals(0.50, mistake.playedWinRate!!, 1e-9)
        assertEquals(best, mistake.bestMove); assertEquals(quiet, mistake.playedMove); assertEquals(14, mistake.depth)
    }

    @Test fun thresholdsAreOrderedAndCentralised() {
        assertEquals(CoachGrade.BEST, CoachThresholds.gradeForLoss(0.0, false))
        assertEquals(CoachGrade.EXCELLENT, CoachThresholds.gradeForLoss(0.008, false))
        assertEquals(CoachGrade.GOOD, CoachThresholds.gradeForLoss(0.02, false))
        assertEquals(CoachGrade.OK, CoachThresholds.gradeForLoss(0.04, false))
        assertEquals(CoachGrade.INACCURACY, CoachThresholds.gradeForLoss(0.06, false))
        assertEquals(CoachGrade.MISTAKE, CoachThresholds.gradeForLoss(0.10, false))
        assertEquals(CoachGrade.BLUNDER, CoachThresholds.gradeForLoss(0.20, false))
        assertEquals(CoachGrade.BEST, CoachThresholds.gradeForLoss(0.30, playedIsBest = true))
    }

    @Test fun winRatesAreAlwaysFromTheHumansPerspective() {
        // Human plays Han: the reviewed position has Han to move, so the engine's side-to-move rates ARE Han's rates.
        val afterCho = start.copy().also { it.makeMove(best) }
        val hanBest = Notation.parseMove("18-37"); val hanPlayed = Notation.parseMove("19-29")
        val han = CoachEvaluator.evaluate(afterCho, hanPlayed, snap(afterCho, listOf(Line(hanBest, 0.58), Line(hanPlayed, 0.52))), Board.HAN, 1)!!
        assertEquals(0.58, han.bestWinRate!!, 1e-9); assertEquals(0.52, han.playedWinRate!!, 1e-9)
        assertEquals(CoachGrade.INACCURACY, han.grade)     // 6 %p -> 부정확
        // Same absolute position, but if the reviewed side were Cho the rates would have to be mirrored — never taken as-is.
        val mirrored = CoachEvaluator.evaluate(afterCho, hanPlayed, snap(afterCho, listOf(Line(hanBest, 0.58), Line(hanPlayed, 0.52))), Board.CHO, 1)!!
        assertEquals(1.0 - 0.58, mirrored.bestWinRate!!, 1e-9); assertEquals(1.0 - 0.52, mirrored.playedWinRate!!, 1e-9)
        // Cho human at the start position: no mirroring either.
        val cho = CoachEvaluator.evaluate(start, quiet, snap(start, listOf(Line(best, 0.60), Line(quiet, 0.55))), Board.CHO, 0)!!
        assertEquals(0.55, cho.playedWinRate!!, 1e-9)
    }

    @Test fun mateScoresAreHandledSeparately() {
        val missed = CoachEvaluator.evaluate(start, quiet, snap(start, listOf(Line(best, 1.0, mate = 2), Line(quiet, 0.55))), Board.CHO, 0)!!
        assertEquals(CoachGrade.BLUNDER, missed.grade); assertEquals(2, missed.bestMateIn); assertTrue(missed.reasons.any { it.contains("외통") })
        val walkedIn = CoachEvaluator.evaluate(start, quiet, snap(start, listOf(Line(best, 0.55), Line(quiet, 0.0, mate = -2))), Board.CHO, 0)!!
        assertEquals(CoachGrade.BLUNDER, walkedIn.grade); assertEquals(-2, walkedIn.playedMateIn)
        val slowerMate = CoachEvaluator.evaluate(start, quiet, snap(start, listOf(Line(best, 1.0, mate = 1), Line(quiet, 1.0, mate = 3))), Board.CHO, 0)!!
        assertEquals(CoachGrade.BEST, slowerMate.grade)
        assertEquals("M3", com.example.janggiai.game.WinRateFormat.score(slowerMate.playedScoreCp, slowerMate.playedMateIn))
    }

    @Test fun unscoredPlayedMoveGivesNoVerdictInsteadOfAGuess() {
        val s = snap(start, listOf(Line(best, 0.6)))
        val withoutPlayed = s.copy(moves = s.moves.filter { it.move != quiet })
        assertNull(CoachEvaluator.evaluate(start, quiet, withoutPlayed, Board.CHO, 0))
    }

    @Test fun explanationsComeFromTheBoardAndThePv() {
        // Cho chariot on (4,0) faces Han's chariot on (4,8) along an empty row.
        val pos = Position.build(mapOf(
            Board.sq(8, 4) to Piece.make(Board.CHO, Piece.KING), Board.sq(1, 4) to Piece.make(Board.HAN, Piece.KING),
            Board.sq(4, 0) to Piece.make(Board.CHO, Piece.CHARIOT), Board.sq(4, 8) to Piece.make(Board.HAN, Piece.CHARIOT),
        ), Board.CHO)
        val capture = Move.encode(Board.sq(4, 0), Board.sq(4, 8))
        val blunder = Move.encode(Board.sq(4, 0), Board.sq(4, 4))
        val recapture = Move.encode(Board.sq(4, 8), Board.sq(4, 4))
        val c = CoachEvaluator.evaluate(pos, blunder, snap(pos, listOf(Line(capture, 0.95), Line(blunder, 0.40, pv = listOf(blunder, recapture)))), Board.CHO, 0)!!
        assertEquals(CoachGrade.BLUNDER, c.grade)
        assertTrue(c.reasons.joinToString(), c.reasons.any { it.contains("잡을 수 있는 車(13점)") })
        assertTrue(c.reasons.joinToString(), c.reasons.any { it.contains("다음 수에 상대가 車(13점)") && it.contains("피합니다") })
        // The capture itself is explained as a capture.
        val good = CoachEvaluator.evaluate(pos, capture, snap(pos, listOf(Line(capture, 0.95))), Board.CHO, 0)!!
        assertTrue(good.reasons.any { it.contains("車(13점)을(를) 잡습니다") })
    }

    @Test fun withoutAConcreteReasonTheTextStaysFactual() {
        val c = CoachEvaluator.evaluate(start, quiet, snap(start, listOf(Line(best, 0.55), Line(quiet, 0.51))), Board.CHO, 0)!!
        assertEquals(CoachGrade.OK, c.grade)
        assertTrue(c.reasons.isEmpty())
        assertTrue(c.summary, c.summary.contains("엔진 평가에서는") && c.summary.contains("4.0%p") && c.summary.contains("최선수는"))
    }

    @Test fun commentsSurviveEncodingAndOnlyHumanMovesAreReviewed() {
        val c = CoachEvaluator.evaluate(start, quiet, snap(start, listOf(Line(best, 0.60), Line(quiet, 0.50))), Board.CHO, 0)!!
        val back = CoachComment.decode(c.encode())
        assertEquals(c, back)
        val many = CoachComment.decodeAll(CoachComment.encodeAll(listOf(c, c.copy(ply = 2, grade = CoachGrade.GOOD))))
        assertEquals(setOf(0, 2), many.keys); assertEquals(CoachGrade.GOOD, many[2]!!.grade)
        assertNotNull(CoachComment.decodeAll(null)); assertTrue(CoachComment.decodeAll("").isEmpty())

        assertTrue(CoachPolicy.reviews(moverSide = Board.CHO, humanSide = Board.CHO, coachEnabled = true, move = quiet))
        assertFalse(CoachPolicy.reviews(moverSide = Board.HAN, humanSide = Board.CHO, coachEnabled = true, move = quiet))   // the AI's move
        assertFalse(CoachPolicy.reviews(Board.CHO, Board.CHO, false, quiet))
        assertFalse(CoachPolicy.reviews(Board.CHO, Board.CHO, true, Move.PASS))
    }
}
