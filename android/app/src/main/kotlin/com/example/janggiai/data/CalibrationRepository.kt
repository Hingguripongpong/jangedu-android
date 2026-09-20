package com.example.janggiai.data

import android.content.Context
import com.example.janggiai.game.LogisticWinProbability
import com.example.janggiai.game.WinProbabilityModel

/**
 * Loads `assets/calibration.json` (the Python `Calibrator.to_json()` format, produced by
 * `python cli.py fit-calibration`) when present; otherwise the uncalibrated logistic prior
 * (K = 300 for Fairy-Stockfish centipawns).
 */
class CalibrationRepository(private val context: Context) {
    fun load(): WinProbabilityModel {
        val text = runCatching { context.assets.open("calibration.json").bufferedReader().use { it.readText() } }.getOrNull()
        return text?.let { LogisticWinProbability.fromCalibrationJson(it) } ?: LogisticWinProbability()
    }
}
