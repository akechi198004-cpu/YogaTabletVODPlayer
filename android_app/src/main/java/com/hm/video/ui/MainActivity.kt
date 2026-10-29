package com.hm.video.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.hm.video.HMVideoApplication
import com.hm.video.R
import com.hm.video.data.api.XingfanConfig
import com.hm.video.data.api.XingfanRepository
import com.hm.video.data.model.*
import com.hm.video.util.ImageLoader
import com.hm.video.util.LoadingViewHelper

class MainActivity : FocusAwareActivity() {

    enum class NavTab {
        HOME, CATEGORY, TOP, DAILY, MINE
    }

    private var currentTab = NavTab.HOME
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tvMode = false
    private var lastCategoryFocusKey = "main:movie"
    private val categoryFocusIds = mutableMapOf<String, Int>()
    private var renderGeneration = 0
    private var heroCarousel: HeroCarouselView? = null
    private var heroIndex = 0

    private lateinit var contentFrame: FrameLayout
    private lateinit var btnHome: TextView
    private lateinit var btnCategory: TextView
    private lateinit var btnTop: TextView
    private lateinit var btnDaily: TextView
    private lateinit var btnMine: TextView

    // Category multi-dimensional filter state
    private var currentMainType = "movie" // "movie", "tv", "anime", "variety"
    private var currentCatSlug = "dianying"
    private var currentCatPage = 1
    private var currentFilterArea = "all"
    private var currentFilterYear = "all"
    private var currentFilterSort = "addtime"

    // Top tab state
    private var currentTopPeriod = "day"
    private var currentDailyPage = 1

    // Touch layouts default to three columns; TV mode fixes the grid at six columns.
    private var listColumns = 3

    // Mine tab state
    private var currentMineSubTab = "fav" // "fav" or "history"
    private val mineFocusIndex = mutableMapOf("fav" to 0, "history" to 0)
    private val mineFocusUrl = mutableMapOf<String, String>()
    private val mineScrollY = mutableMapOf("fav" to 0, "history" to 0)
    private var mineRestorePending = false
    private var categoryReturnUrl: String? = null
    private var categoryReturnScroll = 0

    private data class PosterGrid(val cards: List<View>) {
        val first: View? get() = cards.firstOrNull()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvMode = usesLeanbackLayout()
        if (tvMode) listColumns = 6

        contentFrame = findViewById(R.id.content_frame)
        btnHome = findViewById(R.id.nav_btn_home)
        btnCategory = findViewById(R.id.nav_btn_category)
        btnTop = findViewById(R.id.nav_btn_top)
        btnDaily = findViewById(R.id.nav_btn_daily)
        btnMine = findViewById(R.id.nav_btn_mine)

