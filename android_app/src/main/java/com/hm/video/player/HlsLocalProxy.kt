package com.hm.video.player

import com.hm.video.data.api.XingfanClient
import com.hm.video.data.api.XingfanConfig
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Loopback-only HLS adapter used when a source rejects the direct Media3 request. */
object HlsLocalProxy {
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var isRunning = false
    private val workers = ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS, ArrayBlockingQueue(12))

    @Synchronized fun start() {
        if (isRunning) return
        Thread({
            try {
                val server = ServerSocket(XingfanConfig.LOCAL_PROXY_PORT, 16, InetAddress.getByName("127.0.0.1"))
                serverSocket = server
                isRunning = true
                while (isRunning) {
                    val client = server.accept()
                    client.soTimeout = 4000
                    try {
                        workers.execute { handleClient(client) }
                    } catch (_: java.util.concurrent.RejectedExecutionException) {
                        sendStatus(client, 503, "Service Unavailable")
                        client.close()
                    }
                }
            } catch (_: Exception) {
                isRunning = false
            } finally {
                try { serverSocket?.close() } catch (_: Exception) {}
                serverSocket = null
            }
        }, "hm-hls-proxy").apply { isDaemon = true }.start()
    }

    @Synchronized fun stop() {
        isRunning = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        workers.purge()
    }

    fun getProxyUrl(targetUrl: String, refererUrl: String? = null, hls: Boolean = false): String = try {
        val target = URLEncoder.encode(targetUrl, "UTF-8")
        val referer = refererUrl?.takeIf { it.isNotBlank() }
            ?.let { "&referer=${URLEncoder.encode(it, "UTF-8")}" }.orEmpty()
        // `kind=hls` comes from the media-type probe, so an extensionless playlist is still
        // rewritten instead of being relayed as an opaque byte stream.
        val kind = if (hls) "&kind=hls" else ""
        "http://127.0.0.1:${XingfanConfig.LOCAL_PROXY_PORT}/hls?url=$target$referer$kind"
    } catch (_: Exception) { targetUrl }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            try {
                val input = BufferedInputStream(client.getInputStream())
                val request = readHeader(input) ?: return
                val lines = request.split("\r\n")
                val target = lines.firstOrNull()?.split(" ")?.getOrNull(1) ?: return sendStatus(client, 400, "Bad Request")
                val headers = lines.drop(1).mapNotNull {
                    val split = it.indexOf(':')
                    if (split <= 0) null else it.substring(0, split).trim().lowercase() to it.substring(split + 1).trim()
                }.toMap()
                if (!target.startsWith("/hls?")) return sendStatus(client, 404, "Not Found")
                val query = target.substringAfter('?').split('&').mapNotNull { item ->
                    val pair = item.split('=', limit = 2)
                    if (pair.size == 2) pair[0] to URLDecoder.decode(pair[1], "UTF-8") else null
                }.toMap()
                val targetUrl = query["url"].orEmpty()
                val parsed = runCatching { URL(targetUrl) }.getOrNull()
                if (parsed == null || parsed.protocol !in setOf("http", "https")) return sendStatus(client, 400, "Bad Request")
                proxyRequest(client, targetUrl, headers["range"], query["referer"], query["kind"] == "hls")
            } catch (_: Exception) {
                // Client disconnects and bounded header timeouts are expected during seek.
            }
        }
    }

    private fun readHeader(input: BufferedInputStream): String? {
        val out = StringBuilder()
        var matched = 0
        while (out.length < 16 * 1024) {
            val value = input.read()
            if (value == -1) return null
            out.append(value.toChar())
            matched = when {
                matched == 0 && value == 13 -> 1
                matched == 1 && value == 10 -> 2
                matched == 2 && value == 13 -> 3
                matched == 3 && value == 10 -> return out.toString().dropLast(4)
                value == 13 -> 1
                else -> 0
            }
        }
        return null
    }

    internal fun proxyRequest(
        socket: Socket,
        targetUrl: String,
        rangeHeader: String?,
        refererUrl: String?,
        hlsHint: Boolean
    ) {
        val targetUri = URL(targetUrl)
        val request = Request.Builder().url(targetUrl)
            .header("User-Agent", XingfanConfig.USER_AGENT)
            .header("Referer", refererUrl?.takeIf { it.startsWith("http") } ?: "${targetUri.protocol}://${targetUri.host}/")
            .apply { if (!rangeHeader.isNullOrBlank()) header("Range", rangeHeader) }
            .build()
        try {
            XingfanClient.playbackHttpClient.newCall(request).execute().use { response ->
                val body = response.body()
                val contentType = response.header("Content-Type").orEmpty()
                val finalUrl = response.request().url().toString()
                val finalPathKind = MediaStreamProbe.inferFromPath(finalUrl)
                val progressiveMime = contentType.startsWith("video/", ignoreCase = true) ||
                    contentType.startsWith("audio/", ignoreCase = true)
                // Inspect a small, non-consuming prefix. Never inspect the query string: an MP4
                // URL may legitimately contain a token ending in `.m3u8`.
                val shouldInspectPrefix = hlsHint || finalPathKind == null ||
                    contentType.contains("mpegurl", ignoreCase = true) ||
                    (finalPathKind == MediaStreamKind.PROGRESSIVE && !progressiveMime)
                val prefix = if (response.isSuccessful() && !progressiveMime && shouldInspectPrefix) {
                    runCatching { response.peekBody(1024).bytes() }.getOrDefault(ByteArray(0))
                } else ByteArray(0)
                val isPlaylist = response.isSuccessful() &&
                    MediaStreamProbe.classifyResponse(contentType, finalUrl, prefix) == MediaStreamKind.HLS
                val output = BufferedOutputStream(socket.getOutputStream())
                if (isPlaylist) {
                    val playlist = body?.string().orEmpty()
                    val rewritten = rewriteM3u8(playlist, finalUrl, refererUrl).toByteArray(Charsets.UTF_8)
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${rewritten.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    output.write(rewritten)
                } else {
                    val code = response.code()
                    val reason = when (code) {
                        200 -> "OK"; 206 -> "Partial Content"; 301 -> "Moved Permanently"; 302 -> "Found"
                        403 -> "Forbidden"; 404 -> "Not Found"; 416 -> "Range Not Satisfiable"
                        in 500..599 -> "Upstream Error"; else -> "Upstream Response"
                    }
                    output.write("HTTP/1.1 $code $reason\r\n".toByteArray())
                    output.write("Content-Type: ${contentType.ifEmpty { "application/octet-stream" }}\r\n".toByteArray())
                    response.header("Content-Length")?.let { output.write("Content-Length: $it\r\n".toByteArray()) }
                    response.header("Content-Range")?.let { output.write("Content-Range: $it\r\n".toByteArray()) }
                    output.write("Accept-Ranges: bytes\r\nConnection: close\r\n\r\n".toByteArray())
                    body?.byteStream()?.use { it.copyTo(output, 16 * 1024) }
                }
                output.flush()
            }
        } catch (_: Exception) { sendStatus(socket, 502, "Bad Gateway") }
    }

    internal fun rewriteM3u8(playlist: String, baseUrl: String, refererUrl: String?): String {
        val base = URL(baseUrl)
        var nextUriIsPlaylist = false
        return playlist.lineSequence().joinToString("\n") { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                trimmed
            } else if (trimmed.startsWith("#")) {
                val upper = trimmed.uppercase()
                if (upper.startsWith("#EXT-X-STREAM-INF")) nextUriIsPlaylist = true
                val uriIsPlaylist = upper.startsWith("#EXT-X-MEDIA:") ||
                    upper.startsWith("#EXT-X-I-FRAME-STREAM-INF:")
                val isKey = upper.startsWith("#EXT-X-KEY:") || upper.startsWith("#EXT-X-SESSION-KEY:")
                if (trimmed.contains("URI=\"")) Regex("URI=\"([^\"]+)\"").replace(trimmed) { match ->
                    val resolved = URL(base, match.groupValues[1]).toString()
                    val proxyUrl = getProxyUrl(resolved, refererUrl, hls = uriIsPlaylist && !isKey)
                    "URI=\"$proxyUrl\""
                } else trimmed
            } else {
                val resolved = URL(base, trimmed).toString()
                val proxyUrl = getProxyUrl(resolved, refererUrl, hls = nextUriIsPlaylist)
                nextUriIsPlaylist = false
                proxyUrl
            }
        }
    }

    private fun sendStatus(socket: Socket, code: Int, reason: String) {
        runCatching {
            val body = "$code $reason".toByteArray()
            val out = socket.getOutputStream()
            out.write("HTTP/1.1 $code $reason\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(body)
            out.flush()
        }
    }
}
