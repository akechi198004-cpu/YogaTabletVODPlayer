//! 主应用状态与后台事件处理。

use std::collections::{HashMap, HashSet, VecDeque};
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::mpsc::{Receiver, Sender, SyncSender, TrySendError};
use std::sync::Arc;
use std::thread;
use std::time::{Duration, Instant};

use eframe::egui::{self, ColorImage, TextureHandle, TextureOptions};

use crate::cache::{DecodedPoster, MetadataCache, PosterCache};
use crate::config::{now_unix, AppConfig, ThemeMode, UserData};
use crate::models::{
    BrowseKind, CategoryState, Episode, FavoriteItem, FilterOptions, HistoryItem, MainTab, MineTab,
    PlayInfo, SearchKind, SearchState, WorkDetail, WorkItem,
};
use crate::player;
use crate::ui::theme;
use crate::worker::{spawn_workers, BrowseData, Command, Event, LineProbeResult, PosterCommand};
use crate::AppPaths;

#[path = "app_ui.rs"]
mod app_ui;

const MAX_EVENTS_PER_FRAME: usize = 32;
const MAX_TEXTURE_UPLOADS_PER_FRAME: usize = 2;
const MAX_POSTER_REQUESTS_PER_FRAME: usize = 4;
const TEXTURE_BUDGET_BYTES: usize = 24 * 1024 * 1024;
const TEXTURE_MAX_ENTRIES: usize = 120;
const GRID_PRELOAD_ROWS: usize = 1;
const DAILY_PAGE_SIZE: usize = 24;
const POSTER_RETRY_BASE: Duration = Duration::from_secs(2);
const POSTER_RETRY_MAX: Duration = Duration::from_secs(300);

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum TopPeriod {
    Day,
    Week,
    Month,
}

impl TopPeriod {
    pub fn label(self) -> &'static str {
        match self {
            Self::Day => "今日热播",
            Self::Week => "本周强档",
            Self::Month => "月度风云",
        }
    }
}

/// 设置页草稿。关闭页面时丢弃，只有点击保存才写回生效配置。
#[derive(Clone, Debug)]
pub struct SettingsDraft {
    pub source_base: String,
    pub vlc_path: String,
    pub theme: ThemeMode,
    pub detected_vlc: Option<String>,
}

impl SettingsDraft {
    fn from_config(config: &AppConfig) -> Self {
        Self {
            source_base: config.source_base.clone(),
            vlc_path: config.vlc_path.clone(),
            theme: config.theme,
            detected_vlc: None,
        }
    }

    /// 返回连接源是否变化。
    fn apply(self, config: &mut AppConfig) -> bool {
        let old_base = config.source_base_trimmed();
        config.source_base = self.source_base.trim().trim_end_matches('/').to_owned();
        if config.source_base.is_empty() {
            config.source_base = crate::config::DEFAULT_SOURCE_BASE.to_owned();
        }
        config.vlc_path = self.vlc_path;
        config.theme = self.theme;
        old_base != config.source_base_trimmed()
    }
}

struct PosterTexture {
    handle: TextureHandle,
    last_used_frame: u64,
    bytes: usize,
    size: [usize; 2],
}

struct DeferredPoster {
    epoch: u64,
    key: String,
    poster: DecodedPoster,
}

#[derive(Clone, Copy, Debug)]
struct PosterFailure {
    attempts: u32,
    next_retry: Instant,
}

#[derive(Default)]
struct BrowseState {
    id: u64,
    kind: Option<BrowseKind>,
    data: Option<BrowseData>,
    loading: bool,
    error: Option<String>,
    cached: bool,
}

struct DetailView {
    id: u64,
    fallback: WorkItem,
    loading: bool,
    detail: Option<WorkDetail>,
    error: Option<String>,
    active_line_id: Option<u32>,
    description_expanded: bool,
    cached: bool,
}

struct PlayDialog {
    id: u64,
    work: WorkItem,
    episode_url: String,
    episode_title: String,
    episode_no: u32,
    line_name: String,
    loading: bool,
    info: Option<PlayInfo>,
    error: Option<String>,
    probe: HashMap<usize, LineProbeResult>,
    probing: bool,
}

#[derive(Clone, Debug)]
enum ConfirmAction {
    ClearHistory,
    ClearFavorites,
}

/// 主应用。
pub struct App {
    config: AppConfig,
    config_path: PathBuf,
    user_file: PathBuf,
    cache_dir: PathBuf,
    config_load_failed: bool,
    user_data: UserData,
    poster_cache: PosterCache,

    tab: MainTab,
    category: CategoryState,
    category_filters: Option<FilterOptions>,
    top_period: TopPeriod,
    daily_page: u32,
    mine_tab: MineTab,
    search: SearchState,
    search_active: bool,
    search_text: String,
    hero_index: usize,
    browse: BrowseState,
    detail: Option<DetailView>,
    play: Option<PlayDialog>,
    selected_url: Option<String>,
    show_settings: bool,
    settings_draft: Option<SettingsDraft>,
    show_search_history: bool,
    confirm: Option<ConfirmAction>,

    status_text: String,
    error_text: Option<String>,

