package com.devcompanion.ui

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Manages persistent UI preferences (non-sensitive).
 * Uses standard SharedPreferences — no encryption needed for UI settings.
 */
object UiPreferences {

    private const val TAG = "UiPreferences"
    private const val FILE_NAME = "devcompanion_ui_prefs"
    private const val KEY_LONG_PRESS_TIMEOUT_MS = "long_press_timeout_ms"

    /** Default long-press threshold for entering select mode (1.5 seconds). */
    const val DEFAULT_LONG_PRESS_TIMEOUT_MS = 1500L

    /** Minimum allowed value (300ms — shorter would make long-press nearly impossible to trigger). */
    const val MIN_LONG_PRESS_TIMEOUT_MS = 300L

    /** Maximum allowed value (5000ms — longer would feel unresponsive). */
    const val MAX_LONG_PRESS_TIMEOUT_MS = 5000L

    @Volatile
    private var prefs: SharedPreferences? = null

    @Synchronized
    fun initialize(context: Context) {
        if (prefs != null) return
        prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        Log.i(TAG, "UI preferences initialized")
    }

    private fun requirePrefs(): SharedPreferences {
        return prefs ?: error("UiPreferences not initialized — call initialize(context) first")
    }

    /** Long-press timeout in milliseconds for entering select mode. */
    var longPressTimeoutMs: Long
        get() = requirePrefs().getLong(KEY_LONG_PRESS_TIMEOUT_MS, DEFAULT_LONG_PRESS_TIMEOUT_MS)
            .coerceIn(MIN_LONG_PRESS_TIMEOUT_MS, MAX_LONG_PRESS_TIMEOUT_MS)
        set(value) {
            requirePrefs().edit()
                .putLong(KEY_LONG_PRESS_TIMEOUT_MS, value.coerceIn(MIN_LONG_PRESS_TIMEOUT_MS, MAX_LONG_PRESS_TIMEOUT_MS))
                .apply()
        }
}