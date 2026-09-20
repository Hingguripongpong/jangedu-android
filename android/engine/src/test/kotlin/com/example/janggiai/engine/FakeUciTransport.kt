package com.example.janggiai.engine

import com.example.janggiai.engine.uci.UciTransport
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Scripted Fairy-Stockfish stand-in: records every command and answers like the engine would
 * (uciok / readyok / one `info` per MultiPV line / bestmove).  `go infinite` keeps emitting
 * deepening infos until `stop`.
 */
class FakeUciTransport(
    private val bestMove: String = "b1c3",
    private val cp: Int = 17,
    /** PV heads for multipv 1..n; defaults to every legal move of the start position, best first, pass second. */
    private val pvHeads: List<String> = defaultPvHeads(bestMove),
) : UciTransport {
    companion object {
        fun defaultPvHeads(best: String): List<String> {
            val pos = com.example.janggiai.game.Position.initial()
            val all = com.example.janggiai.game.MoveGen.legalMoves(pos).map { com.example.janggiai.game.Uci.moveToUci(pos, it) }
            return listOf(best, "e2e2") + all.filter { it != best && it != "e2e2" }
        }
    }

    val sent = ArrayList<String>()
    private val out = LinkedBlockingQueue<String>()
    private var running = false
    private var multiPv = 1
    private var infinite = false
    private var infiniteDepth = 0

    @Synchronized override fun start(): Boolean { running = true; return true }

    @Synchronized override fun send(line: String) {
        sent += line
        when {
            line == "uci" -> {
                out += "id name Fairy-Stockfish 14 LB (fake)"
                out += "option name UCI_Variant type combo default chess var chess var janggi var janggicasual"
                out += "option name MultiPV type spin default 1 min 1 max 500"
                out += "uciok"
            }
            line == "isready" -> out += "readyok"
            line.startsWith("setoption name MultiPV value ") -> multiPv = line.substringAfterLast(' ').toInt()
            line.startsWith("go") -> {
                if (line.contains("infinite")) { infinite = true; infiniteDepth = 0; emitRound(1) }
                else { emitRound(7); out += "bestmove $bestMove" }
            }
            line == "stop" -> if (infinite) { infinite = false; out += "bestmove $bestMove" }
            line == "quit" -> running = false
        }
    }

    private fun emitRound(depth: Int) {
        for (i in 1..multiPv) {
            val pv = pvHeads.getOrElse(i - 1) { "a1a2" }
            out += "info depth $depth seldepth ${depth + 2} multipv $i score cp ${cp - i} nodes ${1000 * depth} nps 50000 time ${20 * depth} pv $pv e9e9"
        }
    }

    override fun readLine(timeoutMs: Int): String? {
        val line = out.poll(timeoutMs.toLong().coerceAtLeast(1), TimeUnit.MILLISECONDS)
        synchronized(this) { if (line == null && infinite && infiniteDepth < 40) { infiniteDepth++; emitRound(infiniteDepth) } }
        return line
    }

    @Synchronized override fun isRunning(): Boolean = running
    @Synchronized override fun shutdown(timeoutMs: Int): Boolean { running = false; return true }
    override fun engineInfo(): String = "Fairy-Stockfish 14 LB (fake)"
}
