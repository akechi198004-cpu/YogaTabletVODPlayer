package com.hm.video.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.*
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.media3.common.util.UnstableApi
import com.hm.video.HMVideoApplication
import com.hm.video.R
import com.hm.video.data.api.PageCallScope
import com.hm.video.data.api.XingfanConfig
import com.hm.video.data.api.XingfanRepository
import com.hm.video.data.model.HistoryItem
import com.hm.video.data.model.PlayInfo
import com.hm.video.data.model.PlayLine
import com.hm.video.player.HlsLocalProxy
import com.hm.video.player.LineProbe
import com.hm.video.player.MediaStreamKind
import com.hm.video.player.MediaStreamProbe
import com.hm.video.util.lineLabels

@UnstableApi
class PlayerActivity : Activity() {
    private lateinit var playerView: PlayerView
    private lateinit var overlay: RelativeLayout
    private lateinit var seekBar: SeekBar
    private lateinit var timeDisplay: TextView
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnSpeed: Button
    private var btnTopOrientation: Button? = null
    private lateinit var loadingLayout: View
    private lateinit var resumeToast: TextView

    private var epUrl = ""
    private var epTitle = ""
    private var workTitle = ""
    private var detailUrl = ""
    private var exoPlayer: ExoPlayer? = null
    private lateinit var httpDataSourceFactory: DefaultHttpDataSource.Factory
    private var playInfo: PlayInfo? = null
    private var currentLineIndex = 0
    private var currentlyUsingProxy = false
    private var isVideoPrepared = false
    private var currentSpeed = 1.0f
    private val speeds = floatArrayOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    private var speedIndex = 1
    private var isTracking = false
    private var initialSeekApplied = false
    private var retryingCurrentLine = false
    private var readyHandledForSource = false
    private var terminalPlaybackError = false
    private var loadGeneration = 0
    private var streamGeneration = 0
    private var lineProbe: LineProbe? = null
    private var lineOrder = emptyList<Int>()
    private var lineMeasurements = emptyMap<Int, LineProbe.Result>()
    private val failedLines = mutableSetOf<Int>()
    private var episodeNo = 1
    private var activityStarted = false
    private var playRequested = true
    private var loadedEpisodeUrl = ""
    private var resumePositionMs: Long? = null
    private var lastProgressSavedAt = 0L
    private var posterUrl = ""
    private val pageCallScope = PageCallScope()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var audioManager: AudioManager? = null
    /** True while playback is held back because another app owns audio focus. */
    private var audioFocusLost = false
    /** Only a successful focus request (or return from our own transient loss) permits playback. */
    private var audioFocusGranted = false
    /** Do not retry a denied request automatically on a later lifecycle transition. */
    private var audioFocusRequestFailed = false
    /** Only a transient loss resumes on its own, and only while this screen is started. */
    private var resumeOnFocusGain = false
    private var noisyReceiverRegistered = false

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        handleAudioFocusChange(change)
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) handleBecomingNoisy()
        }
    }

    private val hideOverlayRunnable = Runnable {
        if (exoPlayer?.isPlaying == true && overlay.visibility == View.VISIBLE) overlay.visibility = View.GONE
    }
    private val updateProgressRunnable = object : Runnable {
        override fun run() {
            val player = exoPlayer
            if (player?.isPlaying == true && !isTracking) {
                val current = player.currentPosition.toInt()
                val duration = player.duration.toInt()
                if (duration > 0) {
                    seekBar.progress = (current * 1000L / duration).toInt()
                    timeDisplay.text = "${formatTime(current)} / ${formatTime(duration)}"
                    saveProgress()
                }
            }
            mainHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tvMode = (getSystemService(UI_MODE_SERVICE) as? android.app.UiModeManager)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            !packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)
        if (tvMode) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        setContentView(R.layout.activity_player)
        epUrl = intent.getStringExtra("ep_url").orEmpty()
        epTitle = intent.getStringExtra("ep_title").orEmpty()
        workTitle = intent.getStringExtra("work_title").orEmpty()
        detailUrl = intent.getStringExtra("detail_url").orEmpty()
        posterUrl = intent.getStringExtra("poster").orEmpty()
        episodeNo = intent.getIntExtra("episode_no", 1).coerceAtLeast(1)
        // A recreated activity must continue the episode it was actually playing, not the one
        // named by the original intent, or a background kill would rewind and overwrite history.
        restorePlaybackState(savedInstanceState)
        initViews()
        if (tvMode) {
            btnTopOrientation?.visibility = View.GONE
        }
        audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager
        initPlayer()
        loadPlayInfo()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_EP_URL, epUrl)
        outState.putString(KEY_EP_TITLE, epTitle)
        outState.putString(KEY_WORK_TITLE, workTitle)
        outState.putString(KEY_DETAIL_URL, detailUrl)
        outState.putString(KEY_POSTER, posterUrl)
        outState.putString(KEY_LOADED_EP, loadedEpisodeUrl)
        outState.putInt(KEY_EPISODE_NO, episodeNo)
        outState.putFloat(KEY_SPEED, currentSpeed)
        // Before READY the player sits at 0 while resumePositionMs already holds the real spot.
        outState.putLong(KEY_POSITION_MS, maxOf(exoPlayer?.currentPosition ?: 0L, resumePositionMs ?: 0L))
        // An auto-pause (focus loss, unplugged headset) must not become an auto-play on restore.
        outState.putBoolean(KEY_PLAY_REQUESTED, playRequested && !audioFocusLost)
        outState.putBoolean(KEY_FOCUS_REQUEST_FAILED, audioFocusRequestFailed)
        outState.putBoolean(KEY_INITIAL_SEEK_DONE, initialSeekApplied)
    }

    /**
     * Restores the current episode and playback intent. Only page-level URLs are stored; signed
     * stream links stay in memory and are re-resolved after a process death.
     */
    private fun restorePlaybackState(state: Bundle?) {
        if (state == null) return
        epUrl = state.getString(KEY_EP_URL) ?: epUrl
        epTitle = state.getString(KEY_EP_TITLE) ?: epTitle
        workTitle = state.getString(KEY_WORK_TITLE) ?: workTitle
        detailUrl = state.getString(KEY_DETAIL_URL) ?: detailUrl
        posterUrl = state.getString(KEY_POSTER) ?: posterUrl
        // Treat the restored episode as already loaded so loadPlayInfo() keeps the position
        // instead of clearing resumePositionMs as it would for a brand-new episode.
        loadedEpisodeUrl = state.getString(KEY_LOADED_EP) ?: epUrl
        episodeNo = state.getInt(KEY_EPISODE_NO, episodeNo).coerceAtLeast(1)
        val restoredSpeed = state.getFloat(KEY_SPEED, currentSpeed)
        val restoredIndex = speeds.indexOfFirst { kotlin.math.abs(it - restoredSpeed) < 0.001f }
        if (restoredIndex >= 0) {
            speedIndex = restoredIndex
            currentSpeed = speeds[restoredIndex]
        }
        val position = state.getLong(KEY_POSITION_MS, 0L)
        if (position > 0L) resumePositionMs = position
        playRequested = state.getBoolean(KEY_PLAY_REQUESTED, playRequested)
        audioFocusRequestFailed = state.getBoolean(KEY_FOCUS_REQUEST_FAILED, false)
        initialSeekApplied = state.getBoolean(KEY_INITIAL_SEEK_DONE, false)
    }

    private fun initPlayer() {
        httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(XingfanConfig.USER_AGENT)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(45000)
            .setAllowCrossProtocolRedirects(true)
        val dataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            // Focus loss and headset unplug are handled here so playRequested stays authoritative:
            // an automatic pause must never silently turn into an automatic resume.
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ false)
            .setHandleAudioBecomingNoisy(false)
            .build().also { player ->
                playerView.player = player
                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_READY -> onPlayerReady()
                            Player.STATE_ENDED -> playNextEpisodeIfAvailable()
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) = syncPlaybackButton()

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        isVideoPrepared = false
                        handleStreamFailure(error)
                    }
                })
            }
    }

    private fun initViews() {
        playerView = findViewById(R.id.video_view)
        playerView.useController = false
        playerView.isFocusable = true
        overlay = findViewById(R.id.player_controls_overlay)
        seekBar = findViewById(R.id.player_seekbar)
        timeDisplay = findViewById(R.id.player_time_display)
        btnPlayPause = findViewById(R.id.player_btn_play_pause)
        btnSpeed = findViewById(R.id.player_btn_speed)
        btnSpeed.text = "${currentSpeed}x"
        btnTopOrientation = findViewById(R.id.player_btn_top_orientation)
        loadingLayout = findViewById(R.id.player_loading_layout)
        resumeToast = findViewById(R.id.player_resume_toast)

        findViewById<TextView>(R.id.player_title).text = workTitle
        findViewById<TextView>(R.id.player_ep_badge).text = epTitle
        findViewById<Button>(R.id.player_btn_back).setOnClickListener { finish() }
        btnPlayPause.setOnClickListener { togglePlayback() }
        findViewById<ImageButton>(R.id.player_btn_rewind).setOnClickListener { seekBy(-10000) }
        findViewById<ImageButton>(R.id.player_btn_forward).setOnClickListener { seekBy(10000) }
        btnSpeed.setOnClickListener {
            speedIndex = (speedIndex + 1) % speeds.size
            currentSpeed = speeds[speedIndex]
            btnSpeed.text = "${currentSpeed}x"
            exoPlayer?.playbackParameters = PlaybackParameters(currentSpeed)
        }
        findViewById<Button>(R.id.player_btn_next_ep).setOnClickListener { playNextEpisodeIfAvailable() }
        btnTopOrientation?.setOnClickListener { toggleOrientation() }
        updateOrientationUI(resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val duration = exoPlayer?.duration ?: 0L
                if (fromUser && duration > 0) {
                    val target = progress * duration / 1000
                    timeDisplay.text = "${formatTime(target.toInt())} / ${formatTime(duration.toInt())}"
                    if (isVideoPrepared && !isTracking) exoPlayer?.seekTo(target)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { isTracking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                isTracking = false
                val player = exoPlayer ?: return
                if (isVideoPrepared && player.duration > 0) player.seekTo(seekBar.progress * player.duration / 1000)
            }
        })
        seekBar.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                seekBy(if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -10000 else 10000)
                true
            } else false
        }

        findViewById<View>(R.id.player_root).setOnClickListener {
            if (overlay.visibility == View.VISIBLE) {
                overlay.visibility = View.GONE
                mainHandler.removeCallbacks(hideOverlayRunnable)
            } else showOverlayWithTimeout()
        }
    }

    private fun onPlayerReady() {
        android.util.Log.i("HMPlayer", "ready durationMs=" + (exoPlayer?.duration ?: 0L) +
            " positionMs=" + (exoPlayer?.currentPosition ?: 0L) +
            " line=" + currentLineIndex + " proxy=" + currentlyUsingProxy)
        isVideoPrepared = true
        retryingCurrentLine = false
        terminalPlaybackError = false
        loadingLayout.visibility = View.GONE
        loadingLayout.setOnClickListener(null)
        loadingLayout.isFocusable = false
        loadingLayout.isClickable = false
        val player = exoPlayer ?: return
        if (readyHandledForSource) {
            syncPlaybackButton()
            return
        }
        readyHandledForSource = true
        val history = HMVideoApplication.instance.storageManager.getHistoryItem(detailUrl, epUrl)
        val historyMs = history?.progressSec?.takeIf { it > 3 }?.times(1000) ?: 0L
        val savedPosition = resumePositionMs
        if (savedPosition != null) {
            // A restored bundle can be slightly older than the last flushed history entry for the
            // same episode, so resume from whichever position is further along.
            player.seekTo(maxOf(savedPosition, historyMs))
        } else if (!initialSeekApplied && historyMs > 0L) {
            player.seekTo(historyMs)
            resumeToast.text = "已自动定位到上次播放 ${formatTime(historyMs.toInt())}"
            resumeToast.visibility = View.VISIBLE
            mainHandler.postDelayed({ resumeToast.visibility = View.GONE }, 3500)
        }
        resumePositionMs = null
        initialSeekApplied = true
        syncPlaybackButton()
        if (overlay.visibility != View.VISIBLE) btnPlayPause.requestFocus()
        showOverlayWithTimeout()
        mainHandler.removeCallbacks(updateProgressRunnable)
        mainHandler.post(updateProgressRunnable)
        HMVideoApplication.instance.storageManager.recordHistory(
            HistoryItem(
                url = detailUrl,
                title = workTitle,
                poster = posterUrl,
                lineId = currentLineIndex + 1,
                lineName = playInfo?.lines?.getOrNull(currentLineIndex)?.name,
                episodeNo = episodeNo,
                episodeTitle = epTitle,
                episodeUrl = epUrl
            )
        )
    }

    private fun handleStreamFailure(error: androidx.media3.common.PlaybackException) {
        android.util.Log.w("HMPlayer", "stream failure code=" + error.errorCodeName +
            " line=" + currentLineIndex + " proxy=" + currentlyUsingProxy +
            " msg=" + error.message, error)
        val issueHint = if (error.errorCodeName.contains("NETWORK") || error.errorCodeName.contains("TIMEOUT")) {
            "网络连接中断或超时。"
        } else {
            "当前线路的视频格式或服务器响应暂时无法播放。"
        }
        val line = playInfo?.lines?.getOrNull(currentLineIndex)
        if (line != null && !currentlyUsingProxy && line.url.startsWith("http") && !retryingCurrentLine) {
            retryingCurrentLine = true
            showPlaybackMessage("正在重试当前线路", "视频流连接失败，正在尝试兼容播放方式…")
            startStream(currentLineIndex, useProxy = true)
            return
        }
        retryingCurrentLine = false
        failedLines.add(currentLineIndex)
        val lines = playInfo?.lines.orEmpty()
        val nextIndex = (lineOrder.ifEmpty { lines.indices.toList() }).firstOrNull { it !in failedLines }
        if (nextIndex != null) {
            currentLineIndex = nextIndex
            renderPlayLines(playInfo!!)
            showPlaybackMessage("当前线路无法播放，正在切换", "正在尝试第 ${nextIndex + 1} 条已解析线路…")
            startStream(nextIndex)
        } else {
            val diagnostic = if (line == null) "" else "\n线路：${line.name}"
            showPlaybackMessage(
                "暂时无法播放",
                "已尝试可用播放方式，但视频服务未能开始播放。$issueHint 请检查网络，或返回详情页选择其他剧集。$diagnostic",
                retryAction = { loadPlayInfo(forceRefresh = true) }
            )
        }
    }

    private fun showOverlayWithTimeout() {
        overlay.visibility = View.VISIBLE
        mainHandler.removeCallbacks(hideOverlayRunnable)
        mainHandler.postDelayed(hideOverlayRunnable, 5000)
    }

    private fun toggleOrientation() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (landscape) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            updateOrientationUI(false)
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            updateOrientationUI(true)
        }
    }

    private fun updateOrientationUI(isLandscape: Boolean) {
        val label = if (isLandscape) "📱 竖屏" else "🔄 横屏"
        btnTopOrientation?.text = label
        window.decorView.systemUiVisibility = if (isLandscape) (
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            ) else View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateOrientationUI(newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) updateOrientationUI(true)
    }

    private fun loadPlayInfo() {
        loadPlayInfo(forceRefresh = false)
    }

    private fun loadPlayInfo(forceRefresh: Boolean) {
        if (loadedEpisodeUrl == epUrl) {
            rememberPlaybackPosition()
        } else {
            loadedEpisodeUrl = epUrl
            resumePositionMs = null
            initialSeekApplied = false
            playRequested = true
        }
        val generation = ++loadGeneration
        streamGeneration++
        val ticket = pageCallScope.newRequest()
        lineProbe?.cancel()
        lineProbe = null
        lineOrder = emptyList()
        lineMeasurements = emptyMap()
        failedLines.clear()
        val targetEpisodeUrl = epUrl
        isVideoPrepared = false
        currentLineIndex = 0
        currentlyUsingProxy = false
        retryingCurrentLine = false
        playInfo = null
        readyHandledForSource = false
        terminalPlaybackError = false
        exoPlayer?.stop()
        mainHandler.removeCallbacks(updateProgressRunnable)
        renderPlayLines(PlayInfo(workTitle, epTitle, lines = emptyList()))
        showPlaybackMessage("正在解析播放源", "正在读取 $epTitle 的实际线路，请稍候…")

        if (epUrl.startsWith("http") && (epUrl.contains(".m3u8") || epUrl.contains(".mp4"))) {
            val directInfo = PlayInfo(workTitle.ifEmpty { "影视精选" }, epTitle.ifEmpty { "正片" }, lines = listOf(PlayLine("视频源", epUrl)))
            playInfo = directInfo
            renderPlayLines(directInfo)
            startStream(0)
            return
        }

        Thread {
            try {
                val info = XingfanRepository.getPlayInfo(targetEpisodeUrl, forceRefresh, ticket)
                mainHandler.post {
                    if (generation != loadGeneration || isFinishing || isDestroyed) return@post
                    if (info.lines.isEmpty()) {
                        showPlaybackMessage("没有找到可播放线路", "这集暂时没有可用播放源。请返回详情页更换剧集或稍后重试。", retryAction = { loadPlayInfo(forceRefresh = true) })
                    } else {
                        playInfo = info
                        selectBestLine(info, generation)
                    }
                }
            } catch (error: Exception) {
                mainHandler.post {
                    if (generation != loadGeneration || isFinishing || isDestroyed) return@post
                    showPlaybackMessage(
                        "播放源解析失败",
                        "${friendlyPlaybackError(error)}\n请检查网络，或返回详情页更换剧集后重试。",
                        retryAction = { loadPlayInfo(forceRefresh = true) }
                    )
                }
            }
        }.start()
    }

    private fun friendlyPlaybackError(error: Exception): String {
        val message = error.message.orEmpty().lowercase()
        return when {
            message.contains("http 403") -> "播放服务拒绝了当前请求，线路可能暂时不可用。"
            message.contains("http 404") -> "这集的播放页面或线路已失效。"
            message.contains("http 5") -> "播放服务暂时繁忙，请稍后再试。"
            message.contains("未能从播放页面") || message.contains("解析失败") -> "播放页面没有返回可识别的线路。"
            else -> "网络中断，或播放页面暂时无法读取。"
        }
    }

    private fun selectBestLine(info: PlayInfo, generation: Int) {
        lineOrder = info.lines.indices.toList()
        if (info.lines.size < 2) {
            renderPlayLines(info)
            startStream(0)
            return
        }
        showPlaybackMessage("正在优选播放线路", "正在并发测试 ${info.lines.size} 条线路，最多等待 8 秒…")
        val probe = LineProbe()
        lineProbe = probe
        val referer = info.serviceUrl?.takeIf { it.isNotBlank() } ?: epUrl.ifBlank { XingfanConfig.SOURCE_BASE }
        Thread {
            val results = try { probe.measure(info.lines, referer) } catch (_: Exception) { emptyList() }
            mainHandler.post {
                if (generation != loadGeneration || lineProbe !== probe || isFinishing || isDestroyed) return@post
                lineProbe = null
                lineMeasurements = results.associateBy { it.index }
                lineOrder = results.map { it.index } + info.lines.indices.filter { it !in lineMeasurements }
                currentLineIndex = lineOrder.first()
                android.util.Log.i("HMLineProbe", "Measured ${results.size}/${info.lines.size}; selected=${currentLineIndex + 1}; samples=" + results.joinToString { "${it.index + 1}:${it.elapsedMs}ms/${it.bytes}B" })
                renderPlayLines(info)
                startStream(currentLineIndex)
            }
        }.start()
    }

    private fun showPlaybackMessage(title: String, subtitle: String, retryAction: (() -> Unit)? = null) {
        terminalPlaybackError = retryAction != null && !title.startsWith("正在")
        overlay.visibility = View.GONE
        mainHandler.removeCallbacks(hideOverlayRunnable)
        loadingLayout.visibility = View.VISIBLE
        loadingLayout.findViewById<ProgressBar>(R.id.loading_spinner)?.visibility =
            if (retryAction == null && title.startsWith("正在")) View.VISIBLE else View.GONE
        loadingLayout.findViewById<ProgressBar>(R.id.loading_horizontal_bar)?.visibility =
            if (retryAction == null && title.startsWith("正在")) View.VISIBLE else View.GONE
        loadingLayout.findViewById<TextView>(R.id.loading_main_title)?.text = title
        loadingLayout.findViewById<TextView>(R.id.loading_sub_title)?.text = subtitle
        loadingLayout.findViewById<TextView>(R.id.loading_bottom_tip)?.text =
            if (retryAction != null) "按确认键或点击此处重试 · 按返回键退出播放器" else "按返回键可回到详情页"
        loadingLayout.isClickable = retryAction != null
        loadingLayout.isFocusable = retryAction != null
        loadingLayout.isFocusableInTouchMode = retryAction != null
        loadingLayout.setOnClickListener { retryAction?.invoke() }
        if (retryAction != null) loadingLayout.requestFocus()
    }

    private fun renderPlayLines(info: PlayInfo) {
        val linesRow = findViewById<LinearLayout>(R.id.player_lines_row)
        linesRow.removeAllViews()
        val density = resources.displayMetrics.density
        val labels = lineLabels(info.lines)
        for ((idx, line) in info.lines.withIndex()) {
            val label = labels.getOrElse(idx) { line.name }
            val btn = Button(this).apply {
                text = lineMeasurements[idx]?.let { "$label · ${it.elapsedMs}ms" } ?: label
                contentDescription = "$label（${line.name}）${if (idx == currentLineIndex) "，当前线路" else ""}，按确认切换"
                textSize = 14f
                setBackgroundResource(if (idx == currentLineIndex) R.drawable.bg_pill_active else R.drawable.bg_pill)
                setTextColor(Color.WHITE)
                isFocusable = true
                val pad = (12 * density).toInt()
                setPadding(pad, 0, pad, 0)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (44 * density).toInt()).apply {
                    setMargins(0, 0, (8 * density).toInt(), 0)
                }
                setOnClickListener {
                    currentLineIndex = idx
                    failedLines.clear()
                    retryingCurrentLine = false
                    renderPlayLines(info)
                    startStream(idx)
                }
            }
            linesRow.addView(btn)
        }
    }

    private fun startStream(lineIndex: Int, useProxy: Boolean = false) {
        val line = playInfo?.lines?.getOrNull(lineIndex) ?: return
        rememberPlaybackPosition()
        isVideoPrepared = false
        currentlyUsingProxy = useProxy
        showPlaybackMessage("正在连接【${line.name}】", "正在缓冲视频流，请稍候…")
        val referer = playInfo?.serviceUrl?.takeIf { it.isNotBlank() }
            ?: epUrl.ifBlank { XingfanConfig.SOURCE_BASE }
        httpDataSourceFactory.setDefaultRequestProperties(mapOf("Referer" to referer))
        val generation = ++streamGeneration
        val fromPath = MediaStreamProbe.inferFromPath(line.url)
        if (fromPath != null) {
            beginPlayback(line, lineIndex, useProxy, referer, fromPath)
            return
        }
        // The URL alone proves nothing (extensionless or redirected endpoints), so sniff the
        // real response before Media3 has to choose between HLS and progressive playback.
        Thread {
            val kind = MediaStreamProbe.kindFor(line.url, referer)
            mainHandler.post {
                if (generation != streamGeneration || isFinishing || isDestroyed) return@post
                if (playInfo?.lines?.getOrNull(lineIndex)?.url != line.url) return@post
                beginPlayback(line, lineIndex, useProxy, referer, kind)
            }
        }.start()
    }

    private fun beginPlayback(
        line: PlayLine,
        lineIndex: Int,
        useProxy: Boolean,
        referer: String,
        kind: MediaStreamKind
    ) {
        val playbackUrl = if (useProxy) {
            HlsLocalProxy.getProxyUrl(line.url, referer, kind == MediaStreamKind.HLS)
        } else {
            line.url
        }
        android.util.Log.i("HMPlayer", "start line='" + line.name + "' index=" + lineIndex +
            " proxy=" + useProxy + " kind=" + kind + " url=" + line.url.take(140))
        try {
            val player = exoPlayer ?: return
            player.stop()
            readyHandledForSource = false
            val path = okhttp3.HttpUrl.parse(line.url)?.encodedPath()?.lowercase().orEmpty()
            val mediaItem = MediaItem.Builder()
                .setUri(Uri.parse(playbackUrl))
                .apply {
                    when (kind) {
                        // Only claim HLS when the response proved it; an `.m3u8` in a query
                        // string must not turn an MP4 into a playlist.
                        MediaStreamKind.HLS -> setMimeType(MimeTypes.APPLICATION_M3U8)
                        MediaStreamKind.PROGRESSIVE ->
                            if (path.endsWith(".mp4") || path.endsWith(".m4v")) {
                                setMimeType(MimeTypes.VIDEO_MP4)
                            }
                        MediaStreamKind.UNKNOWN -> Unit
                    }
                }
                .build()
            player.setMediaItem(mediaItem, resumePositionMs ?: 0L)
            player.playbackParameters = PlaybackParameters(currentSpeed)
            applyPlayWhenReady()
            player.prepare()
        } catch (error: Exception) {
            handleStreamFailure(error as? androidx.media3.common.PlaybackException
                ?: androidx.media3.common.PlaybackException("无法打开播放线路", error, 1001))
        }
    }

    private fun togglePlayback() {
        val player = exoPlayer ?: return
        if (!isVideoPrepared && terminalPlaybackError) {
            Toast.makeText(this, "请先选择重试或返回详情页", Toast.LENGTH_SHORT).show()
        } else if (!isVideoPrepared) {
            Toast.makeText(this, "视频正在连接，请稍候", Toast.LENGTH_SHORT).show()
        } else if (player.playWhenReady) {
            requestPlayback(false)
            syncPlaybackButton()
        } else {
            requestPlayback(true)
            syncPlaybackButton()
        }
    }

    private fun requestPlayback(play: Boolean) {
        playRequested = play
        if (play) {
            audioFocusLost = false
            resumeOnFocusGain = false
            requestAudioFocus()
        }
        applyPlayWhenReady()
        if (!play) {
            audioFocusGranted = false
            audioFocusRequestFailed = false
            saveProgress(force = true)
            abandonAudioFocus()
        }
    }

    /** The only place that turns user intent into playWhenReady, so pause reasons stay explicit. */
    private fun applyPlayWhenReady() {
        exoPlayer?.playWhenReady = activityStarted && playRequested && audioFocusGranted && !audioFocusLost
    }

    private fun requestAudioFocus(): Boolean {
        val result = audioManager?.requestAudioFocus(
            audioFocusListener,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN
        ) ?: AudioManager.AUDIOFOCUS_REQUEST_FAILED
        audioFocusGranted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        audioFocusRequestFailed = !audioFocusGranted
        if (!audioFocusGranted) {
            audioFocusLost = false
            resumeOnFocusGain = false
        }
        return audioFocusGranted
    }

    private fun abandonAudioFocus() {
        audioManager?.abandonAudioFocus(audioFocusListener)
    }

    /**
     * A transient loss resumes when focus comes back; a permanent loss clears the play intent.
     * Headset unplug is handled separately by [handleBecomingNoisy].
     */
    private fun handleAudioFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                audioFocusLost = true
                audioFocusGranted = false
                audioFocusRequestFailed = false
                resumeOnFocusGain = false
                playRequested = false
                exoPlayer?.volume = 1f
                applyPlayWhenReady()
                saveProgress(force = true)
                syncPlaybackButton()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                audioFocusLost = true
                audioFocusGranted = false
                resumeOnFocusGain = true
                applyPlayWhenReady()
                saveProgress(force = true)
                syncPlaybackButton()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                exoPlayer?.volume = 0.25f
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                exoPlayer?.volume = 1f
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    audioFocusLost = false
                    audioFocusGranted = true
                }
                // A stray GAIN callback cannot turn a previously denied request into playback.
                if (!audioFocusRequestFailed) applyPlayWhenReady()
                syncPlaybackButton()
            }
        }
    }

    /** Unplugging headphones is an explicit stop signal: do not resume by itself later. */
    private fun handleBecomingNoisy() {
        playRequested = false
        audioFocusLost = false
        audioFocusGranted = false
        audioFocusRequestFailed = false
        resumeOnFocusGain = false
        applyPlayWhenReady()
        saveProgress(force = true)
        abandonAudioFocus()
        syncPlaybackButton()
    }

    private fun registerNoisyReceiver() {
        if (noisyReceiverRegistered) return
        try {
            registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            noisyReceiverRegistered = true
        } catch (_: Exception) {
            // Best effort: playback still works without the unplug broadcast.
        }
    }

    private fun unregisterNoisyReceiver() {
        if (!noisyReceiverRegistered) return
        noisyReceiverRegistered = false
        try {
            unregisterReceiver(noisyReceiver)
        } catch (_: Exception) {
            // Already detached with the activity.
        }
    }

    /** Keep the last usable position even if the replacement source fails before READY. */
    private fun rememberPlaybackPosition() {
        if (!readyHandledForSource) return
        resumePositionMs = exoPlayer?.currentPosition?.coerceAtLeast(0L)
        saveProgress(force = true)
    }

    private fun saveProgress(force: Boolean = false) {
        val player = exoPlayer ?: return
        if (!readyHandledForSource || player.duration <= 0) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastProgressSavedAt < 15_000L) return
        HMVideoApplication.instance.storageManager.updateProgress(
            detailUrl, epUrl, episodeNo, player.currentPosition.coerceAtLeast(0L) / 1000L, player.duration / 1000L
        )
        lastProgressSavedAt = now
    }

    private fun seekBy(offset: Long) {
        val player = exoPlayer ?: return
        if (!isVideoPrepared || player.duration <= 0) return
        val target = (player.currentPosition + offset).coerceIn(0, player.duration)
        player.seekTo(target)
        seekBar.progress = (target * 1000 / player.duration).toInt()
        timeDisplay.text = "${formatTime(target.toInt())} / ${formatTime(player.duration.toInt())}"
        showOverlayWithTimeout()
    }

    private fun playNextEpisodeIfAvailable() {
        val next = playInfo?.nextEpisodeUrl
        if (!next.isNullOrEmpty()) {
            saveProgress(force = true)
            epUrl = next
            episodeNo = (playInfo?.episodeNo ?: episodeNo) + 1
            epTitle = "第 ${episodeNo} 集"
            findViewById<TextView>(R.id.player_ep_badge).text = epTitle
            loadPlayInfo()
        } else if (exoPlayer?.playbackState != Player.STATE_ENDED) {
            Toast.makeText(this, "已经是最后一集", Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatTime(millis: Int): String {
        val sec = millis / 1000
        return String.format("%02d:%02d", sec / 60, sec % 60)
    }

    override fun onDestroy() {
        saveProgress(force = true)
        loadGeneration++
        streamGeneration++
        lineProbe?.cancel()
        lineProbe = null
        pageCallScope.cancel()
        unregisterNoisyReceiver()
        abandonAudioFocus()
        mainHandler.removeCallbacksAndMessages(null)
        playerView.player = null
        exoPlayer?.release()
        exoPlayer = null
        super.onDestroy()
    }

    override fun onStop() {
        activityStarted = false
        saveProgress(force = true)
        exoPlayer?.pause()
        // A pause caused by focus loss must not become an automatic resume on return.
        if (audioFocusLost) playRequested = false
        audioFocusLost = false
        resumeOnFocusGain = false
        audioFocusGranted = false
        unregisterNoisyReceiver()
        abandonAudioFocus()
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        registerNoisyReceiver()
        if (playRequested && !audioFocusRequestFailed) requestAudioFocus()
        applyPlayWhenReady()
        syncPlaybackButton()
    }

    private fun syncPlaybackButton() {
        val playing = exoPlayer?.playWhenReady == true
        btnPlayPause.setImageResource(if (playing) R.drawable.ic_player_pause else R.drawable.ic_player_play)
        btnPlayPause.contentDescription = if (playing) "暂停" else "播放"
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (loadingLayout.visibility == View.VISIBLE && keyCode in intArrayOf(
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU)) {
            if (terminalPlaybackError) loadingLayout.requestFocus()
            return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                if (loadingLayout.visibility == View.VISIBLE) {
                    val focused = currentFocus
                    if (focused?.performClick() != true && terminalPlaybackError) loadingLayout.performClick()
                    return true
                }
                if (overlay.visibility != View.VISIBLE) {
                    overlay.visibility = View.VISIBLE
                    btnPlayPause.requestFocus()
                    return true
                }
                showOverlayWithTimeout()
                val focused = currentFocus
                if (focused != null && focused !== playerView && focused.performClick()) return true
                togglePlayback()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-10000); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (overlay.visibility == View.VISIBLE && currentFocus != null && currentFocus !== playerView) {
                    showOverlayWithTimeout()
                    return super.onKeyDown(keyCode, event)
                }
                seekBy(-10000)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(10000); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (overlay.visibility == View.VISIBLE && currentFocus != null && currentFocus !== playerView) {
                    showOverlayWithTimeout()
                    return super.onKeyDown(keyCode, event)
                }
                seekBy(10000)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { if (isVideoPrepared) requestPlayback(true); syncPlaybackButton(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { requestPlayback(false); syncPlaybackButton(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePlayback(); return true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU -> {
                if (overlay.visibility != View.VISIBLE) {
                    overlay.visibility = View.VISIBLE
                    btnPlayPause.requestFocus()
                } else if (currentFocus == null || currentFocus === playerView) {
                    overlay.visibility = View.GONE
                } else {
                    showOverlayWithTimeout()
                    return super.onKeyDown(keyCode, event)
                }
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (overlay.visibility == View.VISIBLE) {
                    overlay.visibility = View.GONE
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private companion object {
        const val KEY_EP_URL = "hm.player.ep_url"
        const val KEY_EP_TITLE = "hm.player.ep_title"
        const val KEY_WORK_TITLE = "hm.player.work_title"
        const val KEY_DETAIL_URL = "hm.player.detail_url"
        const val KEY_POSTER = "hm.player.poster"
        const val KEY_LOADED_EP = "hm.player.loaded_ep"
        const val KEY_EPISODE_NO = "hm.player.episode_no"
        const val KEY_SPEED = "hm.player.speed"
        const val KEY_POSITION_MS = "hm.player.position_ms"
        const val KEY_PLAY_REQUESTED = "hm.player.play_requested"
        const val KEY_FOCUS_REQUEST_FAILED = "hm.player.focus_request_failed"
        const val KEY_INITIAL_SEEK_DONE = "hm.player.initial_seek_done"
    }
}
