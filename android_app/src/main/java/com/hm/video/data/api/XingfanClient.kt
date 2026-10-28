package com.hm.video.data.api

import okhttp3.OkHttpClient
import okhttp3.HttpUrl
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

/** Thrown for non-success HTTP responses so callers can tell "gone" from "network down". */
class HttpStatusException(val code: Int, message: String) : IOException(message)

object XingfanClient {

    private fun createHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .apply { com.hm.video.util.LegacyTls.configure(this) }
        .addInterceptor { chain ->
            val target = chain.request().url()
            try {
                chain.proceed(chain.request()).also { response ->
                    android.util.Log.i("HMNetwork", "${response.code()} ${target.host()}${target.encodedPath()}")
                }
            } catch (error: IOException) {
                android.util.Log.w("HMNetwork", "Request failed: ${target.host()}${target.encodedPath()}", error)
                throw error
            }
        }
        .connectTimeout(XingfanConfig.FETCH_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(XingfanConfig.FETCH_TIMEOUT_SEC, TimeUnit.SECONDS)
        .writeTimeout(XingfanConfig.FETCH_TIMEOUT_SEC, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    const val PAGE_CALL_TIMEOUT_SEC = 20L
    private val deadlineExecutor = Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "hm-http-deadline").apply { isDaemon = true } }

    fun executePageCall(call: okhttp3.Call): okhttp3.Response {
        val timeout = deadlineExecutor.schedule({ call.cancel() }, PAGE_CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
        try { return call.execute() } finally { timeout.cancel(false) }
    }

    val httpClient: OkHttpClient = createHttpClient()

    // Media segments are streamed for much longer than HTML pages. Keep a separate client
    // so a slow TV box or CDN does not hit the short page-fetch timeout mid-playback.
    val playbackHttpClient: OkHttpClient = httpClient.newBuilder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun absUrl(path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val clean = if (path.startsWith("/")) path else "/$path"
        return "${XingfanConfig.SOURCE_BASE}$clean"
    }

    /** Result of a page request; [html] is null exactly when the origin answered 304. */
    class PageResponse(
        val html: String?,
        val finalUrl: String,
        val etag: String?,
        val lastModified: String?,
        val notModified: Boolean
    )

    @Throws(IOException::class)
    fun fetchPage(url: String, referer: String? = null): String {
        val response = fetchPageWithValidators(url, referer)
        return response.html ?: ""
    }

    /**
     * Fetches HTML, optionally as a conditional request. Reusing [etag] / [lastModified] lets an
     * unchanged page come back as a cheap 304, which the repository turns into a cache touch
     * instead of a full download and HTML parse.
     */
    @Throws(IOException::class)
    fun fetchPageWithValidators(
        url: String,
        referer: String? = null,
        etag: String? = null,
        lastModified: String? = null
    ): PageResponse {
        val targetUrl = absUrl(url)
        val requestBuilder = Request.Builder()
            .url(targetUrl)
            .header("User-Agent", XingfanConfig.USER_AGENT)
            .header("Referer", referer ?: "${XingfanConfig.SOURCE_BASE}/")

        if (!etag.isNullOrEmpty()) requestBuilder.header("If-None-Match", etag)
        if (!lastModified.isNullOrEmpty()) requestBuilder.header("If-Modified-Since", lastModified)

        executePageCall(httpClient.newCall(requestBuilder.build())).use { response ->
            if (response.code() == 304) {
                return PageResponse(
                    html = null,
                    finalUrl = response.request().url().toString(),
                    etag = response.header("ETag") ?: etag,
                    lastModified = response.header("Last-Modified") ?: lastModified,
                    notModified = true
                )
            }
            if (!response.isSuccessful()) {
                throw HttpStatusException(
                    response.code(),
                    "HTTP ${response.code()} error fetching $targetUrl"
                )
            }
            return PageResponse(
                html = response.body()?.string() ?: "",
                finalUrl = response.request().url().toString(),
                etag = response.header("ETag"),
                lastModified = response.header("Last-Modified"),
                notModified = false
            )
        }
    }

    fun buildListUrl(
        cat: String,
        page: Int = 1,
        type: String? = null,
        area: String? = null,
        year: String? = null,
        letter: String? = null,
        sort: String? = null
    ): String {
        val hasFilters = !type.isNullOrEmpty() || !area.isNullOrEmpty() ||
                !year.isNullOrEmpty() || !letter.isNullOrEmpty() || !sort.isNullOrEmpty()
        return if (!hasFilters) {
            if (page > 1) "/$cat/index-$page.html" else "/$cat/"
        } else {
            val parts = listOf(
                cat,
                type.orEmpty(),
                area.orEmpty(),
                year.orEmpty(),
                letter.orEmpty(),
                sort.orEmpty(),
                page.toString()
            )
            "/vodlist/${parts.joinToString("_")}.html"
        }
    }

    fun buildSearchUrl(keyword: String, cid: Int? = null, page: Int = 1): String {
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val category = if (cid != null && cid in 1..3) "&cid=$cid" else ""
        return if (page > 1) {
            val path = "/search/$encoded-$page.html"
            if (cid != null && cid in 1..3) "$path?cid=$cid" else path
        } else "/search/?wd=$encoded$category"
    }

    fun buildPlayerServiceUrl(apiurl: String, id: String): String {
        val normalized = when {
            apiurl.startsWith("//") -> "https:$apiurl"
            apiurl.startsWith("http://") || apiurl.startsWith("https://") -> apiurl
            else -> HttpUrl.parse(XingfanConfig.SOURCE_BASE)?.resolve(apiurl)?.toString()
                ?: "${XingfanConfig.SOURCE_BASE}/$apiurl"
        }
        // Player API templates may end in a query parameter prefix such as `?url=`.
        // Keep that concatenation for absolute service URLs and parameter prefixes.
        return normalized + id
    }
}
