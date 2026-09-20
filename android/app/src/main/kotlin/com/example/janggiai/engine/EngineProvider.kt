package com.example.janggiai.engine

import com.example.janggiai.engine.api.JanggiEngine
import com.example.janggiai.engine.fsf.FairyStockfishEngine
import com.example.janggiai.engine.fsf.JniUciTransport
import com.example.janggiai.game.WinProbabilityModel

/**
 * One engine instance per process, created lazily on first use (Fairy-Stockfish keeps its hash
 * table across positions, and the native thread is expensive to start).  Swap the factory to plug
 * in another [JanggiEngine] implementation.
 */
class EngineProvider(private val winModel: WinProbabilityModel, private val factory: (WinProbabilityModel) -> JanggiEngine = { FairyStockfishEngine(JniUciTransport(), it) }) {
    @Volatile private var engine: JanggiEngine? = null

    @Synchronized fun get(): JanggiEngine = engine ?: factory(winModel).also { engine = it }

    @Synchronized fun close() { engine?.close(); engine = null }
}
