package com.hm.video.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.*
import androidx.media3.common.util.UnstableApi
import com.hm.video.HMVideoApplication
import com.hm.video.R
import com.hm.video.data.api.PageCallScope
import com.hm.video.data.api.XingfanRepository
import com.hm.video.data.model.FavoriteItem
import com.hm.video.data.model.WorkLine
import com.hm.video.data.model.WorkDetail

class DetailActivity : Activity() {

    private var detailUrl: String = ""
    private var currentDetail: WorkDetail? = null
    private var activeLineId: Int = 1
    private var isDetailLoading = true
    private var detailLoadError: String? = null
    private var detailRequestId = 0
    private val pageCallScope = PageCallScope()
    private var tvMode = false
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detail)
        tvMode = DisplayProfile.isLeanbackLayout(this)
        if (tvMode) applyTvLayout()

        detailUrl = intent.getStringExtra("url").orEmpty()
        if (detailUrl.isEmpty()) {
            detailUrl = "/dianying/featured/"
        }

        findViewById<Button>(R.id.detail_btn_back).setOnClickListener { finish() }

        // Keep the title and poster responsive while real episode data loads.
        val initialDetail = createFallbackDetail(detailUrl)
        currentDetail = initialDetail
        findViewById<View>(R.id.detail_loading_view)?.visibility = View.GONE
        findViewById<View>(R.id.detail_content_scroll)?.visibility = View.VISIBLE
        renderDetail(initialDetail)

        loadDetail()
    }

    override fun onResume() {
        super.onResume()
        currentDetail?.let(::renderDetail)
    }

    private fun applyTvLayout() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val scroll = findViewById<ScrollView>(R.id.detail_content_scroll)
        (scroll.getChildAt(0) as LinearLayout).setPadding(dp(32), dp(20), dp(32), dp(24))
        findViewById<ImageView>(R.id.detail_poster).apply {
            layoutParams = layoutParams.apply { width = dp(176); height = dp(264) }
        }
        findViewById<TextView>(R.id.detail_title).textSize = 30f
        findViewById<TextView>(R.id.detail_meta).textSize = 18f
        findViewById<TextView>(R.id.detail_total_eps).textSize = 16f
        (findViewById<TextView>(R.id.detail_title).parent as LinearLayout).gravity = android.view.Gravity.CENTER_VERTICAL
        listOf(R.id.detail_btn_back, R.id.detail_btn_favorite, R.id.detail_btn_play_first).forEach { id ->
            findViewById<Button>(id).apply {
                textSize = 18f
                layoutParams.height = dp(44)
            }
        }
        findViewById<TextView>(R.id.detail_desc).apply {
            textSize = 17f
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            isFocusable = true
            contentDescription = "剧情简介，按确认展开或收起"
            setOnClickListener { maxLines = if (maxLines == 3) Int.MAX_VALUE else 3 }
        }
    }

    private fun loadDetail(forceRefresh: Boolean = false) {
        val requestId = ++detailRequestId
        pageCallScope.cancel()
        isDetailLoading = true
        detailLoadError = null
        currentDetail?.let(::renderDetail)
        Thread {
            try {
                val detail = XingfanRepository.getDetail(detailUrl, forceRefresh, pageCallScope)
                mainHandler.post {
                    if (requestId != detailRequestId || isFinishing || isDestroyed) return@post
                    isDetailLoading = false
                    currentDetail = detail
                    renderDetail(detail)
                }
            } catch (_: Exception) {
                mainHandler.post {
                    if (requestId != detailRequestId || isFinishing || isDestroyed) return@post
                    isDetailLoading = false
                    detailLoadError = "网络连接失败，影视详情暂时无法读取"
                    currentDetail?.let(::renderDetail)
                }
            }
        }.start()
    }

    private fun createFallbackDetail(url: String): WorkDetail {
        val title = intent.getStringExtra("title")?.ifEmpty { "精选影视" } ?: "精选影视"
        val poster = intent.getStringExtra("poster").orEmpty()
        val category = intent.getStringExtra("category") ?: "热播推荐"
        val year = intent.getStringExtra("year")
        return WorkDetail(
            title = title,
            url = url,
            poster = poster,
            category = category,
            year = year,
            description = "正在读取影视简介与可播放内容…",
            totalEpisodes = null,
            lines = emptyList()
        )
    }

    private fun renderDetail(detail: WorkDetail) {
        val posterIv = findViewById<ImageView>(R.id.detail_poster)
        com.hm.video.util.ImageLoader.load(posterIv, detail.poster)

        findViewById<TextView>(R.id.detail_title).text = detail.title
        val metaParts = listOfNotNull(detail.category, detail.year, detail.area)
        findViewById<TextView>(R.id.detail_meta).text = metaParts.joinToString(" · ")
        val epsText = if (detail.totalEpisodes != null) "共 ${detail.totalEpisodes} 集" else if (isDetailLoading) "正在读取剧集…" else "剧集信息暂缺"
        findViewById<TextView>(R.id.detail_total_eps).text = epsText
        findViewById<TextView>(R.id.detail_desc).text = detail.description ?: "暂无简介信息"

        val storage = HMVideoApplication.instance.storageManager
        val btnFav = findViewById<Button>(R.id.detail_btn_favorite)
        updateFavButtonState(btnFav, storage.isFavorite(detail.url))

        btnFav.setOnClickListener {
            val isNowFav = storage.toggleFavorite(
                FavoriteItem(
                    url = detail.url,
                    title = detail.title,
                    poster = detail.poster,
                    category = detail.category,
                    year = detail.year,
                    area = detail.area
                )
            )
            updateFavButtonState(btnFav, isNowFav)
            Toast.makeText(this, if (isNowFav) "已添加收藏" else "已取消收藏", Toast.LENGTH_SHORT).show()
        }

        val playFirstButton = findViewById<Button>(R.id.detail_btn_play_first)
        val selectedLine = detail.lines.firstOrNull { it.id == activeLineId && it.episodes.isNotEmpty() }
            ?: detail.lines.firstOrNull { it.episodes.isNotEmpty() }
        val firstEpisode = selectedLine?.episodes?.firstOrNull()
        val history = storage.getHistoryItem(detailUrl)?.takeIf { it.episodeUrl.isNotBlank() }
        playFirstButton.isEnabled = history != null || firstEpisode != null
        playFirstButton.alpha = if (playFirstButton.isEnabled) 1f else 0.55f
        playFirstButton.text = when {
            history != null -> "继续播放 · ${history.episodeTitle.ifBlank { "第${history.episodeNo}集" }}"
            isDetailLoading -> "正在读取…"
            firstEpisode != null -> " ▶ 立即播放 "
            else -> "暂无可播放剧集"
        }
        playFirstButton.setOnClickListener {
            if (history != null) {
                startPlayer(history.episodeUrl, history.episodeTitle, detail.title, history.episodeNo, detail.poster)
                return@setOnClickListener
            }
            val firstLine = detail.lines.firstOrNull { it.id == activeLineId && it.episodes.isNotEmpty() }
                ?: detail.lines.firstOrNull { it.episodes.isNotEmpty() }
            val firstEp = firstLine?.episodes?.firstOrNull()
            if (firstEp != null) {
                startPlayer(firstEp.url, firstEp.title, detail.title, firstEp.no, detail.poster)
            } else {
                Toast.makeText(this, if (isDetailLoading) "正在读取播放源，请稍候" else "暂无可播放剧集", Toast.LENGTH_SHORT).show()
            }
        }

        renderLines(detail)
    }

    private fun updateFavButtonState(btn: Button, isFav: Boolean) {
        if (isFav) {
            btn.text = "★ 已收藏"
            btn.setBackgroundResource(R.drawable.bg_pill_active)
            btn.setTextColor(Color.WHITE)
        } else {
            btn.text = "☆ 收藏"
            btn.setBackgroundResource(R.drawable.bg_pill)
            btn.setTextColor(Color.WHITE)
        }
    }

    private fun renderLines(detail: WorkDetail) {
        val linesContainer = findViewById<LinearLayout>(R.id.detail_lines_container)
        linesContainer.removeAllViews()

        if (detail.lines.isEmpty()) {
            val message = TextView(this).apply {
                text = when {
                    isDetailLoading -> "正在读取播放线路…"
                    detailLoadError != null -> "详情读取失败：${detailLoadError}\n可按下方按钮重试"
                    else -> "暂无可用播放线路或剧集，您可以稍后重试"
                }
                textSize = 13f
                setTextColor(Color.parseColor("#b8c2d1"))
                setPadding((12 * resources.displayMetrics.density).toInt(), (12 * resources.displayMetrics.density).toInt(), (12 * resources.displayMetrics.density).toInt(), (12 * resources.displayMetrics.density).toInt())
            }
            linesContainer.addView(message)
            if (!isDetailLoading) {
                val retry = Button(this).apply {
                    text = "重新读取"
                    setBackgroundResource(R.drawable.bg_pill)
                    setTextColor(Color.WHITE)
                    isFocusable = true
                    setOnClickListener { loadDetail(forceRefresh = true) }
                }
                linesContainer.addView(retry)
            }
            return
        }
        if (detail.lines.none { it.id == activeLineId }) activeLineId = detail.lines.first().id
        if (detail.lines.firstOrNull { it.id == activeLineId }?.episodes.isNullOrEmpty()) {
            activeLineId = detail.lines.firstOrNull { it.episodes.isNotEmpty() }?.id ?: detail.lines.first().id
        }

        for (line in detail.lines) {
            val btn = Button(this).apply {
                text = line.name
                textSize = if (tvMode) 18f else 12f
                val isActive = line.id == activeLineId
                tag = line.id
                isFocusable = true
                setBackgroundResource(if (isActive) R.drawable.bg_pill_active else R.drawable.bg_pill)
                setTextColor(Color.WHITE)
                val pad = (12 * resources.displayMetrics.density).toInt()
                setPadding(pad, 0, pad, 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    ((if (tvMode) 44 else 34) * resources.displayMetrics.density).toInt()
                ).apply {
                    setMargins(0, 0, (8 * resources.displayMetrics.density).toInt(), 0)
                }
                setOnClickListener {
                    activeLineId = line.id
                    renderLines(detail)
                    linesContainer.post { linesContainer.findViewWithTag<View>(line.id)?.requestFocus() }
                }
            }
            linesContainer.addView(btn)
        }

        val curLine = detail.lines.firstOrNull { it.id == activeLineId } ?: detail.lines.first()
        activeLineId = curLine.id
        renderEpisodes(curLine)
    }

    private fun renderEpisodes(line: WorkLine) {
        val epContainer = findViewById<LinearLayout>(R.id.detail_episodes_container)
        epContainer.removeAllViews()
        val density = resources.displayMetrics.density

        if (line.episodes.isEmpty()) {
            val emptyMessage = TextView(this).apply {
                text = "此线路没有提供可播放剧集，请选择其他线路或稍后重试。"
                textSize = 13f
                setTextColor(Color.parseColor("#b8c2d1"))
                setPadding((12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())
            }
            epContainer.addView(emptyMessage)
            return
        }

        var currentRow: LinearLayout? = null
        val itemsPerRow = if (tvMode) 6 else 4

        for ((idx, ep) in line.episodes.withIndex()) {
            if (idx % itemsPerRow == 0) {
                currentRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 0, (8 * density).toInt())
                    }
                }
                epContainer.addView(currentRow)
            }

            val epBtn = Button(this).apply {
                text = ep.title
                textSize = if (tvMode) 18f else 11f
                setBackgroundResource(R.drawable.bg_card)
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ((if (tvMode) 48 else 42) * density).toInt(),
                    1f
                ).apply {
                    setMargins((4 * density).toInt(), 0, (4 * density).toInt(), 0)
                }
                setOnClickListener {
                    currentDetail?.let { d ->
                    startPlayer(ep.url, ep.title, d.title, ep.no, d.poster)
                    }
                }
                contentDescription = "播放${ep.title}"
                isFocusable = true
            }
            currentRow?.addView(epBtn)
        }
        val remainder = line.episodes.size % itemsPerRow
        if (remainder != 0) {
            repeat(itemsPerRow - remainder) {
                currentRow?.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f).apply {
                        setMargins((4 * density).toInt(), 0, (4 * density).toInt(), 0)
                    }
                })
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                val current = currentFocus
                if (current != null && current.performClick()) {
                    return true
                }
            }
            KeyEvent.KEYCODE_BACK -> {
                finish()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                findViewById<Button>(R.id.detail_btn_play_first)?.performClick()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    private fun startPlayer(epUrl: String, epTitle: String, workTitle: String, episodeNo: Int, poster: String) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("ep_url", epUrl)
            putExtra("ep_title", epTitle)
            putExtra("work_title", workTitle)
            putExtra("detail_url", detailUrl)
            putExtra("episode_no", episodeNo)
            putExtra("poster", poster)
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        detailRequestId++
        pageCallScope.cancel()
        super.onDestroy()
    }
}
