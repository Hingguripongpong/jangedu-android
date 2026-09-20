package com.example.janggiai.engine.uci

/** One parsed `info ... pv ...` line. */
data class UciInfo(
    val depth: Int,
    val seldepth: Int,
    val multipv: Int,
    val scoreKind: String,   // "cp" | "mate"
    val scoreValue: Int,
    val bound: String?,      // "lowerbound" | "upperbound" | null
    val nodes: Long,
    val nps: Long,
    val timeMs: Long,
    val wdl: Triple<Int, Int, Int>?,
    val pv: List<String>,
) {
    /** cp-equivalent used for ordering: mate scores map to ±(100000 - n). */
    val orderingScore: Int get() = if (scoreKind == "mate") (if (scoreValue > 0) 100_000 - scoreValue else -(100_000 + scoreValue)) else scoreValue
    val mateIn: Int? get() = if (scoreKind == "mate") scoreValue else null
}

object UciInfoParser {
    /** Returns null for lines that are not a complete score+pv report (e.g. `info string ...`). */
    fun parse(line: String): UciInfo? {
        if (!line.startsWith("info ")) return null
        val tok = line.split(' ').filter { it.isNotEmpty() }
        var depth = 0; var seldepth = 0; var multipv = 1
        var scoreKind: String? = null; var scoreValue = 0; var bound: String? = null
        var nodes = 0L; var nps = 0L; var time = 0L
        var wdl: Triple<Int, Int, Int>? = null
        var pv: List<String>? = null
        var i = 1
        try {
            while (i < tok.size) {
                when (tok[i]) {
                    "depth" -> { depth = tok[i + 1].toInt(); i += 2 }
                    "seldepth" -> { seldepth = tok[i + 1].toInt(); i += 2 }
                    "multipv" -> { multipv = tok[i + 1].toInt(); i += 2 }
                    "nodes" -> { nodes = tok[i + 1].toLong(); i += 2 }
                    "nps" -> { nps = tok[i + 1].toLong(); i += 2 }
                    "time" -> { time = tok[i + 1].toLong(); i += 2 }
                    "hashfull", "tbhits", "currmovenumber" -> i += 2
                    "currmove" -> i += 2
                    "score" -> {
                        scoreKind = tok[i + 1]; scoreValue = tok[i + 2].toInt(); i += 3
                        if (i < tok.size && (tok[i] == "lowerbound" || tok[i] == "upperbound")) { bound = tok[i]; i += 1 }
                    }
                    "wdl" -> { wdl = Triple(tok[i + 1].toInt(), tok[i + 2].toInt(), tok[i + 3].toInt()); i += 4 }
                    "pv" -> { pv = tok.subList(i + 1, tok.size); i = tok.size }
                    "string" -> return null
                    else -> i += 1
                }
            }
        } catch (e: IndexOutOfBoundsException) { return null } catch (e: NumberFormatException) { return null }
        val kind = scoreKind ?: return null
        val line0 = pv ?: return null
        if (line0.isEmpty()) return null
        return UciInfo(depth, seldepth, multipv, kind, scoreValue, bound, nodes, nps, time, wdl, line0)
    }
}
