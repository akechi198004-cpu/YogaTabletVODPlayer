package com.hm.video.data.api

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.hm.video.data.model.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.regex.Pattern

object XingfanParser {

    private val gson = Gson()
    private val PROGRESS_REGEX = Pattern.compile("更新至\\s*\\d+\\s*集|全\\s*\\d+\\s*集")
    private val EMPTY_STATE_TEXTS = setOf(
        "没有找到相关内容",
        "未找到相关内容",
        "暂无相关内容",
        "没有相关结果",
        "暂无结果"
    )

    fun parseHomePage(html: String): HomeData {
        val doc: Document = Jsoup.parse(html)
        val hero = mutableListOf<HeroSlide>()

        for (el in doc.select("[data-hero-slide]")) {
            val img = el.selectFirst("img")
            val poster = img?.attr("src").orEmpty()
            val title = el.selectFirst("h1, h2")?.text()?.trim()
                ?: img?.attr("alt")?.trim().orEmpty()
            if (title.isEmpty()) continue

            var category: String? = null
            var year: String? = null
            for (tag in el.select(".proto-vtag")) {
                val v = tag.text().trim()
                if (v.matches(Regex("^\\d{4}$"))) {
                    year = v
                } else if (v.isNotEmpty()) {
                    category = v
                }
            }

            var playUrl: String? = null
            var url: String? = null
            for (a in el.select("a[href]")) {
                val href = a.attr("href")
                if (href.endsWith(".html")) {
                    playUrl = href
                } else if (href.matches(Regex("^/[a-z]+/[a-z0-9_-]+/$"))) {
                    url = href
                }
            }

            if (!url.isNullOrEmpty()) {
                hero.add(HeroSlide(title, poster, url, playUrl, category, year))
            }
        }

        val recent = mutableListOf<WorkItem>()
        for (article in doc.select("article.proto-rim")) {
            val a = article.selectFirst("a[href]") ?: continue
            val url = a.attr("href")
            if (!url.matches(Regex("^/[a-z]+/[a-z0-9_-]+/$"))) continue

            val img = article.selectFirst("img")
            val poster = img?.attr("src").orEmpty()
            val alt = img?.attr("alt")?.trim().orEmpty()
            val title = article.selectFirst(".p-2\\.5 p")?.text()?.trim()
                ?: if (alt.isNotEmpty()) alt else article.attr("aria-label").trim()
            if (title.isEmpty()) continue

            var category: String? = null
            var score: String? = null
            for (span in article.select(".p-2\\.5 span")) {
                val v = span.text().trim()
                if (v.matches(Regex("^\\d+(\\.\\d+)?$"))) {
                    score = v
                } else if (v.isNotEmpty()) {
                    category = v
                }
            }

            var progress: String? = null
            for (p in article.select("p")) {
                val v = p.text().trim()
                if (PROGRESS_REGEX.matcher(v).find()) {
                    progress = v
                    break
                }
            }

            recent.add(WorkItem(title, url, poster, category, null, null, progress, score))
        }

        return HomeData(hero, recent)
    }

    fun parseCategoryPage(html: String, requestedPage: Int = 1): PageResult {
        val doc: Document = Jsoup.parse(html)
        val items = mutableListOf<WorkItem>()

        val root = doc.selectFirst("[data-infinite-root]") ?: doc
        for (a in root.select("a.proto-rim")) {
            val url = a.attr("href")
            if (!url.matches(Regex("^/[a-z]+/[a-z0-9_-]+/$"))) continue

            val img = a.selectFirst("img")
            val poster = img?.attr("src").orEmpty()
            val alt = img?.attr("alt")?.trim().orEmpty()
            val title = a.selectFirst(".p-2\\.5 p")?.text()?.trim() ?: alt
            if (title.isEmpty()) continue

            var area: String? = null
            var year: String? = null
            for (span in a.select(".p-2\\.5 span")) {
                val v = span.text().trim()
                if (v.matches(Regex("^\\d{4}$"))) {
                    year = v
                } else if (v.isNotEmpty()) {
                    area = v
                }
            }

            var progress: String? = null
            for (p in a.select("p")) {
                val v = p.text().trim()
                if (PROGRESS_REGEX.matcher(v).find()) {
                    progress = v
                    break
                }
            }

            items.add(WorkItem(title, url, poster, null, year, area, progress, null))
        }

        val pagination = parsePagination(doc)
        val totalItems = parseTotalItems(doc)
        val filters = parseFilterOptions(doc)
        val confirmedEmpty = items.isEmpty() && hasConfirmedEmptyState(doc)

        return PageResult(
            items = items,
            page = if (requestedPage > 0) requestedPage else pagination.first,
            totalPages = pagination.second,
            totalItems = if (confirmedEmpty) 0 else totalItems,
            filters = filters
        )
    }

