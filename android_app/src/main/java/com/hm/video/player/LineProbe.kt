package com.hm.video.player

import android.os.SystemClock
import com.hm.video.data.api.XingfanClient
import com.hm.video.data.api.XingfanConfig
import com.hm.video.data.model.PlayLine
import okhttp3.Call
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Bounded, cancellable samples; never downloads a full video or starts a decoder. */
class LineProbe {
    data class Result(val index: Int, val elapsedMs: Long, val bytes: Int) {
        val score: Double get() = elapsedMs * (1.0 + 131072.0 / bytes.coerceAtLeast(1))
    }
    @Volatile private var cancelled = false
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    private val pool = Executors.newFixedThreadPool(3)
    private val client = XingfanClient.httpClient.newBuilder()
        .connectTimeout(2500, TimeUnit.MILLISECONDS)
        .readTimeout(2500, TimeUnit.MILLISECONDS)
.build()

    fun cancel() {
        cancelled = true
        calls.forEach { it.cancel() }
        pool.shutdownNow()
    }

    fun measure(lines: List<PlayLine>, referer: String): List<Result> {
        val completion = ExecutorCompletionService<Result?>(pool)
        try {
            lines.forEachIndexed { index, line ->
                completion.submit(java.util.concurrent.Callable {
                    try {
                        val started = SystemClock.elapsedRealtime()
                        val size = sample(line.url, referer, 0)
                        Result(index, (SystemClock.elapsedRealtime() - started).coerceAtLeast(1), size)
                    } catch (_: Exception) { null }
                })
            }
            val results = mutableListOf<Result>()
            val deadline = SystemClock.elapsedRealtime() + 8000
            repeat(lines.size) {
                if (cancelled) return results.sortedBy { it.score }
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0) return results.sortedBy { it.score }
                val future = completion.poll(remaining, TimeUnit.MILLISECONDS)
                    ?: return results.sortedBy { it.score }
                future.get()?.let(results::add)
            }
            return results.sortedBy { it.score }
        } finally { cancel() }
    }

    private fun sample(url: String, referer: String, depth: Int): Int {
        if (cancelled || Thread.currentThread().isInterrupted || depth > 3) throw IOException("Probe cancelled")
        val request = Request.Builder().url(url)
            .header("User-Agent", XingfanConfig.USER_AGENT).header("Referer", referer)
            .header("Range", "bytes=0-131071").build()
        val call = client.newCall(request)
        calls.add(call)
        if (cancelled) call.cancel()
        try {
            call.execute().use { response ->
                if (!response.isSuccessful()) throw IOException("HTTP ${response.code()}")
                val type = response.header("Content-Type").orEmpty().lowercase()
                if (type.contains("text/html") || type.contains("application/json")) throw IOException("Not media")
                val body = response.body() ?: throw IOException("Empty body")
                val buffer = ByteArray(8192)
                val output = ByteArrayOutputStream()
                body.byteStream().use { input ->
                    while (output.size() < 131072) {
                        if (cancelled || Thread.currentThread().isInterrupted) throw IOException("Probe cancelled")
                        val read = input.read(buffer, 0, minOf(buffer.size, 131072 - output.size()))
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
                val data = output.toByteArray()
                if (data.isEmpty()) throw IOException("Empty media")
                val prefix = String(data, 0, minOf(data.size, 128), Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r')
                if (prefix.startsWith("<") || prefix.startsWith("{")) throw IOException("Not media")
                if (prefix.startsWith("#EXTM3U")) {
                    val playlist = data.toString(Charsets.UTF_8).lineSequence().map { it.trim() }.toList()
                    // Follow the first advertised variant, then a real media segment.
                    val child = playlist.firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                        ?: throw IOException("Empty playlist")
                    return sample(URI(response.request().url().toString()).resolve(child).toString(), referer, depth + 1)
                }
                return data.size
            }
        } finally { calls.remove(call) }
    }
}
