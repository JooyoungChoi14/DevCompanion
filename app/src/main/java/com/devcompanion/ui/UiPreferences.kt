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
    private const val DEFAULT_CHAT_SHEET_FRACTION = 0.75f

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

    /** Whether the user has accepted the privacy disclaimer. */
    private const val KEY_PRIVACY_ACCEPTED = "privacy_accepted"

    /** Whether auto-capture is enabled. Default: true. */
    private const val KEY_AUTO_CAPTURE_ENABLED = "auto_capture_enabled"
    private const val DEFAULT_AUTO_CAPTURE_ENABLED = true

    /** Last saved chat sheet height fraction. Returns [DEFAULT_CHAT_SHEET_FRACTION] if unset. */
    var chatSheetFraction: Float
        get() = requirePrefs().getFloat(KEY_CHAT_SHEET_FRACTION, DEFAULT_CHAT_SHEET_FRACTION)
        set(value) {
            val clamped = value.coerceIn(0.3f, 0.95f)
            requirePrefs().edit().putFloat(KEY_CHAT_SHEET_FRACTION, clamped).apply()
        }

    /** Whether the user has accepted the privacy disclaimer. */
    var privacyAccepted: Boolean
        get() = requirePrefs().getBoolean(KEY_PRIVACY_ACCEPTED, false)
        set(value) = requirePrefs().edit().putBoolean(KEY_PRIVACY_ACCEPTED, value).apply()

    /** Whether auto-capture (automatic page context capture) is enabled. */
    var autoCaptureEnabled: Boolean
        get() = requirePrefs().getBoolean(KEY_AUTO_CAPTURE_ENABLED, DEFAULT_AUTO_CAPTURE_ENABLED)
        set(value) = requirePrefs().edit().putBoolean(KEY_AUTO_CAPTURE_ENABLED, value).apply()
}