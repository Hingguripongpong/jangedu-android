package com.example.janggiai.ui.common

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide foreground flag, driven by MainActivity.onStart/onStop.  ViewModels observe it to
 * stop the engine while the app is in the background (battery/heat) and to resume afterwards.
 */
object AppForeground {
    private val _state = MutableStateFlow(false)
    val state: StateFlow<Boolean> = _state.asStateFlow()
    fun set(foreground: Boolean) { _state.value = foreground }
}
