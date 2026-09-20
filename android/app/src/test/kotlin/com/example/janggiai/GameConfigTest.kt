package com.example.janggiai

import com.example.janggiai.game.Board
import com.example.janggiai.game.GameConfig
import com.example.janggiai.game.GameSession
import com.example.janggiai.game.Piece
import com.example.janggiai.game.Position
import com.example.janggiai.game.RuleConfig
import com.example.janggiai.game.SetupChoice
import com.example.janggiai.game.SideChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Opening setup + side selection: the rules engine's SETUPS are the single source of truth. */
class GameConfigTest {
    private fun types(pos: Position, row: Int, cols: IntArray): List<Int> = cols.map { Piece.typeOf(pos.board[Board.sq(row, it)]) }
    private val M = Piece.HORSE; private val S = Piece.ELEPHANT

    @Test fun fourStandardSetupsPlacePiecesFromTheOwnersLeft() {
        // Cho sits on row 9 and its left is file 0; Han sits on row 0 and its left is file 8.
        val expect = mapOf("마상상마" to listOf(M, S, S, M), "상마마상" to listOf(S, M, M, S), "마상마상" to listOf(M, S, M, S), "상마상마" to listOf(S, M, S, M))
        for ((name, order) in expect) {
            val pos = Position.initial(choSetup = name, hanSetup = name)
            assertEquals(name, order, types(pos, 9, intArrayOf(1, 2, 6, 7)))          // Cho: left→right = files 1,2,6,7
            assertEquals(name, order, types(pos, 0, intArrayOf(7, 6, 2, 1)))          // Han: its left is file 8, so read files 7,6,2,1
        }
        // Every SetupChoice name exists in the engine's table — the UI never invents a layout.
        for (c in SetupChoice.STANDARD) assertTrue(c.setupName, Position.SETUPS.containsKey(c.setupName!!))
        assertEquals(4, SetupChoice.STANDARD.size)
    }

    @Test fun humanAndAiSetupsAreIndependentAndFollowTheChosenSide() {
        val cho = GameConfig(SideChoice.CHO, SetupChoice.MSMS, SetupChoice.SMSM).resolve()
        assertEquals("마상마상", cho.choSetup); assertEquals("상마상마", cho.hanSetup); assertEquals(Board.HAN, cho.aiSide)
        val han = GameConfig(SideChoice.HAN, SetupChoice.SMMS, SetupChoice.MSMS).resolve()
        assertEquals(Board.HAN, han.humanSide); assertEquals(Board.CHO, han.aiSide)
        assertEquals("마상마상", han.choSetup)    // the AI (Cho) got the AI setup
        assertEquals("상마마상", han.hanSetup)    // the human (Han) got the human setup
        val session = han.newSession(RuleConfig.DEFAULT)
        assertEquals(listOf(S, M, M, S), types(session.position, 0, intArrayOf(7, 6, 2, 1)))   // Han's own left→right
        assertEquals(listOf(M, S, M, S), types(session.position, 9, intArrayOf(1, 2, 6, 7)))
        assertEquals(Board.CHO, session.position.side)                                    // Cho moves first: the AI opens
    }

    @Test fun randomResolvesToStandardSetupsAndBothSidesOnly() {
        val rnd = Random(42)
        val setups = HashSet<String>(); val sides = HashSet<Int>()
        repeat(300) {
            val r = GameConfig(SideChoice.RANDOM, SetupChoice.RANDOM, SetupChoice.RANDOM).resolve(rnd)
            assertTrue(r.humanSetup, Position.SETUPS.containsKey(r.humanSetup)); assertTrue(r.aiSetup, Position.SETUPS.containsKey(r.aiSetup))
            assertTrue(r.humanSide == Board.CHO || r.humanSide == Board.HAN)
            assertNotEquals(r.humanSide, r.aiSide)
            setups += r.humanSetup; setups += r.aiSetup; sides += r.humanSide
        }
        assertEquals(Position.SETUPS.keys, setups)
        assertEquals(setOf(Board.CHO, Board.HAN), sides)
    }

    @Test fun setupSurvivesSaveAndRestoreThroughTheStartFen() {
        val resolved = GameConfig(SideChoice.HAN, SetupChoice.SMSM, SetupChoice.MSMS).resolve()
        val session = resolved.newSession(RuleConfig.DEFAULT).play(com.example.janggiai.game.Notation.parseMove("02-83"))
        // What SavedState / a record keeps: start FEN + moves. Restoring must reproduce the same boards and side.
        val restored = GameSession(startFen = session.startFen, moves = session.moves, viewIndex = session.moves.size, rules = RuleConfig.DEFAULT)
        assertEquals(session.position.toFen(), restored.position.toFen())
        assertEquals(listOf(S, M, S, M), types(restored.first().position, 0, intArrayOf(7, 6, 2, 1)))
        assertEquals(listOf(M, S, M, S), types(restored.first().position, 9, intArrayOf(1, 2, 6, 7)))
        assertNotNull(SetupChoice.ofSetupName(resolved.humanSetup))
        assertEquals(SetupChoice.SMSM, SetupChoice.ofSetupName("상마상마"))
        assertEquals(null, SetupChoice.ofSetupName("garbage"))
    }
}
