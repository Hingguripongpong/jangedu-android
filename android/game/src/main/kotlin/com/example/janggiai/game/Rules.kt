package com.example.janggiai.game

import com.example.janggiai.game.Board.CHO
import com.example.janggiai.game.Board.HAN
import com.example.janggiai.game.Piece.CANNON
import com.example.janggiai.game.Piece.CHARIOT
import com.example.janggiai.game.Piece.ELEPHANT
import com.example.janggiai.game.Piece.GUARD
import com.example.janggiai.game.Piece.HORSE
import com.example.janggiai.game.Piece.KING
import com.example.janggiai.game.Piece.PAWN

/** How a 빅장 (facing kings) ends the game — see the Python `RuleConfig` docstring. */
enum class BikjangRule(val id: String, val fsfVariant: String?) {
    /** No bikjang rule (modern online janggi, Fairy-Stockfish `janggicasual`). */
    OFF("off", "janggicasual"),
    /** Receiver may only resolve or pass; pass/two passes decided on material points (Fairy-Stockfish `janggi`). */
    FORCED("forced", "janggi"),
    /** Any move allowed; an unresolved bikjang is a draw (Python engine only). */
    DRAW("draw", null),
    /** Any move allowed; an unresolved bikjang is decided on material points (Python engine only). */
    POINTS("points", null);

    companion object {
        fun fromId(id: String): BikjangRule = entries.firstOrNull { it.id == id } ?: OFF
    }
}

enum class RepetitionRule(val id: String) { DRAW("draw"), OFF("off");
    companion object { fun fromId(id: String): RepetitionRule = entries.firstOrNull { it.id == id } ?: DRAW }
}

data class RuleConfig(
    val bikjang: BikjangRule = BikjangRule.OFF,
    val repetition: RepetitionRule = RepetitionRule.DRAW,
    val maxPlies: Int = Rules.MAX_PLIES,
) {
    val forcedBikjang: Boolean get() = bikjang == BikjangRule.FORCED

    /** Stamps the legality-affecting part of the rules onto a position (returns it). */
    fun apply(pos: Position): Position { pos.forcedBikjang = forcedBikjang; return pos }

    /** Fairy-Stockfish variant implementing these rules; the Python-only endings map to `janggi`. */
    val fsfVariant: String get() = bikjang.fsfVariant ?: "janggi"

    /** Rules as the engine will actually apply them (DRAW/POINTS are coerced to FORCED for FSF). */
    fun forEngine(): RuleConfig = if (bikjang.fsfVariant == null) copy(bikjang = BikjangRule.FORCED) else this

    companion object {
        val DEFAULT = RuleConfig()
        val TRADITIONAL = RuleConfig(bikjang = BikjangRule.FORCED)
    }
}

enum class GameEnd(val id: String) {
    ONGOING("ongoing"),
    CHECKMATE("checkmate"),
    DRAW_BIKJANG("draw_bikjang"),
    BIKJANG_POINTS("bikjang_points"),
    DRAW_REPETITION("draw_repetition"),
    DRAW_PASSES("draw_passes"),
    PASSES_POINTS("passes_points"),
    DRAW_MOVE_LIMIT("draw_move_limit"),
}

data class MaterialPoints(val cho: Double, val han: Double)

data class GameStatus(
    val end: GameEnd,
    /** CHO / HAN or null. */
    val winner: Int?,
    val inCheck: Boolean,
    val bikjang: Boolean,
    val legalMoveCount: Int,
    val repetitionCount: Int,
    val points: MaterialPoints,
) {
    val isOver: Boolean get() = end != GameEnd.ONGOING
}

/** Game-level rules on top of move generation (port of `engine/rules.py`). */
object Rules {
    const val MAX_PLIES = 400
    const val HAN_BONUS_POINTS = 1.5

    /** 점수제: 차13 포7 마5 상3 사3 졸2. */
    fun pointValue(type: Int): Int = when (type) {
        KING -> 0; GUARD -> 3; CHARIOT -> 13; CANNON -> 7; HORSE -> 5; ELEPHANT -> 3; PAWN -> 2; else -> 0
    }

    fun materialPoints(pos: Position): MaterialPoints {
        var cho = 0.0
        var han = HAN_BONUS_POINTS
        for (p in pos.board) if (p != 0) {
            val v = pointValue(Piece.typeOf(p)).toDouble()
            if (Piece.colorOf(p) == CHO) cho += v else han += v
        }
        return MaterialPoints(cho, han)
    }

    fun pointsWinner(pos: Position): Int? {
        val mp = materialPoints(pos)
        return when { mp.cho > mp.han -> CHO; mp.han > mp.cho -> HAN; else -> null }
    }

    /** The side that just moved received a bikjang and left the kings facing. */
    fun bikjangUnresolved(pos: Position): Boolean = pos.bikjang && pos.prevBikjang()

    /** Non-mate ending for this position, or null. */
    fun ruleEnding(pos: Position, rules: RuleConfig): Pair<GameEnd, Int?>? {
        if (pos.passes >= 2) {
            return if (rules.bikjang == BikjangRule.FORCED) GameEnd.PASSES_POINTS to pointsWinner(pos)
                   else GameEnd.DRAW_PASSES to null
        }
        if (bikjangUnresolved(pos)) {
            when (rules.bikjang) {
                BikjangRule.DRAW -> return GameEnd.DRAW_BIKJANG to null
                BikjangRule.POINTS, BikjangRule.FORCED -> return GameEnd.BIKJANG_POINTS to pointsWinner(pos)
                BikjangRule.OFF -> {}
            }
        }
        if (rules.repetition == RepetitionRule.DRAW && pos.repetitionCount() >= 3) return GameEnd.DRAW_REPETITION to null
        if (pos.ply >= rules.maxPlies) return GameEnd.DRAW_MOVE_LIMIT to null
        return null
    }

    fun gameStatus(pos: Position, rules: RuleConfig = RuleConfig.DEFAULT, legal: List<Int>? = null): GameStatus {
        val inCheck = pos.inCheck()
        val moves = legal ?: MoveGen.legalMoves(pos)
        val pts = materialPoints(pos)
        if (inCheck && moves.isEmpty())
            return GameStatus(GameEnd.CHECKMATE, pos.side xor 1, true, false, 0, pos.repetitionCount(), pts)
        val end = ruleEnding(pos, rules)
        if (end != null)
            return GameStatus(end.first, end.second, inCheck, pos.bikjang, moves.size, pos.repetitionCount(), pts)
        return GameStatus(GameEnd.ONGOING, null, inCheck, pos.bikjang, moves.size, pos.repetitionCount(), pts)
    }

    /** Rebuilds a position from a start FEN and move strings, validating every move under [rules]. */
    fun replay(startFen: String?, moves: List<String>, rules: RuleConfig = RuleConfig.DEFAULT): Position {
        val pos = if (startFen != null) Position.fromFen(startFen) else Position.initial()
        rules.apply(pos)
        for ((i, text) in moves.withIndex()) {
            val m = Notation.parseMove(text)
            if (!MoveGen.legalMoves(pos).contains(m)) throw IllegalArgumentException("illegal move #${i + 1}: $text")
            pos.makeMove(m)
        }
        return pos
    }
}