    fun parseSearchPage(html: String, requestedPage: Int = 1): PageResult {
        val doc: Document = Jsoup.parse(html)
        val items = mutableListOf<WorkItem>()

        for (a in doc.select("a.rim-card")) {
            val url = a.attr("href")
            if (!url.matches(Regex("^/[a-z]+/[a-z0-9_-]+/$"))) continue

            val img = a.selectFirst("img")
            val poster = img?.attr("src").orEmpty()
            val title = a.selectFirst("p")?.text()?.trim() ?: img?.attr("alt")?.trim().orEmpty()
            if (title.isEmpty()) continue

            var category: String? = null
            for (vtag in a.select(".vtag")) {
                val v = vtag.text().trim()
                if (!v.matches(Regex("^\\d{4}$"))) {
                    category = v
                    break
                }
            }

            var year: String? = null
            for (p in a.select("p")) {
                val v = p.text().trim()
                if (v.matches(Regex("^\\d{4}$"))) {
                    year = v
                    break
                }
            }

            items.add(WorkItem(title, url, poster, category, year, null, null, null))
        }

        val pagination = parsePagination(doc)
        val totalItems = parseTotalItems(doc)
        val confirmedEmpty = items.isEmpty() && hasConfirmedEmptyState(doc)

        return PageResult(
            items = items,
            page = if (requestedPage > 0) requestedPage else pagination.first,
            totalPages = pagination.second,
            totalItems = if (confirmedEmpty) 0 else totalItems
        )
    }

    fun parseDailyPage(html: String): List<WorkItem> {
        val doc: Document = Jsoup.parse(html)
        val items = mutableListOf<WorkItem>()
        val seen = HashSet<String>()

        for (a in doc.select("a.update-card")) {
            val url = a.attr("href")
            if (!url.matches(Regex("^/[a-z]+/[a-z0-9_-]+/$")) || seen.contains(url)) continue

            val img = a.selectFirst("img")
            val poster = img?.attr("src").orEmpty()
            val title = a.selectFirst("p")?.text()?.trim()
                ?: img?.attr("alt")?.trim().orEmpty()
                ?: a.attr("aria-label").trim()
            if (title.isEmpty()) continue

            seen.add(url)
            items.add(WorkItem(title, url, poster))
        }
        return items
    }

