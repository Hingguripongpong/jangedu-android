// Host-side smoke test of the JNI engine host with the real Fairy-Stockfish.
// Build: tools/desktop-jni-test/run.sh   (kotlinc + java, no Android SDK needed)
import com.example.janggiai.engine.fsf.FairyStockfishNative as N

fun readUntil(timeoutMs: Long = 20_000, pred: (String) -> Boolean): List<String> {
    val out = ArrayList<String>()
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        val line = N.nativeReadLine(200) ?: continue
        out += line
        if (pred(line)) return out
    }
    error("timeout waiting; got ${out.size} lines, last=${out.lastOrNull()}")
}

fun check(cond: Boolean, msg: String) { if (!cond) error("CHECK FAILED: $msg"); println("  ok  $msg") }

fun main() {
    N.ensureLoaded()
    check(N.nativeStart(), "engine thread started")
    println("  info: ${N.nativeEngineInfo()}")

    // 1. uci handshake, janggi variant advertised
    N.nativeSend("uci")
    val uci = readUntil { it == "uciok" }
    check(uci.any { it.startsWith("id name Fairy-Stockfish") }, "id name = ${uci.first { it.startsWith("id name") }}")
    val variantLine = uci.first { it.contains("UCI_Variant") }
    check(variantLine.contains(" janggi ") && variantLine.contains("janggicasual"), "janggi + janggicasual variants advertised")

    // 2. variant switch + perft regression (must equal Python/FSF reference 33,000)
    N.nativeSend("setoption name UCI_Variant value janggi")
    N.nativeSend("setoption name Threads value 1")
    N.nativeSend("setoption name Hash value 16")
    N.nativeSend("isready"); readUntil { it == "readyok" }
    N.nativeSend("position startpos")
    N.nativeSend("go perft 3")
    val perft = readUntil { it.startsWith("Nodes searched") }
    check(perft.last() == "Nodes searched: 33000", "perft(3) janggi = 33000 (${perft.last()})")
    N.nativeSend("setoption name UCI_Variant value janggicasual")
    N.nativeSend("position startpos"); N.nativeSend("go perft 3")
    check(readUntil { it.startsWith("Nodes searched") }.last() == "Nodes searched: 33316", "perft(3) janggicasual = 33316")
    N.nativeSend("setoption name UCI_Variant value janggi")
    N.nativeSend("position fen r1ba1ab1r/4k1n2/1c2n2c1/p3p1p1p/2p6/6P2/P1P1P3P/1C2NC3/4K4/RNBA1AB1R w - - 0 1")
    N.nativeSend("go perft 3")
    check(readUntil { it.startsWith("Nodes searched") }.last() == "Nodes searched: 42026", "perft(3) midgame = 42026")

    // 3. analysis mode: MultiPV = 32 (all legal moves of the start position)
    N.nativeSend("setoption name MultiPV value 32")
    N.nativeSend("position startpos")
    N.nativeSend("go movetime 700")
    val lines = readUntil { it.startsWith("bestmove") }
    val pvIdx = lines.filter { it.startsWith("info") && " multipv " in it && " pv " in it }
        .map { it.split(" ").let { t -> t[t.indexOf("multipv") + 1].toInt() } }.toSet()
    check(pvIdx.size == 32 && pvIdx.max() == 32, "analysis produced ${pvIdx.size} distinct multipv lines")
    val passLine = lines.filter { it.startsWith("info") && " pv e2e2" in it }.lastOrNull()
    check(passLine != null && " score mate -" in passLine, "pass (e2e2) at start is a points loss under janggi rule: $passLine")

    // 4. play mode: MultiPV = 1, bestmove is a legal 4/5-char move
    N.nativeSend("setoption name MultiPV value 1")
    N.nativeSend("position startpos moves b1c3")
    N.nativeSend("go movetime 500")
    val play = readUntil { it.startsWith("bestmove") }
    val playIdx = play.filter { it.startsWith("info") && " multipv " in it }.map { it.split(" ").let { t -> t[t.indexOf("multipv") + 1].toInt() } }.toSet()
    check(playIdx == setOf(1), "play mode used only multipv 1")
    val best = play.last().split(" ")[1]
    check(Regex("[a-i](10|[1-9])[a-i](10|[1-9])").matches(best), "bestmove $best")
    check(play.any { " depth " in it && it.split(" ").let { t -> t[t.indexOf("depth") + 1].toInt() >= 6 } }, "reached depth >= 6 in 500 ms")

    // 5. stop an infinite search quickly
    N.nativeSend("go infinite")
    Thread.sleep(300)
    val t0 = System.currentTimeMillis()
    N.nativeSend("stop")
    readUntil { it.startsWith("bestmove") }
    check(System.currentTimeMillis() - t0 < 1500, "stop answered in ${System.currentTimeMillis() - t0} ms")

    // 6. invalid position handling: engine must stay alive (FSF prints nothing / keeps old position)
    N.nativeSend("position fen this/is/not/a/fen w - - 0 1")
    N.nativeSend("isready"); readUntil { it == "readyok" }
    check(N.nativeIsRunning(), "engine alive after invalid FEN")

    // 7. shutdown + restart
    check(N.nativeShutdown(5000), "shutdown joined engine thread")
    check(!N.nativeIsRunning(), "not running after shutdown")
    check(N.nativeStart(), "restart")
    N.nativeSend("uci"); readUntil { it == "uciok" }
    N.nativeSend("setoption name UCI_Variant value janggi")
    N.nativeSend("position startpos"); N.nativeSend("go depth 5")
    check(readUntil { it.startsWith("bestmove") }.last().startsWith("bestmove "), "search works after restart")
    check(N.nativeShutdown(5000), "second shutdown")
    println("ALL DESKTOP JNI CHECKS PASSED")
}
