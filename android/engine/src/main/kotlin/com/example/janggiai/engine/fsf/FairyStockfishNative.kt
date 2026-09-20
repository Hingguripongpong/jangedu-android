package com.example.janggiai.engine.fsf

/**
 * JNI surface of `libjanggi_engine.so` (engine/src/main/cpp/janggi_engine_jni.cpp).
 *
 * The engine runs in-process on its own native thread; these calls only move UCI text lines
 * across the boundary.  All methods are `@JvmStatic` so the native symbols take a `jclass`
 * (see the C++ file for the exact symbol names, which embed this class's package).
 *
 * Keep this class at `com.example.janggiai.engine.fsf.FairyStockfishNative` even if the
 * application id changes, or rename the C++ symbols together with it.
 */
object FairyStockfishNative {
    const val LIBRARY_NAME = "janggi_engine"

    @Volatile
    private var loaded = false

    /** Loads the native library once; throws [UnsatisfiedLinkError] if the ABI is unsupported. */
    @Synchronized
    fun ensureLoaded() {
        if (!loaded) {
            System.loadLibrary(LIBRARY_NAME)
            loaded = true
        }
    }

    /** Starts the engine thread (no-op when running). Returns false if the thread could not start. */
    @JvmStatic external fun nativeStart(): Boolean

    /** Queues one UCI command (no newline). */
    @JvmStatic external fun nativeSend(line: String)

    /** Next output line, or null on timeout / engine not running. */
    @JvmStatic external fun nativeReadLine(timeoutMs: Int): String?

    @JvmStatic external fun nativeIsRunning(): Boolean

    /** Sends quit and joins the engine thread; true if it ended within the timeout. */
    @JvmStatic external fun nativeShutdown(timeoutMs: Int): Boolean

    /** e.g. "Fairy-Stockfish 14 LB" */
    @JvmStatic external fun nativeEngineInfo(): String
}
