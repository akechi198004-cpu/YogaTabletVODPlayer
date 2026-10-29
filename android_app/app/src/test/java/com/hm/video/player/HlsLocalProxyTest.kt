package com.hm.video.player

import android.app.Application
import com.hm.video.FixtureHttpServer
import com.hm.video.FixtureResponse
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/** Exercises the actual upstream-response-to-proxy-response path over local sockets. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23], application = Application::class)
class HlsLocalProxyTest {

    @Test
    fun mp4WithM3u8InQueryIsRelayedByteForByte() {
        val mp4 = byteArrayOf(0, 0, 0, 24, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(), 1, 2, 3)
        FixtureHttpServer { FixtureResponse(mp4, "video/mp4") }.use { upstream ->
            val proxied = requestThroughProxy(
                "${upstream.baseUrl}/movie.mp4?token=a.m3u8",
                hlsHint = false
            )

            assertTrue(proxied.headers.contains("Content-Type: video/mp4", ignoreCase = true))
            assertArrayEquals("the proxy must not decode/rewrite MP4 bytes", mp4, proxied.body)
        }
    }

    @Test
    fun masterThenExtensionlessChildPlaylistRewritesOnlyPlaylistUris() {
        val chunk = byteArrayOf(0, 1, 2, 3, -1, 10, 20)
        val key = ByteArray(16) { (it * 7).toByte() }
        FixtureHttpServer { path ->
            when {
                path == "/master.m3u8" -> FixtureResponse.text(
                    "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=900000\nvideo/variant\n",
                    "application/vnd.apple.mpegurl"
                )
                path == "/video/variant" -> FixtureResponse.text(
                    "#EXTM3U\n#EXT-X-TARGETDURATION:6\n" +
                        "#EXT-X-KEY:METHOD=AES-128,URI=\"keys/key.bin\"\n" +
                        "#EXTINF:6,\nsegments/chunk.ts\n#EXT-X-ENDLIST\n",
                    "application/octet-stream"
                )
                path == "/video/segments/chunk.ts" -> FixtureResponse(chunk, "video/mp2t")
                path == "/video/keys/key.bin" -> FixtureResponse(key, "application/octet-stream")
                else -> FixtureResponse(
                    "unexpected path $path".toByteArray(),
                    status = "404 Not Found"
                )
            }
        }.use { upstream ->
            val master = requestThroughProxy("${upstream.baseUrl}/master.m3u8", hlsHint = true)
            val masterText = master.body.toString(Charsets.UTF_8)
            val childProxyUrl = masterText.lineSequence().first { it.contains("/hls?") }
            val (childUrl, childHint) = decodeProxyUrl(childProxyUrl)
            assertTrue("master playlist URI must identify its child as a playlist", childHint)

            val child = requestThroughProxy(childUrl, hlsHint = childHint)
            val childText = child.body.toString(Charsets.UTF_8)
            assertTrue("octet-stream body with #EXTM3U is still a child manifest", childText.startsWith("#EXTM3U"))
            val resourceUrls = childText.lines().filter { it.contains("/hls?") }
            assertEquals("one key and one media segment should be rewritten", 2, resourceUrls.size)

            fun resourceUrl(line: String) = Regex("URI=\"([^\"]+)\"").find(line)
                ?.groupValues?.get(1) ?: line
            val keyProxyUrl = resourceUrls.map(::resourceUrl).first { decodeProxyUrl(it).first.contains("/keys/key.bin") }
            val segmentProxyUrl = resourceUrls.map(::resourceUrl).first { decodeProxyUrl(it).first.contains("/segments/chunk.ts") }
            assertFalse("keys are binary resources, not playlists", decodeProxyUrl(keyProxyUrl).second)
            assertFalse("media segments are binary resources, not playlists", decodeProxyUrl(segmentProxyUrl).second)

            val keyResponse = requestThroughProxy(decodeProxyUrl(keyProxyUrl).first, hlsHint = false)
            val chunkResponse = requestThroughProxy(decodeProxyUrl(segmentProxyUrl).first, hlsHint = false)
            assertArrayEquals(key, keyResponse.body)
            assertArrayEquals(chunk, chunkResponse.body)

            assertTrue(upstream.requests.contains("/master.m3u8"))
            assertTrue(upstream.requests.contains("/video/variant"))
            assertTrue(upstream.requests.contains("/video/segments/chunk.ts"))
            assertTrue(upstream.requests.contains("/video/keys/key.bin"))
        }
    }

    private data class ProxyResponse(val headers: String, val body: ByteArray)

    private fun requestThroughProxy(targetUrl: String, hlsHint: Boolean): ProxyResponse {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            val client = Socket("127.0.0.1", listener.localPort)
            val peer = listener.accept()
            val worker = Thread {
                peer.use {
                    HlsLocalProxy.proxyRequest(
                        socket = it,
                        targetUrl = targetUrl,
                        rangeHeader = null,
                        refererUrl = null,
                        hlsHint = hlsHint
                    )
                }
            }.apply { isDaemon = true; start() }
            val bytes = ByteArrayOutputStream()
            client.use { it.getInputStream().copyTo(bytes) }
            worker.join(3000)
            assertFalse("proxy response worker did not finish", worker.isAlive)
            val response = bytes.toByteArray()
            val delimiter = "\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
            val split = response.indexOfSubsequence(delimiter)
            assertTrue("proxy did not emit an HTTP response", split >= 0)
            return ProxyResponse(
                response.copyOfRange(0, split).toString(Charsets.ISO_8859_1),
                response.copyOfRange(split + delimiter.size, response.size)
            )
        }
    }

    private fun decodeProxyUrl(proxyUrl: String): Pair<String, Boolean> {
        val query = proxyUrl.substringAfter('?').split('&').mapNotNull { part ->
            val pair = part.split('=', limit = 2)
            if (pair.size == 2) pair[0] to URLDecoder.decode(pair[1], "UTF-8") else null
        }.toMap()
        return query.getValue("url") to (query["kind"] == "hls")
    }

    private fun ByteArray.indexOfSubsequence(needle: ByteArray): Int {
        if (needle.isEmpty() || needle.size > size) return -1
        for (start in 0..size - needle.size) {
            if (needle.indices.all { this[start + it] == needle[it] }) return start
        }
        return -1
    }
}
