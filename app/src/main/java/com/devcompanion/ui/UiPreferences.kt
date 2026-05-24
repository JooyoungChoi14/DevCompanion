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
}