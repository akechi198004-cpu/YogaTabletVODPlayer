//! 主应用状态与后台事件处理。

use std::collections::{HashMap, HashSet, VecDeque};
use std::fs;
use std::path::PathBuf;
use std::process::Child;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::mpsc::{Receiver, Sender, SyncSender, TrySendError};
use std::sync::Arc;
use std::thread;
use std::time::{Duration, Instant};

use eframe::egui::{self, ColorImage, TextureHandle, TextureOptions};

use crate::cache::{DecodedPoster, MetadataCache, PosterCache};
use crate::config::{now_unix, AppConfig, PlayerBackend, ThemeMode, UserData};
use crate::embedded::{
    EmbeddedEvent, EmbeddedPlayer, EndReason, LoadOptions, PlayerProperty, VideoWindow,
};
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

#[cfg(windows)]
fn window_handle(cc: &eframe::CreationContext<'_>) -> Option<isize> {
    use raw_window_handle::{HasWindowHandle, RawWindowHandle};
    match cc.window_handle().ok()?.as_raw() {
        RawWindowHandle::Win32(handle) => Some(handle.hwnd.get()),
        _ => None,
    }
}

#[cfg(not(windows))]
fn window_handle(_cc: &eframe::CreationContext<'_>) -> Option<isize> {
    None
}

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
    pub player_backend: PlayerBackend,
    pub vlc_path: String,
    pub theme: ThemeMode,
}

impl SettingsDraft {
    fn from_config(config: &AppConfig) -> Self {
        Self {
            source_base: config.source_base.clone(),
            player_backend: config.player_backend,
            vlc_path: config.vlc_path.clone(),
            theme: config.theme,
        }
    }