    textures: HashMap<String, PosterTexture>,
    poster_jobs: HashSet<String>,
    poster_failures: HashMap<String, PosterFailure>,
    poster_requests_left: u8,
    poster_uploads_left: u8,
    deferred_posters: VecDeque<DeferredPoster>,
    frame_index: u64,
    poster_epoch: u64,

    browse_seq: u64,
    detail_seq: u64,
    play_seq: u64,
    browse_epoch_shared: Arc<AtomicU64>,
    detail_epoch_shared: Arc<AtomicU64>,
    play_epoch_shared: Arc<AtomicU64>,
    poster_epoch_shared: Arc<AtomicU64>,

    worker_tx: Sender<Command>,
    worker_rx: Receiver<Event>,
    poster_txs: Vec<SyncSender<PosterCommand>>,
    poster_next_worker: usize,
}

impl App {
    pub fn new(cc: &eframe::CreationContext<'_>, paths: AppPaths) -> Self {
        let (config, config_error) = match AppConfig::load_or_default(&paths.config_file) {
            Ok(config) => (config, None),
            Err(error) => (
                AppConfig::default(),
                Some(format!(
                    "配置文件解析失败，已使用默认设置并保留原文件：{error}"
                )),
            ),
        };
        theme::set_mode(config.theme);
        theme::apply(&cc.egui_ctx);

        let (user_data, user_error) = match UserData::load(&paths.user_file) {
            Ok(data) => (data, None),
            Err(error) => (
                UserData::default(),
                Some(format!("收藏/历史读取失败，已使用空数据：{error}")),
            ),
        };

        let metadata_cache = MetadataCache::new(paths.cache_dir.join("meta"));
        let poster_cache = PosterCache::new(paths.cache_dir.join("poster"));
        let _ = metadata_cache.ensure_dir();
        let _ = poster_cache.ensure_dir();

        let mut config = config;
        let detected_vlc = config.vlc_path.trim().is_empty() && config.ensure_vlc_path();
        if detected_vlc && config_error.is_none() {
            let _ = config.save(&paths.config_file);
        }

        // 后台清理缓存，避免 2GB 机器被历史海报占满。
        {
            let metadata = metadata_cache.clone();
            let poster = poster_cache.clone();
            let _ = thread::Builder::new()
                .name("hm-cache-trim".to_owned())
                .spawn(move || {
                    metadata.trim();
                    poster.trim();
                });
        }

        let browse_epoch_shared = Arc::new(AtomicU64::new(0));
        let detail_epoch_shared = Arc::new(AtomicU64::new(0));
        let play_epoch_shared = Arc::new(AtomicU64::new(0));
        let poster_epoch_shared = Arc::new(AtomicU64::new(0));
        let (worker_tx, worker_rx, poster_txs) = spawn_workers(
            cc.egui_ctx.clone(),
            metadata_cache.clone(),
            poster_cache.clone(),
            browse_epoch_shared.clone(),
            detail_epoch_shared.clone(),
            play_epoch_shared.clone(),
            poster_epoch_shared.clone(),
        );

        let mut app = Self {
            config,
            config_path: paths.config_file.clone(),
            user_file: paths.user_file.clone(),
            cache_dir: paths.cache_dir.clone(),
            config_load_failed: config_error.is_some(),
            user_data,
            poster_cache,
            tab: MainTab::Home,
            category: CategoryState::default(),
            category_filters: None,
            top_period: TopPeriod::Day,
            daily_page: 1,
            mine_tab: MineTab::Favorites,
            search: SearchState::default(),
            search_active: false,
            search_text: String::new(),
            hero_index: 0,
            browse: BrowseState::default(),
            detail: None,
            play: None,
            selected_url: None,
            show_settings: false,
            settings_draft: None,
            show_search_history: false,
            confirm: None,
            status_text: "正在载入首页…".to_owned(),
            error_text: None,
            textures: HashMap::new(),
            poster_jobs: HashSet::new(),
            poster_failures: HashMap::new(),
            poster_requests_left: 0,
            poster_uploads_left: 0,
            deferred_posters: VecDeque::new(),
            frame_index: 0,
            poster_epoch: 0,
            browse_seq: 0,
            detail_seq: 0,
            play_seq: 0,
            browse_epoch_shared,
            detail_epoch_shared,
            play_epoch_shared,
            poster_epoch_shared,
            worker_tx,
            worker_rx,
            poster_txs,
            poster_next_worker: 0,
        };

        app.load_tab(MainTab::Home);
        if let Some(error) = config_error {
            app.set_error(error);
        } else if let Some(error) = user_error {
            app.set_error(error);
        }
        app
    }

    fn set_error(&mut self, message: impl Into<String>) {
        self.error_text = Some(message.into());
    }

    fn work_item_for_detail(view: &DetailView) -> WorkItem {
        if let Some(detail) = &view.detail {
            WorkItem {
                title: detail.title.clone(),
                url: detail.url.clone(),
                poster: detail.poster.clone(),
                category: detail.category.clone(),
                year: detail.year.clone(),
                area: detail.area.clone(),
                progress: None,
                score: None,
                rank: None,
            }
        } else {
            view.fallback.clone()
        }
    }

