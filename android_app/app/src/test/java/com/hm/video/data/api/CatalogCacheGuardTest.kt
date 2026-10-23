package com.hm.video.data.api

import android.app.Application
import com.google.gson.reflect.TypeToken
import com.hm.video.data.cache.CachePolicy
import com.hm.video.data.cache.MetadataCache
import com.hm.video.data.model.PageResult
import com.hm.video.data.model.WorkItem
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23], application = Application::class)
class CatalogCacheGuardTest {
    @Rule @JvmField val temp = TemporaryFolder()
    private val type = object : TypeToken<PageResult>() {}.type
    private val memory = ConcurrentHashMap<String, Any>()
    private lateinit var disk: MetadataCache
    private var previousCache: Any? = null
    private val cacheField = XingfanRepository::class.java.getDeclaredField("metadataCache").apply { isAccessible = true }

    @Before
    fun setUp() {
        disk = MetadataCache(temp.root)
        previousCache = cacheField.get(null)
        cacheField.set(null, disk)
    }

    @After
    fun tearDown() {
        cacheField.set(null, previousCache)
    }

    @Test
    fun emptyColdResultIsShownButNotPersisted() {
        val empty = PageResult(emptyList(), 1, 1)
        assertTrue(load(empty, force = true).items.isEmpty())
        assertTrue(memory.isEmpty())
        assertNull(disk.get<PageResult>("guard", type))
    }

    @Test
    fun emptyResponseCannotOverwriteGoodMemoryOrDisk() {
        val good = PageResult(listOf(WorkItem("测试", "/dianying/test/", "/p.jpg")), 1, 1)
        load(good, force = true)
        load(PageResult(emptyList(), 1, 1), force = true)
        assertEquals(good, disk.get<PageResult>("guard", type)!!.value)
        assertEquals(good, load(PageResult(emptyList(), 1, 1), force = false))
    }

    /** Exercise the real cache coordinator without adding production networking/test hooks. */
    private fun load(value: PageResult, force: Boolean): PageResult {
        val resultConstructor = XingfanRepository::class.java.declaredClasses
            .first { it.simpleName == "NetworkResult" }.declaredConstructors
            .first { it.parameterCount == 4 }.apply { isAccessible = true }
        val fetch: (String?, String?) -> Any = { _, _ -> resultConstructor.newInstance(value, null, null, false) }
        val cacheNonEmpty: (PageResult) -> Boolean = { it.items.isNotEmpty() }
        val method = XingfanRepository::class.java.declaredMethods.first { it.name == "loadWithCache" }
            .apply { isAccessible = true }
        return method.invoke(XingfanRepository, memory, "guard", type, CachePolicy.SEARCH, force, cacheNonEmpty, fetch) as PageResult
    }
}
