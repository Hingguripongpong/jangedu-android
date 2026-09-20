package com.example.janggiai.game

import com.example.janggiai.engine.api.EnginePosition

/**
 * Immutable view of a game: start FEN, the moves played (RC notation), the index currently shown
 * (for navigating through the record) and the rules.  Pure Kotlin so it is unit-testable and
 * trivially serialisable to a SavedStateHandle / JSON.
 */
data class GameSession(
    val startFen: String = Position.START_FEN,
    val moves: List<String> = emptyList(),
    /** Number of moves applied in the displayed position (== moves.size when at the tip). */
    val viewIndex: Int = 0,
    val rules: RuleConfig = RuleConfig.DEFAULT,
    val choSetup: String = Position.DEFAULT_SETUP,
    val hanSetup: String = Position.DEFAULT_SETUP,
) {
    init { require(viewIndex in 0..moves.size) { "viewIndex out of range" } }

    val atTip: Boolean get() = viewIndex == moves.size
    val canUndo: Boolean get() = moves.isNotEmpty()

    /** Position shown on the board (moves[0 until viewIndex] applied). */
    val position: Position by lazy { Rules.replay(startFen, moves.subList(0, viewIndex), rules) }

    val status: GameStatus by lazy { Rules.gameStatus(position, rules) }
    val legalMoves: List<Int> by lazy { if (status.isOver) emptyList() else MoveGen.legalMoves(position) }
    val lastMove: Int? get() = if (viewIndex == 0) null else Notation.parseMove(moves[viewIndex - 1])

    fun enginePosition(): EnginePosition = EnginePosition(startFen, moves.subList(0, viewIndex), rules)

    fun legalTargets(from: Int): List<Int> = legalMoves.filter { it != Move.PASS && Move.from(it) == from }.map { Move.to(it) }

    /** Plays [move] from the displayed position; moves after the view index are discarded (a new branch). */
    fun play(move: Int): GameSession {
        require(legalMoves.contains(move)) { "illegal move ${Notation.moveToString(move)}" }
        val kept = moves.subList(0, viewIndex) + Notation.moveToString(move)
        return copy(moves = kept, viewIndex = kept.size)
    }

    fun undo(): GameSession = if (moves.isEmpty()) this else copy(moves = moves.dropLast(1), viewIndex = minOf(viewIndex, moves.size - 1))
    fun goTo(index: Int): GameSession = copy(viewIndex = index.coerceIn(0, moves.size))
    fun first(): GameSession = goTo(0)
    fun last(): GameSession = goTo(moves.size)
    fun previous(): GameSession = goTo(viewIndex - 1)
    fun next(): GameSession = goTo(viewIndex + 1)

    /** Move list with pretty notation for display. */
    fun moveTexts(): List<String> {
        val pos = Rules.replay(startFen, emptyList(), rules)
        val out = ArrayList<String>(moves.size)
        for (t in moves) { val m = Notation.parseMove(t); out += Notation.describe(pos.board, m); pos.makeMove(m) }
        return out
    }

    companion object {
        fun newGame(rules: RuleConfig, choSetup: String = Position.DEFAULT_SETUP, hanSetup: String = Position.DEFAULT_SETUP): GameSession {
            val fen = Position.initial(choSetup, hanSetup).toFen()
            return GameSession(startFen = fen, rules = rules, choSetup = choSetup, hanSetup = hanSetup)
        }

        /** Validates [fen] (throws IllegalArgumentException) and starts a session there. */
        fun fromFen(fen: String, rules: RuleConfig): GameSession = GameSession(startFen = Position.fromFen(fen).toFen(), rules = rules)
    }
}
