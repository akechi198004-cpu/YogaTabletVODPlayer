package com.hm.video.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.*
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
import com.hm.video.data.api.XingfanConfig
import com.hm.video.data.api.XingfanRepository
import com.hm.video.data.model.HistoryItem
import com.hm.video.data.model.PlayInfo
import com.hm.video.data.model.PlayLine
import com.hm.video.player.HlsLocalProxy
import com.hm.video.player.LineProbe

@UnstableApi
class PlayerActivity : Activity() {
    private lateinit var playerView: PlayerView
    private lateinit var overlay: RelativeLayout
    private lateinit var seekBar: SeekBar
    private lateinit var timeDisplay: TextView
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnSpeed: Button
    private var btnOrientation: Button? = null
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
    private val mainHandler = Handler(Looper.getMainLooper())

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
        initViews()
        if (tvMode) {
            btnOrientation?.visibility = View.GONE
            btnTopOrientation?.visibility = View.GONE
        }
        initPlayer()
        loadPlayInfo()
    }

    private fun initPlayer() {
        httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(XingfanConfig.USER_AGENT)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(45000)
            .setAllowCrossProtocolRedirects(true)
        val dataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)
        exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
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
        btnOrientation = findViewById(R.id.player_btn_orientation)
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
        btnOrientation?.setOnClickListener { toggleOrientation() }
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
        val savedPosition = resumePositionMs
        if (savedPosition != null) {
            player.seekTo(savedPosition)
        } else if (!initialSeekApplied) {
            val hist = HMVideoApplication.instance.storageManager.getHistoryItem(detailUrl, epUrl)
            if (hist != null && hist.progressSec > 3) {
                val resumeAt = hist.progressSec * 1000
                player.seekTo(resumeAt)
                resumeToast.text = "已自动定位到上次播放 ${formatTime(resumeAt.toInt())}"
                resumeToast.visibility = View.VISIBLE
                mainHandler.postDelayed({ resumeToast.visibility = View.GONE }, 3500)
            }
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
        btnOrientation?.text = label
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
                val info = XingfanRepository.getPlayInfo(targetEpisodeUrl, forceRefresh)
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
        for ((idx, line) in info.lines.withIndex()) {
            val btn = Button(this).apply {
                text = lineMeasurements[idx]?.let { "${line.name} · ${it.elapsedMs}ms" } ?: line.name
                contentDescription = "${line.name}${if (idx == currentLineIndex) "，当前线路" else ""}，按确认切换"
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
        val playbackUrl = if (useProxy) HlsLocalProxy.getProxyUrl(line.url, referer) else line.url
        try {
            val player = exoPlayer ?: return
            player.stop()
            readyHandledForSource = false
            val mediaItem = MediaItem.Builder()
                .setUri(Uri.parse(playbackUrl))
                .apply { if (line.url.contains(".m3u8", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_M3U8) }
                .build()
            player.setMediaItem(mediaItem, resumePositionMs ?: 0L)
            player.playbackParameters = PlaybackParameters(currentSpeed)
            player.playWhenReady = activityStarted && playRequested
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
        exoPlayer?.playWhenReady = activityStarted && play
        if (!play) saveProgress(force = true)
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
        lineProbe?.cancel()
        lineProbe = null
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
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        exoPlayer?.playWhenReady = playRequested
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
}
