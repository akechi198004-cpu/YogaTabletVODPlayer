package com.hm.video.data.storage

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.hm.video.data.model.FavoriteItem
import com.hm.video.data.model.HistoryItem

class UserStorageManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("hm_video_user_data", Context.MODE_PRIVATE)
    private val gson = Gson()

    private val favKey = "favorites_list"
    private val histKey = "history_list"
    private val searchKey = "search_history"

    fun getSearchHistory(): List<String> {
        val json = prefs.getString(searchKey, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<String?>>() {}.type
            gson.fromJson<List<String?>>(json, type).orEmpty()
                .filterNotNull().filter { it.isNotBlank() }.distinct().take(10)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun recordSearch(keyword: String) {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return
        val history = (listOf(trimmed) + getSearchHistory()).distinct().take(10)
        prefs.edit().putString(searchKey, gson.toJson(history)).apply()
    }

    fun clearSearchHistory() {
        prefs.edit().remove(searchKey).apply()
    }

    fun getFavorites(): MutableList<FavoriteItem> {
        val json = prefs.getString(favKey, null) ?: return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<FavoriteItem>>() {}.type
            gson.fromJson(json, type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun isFavorite(url: String): Boolean {
        return getFavorites().any { it.url == url }
    }

    fun toggleFavorite(item: FavoriteItem): Boolean {
        val list = getFavorites()
        val index = list.indexOfFirst { it.url == item.url }
        val added: Boolean
        if (index >= 0) {
            list.removeAt(index)
            added = false
        } else {
            list.add(0, item)
            if (list.size > 200) list.removeAt(list.size - 1)
            added = true
        }
        prefs.edit().putString(favKey, gson.toJson(list)).apply()
        return added
    }

    fun removeFavorite(url: String) {
        val list = getFavorites()
        list.removeAll { it.url == url }
        prefs.edit().putString(favKey, gson.toJson(list)).apply()
    }

    fun clearFavorites() {
        prefs.edit().remove(favKey).apply()
    }

    fun getHistory(): MutableList<HistoryItem> {
        val json = prefs.getString(histKey, null) ?: return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<HistoryItem>>() {}.type
            gson.fromJson(json, type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun getHistoryItem(url: String, episodeUrl: String? = null): HistoryItem? {
        return getHistory().firstOrNull { it.url == url && (episodeUrl.isNullOrEmpty() || it.episodeUrl == episodeUrl) }
    }

    fun recordHistory(item: HistoryItem) {
        val list = getHistory()
        val existing = list.firstOrNull { it.url == item.url && it.episodeUrl == item.episodeUrl }
        if (existing != null && item.progressSec <= 0) {
            item.progressSec = existing.progressSec
            item.durationSec = existing.durationSec
        }
        list.removeAll { it.url == item.url }
        list.add(0, item)
        if (list.size > 100) list.removeAt(list.size - 1)
        prefs.edit().putString(histKey, gson.toJson(list)).apply()
    }

    fun updateProgress(url: String, episodeUrl: String, episodeNo: Int, progressSec: Long, durationSec: Long) {
        val list = getHistory()
        val item = list.firstOrNull { it.url == url && it.episodeUrl == episodeUrl && it.episodeNo == episodeNo }
        if (item != null) {
            item.progressSec = progressSec
            if (durationSec > 0) item.durationSec = durationSec
            item.playedAt = System.currentTimeMillis()
            prefs.edit().putString(histKey, gson.toJson(list)).apply()
        }
    }

    fun removeHistory(url: String) {
        val list = getHistory()
        list.removeAll { it.url == url }
        prefs.edit().putString(histKey, gson.toJson(list)).apply()
    }

    fun clearHistory() {
        prefs.edit().remove(histKey).apply()
    }
}
