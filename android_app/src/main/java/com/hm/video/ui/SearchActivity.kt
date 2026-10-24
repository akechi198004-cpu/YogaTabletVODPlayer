package com.hm.video.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import com.hm.video.R
import com.hm.video.HMVideoApplication
import com.hm.video.data.api.XingfanRepository
import com.hm.video.data.model.WorkItem
import com.hm.video.remoteinput.LanAddresses
import com.hm.video.remoteinput.PhoneInputServer
import com.hm.video.remoteinput.QrCode
import com.hm.video.util.LoadingViewHelper

class SearchActivity : Activity() {

    private val hotKeywords = listOf(
        "繁花", "漫长的季节", "三体", "庆余年", "狂飙",
        "奥本海默", "沙丘", "一人之下", "进击的巨人", "咒术回战"
    )

    private val searchHistory = mutableListOf<String>()
    private var currentCid: Int? = null
    private var currentPage = 1
    private var totalPages = 1
    private var searchRequestId = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var searchInput: EditText
    private lateinit var phoneInputButton: Button
    private lateinit var discoveryLayout: LinearLayout
    private lateinit var resultsContainer: LinearLayout
    private lateinit var hotTagsContainer: LinearLayout
    private lateinit var histTagsContainer: LinearLayout
    private var phoneServer: PhoneInputServer? = null
    private var phoneDialog: AlertDialog? = null
    private var phoneSessionGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        searchInput = findViewById(R.id.search_edit_text)
        phoneInputButton = findViewById(R.id.search_btn_phone_input)
        discoveryLayout = findViewById(R.id.search_discovery_layout)
        resultsContainer = findViewById(R.id.search_results_container)
        hotTagsContainer = findViewById(R.id.search_hot_tags_container)
        histTagsContainer = findViewById(R.id.search_hist_tags_container)
        searchHistory.addAll(HMVideoApplication.instance.storageManager.getSearchHistory())

        findViewById<Button>(R.id.search_btn_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.search_btn_submit).setOnClickListener {
            performSearch(searchInput.text.toString().trim())
        }
        phoneInputButton.setOnClickListener { openPhoneInputDialog() }

        findViewById<Button>(R.id.search_btn_clear_hist).setOnClickListener {
            HMVideoApplication.instance.storageManager.clearSearchHistory()
            searchHistory.clear()
            renderHistoryTags()
        }