    fun parseTopPage(html: String): TopBoards {
        val doc: Document = Jsoup.parse(html)
        val dayList = mutableListOf<RankItem>()
        val weekList = mutableListOf<RankItem>()
        val monthList = mutableListOf<RankItem>()

        for (box in doc.select("div.space-y-2")) {
            val show = box.attr("x-show")
            if (!show.contains("activeCat==='all'")) continue

            val targetList = when {
                show.contains("activeTime==='day'") -> dayList
                show.contains("activeTime==='week'") -> weekList
                show.contains("activeTime==='month'") -> monthList
                else -> null
            } ?: continue

            for (a in box.select("a.rank-row")) {
                val url = a.attr("href")
                if (!url.matches(Regex("^/[a-z]+/[a-z0-9_-]+/$"))) continue

                val rank = a.selectFirst(".rank-hollow")?.text()?.toIntOrNull() ?: (targetList.size + 1)
                val img = a.selectFirst("img")
                val poster = img?.attr("src").orEmpty()
                val title = a.selectFirst("p")?.text()?.trim() ?: img?.attr("alt")?.trim().orEmpty()
                if (title.isEmpty()) continue

                var category: String? = null
                var year: String? = null
                var area: String? = null
                var progress: String? = null

                val pMeta = a.select("p").getOrNull(1)?.text()?.trim().orEmpty()
                if (pMeta.isNotEmpty()) {
                    for (part in pMeta.split("·", " ")) {
                        val s = part.trim()
                        if (s.isEmpty()) continue
                        if (s.matches(Regex("^\\d{4}$"))) {
                            year = s
                        } else if (PROGRESS_REGEX.matcher(s).find()) {
                            progress = s
                        } else if (category == null) {
                            category = s
                        } else {
                            area = s
                        }
                    }
                }

                val scoreMatcher = Pattern.compile("评分\\s*([\\d.]+)").matcher(a.text())
                val score = if (scoreMatcher.find()) scoreMatcher.group(1) else null

                var heat: String? = null
                for (div in a.select("div")) {
                    val v = div.text().trim()
                    if (v.matches(Regex("^\\d{2,}$"))) {
                        heat = v
                    }
                }

                targetList.add(RankItem(title, url, poster, rank, category, year, area, progress, score, heat))
            }
        }

        return TopBoards(dayList, weekList, monthList)
    }

    fun parseDetailPage(html: String, detailUrl: String): WorkDetail {
        val doc: Document = Jsoup.parse(html)
        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim().orEmpty()
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("meta[name=twitter:image]")?.attr("content").orEmpty()

        var year: String? = null
        var area: String? = null
        for (span in doc.select("span")) {
            val v = span.text().trim()
            if (v.contains("·")) {
                for (part in v.split("·")) {
                    val s = part.trim()
                    if (s.isEmpty() || s.matches(Regex("^\\d{4}-\\d{2}-\\d{2}$"))) continue
                    if (s.matches(Regex("^\\d{4}$"))) year = s
                    else if (!s.matches(Regex("^\\d+$")) && !s.contains("集")) area = s
                }
            }
        }

        val statusTags = setOf("热播中", "已完结", "连载中", "连载", "预告", "更新中")
        var category: String? = null
        for (tag in doc.select(".vtag, .proto-vtag")) {
            val t = tag.text().trim()
            if (t.isNotEmpty() && !t.matches(Regex("^\\d{4}$")) && !statusTags.contains(t)) {
                category = t
                break
            }
        }

        var description: String? = null
        for (p in doc.select("p")) {
            val v = p.text().trim()
            if (v.length > 30 && (v.startsWith("《") || v.contains("剧情"))) {
                description = v
                break
            }
        }

        val totalMatcher = Pattern.compile("共\\s*(\\d+)\\s*集").matcher(html)
        val totalEpisodes = if (totalMatcher.find()) totalMatcher.group(1)?.toIntOrNull() else null

        val lines = mutableListOf<WorkLine>()
        val seenLines = HashSet<Int>()

        for (btn in doc.select("button[data-tab-group=detail-lines]")) {
            val target = btn.attr("data-tab-target")
            val matcher = Pattern.compile("detail_line_(\\d+)").matcher(target)
            if (!matcher.find()) continue
            val idStr = matcher.group(1) ?: continue
            val id = idStr.toIntOrNull() ?: continue
            if (seenLines.contains(id)) continue

            val name = btn.text().trim().ifEmpty { "播放线路 $id" }
            val episodes = mutableListOf<Episode>()

            for (a in doc.select("#detail_grid_$id a.episode-link")) {
                val href = a.attr("href")
                val epMatch = Pattern.compile("^/[a-z]+/[a-z0-9_-]+/(\\d+)-(\\d+)\\.html$").matcher(href)
                if (epMatch.find()) {
                    val lineId = epMatch.group(1)?.toIntOrNull()
                    val epNo = epMatch.group(2)?.toIntOrNull()
                    if (lineId == id && epNo != null) {
                        val epTitle = a.text().trim().ifEmpty { "第 ${String.format("%02d", epNo)} 集" }
                        episodes.add(Episode(epNo, epTitle, href))
                    }
                }
            }

            episodes.sortBy { it.no }
            seenLines.add(id)
            lines.add(WorkLine(id, name, episodes))
        }

        if (lines.isEmpty()) {
            val epPattern = Pattern.compile("^/[a-z]+/[a-z0-9_-]+/(\\d+)-(\\d+)\\.html$")
            val episodesByLine = linkedMapOf<Int, MutableList<Episode>>()
            for (a in doc.select("a[href$='.html']")) {
                val href = a.attr("href")
                val m = epPattern.matcher(href)
                if (m.find()) {
                    val lineId = m.group(1)?.toIntOrNull() ?: continue
                    val epNo = m.group(2)?.toIntOrNull() ?: 1
                    val epTitle = a.text().trim().ifEmpty { "第 ${String.format("%02d", epNo)} 集" }
                    episodesByLine.getOrPut(lineId) { mutableListOf() }.add(Episode(epNo, epTitle, href))
                }
            }
            for ((lineId, episodes) in episodesByLine) {
                val uniqueEpisodes = episodes.distinctBy { it.url }.sortedBy { it.no }
                if (uniqueEpisodes.isNotEmpty()) lines.add(WorkLine(lineId, "线路 $lineId", uniqueEpisodes))
            }
        }

        return WorkDetail(
            title = title.replace(" ", ""),
            url = detailUrl,
            poster = poster,
            category = category,
            year = year,
            area = area,
            description = description,
            totalEpisodes = totalEpisodes,
            lines = lines
        )
    }

