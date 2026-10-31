package com.hm.video.ui

import android.app.Application
import android.media.AudioManager
import android.os.Looper
import android.os.SystemClock
import android.view.View
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
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
        set("audioManager", activity.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager)
        set("audioFocusGranted", true) // These tests bypass onCreate/onStart and model granted focus.
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
    fun progressPollingStopsInBackgroundAndRestartsWhenVisible() {
        val mainLooper = shadowOf(Looper.getMainLooper()).apply { pause() }

        call("onPlayerReady")
        call("onStop")
        mainLooper.idleFor(Duration.ofSeconds(6))
        // With the recurring poll still queued, each second would enqueue the next tick and the
        // looper would never become idle; the one-shot overlay timers have all expired by now.
        assertTrue("a stopped activity must not keep rescheduling progress updates", mainLooper.isIdle())

        call("onStart")
        assertFalse("progress polling resumes for an already prepared player", mainLooper.isIdle())
        call("onStop")
        assertTrue("onStop must remove the restarted progress callback", mainLooper.isIdle())
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

    @Test
    fun parseFailureShowsBackButtonAndReturnsToDetail() {
        val back = activity.findViewById<View>(R.id.loading_back_button)
        val showMessage = PlayerActivity::class.java.declaredMethods
            .first { it.name == "showPlaybackMessage" && it.parameterCount == 3 }
            .apply { isAccessible = true }

        showMessage.invoke(activity, *arrayOf<Any?>("正在解析播放源", "正在读取线路", null))
        assertEquals("a loading screen keeps no back button", View.GONE, back.visibility)

        call("showPlaybackMessage", "播放源解析失败", "无法读取播放页面", {})
        assertEquals(View.VISIBLE, back.visibility)
        assertEquals(back.id, activity.findViewById<View>(R.id.player_loading_layout).nextFocusDownId)

        assertTrue(back.performClick())
        assertTrue("back must leave the player", activity.isFinishing)
    }

    // ---------------------------------------------------------------
    // Reordering state after a background kill
    // ---------------------------------------------------------------

    @Test
    fun recreationRestoresTheEpisodeThatWasPlaying() {
        storage.recordHistory(episodeTwoHistory(300))
        set("epUrl", "/dianshiju/test/1-2.html")
        set("epTitle", "第 2 集")
        set("episodeNo", 2)
        set("loadedEpisodeUrl", "/dianshiju/test/1-2.html")
        set("currentSpeed", 1.5f)
        set("speedIndex", 3)
        set("readyHandledForSource", true)
        set("initialSeekApplied", true)
        fake.position = 240_000L
        // The user had paused; do not flush here, so history (300s) stays ahead of the bundle.
        set("playRequested", false)
        val state = android.os.Bundle()
        call("onSaveInstanceState", state)

        val (restored, restoredFake) = recreateWith(state)

        assertEquals("/dianshiju/test/1-2.html", getOn(restored, "epUrl"))
        assertEquals("第 2 集", getOn(restored, "epTitle"))
        assertEquals(2, getOn(restored, "episodeNo"))
        assertEquals(240_000L, getOn(restored, "resumePositionMs"))
        assertEquals(1.5f, getOn(restored, "currentSpeed"))
        assertEquals(false, getOn(restored, "playRequested"))
        assertEquals("1.5x", restored.findViewById<android.widget.Button>(R.id.player_btn_speed).text.toString())

        setOn(restored, "playInfo", PlayInfo("测试", "第 2 集", lines = listOf(
            PlayLine("线路", "https://example.invalid/a.m3u8")
        )))
        callOn(restored, "startStream", 0, false)
        callOn(restored, "onPlayerReady")

        assertEquals(300_000L, restoredFake.position) // history is further than the bundle
        assertFalse("a paused session must stay paused", restoredFake.playWhenReady)
        assertEquals(1.5f, restoredFake.speed, 0f)
        val history = storage.getHistoryItem("/dianshiju/test/")!!
        assertEquals("/dianshiju/test/1-2.html", history.episodeUrl)
        assertEquals(2, history.episodeNo)
        assertEquals("the other episode's latest history must survive", 300L, history.progressSec)
    }

    @Test
    fun savedStateNeverContainsSignedStreamLinks() {
        set("playInfo", PlayInfo("测试", "第一集", lines = listOf(
            PlayLine("线路", "https://cdn.example.invalid/v.m3u8?token=secret-signature")
        )))
        val state = android.os.Bundle()
        call("onSaveInstanceState", state)

        val dumped = state.keySet().joinToString("|") { key -> "$key=${state.get(key)}" }
        assertFalse(dumped.contains("secret-signature"))
        assertFalse(dumped.contains("example.invalid"))
    }

    // ---------------------------------------------------------------
    // Audio focus and headset unplug
    // ---------------------------------------------------------------

    @Test
    fun transientFocusLossPausesAndResumesWhenFocusReturns() {
        set("readyHandledForSource", true)
        set("initialSeekApplied", true)
        call("requestPlayback", true)
        assertTrue(fake.playWhenReady)

        call("handleAudioFocusChange", AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertFalse(fake.playWhenReady)
        assertEquals(true, get("playRequested"))

        call("handleAudioFocusChange", AudioManager.AUDIOFOCUS_GAIN)
        assertTrue(fake.playWhenReady)
    }

    @Test
    fun deniedAudioFocusKeepsPlaybackPausedAndDoesNotAutoplayOnReturn() {
        val manager = activity.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
        shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)

        call("requestPlayback", true)

        assertEquals("the user's play intent is retained", true, get("playRequested"))
        assertEquals(false, get("audioFocusGranted"))
        assertEquals(true, get("audioFocusRequestFailed"))
        assertFalse("a denied focus request must prevent start", fake.playWhenReady)
        val savedState = android.os.Bundle()
        call("onSaveInstanceState", savedState)
        assertTrue("denied focus must remain blocked after recreation", savedState.getBoolean("hm.player.focus_request_failed"))

        val (restored, restoredFake) = recreateWith(savedState)
        callOn(restored, "onStart")
        assertFalse("recreated activity must not auto-start after a denied request", restoredFake.playWhenReady)
        callOn(restored, "onStop")
        callOn(restored, "onDestroy")

        call("onStop")
        call("onStart") // denied focus is not retried automatically by lifecycle restoration
        assertFalse(fake.playWhenReady)

        call("handleAudioFocusChange", AudioManager.AUDIOFOCUS_GAIN)
        assertFalse("a stray gain callback must not turn denial into playback", fake.playWhenReady)
        assertEquals(true, get("playRequested"))
    }

    @Test
    fun focusLossThenBackgroundNeverAutoplaysOnReturn() {
        set("readyHandledForSource", true)
        set("initialSeekApplied", true)
        call("requestPlayback", true)

        call("handleAudioFocusChange", AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertFalse(fake.playWhenReady)
        call("onStop")
        call("onStart")

        assertFalse("auto-paused before backgrounding must stay paused", fake.playWhenReady)
        assertEquals(false, get("playRequested"))
    }

    @Test
    fun permanentFocusLossClearsThePlayIntent() {
        set("readyHandledForSource", true)
        set("initialSeekApplied", true)
        call("requestPlayback", true)

        call("handleAudioFocusChange", AudioManager.AUDIOFOCUS_LOSS)
        assertFalse(fake.playWhenReady)
        assertEquals(false, get("playRequested"))

        call("handleAudioFocusChange", AudioManager.AUDIOFOCUS_GAIN)
        assertFalse(fake.playWhenReady)
        call("onStop")
        call("onStart")
        assertFalse(fake.playWhenReady)
    }

    @Test
    fun headsetUnplugPausesAndDoesNotResumeByItself() {
        set("readyHandledForSource", true)
        set("initialSeekApplied", true)
        call("requestPlayback", true)

        call("handleBecomingNoisy")
        assertFalse(fake.playWhenReady)
        assertEquals(false, get("playRequested"))

        call("onStop")
        call("onStart")
        assertFalse(fake.playWhenReady)
    }

    @Test
    fun automaticPauseStillFlushesProgress() {
        storage.recordHistory(history(progress = 0))
        set("readyHandledForSource", true)
        fake.position = 90_000L
        set("lastProgressSavedAt", SystemClock.elapsedRealtime())

        call("handleAudioFocusChange", AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)

        assertEquals(90L, storage.getHistoryItem("/dianshiju/test/")!!.progressSec)
    }

    // ---------------------------------------------------------------
    // Media kind: response, final address or manifest, never the query string
    // ---------------------------------------------------------------

    @Test
    fun mp4WithM3u8InQueryIsHandedOverAsProgressive() {
        set("playInfo", PlayInfo("测试", "第一集", lines = listOf(
            PlayLine("线路", "https://example.invalid/movie.mp4?token=a.m3u8")
        )))

        call("startStream", 0, false)

        val item = fake.mediaItem!!
        assertEquals(MimeTypes.VIDEO_MP4, item.localConfiguration!!.mimeType)
        assertEquals("example.invalid", item.localConfiguration!!.uri.host)
    }

    @Test
    fun plainPlaylistKeepsItsHlsMimeType() {
        call("startStream", 0, false)
        assertEquals(MimeTypes.APPLICATION_M3U8, fake.mediaItem!!.localConfiguration!!.mimeType)
    }

    @Test
    fun extensionlessHlsIsSniffedBeforeHandover() {
        com.hm.video.FixtureHttpServer {
            com.hm.video.FixtureResponse.text(
                "#EXTM3U\n#EXT-X-TARGETDURATION:4\n",
                "application/octet-stream"
            )
        }.use { server ->
            com.hm.video.player.MediaStreamProbe.clearCacheForTest()
            set("playInfo", PlayInfo("测试", "第一集", lines = listOf(
                PlayLine("线路", "${server.baseUrl}/play/1")
            )))

            call("startStream", 0, false)
            awaitMediaItem()

            assertEquals(MimeTypes.APPLICATION_M3U8, fake.mediaItem!!.localConfiguration!!.mimeType)
        }
    }

    @Test
    fun extensionlessUnknownStreamIsNotForcedToHls() {
        com.hm.video.FixtureHttpServer {
            com.hm.video.FixtureResponse.text("<html>not media</html>", "text/html")
        }.use { server ->
            com.hm.video.player.MediaStreamProbe.clearCacheForTest()
            set("playInfo", PlayInfo("测试", "第一集", lines = listOf(
                PlayLine("线路", "${server.baseUrl}/watch/2")
            )))

            call("startStream", 0, false)
            awaitMediaItem()

            assertNull(fake.mediaItem!!.localConfiguration!!.mimeType)
            assertEquals(server.baseUrl.substringAfter("//"), fake.mediaItem!!.localConfiguration!!.uri.authority)
        }
    }

    private fun awaitMediaItem() {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (fake.mediaItem == null && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertNotNull("media item was never prepared", fake.mediaItem)
    }

    private fun history(progress: Long) = HistoryItem(
        url = "/dianshiju/test/", title = "测试", poster = "", episodeNo = 1,
        episodeUrl = "/dianshiju/test/1-1.html", progressSec = progress, durationSec = 600
    )

    private fun episodeTwoHistory(progress: Long) = HistoryItem(
        url = "/dianshiju/test/", title = "测试", poster = "", episodeNo = 2,
        episodeUrl = "/dianshiju/test/1-2.html", progressSec = progress, durationSec = 600
    )

    private fun set(name: String, value: Any) = setOn(activity, name, value)

    private fun setOn(target: PlayerActivity, name: String, value: Any) {
        PlayerActivity::class.java.getDeclaredField(name).apply { isAccessible = true; set(target, value) }
    }

    private fun get(name: String): Any? = getOn(activity, name)

    private fun getOn(target: PlayerActivity, name: String): Any? =
        PlayerActivity::class.java.getDeclaredField(name).run { isAccessible = true; get(target) }

    private fun call(name: String, vararg args: Any) = callOn(activity, name, *args)

    private fun callOn(target: PlayerActivity, name: String, vararg args: Any) {
        PlayerActivity::class.java.declaredMethods.first { it.name == name && it.parameterCount == args.size }
            .apply { isAccessible = true }.invoke(target, *args)
    }

    /** Mirrors the order used by onCreate for a second activity instance. */
    private fun recreateWith(saved: android.os.Bundle): Pair<PlayerActivity, FakePlayer> {
        val second = Robolectric.buildActivity(PlayerActivity::class.java).get()
        second.setTheme(android.R.style.Theme_Material_NoActionBar)
        second.setContentView(R.layout.activity_player)
        callOn(second, "restorePlaybackState", saved)
        callOn(second, "initViews")
        val secondFake = FakePlayer()
        setOn(second, "exoPlayer", secondFake.player)
        setOn(second, "httpDataSourceFactory", DefaultHttpDataSource.Factory())
        setOn(second, "activityStarted", true)
        return second to secondFake
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
