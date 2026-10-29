package com.hm.video.data.api

import android.content.Context
import android.util.Log
import com.google.gson.reflect.TypeToken
import com.hm.video.data.cache.CacheConfig
import com.hm.video.data.cache.CachePolicy
import com.hm.video.data.cache.MetadataCache
import com.hm.video.data.model.*
import java.io.File
import java.io.IOException
import java.lang.reflect.Type
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException

/**
 * Source repository with a three-level cache:
 *
 *  1. In-process parsed objects (fastest, lost when the app process dies).
 *  2. On-disk parsed objects (survives cold starts, works offline).
 *  3. The remote site, reached only when the disk copy is missing or stale.
 *
 * Stale entries are served immediately (stale-while-revalidate) and refreshed on a single
 * background thread. A failed refresh never replaces good cached data; a 404/410 does remove it,
 * because that means the work really disappeared upstream.
 *
 * Playback URLs are deliberately memory-only: they commonly contain short-lived tokens, so
 * persisting them would produce broken playback after a restart.
 */
object XingfanRepository {

    private const val TAG = "HMCache"

    private class CacheEntry<T>(val data: T, val expireAt: Long)

    private class NetworkResult<T>(
        val value: T?,
        val etag: String?,
        val lastModified: String?,
        val notModified: Boolean = false
    )

    private val homeCache = ConcurrentHashMap<String, CacheEntry<HomeData>>()
    private val listCache = ConcurrentHashMap<String, CacheEntry<PageResult>>()
    private val dailyCache = ConcurrentHashMap<String, CacheEntry<List<WorkItem>>>()
    private val topCache = ConcurrentHashMap<String, CacheEntry<TopBoards>>()
    private val detailCache = ConcurrentHashMap<String, CacheEntry<WorkDetail>>()
    private val playCache = ConcurrentHashMap<String, CacheEntry<PlayInfo>>()

    private const val TTL_PLAY = 30 * 60 * 1000L // 30 min

    private val typeHome: Type = object : TypeToken<HomeData>() {}.type
    private val typePage: Type = object : TypeToken<PageResult>() {}.type
    private val typeWorkItems: Type = object : TypeToken<List<WorkItem>>() {}.type
    private val typeTop: Type = object : TypeToken<TopBoards>() {}.type
    private val typeDetail: Type = object : TypeToken<WorkDetail>() {}.type

    @Volatile
    private var metadataCache: MetadataCache? = null

    private val refreshExecutor = java.util.concurrent.ThreadPoolExecutor(
        1,
        1,
        0L,
        java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.ArrayBlockingQueue<Runnable>(24),
        { runnable -> Thread(runnable, "hm-catalog-refresh").apply { isDaemon = true } },
        java.util.concurrent.ThreadPoolExecutor.AbortPolicy()
    )
    private val refreshing: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val lastRefreshAt = ConcurrentHashMap<String, Long>()

    /** Called once from the Application; cheap because DiskStore indexes lazily. */
    fun init(context: Context) {
        if (metadataCache != null) return
        synchronized(this) {
            if (metadataCache != null) return
            metadataCache = try {
                MetadataCache(
                    rootDir = File(context.cacheDir, CacheConfig.ROOT_DIR),
                    installationTag = appVersionTag(context)
                )
            } catch (error: Exception) {
                Log.w(TAG, "Local catalog cache unavailable; continuing memory-only", error)
                null
            }
        }
    }

