package com.hm.video.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.hm.video.R
import com.hm.video.data.model.HeroSlide
import com.hm.video.util.ImageLoader

/** One large artwork at a time; remote focus remains stable while switching titles. */
class HeroCarouselView(
    context: Context,
    private val slides: List<HeroSlide>,
    initialIndex: Int,
    private val onIndexChanged: (Int) -> Unit,
    private val onOpen: (HeroSlide) -> Unit
) : FrameLayout(context) {
    private val handler = Handler(Looper.getMainLooper())
    private var index = initialIndex.coerceIn(0, (slides.size - 1).coerceAtLeast(0))
    private var active = true
    private val artwork = ImageView(context)
    private val title = TextView(context)
    private val meta = TextView(context)
    private val counter = TextView(context)
    private val dots = LinearLayout(context)
    private val autoAdvance = object : Runnable {
        override fun run() {
            if (canAdvance()) { showSlide(index + 1); schedule() }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    init {
        id = View.generateViewId()
        isFocusable = true
        isClickable = true
        setBackgroundResource(R.drawable.bg_card)
        foreground = resources.getDrawable(R.drawable.bg_hero_focus, context.theme)
        clipToOutline = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        artwork.scaleType = ImageView.ScaleType.CENTER_CROP
        artwork.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(artwork, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#F20D1118"), Color.parseColor("#9E0D1118"), Color.parseColor("#100D1118")))
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.parseColor("#D90D1118"), Color.TRANSPARENT))
        }, LayoutParams(LayoutParams.MATCH_PARENT, dp(160), Gravity.BOTTOM))

        val info = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(16), dp(24), dp(16))
        }
        val badge = TextView(context).apply {
            text = "今日精选"
            textSize = 16f
            setTextColor(Color.parseColor("#CBD8E9"))
            setPadding(0, 0, 0, dp(4))
        }
        info.addView(badge)
        title.apply {
            textSize = 30f
            setTextColor(Color.WHITE)
            paint.isFakeBoldText = true
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        info.addView(title)
        meta.apply {
            textSize = 18f
            setTextColor(Color.parseColor("#CFD8E5"))
            setPadding(0, dp(4), 0, dp(8))
        }
        info.addView(meta)
        info.addView(TextView(context).apply {
            text = "▶ 选集 / 播放"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(20), 0, dp(20), 0)
            setBackgroundResource(R.drawable.bg_gradient_button)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)))
        addView(info, LayoutParams(dp(760), LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))

        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun arrow(textValue: String, description: String, delta: Int) = TextView(context).apply {
            text = textValue
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundResource(R.drawable.bg_pill)
            contentDescription = description
            isFocusable = false
            setOnClickListener { showSlide(index + delta); schedule() }
        }
        if (slides.size > 1) {
            controls.addView(arrow("‹", "上一张精选", -1), LinearLayout.LayoutParams(dp(44), dp(44)))
            dots.gravity = Gravity.CENTER
            dots.setPadding(dp(10), 0, dp(10), 0)
            controls.addView(dots, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)))
            controls.addView(arrow("›", "下一张精选", 1), LinearLayout.LayoutParams(dp(44), dp(44)))
        }
        addView(controls, LayoutParams(LayoutParams.WRAP_CONTENT, dp(44), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(dp(24), 0, dp(24), dp(16))
        })
        counter.textSize = 16f
        counter.setTextColor(Color.parseColor("#CFD8E5"))
        addView(counter, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            setMargins(0, dp(22), dp(28), 0)
        })
        setOnClickListener { slides.getOrNull(index)?.let(onOpen) }
        setOnFocusChangeListener { _, _ -> schedule() }
        showSlide(index)
    }

    fun setActive(value: Boolean) { active = value; schedule() }

    private fun showSlide(target: Int) {
        if (slides.isEmpty()) return
        index = ((target % slides.size) + slides.size) % slides.size
        val slide = slides[index]
        title.text = slide.title
        meta.text = listOfNotNull(slide.category, slide.year).filter { it.isNotBlank() }.joinToString(" · ")
        counter.text = "${index + 1} / ${slides.size}"
        artwork.setImageDrawable(null)
        ImageLoader.load(artwork, slide.poster)
        dots.removeAllViews()
        slides.indices.forEach { number ->
            dots.addView(View(context).apply {
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(if (number == index) "#EAF1FF" else "#64758C"))
                    cornerRadius = dp(3).toFloat()
                }
            }, LinearLayout.LayoutParams(dp(if (number == index) 22 else 6), dp(6)).apply {
                setMargins(dp(3), 0, dp(3), 0)
            })
        }
        contentDescription = "今日精选，第${index + 1}张，共${slides.size}张，${slide.title}。左右键切换，确认选集或播放"
        onIndexChanged(index)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (event.repeatCount == 0) showSlide(index + if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
            schedule()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (slides.size > 1) {
            info.isScrollable = true
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
            showSlide(index + if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) 1 else -1)
            schedule()
            return true
        }
        return super.performAccessibilityAction(action, arguments)
    }

    private fun canAdvance() = active && isAttachedToWindow && isShown &&
        windowVisibility == View.VISIBLE && hasWindowFocus() && !hasFocus() && slides.size > 1
    private fun schedule() {
        handler.removeCallbacks(autoAdvance)
        if (canAdvance()) handler.postDelayed(autoAdvance, 7000)
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); schedule() }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        schedule()
    }
    override fun onDetachedFromWindow() { handler.removeCallbacks(autoAdvance); super.onDetachedFromWindow() }
}
