package com.hm.video.data.api

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.HttpUrl
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Thrown for non-success HTTP responses so callers can tell "gone" from "network down". */
class HttpStatusException(val code: Int, message: String) : IOException(message)

object XingfanClient {

    private fun pageClientBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
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

    const val PAGE_CALL_TIMEOUT_SEC = 20L

    /**
     * Page client: [OkHttpClient.callTimeout] bounds the whole call, including reading the
     * response body. The previous implementation cancelled a timer as soon as the headers
     * arrived, so a server that answered quickly and then dripped its body escaped the limit.
     */
    private fun createHttpClient(): OkHttpClient = pageClientBuilder()
        .callTimeout(PAGE_CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
        .build()

    val httpClient: OkHttpClient = createHttpClient()

    /**
     * Posters keep the per-read timeout and no whole-call budget: a large image on a slow
     * connection must not inherit the page request deadline.
     */
    val imageHttpClient: OkHttpClient = pageClientBuilder().build()

    // Media segments are streamed for much longer than HTML pages. Keep a separate client
    // so a slow TV box or CDN does not hit the short page-fetch timeout mid-playback.
    val playbackHttpClient: OkHttpClient = httpClient.newBuilder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /** Media-type sniffing reads only a prefix; keep it short so start-up stays responsive. */
    val probeHttpClient: OkHttpClient = playbackHttpClient.newBuilder()
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var pageClientOverride: OkHttpClient? = null

    /** The page client used by every HTML/service fetch; tests may swap in a short deadline. */
    internal fun pageClient(): OkHttpClient = pageClientOverride ?: httpClient

    internal fun <T> withPageClientForTest(client: OkHttpClient, block: () -> T): T {
        synchronized(this) { pageClientOverride = client }
        try {
            return block()
        } finally {
            synchronized(this) { pageClientOverride = null }
        }
    }

    internal fun pageClientWithCallTimeoutForTest(seconds: Long): OkHttpClient =
        pageClientBuilder().callTimeout(seconds, TimeUnit.SECONDS).build()

    /**
     * Executes [call] for one user action and reads the response inside [read], so the ticket
     * stays bound to this call until the body has been consumed. A ticket from a superseded
     * action is refused before the request starts, which keeps late threads from cancelling a
     * newer request. The 20-second whole-call timeout still applies.
     */
    fun <T> executePageCall(
        call: Call,
        ticket: PageCallScope.Ticket? = null,
        read: (okhttp3.Response) -> T
    ): T {
        ticket?.claim(call)
        try {
            call.execute().use { response ->
                return read(response)
            }
        } finally {
            ticket?.release(call)
        }
    }

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
    fun fetchPage(url: String, referer: String? = null, ticket: PageCallScope.Ticket? = null): String {
        val response = fetchPageWithValidators(url, referer, ticket = ticket)
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
        lastModified: String? = null,
        ticket: PageCallScope.Ticket? = null,
        client: OkHttpClient = pageClient()
    ): PageResponse {
        val targetUrl = absUrl(url)
        val requestBuilder = Request.Builder()
            .url(targetUrl)
            .header("User-Agent", XingfanConfig.USER_AGENT)
            .header("Referer", referer ?: "${XingfanConfig.SOURCE_BASE}/")

        if (!etag.isNullOrEmpty()) requestBuilder.header("If-None-Match", etag)
        if (!lastModified.isNullOrEmpty()) requestBuilder.header("If-Modified-Since", lastModified)

        return executePageCall(client.newCall(requestBuilder.build()), ticket) { response ->
            if (response.code() == 304) {
                PageResponse(
                    html = null,
                    finalUrl = response.request().url().toString(),
                    etag = response.header("ETag") ?: etag,
                    lastModified = response.header("Last-Modified") ?: lastModified,
                    notModified = true
                )
            } else {
                if (!response.isSuccessful()) {
                    throw HttpStatusException(
                        response.code(),
                        "HTTP ${response.code()} error fetching $targetUrl"
                    )
                }
                PageResponse(
                    html = response.body()?.string() ?: "",
                    finalUrl = response.request().url().toString(),
                    etag = response.header("ETag"),
                    lastModified = response.header("Last-Modified"),
                    notModified = false
                )
            }
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
