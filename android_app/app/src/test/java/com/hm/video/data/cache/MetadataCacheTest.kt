package com.hm.video.data.cache

import com.google.gson.reflect.TypeToken
import com.hm.video.data.model.WorkItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.lang.reflect.Type

class MetadataCacheTest {

    @Rule
    @JvmField
    val temp = TemporaryFolder()

    private val listType: Type = object : TypeToken<List<WorkItem>>() {}.type
    private val pageType: Type = object : TypeToken<com.hm.video.data.model.PageResult>() {}.type
    private val detailType: Type = object : TypeToken<com.hm.video.data.model.WorkDetail>() {}.type

    @Test
    fun roundTripsNestedCatalogModels() {
        val cache = MetadataCache(temp.root, formatVersion = 1)
        val page = com.hm.video.data.model.PageResult(
            items = listOf(WorkItem("影片", "/v/1.html", "/p.jpg", category = "电影", year = "2025", score = "8.1")),
            page = 2,
            totalPages = 10,
            totalItems = 231,
            filters = com.hm.video.data.model.FilterOptions(
                types = listOf(com.hm.video.data.model.ChipOption("全部", "all")),
                areas = listOf(com.hm.video.data.model.ChipOption("大陆", "dalu"))
            )
        )
        val detail = com.hm.video.data.model.WorkDetail(
            title = "剧集",
            url = "/v/1.html",
            poster = "/p.jpg",
            totalEpisodes = 24,
            lines = listOf(
                com.hm.video.data.model.WorkLine(
                    id = 1,
                    name = "线路一",
                    episodes = listOf(com.hm.video.data.model.Episode(1, "第1集", "/v/1-1-1.html"))
                )
            )
        )

        cache.put("page", page, pageType, etag = "\"p\"")
        cache.put("detail", detail, detailType)

        val restoredPage = cache.get<com.hm.video.data.model.PageResult>("page", pageType)!!.value
        assertEquals(2, restoredPage.page)
        assertEquals(231, restoredPage.totalItems)
        assertEquals("电影", restoredPage.items[0].category)
        assertEquals("dalu", restoredPage.filters!!.areas[0].value)
        assertEquals("大陆", restoredPage.filters!!.areas[0].label)

        val restoredDetail = cache.get<com.hm.video.data.model.WorkDetail>("detail", detailType)!!.value
        assertEquals(24, restoredDetail.totalEpisodes)
        assertEquals("线路一", restoredDetail.lines[0].name)
        assertEquals("/v/1-1-1.html", restoredDetail.lines[0].episodes[0].url)
    }

    @Test
    fun roundTripsCatalogPayloadsWithValidators() {
        val cache = MetadataCache(temp.root, formatVersion = 1)
        val items = listOf(
            WorkItem(title = "示例影片", url = "/dianying/1.html", poster = "/p1.jpg", year = "2026"),
            WorkItem(title = "第二部", url = "/dianying/2.html", poster = "/p2.jpg")
        )

        assertTrue(cache.put("list:/dianying/", items, listType, etag = "\"abc\"", lastModified = "Wed, 21 Oct 2026 07:28:00 GMT"))

        val loaded = cache.get<List<WorkItem>>("list:/dianying/", listType)
        assertNotNull(loaded)
        assertEquals(2, loaded!!.value.size)
        assertEquals("示例影片", loaded.value[0].title)
        assertEquals("/p2.jpg", loaded.value[1].poster)
        assertEquals("\"abc\"", loaded.etag)
        assertEquals("Wed, 21 Oct 2026 07:28:00 GMT", loaded.lastModified)
        assertTrue(loaded.ageMs() < 5_000)
    }

    @Test
    fun touchRefreshesFreshnessWithoutLosingPayload() {
        val cache = MetadataCache(temp.root, formatVersion = 1)
        val items = listOf(WorkItem(title = "旧片", url = "/1.html", poster = "/1.jpg"))
        cache.put("list:old", items, listType, savedAt = System.currentTimeMillis() - 60_000)

        val before = cache.get<List<WorkItem>>("list:old", listType)!!
        assertTrue(before.ageMs() >= 59_000)

        cache.touch("list:old", etag = "\"new\"")

        val after = cache.get<List<WorkItem>>("list:old", listType)!!
        assertEquals("旧片", after.value[0].title)
        assertEquals("\"new\"", after.etag)
        assertTrue(after.ageMs() < 5_000)
    }

    @Test
    fun corruptEntryIsDiscardedInsteadOfCrashing() {
        val cache = MetadataCache(temp.root, formatVersion = 1)
        cache.put("key", listOf(WorkItem("t", "/u", "/p")), listType)

        // Corrupt the persisted payload behind the cache's back.
        val metaDir = java.io.File(temp.root, "meta")
        val entryFile = metaDir.walkTopDown().firstOrNull { it.isFile && it.name.endsWith(".bin") }
        assertNotNull(entryFile)
        entryFile!!.writeText("{not valid json")

        assertNull(cache.get<List<WorkItem>>("key", listType))
    }

    @Test
    fun formatVersionChangeWipesOldEntries() {
        val first = MetadataCache(temp.root, formatVersion = 1)
        first.put("key", listOf(WorkItem("t", "/u", "/p")), listType)
        assertNotNull(first.get<List<WorkItem>>("key", listType))

        val upgraded = MetadataCache(temp.root, formatVersion = 2)

        assertNull(upgraded.get<List<WorkItem>>("key", listType))
    }

    @Test
    fun installationUpgradeWipesOldEntriesEvenWithSameFormat() {
        val first = MetadataCache(temp.root, formatVersion = 1, installationTag = "6")
        first.put("key", listOf(WorkItem("t", "/u", "/p")), listType)
        assertNotNull(first.get<List<WorkItem>>("key", listType))

        val upgraded = MetadataCache(temp.root, formatVersion = 1, installationTag = "7")

        assertNull(upgraded.get<List<WorkItem>>("key", listType))
    }
}
