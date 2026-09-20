package com.example.janggiai.engine.fsf

import com.example.janggiai.engine.api.AnalysisLimits
import com.example.janggiai.engine.api.AnalysisSnapshot
import com.example.janggiai.engine.api.EngineException
import com.example.janggiai.engine.api.EngineMove
import com.example.janggiai.engine.api.EnginePosition
import com.example.janggiai.engine.api.JanggiEngine
import com.example.janggiai.engine.api.MoveAnalysis
import com.example.janggiai.engine.api.SearchInfo
import com.example.janggiai.engine.api.SearchLimits
import com.example.janggiai.engine.uci.UciInfo
import com.example.janggiai.engine.uci.UciInfoParser
import com.example.janggiai.engine.uci.UciTransport
import com.example.janggiai.game.LogisticWinProbability
import com.example.janggiai.game.Move
import com.example.janggiai.game.MoveGen
import com.example.janggiai.game.Notation
import com.example.janggiai.game.Position
import com.example.janggiai.game.RuleConfig
import com.example.janggiai.game.Rules
import com.example.janggiai.game.Uci
import com.example.janggiai.game.WinProbabilityModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [JanggiEngine] implementation on top of Fairy-Stockfish speaking UCI through a [UciTransport].
 *
 * Mode separation happens at command level:
 *  * [analyze]  → `setoption name MultiPV value <legal move count>` (analysis mode)
 *  * [bestMove] → `setoption name MultiPV value 1` (play mode)
 *
 * Threading: all engine I/O runs on one dedicated thread ([engineDispatcher]); searches are
 * serialised by [searchMutex] because UCI allows one `go` at a time.  [stop] may be called from
 * any thread — it writes directly to the transport, which is thread-safe.
 *
 * Race safety: a cancelled analysis sends `stop` and waits for the engine's `bestmove` before the
 * mutex is released, so the next search always starts on a quiet engine; every emitted snapshot
 * carries the caller's `analysisId` so the UI can discard stale results.
 */
