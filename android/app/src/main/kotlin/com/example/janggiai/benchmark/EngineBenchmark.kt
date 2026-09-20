package com.example.janggiai.benchmark

import com.example.janggiai.engine.api.AnalysisLimits
import com.example.janggiai.engine.api.EnginePosition
import com.example.janggiai.engine.api.JanggiEngine
import com.example.janggiai.engine.api.SearchLimits
import kotlinx.coroutines.flow.last

data class BenchmarkRow(val mode: String, val budgetMs: Long, val depth: Int, val nodes: Long, val nps: Long, val elapsedMs: Long)

/**
 * Developer benchmark (Settings → 엔진 벤치마크): analysis (MultiPV=N) and play (MultiPV=1) from the
 * start position at 300 ms / 1 s / 3 s.  Reports depth, nodes, nps and wall time.  CPU usage and
 * memory are read from the OS by the caller (see BenchmarkScreen), not estimated here.
 */
class EngineBenchmark(private val engine: JanggiEngine) {
    suspend fun run(threads: Int, hashMb: Int, budgets: List<Long> = listOf(300, 1000, 3000), onRow: (BenchmarkRow) -> Unit) {
        val pos = EnginePosition()
        for (ms in budgets) {
            val snap = engine.analyze(pos, AnalysisLimits(timeMs = ms, threads = threads, hashMb = hashMb), analysisId = ms).last()
            onRow(BenchmarkRow("분석 MultiPV=${snap.legalMoveCount}", ms, snap.depth, snap.nodes, snap.nps, snap.timeMs))
        }
        for (ms in budgets) {
            val mv = engine.bestMove(pos, SearchLimits(timeMs = ms, threads = threads, hashMb = hashMb))
            onRow(BenchmarkRow("대국 MultiPV=1", ms, mv.depth, mv.nodes, mv.nps, mv.timeMs))
        }
    }
}
