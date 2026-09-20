package com.example.janggiai.engine.uci

/**
 * Line-oriented transport to a UCI engine.  The JNI implementation is
 * [com.example.janggiai.engine.fsf.JniUciTransport]; tests use a scripted fake.
 * All methods must be thread-safe: `send` is called from the UI thread for `stop`.
 */
interface UciTransport {
    fun start(): Boolean
    fun send(line: String)
    /** Next line or null on timeout (also null when the engine is not running). */
    fun readLine(timeoutMs: Int): String?
    fun isRunning(): Boolean
    fun shutdown(timeoutMs: Int): Boolean
    fun engineInfo(): String
}