class FairyStockfishEngine(
    private val transport: UciTransport,
    private val winModel: WinProbabilityModel = LogisticWinProbability(),
    /** Throttle for intermediate snapshots. */
    private val progressIntervalMs: Long = 100,
    private val maxPvLength: Int = 12,
) : JanggiEngine, Closeable {

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "janggi-engine-io").apply { isDaemon = true } }
    private val engineDispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()
    private val searchMutex = Mutex()
    private val closed = AtomicBoolean(false)

    @Volatile private var started = false
    @Volatile private var searching = false
    @Volatile override var name: String = "Fairy-Stockfish"
        private set

    /** Last commands written (protocol-level tests and the debug screen read these). */
    val sentCommands: ArrayDeque<String> = ArrayDeque()
    private val sentLock = Any()

    private var activeVariant: String? = null
    private var activeMultiPv: Int? = null
    private var activeThreads: Int? = null
    private var activeHash: Int? = null
    private var activeSkill: Int? = null

    // ------------------------------------------------------------------ lifecycle
    private fun send(line: String) {
        synchronized(sentLock) { sentCommands.addLast(line); while (sentCommands.size > 128) sentCommands.removeFirst() }
        transport.send(line)
    }

    /** Runs on the engine thread. */
    private fun ensureStarted() {
        if (closed.get()) throw EngineException("engine closed")
        if (started && transport.isRunning()) return
        if (!transport.start()) throw EngineException("could not start engine thread")
        activeVariant = null; activeMultiPv = null; activeThreads = null; activeHash = null; activeSkill = null
        drain()
        send("uci")
        val lines = readUntil(15_000) { it == "uciok" }
        lines.firstOrNull { it.startsWith("id name ") }?.let { name = it.removePrefix("id name ").trim() }
        if (lines.none { it.contains("UCI_Variant") && it.contains("janggi") })
            throw EngineException("engine does not support the janggi variant (not a largeboard build?)")
        send("setoption name UCI_ShowWDL value true")
        sync()
        started = true
    }

    private fun sync() { send("isready"); readUntil(30_000) { it == "readyok" } }

    private fun drain() { while (transport.readLine(0) != null) { /* discard */ } }

    private fun readUntil(timeoutMs: Long, pred: (String) -> Boolean): List<String> {
        val out = ArrayList<String>()
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            val line = transport.readLine(50)
            if (line == null) {
                if (!transport.isRunning()) throw EngineException("engine stopped unexpectedly")
                continue
            }
            out += line
            if (pred(line)) return out
        }
        throw EngineException("engine timed out")
    }

    private fun setOption(name: String, value: Any) = send("setoption name $name value $value")

    private fun applyCommon(rules: RuleConfig, threads: Int, hashMb: Int) {
        val variant = rules.fsfVariant
        var changed = false
        if (variant != activeVariant) { setOption("UCI_Variant", variant); activeVariant = variant; changed = true }
        val t = threads.coerceIn(1, 8)
        if (t != activeThreads) { setOption("Threads", t); activeThreads = t; changed = true }
        val h = hashMb.coerceIn(1, 1024)
        if (h != activeHash) { setOption("Hash", h); activeHash = h; changed = true }
        if (changed) sync()
    }

    private fun setMultiPv(n: Int) {
        val v = n.coerceIn(1, 500)
        if (v != activeMultiPv) { setOption("MultiPV", v); activeMultiPv = v }
    }

    private fun setSkill(level: Int) {
        val v = level.coerceIn(0, 20)
        if (v != activeSkill) { setOption("Skill Level", v); activeSkill = v }
    }

    private fun goCommand(timeMs: Long?, depth: Int?, nodes: Long?): String {
        val parts = ArrayList<String>().apply { add("go") }
        if (depth != null && depth > 0) { parts += "depth"; parts += depth.toString() }
        if (timeMs != null && timeMs > 0) { parts += "movetime"; parts += timeMs.toString() }
        if (nodes != null && nodes > 0) { parts += "nodes"; parts += nodes.toString() }
        if (parts.size == 1) parts += "infinite"
        return parts.joinToString(" ")
    }

    override fun stop() {
        if (searching) transport.send("stop")   // bypasses the queue: must be immediate from any thread
    }

    override suspend fun newGame() = withContext(engineDispatcher) {
        searchMutex.withLock { ensureStarted(); send("ucinewgame"); sync() }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try { if (transport.isRunning()) { transport.send("stop"); transport.shutdown(3000) } } catch (_: Throwable) {}
            executor.shutdownNow()
        }
    }

    // ------------------------------------------------------------------ analysis mode
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun analyze(position: EnginePosition, limits: AnalysisLimits, analysisId: Long): Flow<AnalysisSnapshot> = channelFlow {
        withContext(engineDispatcher) {
            searchMutex.withLock { runAnalysis(this@channelFlow, position, limits, analysisId) }
        }
    }

    private suspend fun runAnalysis(scope: ProducerScope<AnalysisSnapshot>, position: EnginePosition, limits: AnalysisLimits, analysisId: Long) {
        val pos = position.toPosition()
        val rules = position.rules.forEngine()
        val status = Rules.gameStatus(pos, rules)
        val legal = if (status.isOver) emptyList() else MoveGen.legalMoves(pos)   // nothing to analyse after the end
        val start = System.nanoTime()
        val stateKey = pos.stateKey()
        val meta = Meta(pos, legal)
        val lines = HashMap<Int, UciInfo>()          // multipv index -> latest info

        fun snapshot(final: Boolean, aborted: Boolean, error: String? = null): AnalysisSnapshot =
            buildSnapshot(analysisId, meta, stateKey, lines, start, final, aborted, error, legal.size)

        if (legal.isEmpty()) { scope.send(snapshot(final = true, aborted = false)); return }

        try {
            ensureStarted()
            applyCommon(rules, limits.threads, limits.hashMb)
            drain()
            setMultiPv(minOf(legal.size, limits.maxMultiPv))     // analysis mode: one PV per legal move
            send(Uci.positionCommand(pos))
            sync()
            send(goCommand(limits.timeMs, limits.depth, limits.nodes))
            searching = true

            var lastEmit = 0L
            var stopSent = false
            var aborted = false
            while (true) {
                if (!scope.isActive && !stopSent) { transport.send("stop"); stopSent = true; aborted = true }
                val line = transport.readLine(50)
                if (line == null) {
                    if (!transport.isRunning()) throw EngineException("engine stopped during analysis")
                    continue
                }
                if (line.startsWith("bestmove")) break
                val info = UciInfoParser.parse(line) ?: continue
                if (info.multipv < 1) continue
                lines[info.multipv] = info
                val now = System.nanoTime()
                // emit when a MultiPV round completes (last index) or the throttle interval elapsed
                val roundDone = info.multipv == minOf(legal.size, limits.maxMultiPv)
                if (scope.isActive && (roundDone || now - lastEmit > progressIntervalMs * 1_000_000)) {
                    lastEmit = now
                    scope.send(snapshot(final = false, aborted = false))
                }
            }
            searching = false
            scope.send(snapshot(final = true, aborted = aborted))
        } catch (e: EngineException) {
            searching = false
            scope.send(snapshot(final = true, aborted = true, error = e.message))
        }
    }

    private class Meta(val pos: Position, legal: List<Int>) {
        val fen = pos.toFen()
        val side = pos.side
        val legalSet = legal.toHashSet()
        val gives = HashMap<Int, Boolean>().also { m -> for (mv in legal) m[mv] = MoveGen.givesCheck(pos, mv) }
        val legalList = legal
    }

    private fun buildSnapshot(analysisId: Long, meta: Meta, stateKey: String, lines: Map<Int, UciInfo>, startNs: Long,
                              final: Boolean, aborted: Boolean, error: String?, legalCount: Int): AnalysisSnapshot {
        val byMove = HashMap<Int, UciInfo>()
        for (ln in lines.values) {
            val m = runCatching { Uci.uciToMove(ln.pv[0]) }.getOrNull() ?: continue
            if (m in meta.legalSet) byMove[m] = ln
        }
        val scored = ArrayList<Pair<Int, UciInfo>>()
        val unscored = ArrayList<Int>()
        for (m in meta.legalList) { val ln = byMove[m]; if (ln == null) unscored += m else scored += m to ln }
        scored.sortWith(compareByDescending<Pair<Int, UciInfo>> { it.second.orderingScore }.thenBy { Notation.moveToString(it.first) })
        val out = ArrayList<MoveAnalysis>(meta.legalList.size)
        var rank = 1
        for ((m, ln) in scored) out += toMoveAnalysis(meta, m, ln, rank++)
        for (m in unscored.sortedBy { Notation.moveToString(it) }) out += toMoveAnalysis(meta, m, null, rank++)
        val depths = byMove.values.map { it.depth }
        val depth = if (byMove.size >= legalCount && depths.isNotEmpty()) depths.min() else 0
        val nodes = lines.values.maxOfOrNull { it.nodes } ?: 0L
        val nps = lines.values.maxOfOrNull { it.nps } ?: 0L
        return AnalysisSnapshot(
            analysisId = analysisId, fen = meta.fen, stateKey = stateKey, sideToMove = meta.side, moves = out,
            depth = depth, maxDepth = depths.maxOrNull() ?: 0, nodes = nodes, nps = nps,
            timeMs = (System.nanoTime() - startNs) / 1_000_000, final = final, aborted = aborted, error = error,
            engineName = name, multiPv = activeMultiPv ?: 0, legalMoveCount = legalCount,
        )
    }

    private fun toMoveAnalysis(meta: Meta, m: Int, ln: UciInfo?, rank: Int): MoveAnalysis {
        val board = meta.pos.board
        val from = if (m == Move.PASS) null else Move.from(m)
        val to = if (m == Move.PASS) null else Move.to(m)
        var scoreCp: Int? = null; var mateIn: Int? = null; var winRate: Double? = null
        var pv = listOf(m); var pvText = listOf(Notation.describe(board, m))
        if (ln != null) {
            scoreCp = ln.orderingScore
            mateIn = ln.mateIn
            winRate = if (mateIn != null) (if (mateIn > 0) 1.0 else 0.0) else winModel.winProbability(ln.scoreValue)
            val conv = convertPv(meta.pos, ln.pv)
            if (conv.first.isNotEmpty() && conv.first[0] == m) { pv = conv.first; pvText = conv.second }
        }
        return MoveAnalysis(
            rank = rank, move = m, moveText = Notation.moveToString(m), notation = Notation.describe(board, m),
            from = from, to = to, scoreCp = scoreCp, mateIn = mateIn, winRate = winRate,
            depth = ln?.depth ?: 0, seldepth = ln?.seldepth ?: 0, nodes = ln?.nodes ?: 0L,
            pv = pv, pvText = pvText, bound = ln?.bound, givesCheck = meta.gives[m] ?: false,
            isCapture = to != null && board[to] != 0,
        )
    }

    private fun convertPv(pos: Position, pv: List<String>): Pair<List<Int>, List<String>> {
        val cur = pos.copy()
        val moves = ArrayList<Int>(); val text = ArrayList<String>()
        for (t in pv.take(maxPvLength)) {
            val m = runCatching { Uci.uciToMove(t) }.getOrNull() ?: break
            if (!MoveGen.legalMoves(cur).contains(m)) break
            moves += m; text += Notation.describe(cur.board, m)
            cur.makeMove(m)
        }
        return moves to text
    }

    // ------------------------------------------------------------------ play mode
    override suspend fun bestMove(position: EnginePosition, limits: SearchLimits, onInfo: ((SearchInfo) -> Unit)?): EngineMove =
        withContext(engineDispatcher) { searchMutex.withLock { runBestMove(position, limits, onInfo) } }

    private suspend fun runBestMove(position: EnginePosition, limits: SearchLimits, onInfo: ((SearchInfo) -> Unit)?): EngineMove {
        val pos = position.toPosition()
        val rules = position.rules.forEngine()
        val legal = MoveGen.legalMoves(pos)
        val status = Rules.gameStatus(pos, rules, legal)
        val start = System.nanoTime()
        fun empty(aborted: Boolean) = EngineMove(null, null, null, null, null, null, 0, 0, 0, (System.nanoTime() - start) / 1_000_000, emptyList(), name, aborted)
        if (legal.isEmpty() || status.isOver) return empty(false)

        ensureStarted()
        applyCommon(rules, limits.threads, limits.hashMb)
        drain()
        setMultiPv(1)                                   // play mode: the whole budget into one line
        setSkill(limits.skillLevel)
        send(Uci.positionCommand(pos))
        sync()
        send(goCommand(limits.timeMs, limits.depth, limits.nodes))
        searching = true

        var last: UciInfo? = null
        var bestText: String? = null
        var stopSent = false
        var aborted = false
        try {
            while (true) {
                if (!currentCoroutineContext().isActive && !stopSent) { transport.send("stop"); stopSent = true; aborted = true }
                val line = transport.readLine(50)
                if (line == null) {
                    if (!transport.isRunning()) throw EngineException("engine stopped during search")
                    continue
                }
                if (line.startsWith("bestmove")) { bestText = line.split(' ').getOrNull(1); break }
                val info = UciInfoParser.parse(line) ?: continue
                if (info.multipv == 1) {
                    last = info
                    onInfo?.invoke(SearchInfo(info.depth, info.seldepth, if (info.mateIn == null) info.scoreValue else null, info.mateIn, info.nodes, info.nps, info.timeMs, info.pv))
                }
            }
        } finally { searching = false }

        val legalSet = legal.toHashSet()
        var m: Int? = bestText?.takeIf { it != "(none)" }?.let { runCatching { Uci.uciToMove(it) }.getOrNull() }?.takeIf { it in legalSet }
        if (m == null) m = last?.let { runCatching { Uci.uciToMove(it.pv[0]) }.getOrNull() }?.takeIf { it in legalSet }
        if (m == null) return empty(aborted)
        val ln = last
        val pv = ln?.let { convertPv(pos, it.pv).first }?.takeIf { it.isNotEmpty() && it[0] == m } ?: listOf(m)
        val mateIn = ln?.mateIn
        val scoreCp = ln?.orderingScore
        val winRate = when { ln == null -> null; mateIn != null -> if (mateIn > 0) 1.0 else 0.0; else -> winModel.winProbability(ln.scoreValue) }
        return EngineMove(
            move = m, moveText = Notation.moveToString(m), notation = Notation.describe(pos.board, m),
            scoreCp = scoreCp, mateIn = mateIn, winRate = winRate, depth = ln?.depth ?: 0, nodes = ln?.nodes ?: 0,
            nps = ln?.nps ?: 0, timeMs = (System.nanoTime() - start) / 1_000_000, pv = pv, engineName = name, aborted = aborted,
        )
    }
}
