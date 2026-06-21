package com.devcompanion.debug

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holder to share the current [BrowserDebugger] instance with DevTools tabs and BridgeServer.
 * Uses StateFlow for reactive null-safety.
 */
object BrowserDebuggerHolder {
    private val _current = MutableStateFlow<BrowserDebugger?>(null)
    val currentFlow: StateFlow<BrowserDebugger?> = _current.asStateFlow()

    var current: BrowserDebugger?
        get() = _current.value
        set(value) { _current.value = value }

    fun clear() { _current.value = null }
}