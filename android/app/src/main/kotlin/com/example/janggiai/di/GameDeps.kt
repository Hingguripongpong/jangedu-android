package com.example.janggiai.di

import com.example.janggiai.coach.CoachComment
import com.example.janggiai.data.Settings
import com.example.janggiai.engine.api.JanggiEngine
import com.example.janggiai.game.GameSession
import kotlinx.coroutines.flow.StateFlow

/**
 * What a game screen needs from the app: settings, the shared engine and record saving.
 * [AppContainer] implements it; unit tests substitute a fake engine so the human → AI → coach flow can be
 * exercised on the JVM.
 */
interface GameDeps {
    val settingsFlow: StateFlow<Settings>
    fun updateSettings(transform: (Settings) -> Settings)
    val engine: JanggiEngine
    /** Persists a finished/ongoing game; returns the record title. */
    suspend fun saveGameRecord(session: GameSession, title: String, result: String, humanSide: Int?, coachComments: Map<Int, CoachComment>): String
}
