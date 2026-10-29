package com.hm.video.player

import com.hm.video.data.api.XingfanClient
import com.hm.video.data.api.XingfanConfig
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/** How a playback URL should be handed to Media3. */
enum class MediaStreamKind {
    /** HLS playlist: Media3 needs the HLS module and, behind the proxy, URL rewriting. */
    HLS,

    /** Progressive media (MP4/M4V/FLV/TS): decode straight from the byte stream. */
    PROGRESSIVE,

    /** Could not tell; do not guess HLS, let Media3 infer from the URI. */
    UNKNOWN
}

/**
 * Decides the media kind from the real response, not from a substring of the URL.
 *
 * A source may advertise an extensionless endpoint (`/play?id=…`), or a `.mp4` URL whose query
 * happens to contain `.m3u8`, or redirect to a playlist. Only an explicit playlist extension is
 * trusted up front; everything else is sniffed once per URL and cached for the session.
 */
object MediaStreamProbe {

    private const val SNIFF_BYTES = 1024
    private val cache = ConcurrentHashMap<String, MediaStreamKind>()

    /** Extension-only hint. Returns null when the URL alone proves nothing. */
    fun inferFromPath(url: String): MediaStreamKind? {
        val path = HttpUrl.parse(url)?.encodedPath()?.lowercase() ?: return null
        return when {
            path.endsWith(".m3u8") -> MediaStreamKind.HLS
            path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".flv") ||
                path.endsWith(".ts") || path.endsWith(".mkv") || path.endsWith(".webm") ->
                MediaStreamKind.PROGRESSIVE
            else -> null
        }
    }

    /**
     * Resolves the kind, using [inferFromPath] when possible and a bounded GET otherwise.
     * Network failures resolve to [MediaStreamKind.UNKNOWN] rather than a wrong guess.
     */
    fun kindFor(
        url: String,
        referer: String?,
        client: OkHttpClient = XingfanClient.probeHttpClient
    ): MediaStreamKind {
        inferFromPath(url)?.let { return it }
        cache[url]?.let { return it }
        val kind = try {
            sniff(url, referer, client)
        } catch (_: Exception) {
            MediaStreamKind.UNKNOWN
        }
        if (kind != MediaStreamKind.UNKNOWN) {
            // Bounded: signed URLs change often, and this only needs to survive a retry cycle.
            if (cache.size > 64) cache.clear()
            cache[url] = kind
        }
        return kind
    }

    internal fun clearCacheForTest() = cache.clear()

    private fun sniff(url: String, referer: String?, client: OkHttpClient): MediaStreamKind {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", XingfanConfig.USER_AGENT)
            .apply { if (!referer.isNullOrBlank()) header("Referer", referer) }
            // A prefix is enough: playlists start with #EXTM3U, MP4 files carry an ftyp box.
            .header("Range", "bytes=0-${SNIFF_BYTES - 1}")
            .build()
        client.newCall(request).execute().use { response ->
            val finalUrl = response.request().url()
            val contentType = response.header("Content-Type").orEmpty().lowercase()
            val prefix = readPrefix(response.body()?.byteStream()) ?: ByteArray(0)
            return classifyResponse(contentType, finalUrl.toString(), prefix)
        }
    }

    /** Shared response classifier used by direct playback probing and the local proxy. */
    internal fun classifyResponse(contentType: String, finalUrl: String, prefix: ByteArray): MediaStreamKind {
        val normalizedType = contentType.lowercase()
        // The manifest signature is the strongest evidence and resolves conflicting headers or
        // extensions without mistaking a query parameter for a media path.
        if (isPlaylistPrefix(prefix) || isHlsType(normalizedType)) return MediaStreamKind.HLS
        if (normalizedType.startsWith("video/") || normalizedType.startsWith("audio/")) {
            return MediaStreamKind.PROGRESSIVE
        }
        inferFromPath(finalUrl)?.let { return it }
        if (hasContainerSignature(prefix)) return MediaStreamKind.PROGRESSIVE
        return MediaStreamKind.UNKNOWN
    }

    private fun isHlsType(contentType: String): Boolean =
        contentType.contains("mpegurl") || contentType.contains("vnd.apple.mpegurl")

    internal fun isPlaylistPrefix(prefix: ByteArray): Boolean =
        String(prefix, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r')
            .startsWith("#EXTM3U")

    /** MP4/F4V (`ftyp`), FLV and Matroska/WebM start-of-file markers. */
    private fun hasContainerSignature(prefix: ByteArray): Boolean {
        if (prefix.size >= 8 && String(prefix, 4, 4, Charsets.ISO_8859_1) == "ftyp") return true
        if (prefix.size >= 3 && String(prefix, 0, 3, Charsets.ISO_8859_1) == "FLV") return true
        if (prefix.size >= 4 && prefix[0].toInt() == 0x1A && prefix[1].toInt() == 0x45 &&
            prefix[2].toInt() == 0xDF && prefix[3].toInt() == 0xA3) return true
        return false
    }

    private fun readPrefix(stream: InputStream?): ByteArray? {
        if (stream == null) return null
        val buffer = ByteArray(SNIFF_BYTES)
        var size = 0
        stream.use { input ->
            while (size < buffer.size) {
                val read = input.read(buffer, size, buffer.size - size)
                if (read < 0) break
                size += read
            }
        }
        return if (size == 0) null else buffer.copyOf(size)
    }
}
