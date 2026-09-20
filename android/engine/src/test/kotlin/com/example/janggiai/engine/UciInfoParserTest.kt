package com.example.janggiai.engine

import com.example.janggiai.engine.uci.UciInfoParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UciInfoParserTest {
    @Test fun parsesFullInfoLine() {
        val i = UciInfoParser.parse("info depth 9 seldepth 3 multipv 28 score mate -1 nodes 239922 nps 341769 tbhits 0 time 702 pv e2e2 e9e9")!!
        assertEquals(9, i.depth); assertEquals(3, i.seldepth); assertEquals(28, i.multipv)
        assertEquals("mate", i.scoreKind); assertEquals(-1, i.scoreValue); assertEquals(-1, i.mateIn)
        assertEquals(239922L, i.nodes); assertEquals(341769L, i.nps); assertEquals(702L, i.timeMs)
        assertEquals(listOf("e2e2", "e9e9"), i.pv)
        assertEquals(-99_999, i.orderingScore)                   // mate -1 -> -(100000 - 1), same scale as the Python adapter
    }

    @Test fun parsesCpWithBoundAndWdl() {
        val i = UciInfoParser.parse("info depth 12 seldepth 18 multipv 1 score cp 35 lowerbound wdl 520 0 480 nodes 5 nps 1 time 3 pv b1c3 h10g8")!!
        assertEquals(35, i.scoreValue); assertEquals("lowerbound", i.bound); assertEquals(Triple(520, 0, 480), i.wdl)
        assertEquals(35, i.orderingScore); assertNull(i.mateIn)
    }

    @Test fun ignoresNonScoreLines() {
        assertNull(UciInfoParser.parse("info string classical evaluation enabled"))
        assertNull(UciInfoParser.parse("info depth 3 currmove b1c3 currmovenumber 1"))
        assertNull(UciInfoParser.parse("bestmove b1c3"))
        assertNull(UciInfoParser.parse("info depth 3 score cp 1"))    // no pv
    }
}
