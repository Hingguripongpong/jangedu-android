package com.example.janggiai.game

import kotlin.math.exp
import kotlin.math.ln

/**
 * Evaluation → win probability.  An engine score is *not* a win rate; this layer converts
 * centipawns into an estimated probability and can be replaced by a model fitted on real games
 * (the JSON schema is that of the Python `Calibrator`: `k`, `draw_margin`, `calibrated`, `samples`,
 * `source`).  Until a calibrated model is loaded the UI labels the value "예상 승률".
 */
interface WinProbabilityModel {
    /** P(win) for the side to move given [scoreCp] from its point of view. */
    fun winProbability(scoreCp: Int): Double
    /** (win, draw, loss) split; draw is 0 for the plain logistic model. */
    fun wdl(scoreCp: Int): Triple<Double, Double, Double>
    val calibrated: Boolean
    val description: String
}

/**
 * Logistic model `P = 1 / (1 + exp(-s / K))` with an optional draw margin (ordered logit).
 * Fairy-Stockfish's janggi centipawn scale is smaller than the Python engine's (a chariot ≈ 800 cp
 * instead of 1300), hence [FSF_DEFAULT_K] = 300 as in `engine/uci_engine.py`.
 */
class LogisticWinProbability(
    val k: Double = FSF_DEFAULT_K,
    val drawMargin: Double = 0.0,
    override val calibrated: Boolean = false,
    val samples: Int = 0,
    val source: String = "default prior (uncalibrated)",
) : WinProbabilityModel {
    override fun winProbability(scoreCp: Int): Double = sigmoid(scoreCp / k)

    override fun wdl(scoreCp: Int): Triple<Double, Double, Double> {
        if (drawMargin <= 0.0) { val w = winProbability(scoreCp); return Triple(w, 0.0, 1.0 - w) }
        val w = sigmoid((scoreCp - drawMargin) / k)
        val l = sigmoid((-scoreCp - drawMargin) / k)
        return Triple(w, maxOf(0.0, 1.0 - w - l), l)
    }

    fun scoreForProbability(p: Double): Double {
        val q = p.coerceIn(1e-9, 1 - 1e-9)
        return k * ln(q / (1 - q))
    }

    override val description: String get() = (if (calibrated) "Win Rate" else "Estimated Win Rate") + " (logistic K=${k.toInt()}, $source)"

    companion object {
        const val FSF_DEFAULT_K = 300.0

        fun sigmoid(x: Double): Double =
            if (x >= 0) { val z = exp(-x); 1.0 / (1.0 + z) } else { val z = exp(x); z / (1.0 + z) }

        /**
         * Parses the Python `Calibrator.to_json()` schema without a JSON library (the file is flat).
         * Returns null when the text is not a calibration record.
         */
        fun fromCalibrationJson(text: String): LogisticWinProbability? {
            fun num(key: String): Double? = Regex("\"$key\"\\s*:\\s*(-?[0-9.]+(?:[eE]-?\\d+)?)").find(text)?.groupValues?.get(1)?.toDoubleOrNull()
            fun bool(key: String): Boolean? = Regex("\"$key\"\\s*:\\s*(true|false)").find(text)?.groupValues?.get(1)?.toBoolean()
            fun str(key: String): String? = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(text)?.groupValues?.get(1)
            val k = num("k") ?: return null
            if (k <= 0) return null
            return LogisticWinProbability(
                k = k,
                drawMargin = num("draw_margin") ?: 0.0,
                calibrated = bool("calibrated") ?: false,
                samples = (num("samples") ?: 0.0).toInt(),
                source = str("source") ?: "calibration.json",
            )
        }
    }
}
