package com.devcompanion.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Persists recently visited URLs across app restarts.
 * Mirrors the in-memory urlHistory from WebViewDebugger but survives process death.
 */
class UrlHistoryStore(context: Context) {

    private val prefs = context.getSharedPreferences("url_history", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val key = "url_list"
    private val maxItems = 50

    fun getUrls(): List<String> {
        val json = prefs.getString(key, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson<List<String>>(json, type)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addUrl(url: String): List<String> {
        val current = getUrls().toMutableList()
        // Move to front if exists, otherwise prepend
        current.remove(url)
        current.add(0, url)
        // Trim to max
        val trimmed = current.take(maxItems)
        prefs.edit().putString(key, gson.toJson(trimmed)).commit()
        return trimmed
    }

    fun clear() {
        prefs.edit().remove(key).commit()
    }
}