    /**
     * Catalog entries are wiped when the APK version changes. This keeps a model change from ever
     * being parsed out of an older payload, even if a release forgets to bump [CacheConfig.FORMAT_VERSION].
     */
    private fun appVersionTag(context: Context): String {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                info.longVersionCode.toString()
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toString()
            }
        } catch (_: Exception) {
            ""
        }
    }

    /** Drops expired entries and enforces disk quotas. Call from a background thread. */
    fun trimDiskCache() {
        try {
            metadataCache?.trim()
        } catch (error: Exception) {
            Log.w(TAG, "Catalog cache trim failed", error)
        }
    }

    private fun <T> getCached(map: ConcurrentHashMap<String, CacheEntry<T>>, key: String): T? {
        val entry = map[key] ?: return null
        if (System.currentTimeMillis() > entry.expireAt) {
            map.remove(key)
            return null
        }
        return entry.data
    }

    private fun <T> putCache(map: ConcurrentHashMap<String, CacheEntry<T>>, key: String, data: T, ttl: Long) {
        map[key] = CacheEntry(data, System.currentTimeMillis() + ttl)
        val now = System.currentTimeMillis()
        map.entries.removeAll { it.value.expireAt <= now }
        while (map.size > 80) {
            val oldest = map.entries.minByOrNull { it.value.expireAt } ?: break
            map.remove(oldest.key, oldest.value)
        }
    }

    private fun <T> readDisk(key: String, type: Type): MetadataCache.Entry<T>? {
        return try {
            metadataCache?.get(key, type)
        } catch (error: Exception) {
            Log.w(TAG, "Ignoring unreadable cache entry $key", error)
            null
        }
    }

    private fun <T> writeDisk(
        key: String,
        type: Type,
        value: T,
        etag: String?,
        lastModified: String?
    ) {
        try {
            metadataCache?.put(key, value, type, etag, lastModified)
        } catch (error: Exception) {
            Log.w(TAG, "Could not persist cache entry $key", error)
        }
    }

    private fun removeDisk(key: String) {
        try {
            metadataCache?.remove(key)
        } catch (_: Exception) {
            // Best effort.
        }
    }

    /**
     * True when the origin explicitly says the content is gone. Such entries must not be served
     * from cache, otherwise a removed work would stay tappable forever.
     */
    private fun isGone(error: Throwable): Boolean {
        return error is HttpStatusException && (error.code == 404 || error.code == 410)
    }

    /**
     * A catalog page is safe to persist when it contains works, or when the parser confirmed a
     * trusted zero result (an explicit visible empty state or a zero total). Filter rows and
     * pagination alone are not proof: a site layout change can keep them while card parsing
     * fails, and that must not overwrite a previously good cache entry.
     */
    internal fun isCacheableCatalogPage(page: PageResult): Boolean {
        if (page.items.isNotEmpty()) return true
        return page.totalItems == 0
    }

    private fun <T> loadWithCache(
        memory: ConcurrentHashMap<String, CacheEntry<T>>,
        key: String,
        type: Type,
        policy: CachePolicy,
        forceRefresh: Boolean,
        shouldCache: (T) -> Boolean = { true },
        fetch: (etag: String?, lastModified: String?) -> NetworkResult<T>
    ): T {
        val now = System.currentTimeMillis()
        var diskEntry: MetadataCache.Entry<T>? = null

        if (!forceRefresh) {
            getCached(memory, key)?.let { return it }

            diskEntry = readDisk(key, type)
            val entry = diskEntry
            if (entry != null) {
                val age = entry.ageMs(now)
                if (policy.isFresh(age)) {
                    // A warm start renders straight from disk without touching the network.
                    // Never let the in-memory copy outlive the disk entry's fresh window.
                    val memoryTtl = minOf(policy.memoryTtlMs, (policy.freshMs - age).coerceAtLeast(1_000L))
                    putCache(memory, key, entry.value, memoryTtl)
                    return entry.value
                }
                if (policy.isUsable(age)) {
                    // Serve now, refresh silently, so the next visit shows the upstream changes.
                    putCache(memory, key, entry.value, policy.memoryTtlMs)
                    scheduleRefresh(memory, key, type, policy, entry.etag, entry.lastModified, shouldCache, fetch)
                    return entry.value
                }
            }
        }

        return try {
            val result = fetch(
                if (forceRefresh) null else diskEntry?.etag,
                if (forceRefresh) null else diskEntry?.lastModified
            )
            if (result.notModified && diskEntry != null) {
                val value = diskEntry.value
                if (shouldCache(value)) {
                    putCache(memory, key, value, policy.memoryTtlMs)
                    metadataCache?.touch(key, result.etag, result.lastModified)
                }
                value
            } else {
                val value = result.value
                    ?: throw IOException("远端返回空内容")
                if (shouldCache(value)) {
                    putCache(memory, key, value, policy.memoryTtlMs)
                    writeDisk(key, type, value, result.etag, result.lastModified)
                }
                value
            }
        } catch (error: Exception) {
            val stale = diskEntry
            if (!forceRefresh && stale != null && policy.isUsable(stale.ageMs(now)) && !isGone(error)) {
                Log.i(TAG, "Network failed, serving cached $key", error)
                putCache(memory, key, stale.value, policy.memoryTtlMs)
                stale.value
            } else {
                if (isGone(error)) {
                    memory.remove(key)
                    removeDisk(key)
                }
                throw error
            }
        }
    }

    private fun <T> scheduleRefresh(
        memory: ConcurrentHashMap<String, CacheEntry<T>>,
        key: String,
        type: Type,
        policy: CachePolicy,
        etag: String?,
        lastModified: String?,
        shouldCache: (T) -> Boolean,
        fetch: (etag: String?, lastModified: String?) -> NetworkResult<T>
    ) {
        val now = System.currentTimeMillis()
        val lastAttempt = lastRefreshAt[key]
        if (lastAttempt != null && now - lastAttempt < CacheConfig.MIN_REFRESH_INTERVAL_MS) return
        if (!refreshing.add(key)) return
        lastRefreshAt[key] = now
        if (lastRefreshAt.size > 512) {
            lastRefreshAt.entries.removeAll { now - it.value > CacheConfig.MIN_REFRESH_INTERVAL_MS * 15 }
        }
        try {
            refreshExecutor.execute {
                try {
                    val result = fetch(etag, lastModified)
                    if (result.notModified) {
                        metadataCache?.touch(key, result.etag, result.lastModified)
                    } else {
                        val value = result.value
                        if (value != null && shouldCache(value)) {
                            putCache(memory, key, value, policy.memoryTtlMs)
                            writeDisk(key, type, value, result.etag, result.lastModified)
                        }
                    }
                } catch (error: Exception) {
                    // Keep serving the cached copy; a later visit retries.
                    if (isGone(error)) {
                        memory.remove(key)
                        removeDisk(key)
                    }
                    Log.i(TAG, "Background refresh failed for $key", error)
                } finally {
                    refreshing.remove(key)
                }
            }
        } catch (_: RejectedExecutionException) {
            refreshing.remove(key)
        }
    }

    data class FetchedPage(val html: String, val finalUrl: String)

    fun fetchPageWithFinalUrl(
        url: String,
        referer: String? = null,
        scope: PageCallScope? = null
    ): FetchedPage {
        val targetUrl = XingfanClient.absUrl(url)
        val requestBuilder = okhttp3.Request.Builder().url(targetUrl)
            .header("User-Agent", XingfanConfig.USER_AGENT)
            .header("Referer", referer ?: "${XingfanConfig.SOURCE_BASE}/")
        // Same whole-call budget as catalog pages: the player-service response is a page too,
        // and its body used to be read after the old deadline had already been cancelled.
        return XingfanClient.executePageCall(
            XingfanClient.pageClient().newCall(requestBuilder.build()),
            scope
        ) { response ->
            if (!response.isSuccessful()) {
                throw HttpStatusException(response.code(), "HTTP ${response.code()} error fetching ${response.request().url()}")
            }
            FetchedPage(response.body()?.string().orEmpty(), response.request().url().toString())
        }
    }

    fun getHome(forceRefresh: Boolean = false, scope: PageCallScope? = null): HomeData {
        val key = "home"
        return loadWithCache(homeCache, key, typeHome, CachePolicy.HOME, forceRefresh,
            shouldCache = { it.hero.isNotEmpty() || it.recent.isNotEmpty() }) { etag, lastModified ->
            val page = XingfanClient.fetchPageWithValidators("/", etag = etag, lastModified = lastModified, scope = scope)
            if (page.notModified) {
                NetworkResult(null, page.etag, page.lastModified, notModified = true)
            } else {
                NetworkResult(
                    XingfanParser.parseHomePage(page.html.orEmpty()),
                    page.etag,
                    page.lastModified
                )
            }
        }
    }

    fun getCategoryList(
        cat: String,
        page: Int = 1,
        type: String? = null,
        area: String? = null,
        year: String? = null,
        letter: String? = null,
        sort: String? = null,
        forceRefresh: Boolean = false,
        scope: PageCallScope? = null
    ): PageResult {
        val urlPath = XingfanClient.buildListUrl(cat, page, type, area, year, letter, sort)
        val key = "list:$urlPath"
        return loadWithCache(listCache, key, typePage, CachePolicy.LIST, forceRefresh,
            shouldCache = { isCacheableCatalogPage(it) }) { etag, lastModified ->
            val response = XingfanClient.fetchPageWithValidators(urlPath, etag = etag, lastModified = lastModified, scope = scope)
            if (response.notModified) {
                NetworkResult(null, response.etag, response.lastModified, notModified = true)
            } else {
                NetworkResult(
                    XingfanParser.parseCategoryPage(response.html.orEmpty(), page),
                    response.etag,
                    response.lastModified
                )
            }
        }
    }

    fun search(
        keyword: String,
        cid: Int? = null,
        page: Int = 1,
        forceRefresh: Boolean = false,
        scope: PageCallScope? = null
    ): PageResult {
        val urlPath = XingfanClient.buildSearchUrl(keyword, cid, page)
        val key = "search:$urlPath"
        return loadWithCache(listCache, key, typePage, CachePolicy.SEARCH, forceRefresh,
            shouldCache = { isCacheableCatalogPage(it) }) { etag, lastModified ->
            val response = XingfanClient.fetchPageWithValidators(urlPath, etag = etag, lastModified = lastModified, scope = scope)
            if (response.notModified) {
                NetworkResult(null, response.etag, response.lastModified, notModified = true)
            } else {
                NetworkResult(
                    XingfanParser.parseSearchPage(response.html.orEmpty(), page),
                    response.etag,
                    response.lastModified
                )
            }
        }
    }

    fun getDailyUpdates(forceRefresh: Boolean = false, scope: PageCallScope? = null): List<WorkItem> {
        val key = "daily"
        return loadWithCache(dailyCache, key, typeWorkItems, CachePolicy.DAILY, forceRefresh,
            shouldCache = { it.isNotEmpty() }) { etag, lastModified ->
            val response = XingfanClient.fetchPageWithValidators("/new.html", etag = etag, lastModified = lastModified, scope = scope)
            if (response.notModified) {
                NetworkResult(null, response.etag, response.lastModified, notModified = true)
            } else {
                NetworkResult(
                    XingfanParser.parseDailyPage(response.html.orEmpty()),
                    response.etag,
                    response.lastModified
                )
            }
        }
    }

    fun getTopBoards(forceRefresh: Boolean = false, scope: PageCallScope? = null): TopBoards {
        val key = "top"
        return loadWithCache(topCache, key, typeTop, CachePolicy.TOP, forceRefresh,
            shouldCache = { it.day.isNotEmpty() || it.week.isNotEmpty() || it.month.isNotEmpty() }) { etag, lastModified ->
            val response = XingfanClient.fetchPageWithValidators("/top.html", etag = etag, lastModified = lastModified, scope = scope)
            if (response.notModified) {
                NetworkResult(null, response.etag, response.lastModified, notModified = true)
            } else {
                NetworkResult(
                    XingfanParser.parseTopPage(response.html.orEmpty()),
                    response.etag,
                    response.lastModified
                )
            }
        }
    }

    fun getDetail(url: String, forceRefresh: Boolean = false, scope: PageCallScope? = null): WorkDetail {
        val key = "detail:$url"
        return loadWithCache(
            memory = detailCache,
            key = key,
            type = typeDetail,
            policy = CachePolicy.DETAIL,
            forceRefresh = forceRefresh,
            shouldCache = { it.lines.isNotEmpty() }
        ) { etag, lastModified ->
            val response = XingfanClient.fetchPageWithValidators(url, etag = etag, lastModified = lastModified, scope = scope)
            if (response.notModified) {
                NetworkResult(null, response.etag, response.lastModified, notModified = true)
            } else {
                NetworkResult(
                    XingfanParser.parseDetailPage(response.html.orEmpty(), url),
                    response.etag,
                    response.lastModified
                )
            }
        }
    }

    fun getPlayInfo(url: String, forceRefresh: Boolean = false, scope: PageCallScope? = null): PlayInfo {
        val key = "play:$url"
        if (forceRefresh) playCache.remove(key)
        if (!forceRefresh) getCached(playCache, key)?.let { return it }

        val html = XingfanClient.fetchPage(url, scope = scope)
        val playerConfig = XingfanParser.extractPlayerConfig(html)
            ?: throw IOException("未能从播放页面提取到播放器配置")

        val titlePair = XingfanParser.parsePlayTitle(html)
        val serviceUrl = XingfanClient.buildPlayerServiceUrl(playerConfig.apiurl, playerConfig.url)

        val servicePage = fetchPageWithFinalUrl(serviceUrl, XingfanClient.absUrl(url), scope)
        val lines = XingfanParser.extractPlayLinesFromPlayerHtml(servicePage.html, servicePage.finalUrl)

        if (lines.isEmpty()) {
            throw IOException("播放源解析失败：未发现可用的流媒体线路")
        }

        val info = PlayInfo(
            workTitle = titlePair.first,
            episodeTitle = titlePair.second,
            lines = lines,
            nextEpisodeUrl = playerConfig.next,
            serviceUrl = servicePage.finalUrl,
            episodeNo = Regex("/(\\d+)-(\\d+)\\.html(?:$|\\?)").find(url)?.groupValues?.get(2)?.toIntOrNull() ?: 1
        )
        playCache[key] = CacheEntry(info, System.currentTimeMillis() + TTL_PLAY)
        val now = System.currentTimeMillis()
        playCache.entries.removeAll { it.value.expireAt <= now }
        return info
    }
}
