package com.example.janggiai.di

import android.content.Context
import com.example.janggiai.coach.CoachComment
import com.example.janggiai.data.CalibrationRepository
import com.example.janggiai.data.GameRecordRepository
import com.example.janggiai.data.Settings
import com.example.janggiai.data.SettingsRepository
import com.example.janggiai.engine.EngineProvider
import com.example.janggiai.engine.api.JanggiEngine
import com.example.janggiai.game.GameSession
import com.example.janggiai.game.WinProbabilityModel
import kotlinx.coroutines.flow.StateFlow

/** Manual dependency container (the app is small; no DI framework needed). */
class AppContainer(context: Context) : GameDeps {
    val settings = SettingsRepository(context)
    val records = GameRecordRepository(context)
    val winModel: WinProbabilityModel = CalibrationRepository(context).load()
    val engines = EngineProvider(winModel)

    override val settingsFlow: StateFlow<Settings> get() = settings.settings
    override fun updateSettings(transform: (Settings) -> Settings) = settings.update(transform)
    override val engine: JanggiEngine get() = engines.get()
    override suspend fun saveGameRecord(session: GameSession, title: String, result: String, humanSide: Int?, coachComments: Map<Int, CoachComment>): String =
        records.save(session, title, result, humanSide, coachComments).title
}
