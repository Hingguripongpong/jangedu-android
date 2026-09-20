package com.example.janggiai.engine.fsf

import com.example.janggiai.engine.uci.UciTransport

/** [UciTransport] over the in-process JNI engine host. */
class JniUciTransport : UciTransport {
    override fun start(): Boolean { FairyStockfishNative.ensureLoaded(); return FairyStockfishNative.nativeStart() }
    override fun send(line: String) = FairyStockfishNative.nativeSend(line)
    override fun readLine(timeoutMs: Int): String? = FairyStockfishNative.nativeReadLine(timeoutMs)
    override fun isRunning(): Boolean = FairyStockfishNative.nativeIsRunning()
    override fun shutdown(timeoutMs: Int): Boolean = FairyStockfishNative.nativeShutdown(timeoutMs)
    override fun engineInfo(): String = FairyStockfishNative.nativeEngineInfo()
}
