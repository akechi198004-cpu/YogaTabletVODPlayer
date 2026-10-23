package com.hm.video.data.model

import java.io.Serializable

data class WorkItem(
    val title: String,
    val url: String,
    val poster: String,
    val category: String? = null,
    val year: String? = null,
    val area: String? = null,
    val progress: String? = null,
    val score: String? = null
) : Serializable

data class HeroSlide(
    val title: String,
    val poster: String,
    val url: String,
    val playUrl: String? = null,
    val category: String? = null,
    val year: String? = null
) : Serializable

data class HomeData(
    val hero: List<HeroSlide>,
    val recent: List<WorkItem>
) : Serializable

data class Episode(
    val no: Int,
    val title: String,
    val url: String
) : Serializable

data class WorkLine(
    val id: Int,
    val name: String,
    val episodes: List<Episode>
) : Serializable

data class WorkDetail(
    val title: String,
    val url: String,
    val poster: String,
    val category: String? = null,
    val year: String? = null,
    val area: String? = null,
    val description: String? = null,
    val totalEpisodes: Int? = null,
    val lines: List<WorkLine> = emptyList()
) : Serializable

data class PlayLine(
    val name: String,
    val url: String
) : Serializable

data class PlayInfo(
    val workTitle: String,
    val episodeTitle: String,
    val poster: String? = null,
    val lines: List<PlayLine> = emptyList(),
    val nextEpisodeUrl: String? = null,
    val serviceUrl: String? = null,
    val episodeNo: Int = 1
) : Serializable

data class RankItem(
    val title: String,
    val url: String,
    val poster: String,
    val rank: Int,
    val category: String? = null,
    val year: String? = null,
    val area: String? = null,
    val progress: String? = null,
    val score: String? = null,
    val heat: String? = null
) : Serializable

data class TopBoards(
    val day: List<RankItem> = emptyList(),
    val week: List<RankItem> = emptyList(),
    val month: List<RankItem> = emptyList()
) : Serializable

data class ChipOption(
    val label: String,
    val value: String
) : Serializable

data class FilterOptions(
    val types: List<ChipOption> = emptyList(),
    val areas: List<ChipOption> = emptyList(),
    val years: List<ChipOption> = emptyList(),
    val letters: List<ChipOption> = emptyList(),
    val sorts: List<ChipOption> = emptyList()
) : Serializable

data class PageResult(
    val items: List<WorkItem>,
    val page: Int,
    val totalPages: Int,
    val totalItems: Int? = null,
    val filters: FilterOptions? = null
) : Serializable

data class FavoriteItem(
    val url: String,
    val title: String,
    val poster: String,
    val category: String? = null,
    val year: String? = null,
    val area: String? = null,
    val addedAt: Long = System.currentTimeMillis()
) : Serializable

data class HistoryItem(
    val url: String,
    val title: String,
    val poster: String,
    val category: String? = null,
    val lineId: Int = 1,
    val lineName: String? = null,
    val episodeNo: Int = 1,
    val episodeTitle: String = "",
    val episodeUrl: String = "",
    var progressSec: Long = 0,
    var durationSec: Long = 0,
    var playedAt: Long = System.currentTimeMillis()
) : Serializable

data class Category(
    val slug: String,
    val label: String
) : Serializable
