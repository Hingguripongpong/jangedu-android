package com.example.janggiai.game

/**
 * Fairy-Stockfish UCI coordinate mapping (port of the notation part of `engine/uci_engine.py`).
 *
 * Files a–i from Cho's left, ranks 1–10 with rank 1 = Cho's back rank (our row 9).  A pass is
 * written as the moving king's square twice (`e2e2`).
 */
object Uci {
    private const val FILES = "abcdefghi"

    fun squareToUci(s: Int): String {
        val r = Board.rowOf(s); val c = Board.colOf(s)
        return "${FILES[c]}${10 - r}"
    }

    fun uciToSquare(text: String): Int {
        val c = FILES.indexOf(text[0])
        if (c < 0) throw IllegalArgumentException("bad UCI square '$text'")
        val r = 10 - text.substring(1).toInt()
        if (r !in 0 until 10) throw IllegalArgumentException("bad UCI square '$text'")
        return r * 9 + c
    }

    fun moveToUci(pos: Position, m: Int): String {
        if (m == Move.PASS) { val k = squareToUci(pos.kingSq[pos.side]); return k + k }
        return squareToUci(Move.from(m)) + squareToUci(Move.to(m))
    }

    /** UCI move → our int move (a square repeated = 한수쉼). */
    fun uciToMove(text: String): Int {
        val t = text.trim()
        var i = 1
        while (i < t.length && !t[i].isLetter()) i++
        if (i >= t.length) throw IllegalArgumentException("bad UCI move '$text'")
        val from = uciToSquare(t.substring(0, i)); val to = uciToSquare(t.substring(i))
        return if (from == to) Move.PASS else Move.encode(from, to)
    }

    fun movesToUci(start: Position, moves: List<Int>): List<String> {
        val pos = start.copy()
        val out = ArrayList<String>(moves.size)
        for (m in moves) { out += moveToUci(pos, m); pos.makeMove(m) }
        return out
    }

    /**
     * `position fen <root> moves ...` for the given position, including its move history so the
     * engine sees repetitions; `position fen <fen>` when there is no history.
     */
    fun positionCommand(pos: Position): String {
        val played = pos.movesPlayed()
        if (played.isEmpty()) return "position fen ${pos.toFen()}"
        val root = pos.copy()
        root.unwindTo(0)
        return "position fen ${root.toFen()} moves ${movesToUci(root, played).joinToString(" ")}"
    }
}