    fn kind_for_tab(&self, tab: MainTab) -> Option<BrowseKind> {
        match tab {
            MainTab::Home => Some(BrowseKind::Home),
            MainTab::Category => Some(BrowseKind::Category {
                cat: self.category.cat_slug.to_owned(),
                page: self.category.page.max(1),
                area: self.category.area.clone(),
                year: self.category.year.clone(),
                sort: self.category.sort.clone(),
            }),
            MainTab::Top => Some(BrowseKind::Top),
            MainTab::Daily => Some(BrowseKind::Daily),
            MainTab::Mine => None,
        }
    }

    fn close_detail_overlay(&mut self) {
        if self.detail.is_some() {
            self.detail_seq = self.detail_seq.wrapping_add(1);
            self.detail_epoch_shared
                .store(self.detail_seq, Ordering::SeqCst);
            self.detail = None;
        }
        if self.play.is_some() {
            self.play_seq = self.play_seq.wrapping_add(1);
            self.play_epoch_shared
                .store(self.play_seq, Ordering::SeqCst);
            self.play = None;
        }
        self.selected_url = None;
    }

    pub fn load_tab(&mut self, tab: MainTab) {
        self.close_detail_overlay();
        self.tab = tab;
        self.search_active = false;
        self.search_text.clear();
        self.show_search_history = false;
        self.error_text = None;
        if tab == MainTab::Mine {
            self.browse_seq = self.browse_seq.wrapping_add(1);
            self.browse_epoch_shared
                .store(self.browse_seq, Ordering::SeqCst);
            self.browse = BrowseState::default();
            self.status_text = format!(
                "收藏 {} 部 · 历史 {} 条",
                self.user_data.favorites.len(),
                self.user_data.history.len()
            );
            return;
        }
        if let Some(kind) = self.kind_for_tab(tab) {
            self.start_browse(kind, false);
        }
    }

    fn start_browse(&mut self, kind: BrowseKind, force: bool) {
        self.browse_seq = self.browse_seq.wrapping_add(1);
        let id = self.browse_seq;
        self.browse_epoch_shared.store(id, Ordering::SeqCst);
        self.browse = BrowseState {
            id,
            kind: Some(kind.clone()),
            data: None,
            loading: true,
            error: None,
            cached: false,
        };
        self.poster_epoch = self.poster_epoch.wrapping_add(1);
        self.poster_epoch_shared
            .store(self.poster_epoch, Ordering::SeqCst);
        self.poster_jobs.clear();
        self.poster_failures.clear();
        self.deferred_posters.clear();
        self.selected_url = None;
        self.error_text = None;
        self.status_text = match &kind {
            BrowseKind::Home => "正在载入首页…".to_owned(),
            BrowseKind::Category { page, .. } => format!("正在载入第 {page} 页…"),
            BrowseKind::Top => "正在载入榜单…".to_owned(),
            BrowseKind::Daily => "正在载入最近更新…".to_owned(),
            BrowseKind::Search { keyword, page, .. } => {
                format!("正在搜索「{keyword}」第 {page} 页…")
            }
        };
        let _ = self.worker_tx.send(Command::Browse {
            id,
            kind,
            force,
            source_base: self.config.source_base_trimmed(),
        });
    }

    fn reload_browse(&mut self, force: bool) {
        if self.search_active {
            let keyword = self.search.keyword.clone();
            if keyword.is_empty() {
                self.exit_search();
                return;
            }
            let kind = BrowseKind::Search {
                keyword,
                kind: self.search.kind,
                page: self.search.page,
            };
            self.start_browse(kind, force);
        } else if let Some(kind) = self.kind_for_tab(self.tab) {
            self.start_browse(kind, force);
        }
    }

    fn start_search(&mut self, keyword: String, kind: SearchKind, page: u32, force: bool) {
        let keyword = keyword.trim().to_owned();
        if keyword.is_empty() {
            self.exit_search();
            return;
        }
        self.close_detail_overlay();
        self.search_active = true;
        self.show_search_history = false;
        self.search.keyword = keyword.clone();
        self.search.kind = kind;
        self.search.page = page.max(1);
        self.start_browse(
            BrowseKind::Search {
                keyword: keyword.clone(),
                kind,
                page: page.max(1),
            },
            force,
        );
        self.user_data.record_search(&keyword);
        self.save_user_data();
    }

    fn exit_search(&mut self) {
        self.search_active = false;
        self.search.keyword.clear();
        self.search.page = 1;
        self.search_text.clear();
        self.load_tab(self.tab);
    }

    fn submit_search_text(&mut self) {
        let keyword = self.search_text.trim().to_owned();
        if keyword.is_empty() {
            self.exit_search();
        } else {
            self.start_search(keyword, SearchKind::All, 1, false);
        }
    }

    fn open_detail(&mut self, item: WorkItem) {
        if item.url.trim().is_empty() {
            self.set_error("这个条目没有可用的详情地址。");
            return;
        }
        self.detail_seq = self.detail_seq.wrapping_add(1);
        let id = self.detail_seq;
        self.detail_epoch_shared.store(id, Ordering::SeqCst);
        self.detail = Some(DetailView {
            id,
            fallback: item.clone(),
            loading: true,
            detail: None,
            error: None,
            active_line_id: None,
            description_expanded: false,
            cached: false,
        });
        self.play = None;
        self.selected_url = Some(item.url.clone());
        self.error_text = None;
        self.status_text = format!("正在读取《{}》详情…", item.title);
        let _ = self.worker_tx.send(Command::Detail {
            id,
            url: item.url,
            force: false,
            source_base: self.config.source_base_trimmed(),
        });
    }

