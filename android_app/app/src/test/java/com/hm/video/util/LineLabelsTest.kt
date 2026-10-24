package com.hm.video.util

import com.hm.video.data.model.PlayLine
import org.junit.Assert.assertEquals
import org.junit.Test

class LineLabelsTest {

    @Test
    fun numbersDuplicateProvidersInDisplayOrder() {
        val labels = lineLabels(listOf(
            PlayLine("线路1", "https://six.svipplay.com/lzm3u8/a.m3u8?token=x"),
            PlayLine("线路2", "https://vodcnd05.uvjtih.cn/20260427/a/index.m3u8"),
            PlayLine("线路3", "https://svip.ryplay14.com/20260427/16441/index.m3u8"),
            PlayLine("线路4", "https://six.svipplay.com/zykm3u8/b.m3u8?token=y"),
            PlayLine("线路5", "https://vv.jisuzyv.com/play/a/index.m3u8")
        ))
        assertEquals(
            listOf("svipplay(1)", "uvjtih", "ryplay14", "svipplay(2)", "jisuzyv"),
            labels
        )
    }

    @Test
    fun stripsSubdomainsPortsAndCommonSecondLevelSuffixes() {
        assertEquals(
            listOf("jisuts", "fengbao8", "yzzyhd-play", "letvoss"),
            lineLabels(listOf(
                PlayLine("a", "https://p.jisuts.com:999/hls/a.m3u8"),
                PlayLine("b", "https://v.fengbao8.com/video/a/index.m3u8"),
                PlayLine("c", "https://cdn1.yzzyhd-play.com/a.m3u8"),
                PlayLine("d", "https://v6.letvoss.com/2022/a/index.m3u8")
            ))
        )
        assertEquals(listOf("example"), lineLabels(listOf(
            PlayLine("a", "https://video.example.com.cn/a.m3u8")
        )))
    }

    @Test
    fun keepsOriginalNameWhenUrlHasNoUsableHost() {
        assertEquals(
            listOf("备用线路", "relative"),
            lineLabels(listOf(
                PlayLine("备用线路", "not a url"),
                PlayLine("relative", "/video/index.m3u8")
            ))
        )
    }
}
