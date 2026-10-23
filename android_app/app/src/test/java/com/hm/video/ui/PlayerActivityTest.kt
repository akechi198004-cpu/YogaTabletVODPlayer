package com.hm.video.ui

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.View
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import com.hm.video.LocalHttpServer
import com.hm.video.R
import com.hm.video.installTestApplication
import com.hm.video.data.api.XingfanRepository
import com.hm.video.data.model.HistoryItem
import com.hm.video.data.model.PlayInfo
import com.hm.video.data.model.PlayLine
import com.hm.video.data.storage.UserStorageManager
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.lang.reflect.Proxy
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23], application = Application::class)
class PlayerActivityTest {
    private lateinit var activity: PlayerActivity
    private lateinit var storage: UserStorageManager
    private lateinit var fake: FakePlayer

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("hm_video_user_data", 0).edit().clear().commit()
        storage = installTestApplication()
        // Do not call onCreate/initPlayer: tests use a fake ExoPlayer and no real decoder/network.
        activity = Robolectric.buildActivity(PlayerActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        activity.setContentView(R.layout.activity_player)
        call("initViews")
        fake = FakePlayer()
        set("exoPlayer", fake.player)
        set("httpDataSourceFactory", DefaultHttpDataSource.Factory())
        set("detailUrl", "/dianshiju/test/")
        set("epUrl", "/dianshiju/test/1-1.html")
        set("loadedEpisodeUrl", "/dianshiju/test/1-1.html")
        set("playInfo", PlayInfo("测试", "第一集", lines = listOf(
            PlayLine("线路一", "https://example.invalid/a.m3u8"),
            PlayLine("线路二", "https://example.invalid/b.m3u8")
        )))
        set("activityStarted", true)
    }

    @After
    fun tearDown() {
        call("onDestroy")
    }

    @Test
    fun switchingLinesKeepsPositionSpeedAndPauseIntent() {
        fake.position = 180_000L
        set("readyHandledForSource", true)
        set("initialSeekApplied", true)
        set("currentSpeed", 1.5f)
        call("requestPlayback", false)

        call("startStream", 1, false)

        assertEquals(180_000L, fake.position)
        assertEquals(1.5f, fake.speed, 0f)
        assertFalse(fake.playWhenReady)
        call("onPlayerReady")
        assertEquals(180_000L, fake.position)
        call("onStop")
        call("onStart")
        assertFalse(fake.playWhenReady)
    }

    @Test
    fun proxyFailureBeforeReadyDoesNotLosePendingPosition() {
        fake.position = 210_000L
        set("readyHandledForSource", true)
        set("initialSeekApplied", true)
        call("startStream", 1, false)
        fake.position = 0L // A failed replacement has not produced a usable timeline.

        call("startStream", 0, true)

        assertEquals(210_000L, fake.position)
        assertTrue(fake.mediaItem!!.localConfiguration!!.uri.host == "127.0.0.1")
    }

    @Test
    fun firstReadyStillResumesSavedHistory() {
        storage.recordHistory(history(progress = 75))
        call("startStream", 0, false)
        call("onPlayerReady")
        assertEquals(75_000L, fake.position)
    }

    @Test
    fun bufferingStopsInBackgroundAndLateSourceCannotAutoplay() {
        fake.state = Player.STATE_BUFFERING
        fake.playWhenReady = true
        call("onStop")
        assertFalse(fake.playWhenReady)

        call("startStream", 0, false) // Simulates a parsing/probe callback after onStop.
        assertFalse(fake.playWhenReady)
        call("onPlayerReady")
        assertFalse(fake.playWhenReady)
        call("onStart")
        assertTrue(fake.playWhenReady)
    }

    @Test
    fun nextEpisodeSavesOldProgressButDoesNotCarryPositionForward() {
        storage.recordHistory(history(progress = 30))
        fake.position = 180_000L
        set("readyHandledForSource", true)
        set("initialSeekApplied", true)
        val info = get("playInfo") as PlayInfo
        set("playInfo", info.copy(nextEpisodeUrl = "https://example.invalid/2.mp4"))

        call("playNextEpisodeIfAvailable")

        assertEquals(180L, storage.getHistoryItem("/dianshiju/test/")!!.progressSec)
        assertEquals(0L, fake.position)
        call("onPlayerReady")
        assertEquals(0L, fake.position)
        assertEquals(2, storage.getHistoryItem("/dianshiju/test/")!!.episodeNo)
    }