    data class ParsedPlayerConfig(
        val url: String,
        val apiurl: String,
        val next: String? = null,
        val name: String? = null
    )

    fun extractPlayerConfig(html: String): ParsedPlayerConfig? {
        val anchor = html.indexOf("zanpiancms_player")
        if (anchor == -1) return null
        val start = html.indexOf('{', anchor)
        if (start == -1) return null

        var depth = 0
        var inString = false
        var escaped = false

        for (i in start until html.length) {
            val ch = html[i]
            if (inString) {
                if (escaped) escaped = false
                else if (ch == '\\') escaped = true
                else if (ch == '"') inString = false
                continue
            }
            if (ch == '"') inString = true
            else if (ch == '{') depth++
            else if (ch == '}') {
                depth--
                if (depth == 0) {
                    val raw = html.substring(start, i + 1)
                    return try {
                        val mapType = object : TypeToken<Map<String, Any>>() {}.type
                        val map = gson.fromJson<Map<String, Any>>(raw, mapType)
                        val url = map["url"] as? String
                        val apiurl = map["apiurl"] as? String
                        val next = map["next"] as? String
                        val name = map["name"] as? String
                        if (!url.isNullOrEmpty() && !apiurl.isNullOrEmpty()) {
                            ParsedPlayerConfig(url, apiurl, next, name)
                        } else null
                    } catch (e: Exception) {
                        null
                    }
                }
            }
        }
        return null
    }

    fun parsePlayTitle(html: String): Pair<String, String> {
        val doc = Jsoup.parse(html)
        val raw = doc.selectFirst("h1")?.text()?.trim().orEmpty()
        if (raw.isEmpty()) return Pair("", "")
        val parts = raw.split("·").map { it.trim() }.filter { it.isNotEmpty() }
        return if (parts.size >= 2 && parts.last().contains("集")) {
            Pair(parts.dropLast(1).joinToString(" · "), parts.last())
        } else {
            Pair(raw, "")
        }
    }

