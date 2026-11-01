//! 数据模型：与 Android 版 Xingfan 数据层保持一致，并补充桌面端需要的缓存字段。

use serde::{Deserialize, Serialize};

/// 分类入口。
#[derive(Clone, Debug)]
pub struct Category {
    pub slug: &'static str,
    pub label: &'static str,
}

/// 海报墙中的单个作品。
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct WorkItem {
    pub title: String,
    /// 站内相对详情地址，例如 `/guocanju/fanhua/`。
    pub url: String,
    pub poster: String,
    #[serde(default)]
    pub category: Option<String>,
    #[serde(default)]
    pub year: Option<String>,
    #[serde(default)]
    pub area: Option<String>,
    #[serde(default)]
    pub progress: Option<String>,
    #[serde(default)]
    pub score: Option<String>,
    /// 榜单排名；普通列表为空。
    #[serde(default)]
    pub rank: Option<u32>,
}

impl WorkItem {
    /// 卡片第二行：分类 · 年份；没有时退回更新进度。
    pub fn subtitle(&self) -> String {
        let meta = [self.category.as_deref(), self.year.as_deref()]
            .into_iter()
            .flatten()
            .filter(|value| !value.trim().is_empty())
            .collect::<Vec<_>>()
            .join(" · ");
        if meta.is_empty() {
            self.progress.clone().unwrap_or_default()
        } else {
            meta
        }
    }
}

