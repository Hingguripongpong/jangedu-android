package com.example.janggiai.coach

import com.example.janggiai.game.Move

/** Which moves the coach reviews: only the human's own, non-pass moves, and only when the coach is on. */
object CoachPolicy {
    fun reviews(moverSide: Int, humanSide: Int, coachEnabled: Boolean, move: Int): Boolean =
        coachEnabled && moverSide == humanSide && move != Move.PASS
}
