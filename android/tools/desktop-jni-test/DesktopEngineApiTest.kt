// The shipped Kotlin engine (FairyStockfishEngine + JniUciTransport) against the shipped native
// library, on the development machine.  Same checks as FairyStockfishInstrumentedTest.
import com.example.janggiai.engine.api.AnalysisLimits
import com.example.janggiai.engine.api.EnginePosition
import com.example.janggiai.engine.api.SearchLimits
import com.example.janggiai.engine.fsf.FairyStockfishEngine
import com.example.janggiai.engine.fsf.FairyStockfishNative
import com.example.janggiai.engine.fsf.JniUciTransport
import com.example.janggiai.game.BikjangRule
import com.example.janggiai.game.MoveGen
import com.example.janggiai.game.Position
import com.example.janggiai.game.RuleConfig
import com.example.janggiai.game.Uci
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

fun ok(cond: Boolean, msg: String) { if (!cond) error("CHECK FAILED: $msg"); println("  ok  $msg") }

fun main() = runBlocking {
    val engine = FairyStockfishEngine(JniUciTransport())
    engine.newGame()
    ok(engine.name.startsWith("Fairy-Stockfish"), "engine name: ${engine.name}")

    val forced = EnginePosition(rules = RuleConfig(bikjang = BikjangRule.FORCED))
    val snaps = withTimeout(30_000) { engine.analyze(forced, AnalysisLimits(timeMs = 1500), 42).toList() }
    val last = snaps.last()
    ok(last.final && !last.aborted && last.error == null, "analysis final (${snaps.size} snapshots, ${last.timeMs} ms)")
    ok(last.legalMoveCount == 32 && last.moves.size == 32 && last.moves.all { it.scored }, "all 32 legal moves scored")
    ok(last.depth >= 4, "completed depth for every move = ${last.depth} (max ${last.maxDepth}, ${last.nodes} nodes, ${last.nps} nps)")
    ok(engine.sentCommands.contains("setoption name MultiPV value 32"), "analysis sent MultiPV 32")
    val pass = last.moves.first { it.moveText == "pass" }
    ok(pass.mateIn != null && pass.mateIn!! < 0 && pass.winRate == 0.0, "pass = points loss (mate ${pass.mateIn})")
    println("  top 5: " + last.moves.take(5).joinToString(" | ") { "${it.notation} ${"%.1f".format((it.winRate ?: 0.0) * 100)}% cp=${it.scoreCp} d${it.depth}" })
    ok(last.best!!.pv.size >= 2 && last.best!!.pvText.size == last.best!!.pv.size, "best PV: ${last.best!!.pvText.joinToString(" ")}")

    val mv = withTimeout(30_000) { engine.bestMove(EnginePosition(), SearchLimits(timeMs = 800)) }
    ok(mv.move != null && MoveGen.legalMoves(Position.initial()).contains(mv.move!!), "play-mode best move ${mv.notation} depth ${mv.depth} cp ${mv.scoreCp} wr ${mv.winRate}")
    ok(mv.depth >= 6, "play depth >= 6")
    ok(engine.sentCommands.contains("setoption name MultiPV value 1"), "play sent MultiPV 1")

    val weak = withTimeout(30_000) { engine.bestMove(EnginePosition(), SearchLimits(timeMs = 300, skillLevel = 3)) }
    ok(weak.move != null && engine.sentCommands.contains("setoption name Skill Level value 3"), "skill level applied for easy difficulty")

    val job = launch { engine.analyze(EnginePosition(), AnalysisLimits(timeMs = null), 1).toList() }
    delay(500)
    val t0 = System.currentTimeMillis()
    job.cancel(); job.join()
    val after = withTimeout(30_000) { engine.bestMove(EnginePosition(), SearchLimits(timeMs = 200)) }
    ok(after.move != null && System.currentTimeMillis() - t0 < 5000, "cancel of infinite analysis released the engine in ${System.currentTimeMillis() - t0} ms")

    val bad = runCatching { engine.bestMove(EnginePosition(startFen = "this/is/not/a/fen w - - 0 1"), SearchLimits(timeMs = 100)) }
    ok(bad.isFailure, "invalid FEN rejected before reaching the engine: ${bad.exceptionOrNull()?.message}")
    ok(engine.bestMove(EnginePosition(), SearchLimits(timeMs = 200)).move != null, "engine still fine afterwards")

    // legal moves single-source-of-truth: Kotlin vs engine `go perft 1`
    engine.close()
    ok(FairyStockfishNative.nativeStart(), "raw restart for perft divide")
    fun readUntil(p: (String) -> Boolean): List<String> { val out = ArrayList<String>(); val dl = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < dl) { val l = FairyStockfishNative.nativeReadLine(100) ?: continue; out += l; if (p(l)) return out }; error("timeout") }
    FairyStockfishNative.nativeSend("uci"); readUntil { it == "uciok" }
    FairyStockfishNative.nativeSend("setoption name UCI_Variant value janggi")
    for (fen in listOf(Position.START_FEN, "r1ba1ab1r/4k1n2/1c2n2c1/p3p1p1p/2p6/6P2/P1P1P3P/1C2NC3/4K4/RNBA1AB1R w - - 0 1", "9/4k4/9/8p/9/9/P8/9/4K4/R8 w - - 0 1")) {
        FairyStockfishNative.nativeSend("position fen $fen"); FairyStockfishNative.nativeSend("go perft 1")
        val lines = readUntil { it.startsWith("Nodes searched") }
        val engineMoves = lines.filter { it.contains(": ") && !it.startsWith("Nodes") }.map { it.substringBefore(":").trim() }.toSet()
        val pos = RuleConfig.TRADITIONAL.apply(Position.fromFen(fen))
        val ours = MoveGen.legalMoves(pos).map { Uci.moveToUci(pos, it) }.toSet()
        ok(engineMoves == ours, "legal moves identical to engine perft divide (${ours.size} moves) for ${fen.substringBefore(' ')}")
    }
    FairyStockfishNative.nativeShutdown(5000)
    println("ALL DESKTOP ENGINE-API CHECKS PASSED")
}
