package com.hm.video.data.api

import com.hm.video.data.model.Category

object XingfanConfig {
    const val SOURCE_BASE = "https://www.xingfan.cc"
    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    const val FETCH_TIMEOUT_SEC = 4L
    const val LOCAL_PROXY_PORT = 8998

    val TV_CATEGORIES = listOf(
        Category("dianshiju", "全部剧集"),
        Category("guocanju", "国产剧"),
        Category("xianggangju", "香港剧"),
        Category("oumeiju", "欧美剧"),
        Category("ribenju", "日本剧"),
        Category("haiwaiju", "海外剧"),
        Category("taiwanju", "台湾剧"),
        Category("hanguoju", "韩剧"),
        Category("taiguoju", "泰剧")
    )

    val MOVIE_CATEGORIES = listOf(
        Category("dianying", "全部电影"),
        Category("dongzuopian", "动作片"),
        Category("xijupian", "喜剧片"),
        Category("aiqingpian", "爱情片"),
        Category("kehuanpian", "科幻片"),
        Category("kongbupian", "恐怖片"),
        Category("zhanzhengpian", "战争片"),
        Category("juqingpian", "剧情片"),
        Category("jilupian", "纪录片"),
        Category("donghuapian", "动画片")
    )

    val SINGLE_CATEGORIES = listOf(
        Category("all", "全部热门"),
        Category("dianying", "全部电影"),
        Category("dianshiju", "全部剧集"),
        Category("dongman", "全部动漫"),
        Category("ribendongman", "日本动漫"),
        Category("guochandongman", "国创动画"),
        Category("zongyi", "全部综艺"),
        Category("dalu_variety", "大陆真人秀"),
        Category("talk_variety", "脱口秀相声")
    )

    val ALL_CATEGORIES = TV_CATEGORIES + MOVIE_CATEGORIES + SINGLE_CATEGORIES

    fun getCategoryLabel(slug: String): String {
        return ALL_CATEGORIES.firstOrNull { it.slug == slug }?.label ?: slug
    }
}