    @Test
    fun progressWritesAreThrottledButPauseFlushesLatestPosition() {
        storage.recordHistory(history(progress = 0))
        set("readyHandledForSource", true)
        set("lastProgressSavedAt", SystemClock.elapsedRealtime())
        fake.position = 120_000L
        call("saveProgress", false)
        assertEquals(0L, storage.getHistoryItem("/dianshiju/test/")!!.progressSec)

        ShadowSystemClock.advanceBy(Duration.ofSeconds(16))
        call("saveProgress", false)
        assertEquals(120L, storage.getHistoryItem("/dianshiju/test/")!!.progressSec)
        fake.position = 130_000L
        call("saveProgress", false)
        assertEquals(120L, storage.getHistoryItem("/dianshiju/test/")!!.progressSec)
        call("requestPlayback", false)
        assertEquals(130L, storage.getHistoryItem("/dianshiju/test/")!!.progressSec)
    }

    @Test
    fun terminalRetryReparsesInsteadOfReusingCachedSignedUrl() {
        val version = AtomicInteger(1)
        var base = ""
        LocalHttpServer { path ->
            if (path.startsWith("/service")) {
                "<div id='line-list'><a data-url='https://example.invalid/v${version.get()}.m3u8'>线路</a></div>"
            } else {
                """<script>var zanpiancms_player={"url":"id","apiurl":"$base/service?url="};</script>"""
            }
        }.use { server ->
            base = server.baseUrl
            val url = "$base/1-1.html"
            val old = XingfanRepository.getPlayInfo(url)
            assertTrue(old.lines[0].url.contains("v1.m3u8"))
            set("epUrl", url)
            set("loadedEpisodeUrl", url)
            set("playInfo", old)
            set("currentlyUsingProxy", true)
            call("handleStreamFailure", PlaybackException("test", null, 1001))
            version.set(2)

            activity.findViewById<View>(R.id.player_loading_layout).performClick()
            val deadline = System.nanoTime() + 5_000_000_000L
            while (fake.mediaItem == null && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }

            assertTrue(fake.mediaItem?.localConfiguration?.uri.toString().contains("v2.m3u8"))
            assertEquals(2, server.requests.count { it == "/1-1.html" })
        }
    }

    private fun history(progress: Long) = HistoryItem(
        url = "/dianshiju/test/", title = "测试", poster = "", episodeNo = 1,
        episodeUrl = "/dianshiju/test/1-1.html", progressSec = progress, durationSec = 600
    )

    private fun set(name: String, value: Any) {
        PlayerActivity::class.java.getDeclaredField(name).apply { isAccessible = true; set(activity, value) }
    }

    private fun get(name: String): Any? = PlayerActivity::class.java.getDeclaredField(name).run {
        isAccessible = true; get(activity)
    }

    private fun call(name: String, vararg args: Any) {
        PlayerActivity::class.java.declaredMethods.first { it.name == name && it.parameterCount == args.size }
            .apply { isAccessible = true }.invoke(activity, *args)
    }

    private class FakePlayer {
        var position = 0L
        var playWhenReady = true
        var state = Player.STATE_READY
        var speed = 1f
        var mediaItem: MediaItem? = null
        val player = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { _, method, args ->
            when (method.name) {
                "getCurrentPosition" -> position
                "getDuration" -> 600_000L
                "getPlayWhenReady" -> playWhenReady
                "setPlayWhenReady" -> { playWhenReady = args!![0] as Boolean; null }
                "getPlaybackState" -> state
                "isPlaying" -> playWhenReady && state == Player.STATE_READY
                "pause" -> { playWhenReady = false; null }
                "stop" -> { state = Player.STATE_IDLE; null }
                "prepare" -> { state = Player.STATE_BUFFERING; null }
                "setMediaItem" -> {
                    mediaItem = args!![0] as MediaItem
                    position = if (args.size == 2 && args[1] is Long) args[1] as Long else 0L
                    null
                }
                "seekTo" -> { position = args!!.last() as Long; null }
                "setPlaybackParameters" -> { speed = (args!![0] as PlaybackParameters).speed; null }
                else -> null
            }
        } as ExoPlayer
    }
}
