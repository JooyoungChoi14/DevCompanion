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

    /** Chat overlay height as fraction of screen (0.3–0.95). Saved across sessions. */
    private const val KEY_CHAT_SHEET_FRACTION = "chat_sheet_fraction"
    private const val DEFAULT_CHAT_SHEET_FRACTION = 0.55f

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

    /** Last saved chat sheet height fraction. Returns [DEFAULT_CHAT_SHEET_FRACTION] if unset. */
    var chatSheetFraction: Float
        get() = requirePrefs().getFloat(KEY_CHAT_SHEET_FRACTION, DEFAULT_CHAT_SHEET_FRACTION)
        set(value) {
            val clamped = value.coerceIn(0.3f, 0.95f)
            requirePrefs().edit().putFloat(KEY_CHAT_SHEET_FRACTION, clamped).apply()
        }
}