    fn retry_detail(&mut self) {
        let Some(view) = self.detail.as_ref() else {
            return;
        };
        let url = view.fallback.url.clone();
        self.detail_seq = self.detail_seq.wrapping_add(1);
        let id = self.detail_seq;
        self.detail_epoch_shared.store(id, Ordering::SeqCst);
        if let Some(view) = self.detail.as_mut() {
            view.id = id;
            view.loading = true;
            view.error = None;
            view.cached = false;
        }
        self.error_text = None;
        self.status_text = "正在重新读取详情…".to_owned();
        let _ = self.worker_tx.send(Command::Detail {
            id,
            url,
            force: true,
            source_base: self.config.source_base_trimmed(),
        });
    }

    fn start_play(&mut self, work: WorkItem, line_name: String, episode: Episode) {
        self.play_seq = self.play_seq.wrapping_add(1);
        let id = self.play_seq;
        self.play_epoch_shared.store(id, Ordering::SeqCst);
        let episode_title = if episode.title.trim().is_empty() {
            format!("第{:02}集", episode.no)
        } else {
            episode.title.clone()
        };
        self.play = Some(PlayDialog {
            id,
            work: work.clone(),
            episode_url: episode.url.clone(),
            episode_title: episode_title.clone(),
            episode_no: episode.no,
            line_name,
            loading: true,
            info: None,
            error: None,
            probe: HashMap::new(),
            probing: false,
        });
        self.error_text = None;
        self.status_text = format!("正在解析《{}》{}…", work.title, episode_title);
        let _ = self.worker_tx.send(Command::PlayInfo {
            id,
            episode_url: episode.url,
            work_title: work.title,
            episode_title,
            episode_no: episode.no,
            poster: work.poster,
            force: false,
            source_base: self.config.source_base_trimmed(),
        });
    }

    fn play_first(&mut self) {
        let Some(view) = self.detail.as_ref() else {
            return;
        };
        let work = Self::work_item_for_detail(view);
        if let Some(history) = self.user_data.history_for(&work.url).cloned() {
            if !history.episode_url.trim().is_empty() {
                let episode = Episode {
                    no: history.episode_no,
                    title: history.episode_title.clone(),
                    url: history.episode_url.clone(),
                };
                let line_name = history
                    .line_name
                    .clone()
                    .unwrap_or_else(|| "上次线路".to_owned());
                self.start_play(work, line_name, episode);
                return;
            }
        }

        let selected = self.detail.as_ref().and_then(|view| {
            let detail = view.detail.as_ref()?;
            let line = detail
                .lines
                .iter()
                .find(|line| Some(line.id) == view.active_line_id && !line.episodes.is_empty())
                .or_else(|| detail.lines.iter().find(|line| !line.episodes.is_empty()))?;
            let episode = line.episodes.first()?.clone();
            Some((line.name.clone(), episode))
        });
        match selected {
            Some((line_name, episode)) => self.start_play(work, line_name, episode),
            None => {
                if self
                    .detail
                    .as_ref()
                    .is_some_and(|view| view.detail.is_none())
                {
                    self.set_error("详情仍在读取，请稍候。");
                } else {
                    self.set_error("这部作品暂无可播放剧集。");
                }
            }
        }
    }

    fn play_episode(&mut self, episode: Episode) {
        let Some(view) = self.detail.as_ref() else {
            return;
        };
        let work = Self::work_item_for_detail(view);
        let line_name = view
            .detail
            .as_ref()
            .and_then(|detail| {
                detail
                    .lines
                    .iter()
                    .find(|line| Some(line.id) == view.active_line_id)
                    .or_else(|| detail.lines.first())
            })
            .map(|line| line.name.clone())
            .unwrap_or_else(|| "线路".to_owned());
        self.start_play(work, line_name, episode);
    }

    fn retry_play(&mut self) {
        let Some((episode_url, work_title, episode_title, episode_no, poster)) =
            self.play.as_ref().map(|dialog| {
                (
                    dialog.episode_url.clone(),
                    dialog.work.title.clone(),
                    dialog.episode_title.clone(),
                    dialog.episode_no,
                    dialog.work.poster.clone(),
                )
            })
        else {
            return;
        };
        self.play_seq = self.play_seq.wrapping_add(1);
        let id = self.play_seq;
        self.play_epoch_shared.store(id, Ordering::SeqCst);
        let command = Command::PlayInfo {
            id,
            episode_url,
            work_title,
            episode_title,
            episode_no,
            poster,
            force: true,
            source_base: self.config.source_base_trimmed(),
        };
        if let Some(dialog) = self.play.as_mut() {
            dialog.id = id;
            dialog.loading = true;
            dialog.error = None;
            dialog.info = None;
            dialog.probe.clear();
            dialog.probing = false;
        }
        self.error_text = None;
        self.status_text = "正在重新解析播放线路…".to_owned();
        let _ = self.worker_tx.send(command);
    }

