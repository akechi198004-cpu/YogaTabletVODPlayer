package com.hm.video.data.api

import org.junit.Assert.*
import org.junit.Test

class XingfanParserTest {
    @Test
    fun parsesActualHomeCardAndHeroSelectors() {
        val home = XingfanParser.parseHomePage("""
            <section data-hero-slide><h2>测试精选</h2><img src='/hero.jpg'>
                <a href='/dianshiju/test/'>详情</a><span class='proto-vtag'>2026</span></section>
            <article class='proto-rim'><a href='/dianying/movie/'><img src='/poster.jpg' alt='测试影片'></a>
                <p>更新至 12 集</p></article>
        """.trimIndent())
        assertEquals("测试精选", home.hero.single().title)
        assertEquals("2026", home.hero.single().year)
        assertEquals("/dianying/movie/", home.recent.single().url)
        assertEquals("更新至 12 集", home.recent.single().progress)
    }

    @Test
    fun parsesEpisodesInNumericOrderAndKeepsLineIdentity() {
        val detail = XingfanParser.parseDetailPage("""
            <h1>测试剧集</h1><button data-tab-group='detail-lines' data-tab-target='detail_line_2'>备用线</button>
            <div id='detail_grid_2'>
                <a class='episode-link' href='/dianshiju/test/2-10.html'>第10集</a>
                <a class='episode-link' href='/dianshiju/test/2-2.html'>第2集</a>
            </div>
        """.trimIndent(), "/dianshiju/test/")
        assertEquals(2, detail.lines.single().id)
        assertEquals(listOf(2, 10), detail.lines.single().episodes.map { it.no })
    }

    @Test
    fun resolvesAndDeduplicatesServiceLinesAgainstFinalUrl() {
        val lines = XingfanParser.extractPlayLinesFromPlayerHtml("""
            <div id='line-list'>
                <a data-url='../media/main.m3u8?token=one'>线路一</a>
                <a data-url='https://cdn.example/media/main.m3u8?token=one'>重复线</a>
            </div>
        """.trimIndent(), "https://cdn.example/player/index.html")
        assertEquals(1, lines.size)
        assertEquals("https://cdn.example/media/main.m3u8?token=one", lines.single().url)
    }

    @Test
    fun playerConfigKeepsNextEpisodeAndBracesInsideStrings() {
        val config = XingfanParser.extractPlayerConfig("""
            <script>var zanpiancms_player={"url":"id{one}","apiurl":"/service?url=","next":"/dianshiju/test/1-2.html"};</script>
        """.trimIndent())
        assertNotNull(config)
        assertEquals("id{one}", config!!.url)
        assertEquals("/dianshiju/test/1-2.html", config.next)
    }

    @Test
    fun emptySearchAndUnrecognizedPageDoNotInventResults() {
        assertTrue(XingfanParser.parseSearchPage("<main>没有相关结果</main>").items.isEmpty())
        val challenge = XingfanParser.parseHomePage("<html><h1>请完成验证</h1></html>")
        assertTrue(challenge.hero.isEmpty())
        assertTrue(challenge.recent.isEmpty())
    }

    @Test
    fun onlyVisibleDomEmptyStateConfirmsZeroResult() {
        val confirmed = XingfanParser.parseSearchPage(
            "<html><body><main><img class='empty-img'><p>没有找到相关内容</p></main></body></html>"
        )
        assertEquals(0, confirmed.totalItems)

        val cssOnly = XingfanParser.parseSearchPage(
            "<html><head><style>.empty-img { width: 200px; }</style></head><body><main></main></body></html>"
        )
        assertNull(cssOnly.totalItems)

        val scriptOnly = XingfanParser.parseSearchPage(
            "<html><body><main><script>const t = '<div class=\"empty-img\">没有找到相关内容</div>'</script></main></body></html>"
        )
        assertNull(scriptOnly.totalItems)

        val templateOnly = XingfanParser.parseSearchPage(
            "<html><body><main><template><div class='empty-img'>没有找到相关内容</div></template></main></body></html>"
        )
        assertNull(templateOnly.totalItems)

        val hiddenOnly = XingfanParser.parseSearchPage(
            "<html><body><main><div class='empty-img' style='display: none'></div></main></body></html>"
        )
        assertNull(hiddenOnly.totalItems)

        val commentOnly = XingfanParser.parseSearchPage(
            "<html><body><main><!-- <div class='empty-img'></div> --></main></body></html>"
        )
        assertNull(commentOnly.totalItems)

        assertNull(XingfanParser.parseSearchPage("<html><h1>请完成验证</h1></html>").totalItems)
    }
}
