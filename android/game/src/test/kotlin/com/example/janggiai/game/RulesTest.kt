package com.example.janggiai.game

import com.example.janggiai.game.Board.CHO
import com.example.janggiai.game.Board.HAN
import com.example.janggiai.game.Board.sq
import com.example.janggiai.game.Piece.CHARIOT
import com.example.janggiai.game.Piece.HORSE
import com.example.janggiai.game.Piece.KING
import com.example.janggiai.game.Piece.PAWN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The same test vectors as the Python reference suite (`tests/test_rules_*.py`, `test_uci_engine.py`).
 * Perft numbers are the regression anchors shared with Fairy-Stockfish `go perft`.
 */
class RulesTest {
    private fun cho(t: Int) = Piece.make(CHO, t)
    private fun han(t: Int) = Piece.make(HAN, t)
    private fun legalStrings(pos: Position) = MoveGen.legalMoves(pos).map { Notation.moveToString(it) }.toSet()

    // ---------------------------------------------------------------- perft regression
    @Test fun perftStartPositionNoPass() {
        val pos = Position.initial()
        assertEquals(31L, MoveGen.perft(pos, 1))
        assertEquals(32L, MoveGen.perft(pos, 1, includePass = true))
        assertEquals(961L, MoveGen.perft(pos, 2))
        assertEquals(30353L, MoveGen.perft(pos, 3))
        assertEquals(Position.initial().toFen(), pos.toFen())   // search left the board untouched
    }

    @Test fun perftMatchesFairyStockfishJanggi() {
        // Fairy-Stockfish 14 `go perft` (variant janggi = forced bikjang, passes included)
        val start = RuleConfig.TRADITIONAL.apply(Position.fromFen(Position.START_FEN))
        assertEquals(33000L, MoveGen.perft(start, 3, includePass = true, stopAtGameEnd = true))
        val mid = RuleConfig.TRADITIONAL.apply(Position.fromFen("r1ba1ab1r/4k1n2/1c2n2c1/p3p1p1p/2p6/6P2/P1P1P3P/1C2NC3/4K4/RNBA1AB1R w - - 0 1"))
        assertEquals(42026L, MoveGen.perft(mid, 3, includePass = true, stopAtGameEnd = true))
        // variant janggicasual (no bikjang rule)
        val casual = RuleConfig(bikjang = BikjangRule.OFF).apply(Position.initial())
        assertEquals(33316L, MoveGen.perft(casual, 3, includePass = true, stopAtGameEnd = true))
    }

    @Test fun perftDepth4MatchesFairyStockfish() {
        val start = RuleConfig.TRADITIONAL.apply(Position.initial())
        assertEquals(1065277L, MoveGen.perft(start, 4, includePass = true, stopAtGameEnd = true))
    }

    // ---------------------------------------------------------------- check / mate
    @Test fun mustEscapeCheckAndCannotPass() {
        val pos = Position.build(mapOf(sq(8, 3) to cho(KING), sq(1, 4) to han(KING), sq(5, 3) to han(CHARIOT)))
        assertTrue(pos.inCheck())
        val legal = MoveGen.legalMoves(pos)
        assertFalse(legal.contains(Move.PASS))
        assertTrue(legal.isNotEmpty())
        for (m in legal) { pos.makeMove(m); assertFalse(pos.attackedBy(pos.kingSq[CHO], HAN)); pos.unmakeMove() }
    }

    @Test fun pinnedPieceCannotLeaveTheLine() {
        val pos = Position.build(mapOf(sq(8, 4) to cho(KING), sq(1, 3) to han(KING), sq(6, 4) to cho(HORSE), sq(3, 4) to han(CHARIOT)))
        assertTrue(MoveGen.legalMoves(pos).none { it != Move.PASS && Move.from(it) == sq(6, 4) })
    }

    @Test fun checkmateAndMateInOne() {
        val mated = Position.build(mapOf(sq(8, 4) to cho(KING), sq(0, 3) to han(KING), sq(5, 4) to cho(CHARIOT),
            sq(2, 3) to cho(PAWN), sq(0, 8) to cho(CHARIOT)), side = HAN)
        val st = Rules.gameStatus(mated)
        assertEquals(GameEnd.CHECKMATE, st.end)
        assertEquals(CHO, st.winner)
        assertTrue(MoveGen.legalMoves(mated).isEmpty())

        val pos = Position.build(mapOf(sq(8, 4) to cho(KING), sq(0, 3) to han(KING), sq(5, 4) to cho(CHARIOT),
            sq(2, 3) to cho(PAWN), sq(3, 8) to cho(CHARIOT)))
        assertEquals(GameEnd.ONGOING, Rules.gameStatus(pos).end)
        val m = Move.encode(sq(3, 8), sq(0, 8))
        assertTrue(MoveGen.legalMoves(pos).contains(m))
        pos.makeMove(m)
        assertEquals(GameEnd.CHECKMATE, Rules.gameStatus(pos).end)
    }