    fn probe_play_lines(&mut self) {
        let Some((id, lines, referer)) = self.play.as_ref().and_then(|dialog| {
            let info = dialog.info.as_ref()?;
            if info.lines.is_empty() || dialog.probing {
                return None;
            }
            let referer = info
                .service_url
                .clone()
                .unwrap_or_else(|| dialog.episode_url.clone());
            Some((dialog.id, info.lines.clone(), referer))
        }) else {
            return;
        };
        let command = Command::ProbeLines {
            id,
            lines,
            referer,
            source_base: self.config.source_base_trimmed(),
        };
        if let Some(dialog) = self.play.as_mut() {
            dialog.probing = true;
            dialog.probe.clear();
        }
        self.status_text = "正在测速线路…".to_owned();
        let _ = self.worker_tx.send(command);
    }

    fn close_play(&mut self) {
        self.play = None;
        self.play_seq = self.play_seq.wrapping_add(1);
        self.play_epoch_shared
            .store(self.play_seq, Ordering::SeqCst);
    }

    fn launch_line(&mut self, index: usize) {
        let Some(dialog) = self.play.as_ref() else {
            return;
        };
        let Some(info) = dialog.info.as_ref() else {
            return;
        };
        let Some(line) = info.lines.get(index) else {
            return;
        };
        let line = line.clone();
        let referer = info.service_url.clone();
        let media_title = {
            let work = if info.work_title.trim().is_empty() {
                &dialog.work.title
            } else {
                &info.work_title
            };
            let episode = if info.episode_title.trim().is_empty() {
                &dialog.episode_title
            } else {
                &info.episode_title
            };
            format!("{work} · {episode}")
        };
        let work = dialog.work.clone();
        let episode_url = dialog.episode_url.clone();
        let episode_title = if info.episode_title.trim().is_empty() {
            dialog.episode_title.clone()
        } else {
            info.episode_title.clone()
        };
        let episode_no = if info.episode_no == 0 {
            dialog.episode_no
        } else {
            info.episode_no
        };

        if self.config.vlc_path_trimmed().is_empty() {
            self.config.ensure_vlc_path();
            if !self.config.vlc_path_trimmed().is_empty() && !self.config_load_failed {
                let _ = self.config.save(&self.config_path);
            }
        }
        if self.config.vlc_path_trimmed().is_empty() {
            if let Some(dialog) = self.play.as_mut() {
                dialog.error = Some(
                    "还没有配置 VLC。请点右上角「设置」填写 vlc.exe 路径，或点「自动检测」。"
                        .to_owned(),
                );
            }
            return;
        }

        let vlc_path = self.config.vlc_path.clone();
        match player::launch_vlc(&vlc_path, &line.url, &media_title, referer.as_deref()) {
            Ok(()) => {
                let history = HistoryItem {
                    url: work.url.clone(),
                    title: work.title.clone(),
                    poster: work.poster.clone(),
                    category: work.category.clone(),
                    year: work.year.clone(),
                    area: work.area.clone(),
                    line_name: Some(line.name.clone()),
                    episode_no,
                    episode_title: episode_title.clone(),
                    episode_url,
                    played_at: now_unix(),
                };
                self.error_text = None;
                self.user_data.record_history(history);
                self.save_user_data();
                self.close_play();
                self.status_text = format!("已用 VLC 打开《{} · {}》", work.title, episode_title);
            }
            Err(error) => {
                if let Some(dialog) = self.play.as_mut() {
                    dialog.error = Some(format!("{error}"));
                }
            }
        }
    }

    fn toggle_favorite_current(&mut self) {
        let Some(view) = self.detail.as_ref() else {
            return;
        };
        let work = Self::work_item_for_detail(view);
        let item = FavoriteItem {
            url: work.url,
            title: work.title,
            poster: work.poster,
            category: work.category,
            year: work.year,
            area: work.area,
            added_at: now_unix(),
        };
        let title = item.title.clone();
        let added = self.user_data.toggle_favorite(item);
        self.error_text = None;
        self.save_user_data();
        self.status_text = if added {
            format!("已收藏《{title}》")
        } else {
            format!("已取消收藏《{title}》")
        };
    }

    fn remove_history(&mut self, url: &str) {
        self.user_data.remove_history(url);
        self.save_user_data();
        self.status_text = "已删除该条观看记录".to_owned();
    }

    fn remove_favorite(&mut self, url: &str) {
        self.user_data.remove_favorite(url);
        self.save_user_data();
        self.status_text = "已取消收藏".to_owned();
    }

    fn clear_search_history(&mut self) {
        self.user_data.clear_search_history();
        self.save_user_data();
        self.status_text = "已清空搜索历史".to_owned();
    }

    fn confirm_action(&mut self) {
        let Some(action) = self.confirm.take() else {
            return;
        };
        match action {
            ConfirmAction::ClearHistory => {
                self.user_data.clear_history();
                self.save_user_data();
                self.status_text = "已清空观看历史".to_owned();
            }
            ConfirmAction::ClearFavorites => {
                self.user_data.clear_favorites();
                self.save_user_data();
                self.status_text = "已清空收藏".to_owned();
            }
        }
    }

