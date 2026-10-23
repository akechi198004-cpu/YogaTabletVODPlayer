package com.hm.video.data.storage

import android.app.Application
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23], application = Application::class)
class UserStorageManagerTest {
    private lateinit var storage: UserStorageManager

    @Before
    fun setUp() {
        RuntimeEnvironment.getApplication().getSharedPreferences("hm_video_user_data", 0).edit().clear().commit()
        storage = UserStorageManager(RuntimeEnvironment.getApplication())
    }

    @Test
    fun searchHistorySurvivesNewStorageInstanceAndCanBeCleared() {
        storage.recordSearch(" 三体 ")
        val reopened = UserStorageManager(RuntimeEnvironment.getApplication())
        assertEquals(listOf("三体"), reopened.getSearchHistory())
        reopened.clearSearchHistory()
        assertTrue(storage.getSearchHistory().isEmpty())
    }

    @Test
    fun searchHistoryMovesRepeatedQueryToFrontAndKeepsTenEntries() {
        repeat(12) { storage.recordSearch("作品$it") }
        storage.recordSearch("作品5")
        storage.recordSearch("  ")
        assertEquals(10, storage.getSearchHistory().size)
        assertEquals("作品5", storage.getSearchHistory().first())
        assertEquals(1, storage.getSearchHistory().count { it == "作品5" })
    }

    @Test
    fun corruptSearchHistoryCanRecoverOnNextQuery() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("hm_video_user_data", 0)
        prefs.edit().putString("search_history", "{bad json").commit()
        assertTrue(storage.getSearchHistory().isEmpty())
        storage.recordSearch("新查询")
        assertEquals(listOf("新查询"), storage.getSearchHistory())
    }
}
