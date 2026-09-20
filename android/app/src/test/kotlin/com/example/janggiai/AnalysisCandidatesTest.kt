package com.example.janggiai

import com.example.janggiai.engine.api.AnalysisSnapshot
import com.example.janggiai.engine.api.MoveAnalysis
import com.example.janggiai.game.AnalysisCandidates
import com.example.janggiai.game.Board
import com.example.janggiai.game.BoardCandidatesMode
import com.example.janggiai.game.Move
import com.example.janggiai.game.MoveGen
import com.example.janggiai.game.Notation
import com.example.janggiai.game.Position
import com.example.janggiai.game.WinRatePerspective
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The analysis UI model must carry every legal move — no Top-5 truncation anywhere. */
class AnalysisCandidatesTest {
    /** A snapshot of the start position: 31 real moves (no pass) scored best-first, plus the pass unscored. */
    private fun snapshot(): AnalysisSnapshot {
        val pos = Position.initial()
        val legal = MoveGen.legalMoves(pos).filter { it != Move.PASS }
        assertEquals(31, legal.size)
        val moves = legal.mapIndexed { i, m ->
            val cp = 50 - i * 4
            MoveAnalysis(i + 1, m, Notation.moveToString(m), Notation.describe(pos.board, m), Move.from(m), Move.to(m), cp, null, 0.5 + cp / 1000.0,
                12, 15, 1000, listOf(m), listOf(Notation.describe(pos.board, m)), null, false, false)
        } + MoveAnalysis(32, Move.PASS, "pass", "한수쉼", null, null, null, null, null, 0, 0, 0, emptyList(), emptyList(), null, false, false)
        return AnalysisSnapshot(1, pos.toFen(), pos.stateKey(), Board.CHO, moves, 12, 15, 100000, 100000, 1000, true, false, null, "fake", 32, 32)
    }

    @Test fun thirtyOneLegalMovesGiveThirtyOneScoredCandidatesInRankOrder() {
        val all = AnalysisCandidates.fromSnapshot(snapshot(), WinRatePerspective.SIDE_TO_MOVE)
        assertEquals(32, all.size)
        assertEquals(31, all.count { it.scored })
        assertEquals((1..32).toList(), all.map { it.rank })
        assertEquals(31, AnalysisCandidates.forList(all, showAll = true).count { it.scored })
        assertEquals(32, AnalysisCandidates.forList(all, showAll = true).size)   // the list shows everything, pass included
        assertEquals(5, AnalysisCandidates.forList(all, showAll = false).size)
        assertTrue(all.zipWithNext().all { (a, b) -> (a.winRate ?: 0.0) >= (b.winRate ?: 0.0) })
        assertEquals("D12", "D${all.first().depth}")
    }

    @Test fun boardModeNarrowsDrawingButNeverTheModel() {
        val all = AnalysisCandidates.fromSnapshot(snapshot(), WinRatePerspective.SIDE_TO_MOVE)
        val everything = AnalysisCandidates.forBoard(all, BoardCandidatesMode.ALL, selectedSquare = null, focusedMove = null)
        assertEquals(31, everything.size)                                   // every scored non-pass move gets a marker
        val top5 = AnalysisCandidates.forBoard(all, BoardCandidatesMode.TOP5, null, null)
        assertEquals((1..5).toList(), top5.map { it.rank })
        assertEquals(setOf(1, 2, 3, 4, 5), AnalysisCandidates.emphasised(everything, null).map { m -> all.first { it.move == m }.rank }.toSet())
        // selecting a piece keeps only its moves and emphasises all of them
        val horse = Notation.parseSquare("02")
        val mine = AnalysisCandidates.forBoard(all, BoardCandidatesMode.ALL, horse, null)
        assertTrue(mine.isNotEmpty() && mine.all { Move.from(it.move) == horse })
        assertEquals(mine.map { it.move }.toSet(), AnalysisCandidates.emphasised(mine, horse))
        // a focused move hides the markers (its PV is drawn instead)
        assertTrue(AnalysisCandidates.forBoard(all, BoardCandidatesMode.ALL, null, all.first().move).isEmpty())
        assertEquals("5", all[4].label(emphasised = false)); assertEquals("55.0%", all[0].label(emphasised = true))
    }

    @Test fun perspectiveFlipsRatesButKeepsRanks() {
        val han = AnalysisCandidates.fromSnapshot(snapshot(), WinRatePerspective.HAN)      // side to move is Cho
        val cho = AnalysisCandidates.fromSnapshot(snapshot(), WinRatePerspective.CHO)
        assertEquals(cho.map { it.rank }, han.map { it.rank })
        assertEquals(1.0 - cho[0].winRate!!, han[0].winRate!!, 1e-9)
        assertEquals(-cho[0].scoreCp!!, han[0].scoreCp!!)
    }
}