    fn save_user_data(&mut self) {
        if let Err(error) = self.user_data.save(&self.user_file) {
            self.set_error(format!("保存收藏/历史失败：{error}"));
        }
    }

    fn save_config(&mut self, show_success: bool) {
        match self.config.save(&self.config_path) {
            Ok(()) => {
                self.config_load_failed = false;
                self.error_text = None;
                if show_success {
                    self.status_text = "设置已保存".to_owned();
                }
            }
            Err(error) => self.set_error(format!("保存设置失败：{error}")),
        }
    }

    fn save_config_silently(&mut self) {
        if self.config_load_failed {
            return;
        }
        self.save_config(false);
    }

    fn open_settings(&mut self) {
        if self.settings_draft.is_none() {
            self.settings_draft = Some(SettingsDraft::from_config(&self.config));
        }
        self.show_settings = true;
        self.show_search_history = false;
    }

    fn apply_settings(&mut self, draft: SettingsDraft, ctx: &egui::Context) {
        let previous_base = self.config.source_base_trimmed();
        let theme_mode = draft.theme;
        let base_changed = draft.apply(&mut self.config);
        if self.config.vlc_path_trimmed().is_empty() {
            self.config.ensure_vlc_path();
        }
        theme::set_mode(theme_mode);
        theme::apply(ctx);
        self.save_config(true);
        self.settings_draft = None;
        self.show_settings = false;

        if base_changed {
            let tab = self.tab;
            self.invalidate_all(true);
            self.load_tab(tab);
            self.status_text = "已切换播放源并重新载入当前页面".to_owned();
        } else {
            self.status_text = format!(
                "设置已保存（{}）",
                if previous_base == self.config.source_base_trimmed() {
                    "播放源未变"
                } else {
                    "播放源已变"
                }
            );
        }
    }

    /// 使当前列表、详情、播放解析全部作废；连接源改变时连纹理一起清空。
    fn invalidate_all(&mut self, clear_textures: bool) {
        self.browse_seq = self.browse_seq.wrapping_add(1);
        self.browse_epoch_shared
            .store(self.browse_seq, Ordering::SeqCst);
        self.detail_seq = self.detail_seq.wrapping_add(1);
        self.detail_epoch_shared
            .store(self.detail_seq, Ordering::SeqCst);
        self.play_seq = self.play_seq.wrapping_add(1);
        self.play_epoch_shared
            .store(self.play_seq, Ordering::SeqCst);
        self.poster_epoch = self.poster_epoch.wrapping_add(1);
        self.poster_epoch_shared
            .store(self.poster_epoch, Ordering::SeqCst);
        self.browse = BrowseState::default();
        self.detail = None;
        self.play = None;
        self.search_active = false;
        self.search.keyword.clear();
        self.category_filters = None;
        self.poster_jobs.clear();
        self.poster_failures.clear();
        self.deferred_posters.clear();
        if clear_textures {
            self.textures.clear();
        }
    }

