package com.example.janggiai.game

import com.example.janggiai.game.Board.CHO
import com.example.janggiai.game.Board.COLS
import com.example.janggiai.game.Board.HAN
import com.example.janggiai.game.Board.NUM_SQUARES
import com.example.janggiai.game.Board.ROWS
import com.example.janggiai.game.Board.onBoard
import com.example.janggiai.game.Board.sq

/**
 * Precomputed move/attack tables (port of `engine/geometry.py`).
 *
 * Palaces are the 3x3 blocks rows 0-2 / 7-9 on files 3-5.  Diagonal lines exist only between
 * a palace corner and its centre; [diagStepOk] is the single source of truth for that.
 */
object Geometry {
    /** 0 = Han palace (top), 1 = Cho palace (bottom), -1 = not in a palace. */
    fun palaceId(row: Int, col: Int): Int {
        if (col in 3..5) {
            if (row <= 2) return HAN
            if (row >= 7) return CHO
        }
        return -1
    }

    fun isPalaceCenter(row: Int, col: Int): Boolean = (row == 1 && col == 4) || (row == 8 && col == 4)

    fun diagStepOk(r1: Int, c1: Int, r2: Int, c2: Int): Boolean {
        if (kotlin.math.abs(r1 - r2) != 1 || kotlin.math.abs(c1 - c2) != 1) return false
        val p1 = palaceId(r1, c1)
        if (p1 < 0 || p1 != palaceId(r2, c2)) return false
        return isPalaceCenter(r1, c1) || isPalaceCenter(r2, c2)
    }

    private val ORTH = arrayOf(intArrayOf(-1, 0), intArrayOf(1, 0), intArrayOf(0, -1), intArrayOf(0, 1))
    private val DIAG = arrayOf(intArrayOf(-1, -1), intArrayOf(-1, 1), intArrayOf(1, -1), intArrayOf(1, 1))

    /** Sliding rays (orthogonal, palace diagonal, both) per square: list of squares along the ray. */
    val RAYS_ORTH: Array<Array<IntArray>>
    val RAYS_DIAG: Array<Array<IntArray>>
    val RAYS_ALL: Array<Array<IntArray>>

    /** King/guard one-step destinations inside the palace. */
    val PALACE_STEPS: Array<IntArray>

    /** Horse: pairs (blockingSquare, destination). Elephant: triples (block1, block2, destination). */
    val HORSE_MOVES: Array<Array<IntArray>>
    val ELEPHANT_MOVES: Array<Array<IntArray>>

    /** Pawn destinations per colour. */
    val PAWN_MOVES: Array<Array<IntArray>>

    /** Reverse tables: which origins attack this square. */
    val HORSE_ATTACKS: Array<Array<IntArray>>      // (block, origin)
    val ELEPHANT_ATTACKS: Array<Array<IntArray>>   // (b1, b2, origin)
    val PAWN_ATTACKS: Array<Array<IntArray>>       // [colour][target] -> origins

    val IN_PALACE = IntArray(NUM_SQUARES) { palaceId(Board.rowOf(it), Board.colOf(it)) }
    val ROW_OF = IntArray(NUM_SQUARES) { Board.rowOf(it) }
    val COL_OF = IntArray(NUM_SQUARES) { Board.colOf(it) }
    val MIRROR_SQ = IntArray(NUM_SQUARES) { sq(ROWS - 1 - Board.rowOf(it), Board.colOf(it)) }

