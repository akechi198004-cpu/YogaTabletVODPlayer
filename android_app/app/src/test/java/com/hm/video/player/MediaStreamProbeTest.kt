package com.hm.video.player

import android.app.Application
import com.hm.video.FixtureHttpServer
import com.hm.video.FixtureResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Media kind must come from the response, the final URL or the manifest itself. A `.m3u8`
 * substring in a query string proves nothing, and an extensionless endpoint must not be treated
 * as progressive by default.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23], application = Application::class)
class MediaStreamProbeTest {

    @Before
    fun setUp() = MediaStreamProbe.clearCacheForTest()

    @After
    fun tearDown() = MediaStreamProbe.clearCacheForTest()

    @Test
    fun plainHlsIsRecognisedByItsExtension() {
        FixtureHttpServer { FixtureResponse.text("#EXTM3U") }.use { server ->
            val url = "${server.baseUrl}/live/index.m3u8"
            assertEquals(MediaStreamKind.HLS, MediaStreamProbe.kindFor(url, null))
            assertTrue("an explicit playlist extension needs no sniffing", server.requests.isEmpty())
        }
    }

    @Test
    fun plainMp4IsProgressiveByItsExtension() {
        FixtureHttpServer { FixtureResponse.text("unused") }.use { server ->
            assertEquals(
                MediaStreamKind.PROGRESSIVE,
                MediaStreamProbe.kindFor("${server.baseUrl}/movie/2026.mp4", null)
            )
            assertTrue(server.requests.isEmpty())
        }
    }

    @Test
    fun mp4WithM3u8InTheQueryStaysProgressive() {
        FixtureHttpServer { FixtureResponse.text("unused") }.use { server ->
            val url = "${server.baseUrl}/movie.mp4?token=.m3u8&expires=1"
            assertEquals(MediaStreamKind.PROGRESSIVE, MediaStreamProbe.kindFor(url, null))
        }
    }

    @Test
    fun extensionlessPlaylistIsHlsByContentType() {
        FixtureHttpServer {
            FixtureResponse.text("#EXTM3U\n#EXT-X-VERSION:3\n", "application/vnd.apple.mpegurl")
        }.use { server ->
            assertEquals(MediaStreamKind.HLS, MediaStreamProbe.kindFor("${server.baseUrl}/play?id=7", null))
        }
    }

    @Test
    fun extensionlessPlaylistIsHlsByManifestFeature() {
        FixtureHttpServer {
            // Deliberately wrong content type: the manifest itself is the evidence.
            FixtureResponse.text("#EXTM3U\n#EXT-X-TARGETDURATION:6\n", "application/octet-stream")
        }.use { server ->
            assertEquals(MediaStreamKind.HLS, MediaStreamProbe.kindFor("${server.baseUrl}/stream/8", null))
        }
    }

    @Test
    fun redirectedPlaylistIsHlsByFinalAddress() {
        FixtureHttpServer { path ->
            if (path == "/go") {
                FixtureResponse(ByteArray(0), status = "302 Found", headers = listOf("Location" to "/live/index.m3u8"))
            } else {
                FixtureResponse.text("#EXTM3U")
            }
        }.use { server ->
            assertEquals(MediaStreamKind.HLS, MediaStreamProbe.kindFor("${server.baseUrl}/go", null))
        }
    }

    @Test
    fun extensionlessMp4IsProgressiveByContentType() {
        FixtureHttpServer {
            FixtureResponse(ByteArray(32) { 0 }, "video/mp4")
        }.use { server ->
            assertEquals(MediaStreamKind.PROGRESSIVE, MediaStreamProbe.kindFor("${server.baseUrl}/download/9", null))
        }
    }

    @Test
    fun unknownResponseStaysUnknownInsteadOfAssumingHls() {
        FixtureHttpServer {
            FixtureResponse.text("<html>not a stream</html>", "text/html")
        }.use { server ->
            assertEquals(MediaStreamKind.UNKNOWN, MediaStreamProbe.kindFor("${server.baseUrl}/watch/10", null))
        }
    }

    @Test
    fun probeResultIsCachedForTheSession() {
        FixtureHttpServer {
            FixtureResponse.text("#EXTM3U", "application/vnd.apple.mpegurl")
        }.use { server ->
            val url = "${server.baseUrl}/live/11"
            assertEquals(MediaStreamKind.HLS, MediaStreamProbe.kindFor(url, null))
            assertEquals(MediaStreamKind.HLS, MediaStreamProbe.kindFor(url, null))
            assertEquals(1, server.requests.size)
        }
    }
}