    fn process_events(&mut self, ctx: &egui::Context) {
        self.drain_deferred_posters(ctx);
        let mut processed = 0;
        while processed < MAX_EVENTS_PER_FRAME {
            let Ok(event) = self.worker_rx.try_recv() else {
                break;
            };
            processed += 1;
            match event {
                Event::Browse {
                    id,
                    kind,
                    result,
                    cached,
                } => {
                    if id != self.browse.id {
                        continue;
                    }
                    self.browse.loading = false;
                    self.browse.cached = cached;
                    match result {
                        Ok(data) => {
                            self.error_text = None;
                            self.browse.error = None;
                            self.browse.data = Some(data.clone());
                            if matches!(kind, BrowseKind::Category { .. }) {
                                if let BrowseData::Page(page) = &data {
                                    if let Some(filters) = &page.filters {
                                        self.category_filters = Some(filters.clone());
                                    }
                                }
                            }
                            match &data {
                                BrowseData::Home(home) => {
                                    if home.hero.is_empty() || self.hero_index >= home.hero.len() {
                                        self.hero_index = 0;
                                    }
                                    self.status_text = if cached {
                                        format!(
                                            "已从本地缓存载入首页（{} 部推荐）",
                                            home.recent.len()
                                        )
                                    } else {
                                        format!("首页已更新（{} 部推荐）", home.recent.len())
                                    };
                                }
                                BrowseData::Page(page) => {
                                    let suffix = if cached { " · 本地缓存" } else { "" };
                                    self.status_text = format!(
                                        "第 {}/{} 页 · {} 部{}",
                                        page.page,
                                        page.total_pages.max(1),
                                        page.total_items.unwrap_or(page.items.len() as u32),
                                        suffix
                                    );
                                }
                                BrowseData::Top(boards) => {
                                    let count = match self.top_period {
                                        TopPeriod::Day => boards.day.len(),
                                        TopPeriod::Week => boards.week.len(),
                                        TopPeriod::Month => boards.month.len(),
                                    };
                                    self.status_text = if cached {
                                        format!("榜单已从缓存载入（{} 部）", count)
                                    } else {
                                        format!("榜单已更新（{} 部）", count)
                                    };
                                }
                                BrowseData::Daily(items) => {
                                    self.daily_page = 1;
                                    self.status_text = if cached {
                                        format!("更新列表已从缓存载入（{} 部）", items.len())
                                    } else {
                                        format!("更新列表已更新（{} 部）", items.len())
                                    };
                                }
                            }
                        }
                        Err(error) => {
                            self.browse.error = Some(error.clone());
                            self.set_error(error);
                            self.status_text = "加载失败，请重试".to_owned();
                        }
                    }
                }
                Event::Detail {
                    id,
                    url,
                    result,
                    cached,
                } => {
                    let outcome = {
                        let Some(view) = self.detail.as_mut() else {
                            continue;
                        };
                        if id != view.id {
                            continue;
                        }
                        view.loading = false;
                        view.cached = cached;
                        match result {
                            Ok(detail) => {
                                view.error = None;
                                view.active_line_id = detail
                                    .lines
                                    .iter()
                                    .find(|line| !line.episodes.is_empty())
                                    .map(|line| line.id)
                                    .or_else(|| detail.lines.first().map(|line| line.id));
                                view.description_expanded = false;
                                let title = detail.title.clone();
                                let count = detail.lines.len();
                                view.detail = Some(detail);
                                let _ = url;
                                Ok(if cached {
                                    format!("《{title}》详情已从缓存载入（{count} 条线路）")
                                } else {
                                    format!("《{title}》详情已载入（{count} 条线路）")
                                })
                            }
                            Err(error) => {
                                view.error = Some(error.clone());
                                Err(error)
                            }
                        }
                    };
                    match outcome {
                        Ok(status) => {
                            self.error_text = None;
                            self.status_text = status;
                        }
                        Err(error) => {
                            self.set_error(error);
                            self.status_text = "详情读取失败".to_owned();
                        }
                    }
                }
                Event::PlayInfo {
                    id,
                    episode_url,
                    result,
                } => {
                    let outcome = {
                        let Some(dialog) = self.play.as_mut() else {
                            continue;
                        };
                        if id != dialog.id || episode_url != dialog.episode_url {
                            continue;
                        }
                        dialog.loading = false;
                        match result {
                            Ok(info) => {
                                dialog.probe.clear();
                                dialog.probing = false;
                                if info.lines.is_empty() {
                                    dialog.error =
                                        Some("没有找到可播放线路，请稍后重试。".to_owned());
                                    Ok("没有找到可播放线路".to_owned())
                                } else {
                                    dialog.error = None;
                                    let count = info.lines.len();
                                    dialog.info = Some(info);
                                    Ok(format!("已解析 {count} 条线路"))
                                }
                            }
                            Err(error) => {
                                dialog.error = Some(error.clone());
                                Err(error)
                            }
                        }
                    };
                    match outcome {
                        Ok(status) => {
                            self.error_text = None;
                            self.status_text = status;
                        }
                        Err(error) => {
                            self.set_error(error);
                            self.status_text = "播放源解析失败".to_owned();
                        }
                    }
                }
                Event::LinesProbed { id, result } => {
                    let status = {
                        let Some(dialog) = self.play.as_mut() else {
                            continue;
                        };
                        if id != dialog.id {
                            continue;
                        }
                        dialog.probing = false;
                        dialog.probe = result
                            .iter()
                            .map(|item| (item.index, item.clone()))
                            .collect();
                        if let Some(best) = result.first() {
                            format!(
                                "测速完成，推荐第 {} 条线路（{} ms）",
                                best.index + 1,
                                best.elapsed_ms
                            )
                        } else {
                            "测速未取得有效结果，可直接尝试线路".to_owned()
                        }
                    };
                    self.status_text = status;
                }
                Event::PosterReady { epoch, key, poster } => {
                    if epoch != self.poster_epoch {
                        continue;
                    }
                    if self.poster_uploads_left == 0 {
                        self.deferred_posters
                            .push_back(DeferredPoster { epoch, key, poster });
                    } else {
                        self.poster_uploads_left -= 1;
                        self.install_poster_texture(ctx, key.clone(), poster);
                        self.poster_jobs.remove(&key);
                    }
                }
                Event::PosterFailed { epoch, key, .. } => {
                    if epoch != self.poster_epoch {
                        continue;
                    }
                    self.poster_jobs.remove(&key);
                    self.record_poster_failure(&key);
                }
            }
        }
        self.drain_deferred_posters(ctx);
        if processed == MAX_EVENTS_PER_FRAME || !self.deferred_posters.is_empty() {
            ctx.request_repaint();
        }
    }

    fn drain_deferred_posters(&mut self, ctx: &egui::Context) {
        while self.poster_uploads_left > 0 {
            let Some(poster) = self.deferred_posters.pop_front() else {
                break;
            };
            if poster.epoch != self.poster_epoch {
                continue;
            }
            self.poster_uploads_left -= 1;
            let key = poster.key;
            self.install_poster_texture(ctx, key.clone(), poster.poster);
            self.poster_jobs.remove(&key);
        }
    }

