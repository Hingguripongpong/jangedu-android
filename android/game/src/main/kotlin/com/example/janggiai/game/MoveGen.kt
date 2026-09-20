package com.example.janggiai.game

import com.example.janggiai.game.Board.NUM_SQUARES
import com.example.janggiai.game.Piece.CANNON
import com.example.janggiai.game.Piece.CHARIOT
import com.example.janggiai.game.Piece.ELEPHANT
import com.example.janggiai.game.Piece.HORSE
import com.example.janggiai.game.Piece.PAWN

/**
 * Move generation (port of `engine/movegen.py`).
 *
 *  * 차 slides orthogonally, plus palace diagonals.
 *  * 포 jumps exactly one screen (never a cannon) and never captures a cannon.
 *  * 마 one orthogonal + one diagonal step (first square must be empty); 상 one orthogonal + two
 *    diagonal steps (both intermediate squares empty).
 *  * 졸/병 forward or sideways, palace diagonals forward inside a palace.
 *  * 궁/사 one step along palace lines.
 *  * 한수쉼 ([Move.PASS]) is legal whenever the side to move is not in check.
 */
object MoveGen {
    fun pseudoMoves(pos: Position, capturesOnly: Boolean = false): IntArrayList {
        val board = pos.board
        val side = pos.side
        val moves = IntArrayList(64)
        val pawnTbl = Geometry.PAWN_MOVES[side]
        for (from in 0 until NUM_SQUARES) {
            val p = board[from]
            if (p == 0 || Piece.colorOf(p) != side) continue
            val base = from * NUM_SQUARES
            when (Piece.typeOf(p)) {
                CHARIOT -> for (ray in Geometry.RAYS_ALL[from]) {
                    for (to in ray) {
                        val q = board[to]
                        if (q == 0) { if (!capturesOnly) moves.add(base + to) }
                        else { if (Piece.colorOf(q) != side) moves.add(base + to); break }
                    }
                }
                CANNON -> for (ray in Geometry.RAYS_ALL[from]) {
                    var screen = false
                    for (to in ray) {
                        val q = board[to]
                        if (!screen) {
                            if (q != 0) { if (Piece.typeOf(q) == CANNON) break; screen = true }
                        } else if (q == 0) {
                            if (!capturesOnly) moves.add(base + to)
                        } else {
                            if (Piece.colorOf(q) != side && Piece.typeOf(q) != CANNON) moves.add(base + to)
                            break
                        }
                    }
                }
                HORSE -> for (hm in Geometry.HORSE_MOVES[from]) {
                    if (board[hm[0]] != 0) continue
                    val q = board[hm[1]]
                    if (q == 0) { if (!capturesOnly) moves.add(base + hm[1]) }
                    else if (Piece.colorOf(q) != side) moves.add(base + hm[1])
                }
                ELEPHANT -> for (em in Geometry.ELEPHANT_MOVES[from]) {
                    if (board[em[0]] != 0 || board[em[1]] != 0) continue
                    val q = board[em[2]]
                    if (q == 0) { if (!capturesOnly) moves.add(base + em[2]) }
                    else if (Piece.colorOf(q) != side) moves.add(base + em[2])
                }
                PAWN -> for (to in pawnTbl[from]) {
                    val q = board[to]
                    if (q == 0) { if (!capturesOnly) moves.add(base + to) }
                    else if (Piece.colorOf(q) != side) moves.add(base + to)
                }
                else -> for (to in Geometry.PALACE_STEPS[from]) {  // KING / GUARD
                    val q = board[to]
                    if (q == 0) { if (!capturesOnly) moves.add(base + to) }
                    else if (Piece.colorOf(q) != side) moves.add(base + to)
                }
            }
        }
        return moves
    }

    /** Forced-bikjang variant: the side to move faces a 빅장 and is not in check. */
    fun mustResolveBikjang(pos: Position): Boolean = pos.forcedBikjang && pos.bikjang && !pos.inCheck()

    fun legalMoves(pos: Position, includePass: Boolean = true): List<Int> {
        val side = pos.side
        val legal = ArrayList<Int>(48)
        val mustResolve = mustResolveBikjang(pos)
        val pseudo = pseudoMoves(pos)
        for (i in 0 until pseudo.size) {
            val m = pseudo[i]
            pos.makeMove(m)
            var ok = !pos.attackedBy(pos.kingSq[side], side xor 1)
            if (ok && mustResolve && pos.kingsFacing()) ok = false   // leaving the kings facing is not an answer to a 빅장
            pos.unmakeMove()
            if (ok) legal += m
        }
        if (includePass && !pos.inCheck()) legal += Move.PASS   // under a forced 빅장 the pass accepts the ending
        return legal
    }

    fun isLegal(pos: Position, m: Int): Boolean =
        if (m == Move.PASS) !pos.inCheck() else legalMoves(pos, includePass = false).contains(m)

    fun givesCheck(pos: Position, m: Int): Boolean {
        pos.makeMove(m)
        val chk = pos.inCheck()
        pos.unmakeMove()
        return chk
    }

    /**
     * Leaf count of the legal move tree.  [stopAtGameEnd] treats rule-ended positions (two passes,
     * unresolved/accepted 빅장) as leaves, which is how Fairy-Stockfish counts; with [includePass]
     * on a forced-bikjang position this reproduces its `go perft` numbers exactly.
     */
    fun perft(pos: Position, depth: Int, includePass: Boolean = false, stopAtGameEnd: Boolean = false): Long {
        if (depth == 0) return 1
        if (stopAtGameEnd && (pos.passes >= 2 || (pos.bikjang && pos.prevBikjang()))) return 0
        val side = pos.side
        var total = 0L
        val pseudo = pseudoMoves(pos)
        val mustResolve = mustResolveBikjang(pos)
        if (depth == 1 && !stopAtGameEnd && !mustResolve) {
            for (i in 0 until pseudo.size) {
                pos.makeMove(pseudo[i])
                if (!pos.attackedBy(pos.kingSq[side], side xor 1)) total++
                pos.unmakeMove()
            }
            if (includePass && !pos.inCheck()) total++
            return total
        }
        for (i in 0 until pseudo.size) {
            pos.makeMove(pseudo[i])
            if (!pos.attackedBy(pos.kingSq[side], side xor 1) && !(mustResolve && pos.kingsFacing()))
                total += perft(pos, depth - 1, includePass, stopAtGameEnd)
            pos.unmakeMove()
        }
        if (includePass && !pos.inCheck()) {
            pos.makeMove(Move.PASS)
            total += perft(pos, depth - 1, includePass, stopAtGameEnd)
            pos.unmakeMove()
        }
        return total
    }
}

/** Minimal growable int list (avoids boxing in the hot move-generation path). */
class IntArrayList(capacity: Int = 16) {
    private var data = IntArray(capacity)
    var size = 0
        private set

    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    operator fun get(i: Int): Int = data[i]
    fun toList(): List<Int> = List(size) { data[it] }
}