/// 首页精选轮播条目。
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct HeroSlide {
    pub title: String,
    pub poster: String,
    pub url: String,
    #[serde(default)]
    pub play_url: Option<String>,
    #[serde(default)]
    pub category: Option<String>,
    #[serde(default)]
    pub year: Option<String>,
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct HomeData {
    #[serde(default)]
    pub hero: Vec<HeroSlide>,
    #[serde(default)]
    pub recent: Vec<WorkItem>,
}

/// 单集。
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct Episode {
    pub no: u32,
    pub title: String,
    pub url: String,
}

/// 详情页的一条播放线路。
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct WorkLine {
    pub id: u32,
    pub name: String,
    #[serde(default)]
    pub episodes: Vec<Episode>,
}

/// 作品详情。磁盘缓存只保存线路与剧集，不保存播放直链。
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct WorkDetail {
    pub title: String,
    pub url: String,
    pub poster: String,
    #[serde(default)]
    pub category: Option<String>,
    #[serde(default)]
    pub year: Option<String>,
    #[serde(default)]
    pub area: Option<String>,
    #[serde(default)]
    pub description: Option<String>,
    #[serde(default)]
    pub total_episodes: Option<u32>,
    #[serde(default)]
    pub lines: Vec<WorkLine>,
}

impl WorkDetail {
    pub fn meta_line(&self) -> String {
        [
            self.category.as_deref(),
            self.year.as_deref(),
            self.area.as_deref(),
        ]
        .into_iter()
        .flatten()
        .filter(|value| !value.trim().is_empty())
        .collect::<Vec<_>>()
        .join(" · ")
    }
}

/// 播放服务返回的实际流媒体线路。
#[derive(Clone, Debug, Default)]
pub struct PlayLine {
    pub name: String,
    pub url: String,
}

#[derive(Clone, Debug, Default)]
pub struct PlayInfo {
    pub work_title: String,
    pub episode_title: String,
    #[allow(dead_code)]
    pub poster: Option<String>,
    pub lines: Vec<PlayLine>,
    #[allow(dead_code)]
    pub next_episode_url: Option<String>,
    /// 播放服务页面的最终地址，作为媒体请求的 Referer。
    pub service_url: Option<String>,
    pub episode_no: u32,
}

/// 分类/搜索筛选选项。
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct ChipOption {
    pub label: String,
    pub value: String,
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct FilterOptions {
    #[serde(default)]
    pub types: Vec<ChipOption>,
    #[serde(default)]
    pub areas: Vec<ChipOption>,
    #[serde(default)]
    pub years: Vec<ChipOption>,
    #[serde(default)]
    pub letters: Vec<ChipOption>,
    #[serde(default)]
    pub sorts: Vec<ChipOption>,
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct PageResult {
    #[serde(default)]
    pub items: Vec<WorkItem>,
    #[serde(default = "one")]
    pub page: u32,
    #[serde(default = "one")]
    pub total_pages: u32,
    #[serde(default)]
    pub total_items: Option<u32>,
    #[serde(default)]
    pub filters: Option<FilterOptions>,
}

fn one() -> u32 {
    1
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct TopBoards {
    #[serde(default)]
    pub day: Vec<WorkItem>,
    #[serde(default)]
    pub week: Vec<WorkItem>,
    #[serde(default)]
    pub month: Vec<WorkItem>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct FavoriteItem {
    pub url: String,
    pub title: String,
    #[serde(default)]
    pub poster: String,
    #[serde(default)]
    pub category: Option<String>,
    #[serde(default)]
    pub year: Option<String>,
    #[serde(default)]
    pub area: Option<String>,
    #[serde(default)]
    pub added_at: i64,
}

/// 播放历史。外部 VLC 无法回传播放进度，因此只记录最后播放的线路与剧集。
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct HistoryItem {
    pub url: String,
    pub title: String,
    #[serde(default)]
    pub poster: String,
    #[serde(default)]
    pub category: Option<String>,
    #[serde(default)]
    pub year: Option<String>,
    #[serde(default)]
    pub area: Option<String>,
    #[serde(default)]
    pub line_name: Option<String>,
    #[serde(default = "one")]
    pub episode_no: u32,
    #[serde(default)]
    pub episode_title: String,
    #[serde(default)]
    pub episode_url: String,
    #[serde(default)]
    pub played_at: i64,
}

impl HistoryItem {
    pub fn episode_line(&self) -> String {
        let ep = if self.episode_title.trim().is_empty() {
            format!("第{:02}集", self.episode_no)
        } else {
            self.episode_title.clone()
        };
        match self
            .line_name
            .as_deref()
            .filter(|value| !value.trim().is_empty())
        {
            Some(line) => format!("{ep} · {line}"),
            None => ep,
        }
    }

    pub fn to_work_item(&self) -> WorkItem {
        WorkItem {
            title: self.title.clone(),
            url: self.url.clone(),
            poster: self.poster.clone(),
            category: self.category.clone(),
            year: self.year.clone(),
            area: self.area.clone(),
            progress: Some(self.episode_line()),
            score: None,
            rank: None,
        }
    }
}

/// 搜索分类：对应站点的 cid。
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum SearchKind {
    #[default]
    All,
    Movie,
    Tv,
    Anime,
}

impl SearchKind {
    pub fn cid(self) -> Option<u32> {
        match self {
            Self::All => None,
            Self::Movie => Some(1),
            Self::Tv => Some(2),
            Self::Anime => Some(3),
        }
    }

    pub fn label(self) -> &'static str {
        match self {
            Self::All => "全部",
            Self::Movie => "电影",
            Self::Tv => "剧集",
            Self::Anime => "动漫",
        }
    }
}

/// 当前主页面。
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum MainTab {
    Home,
    Category,
    Top,
    Daily,
    Mine,
}

impl MainTab {
    pub fn label(self) -> &'static str {
        match self {
            Self::Home => "首页",
            Self::Category => "分类",
            Self::Top => "榜单",
            Self::Daily => "更新",
            Self::Mine => "收藏/历史",
        }
    }

    /// 窄窗口使用的两字标签，避免顶栏挤压搜索框。
    pub fn compact_label(self) -> &'static str {
        match self {
            Self::Home => "首页",
            Self::Category => "分类",
            Self::Top => "榜单",
            Self::Daily => "更新",
            Self::Mine => "我的",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum MineTab {
    Favorites,
    History,
}

/// 首页、分类、榜单、更新与搜索共用的列表请求身份。
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum BrowseKind {
    Home,
    Category {
        cat: String,
        page: u32,
        area: Option<String>,
        year: Option<String>,
        sort: Option<String>,
    },
    Top,
    Daily,
    Search {
        keyword: String,
        kind: SearchKind,
        page: u32,
    },
}

impl BrowseKind {
    #[allow(dead_code)]
    pub fn label(&self) -> &'static str {
        match self {
            Self::Home => "首页",
            Self::Category { .. } => "分类",
            Self::Top => "榜单",
            Self::Daily => "更新",
            Self::Search { .. } => "搜索",
        }
    }
}

/// 分类页当前筛选状态。
#[derive(Clone, Debug)]
pub struct CategoryState {
    pub main_type: &'static str,
    pub cat_slug: &'static str,
    pub page: u32,
    pub area: Option<String>,
    pub year: Option<String>,
    pub sort: Option<String>,
}

impl Default for CategoryState {
    fn default() -> Self {
        Self {
            main_type: "movie",
            cat_slug: "dianying",
            page: 1,
            area: None,
            year: None,
            sort: Some("addtime".to_owned()),
        }
    }
}

/// 搜索页状态。
#[derive(Clone, Debug)]
pub struct SearchState {
    pub keyword: String,
    pub kind: SearchKind,
    pub page: u32,
}

impl Default for SearchState {
    fn default() -> Self {
        Self {
            keyword: String::new(),
            kind: SearchKind::All,
            page: 1,
        }
    }
}
