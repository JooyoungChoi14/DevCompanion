package com.devcompanion.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

data class Bookmark(
    val id: String = UUID.randomUUID().toString(),
    val title: String?,
    val url: String?,
    val faviconUrl: String? = null,
    val addedAt: Long = System.currentTimeMillis()
)

class BookmarksStore(context: Context) {

    private val prefs = context.getSharedPreferences("bookmarks", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val key = "bookmarks_list"

    fun getBookmarks(): List<Bookmark> {
        val json = prefs.getString(key, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<Bookmark>>() {}.type
            gson.fromJson<List<Bookmark>>(json, type)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addBookmark(bookmark: Bookmark): Bookmark {
        val list = getBookmarks().toMutableList()
        // Avoid duplicate URLs
        if (bookmark.url != null && list.none { it.url == bookmark.url }) {
            list.add(bookmark)
            saveList(list)
        }
        return bookmark
    }

    fun removeBookmark(id: String) {
        val list = getBookmarks().toMutableList()
        list.removeAll { it.id == id }
        saveList(list)
    }

    private fun saveList(list: List<Bookmark>) {
        prefs.edit().putString(key, gson.toJson(list)).apply()
    }
}