    fun extractPlayLinesFromPlayerHtml(html: String, baseUrl: String = XingfanConfig.SOURCE_BASE): List<PlayLine> {
        val doc = Jsoup.parse(html)
        val lines = mutableListOf<PlayLine>()

        for (a in doc.select("#line-list a[data-url]")) {
            val url = normalizePlayUrl(a.attr("data-url"), baseUrl)
            if (url.isNotEmpty()) {
                val name = a.text().trim().ifEmpty { "线路 ${lines.size + 1}" }
                lines.add(PlayLine(name, url))
            }
        }

        if (lines.isEmpty()) {
            val matcher = Pattern.compile("quality:\\s*(\\[[\\s\\S]*?\\])").matcher(html)
            if (matcher.find()) {
                val jsonStr = matcher.group(1)?.replace("\\/", "/").orEmpty()
                try {
                    val listType = object : TypeToken<List<Map<String, String>>>() {}.type
                    val list = gson.fromJson<List<Map<String, String>>>(jsonStr, listType)
                    for ((i, item) in list.withIndex()) {
                        val u = normalizePlayUrl(item["url"].orEmpty(), baseUrl)
                        val name = item["html"] ?: "线路 ${i + 1}"
                        if (!u.isNullOrEmpty()) {
                            lines.add(PlayLine(name, u))
                        }
                    }
                } catch (ignored: Exception) {}
            }
        }

        if (lines.isEmpty()) {
            val matcher = Pattern.compile("url:\\s*'([^']+\\.(?:m3u8|mp4|m4v|flv)[^']*)'", Pattern.CASE_INSENSITIVE).matcher(html)
            if (matcher.find()) {
                val u = normalizePlayUrl(matcher.group(1).orEmpty(), baseUrl)
                if (!u.isNullOrEmpty()) {
                    lines.add(PlayLine("极速源", u))
                }
            }
        }

        // Deduplicate
        val seen = HashSet<String>()
        return lines.filter { if (seen.contains(it.url)) false else { seen.add(it.url); true } }
    }

    private fun normalizePlayUrl(value: String, baseUrl: String): String {
        val cleaned = value.trim().replace("\\/", "/")
        if (cleaned.isEmpty()) return ""
        val normalizedBase = if (baseUrl.startsWith("http")) baseUrl else XingfanConfig.SOURCE_BASE
        return normalizedBase.toHttpUrlOrNull()?.resolve(cleaned)?.toString().orEmpty()
    }

    private fun parsePagination(doc: Document): Pair<Int, Int> {
        val pager = doc.selectFirst(".pagination-wrap") ?: return Pair(1, 1)
        var page = 1
        var totalPages = 1

        for (span in pager.select("li.active span:not(.num)")) {
            val v = span.text().trim().toIntOrNull()
            if (v != null) page = v
        }

        for (a in pager.select("a[data^=p-]")) {
            val n = a.attr("data").replace("p-", "").toIntOrNull()
            if (n != null && n > totalPages) totalPages = n
        }

        val numText = pager.selectFirst(".num")?.text().orEmpty()
        val m = Pattern.compile("(\\d+)\\s*/\\s*(\\d+)").matcher(numText)
        if (m.find()) {
            page = m.group(1)?.toIntOrNull() ?: 1
            val t = m.group(2)?.toIntOrNull() ?: 1
            if (t > totalPages) totalPages = t
        }

        return Pair(page, totalPages)
    }

    private fun parseTotalItems(doc: Document): Int? {
        val pattern = Pattern.compile("共\\s*(\\d+)\\s*[条个部]")
        for (element in doc.select("body *")) {
            if (element.children().isNotEmpty() || !isVisibleResultNode(element)) continue
            val matcher = pattern.matcher(element.text())
            if (matcher.find()) return matcher.group(1)?.toIntOrNull()
        }
        return null
    }