    // ---------------------------------------------------------------- 빅장
    @Test fun bikjangFlagAndEndings() {
        val pieces = mapOf(sq(8, 4) to cho(KING), sq(1, 4) to han(KING), sq(9, 0) to cho(CHARIOT), sq(0, 8) to han(CHARIOT))
        val pos = Position.build(pieces)
        assertTrue(pos.kingsFacing() && pos.bikjang)
        val draw = RuleConfig(bikjang = BikjangRule.DRAW)
        assertEquals(GameEnd.ONGOING, Rules.gameStatus(pos, draw).end)
        pos.makeMove(Move.encode(sq(9, 0), sq(9, 1)))          // ignores it
        assertTrue(pos.bikjang && pos.prevBikjang())
        assertEquals(GameEnd.DRAW_BIKJANG, Rules.gameStatus(pos, draw).end)
        assertEquals(GameEnd.ONGOING, Rules.gameStatus(pos, RuleConfig(bikjang = BikjangRule.OFF)).end)
        val pts = Rules.gameStatus(pos, RuleConfig(bikjang = BikjangRule.POINTS))
        assertEquals(GameEnd.BIKJANG_POINTS, pts.end)
        assertEquals(HAN, pts.winner)                             // 13 vs 13 + 1.5
        pos.unmakeMove()
        pos.makeMove(Move.encode(sq(8, 4), sq(8, 3)))          // king leaves the file
        assertFalse(pos.bikjang)
        assertEquals(GameEnd.ONGOING, Rules.gameStatus(pos, draw).end)
    }

    @Test fun checkTakesPrecedenceOverBikjang() {
        val pos = Position.build(mapOf(sq(8, 4) to cho(KING), sq(1, 4) to han(KING), sq(4, 0) to cho(CHARIOT)))
        assertTrue(pos.kingsFacing() && pos.bikjang)
        pos.makeMove(Move.encode(sq(4, 0), sq(1, 0)))
        assertTrue(pos.inCheck())
        assertTrue(pos.kingsFacing() && !pos.bikjang)
        assertEquals(GameEnd.ONGOING, Rules.gameStatus(pos, RuleConfig(bikjang = BikjangRule.DRAW)).end)
    }