    fn install_poster_texture(&mut self, ctx: &egui::Context, key: String, poster: DecodedPoster) {
        if poster.size[0] == 0 || poster.size[1] == 0 {
            self.record_poster_failure(&key);
            return;
        }
        let image = ColorImage::from_rgba_unmultiplied(poster.size, &poster.pixels);
        let handle = ctx.load_texture(key.clone(), image, TextureOptions::LINEAR);
        let size = poster.size;
        let bytes = size[0].saturating_mul(size[1]).saturating_mul(4);
        self.textures.insert(
            key.clone(),
            PosterTexture {
                handle,
                last_used_frame: self.frame_index,
                bytes,
                size,
            },
        );
        self.poster_failures.remove(&key);
    }

    fn texture_for(&self, poster_url: &str) -> Option<&TextureHandle> {
        self.textures.get(poster_url).map(|texture| &texture.handle)
    }

    fn texture_size(&self, poster_url: &str) -> Option<[usize; 2]> {
        self.textures.get(poster_url).map(|texture| texture.size)
    }

    fn touch_texture(&mut self, key: &str) {
        if let Some(texture) = self.textures.get_mut(key) {
            texture.last_used_frame = self.frame_index;
        }
    }

    fn enforce_texture_budget(&mut self, protected: &HashSet<String>) {
        let mut total: usize = self.textures.values().map(|texture| texture.bytes).sum();
        if total <= TEXTURE_BUDGET_BYTES && self.textures.len() <= TEXTURE_MAX_ENTRIES {
            return;
        }
        let mut candidates: Vec<(String, u64)> = self
            .textures
            .iter()
            .filter(|(key, _)| !protected.contains(*key))
            .map(|(key, texture)| (key.clone(), texture.last_used_frame))
            .collect();
        candidates.sort_by_key(|(_, last_used)| *last_used);
        for (key, _) in candidates {
            if total <= TEXTURE_BUDGET_BYTES && self.textures.len() <= TEXTURE_MAX_ENTRIES {
                break;
            }
            if let Some(texture) = self.textures.remove(&key) {
                total = total.saturating_sub(texture.bytes);
            }
        }
    }

    fn ensure_poster_texture(&mut self, ctx: &egui::Context, poster_url: &str) {
        let poster_url = poster_url.trim();
        if poster_url.is_empty()
            || self.textures.contains_key(poster_url)
            || self.poster_jobs.contains(poster_url)
        {
            return;
        }
        if let Some(failure) = self.poster_failures.get(poster_url) {
            let now = Instant::now();
            if now < failure.next_retry {
                ctx.request_repaint_after(failure.next_retry - now);
                return;
            }
        }
        if self.poster_requests_left == 0 {
            ctx.request_repaint();
            return;
        }

        let path = self.poster_cache.poster_path(poster_url);
        let command = if self.poster_cache.has(&path) {
            PosterCommand::Decode {
                epoch: self.poster_epoch,
                key: poster_url.to_owned(),
                path,
            }
        } else {
            PosterCommand::Fetch {
                epoch: self.poster_epoch,
                key: poster_url.to_owned(),
                url: poster_url.to_owned(),
                path,
                source_base: self.config.source_base_trimmed(),
            }
        };
        self.poster_requests_left -= 1;
        self.poster_jobs.insert(poster_url.to_owned());
        if !self.enqueue_poster(command, poster_url) {
            ctx.request_repaint_after(Duration::from_millis(200));
        }
    }

    fn enqueue_poster(&mut self, command: PosterCommand, key: &str) -> bool {
        if self.poster_txs.is_empty() {
            self.poster_jobs.remove(key);
            return false;
        }
        let count = self.poster_txs.len();
        for offset in 0..count {
            let index = (self.poster_next_worker + offset) % count;
            match self.poster_txs[index].try_send(command.clone()) {
                Ok(()) => {
                    self.poster_next_worker = (index + 1) % count;
                    return true;
                }
                Err(TrySendError::Full(_)) => continue,
                Err(TrySendError::Disconnected(_)) => break,
            }
        }
        self.poster_jobs.remove(key);
        false
    }

    fn record_poster_failure(&mut self, key: &str) {
        let attempts = self
            .poster_failures
            .get(key)
            .map(|failure| failure.attempts.saturating_add(1))
            .unwrap_or(1);
        let delay = poster_retry_delay(attempts);
        self.poster_failures.insert(
            key.to_owned(),
            PosterFailure {
                attempts,
                next_retry: Instant::now() + delay,
            },
        );
    }
}

impl eframe::App for App {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        self.frame_index = self.frame_index.wrapping_add(1);
        self.poster_requests_left = MAX_POSTER_REQUESTS_PER_FRAME as u8;
        self.poster_uploads_left = MAX_TEXTURE_UPLOADS_PER_FRAME as u8;
        self.process_events(ctx);
        self.draw_ui(ctx);
    }
}

fn poster_retry_delay(attempts: u32) -> Duration {
    let multiplier = 1u64 << attempts.min(8);
    let millis = POSTER_RETRY_BASE
        .as_millis()
        .saturating_mul(multiplier as u128)
        .min(POSTER_RETRY_MAX.as_millis());
    Duration::from_millis(millis as u64)
}
