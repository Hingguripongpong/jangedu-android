package com.example.janggiai.game

import com.example.janggiai.game.Board.CHO
import com.example.janggiai.game.Board.COLS
import com.example.janggiai.game.Board.sq
import com.example.janggiai.game.Piece.CANNON
import com.example.janggiai.game.Piece.CHARIOT
import com.example.janggiai.game.Piece.ELEPHANT
import com.example.janggiai.game.Piece.GUARD
import com.example.janggiai.game.Piece.HORSE
import com.example.janggiai.game.Piece.KING
import com.example.janggiai.game.Piece.PAWN

/**
 * Human-readable notation (port of `engine/notation.py`).
 *
 * Squares use the Korean two-digit form `RC`: R = row counted from Han's side 1..9 then 0 for the
 * 10th row, C = file 1..9 from Cho's left.  Cho's king starts on `95`, Han's on `25`.
 * Canonical machine move string: `"95-73"` or `"pass"`.
 */
object Notation {
    fun hanja(p: Int): String {
        val cho = Piece.colorOf(p) == CHO
        return when (Piece.typeOf(p)) {
            KING -> if (cho) "楚" else "漢"
            GUARD -> "士"; CHARIOT -> "車"; CANNON -> "包"; HORSE -> "馬"; ELEPHANT -> "象"
            PAWN -> if (cho) "卒" else "兵"
            else -> "?"
        }
    }

    fun korean(p: Int): String {
        val cho = Piece.colorOf(p) == CHO
        return when (Piece.typeOf(p)) {
            KING -> "궁"; GUARD -> "사"; CHARIOT -> "차"; CANNON -> "포"; HORSE -> "마"; ELEPHANT -> "상"
            PAWN -> if (cho) "졸" else "병"
            else -> "?"
        }
    }

    fun sideName(side: Int): String = if (side == CHO) "cho" else "han"
    fun sideKorean(side: Int): String = if (side == CHO) "초" else "한"

    fun squareToString(s: Int): String {
        val r = Board.rowOf(s); val c = Board.colOf(s)
        return "${(r + 1) % 10}${c + 1}"
    }

    fun parseSquare(text: String): Int {
        val t = text.trim()
        if (t.length != 2 || !t.all { it.isDigit() }) throw IllegalArgumentException("bad square '$text'")
        val r = ((t[0] - '0') - 1 + 10) % 10
        val c = (t[1] - '0') - 1
        if (c !in 0 until COLS) throw IllegalArgumentException("bad square '$text'")
        return sq(r, c)
    }

    fun moveToString(m: Int): String =
        if (m == Move.PASS) "pass" else "${squareToString(Move.from(m))}-${squareToString(Move.to(m))}"

    private val MOVE_RE = Regex("^\\s*(\\d\\d)\\s*(?:-|→|->|>)?\\s*(\\d\\d)\\s*$")

    fun parseMove(text: String): Int {
        val t = text.trim().lowercase()
        if (t in setOf("pass", "한수쉼", "쉼", "p", "--")) return Move.PASS
        val mr = MOVE_RE.find(t) ?: throw IllegalArgumentException("bad move '$text'")
        return Move.encode(parseSquare(mr.groupValues[1]), parseSquare(mr.groupValues[2]))
    }

    /** Pretty form with the moving piece: `馬 92→73` / `車 01x04` / `한수쉼`. */
    fun describe(board: IntArray, m: Int): String {
        if (m == Move.PASS) return "한수쉼"
        val from = Move.from(m); val to = Move.to(m)
        val p = board[from]
        val name = if (p != 0) hanja(p) else "?"
        val sep = if (board[to] != 0) "x" else "→"
        return "$name ${squareToString(from)}$sep${squareToString(to)}"
    }
}