        setupCategoryTabs()
        renderHotTags()
        renderHistoryTags()
    }

    private fun setupCategoryTabs() {
        val tabAll = findViewById<Button>(R.id.search_tab_all)
        val tabMovie = findViewById<Button>(R.id.search_tab_movie)
        val tabTv = findViewById<Button>(R.id.search_tab_tv)
        val tabAnime = findViewById<Button>(R.id.search_tab_anime)

        val tabs = listOf(tabAll to null, tabMovie to 1, tabTv to 2, tabAnime to 3)

        for ((btn, cid) in tabs) {
            btn.setOnClickListener {
                currentCid = cid
                tabs.forEach { (b, c) ->
                    val isCur = c == currentCid
                    b.setBackgroundResource(if (isCur) R.drawable.bg_pill_active else R.drawable.bg_pill)
                    b.setTextColor(if (isCur) Color.WHITE else Color.parseColor("#9ca6b5"))
                }
                val kw = searchInput.text.toString().trim()
                if (kw.isNotEmpty()) {
                    performSearch(kw)
                }
            }
        }
    }

    private fun renderHotTags() {
        hotTagsContainer.removeAllViews()
        val density = resources.displayMetrics.density

        var row: LinearLayout? = null
        for ((idx, kw) in hotKeywords.withIndex()) {
            if (idx % 3 == 0) {
                row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 0, (6 * density).toInt())
                    }
                }
                hotTagsContainer.addView(row)
            }

            val tagBtn = Button(this).apply {
                text = "${idx + 1}. $kw"
                textSize = 12f
                setBackgroundResource(R.drawable.bg_pill)
                setTextColor(if (idx < 3) Color.parseColor("#b6c9e2") else Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    (36 * density).toInt(),
                    1f
                ).apply {
                    setMargins((3 * density).toInt(), 0, (3 * density).toInt(), 0)
                }
                setOnClickListener {
                    searchInput.setText(kw)
                    performSearch(kw)
                }
            }
            row?.addView(tagBtn)
        }
    }

    private fun renderHistoryTags() {
        histTagsContainer.removeAllViews()
        val density = resources.displayMetrics.density

        if (searchHistory.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "暂无搜索历史"
                setTextColor(Color.parseColor("#9ca6b5"))
                textSize = 12f
            }
            histTagsContainer.addView(emptyTv)
            return
        }

        var row: LinearLayout? = null
        for ((idx, kw) in searchHistory.withIndex()) {
            if (idx % 3 == 0) {
                row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 0, (6 * density).toInt())
                    }
                }
                histTagsContainer.addView(row)
            }

            val tagBtn = Button(this).apply {
                text = kw
                textSize = 12f
                setBackgroundResource(R.drawable.bg_pill)
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    (36 * density).toInt(),
                    1f
                ).apply {
                    setMargins((3 * density).toInt(), 0, (3 * density).toInt(), 0)
                }
                setOnClickListener {
                    searchInput.setText(kw)
                    performSearch(kw)
                }
            }
            row?.addView(tagBtn)
        }
    }

    private fun performSearch(keyword: String, page: Int = 1) {
        if (keyword.isEmpty()) return
        currentPage = page.coerceAtLeast(1)
        val requestId = ++searchRequestId
        val requestedCid = currentCid
        val requestedPage = currentPage
        val storage = HMVideoApplication.instance.storageManager
        storage.recordSearch(keyword)
        searchHistory.clear()
        searchHistory.addAll(storage.getSearchHistory())
        renderHistoryTags()

        discoveryLayout.visibility = View.GONE
        resultsContainer.visibility = View.VISIBLE
        resultsContainer.removeAllViews()

        val loadingView = LoadingViewHelper.createLoadingView(
            this,
            title = "正在全网检索「$keyword」…",
            subtitle = "智能匹配电影、电视剧、动漫及多线路播放源",
            badge = "HM影视 · 智能搜索引擎",
            tip = "支持按片名、主演、导演及首字母模糊搜索"
        )
        resultsContainer.addView(loadingView)

        Thread {
            try {
                val res = XingfanRepository.search(keyword, requestedCid, requestedPage)
                mainHandler.post {
                    if (requestId != searchRequestId || isFinishing || isDestroyed) return@post
                    currentPage = res.page
                    totalPages = res.totalPages.coerceAtLeast(currentPage)
                    resultsContainer.removeAllViews()
                    if (res.items.isEmpty()) {
                        val density = resources.displayMetrics.density
                        val emptyCard = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = android.view.Gravity.CENTER
                            setBackgroundResource(R.drawable.bg_loading_card)
                            val pad = (24 * density).toInt()
                            setPadding(pad, pad, pad, pad)
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT
                            ).apply {
                                setMargins(0, (20 * density).toInt(), 0, 0)
                            }
                            val tvTitle = TextView(this@SearchActivity).apply {
                                text = "未找到与「$keyword」相关的影视作品"
                                setTextColor(Color.WHITE)
                                textSize = 15f
                                paint.isFakeBoldText = true
                                gravity = android.view.Gravity.CENTER
                            }
                            addView(tvTitle)
                            val tvSub = TextView(this@SearchActivity).apply {
                                text = "建议尝试更换关键词，或从上方热门推荐中挑选"
                                setTextColor(Color.parseColor("#9ca6b5"))
                                textSize = 12f
                                gravity = android.view.Gravity.CENTER
                                setPadding(0, (8 * density).toInt(), 0, 0)
                            }
                            addView(tvSub)
                        }
                        resultsContainer.addView(emptyCard)
                    } else {
                        renderResults(res.items)
                    }
                    renderPager(keyword)
                }
            } catch (e: Exception) {
                mainHandler.post {
                    if (requestId != searchRequestId || isFinishing || isDestroyed) return@post
                    resultsContainer.removeAllViews()
                    val errTv = TextView(this).apply {
                        text = "搜索遇到错误: ${e.message}"
                        setTextColor(Color.RED)
                        textSize = 14f
                        setPadding(0, 40, 0, 40)
                    }
                    resultsContainer.addView(errTv)
                }
            }
        }.start()
    }

    private fun renderResults(items: List<WorkItem>) {
        val density = resources.displayMetrics.density
        for (item in items) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                isFocusable = true
                isClickable = true
                gravity = android.view.Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_card)
                val pad = (10 * density).toInt()
                setPadding(pad, pad, pad, pad)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, (10 * density).toInt())
                }

                // Poster Image
                val posterIv = ImageView(this@SearchActivity).apply {
                    layoutParams = LinearLayout.LayoutParams((65 * density).toInt(), (90 * density).toInt())
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundResource(R.drawable.bg_card)
                }
                com.hm.video.util.ImageLoader.load(posterIv, item.poster)
                addView(posterIv)

                // Info Column
                val infoCol = LinearLayout(this@SearchActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins((12 * density).toInt(), 0, 0, 0)
                    }

                    val titleTv = TextView(this@SearchActivity).apply {
                        text = item.title
                        setTextColor(Color.WHITE)
                        textSize = 15f
                        paint.isFakeBoldText = true
                    }
                    addView(titleTv)

                    val metaText = listOfNotNull(item.category, item.year, item.progress).joinToString(" · ")
                    if (metaText.isNotEmpty()) {
                        val metaTv = TextView(this@SearchActivity).apply {
                            text = metaText
                            setTextColor(Color.parseColor("#b6c9e2"))
                            textSize = 12f
                            setPadding(0, (4 * density).toInt(), 0, 0)
                        }
                        addView(metaTv)
                    }
                }
                addView(infoCol)

                setOnClickListener {
                    val intent = Intent(this@SearchActivity, DetailActivity::class.java).apply {
                        putExtra("url", item.url)
                        putExtra("title", item.title)
                        putExtra("poster", item.poster)
                        if (!item.category.isNullOrEmpty()) putExtra("category", item.category)
                        if (!item.year.isNullOrEmpty()) putExtra("year", item.year)
                    }
                    startActivity(intent)
                }
            }
            resultsContainer.addView(card)
        }
    }

    private fun renderPager(keyword: String) {
        if (totalPages <= 1) return
        val density = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(0, (12 * density).toInt(), 0, (24 * density).toInt())
        }
        fun pageButton(label: String, enabled: Boolean, target: Int) = Button(this).apply {
            text = label
            isEnabled = enabled
            isFocusable = true
            setBackgroundResource(R.drawable.bg_pill)
            setTextColor(Color.WHITE)
            setOnClickListener { performSearch(keyword, target) }
        }
        row.addView(pageButton("上一页", currentPage > 1, currentPage - 1))
        val pageLabel = TextView(this).apply {
            text = "  $currentPage / $totalPages  "
            setTextColor(Color.WHITE)
            gravity = android.view.Gravity.CENTER
        }
        row.addView(pageLabel)
        row.addView(pageButton("下一页", currentPage < totalPages, currentPage + 1))
        resultsContainer.addView(row)
    }

    private fun openPhoneInputDialog() {
        if (phoneDialog?.isShowing == true) return
        val addresses = LanAddresses.candidates()
        if (addresses.isEmpty()) {
            Toast.makeText(this, "未找到可用的局域网地址，请检查电视网络", Toast.LENGTH_LONG).show()
            return
        }

        val generation = ++phoneSessionGeneration
        val content = layoutInflater.inflate(R.layout.dialog_phone_input, null)
        val qrView = content.findViewById<ImageView>(R.id.phone_input_qr)
        val urlView = content.findViewById<TextView>(R.id.phone_input_url)
        val alternativesView = content.findViewById<TextView>(R.id.phone_input_alternatives)
        val statusView = content.findViewById<TextView>(R.id.phone_input_status)
        val closeButton = content.findViewById<Button>(R.id.phone_input_close)
        val dialog = AlertDialog.Builder(this).setView(content).create()
        dialog.setCanceledOnTouchOutside(false)
        var submitted = false
        dialog.setOnDismissListener {
            phoneSessionGeneration++
            phoneServer?.stop()
            phoneServer = null
            phoneDialog = null
            if (!isFinishing && !isDestroyed) {
                val targetId = if (submitted) R.id.search_btn_submit else R.id.search_btn_phone_input
                window.decorView.post { findViewById<Button>(targetId).requestFocus() }
            }
        }

        val server = PhoneInputServer(
            hostOverride = addresses.first().host,
            allowedHosts = addresses.map { it.host }.toSet(),
            mainHandler = mainHandler,
            onKeyword = { keyword ->
                if (generation == phoneSessionGeneration && !isFinishing && !isDestroyed) {
                    submitted = true
                    dialog.dismiss()
                    searchInput.setText(keyword)
                    performSearch(keyword)
                }
            },
            onExpired = {
                if (generation == phoneSessionGeneration && dialog.isShowing) {
                    statusView.text = "会话已过期，请关闭后重新打开"
                }
            }
        )

        val session = try {
            server.start()
        } catch (error: Exception) {
            server.stop()
            Toast.makeText(this, "无法启动手机输入服务，请检查网络或稍后重试", Toast.LENGTH_LONG).show()
            return
        }

        phoneServer = server
        phoneDialog = dialog
        urlView.text = session.url
        val qrBitmap = QrCode.bitmap(session.url, 480)
        if (qrBitmap != null) {
            qrView.setImageBitmap(qrBitmap)
        } else {
            qrView.visibility = View.GONE
            statusView.text = "二维码生成失败，请手动输入地址"
        }
        if (addresses.size > 1) {
            alternativesView.visibility = View.VISIBLE
            alternativesView.text = "如无法访问，可尝试：\n" + addresses.drop(1).joinToString("\n") {
                "http://${it.host}:${session.port}/${session.code}"
            }
        }
        closeButton.setOnClickListener { dialog.dismiss() }
        dialog.setOnShowListener { closeButton.post { closeButton.requestFocus() } }
        dialog.show()
    }

    private fun dismissPhoneInput() {
        phoneSessionGeneration++
        phoneServer?.stop()
        phoneServer = null
        val dialog = phoneDialog
        phoneDialog = null
        if (dialog?.isShowing == true) dialog.dismiss()
    }

    override fun onStop() {
        dismissPhoneInput()
        super.onStop()
    }

    override fun onDestroy() {
        dismissPhoneInput()
        searchRequestId++
        super.onDestroy()
    }
}
