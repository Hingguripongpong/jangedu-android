package com.example.janggiai.ui.board

/** A candidate move to show on the board with its win-rate label (analysis mode). */
data class BoardCandidate(val move: Int, val rank: Int, val label: String, val winRate: Double?)

/** Everything drawn on top of the pieces. */
data class BoardOverlay(
    val selected: Int? = null,
    val legalTargets: Set<Int> = emptySet(),
    val lastMove: Int? = null,
    /** Square of a king in check. */
    val checkSquare: Int? = null,
    val candidates: List<BoardCandidate> = emptyList(),
    /** Principal variation to draw as arrows (first move highlighted). */
    val pv: List<Int> = emptyList(),
    /** A move drawn faintly with a dashed line (e.g. the move actually played, next to the engine's best). */
    val ghostMove: Int? = null,
    /** Moves whose badge shows the win rate; the others show only the rank number. */
    val emphasised: Set<Int> = emptySet(),
)