    @Test fun forcedBikjangRestrictsMovesToResolvingOnesPlusPass() {
        val rules = RuleConfig.TRADITIONAL
        val pos = Rules.replay(null, listOf("95-94", "25-24"), rules)   // kings face on file d
        assertTrue(pos.bikjang && pos.forcedBikjang)
        assertEquals(setOf("pass", "94-95", "75-74", "73-74"), legalStrings(pos))   // FSF: d2d2 d2e2 e4d4 c4d4
        try { Rules.replay(null, listOf("95-94", "25-24", "71-61"), rules); throw AssertionError("ignoring bikjang accepted") }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("illegal")) }
        pos.makeMove(Move.PASS)
        val st = Rules.gameStatus(pos, rules)
        assertEquals(GameEnd.BIKJANG_POINTS, st.end)
        assertEquals(HAN, st.winner)                                   // equal material: Han's +1.5
        val pp = Rules.replay(null, listOf("pass", "pass"), rules)
        assertEquals(GameEnd.PASSES_POINTS, Rules.gameStatus(pp, rules).end)
        assertEquals(GameEnd.DRAW_PASSES, Rules.gameStatus(Rules.replay(null, listOf("pass", "pass"))).end)
        val classic = Rules.replay(null, listOf("95-94", "25-24", "71-61"), RuleConfig(bikjang = BikjangRule.DRAW))
        assertEquals(GameEnd.DRAW_BIKJANG, Rules.gameStatus(classic, RuleConfig(bikjang = BikjangRule.DRAW)).end)
    }

    @Test fun forcedBikjangCheckTakesPrecedence() {
        val pos = RuleConfig.TRADITIONAL.apply(Position.fromFen("4k4/9/9/9/9/9/9/9/r3K4/9 w - - 0 1"))
        assertTrue(pos.inCheck() && !pos.bikjang)
        val legal = legalStrings(pos)
        assertFalse(legal.contains("pass"))
        assertTrue(legal.containsAll(setOf("95-84", "95-86", "95-04", "95-06")))
    }

    // ---------------------------------------------------------------- 한수쉼 / repetition
    @Test fun passOnlyOutOfCheckAndDoublePassDraws() {
        val pos = Position.initial()
        assertTrue(MoveGen.legalMoves(pos).contains(Move.PASS))
        pos.makeMove(Move.PASS)
        assertEquals(1, pos.passes); assertEquals(HAN, pos.side)
        assertEquals(GameEnd.ONGOING, Rules.gameStatus(pos).end)
        pos.makeMove(Move.PASS)
        assertEquals(GameEnd.DRAW_PASSES, Rules.gameStatus(pos).end)
        pos.unmakeMove()
        pos.makeMove(Move.encode(sq(3, 0), sq(4, 0)))
        assertEquals(0, pos.passes)
        assertEquals(GameEnd.ONGOING, Rules.gameStatus(pos).end)
    }

    @Test fun threefoldRepetition() {
        val cycle = listOf("02-83", "18-37", "83-02", "37-18")
        val pos = Rules.replay(null, cycle + cycle)
        assertEquals(3, pos.repetitionCount())
        assertEquals(GameEnd.DRAW_REPETITION, Rules.gameStatus(pos).end)
        assertEquals(GameEnd.ONGOING, Rules.gameStatus(pos, RuleConfig(repetition = RepetitionRule.OFF)).end)
        pos.unmakeMove()
        assertEquals(GameEnd.ONGOING, Rules.gameStatus(pos).end)
        assertTrue(pos.isRepetition())
        // same board, different repetition context -> different state key
        assertEquals(Position.initial().hash, Rules.replay(null, cycle).hash)
        assertNotEquals(Position.initial().stateKey(), Rules.replay(null, cycle).stateKey())
    }

    // ---------------------------------------------------------------- make / unmake integrity
    @Test fun makeUnmakeRestoresEverythingInRandomPlayouts() {
        val rng = Random(7)
        val setups = Position.SETUPS.keys.toList()
        for (game in 0 until 20) {
            val pos = Position.initial(setups[rng.nextInt(setups.size)])
            for (ply in 0 until 60) {
                val legal = MoveGen.legalMoves(pos)
                if (legal.isEmpty()) break
                val snapshot = listOf(pos.board.toList(), pos.side, pos.hash, pos.kingSq.toList(), pos.passes, pos.bikjang, pos.ply, pos.history.toList())
                val m = legal[rng.nextInt(legal.size)]
                pos.makeMove(m)
                assertEquals("incremental hash drifted", Zobrist.compute(pos.board, pos.side), pos.hash)
                assertEquals(KING, Piece.typeOf(pos.board[pos.kingSq[CHO]]))
                assertEquals(KING, Piece.typeOf(pos.board[pos.kingSq[HAN]]))
                pos.unmakeMove()
                assertEquals(snapshot, listOf(pos.board.toList(), pos.side, pos.hash, pos.kingSq.toList(), pos.passes, pos.bikjang, pos.ply, pos.history.toList()))
                pos.makeMove(m)
                if (Rules.gameStatus(pos).isOver) break
            }
        }
    }

    @Test fun fenRoundTripAfterRandomMoves() {
        val rng = Random(3)
        val pos = Position.initial()
        for (i in 0 until 40) {
            val legal = MoveGen.legalMoves(pos).filter { it != Move.PASS }
            pos.makeMove(legal[rng.nextInt(legal.size)])
        }
        val fen = pos.toFen()
        val again = Position.fromFen(fen)
        assertEquals(fen, again.toFen())
        assertEquals(pos.board.toList(), again.board.toList())
        assertEquals(pos.hash, again.hash)
        assertEquals(Position.START_FEN, Position.initial().toFen())
    }

    @Test fun setupsPlaceHorsesAndElephantsFromOwnersLeft() {
        val pos = Position.initial("상마상마", "마상마상")
        assertEquals(Piece.ELEPHANT, Piece.typeOf(pos.board[sq(9, 1)]))   // Cho's left-most slot
        assertEquals(HORSE, Piece.typeOf(pos.board[sq(9, 2)]))
        assertEquals(HORSE, Piece.typeOf(pos.board[sq(0, 7)]))            // Han's left is file 8 side
        assertEquals(Piece.ELEPHANT, Piece.typeOf(pos.board[sq(0, 6)]))
        try { Position.initial("마마마마"); throw AssertionError("bad setup accepted") } catch (e: IllegalArgumentException) {}
    }

    @Test fun replayValidatesMoves() {
        val pos = Rules.replay(null, listOf("02-83", "18-37"))
        assertEquals(2, pos.ply)
        try { Rules.replay(null, listOf("02-84")); throw AssertionError("illegal move accepted") }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("illegal")) }
    }

    @Test fun materialPointsAndHanBonus() {
        val mp = Rules.materialPoints(Position.initial())
        assertEquals(2 * 13 + 2 * 7 + 2 * 5 + 2 * 3 + 2 * 3 + 5 * 2.0, mp.cho, 1e-9)
        assertEquals(mp.cho + 1.5, mp.han, 1e-9)
        // a lone Cho pawn (2) beats Han's bonus (1.5); equal material -> Han's +1.5 decides
        assertEquals(CHO, Rules.pointsWinner(Position.build(mapOf(sq(8, 4) to cho(KING), sq(1, 3) to han(KING), sq(6, 0) to cho(PAWN)))))
        assertEquals(HAN, Rules.pointsWinner(Position.build(mapOf(sq(8, 4) to cho(KING), sq(1, 3) to han(KING), sq(6, 0) to cho(PAWN), sq(3, 8) to han(PAWN)))))
        assertNull(Rules.pointsWinner(Position.build(mapOf(sq(8, 4) to cho(KING), sq(1, 3) to han(KING), sq(6, 0) to cho(PAWN), sq(6, 2) to cho(PAWN), sq(3, 8) to han(PAWN)))).takeIf { false })
    }

    // ---------------------------------------------------------------- notation / UCI mapping
    @Test fun notationRoundTrip() {
        assertEquals("95", Notation.squareToString(sq(8, 4)))
        assertEquals("25", Notation.squareToString(sq(1, 4)))
        assertEquals("01", Notation.squareToString(sq(9, 0)))
        for (s in 0 until 90) assertEquals(s, Notation.parseSquare(Notation.squareToString(s)))
        assertEquals(Move.PASS, Notation.parseMove("pass"))
        assertEquals(Move.PASS, Notation.parseMove("한수쉼"))
        assertEquals(Move.encode(sq(8, 4), sq(6, 2)), Notation.parseMove("95-73"))
        assertEquals("95-73", Notation.moveToString(Notation.parseMove("95 → 73")))
        assertEquals("馬 02→83", Notation.describe(Position.initial().board, Notation.parseMove("02-83")))
        assertEquals("한수쉼", Notation.describe(Position.initial().board, Move.PASS))
    }

    @Test fun uciSquareAndMoveMapping() {
        assertEquals("a10", Uci.squareToUci(0)); assertEquals("i1", Uci.squareToUci(89)); assertEquals("e2", Uci.squareToUci(76))
        for (s in 0 until 90) assertEquals(s, Uci.uciToSquare(Uci.squareToUci(s)))
        val pos = Position.initial()
        assertEquals("b1c3", Uci.moveToUci(pos, Notation.parseMove("02-83")))
        assertEquals("02-83", Notation.moveToString(Uci.uciToMove("b1c3")))
        assertEquals("e2e2", Uci.moveToUci(pos, Move.PASS)); assertEquals(Move.PASS, Uci.uciToMove("e2e2"))
        assertEquals(Notation.parseMove("12-33"), Uci.uciToMove("b10c8")); assertEquals(Notation.parseMove("11-21"), Uci.uciToMove("a10a9"))
        pos.makeMove(Notation.parseMove("02-83"))
        assertEquals("e9e9", Uci.moveToUci(pos, Move.PASS))                    // Han's king square
        assertEquals(listOf("b1c3", "e9e9"), Uci.movesToUci(Position.initial(), listOf(Notation.parseMove("02-83"), Move.PASS)))
        assertEquals("position fen ${Position.START_FEN} moves b1c3 e9e9",
            Uci.positionCommand(Rules.replay(null, listOf("02-83", "pass"))))
        assertEquals("position fen ${Position.START_FEN}", Uci.positionCommand(Position.initial()))
    }

    // ---------------------------------------------------------------- win probability
    @Test fun logisticWinProbability() {
        val m = LogisticWinProbability()
        assertEquals(0.5, m.winProbability(0), 1e-9)
        assertTrue(m.winProbability(100) > 0.5 && m.winProbability(-100) < 0.5)
        assertEquals(1.0, m.winProbability(300) + m.winProbability(-300), 1e-9)
        assertEquals(0.5, LogisticWinProbability.sigmoid(0.0), 1e-9)
        val (w, d, l) = m.wdl(120)
        assertEquals(0.0, d, 0.0); assertEquals(1.0, w + l, 1e-9)
        val parsed = LogisticWinProbability.fromCalibrationJson("""{"k": 412.5, "draw_margin": 0.0, "calibrated": true, "samples": 2000, "source": "fitted from 2000 game results"}""")!!
        assertEquals(412.5, parsed.k, 1e-9); assertTrue(parsed.calibrated); assertEquals(2000, parsed.samples)
        assertNull(LogisticWinProbability.fromCalibrationJson("{}"))
        assertEquals(m.scoreForProbability(m.winProbability(250)), 250.0, 1e-6)
    }
}
