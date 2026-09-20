package com.example.janggiai.game

/**
 * Deterministic Zobrist keys (SplitMix64 from a fixed seed) so hashes are reproducible across
 * runs and devices.  `hash` identifies board + side to move; the state keys below are XOR-ed
 * in by callers that need a *game state* identity (analysis caches), never into [Position.hash]
 * itself, so repetition detection keeps working on the plain board identity.
 */
object Zobrist {
    private var state = 0x4A61_6E67_6769_0001L  // "Janggi" + 1

    private fun next(): Long {
        state += -0x61c8864680b583ebL           // 0x9E3779B97F4A7C15
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L  // 0xBF58476D1CE4E5B9
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L  // 0x94D049BB133111EB
        return z xor (z ushr 31)
    }

    /** [piece][square]; index 0 (empty) is never used. */
    val PIECE: Array<LongArray> = Array(16) { LongArray(Board.NUM_SQUARES) { next() } }
    val SIDE: Long = next()
    val PASS_PENDING: Long = next()
    val BIKJANG: Long = next()

    fun compute(board: IntArray, side: Int): Long {
        var h = 0L
        for (s in board.indices) {
            val p = board[s]
            if (p != 0) h = h xor PIECE[p][s]
        }
        if (side != 0) h = h xor SIDE
        return h
    }
}
