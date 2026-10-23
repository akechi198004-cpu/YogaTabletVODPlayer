package com.hm.video.util

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import com.hm.video.R

/**
 * 全局统一的影视级加载与等待界面构建器
 * 摒弃单行文字，采用赛博朋克极简暗黑卡片、青蓝动效光环与多维度解析状态
 */
object LoadingViewHelper {

    fun createLoadingView(
        context: Context,
        title: String = "正在极速加载中…",
        subtitle: String = "正在直连全球 CDN 节点 · 防盗链自动注入",
        badge: String = "HM影视 · 极速流媒体引擎",
        tip: String = "若遇网络波动，系统将自动无缝切换备用线路"
    ): View {
        val view = LayoutInflater.from(context).inflate(R.layout.layout_loading_view, null, false)
        view.findViewById<TextView>(R.id.loading_main_title)?.text = title
        view.findViewById<TextView>(R.id.loading_sub_title)?.text = subtitle
        view.findViewById<TextView>(R.id.loading_engine_badge)?.text = badge
        view.findViewById<TextView>(R.id.loading_bottom_tip)?.text = tip
        return view
    }
}