        val navBar = findViewById<LinearLayout>(R.id.bottom_nav)
        val rootLayout = findViewById<View>(R.id.content_frame).parent as? LinearLayout
        val navButtons = listOf(btnHome, btnCategory, btnTop, btnDaily, btnMine)
        navButtons.forEach {
            it.isFocusable = true
            it.isClickable = true
            it.setBackgroundResource(R.drawable.bg_nav_item)
        }
        if (tvMode && rootLayout != null) {
            val density = resources.displayMetrics.density
            fun dp(value: Int) = (value * density).toInt()
            val topBar = findViewById<RelativeLayout>(R.id.top_bar)
            val title = findViewById<TextView>(R.id.app_title)
            val search = findViewById<Button>(R.id.btn_search)
            rootLayout.setBackgroundResource(R.drawable.bg_tv_backdrop)
            topBar.setBackgroundColor(Color.TRANSPARENT)
            topBar.layoutParams.height = dp(72)
            topBar.setPadding(dp(32), 0, dp(32), 0)
            title.text = "HM 影视"
            title.textSize = 24f
            title.layoutParams.width = dp(132)
            rootLayout.removeView(navBar)
            navBar.setBackgroundColor(Color.TRANSPARENT)
            // Fill the gap between the title and the search button instead of a fixed 490dp:
            // the same code then fits both landscape (960dp) and portrait (600dp) without
            // re-laying out on rotation, which configChanges would otherwise skip.
            topBar.addView(navBar, RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT, dp(48)
            ).apply {
                addRule(RelativeLayout.END_OF, R.id.app_title)
                addRule(RelativeLayout.START_OF, R.id.btn_search)
                addRule(RelativeLayout.CENTER_VERTICAL)
                marginStart = dp(16)
                marginEnd = dp(16)
            })
            navButtons.forEach { button ->
                button.textSize = 20f
                button.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    setMargins(dp(3), 0, dp(3), 0)
                }
            }
            search.text = "搜索影视"
            search.textSize = 18f
            search.layoutParams = (search.layoutParams as RelativeLayout.LayoutParams).apply {
                width = dp(144)
                height = dp(44)
            }
            btnHome.nextFocusLeftId = btnHome.id
            btnMine.nextFocusRightId = search.id
            search.nextFocusLeftId = btnMine.id
        }

        findViewById<Button>(R.id.btn_search).setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }

        btnHome.setOnClickListener { switchTab(NavTab.HOME) }
        btnCategory.setOnClickListener { switchTab(NavTab.CATEGORY) }
        btnTop.setOnClickListener { switchTab(NavTab.TOP) }
        btnDaily.setOnClickListener { switchTab(NavTab.DAILY) }
        btnMine.setOnClickListener { switchTab(NavTab.MINE) }

        switchTab(NavTab.HOME)
        if (tvMode) btnHome.post { btnHome.requestFocus() }
    }

    override fun onResume() {
        super.onResume()
        heroCarousel?.setActive(true)
        if (currentTab == NavTab.MINE) {
            val restore = mineRestorePending
            mineRestorePending = false
            renderMineTab(restoreFocus = restore)
        }
        if (currentTab == NavTab.CATEGORY) restoreCategoryFocus()
    }

    override fun onPause() {
        heroCarousel?.setActive(false)
        super.onPause()
    }

    private fun usesLeanbackLayout(): Boolean = DisplayProfile.isLeanbackLayout(this)

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                val currentFocus = currentFocus
                if (currentFocus != null && currentFocus.performClick()) {
                    return true
                }
            }
            KeyEvent.KEYCODE_MENU -> {
                startActivity(Intent(this, SearchActivity::class.java))
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (currentTab != NavTab.HOME) {
                    switchTab(NavTab.HOME)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun switchTab(tab: NavTab) {
        heroCarousel?.setActive(false)
        heroCarousel = null
        currentTab = tab
        if (tab != NavTab.CATEGORY) categoryReturnUrl = null
        val tabs = listOf(
            btnHome to NavTab.HOME,
            btnCategory to NavTab.CATEGORY,
            btnTop to NavTab.TOP,
            btnDaily to NavTab.DAILY,
            btnMine to NavTab.MINE
        )

        for ((btn, t) in tabs) {
            val isCur = t == currentTab
            btn.setTextColor(if (isCur) Color.parseColor("#b6c9e2") else Color.parseColor("#9ca6b5"))
            btn.isSelected = isCur
            btn.paint.isFakeBoldText = isCur
            btn.contentDescription = if (isCur) "${btn.text}，当前页面" else btn.text
        }

        when (tab) {
            NavTab.HOME -> renderHomeTab()
            NavTab.CATEGORY -> renderCategoryTab()
            NavTab.TOP -> renderTopTab()
            NavTab.DAILY -> renderDailyTab()
            NavTab.MINE -> renderMineTab()
        }
    }

    // -------------------------------------------------------------
    // Tab 1: Home
    // -------------------------------------------------------------
    private fun renderHomeTab() {
        val requestId = ++renderGeneration
        val root = createLoadingLayout("正在获取今日精选影视…")
        contentFrame.removeAllViews()
        contentFrame.addView(root)

        Thread {
            val result = runCatching { XingfanRepository.getHome() }
            result.exceptionOrNull()?.let { android.util.Log.e("HMNetwork", "home load failed", it) }

            mainHandler.post {
                if (requestId != renderGeneration || currentTab != NavTab.HOME || isFinishing || isDestroyed) return@post
                val data = result.getOrNull()
                if (data == null) {
                    showErrorMessage("首页内容暂时无法读取，请检查网络后重试") { renderHomeTab() }
                    return@post
                }
                val view = inflatePage()
                val container = view.findViewById<LinearLayout>(R.id.page_items_container)

                // TV uses the featured carousel; touch layouts retain the compact hero card.
                val slides = data.hero.distinctBy { it.url }.take(8)
                if (tvMode && slides.isNotEmpty()) {
                    val density = resources.displayMetrics.density
                    heroCarousel = HeroCarouselView(this, slides, heroIndex,
                        onIndexChanged = { heroIndex = it },
                        onOpen = { openDetail(it.url, it.title, it.poster, it.category, it.year) }
                    ).apply {
                        nextFocusUpId = btnHome.id
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                            (200 * density).toInt()).apply { bottomMargin = (8 * density).toInt() }
                    }
                    container.addView(heroCarousel)
                    btnHome.nextFocusDownId = heroCarousel!!.id
                } else if (slides.isNotEmpty()) {
                    container.addView(createHeroCard(slides.first()))
                }

                val density = resources.displayMetrics.density

                // Section heading; the layout toggle below is available only on touch layouts.
                val headingRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, ((if (tvMode) 8 else 16) * density).toInt(), 0, ((if (tvMode) 8 else 12) * density).toInt())
                }

                val heading = TextView(this).apply {
                    text = "最近更新 · 热门影视"
                    setTextColor(Color.WHITE)
                    textSize = uiTextSize(16f)
                    paint.isFakeBoldText = true
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                headingRow.addView(heading)

                if (!tvMode) {
                    val iconToggleHome = TextView(this).apply {
                        setBackgroundResource(R.drawable.bg_pill)
                        gravity = Gravity.CENTER
                        minHeight = (36 * density).toInt()
                        text = if (listColumns == 3) "⊞" else "☰"
                        textSize = uiTextSize(18f)
                        setTextColor(Color.parseColor("#b6c9e2"))
                        val p = (8 * density).toInt()
                        setPadding(p, 0, p, 0)
                        setOnClickListener {
                            listColumns = if (tvMode) {
                                if (listColumns == 6) 5 else 6
                            } else {
                                if (listColumns == 3) 2 else 3
                            }
                            renderHomeTab()
                        }
                        isFocusable = true
                        isClickable = true
                        contentDescription = "切换海报列数"
                    }
                    headingRow.addView(iconToggleHome)
                }
                container.addView(headingRow)

                // Recent Works Poster Grid
                val homeGrid = renderPosterGrid(container, data.recent, listColumns)
                TvFocusNavigator.wireGrid(
                    homeGrid?.cards.orEmpty(),
                    listColumns,
                    upTarget = heroCarousel ?: btnHome
                )

                contentFrame.removeAllViews()
                contentFrame.addView(view)
            }
        }.start()
    }

    private fun createHeroCard(slide: HeroSlide): View {
        val density = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            isFocusable = true
            isClickable = true
            setBackgroundResource(R.drawable.bg_card)
            val pad = (12 * density).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, (14 * density).toInt())
            }

            // Big Poster
            val posterIv = ImageView(this@MainActivity).apply {
                val posterWidthDp = if (tvMode) 132 else 110
                layoutParams = LinearLayout.LayoutParams(
                    (posterWidthDp * density).toInt(),
                    (posterWidthDp * 1.5f * density).toInt()
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundResource(R.drawable.bg_card)
            }
            ImageLoader.load(posterIv, slide.poster)
            addView(posterIv)

            // Info Column
            val infoLayout = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins((12 * density).toInt(), 0, 0, 0)
                }

                val badge = TextView(this@MainActivity).apply {
                    text = "今日精选"
                    setTextColor(Color.WHITE)
                    textSize = uiTextSize(10f)
                    paint.isFakeBoldText = true
                    setBackgroundResource(R.drawable.bg_pill_active)
                    val p = (8 * density).toInt()
                    setPadding(p, 2, p, 2)
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                addView(badge)

                val titleTv = TextView(this@MainActivity).apply {
                    text = slide.title
                    setTextColor(Color.WHITE)
                    textSize = if (tvMode) 30f else 18f
                    paint.isFakeBoldText = true
                    setPadding(0, (6 * density).toInt(), 0, 0)
                }
                addView(titleTv)

                val metaTv = TextView(this@MainActivity).apply {
                    text = listOfNotNull(slide.category, slide.year).joinToString(" · ")
                    setTextColor(Color.parseColor("#b6c9e2"))
                    textSize = uiTextSize(12f)
                    setPadding(0, 4, 0, 8)
                }
                addView(metaTv)

                val playBtn = Button(this@MainActivity).apply {
                    text = "▶ 立即播放"
                    setTextColor(Color.WHITE)
                    textSize = uiTextSize(12f)
                    paint.isFakeBoldText = true
                    setBackgroundResource(R.drawable.bg_gradient_button)
                    val p = (14 * density).toInt()
                    setPadding(p, 0, p, 0)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        (36 * density).toInt()
                    )
                    setOnClickListener { openDetail(slide.url, slide.title, slide.poster) }
                }
                addView(playBtn)
            }
            addView(infoLayout)
            setOnClickListener { openDetail(slide.url, slide.title, slide.poster) }
        }
    }

    // -------------------------------------------------------------
    // Tab 2: Category (包含电影、电视剧、动漫、综艺全分类)
    // -------------------------------------------------------------
    private fun renderCategoryTab(focusFirst: Boolean = false) {
        val requestId = ++renderGeneration
        val focusBeforeId = currentFocus?.id ?: View.NO_ID
        val focusToken = beginFocusLoad()
        val root = createLoadingLayout("正在获取分类库作品…")
        contentFrame.removeAllViews()
        contentFrame.addView(root)

        val effectiveCat = when {
            currentCatSlug.isNotEmpty() && currentCatSlug != "all" && currentCatSlug != "all_movie" && currentCatSlug != "all_tv" -> currentCatSlug
            currentMainType == "tv" -> "dianshiju"
            currentMainType == "anime" -> "dongman"
            currentMainType == "variety" -> "zongyi"
            else -> "dianying"
        }
        val requestedPage = currentCatPage
        val requestedArea = currentFilterArea.takeIf { it != "all" }
        val requestedYear = currentFilterYear.takeIf { it != "all" }
        val requestedSort = currentFilterSort
        Thread {
            try {
                val data = XingfanRepository.getCategoryList(effectiveCat, requestedPage, area = requestedArea, year = requestedYear, sort = requestedSort)
                mainHandler.post {
                    if (requestId != renderGeneration || currentTab != NavTab.CATEGORY || isFinishing || isDestroyed) return@post
                    val view = inflatePage()
                    val filterScroll = view.findViewById<HorizontalScrollView>(R.id.page_filter_scroll)
                    val filterContainer = view.findViewById<LinearLayout>(R.id.page_filter_container)
                    val itemsContainer = view.findViewById<LinearLayout>(R.id.page_items_container)

                    filterScroll.visibility = View.VISIBLE
                    filterContainer.removeAllViews()
                    val density = resources.displayMetrics.density
                    // Each TV filter row scrolls independently; focusing a distant chip
                    // must not move all the other rows off screen.
                    filterContainer.layoutParams.width = resources.displayMetrics.widthPixels - view.paddingLeft - view.paddingRight - filterScroll.paddingLeft - filterScroll.paddingRight
                    filterScroll.isFocusable = false
                    fun addFilterRow(row: LinearLayout) {
                        for (index in 0 until row.childCount) {
                            row.getChildAt(index).apply {
                                nextFocusLeftId = row.getChildAt((index - 1).coerceAtLeast(0)).id
                                nextFocusRightId = row.getChildAt((index + 1).coerceAtMost(row.childCount - 1)).id
                            }
                        }
                        filterContainer.addView(HorizontalScrollView(this).apply {
                            isHorizontalScrollBarEnabled = false
                            isFocusable = false
                            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                            addView(row)
                        })
                    }

                    // Row 1: Primary Category Tabs (电影 / 电视剧 / 动漫 / 综艺)
                    val mainTabsLayout = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, 0, 0, (4 * density).toInt())
                    }

                    val mainCategories = listOf(
                        "movie" to "🎬 电影专区",
                        "tv" to "📺 电视剧场",
                        "anime" to "⚡ 动漫新番",
                        "variety" to "🎉 综艺娱乐"
                    )

                    for ((mKey, mLabel) in mainCategories) {
                        val isCur = mKey == currentMainType
                        val btn = Button(this).apply {
                            text = mLabel
                            textSize = uiTextSize(12f)
                            paint.isFakeBoldText = isCur
                            setBackgroundResource(if (isCur) R.drawable.bg_pill_active else R.drawable.bg_pill)
                            setTextColor(if (isCur) Color.WHITE else Color.parseColor("#9ca6b5"))
                            val p = (14 * density).toInt()
                            setPadding(p, 0, p, 0)
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                ((if (tvMode) 40 else 34) * density).toInt()
                            ).apply {
                                setMargins(0, 0, (8 * density).toInt(), 0)
                            }
                            setOnClickListener {
                                currentMainType = mKey
                                lastCategoryFocusKey = "main:$mKey"
                                currentCatSlug = when (mKey) {
                                    "tv" -> "dianshiju"
                                    "anime" -> "dongman"
                                    "variety" -> "zongyi"
                                    else -> "dianying"
                                }
                                currentCatPage = 1
                                renderCategoryTab()
                            }
                        }
                        trackCategoryFocus(btn, "main:$mKey")
                        mainTabsLayout.addView(btn)
                    }
                    addFilterRow(mainTabsLayout)

                    // Row 2: Sub-category pills based on currentMainType
                    val subCategories = when (currentMainType) {
                        "movie" -> XingfanConfig.MOVIE_CATEGORIES
                        "tv" -> XingfanConfig.TV_CATEGORIES
                        "anime" -> listOf(
                            Category("dongman", "全部动漫"),
                            Category("ribendongman", "日本动漫"),
                            Category("guochandongman", "国创动画")
                        )
                        "variety" -> listOf(
                            Category("zongyi", "全部综艺"),
                            Category("dalu_variety", "大陆真人秀"),
                            Category("talk_variety", "脱口秀相声")
                        )
                        else -> listOf(
                            Category("all", "全部热门"),
                            Category("kehuanpian", "科幻电影"),
                            Category("dongzuopian", "动作大片"),
                            Category("guocanju", "华语热剧"),
                            Category("oumeiju", "欧美高分"),
                            Category("dongman", "新番动漫")
                        )
                    }

                    val subRowLayout = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, (2 * density).toInt(), 0, (4 * density).toInt())
                    }

                    for (cat in subCategories) {
                        val isCur = cat.slug == currentCatSlug
                        val btn = Button(this).apply {
                            text = cat.label
                            textSize = uiTextSize(11f)
                            setBackgroundResource(if (isCur) R.drawable.bg_pill_active else R.drawable.bg_pill)
                            setTextColor(if (isCur) Color.WHITE else Color.parseColor("#9ca6b5"))
                            val p = (12 * density).toInt()
                            setPadding(p, 0, p, 0)
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                (30 * density).toInt()
                            ).apply {
                                setMargins(0, 0, (6 * density).toInt(), 0)
                            }
                            setOnClickListener {
                                currentCatSlug = cat.slug
                                lastCategoryFocusKey = "sub:${cat.slug}"
                                currentCatPage = 1
                                renderCategoryTab()
                            }
                        }
                        trackCategoryFocus(btn, "sub:${cat.slug}")
                        subRowLayout.addView(btn)
                    }
                    addFilterRow(subRowLayout)

                    // Row 3: 制片地区 Pills (全部、中国大陆、中国香港、中国台湾、欧美、日本、韩国)
                    val areaRowLayout = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, (2 * density).toInt(), 0, (4 * density).toInt())
                    }
                    val areas = listOf("all" to "全部地区") + (data.filters?.areas.orEmpty().ifEmpty {
                        listOf(ChipOption("大陆", "Dalu"), ChipOption("香港", "HongKong"), ChipOption("台湾", "Taiwan"), ChipOption("欧美", "Oumei"), ChipOption("日本", "Japan"), ChipOption("韩国", "Hanguo"))
                    }.map { it.value to it.label })
                    for ((aKey, aLabel) in areas) {
                        val isCur = aKey == currentFilterArea
                        val btn = Button(this).apply {
                            text = aLabel
                            textSize = uiTextSize(10.5f)
                            setBackgroundResource(if (isCur) R.drawable.bg_pill_active else R.drawable.bg_pill)
                            setTextColor(if (isCur) Color.WHITE else Color.parseColor("#9ca6b5"))
                            val p = (10 * density).toInt()
                            setPadding(p, 0, p, 0)
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                ((if (tvMode) 36 else 28) * density).toInt()
                            ).apply {
                                setMargins(0, 0, (5 * density).toInt(), 0)
                            }
                            setOnClickListener {
                                currentFilterArea = aKey
                                lastCategoryFocusKey = "area:$aKey"
                                currentCatPage = 1
                                renderCategoryTab()
                            }
                        }
                        trackCategoryFocus(btn, "area:$aKey")
                        areaRowLayout.addView(btn)
                    }
                    addFilterRow(areaRowLayout)

                    // Row 4: 上映年份 Pills (全部、2025、2024、2023、2022、更早)
                    val yearRowLayout = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, (2 * density).toInt(), 0, (4 * density).toInt())
                    }
                    val years = listOf("all" to "全部年份") + data.filters?.years.orEmpty().map { it.value to it.label }
                    for ((yKey, yLabel) in years) {
                        val isCur = yKey == currentFilterYear
                        val btn = Button(this).apply {
                            text = yLabel
                            textSize = uiTextSize(10.5f)
                            setBackgroundResource(if (isCur) R.drawable.bg_pill_active else R.drawable.bg_pill)
                            setTextColor(if (isCur) Color.WHITE else Color.parseColor("#9ca6b5"))
                            val p = (10 * density).toInt()
                            setPadding(p, 0, p, 0)
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                ((if (tvMode) 36 else 28) * density).toInt()
                            ).apply {
                                setMargins(0, 0, (5 * density).toInt(), 0)
                            }
                            setOnClickListener {
                                currentFilterYear = yKey
                                lastCategoryFocusKey = "year:$yKey"
                                currentCatPage = 1
                                renderCategoryTab()
                            }
                        }
                        trackCategoryFocus(btn, "year:$yKey")
                        yearRowLayout.addView(btn)
                    }
                    addFilterRow(yearRowLayout)

                    // Row 5: 排序规则 Pills (🔥 热度、★ 评分、🕒 最新) + 重置按钮
                    val sortRowLayout = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, (2 * density).toInt(), 0, (6 * density).toInt())
                    }
                    val sorts = data.filters?.sorts.orEmpty().ifEmpty {
                        listOf(ChipOption("最新", "addtime"), ChipOption("最热", "hits"), ChipOption("评分", "gold"))
                    }.map { it.value to it.label }
                    for ((sKey, sLabel) in sorts) {
                        val isCur = sKey == currentFilterSort
                        val btn = Button(this).apply {
                            text = sLabel
                            textSize = uiTextSize(10.5f)
                            setBackgroundResource(if (isCur) R.drawable.bg_pill_active else R.drawable.bg_pill)
                            setTextColor(if (isCur) Color.WHITE else Color.parseColor("#9ca6b5"))
                            val p = (10 * density).toInt()
                            setPadding(p, 0, p, 0)
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                ((if (tvMode) 36 else 28) * density).toInt()
                            ).apply {
                                setMargins(0, 0, (5 * density).toInt(), 0)
                            }
                            setOnClickListener {
                                currentFilterSort = sKey
                                lastCategoryFocusKey = "sort:$sKey"
                                currentCatPage = 1
                                renderCategoryTab()
                            }
                        }
                        trackCategoryFocus(btn, "sort:$sKey")
                        sortRowLayout.addView(btn)
                    }

                    if (currentCatSlug != "all" || currentFilterArea != "all" || currentFilterYear != "all") {
                        val resetBtn = Button(this).apply {
                            text = "↺ 重置筛选"
                            textSize = uiTextSize(10.5f)
                            setBackgroundResource(R.drawable.bg_pill)
                            setTextColor(Color.parseColor("#ff5252"))
                            val p = (10 * density).toInt()
                            setPadding(p, 0, p, 0)
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                ((if (tvMode) 36 else 28) * density).toInt()
                            ).apply {
                                setMargins((8 * density).toInt(), 0, 0, 0)
                            }
                            setOnClickListener {
                                currentCatSlug = when (currentMainType) {
                                    "tv" -> "dianshiju"
                                    "anime" -> "dongman"
                                    "variety" -> "zongyi"
                                    else -> "dianying"
                                }
                                currentFilterArea = "all"
                                currentFilterYear = "all"
                                currentFilterSort = "addtime"
                                lastCategoryFocusKey = "sort:reset"
                                currentCatPage = 1
                                renderCategoryTab()
                            }
                        }
                        trackCategoryFocus(resetBtn, "sort:reset")
                        sortRowLayout.addView(resetBtn)
                    }
                    addFilterRow(sortRowLayout)

                    // Category result summary; only touch layouts expose a density toggle.
                    val headerRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, (8 * density).toInt(), 0, (10 * density).toInt())
                    }

                    val header = TextView(this).apply {
                        val label = XingfanConfig.getCategoryLabel(currentCatSlug)
                        val total = if (data.totalItems != null) " (共 ${data.totalItems} 部)" else " (共 ${data.items.size} 部)"
                        text = "筛选结果：$label · 第 ${data.page}/${data.totalPages} 页$total"
                        setTextColor(Color.WHITE)
                        textSize = uiTextSize(13f)
                        paint.isFakeBoldText = true
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    headerRow.addView(header)

                        if (!tvMode) {
                    val iconToggleCat = TextView(this).apply {
                            setBackgroundResource(R.drawable.bg_pill)
                            gravity = Gravity.CENTER
                            minHeight = (36 * density).toInt()
                            contentDescription = "每行${listColumns}张海报，按确认切换"
                            text = if (listColumns == 3) "⊞" else "☰"
                            textSize = uiTextSize(18f)
                            setTextColor(Color.parseColor("#b6c9e2"))
                            val p = (8 * density).toInt()
                            setPadding(p, 0, p, 0)
                            setOnClickListener {
                                listColumns = if (tvMode) {
                                    if (listColumns == 6) 5 else 6
                                } else {
                                    if (listColumns == 3) 2 else 3
                                }
                                lastCategoryFocusKey = "layout"
                                renderCategoryTab()
                            }
                        }
                        trackCategoryFocus(iconToggleCat, "layout")
                        sortRowLayout.getChildAt(sortRowLayout.childCount - 1)?.let { lastFilter ->
                            lastFilter.nextFocusRightId = iconToggleCat.id
                            iconToggleCat.nextFocusLeftId = lastFilter.id
                            iconToggleCat.nextFocusUpId = lastFilter.id
                        }
                        headerRow.addView(iconToggleCat)
                    }
                    itemsContainer.addView(headerRow)

                    // Works Poster Grid
                    val grid = if (data.items.isEmpty()) {
                        itemsContainer.addView(TextView(this).apply {
                            text = "没有符合当前条件的影片，请更换条件或重置筛选"
                            setTextColor(Color.parseColor("#b8c2d1"))
                            textSize = uiTextSize(14f)
                            gravity = Gravity.CENTER
                            setPadding(12, 40, 12, 40)
                        })
                        null
                    } else renderPosterGrid(itemsContainer, data.items, listColumns)

                    // Pagination buttons row
                    val pagerRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER
                        setPadding(0, 16, 0, 24)

                        if (currentCatPage > 1) {
                            val prevBtn = Button(this@MainActivity).apply {
                                text = "〈 上一页"
                                textSize = uiTextSize(12f)
                                setBackgroundResource(R.drawable.bg_pill)
                                setTextColor(Color.WHITE)
                                val p = (14 * density).toInt()
                                setPadding(p, 0, p, 0)
                                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (36 * density).toInt())
                                setOnClickListener {
                                    currentCatPage--
                                    renderCategoryTab(focusFirst = true)
                                }
                            }
                            addView(prevBtn)
                        }

                        val pageIndicator = TextView(this@MainActivity).apply {
                            text = " 第 ${data.page} / ${data.totalPages} 页 "
                            setTextColor(Color.parseColor("#b6c9e2"))
                            textSize = uiTextSize(12f)
                            setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
                        }
                        addView(pageIndicator)

                        if (currentCatPage < data.totalPages) {
                            val nextBtn = Button(this@MainActivity).apply {
                                text = "下一页 〉"
                                textSize = uiTextSize(12f)
                                setBackgroundResource(R.drawable.bg_pill_active)
                                setTextColor(Color.WHITE)
                                val p = (14 * density).toInt()
                                setPadding(p, 0, p, 0)
                                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (36 * density).toInt())
                                setOnClickListener {
                                    currentCatPage++
                                    renderCategoryTab(focusFirst = true)
                                }
                            }
                            addView(nextBtn)
                        }
                    }
                    itemsContainer.addView(pagerRow)
                    val pagerTarget = firstFocusableChild(pagerRow)
                    TvFocusNavigator.wireGrid(
                        grid?.cards.orEmpty(),
                        listColumns,
                        upTarget = sortRowLayout.getChildAt(0) ?: mainTabsLayout.getChildAt(0),
                        downTarget = pagerTarget
                    )

                    contentFrame.removeAllViews()
                    contentFrame.addView(view)
                    view.post {
                        if (isFocusLoadCurrent(focusToken) && shouldApplyPendingFocus(btnCategory.id, focusBeforeId)) {
                            val first = grid?.first
                            if (focusFirst && first != null) {
                                first.requestFocus()
                                (itemsContainer.parent as? ScrollView)?.scrollTo(0, 0)
                            } else {
                                val focusId = categoryFocusIds[lastCategoryFocusKey]
                                val focusTarget = focusId?.let { view.findViewById<View>(it) }
                                    ?: mainTabsLayout.getChildAt(0)
                                focusTarget?.requestFocus()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("HMNetwork", "Category load failed", e)
                mainHandler.post {
                    if (requestId == renderGeneration && currentTab == NavTab.CATEGORY && !isFinishing && !isDestroyed) {
                        showErrorMessage("分类内容暂时无法读取，请检查网络后重试") { renderCategoryTab() }
                    }
                }
            }
        }.start()
    }

    // -------------------------------------------------------------
    // Tab 3: Top Leaderboards
    // -------------------------------------------------------------
    private fun renderTopTab() {
        val requestId = ++renderGeneration
        val focusBeforeId = currentFocus?.id ?: View.NO_ID
        val focusToken = beginFocusLoad()
        val root = createLoadingLayout("正在获取全网影视榜单…")
        contentFrame.removeAllViews()
        contentFrame.addView(root)

        Thread {
            val result = runCatching { XingfanRepository.getTopBoards() }

            mainHandler.post {
                    if (requestId != renderGeneration || currentTab != NavTab.TOP || isFinishing || isDestroyed) return@post
                    val boards = result.getOrNull()
                    if (boards == null) {
                        showErrorMessage("榜单暂时无法读取，请检查网络后重试") { renderTopTab() }
                        return@post
                    }
                    val view = inflatePage()
                    val filterScroll = view.findViewById<HorizontalScrollView>(R.id.page_filter_scroll)
                    val filterContainer = view.findViewById<LinearLayout>(R.id.page_filter_container)
                    filterContainer.orientation = LinearLayout.HORIZONTAL
                    val itemsContainer = view.findViewById<LinearLayout>(R.id.page_items_container)

                    filterScroll.visibility = View.VISIBLE
                    filterContainer.removeAllViews()

                    val density = resources.displayMetrics.density
                    val periods = listOf("day" to "今日热播榜", "week" to "本周强档榜", "month" to "月度风云榜")
                    var activePeriod: View? = null

                    for ((pKey, pLabel) in periods) {
                        val isCur = pKey == currentTopPeriod
                        val btn = Button(this).apply {
                            id = View.generateViewId()
                            text = pLabel
                            textSize = uiTextSize(12f)
                            setBackgroundResource(if (isCur) R.drawable.bg_pill_active else R.drawable.bg_pill)
                            setTextColor(if (isCur) Color.WHITE else Color.parseColor("#9ca6b5"))
                            val p = (14 * density).toInt()
                            setPadding(p, 0, p, 0)
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                ((if (tvMode) 40 else 34) * density).toInt()
                            ).apply {
                                setMargins(0, 0, (8 * density).toInt(), 0)
                            }
                            setOnClickListener {
                                currentTopPeriod = pKey
                                renderTopTab()
                            }
                        }
                        filterContainer.addView(btn)
                        if (isCur) activePeriod = btn
                    }

                    val currentList = when (currentTopPeriod) {
                        "day" -> boards.day
                        "week" -> boards.week
                        else -> boards.month
                    }

                    // 与分类列表保持一致：多列竖版海报网格；排名通过左上角角标保留。
                    val rankItems = currentList.map {
                        WorkItem(
                            title = it.title,
                            url = it.url,
                            poster = it.poster,
                            category = it.category,
                            year = it.year,
                            area = it.area,
                            progress = it.progress,
                            score = it.score
                        )
                    }
                    val topGrid = renderPosterGrid(itemsContainer, rankItems, listColumns, currentList.map { it.rank })

                    contentFrame.removeAllViews()
                    contentFrame.addView(view)
                    TvFocusNavigator.wireGrid(
                        topGrid?.cards.orEmpty(),
                        listColumns,
                        upTarget = activePeriod ?: btnTop
                    )
                    activePeriod?.let { target ->
                        btnTop.nextFocusDownId = target.id
                        view.post {
                            if (isFocusLoadCurrent(focusToken) && shouldApplyPendingFocus(btnTop.id, focusBeforeId)) target.requestFocus()
                        }
                    }
                }
        }.start()
    }

    // -------------------------------------------------------------
    // Tab 4: Daily Airing Updates
    // -------------------------------------------------------------
    private fun renderDailyTab() {
        val requestId = ++renderGeneration
        val focusBeforeId = currentFocus?.id ?: View.NO_ID
        val focusToken = beginFocusLoad()
        val root = createLoadingLayout("正在获取今日实时连载更新…")
        contentFrame.removeAllViews()
        contentFrame.addView(root)

        Thread {
            val result = runCatching { XingfanRepository.getDailyUpdates() }

            mainHandler.post {
                    if (requestId != renderGeneration || currentTab != NavTab.DAILY || isFinishing || isDestroyed) return@post
                    val items = result.getOrNull()
                    if (items == null) {
                        showErrorMessage("更新内容暂时无法读取，请检查网络后重试") { renderDailyTab() }
                        return@post
                    }
                    val view = inflatePage()
                    val container = view.findViewById<LinearLayout>(R.id.page_items_container)

                    val pageSize = 24
                    val pageCount = ((items.size + pageSize - 1) / pageSize).coerceAtLeast(1)
                    currentDailyPage = currentDailyPage.coerceIn(1, pageCount)
                    val heading = TextView(this).apply {
                        text = "最近更新 · 第 $currentDailyPage/$pageCount 页（共 ${items.size} 部）"
                        setTextColor(Color.WHITE)
                        textSize = uiTextSize(16f)
                        paint.isFakeBoldText = true
                        setPadding(0, 8, 0, 12)
                    }
                    container.addView(heading)

                    val pageItems = items.drop((currentDailyPage - 1) * pageSize).take(pageSize)
                    val dailyGrid = renderPosterGrid(container, pageItems, listColumns)
                    val firstCard = dailyGrid?.first?.apply {
                        id = View.generateViewId()
                    }
                    if (items.isEmpty()) {
                        container.addView(TextView(this).apply {
                            text = "暂时没有更新内容，请稍后再来看看"
                            setTextColor(Color.WHITE)
                            textSize = uiTextSize(14f)
                        })
                    }
                    val pager = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER
                    }
                    fun pageButton(label: String, delta: Int) = Button(this).apply {
                        text = label
                        setTextColor(Color.WHITE)
                        setBackgroundResource(R.drawable.bg_pill)
                        setOnClickListener { currentDailyPage += delta; renderDailyTab() }
                    }
                    if (currentDailyPage > 1) pager.addView(pageButton("上一页", -1))
                    if (currentDailyPage < pageCount) pager.addView(pageButton("下一页", 1))
                    container.addView(pager)

                    contentFrame.removeAllViews()
                    contentFrame.addView(view)
                    TvFocusNavigator.wireGrid(
                        dailyGrid?.cards.orEmpty(),
                        listColumns,
                        upTarget = btnDaily,
                        downTarget = firstFocusableChild(pager)
                    )
                    firstCard?.let { target ->
                        btnDaily.nextFocusDownId = target.id
                        view.post {
                            if (isFocusLoadCurrent(focusToken) && shouldApplyPendingFocus(btnDaily.id, focusBeforeId)) target.requestFocus()
                        }
                    }
                }
        }.start()
    }

    // -------------------------------------------------------------
    // Tab 5: Favorites & Watch History
    // -------------------------------------------------------------
    private fun renderMineTab(restoreFocus: Boolean = false, ensureFocusedVisible: Boolean = false) {
        val view = inflatePage()
        val filterScroll = view.findViewById<HorizontalScrollView>(R.id.page_filter_scroll)
        val filterContainer = view.findViewById<LinearLayout>(R.id.page_filter_container)
        filterContainer.orientation = LinearLayout.HORIZONTAL
        val itemsContainer = view.findViewById<LinearLayout>(R.id.page_items_container)
        val itemsScroll = itemsContainer.parent as? ScrollView
        val cards = mutableListOf<View>()
        val deleteButtons = mutableListOf<View>()
        val tabKey = currentMineSubTab
        val storage = HMVideoApplication.instance.storageManager

        fun confirmDelete(item: WorkItem, index: Int) {
            AlertDialog.Builder(this@MainActivity)
                .setTitle("删除观看记录")
                .setMessage("确定删除「${item.title}」的观看记录？")
                .setPositiveButton("删除") { _, _ ->
                    val remaining = (cards.size - 1).coerceAtLeast(0)
                    mineScrollY["history"] = itemsScroll?.scrollY ?: mineScrollY["history"] ?: 0
                    storage.removeHistory(item.url)
                    mineFocusIndex["history"] = nextFocusIndexAfterDelete(index, remaining).coerceAtLeast(0)
                    mineFocusUrl.remove("history")
                    renderMineTab(restoreFocus = true, ensureFocusedVisible = true)
                }
                .setNegativeButton("取消", null)
                .show()
        }

        fun recordPosition(index: Int, url: String) {
            mineFocusIndex[tabKey] = index
            mineFocusUrl[tabKey] = url
            itemsScroll?.post { mineScrollY[tabKey] = itemsScroll.scrollY }
        }

        fun addCard(workItem: WorkItem, onClick: (() -> Unit)? = null, deletable: Boolean = false) {
            val cardIndex = cards.size
            val onDelete = if (deletable) {
                { confirmDelete(workItem, cardIndex) }
            } else null
            val card = createWorkCard(workItem, onDelete)
            card.tag = workItem.url
            if (onClick != null) card.setOnClickListener { onClick() }
            card.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) recordPosition(cardIndex, workItem.url)
            }
            card.findViewWithTag<View>("delete")?.let { delete ->
                deleteButtons.add(delete)
                delete.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) recordPosition(cardIndex, workItem.url)
                }
            }
            cards.add(card)
            itemsContainer.addView(card)
        }

        filterScroll.visibility = View.VISIBLE
        filterContainer.removeAllViews()

        val density = resources.displayMetrics.density
        val favs = storage.getFavorites()
        val hist = storage.getHistory()

        // Tab Segmented Controls: 收藏 vs 观看历史
        val btnFav = Button(this).apply {
            id = View.generateViewId()
            text = "★ 我的收藏 (${favs.size})"
            textSize = uiTextSize(12f)
            val isCur = currentMineSubTab == "fav"
            setBackgroundResource(if (isCur) R.drawable.bg_pill_active else R.drawable.bg_pill)
            setTextColor(if (isCur) Color.WHITE else Color.parseColor("#9ca6b5"))
            val p = (14 * density).toInt()
            setPadding(p, 0, p, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                ((if (tvMode) 40 else 34) * density).toInt()
            ).apply {
                setMargins(0, 0, (8 * density).toInt(), 0)
            }
            setOnClickListener {
                currentMineSubTab = "fav"
                renderMineTab()
            }
        }
        filterContainer.addView(btnFav)

        val btnHist = Button(this).apply {
            id = View.generateViewId()
            text = "🕒 播放历史 (${hist.size})"
            textSize = uiTextSize(12f)
            val isCur = currentMineSubTab == "history"
            setBackgroundResource(if (isCur) R.drawable.bg_pill_active else R.drawable.bg_pill)
            setTextColor(if (isCur) Color.WHITE else Color.parseColor("#9ca6b5"))
            val p = (14 * density).toInt()
            setPadding(p, 0, p, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                ((if (tvMode) 40 else 34) * density).toInt()
            )
            setOnClickListener {
                currentMineSubTab = "history"
                renderMineTab()
            }
        }
        filterContainer.addView(btnHist)

        if (currentMineSubTab == "fav") {
            if (favs.isEmpty()) {
                val emptyTv = TextView(this).apply {
                    text = "您还没有收藏任何影视作品\n在影视详情页点击「收藏」即可在此查看"
                    setTextColor(Color.parseColor("#9ca6b5"))
                    textSize = uiTextSize(14f)
                    gravity = Gravity.CENTER
                    setPadding(0, 60, 0, 60)
                }
                itemsContainer.addView(emptyTv)
            } else {
                for (item in favs) {
                    addCard(
                        WorkItem(
                            title = item.title,
                            url = item.url,
                            poster = item.poster,
                            category = item.category,
                            year = item.year,
                            area = item.area
                        )
                    )
                }
            }
        } else {
            if (hist.isEmpty()) {
                val emptyTv = TextView(this).apply {
                    text = "暂无观看播放记录\n点击播放影视即可自动记录观看进度"
                    setTextColor(Color.parseColor("#9ca6b5"))
                    textSize = uiTextSize(14f)
                    gravity = Gravity.CENTER
                    setPadding(0, 60, 0, 60)
                }
                itemsContainer.addView(emptyTv)
            } else {
                for (item in hist) {
                    addCard(
                        WorkItem(
                            title = "${item.title} (${item.episodeTitle})",
                            url = item.url,
                            poster = item.poster,
                            category = item.category,
                            progress = if (item.progressSec > 0) "上次播放至 ${item.progressSec / 60}分${item.progressSec % 60}秒" else "已观看"
                        ),
                        onClick = { openHistory(item) },
                        deletable = true
                    )
                }
            }
        }

        // App Version and Feature Status Banner
        val infoCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            val p = (12 * density).toInt()
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, (16 * density).toInt(), 0, (24 * density).toInt())
            }

            val appTitle = TextView(this@MainActivity).apply {
                text = "HM影视 ${packageManager.getPackageInfo(packageName, 0).versionName}"
                setTextColor(Color.WHITE)
                textSize = uiTextSize(13f)
                paint.isFakeBoldText = true
            }
            addView(appTitle)

            val appDesc = TextView(this@MainActivity).apply {
                text = "方向键选择 · 确认键打开\n首页按菜单键进入搜索；播放时按确认键显示控制栏。"
                setTextColor(Color.parseColor("#9ca6b5"))
                textSize = uiTextSize(11f)
                setLineSpacing(4f, 1f)
                setPadding(0, (6 * density).toInt(), 0, 0)
            }
            addView(appDesc)
        }
        itemsContainer.addView(infoCard)

        contentFrame.removeAllViews()
        contentFrame.addView(view)
        btnFav.nextFocusRightId = btnHist.id
        btnHist.nextFocusLeftId = btnFav.id
        btnFav.nextFocusLeftId = btnFav.id
        btnHist.nextFocusRightId = btnHist.id
        btnFav.nextFocusUpId = btnMine.id
        btnHist.nextFocusUpId = btnMine.id
        val mineTab = if (currentMineSubTab == "fav") btnFav else btnHist
        btnMine.nextFocusDownId = mineTab.id
        TvFocusNavigator.wireList(cards, upTarget = mineTab)
        if (deleteButtons.size == cards.size) {
            deleteButtons.forEachIndexed { index, delete ->
                val card = cards[index]
                card.nextFocusRightId = delete.id
                delete.nextFocusLeftId = card.id
                delete.nextFocusUpId = if (index == 0) mineTab.id else deleteButtons[index - 1].id
                delete.nextFocusDownId =
                    if (index == deleteButtons.lastIndex) delete.id else deleteButtons[index + 1].id
                delete.nextFocusRightId = delete.id
            }
        }
        val firstCardId = cards.firstOrNull()?.id
        btnFav.nextFocusDownId = firstCardId ?: btnFav.id
        btnHist.nextFocusDownId = firstCardId ?: btnHist.id

        if (restoreFocus && cards.isNotEmpty()) {
            val index = (mineFocusIndex[tabKey] ?: 0).coerceIn(0, cards.lastIndex)
            val target = cards.firstOrNull { it.tag == mineFocusUrl[tabKey] }
                ?: cards.getOrNull(index)
                ?: cards.last()
            val scrollY = mineScrollY[tabKey] ?: 0
            view.post {
                target.requestFocus()
                if (ensureFocusedVisible) {
                    target.post {
                        target.requestRectangleOnScreen(
                            android.graphics.Rect(0, 0, target.width, target.height),
                            true
                        )
                    }
                } else {
                    itemsScroll?.scrollTo(0, scrollY)
                }
            }
        } else {
            view.post { mineTab.requestFocus() }
        }
    }

    private fun openHistory(item: HistoryItem) {
        openDetail(item.url, item.title, item.poster, item.category, null)
    }

    // 纵向海报卡片；每行列数由 renderPosterGrid 控制，电视固定六列。
    private fun createPosterCard(item: WorkItem, rank: Int? = null): View {
        val density = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            val pad = (6 * density).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins((4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())
            }

            // 竖版大封面海报 (宽高比约 2:3) 带右上角评分角标
            val posterFrame = PortraitPosterFrame(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )

                val posterIv = ImageView(this@MainActivity).apply {
                    layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundResource(R.drawable.bg_card)
                }
                ImageLoader.load(posterIv, item.poster)
                addView(posterIv)

                if (rank != null) {
                    val rankTv = TextView(this@MainActivity).apply {
                        text = "#$rank"
                        setTextColor(Color.WHITE)
                        textSize = uiTextSize(11f)
                        paint.isFakeBoldText = true
                        setBackgroundResource(if (rank <= 3) R.drawable.bg_pill_active else R.drawable.bg_pill)
                        val hp = (6 * density).toInt()
                        val vp = (2 * density).toInt()
                        setPadding(hp, vp, hp, vp)
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            gravity = Gravity.TOP or Gravity.START
                            setMargins((4 * density).toInt(), (4 * density).toInt(), 0, 0)
                        }
                    }
                    addView(rankTv)
                }

                if (!item.score.isNullOrEmpty() && item.score != "0.0") {
                    val scoreTv = TextView(this@MainActivity).apply {
                        text = "★ ${item.score}"
                        setTextColor(Color.parseColor("#b6c9e2"))
                        textSize = uiTextSize(9.5f)
                        paint.isFakeBoldText = true
                        setBackgroundResource(R.drawable.bg_pill)
                        val sp = (5 * density).toInt()
                        setPadding(sp, (2 * density).toInt(), sp, (2 * density).toInt())
                        layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                            gravity = Gravity.TOP or Gravity.END
                            setMargins(0, (4 * density).toInt(), (4 * density).toInt(), 0)
                        }
                    }
                    addView(scoreTv)
                }

                if (!item.progress.isNullOrEmpty()) {
                    val progressTv = TextView(this@MainActivity).apply {
                        text = item.progress
                        setTextColor(Color.WHITE)
                        textSize = uiTextSize(9f)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setBackgroundColor(Color.parseColor("#cc000000"))
                        setPadding((4 * density).toInt(), (2 * density).toInt(), (4 * density).toInt(), (2 * density).toInt())
                        layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                            gravity = Gravity.BOTTOM
                        }
                    }
                    addView(progressTv)
                }
            }
            addView(posterFrame)

            // 标题文本
            val titleTv = TextView(this@MainActivity).apply {
                text = item.title
                setTextColor(Color.WHITE)
                textSize = if (tvMode) 18f else 12f
                paint.isFakeBoldText = true
                maxLines = if (tvMode) 2 else 1
                minLines = if (tvMode) 2 else 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, (6 * density).toInt(), 0, 0)
            }
            addView(titleTv)

            // 副标题与分类
            val subTv = TextView(this@MainActivity).apply {
                val subText = listOfNotNull(item.category, item.year).joinToString(" · ")
                text = if (subText.isNotEmpty()) subText else (item.progress ?: "")
                setTextColor(Color.parseColor("#9ca6b5"))
                textSize = uiTextSize(10f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, (2 * density).toInt(), 0, (2 * density).toInt())
            }
            addView(subTv)

            isFocusable = true
            setOnClickListener { openDetail(item.url, item.title, item.poster, item.category, item.year) }
        }
    }

    private fun firstFocusableChild(parent: ViewGroup): View? {
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            if (child.visibility != View.VISIBLE) continue
            if (child.isFocusable) return child
            if (child is ViewGroup) firstFocusableChild(child)?.let { return it }
        }
        return null
    }

    private fun renderPosterGrid(
        container: LinearLayout,
        items: List<WorkItem>,
        columns: Int = 3,
        ranks: List<Int>? = null
    ): PosterGrid? {
        val density = resources.displayMetrics.density
        var rowLayout: LinearLayout? = null
        val cards = mutableListOf<View>()
        for (i in items.indices) {
            if (i % columns == 0) {
                rowLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 0, (6 * density).toInt())
                    }
                }
                container.addView(rowLayout)
            }
            val card = createPosterCard(items[i], ranks?.getOrNull(i)).apply {
                tag = items[i].url
            }
            cards.add(card)
            rowLayout?.addView(card)
        }
        val remainder = items.size % columns
        if (remainder != 0 && rowLayout != null) {
            for (j in 0 until (columns - remainder)) {
                val dummy = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f).apply {
                        setMargins((4 * density).toInt(), 0, (4 * density).toInt(), 0)
                    }
                }
                rowLayout.addView(dummy)
            }
        }
        return if (cards.isEmpty()) null else PosterGrid(cards)
    }

    private fun inflatePage(): View = layoutInflater.inflate(R.layout.fragment_page_list, contentFrame, false).apply {
        findViewById<ScrollView>(R.id.page_items_scroll)?.apply {
            isFocusable = false
            isFocusableInTouchMode = false
        }
        findViewById<HorizontalScrollView>(R.id.page_filter_scroll)?.isFocusable = false
        if (tvMode) {
            setBackgroundColor(Color.TRANSPARENT)
            val density = resources.displayMetrics.density
            setPadding((24 * density).toInt(), (8 * density).toInt(), (24 * density).toInt(), 0)
        }
    }

    private fun uiTextSize(baseSize: Float): Float = if (tvMode) (baseSize * 1.3f).coerceAtLeast(16f) else baseSize

    private fun trackCategoryFocus(view: View, key: String) {
        view.id = categoryFocusIds.getOrPut(key) { View.generateViewId() }
        view.isFocusable = true
        view.setOnKeyListener { focused, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                val targetId = if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) focused.nextFocusLeftId else focused.nextFocusRightId
                if (targetId != View.NO_ID) {
                    if (event.action == KeyEvent.ACTION_DOWN) focused.rootView.findViewById<View>(targetId)?.requestFocus()
                    true
                } else false
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                val explicitId = if (keyCode == KeyEvent.KEYCODE_DPAD_UP) focused.nextFocusUpId else focused.nextFocusDownId
                val row = focused.parent as? LinearLayout
                val viewport = row?.parent as? HorizontalScrollView
                val group = viewport?.parent as? LinearLayout
                val adjacentRow = if (group?.id == R.id.page_filter_container) {
                    val index = group.indexOfChild(viewport) + if (keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1
                    (group.getChildAt(index) as? HorizontalScrollView)?.getChildAt(0) as? LinearLayout
                } else null
                val bounds = android.graphics.Rect()
                focused.getGlobalVisibleRect(bounds)
                val target = if (explicitId != View.NO_ID) focused.rootView.findViewById<View>(explicitId) else {
                    adjacentRow?.let { next ->
                        (0 until next.childCount).map { next.getChildAt(it) }.filter { it.isFocusable }
                            .mapNotNull { candidate ->
                                val rect = android.graphics.Rect()
                                if (candidate.getGlobalVisibleRect(rect)) candidate to kotlin.math.abs(rect.centerX() - bounds.centerX()) else null
                            }.minByOrNull { it.second }?.first
                    }
                }
                if (target != null) {
                    if (event.action == KeyEvent.ACTION_DOWN) target.requestFocus()
                    true
                } else false
            } else false
        }
        view.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) lastCategoryFocusKey = key
        }
    }

    private class PortraitPosterFrame(context: Context) : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            val posterHeight = (measuredWidth * 1.5f).toInt()
            super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(posterHeight, View.MeasureSpec.EXACTLY))
        }
    }

    // 单行横版卡片渲染备用：包含海报图像 (ImageView + ImageLoader)
    private fun createWorkCard(item: WorkItem, onDelete: (() -> Unit)? = null): View {
        val density = resources.displayMetrics.density
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            isFocusable = true
            isClickable = true
            gravity = Gravity.CENTER_VERTICAL
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
            val posterIv = ImageView(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams((75 * density).toInt(), (105 * density).toInt())
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundResource(R.drawable.bg_card)
            }
            ImageLoader.load(posterIv, item.poster)
            addView(posterIv)

            // Right Info Column
            val infoLayout = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins((12 * density).toInt(), 0, 0, 0)
                }

                val titleTv = TextView(this@MainActivity).apply {
                    text = item.title
                    setTextColor(Color.WHITE)
                    textSize = uiTextSize(15f)
                    paint.isFakeBoldText = true
                    maxLines = 1
                }
                addView(titleTv)

                val metaText = listOfNotNull(item.category, item.year, item.area).joinToString(" · ")
                if (metaText.isNotEmpty()) {
                    val metaTv = TextView(this@MainActivity).apply {
                        text = metaText
                        setTextColor(Color.parseColor("#b6c9e2"))
                        textSize = uiTextSize(12f)
                        setPadding(0, 4, 0, 0)
                    }
                    addView(metaTv)
                }

                if (!item.progress.isNullOrEmpty()) {
                    val progressTv = TextView(this@MainActivity).apply {
                        text = item.progress
                        setTextColor(Color.parseColor("#9ca6b5"))
                        textSize = uiTextSize(11f)
                        setPadding(0, 3, 0, 0)
                    }
                    addView(progressTv)
                }

                if (!item.score.isNullOrEmpty() && item.score != "0.0") {
                    val scoreTv = TextView(this@MainActivity).apply {
                        text = "★ 评分 ${item.score}"
                        setTextColor(Color.parseColor("#b6c9e2"))
                        textSize = uiTextSize(11f)
                        paint.isFakeBoldText = true
                        setPadding(0, 3, 0, 0)
                    }
                    addView(scoreTv)
                }
            }
            addView(infoLayout)

            if (onDelete != null) {
                val deleteButton = Button(this@MainActivity).apply {
                    id = View.generateViewId()
                    tag = "delete"
                    text = "删除"
                    textSize = uiTextSize(12f)
                    setTextColor(Color.parseColor("#ff8a80"))
                    setBackgroundResource(R.drawable.bg_pill)
                    contentDescription = "删除${item.title}的播放历史"
                    isFocusable = true
                    val horizontal = (16 * density).toInt()
                    setPadding(horizontal, 0, horizontal, 0)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        (40 * density).toInt()
                    ).apply {
                        marginStart = (8 * density).toInt()
                    }
                    setOnClickListener { onDelete() }
                }
                addView(deleteButton)
            }

            setOnClickListener { openDetail(item.url, item.title, item.poster, item.category, item.year) }
        }
    }

    companion object {
        internal fun nextFocusIndexAfterDelete(deletedIndex: Int, remainingCount: Int): Int {
            if (remainingCount <= 0) return -1
            return deletedIndex.coerceIn(0, remainingCount - 1)
        }
    }

    /**
     * Only move focus into freshly loaded content if the user has not deliberately moved to
     * another top-level control while the request was in flight.
     */
    private fun shouldApplyPendingFocus(sourceNavId: Int, focusBeforeId: Int): Boolean {
        val current = currentFocus ?: return true
        if (current.id == sourceNavId || current.id == focusBeforeId) return true
        val topBarIds = intArrayOf(btnHome.id, btnCategory.id, btnTop.id, btnDaily.id, btnMine.id, R.id.btn_search)
        return current.id !in topBarIds
    }

    private fun restoreCategoryFocus() {
        val url = categoryReturnUrl ?: return
        val container = contentFrame.findViewById<LinearLayout>(R.id.page_items_container) ?: return
        val target = container.findViewWithTag<View>(url)
        categoryReturnUrl = null
        if (target == null) return
        target.requestFocus()
        (container.parent as? ScrollView)?.scrollTo(0, categoryReturnScroll)
    }

    private fun openDetail(
        url: String,
        title: String = "",
        poster: String = "",
        category: String? = null,
        year: String? = null
    ) {
        if (currentTab == NavTab.MINE) mineRestorePending = true
        if (currentTab == NavTab.CATEGORY) {
            categoryReturnUrl = url
            categoryReturnScroll = (contentFrame.findViewById<View>(R.id.page_items_container)?.parent as? ScrollView)?.scrollY ?: 0
        }
        val intent = Intent(this, DetailActivity::class.java).apply {
            putExtra("url", url)
            if (title.isNotEmpty()) putExtra("title", title)
            if (poster.isNotEmpty()) putExtra("poster", poster)
            if (!category.isNullOrEmpty()) putExtra("category", category)
            if (!year.isNullOrEmpty()) putExtra("year", year)
        }
        startActivity(intent)
    }

    private fun createLoadingLayout(message: String): View {
        return LoadingViewHelper.createLoadingView(
            context = this,
            title = message,
            subtitle = "直连超清 CDN · 防盗链自动注入 · 智能解析",
            badge = "HM影视 · 极速流媒体引擎",
            tip = "若遇网络波动，系统将自动无缝切换备用线路"
        )
    }

    private fun showErrorMessage(msg: String, onRetry: () -> Unit) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(20, 80, 20, 0)

            val tv = TextView(this@MainActivity).apply {
                text = msg
                setTextColor(Color.parseColor("#ff6b6b"))
                textSize = uiTextSize(14f)
                gravity = Gravity.CENTER
            }
            addView(tv)

            val retryBtn = Button(this@MainActivity).apply {
                text = "点击重试"
                textSize = uiTextSize(13f)
                setBackgroundResource(R.drawable.bg_pill)
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 20, 0, 0)
                }
                setOnClickListener { onRetry() }
            }
            addView(retryBtn)
        }
        contentFrame.removeAllViews()
        contentFrame.addView(root)
    }
}