    /// 返回连接源是否变化。
    fn apply(self, config: &mut AppConfig) -> bool {
        let old_base = config.source_base_trimmed();
        config.source_base = self.source_base.trim().trim_end_matches('/').to_owned();
        if config.source_base.is_empty() {
            config.source_base = crate::config::DEFAULT_SOURCE_BASE.to_owned();
        }
        config.player_backend = self.player_backend;
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

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum PlayerStatus {
    Opening,
    Playing,
    Paused,
    Ended,
    Failed,
}

/// 内置播放会话。`session` 与 mpv 的 load_id 一致，旧会话事件直接丢弃。
#[derive(Clone)]
struct PlayerView {
    session: u64,
    work: WorkItem,
    episode_url: String,
    episode_title: String,
    episode_no: u32,
    line_name: String,
    line_index: usize,
    info: PlayInfo,
    media_url: String,
    media_title: String,
    referer: Option<String>,
    status: PlayerStatus,
    error: Option<String>,
    duration: Option<f64>,
    position: f64,
    seek_target: Option<f64>,
    volume: u8,
    started_at: Instant,
}

#[derive(Clone, Debug, PartialEq, Eq)]
enum PlayerEventOutcome {
    Ignored,
    Updated,
    Loading,
    Playing,
    Ended,
    Failed(String),
}

impl PlayerView {
    fn valid_duration(&self) -> Option<f64> {
        self.duration
            .filter(|value| value.is_finite() && *value > 0.0)
    }

    fn clear_seek_target(&mut self) {
        self.seek_target = None;
    }

    fn preview_seek_fraction(&mut self, fraction: f64) {
        if let Some(duration) = self.valid_duration() {
            self.seek_target = Some(fraction.clamp(0.0, 1.0) * duration);
        } else {
            self.seek_target = None;
        }
    }

    /// 结束拖动并返回绝对秒数；无论是否有效都会清理 seek_target。
    fn finish_seek_fraction(&mut self, fraction: f64) -> Option<f64> {
        let target = self
            .valid_duration()
            .map(|duration| fraction.clamp(0.0, 1.0) * duration);
        self.seek_target = None;
        target
    }

    fn apply_property(&mut self, property: PlayerProperty) {
        match property {
            PlayerProperty::TimePos(value) => {
                if value.is_finite() && value >= 0.0 && self.seek_target.is_none() {
                    self.position = value;
                }
            }
            PlayerProperty::Duration(value) => {
                self.duration = value.filter(|value| value.is_finite() && *value > 0.0);
                if self.duration.is_none() {
                    // 时长未知/无效时不能保留拖动预览，否则后续 time-pos 会被永久遮蔽。
                    self.seek_target = None;
                }
            }
            PlayerProperty::Pause(paused) => {
                if paused && matches!(self.status, PlayerStatus::Opening | PlayerStatus::Playing) {
                    self.status = PlayerStatus::Paused;
                } else if !paused && self.status == PlayerStatus::Paused {
                    self.status = PlayerStatus::Playing;
                }
            }
            PlayerProperty::Volume(value) => {
                if value.is_finite() {
                    self.volume = value.round().clamp(0.0, 100.0) as u8;
                }
            }
        }
    }

    fn apply_event(&mut self, event: EmbeddedEvent) -> PlayerEventOutcome {
        let load_id = match &event {
            EmbeddedEvent::Loading { load_id }
            | EmbeddedEvent::Playing { load_id }
            | EmbeddedEvent::Ended { load_id, .. }
            | EmbeddedEvent::ProcessExited { load_id, .. }
            | EmbeddedEvent::Error { load_id, .. }
            | EmbeddedEvent::Property { load_id, .. } => *load_id,
        };
        if load_id != self.session {
            return PlayerEventOutcome::Ignored;
        }
        match event {
            EmbeddedEvent::Loading { .. } => {
                if self.status == PlayerStatus::Opening {
                    PlayerEventOutcome::Loading
                } else {
                    PlayerEventOutcome::Ignored
                }
            }
            EmbeddedEvent::Playing { .. } => {
                if self.status == PlayerStatus::Opening {
                    self.status = PlayerStatus::Playing;
                    PlayerEventOutcome::Playing
                } else {
                    PlayerEventOutcome::Ignored
                }
            }
            EmbeddedEvent::Property { property, .. } => {
                self.apply_property(property);
                PlayerEventOutcome::Updated
            }
            EmbeddedEvent::Ended { reason, error, .. } => match reason {
                EndReason::Eof => PlayerEventOutcome::Ended,
                EndReason::Error => PlayerEventOutcome::Failed(format!(
                    "播放器无法继续解码该视频{}。",
                    error.map(|error| format!("：{error}")).unwrap_or_default()
                )),
                EndReason::Stop | EndReason::Quit => {
                    if self.status == PlayerStatus::Ended {
                        PlayerEventOutcome::Ignored
                    } else {
                        PlayerEventOutcome::Failed(
                            "播放器已停止，线路可能不可用或媒体已结束。".to_owned(),
                        )
                    }
                }
                EndReason::Redirect => PlayerEventOutcome::Ignored,
            },
            EmbeddedEvent::ProcessExited { code, .. } => {
                if self.status == PlayerStatus::Ended {
                    PlayerEventOutcome::Ignored
                } else {
                    PlayerEventOutcome::Failed(format!(
                        "内置播放器进程意外退出（退出码 {}）。",
                        code.map(|code| code.to_string())
                            .unwrap_or_else(|| "未知".to_owned())
                    ))
                }
            }
            EmbeddedEvent::Error { message, .. } => {
                PlayerEventOutcome::Failed(format!("内置播放器错误：{message}"))
            }
        }
    }

    fn mark_ended(&mut self) {
        self.status = PlayerStatus::Ended;
        self.seek_target = None;
        if let Some(duration) = self.valid_duration() {
            self.position = duration;
        }
    }

    fn mark_failed(&mut self, error: String) {
        self.status = PlayerStatus::Failed;
        self.error = Some(error);
        self.seek_target = None;
    }
}

#[derive(Clone)]
struct PlaySelection {
    work: WorkItem,
    episode_url: String,
    episode_title: String,
    episode_no: u32,
    line: crate::models::PlayLine,
    line_index: usize,
    info: PlayInfo,
    referer: Option<String>,
    media_title: String,
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
    user_data_load_failed: bool,
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
    player_view: Option<PlayerView>,
    embedded: Option<EmbeddedPlayer>,
    external_vlc: Option<Child>,
    video_window: VideoWindow,
    window_hwnd: Option<isize>,
    player_seq: u64,
    window_fullscreen: bool,
    settings_notice: Option<(String, bool)>,
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

        let (user_data, user_data_load_failed, user_error) =
            match UserData::load_optional(&paths.user_file) {
                Ok(Some(data)) => (data, false, None),
                Ok(None) => (UserData::default(), false, None),
                Err(error) => (
                    UserData::default(),
                    true,
                    Some(format!(
                        "收藏/历史文件读取失败，原文件已保留，自动保存已暂停：{error}"
                    )),
                ),
            };

        let metadata_cache = MetadataCache::new(paths.cache_dir.join("meta"));
        let poster_cache = PosterCache::new(paths.cache_dir.join("poster"));
        let _ = metadata_cache.ensure_dir();
        let _ = poster_cache.ensure_dir();

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
            user_data_load_failed,
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
            player_view: None,
            embedded: None,
            external_vlc: None,
            video_window: VideoWindow::new(),
            window_hwnd: window_handle(cc),
            player_seq: 0,
            window_fullscreen: false,
            settings_notice: None,
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

    fn play_selection(&self, index: usize) -> Option<PlaySelection> {
        let dialog = self.play.as_ref()?;
        let info = dialog.info.as_ref()?;
        let line = info.lines.get(index)?.clone();
        if line.url.trim().is_empty() {
            return None;
        }
        let referer = info.service_url.clone();
        let work = dialog.work.clone();
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
        let title = if info.work_title.trim().is_empty() {
            work.title.clone()
        } else {
            info.work_title.clone()
        };
        Some(PlaySelection {
            work,
            episode_url: dialog.episode_url.clone(),
            episode_title: episode_title.clone(),
            episode_no,
            line,
            line_index: index,
            info: info.clone(),
            referer,
            media_title: format!("{title} · {episode_title}"),
        })
    }

    fn launch_line(&mut self, index: usize) {
        let Some(selection) = self.play_selection(index) else {
            if let Some(dialog) = self.play.as_mut() {
                dialog.error = Some("这条线路没有可播放的媒体地址。".to_owned());
            }
            return;
        };
        match self.config.player_backend {
            PlayerBackend::Embedded => self.launch_embedded(selection),
            PlayerBackend::ExternalVlc => self.launch_external(selection),
        }
    }

    fn ensure_embedded_player(&mut self) -> Result<(), String> {
        if self.embedded.is_some() {
            return Ok(());
        }
        let Some(hwnd) = self.window_hwnd else {
            return Err("无法取得应用主窗口句柄，内置播放不可用。".to_owned());
        };
        self.video_window
            .ensure(hwnd)
            .map_err(|error| format!("创建视频窗口失败：{error}"))?;
        let player = EmbeddedPlayer::new().map_err(|error| error.to_string())?;
        player.set_video_window(self.video_window.hwnd());
        player.set_volume(self.config.embedded_volume);
        self.embedded = Some(player);
        Ok(())
    }

    fn player_view_from_selection(
        &self,
        selection: PlaySelection,
        session: u64,
        status: PlayerStatus,
    ) -> PlayerView {
        PlayerView {
            session,
            work: selection.work,
            episode_url: selection.episode_url,
            episode_title: selection.episode_title,
            episode_no: selection.episode_no,
            line_name: selection.line.name,
            line_index: selection.line_index,
            info: selection.info,
            media_url: selection.line.url,
            media_title: selection.media_title,
            referer: selection.referer,
            status,
            error: None,
            duration: None,
            position: 0.0,
            seek_target: None,
            volume: self.config.embedded_volume,
            started_at: Instant::now(),
        }
    }

    fn launch_embedded(&mut self, selection: PlaySelection) {
        let session = self.next_player_session();
        self.stop_external_vlc();
        self.video_window.hide();
        if let Err(error) = self.ensure_embedded_player() {
            self.show_embedded_failure(selection, session, error);
            return;
        }
        let referer = selection.referer.clone().unwrap_or_default();
        if let Some(player) = &self.embedded {
            player.set_volume(self.config.embedded_volume);
            player.load(
                session,
                LoadOptions {
                    url: selection.line.url.clone(),
                    user_agent: crate::source::USER_AGENT.to_owned(),
                    referer,
                    disable_hw_decode: false,
                },
            );
        }
        self.save_play_history(&selection);
        let title = selection.work.title.clone();
        let episode = selection.episode_title.clone();
        self.player_view =
            Some(self.player_view_from_selection(selection, session, PlayerStatus::Opening));
        self.play = None;
        self.show_search_history = false;
        self.error_text = None;
        self.status_text = format!("内置播放器正在连接《{title} · {episode}》…");
    }

    fn show_embedded_failure(&mut self, selection: PlaySelection, session: u64, error: String) {
        if let Some(player) = &self.embedded {
            player.stop();
        }
        self.video_window.hide();
        let mut view = self.player_view_from_selection(selection, session, PlayerStatus::Failed);
        view.error = Some(format!(
            "{error}\n\n不会自动启动外部 VLC；可点击下方「改用 VLC 播放」，或到设置中切换播放方式。"
        ));
        self.player_view = Some(view);
        self.play = None;
        self.show_search_history = false;
        self.error_text = None;
        self.status_text = "内置播放器不可用".to_owned();
    }

    fn launch_external(&mut self, selection: PlaySelection) {
        self.stop_external_vlc();
        match self.launch_vlc_for(&selection) {
            Ok(child) => {
                self.external_vlc = Some(child);
                self.save_play_history(&selection);
                let title = selection.work.title.clone();
                let episode = selection.episode_title.clone();
                self.close_play();
                self.error_text = None;
                self.status_text = format!("已用 VLC 打开《{title} · {episode}》");
            }
            Err(error) => {
                if let Some(dialog) = self.play.as_mut() {
                    dialog.error = Some(error);
                }
            }
        }
    }

    fn launch_vlc_for(&mut self, selection: &PlaySelection) -> Result<Child, String> {
        if self.config.vlc_path_trimmed().is_empty() && self.config.ensure_vlc_path() {
            self.save_config_silently();
        }
        if self.config.vlc_path_trimmed().is_empty() {
            return Err(
                "未找到 VLC。请到设置中点击「选择文件」或「选择安装目录」定位 vlc.exe。".to_owned(),
            );
        }
        let vlc_path = self.config.vlc_path.clone();
        if let Err(error) = player::validate_vlc_path(&vlc_path) {
            return Err(format!(
                "{error}。请到设置中重新选择 vlc.exe 或 VLC 安装目录。"
            ));
        }
        player::launch_vlc(
            &vlc_path,
            &selection.line.url,
            &selection.media_title,
            selection.referer.as_deref(),
        )
        .map_err(|error| format!("{error}"))
    }

    fn save_play_history(&mut self, selection: &PlaySelection) {
        let history = HistoryItem {
            url: selection.work.url.clone(),
            title: selection.work.title.clone(),
            poster: selection.work.poster.clone(),
            category: selection.work.category.clone(),
            year: selection.work.year.clone(),
            area: selection.work.area.clone(),
            line_name: Some(selection.line.name.clone()),
            episode_no: selection.episode_no,
            episode_title: selection.episode_title.clone(),
            episode_url: selection.episode_url.clone(),
            played_at: now_unix(),
        };
        self.user_data.record_history(history);
        self.save_user_data();
    }

    fn next_player_session(&mut self) -> u64 {
        self.player_seq = self.player_seq.wrapping_add(1);
        self.player_seq
    }

    fn stop_external_vlc(&mut self) {
        if let Some(mut child) = self.external_vlc.take() {
            let _ = child.kill();
            let _ = thread::Builder::new()
                .name("hm-vlc-reaper".to_owned())
                .spawn(move || {
                    let _ = child.wait();
                });
        }
    }

    fn restart_player_line(&mut self) {
        let Some(view) = self.player_view.as_ref() else {
            return;
        };
        if view.info.lines.len() < 2 {
            return;
        }
        let mut next = view.line_index;
        let mut chosen = None;
        for _ in 0..view.info.lines.len() {
            next = (next + 1) % view.info.lines.len();
            let line = &view.info.lines[next];
            if !line.url.trim().is_empty() {
                chosen = Some(line.clone());
                break;
            }
        }
        let Some(line) = chosen else {
            if let Some(view) = self.player_view.as_mut() {
                view.error = Some("所有线路都没有可播放的媒体地址。".to_owned());
            }
            return;
        };
        let selection = PlaySelection {
            work: view.work.clone(),
            episode_url: view.episode_url.clone(),
            episode_title: view.episode_title.clone(),
            episode_no: view.episode_no,
            line,
            line_index: next,
            info: view.info.clone(),
            referer: view.referer.clone(),
            media_title: view.media_title.clone(),
        };
        if self.embedded.is_none() {
            self.launch_embedded(selection);
            return;
        }
        let session = self.next_player_session();
        let referer = selection.referer.clone().unwrap_or_default();
        self.video_window.hide();
        if let Some(player) = &self.embedded {
            player.load(
                session,
                LoadOptions {
                    url: selection.line.url.clone(),
                    user_agent: crate::source::USER_AGENT.to_owned(),
                    referer,
                    disable_hw_decode: false,
                },
            );
        }
        self.save_play_history(&selection);
        if let Some(view) = self.player_view.as_mut() {
            view.session = session;
            view.line_name = selection.line.name.clone();
            view.line_index = selection.line_index;
            view.media_url = selection.line.url.clone();
            view.status = PlayerStatus::Opening;
            view.error = None;
            view.duration = None;
            view.position = 0.0;
            view.seek_target = None;
            view.started_at = Instant::now();
        }
        self.error_text = None;
        self.status_text = format!("正在切换线路：{}…", selection.line.name);
    }

    fn fallback_player_to_vlc(&mut self, ctx: &egui::Context) {
        let Some(view) = self.player_view.as_ref() else {
            return;
        };
        let line = view
            .info
            .lines
            .get(view.line_index)
            .cloned()
            .unwrap_or_else(|| crate::models::PlayLine {
                name: view.line_name.clone(),
                url: view.media_url.clone(),
            });
        let selection = PlaySelection {
            work: view.work.clone(),
            episode_url: view.episode_url.clone(),
            episode_title: view.episode_title.clone(),
            episode_no: view.episode_no,
            line,
            line_index: view.line_index,
            info: view.info.clone(),
            referer: view.referer.clone(),
            media_title: view.media_title.clone(),
        };
        if let Some(player) = &self.embedded {
            player.stop();
        }
        self.video_window.hide();
        match self.launch_vlc_for(&selection) {
            Ok(child) => {
                self.external_vlc = Some(child);
                self.save_play_history(&selection);
                self.player_view = None;
                self.next_player_session();
                self.exit_fullscreen(ctx);
                self.error_text = None;
                self.status_text = format!(
                    "已改用外部 VLC 播放《{} · {}》",
                    selection.work.title, selection.episode_title
                );
            }
            Err(error) => {
                if let Some(view) = self.player_view.as_mut() {
                    view.error = Some(format!(
                        "{error}\n\n不会自动启动外部 VLC；请修复 VLC 路径后再次点击「改用 VLC 播放」。"
                    ));
                }
            }
        }
    }

    fn close_player(&mut self, ctx: &egui::Context) {
        if let Some(player) = &self.embedded {
            player.stop();
        }
        self.video_window.hide();
        self.exit_fullscreen(ctx);
        self.player_view = None;
        self.next_player_session();
        self.status_text = "已退出播放".to_owned();
    }

    /// 设置页切换源等场景下静默结束播放，不发送窗口命令。
    fn stop_player_silent(&mut self) {
        if let Some(player) = &self.embedded {
            player.stop();
        }
        self.video_window.hide();
        self.player_view = None;
        self.next_player_session();
        self.window_fullscreen = false;
    }

    fn toggle_player_pause(&mut self) {
        let Some(view) = self.player_view.as_ref() else {
            return;
        };
        if !matches!(
            view.status,
            PlayerStatus::Opening | PlayerStatus::Playing | PlayerStatus::Paused
        ) {
            return;
        }
        let next = view.status != PlayerStatus::Paused;
        if let Some(player) = &self.embedded {
            player.pause(next);
        }
        if let Some(view) = self.player_view.as_mut() {
            view.status = if next {
                PlayerStatus::Paused
            } else {
                PlayerStatus::Playing
            };
            self.status_text = if next {
                format!("已暂停：{}", view.episode_title)
            } else {
                format!("继续播放：{}", view.episode_title)
            };
        }
    }

    fn seek_player(&mut self, seconds: f64) {
        let seconds = seconds.max(0.0);
        if let Some(player) = &self.embedded {
            player.seek(seconds);
        }
        if let Some(view) = self.player_view.as_mut() {
            view.position = seconds;
            view.seek_target = None;
        }
    }

    fn set_player_volume(&mut self, volume: u8) {
        let volume = volume.min(100);
        self.config.embedded_volume = volume;
        if let Some(player) = &self.embedded {
            player.set_volume(volume);
        }
        if let Some(view) = self.player_view.as_mut() {
            view.volume = volume;
        }
    }

    fn replay_player(&mut self) {
        let Some(view) = self.player_view.as_ref() else {
            return;
        };
        let line = view
            .info
            .lines
            .get(view.line_index)
            .cloned()
            .unwrap_or_else(|| crate::models::PlayLine {
                name: view.line_name.clone(),
                url: view.media_url.clone(),
            });
        let selection = PlaySelection {
            work: view.work.clone(),
            episode_url: view.episode_url.clone(),
            episode_title: view.episode_title.clone(),
            episode_no: view.episode_no,
            line,
            line_index: view.line_index,
            info: view.info.clone(),
            referer: view.referer.clone(),
            media_title: view.media_title.clone(),
        };
        self.launch_embedded(selection);
    }

    fn toggle_player_fullscreen(&mut self, ctx: &egui::Context) {
        if self.player_view.is_none() {
            return;
        }
        self.window_fullscreen = !self.window_fullscreen;
        ctx.send_viewport_cmd(egui::ViewportCommand::Fullscreen(self.window_fullscreen));
    }

    fn exit_fullscreen(&mut self, ctx: &egui::Context) {
        if self.window_fullscreen {
            self.window_fullscreen = false;
            ctx.send_viewport_cmd(egui::ViewportCommand::Fullscreen(false));
        }
    }

    fn update_player_bounds(&mut self, rect: Option<egui::Rect>, ctx: &egui::Context) {
        let visible = self.player_view.as_ref().is_some_and(|view| {
            view.error.is_none()
                && matches!(
                    view.status,
                    PlayerStatus::Opening | PlayerStatus::Playing | PlayerStatus::Paused
                )
        });
        if !visible || self.embedded.is_none() {
            self.video_window.hide();
            return;
        }
        let Some(rect) = rect else {
            self.video_window.hide();
            return;
        };
        let ppp = ctx.pixels_per_point();
        let x = (rect.min.x * ppp).round() as i32;
        let y = (rect.min.y * ppp).round() as i32;
        let width = (rect.width() * ppp).round() as i32;
        let height = (rect.height() * ppp).round() as i32;
        if width > 0 && height > 0 {
            self.video_window.set_bounds(Some((x, y, width, height)));
        } else {
            self.video_window.hide();
        }
    }

    fn poll_player_events(&mut self, ctx: &egui::Context) {
        let mut events = Vec::new();
        if let Some(player) = &self.embedded {
            while let Some(event) = player.try_recv() {
                events.push(event);
            }
        }
        for event in events {
            self.handle_player_event(event, ctx);
        }
        let opening_timeout = self.player_view.as_ref().is_some_and(|view| {
            view.status == PlayerStatus::Opening
                && view.started_at.elapsed() > Duration::from_secs(30)
        });
        if opening_timeout {
            self.fail_player(
                "连接内置播放器超时，可能是线路不可用或网络过慢。".to_owned(),
                ctx,
            );
        }
    }

    fn handle_player_event(&mut self, event: EmbeddedEvent, ctx: &egui::Context) {
        let outcome = {
            let Some(view) = self.player_view.as_mut() else {
                return;
            };
            view.apply_event(event)
        };
        match outcome {
            PlayerEventOutcome::Ignored | PlayerEventOutcome::Updated => {}
            PlayerEventOutcome::Loading => {
                if let Some(view) = self.player_view.as_ref() {
                    self.status_text = format!("正在缓冲：{}…", view.episode_title);
                }
            }
            PlayerEventOutcome::Playing => {
                if let Some(view) = self.player_view.as_ref() {
                    self.status_text = format!("正在播放：{}", view.episode_title);
                }
            }
            PlayerEventOutcome::Ended => self.finish_player(ctx),
            PlayerEventOutcome::Failed(message) => self.fail_player(message, ctx),
        }
    }

    fn finish_player(&mut self, _ctx: &egui::Context) {
        if let Some(view) = self.player_view.as_mut() {
            view.mark_ended();
        }
        self.video_window.hide();
        if let Some(player) = &self.embedded {
            player.stop();
        }
        self.status_text = "播放结束".to_owned();
    }

    fn fail_player(&mut self, message: String, ctx: &egui::Context) {
        if let Some(view) = self.player_view.as_mut() {
            if view.status == PlayerStatus::Failed {
                return;
            }
            view.mark_failed(format!(
                "{message}\n\n不会自动启动外部 VLC；可点击下方「改用 VLC 播放」，或到设置中切换播放方式。"
            ));
        }
        self.video_window.hide();
        if let Some(player) = &self.embedded {
            player.stop();
        }
        self.exit_fullscreen(ctx);
        self.status_text = "内置播放失败".to_owned();
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
        if self.user_data_load_failed {
            self.set_error(
                "收藏/历史文件读取失败，已暂停自动保存以保护原文件；请到设置页恢复或重建。",
            );
            return;
        }
        match self.user_data.save_if_allowed(&self.user_file, true) {
            Ok(true) => {}
            Ok(false) => {
                self.set_error("收藏/历史文件受保护，本次修改未保存；请到设置页恢复或重建。")
            }
            Err(error) => self.set_error(format!("保存收藏/历史失败：{error}")),
        }
    }

    fn retry_user_data_load(&mut self) {
        match UserData::load_optional(&self.user_file) {
            Ok(Some(data)) => {
                self.user_data = data;
                self.user_data_load_failed = false;
                self.error_text = None;
                self.status_text = "已重新读取收藏/历史".to_owned();
            }
            Ok(None) => {
                // 失败后文件被外部移走，等同于首次运行。
                self.user_data = UserData::default();
                self.user_data_load_failed = false;
                self.error_text = None;
                self.status_text = "未发现用户数据文件，已恢复自动保存".to_owned();
            }
            Err(error) => {
                self.set_error(format!("重新读取收藏/历史失败，原文件仍保留：{error}"));
            }
        }
    }

    /// 用户明确选择保留损坏文件后再重建，避免静默覆盖。
    fn recover_user_data(&mut self) {
        let backup = self
            .user_file
            .with_file_name(format!("user_data.json.bak-{}", now_unix()));
        let exists = match crate::config::path_exists_checked(&self.user_file) {
            Ok(exists) => exists,
            Err(error) => {
                self.set_error(format!(
                    "无法确认用户数据文件状态，未做任何覆盖；请检查文件权限后重试：{error}"
                ));
                return;
            }
        };
        if exists {
            if let Err(error) = fs::rename(&self.user_file, &backup) {
                self.set_error(format!("备份原用户数据失败，未做任何覆盖：{error}"));
                return;
            }
        }
        self.user_data = UserData::default();
        self.user_data_load_failed = false;
        self.error_text = None;
        self.save_user_data();
        if self.error_text.is_none() {
            self.status_text = if backup.exists() {
                format!("已备份原文件到 {} 并重建用户数据", backup.display())
            } else {
                "已重建用户数据".to_owned()
            };
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
        self.settings_notice = None;
        self.show_settings = true;
        self.show_search_history = false;
    }

    fn apply_settings(&mut self, draft: SettingsDraft, ctx: &egui::Context) {
        let previous_base = self.config.source_base_trimmed();
        let theme_mode = draft.theme;
        let base_changed = draft.apply(&mut self.config);
        theme::set_mode(theme_mode);
        theme::apply(ctx);
        self.save_config(true);
        self.settings_draft = None;
        self.settings_notice = None;
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
        self.stop_player_silent();
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
        self.poll_player_events(ctx);
        self.process_events(ctx);
        self.draw_ui(ctx);
        if self.player_view.is_some() || self.play.is_some() {
            ctx.request_repaint_after(Duration::from_millis(250));
        }
    }

    fn on_exit(&mut self, _gl: Option<&eframe::glow::Context>) {
        // 只投递后台清理命令；mpv 由工作线程和 KILL_ON_JOB_CLOSE 作业对象兜底。
        if let Some(mut player) = self.embedded.take() {
            player.shutdown_fast();
        }
        if let Some(mut child) = self.external_vlc.take() {
            let _ = child.kill();
        }
        self.video_window.destroy();
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

#[cfg(test)]
mod player_view_tests {
    use super::*;

    fn view(session: u64) -> PlayerView {
        PlayerView {
            session,
            work: WorkItem::default(),
            episode_url: "/test/1".to_owned(),
            episode_title: "第01集".to_owned(),
            episode_no: 1,
            line_name: "线路1".to_owned(),
            line_index: 0,
            info: PlayInfo::default(),
            media_url: "http://127.0.0.1/test.mp4".to_owned(),
            media_title: "测试 · 第01集".to_owned(),
            referer: None,
            status: PlayerStatus::Opening,
            error: None,
            duration: None,
            position: 0.0,
            seek_target: None,
            volume: 80,
            started_at: Instant::now(),
        }
    }

    #[test]
    fn stale_session_events_are_ignored_after_line_switch_or_replay() {
        let mut view = view(1);
        assert_eq!(
            view.apply_event(EmbeddedEvent::Playing { load_id: 1 }),
            PlayerEventOutcome::Playing
        );
        view.position = 12.0;
        view.duration = Some(60.0);

        // 换线路或重新播放会开启新会话；旧会话的结束/错误/属性事件必须全部丢弃。
        view.session = 2;
        view.status = PlayerStatus::Opening;
        view.position = 0.0;
        view.duration = None;
        view.seek_target = None;

        assert_eq!(
            view.apply_event(EmbeddedEvent::Ended {
                load_id: 1,
                reason: EndReason::Eof,
                error: None,
            }),
            PlayerEventOutcome::Ignored
        );
        assert_eq!(
            view.apply_event(EmbeddedEvent::Error {
                load_id: 1,
                message: "old session".to_owned(),
            }),
            PlayerEventOutcome::Ignored
        );
        assert_eq!(
            view.apply_event(EmbeddedEvent::ProcessExited {
                load_id: 1,
                code: Some(1),
            }),
            PlayerEventOutcome::Ignored
        );
        assert_eq!(
            view.apply_event(EmbeddedEvent::Property {
                load_id: 1,
                property: PlayerProperty::TimePos(55.0),
            }),
            PlayerEventOutcome::Ignored
        );
        assert_eq!(view.status, PlayerStatus::Opening);
        assert_eq!(view.position, 0.0);
        assert_eq!(view.duration, None);

        // 新会话的事件仍然正常生效。
        assert_eq!(
            view.apply_event(EmbeddedEvent::Property {
                load_id: 2,
                property: PlayerProperty::Duration(Some(30.0)),
            }),
            PlayerEventOutcome::Updated
        );
        assert_eq!(
            view.apply_event(EmbeddedEvent::Property {
                load_id: 2,
                property: PlayerProperty::TimePos(11.0),
            }),
            PlayerEventOutcome::Updated
        );
        assert_eq!(view.position, 11.0);
        assert_eq!(
            view.apply_event(EmbeddedEvent::Ended {
                load_id: 2,
                reason: EndReason::Eof,
                error: None,
            }),
            PlayerEventOutcome::Ended
        );
    }

    #[test]
    fn unknown_duration_drag_does_not_block_later_progress() {
        let mut view = view(1);
        view.preview_seek_fraction(0.5);
        assert_eq!(view.seek_target, None);

        assert_eq!(
            view.apply_event(EmbeddedEvent::Property {
                load_id: 1,
                property: PlayerProperty::Duration(Some(100.0)),
            }),
            PlayerEventOutcome::Updated
        );
        assert_eq!(
            view.apply_event(EmbeddedEvent::Property {
                load_id: 1,
                property: PlayerProperty::TimePos(30.0),
            }),
            PlayerEventOutcome::Updated
        );
        assert_eq!(view.duration, Some(100.0));
        assert_eq!(view.position, 30.0);
        assert_eq!(view.seek_target, None);

        view.preview_seek_fraction(0.5);
        assert_eq!(view.seek_target, Some(50.0));
        assert_eq!(view.finish_seek_fraction(0.5), Some(50.0));
        assert_eq!(view.seek_target, None);

        // 拖动结束后 time-pos 继续更新。
        assert_eq!(
            view.apply_event(EmbeddedEvent::Property {
                load_id: 1,
                property: PlayerProperty::TimePos(55.0),
            }),
            PlayerEventOutcome::Updated
        );
        assert_eq!(view.position, 55.0);
    }

    #[test]
    fn invalid_duration_clears_seek_preview() {
        let mut view = view(1);
        view.duration = Some(60.0);
        view.seek_target = Some(30.0);
        view.apply_property(PlayerProperty::Duration(Some(0.0)));
        assert_eq!(view.duration, None);
        assert_eq!(view.seek_target, None);

        view.preview_seek_fraction(0.5);
        assert_eq!(view.seek_target, None);

        view.duration = Some(f64::NAN);
        view.seek_target = Some(10.0);
        view.apply_property(PlayerProperty::Duration(Some(f64::NAN)));
        assert_eq!(view.duration, None);
        assert_eq!(view.seek_target, None);
    }

    #[test]
    fn seek_target_is_cleared_on_finish_cancel_and_failure() {
        let mut view = view(1);
        view.duration = Some(60.0);
        view.seek_target = Some(20.0);
        view.clear_seek_target();
        assert_eq!(view.seek_target, None);

        view.seek_target = Some(20.0);
        view.mark_failed("failed".to_owned());
        assert_eq!(view.seek_target, None);
        assert_eq!(view.status, PlayerStatus::Failed);

        view.duration = Some(60.0);
        view.seek_target = Some(20.0);
        assert_eq!(view.finish_seek_fraction(0.5), Some(30.0));
        assert_eq!(view.seek_target, None);

        // 时长在拖动过程中失效：取消 seek，但不得留下 seek_target。
        view.duration = None;
        view.seek_target = Some(20.0);
        assert_eq!(view.finish_seek_fraction(0.5), None);
        assert_eq!(view.seek_target, None);
    }
}
