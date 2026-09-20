package com.example.janggiai.game

import com.example.janggiai.engine.api.AnalysisLimits
import com.example.janggiai.engine.api.AnalysisSnapshot
import com.example.janggiai.engine.api.EnginePosition
import com.example.janggiai.engine.api.JanggiEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/** What the UI shows for the current analysis. */
data class AnalysisUiState(
    val running: Boolean = false,
    val snapshot: AnalysisSnapshot? = null,
    /** State key of the position the snapshot belongs to (for stale checks by the UI). */
    val stateKey: String? = null,
    val error: String? = null,
)

/**
 * Drives [JanggiEngine.analyze] for the position on screen.
 *
 *  * Every request gets a fresh, monotonically increasing `analysisId`; the collected snapshots are
 *    dropped unless their id is still the current one, so a result of a superseded search can never
 *    overwrite the UI of a newer position (the id travels inside the snapshot, so this works across
 *    the engine/coroutine boundary).
 *  * A new request cancels the running collection (which sends `stop` to the engine) and waits a
 *    short debounce so rapid move/undo sequences start one search, not five.
 *  * Finished analyses are cached by position *state key* (not FEN), see [Position.stateKey].
 */
class AnalysisController(
    private val engine: JanggiEngine,
    private val scope: CoroutineScope,
    private val debounceMs: Long = 150,
    private val cacheSize: Int = 64,
) {
    private val _state = MutableStateFlow(AnalysisUiState())
    val state: StateFlow<AnalysisUiState> = _state.asStateFlow()

    private val ids = AtomicLong(0)
    @Volatile private var currentId = 0L
    private var job: Job? = null
    private val cache = object : LinkedHashMap<String, AnalysisSnapshot>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AnalysisSnapshot>?): Boolean = size > cacheSize
    }

    val currentAnalysisId: Long get() = currentId

    fun request(position: EnginePosition, limits: AnalysisLimits, stateKey: String, useCache: Boolean = true) {
        val id = ids.incrementAndGet()
        currentId = id
        job?.cancel()
        val cached = if (useCache) synchronized(cache) { cache[stateKey] } else null
        // Copy into a local: `limits` comes from the :engine module, and Kotlin refuses to smart-cast a nullable
        // public property declared in another module (compiles in a single-module kotlinc run, fails under Gradle).
        val budgetMs: Long? = limits.timeMs
        if (cached != null && cached.final && !cached.aborted && (budgetMs == null || cached.timeMs >= budgetMs * 0.8)) {
            _state.value = AnalysisUiState(running = false, snapshot = cached.copy(analysisId = id), stateKey = stateKey)
            return
        }
        _state.value = AnalysisUiState(running = true, snapshot = cached?.copy(analysisId = id), stateKey = stateKey)
        job = scope.launch {
            delay(debounceMs)
            if (currentId != id) return@launch
            engine.analyze(position, limits, id)
                .catch { e -> if (currentId == id) _state.value = AnalysisUiState(false, _state.value.snapshot, stateKey, e.message ?: e.toString()) }
                .collect { snap ->
                    if (snap.analysisId != currentId) return@collect          // stale: a newer request exists
                    if (snap.final && !snap.aborted && snap.error == null && snap.moves.isNotEmpty())
                        synchronized(cache) { cache[stateKey] = snap }
                    _state.value = AnalysisUiState(running = !snap.final, snapshot = snap, stateKey = stateKey, error = snap.error)
                }
        }
    }

    /** Stops the running analysis; the last snapshot stays on screen. */
    fun stop() {
        currentId = ids.incrementAndGet()
        job?.cancel()
        job = null
        engine.stop()
        _state.value = _state.value.copy(running = false)
    }

    fun clear() { stop(); _state.value = AnalysisUiState() }
}