    init {
        val raysOrth = Array(NUM_SQUARES) { ArrayList<IntArray>() }
        val raysDiag = Array(NUM_SQUARES) { ArrayList<IntArray>() }
        val palaceSteps = Array(NUM_SQUARES) { ArrayList<Int>() }
        val horse = Array(NUM_SQUARES) { ArrayList<IntArray>() }
        val elephant = Array(NUM_SQUARES) { ArrayList<IntArray>() }
        val pawn = Array(2) { Array(NUM_SQUARES) { ArrayList<Int>() } }
        val horseAtt = Array(NUM_SQUARES) { ArrayList<IntArray>() }
        val elephantAtt = Array(NUM_SQUARES) { ArrayList<IntArray>() }
        val pawnAtt = Array(2) { Array(NUM_SQUARES) { ArrayList<Int>() } }

        for (s in 0 until NUM_SQUARES) {
            val r = Board.rowOf(s)
            val c = Board.colOf(s)

            for (d in ORTH) {
                val ray = ArrayList<Int>()
                var rr = r + d[0]; var cc = c + d[1]
                while (onBoard(rr, cc)) { ray += sq(rr, cc); rr += d[0]; cc += d[1] }
                if (ray.isNotEmpty()) raysOrth[s] += ray.toIntArray()
            }
            for (d in DIAG) {
                val ray = ArrayList<Int>()
                var pr = r; var pc = c
                var rr = r + d[0]; var cc = c + d[1]
                while (onBoard(rr, cc) && diagStepOk(pr, pc, rr, cc)) {
                    ray += sq(rr, cc); pr = rr; pc = cc; rr += d[0]; cc += d[1]
                }
                if (ray.isNotEmpty()) raysDiag[s] += ray.toIntArray()
            }

            val pid = palaceId(r, c)
            if (pid >= 0) {
                for (d in ORTH) {
                    val rr = r + d[0]; val cc = c + d[1]
                    if (onBoard(rr, cc) && palaceId(rr, cc) == pid) palaceSteps[s] += sq(rr, cc)
                }
                for (d in DIAG) {
                    val rr = r + d[0]; val cc = c + d[1]
                    if (onBoard(rr, cc) && diagStepOk(r, c, rr, cc)) palaceSteps[s] += sq(rr, cc)
                }
            }

            for (d in ORTH) {
                val br = r + d[0]; val bc = c + d[1]
                if (!onBoard(br, bc)) continue
                val diags = if (d[0] != 0) arrayOf(intArrayOf(d[0], -1), intArrayOf(d[0], 1))
                            else arrayOf(intArrayOf(-1, d[1]), intArrayOf(1, d[1]))
                for (sd in diags) {
                    val tr = br + sd[0]; val tc = bc + sd[1]
                    if (onBoard(tr, tc)) horse[s] += intArrayOf(sq(br, bc), sq(tr, tc))
                    val t2r = tr + sd[0]; val t2c = tc + sd[1]
                    if (onBoard(t2r, t2c)) elephant[s] += intArrayOf(sq(br, bc), sq(tr, tc), sq(t2r, t2c))
                }
            }

            for (color in intArrayOf(CHO, HAN)) {
                val fwd = if (color == CHO) -1 else 1
                if (onBoard(r + fwd, c)) pawn[color][s] += sq(r + fwd, c)
                for (dc in intArrayOf(-1, 1)) if (onBoard(r, c + dc)) pawn[color][s] += sq(r, c + dc)
                for (dc in intArrayOf(-1, 1)) {
                    val rr = r + fwd; val cc = c + dc
                    if (onBoard(rr, cc) && diagStepOk(r, c, rr, cc)) pawn[color][s] += sq(rr, cc)
                }
            }
        }
        for (s in 0 until NUM_SQUARES) {
            for (hm in horse[s]) horseAtt[hm[1]] += intArrayOf(hm[0], s)
            for (em in elephant[s]) elephantAtt[em[2]] += intArrayOf(em[0], em[1], s)
            for (color in intArrayOf(CHO, HAN)) for (dest in pawn[color][s]) pawnAtt[color][dest] += s
        }

        RAYS_ORTH = Array(NUM_SQUARES) { raysOrth[it].toTypedArray() }
        RAYS_DIAG = Array(NUM_SQUARES) { raysDiag[it].toTypedArray() }
        RAYS_ALL = Array(NUM_SQUARES) { (raysOrth[it] + raysDiag[it]).toTypedArray() }
        PALACE_STEPS = Array(NUM_SQUARES) { palaceSteps[it].toIntArray() }
        HORSE_MOVES = Array(NUM_SQUARES) { horse[it].toTypedArray() }
        ELEPHANT_MOVES = Array(NUM_SQUARES) { elephant[it].toTypedArray() }
        PAWN_MOVES = Array(2) { c -> Array(NUM_SQUARES) { pawn[c][it].toIntArray() } }
        HORSE_ATTACKS = Array(NUM_SQUARES) { horseAtt[it].toTypedArray() }
        ELEPHANT_ATTACKS = Array(NUM_SQUARES) { elephantAtt[it].toTypedArray() }
        PAWN_ATTACKS = Array(2) { c -> Array(NUM_SQUARES) { pawnAtt[c][it].toIntArray() } }
    }
}