    private fun hasConfirmedEmptyState(doc: Document): Boolean {
        val roots = doc.select("main").ifEmpty { listOfNotNull(doc.body()) }
        return roots.any { root ->
            root.select("img.empty-img, .empty-img").any(::isVisibleResultNode) ||
                root.select("p, div, span").any { node ->
                    isVisibleResultNode(node) && node.text().trim() in EMPTY_STATE_TEXTS
                }
        }
    }

    /** Reject templates, scripts, styles and explicitly hidden empty-state markup. */
    private fun isVisibleResultNode(element: Element): Boolean {
        var current: Element? = element
        while (current != null) {
            if (current.tagName() in setOf("template", "script", "style", "noscript")) return false
            if (current.hasAttr("hidden") || current.attr("aria-hidden").equals("true", ignoreCase = true)) return false
            if (current.hasClass("hidden") || current.hasClass("x-cloak")) return false
            val style = current.attr("style").filterNot { it.isWhitespace() }.lowercase()
            if (style.contains("display:none") || style.contains("visibility:hidden")) return false
            current = current.parent()
        }
        return true
    }

    private fun parseFilterOptions(doc: Document): FilterOptions {
        val types = mutableListOf<ChipOption>()
        val areas = mutableListOf<ChipOption>()
        val years = mutableListOf<ChipOption>()
        val letters = mutableListOf<ChipOption>()
        val sorts = mutableListOf<ChipOption>()

        val seenYears = HashSet<String>()
        val seenLetters = HashSet<String>()
        val seenSorts = HashSet<String>()

        for (chip in doc.select("a.f-chip[href^=/vodlist/]")) {
            val label = chip.text().trim()
            val href = chip.attr("href")
            if (label.isEmpty() || label.startsWith("全部")) continue

            if (label.matches(Regex("^\\d{4}$"))) {
                if (seenYears.add(label)) years.add(ChipOption(label, label))
            } else if (label.matches(Regex("^[A-Z]$"))) {
                if (seenLetters.add(label)) letters.add(ChipOption(label, label))
            } else {
                val parts = parseVodlistParts(href)
                if (parts != null && parts.type.isNotEmpty()) {
                    types.add(ChipOption(label, parts.type))
                } else if (parts != null && parts.area.isNotEmpty()) {
                    areas.add(ChipOption(label, parts.area))
                }
            }
        }

        for (sort in doc.select("a.sort-btn[href^=/vodlist/]")) {
            val label = sort.text().trim()
            val href = sort.attr("href")
            val parts = parseVodlistParts(href)
            if (parts != null && parts.sort.isNotEmpty() && seenSorts.add(parts.sort)) {
                sorts.add(ChipOption(label, parts.sort))
            }
        }

        return FilterOptions(types, areas, years, letters, sorts)
    }

    data class VodlistParts(
        val cat: String,
        val type: String,
        val area: String,
        val year: String,
        val letter: String,
        val sort: String,
        val page: Int
    )

    fun parseVodlistParts(href: String): VodlistParts? {
        val path = href.substringBefore('?')
        val m = Pattern.compile("^/vodlist/(.+)_(\\d+)\\.html$").matcher(path)
        if (!m.find()) return null
        val encodedFields = m.group(1) ?: return null
        val page = m.group(2)?.toIntOrNull() ?: 1
        // Filter values themselves may contain underscores; the category is matched
        // against known slugs and the five trailing filter slots are parsed from the end.
        val knownCategories = XingfanConfig.ALL_CATEGORIES.map { it.slug }.distinct().sortedByDescending { it.length }
        val cat = knownCategories.firstOrNull { encodedFields == it || encodedFields.startsWith("${it}_") }
            ?: return null
        val remainder = encodedFields.removePrefix("${cat}_")
        val rest = remainder.split("_", limit = 5).toMutableList()
        while (rest.size < 5) rest.add("")
        val filters = rest
        return VodlistParts(
            cat = cat,
            type = filters[0],
            area = filters[1],
            year = filters[2],
            letter = filters[3],
            sort = filters[4],
            page = page
        )
    }
}
