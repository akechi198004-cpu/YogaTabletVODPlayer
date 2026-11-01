//! 后台工作线程：页面/详情/播放解析串行执行，海报下载独立队列。
//!
//! UI 线程不做网络、文件读取或图片解码；所有结果通过 channel 回到主线程。

use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::mpsc::{self, Receiver, Sender, SyncSender};
use std::sync::Arc;
use std::thread;
use std::time::{Duration, Instant};

use serde::de::DeserializeOwned;
use serde::Serialize;

use crate::cache::{CacheEntry, CachePolicy, DecodedPoster, MetadataCache, PosterCache};
use crate::models::{
    BrowseKind, HomeData, PageResult, PlayInfo, PlayLine, TopBoards, WorkDetail, WorkItem,
};
use crate::parser;
use crate::player;
use crate::source::{
    build_list_url, build_player_service_url, build_search_url, SourceClient, SourceError,
};

const PLAY_CACHE_TTL: Duration = Duration::from_secs(30 * 60);
const PLAY_CACHE_MAX: usize = 20;
const POSTER_QUEUE_CAPACITY: usize = 24;
const PROBE_SAMPLE_BYTES: usize = 128 * 1024;
const PROBE_MAX_LINES: usize = 6;

#[derive(Clone, Debug)]
pub enum BrowseData {
    Home(HomeData),
    Page(PageResult),
    Top(TopBoards),
    Daily(Vec<WorkItem>),
}

#[derive(Clone, Debug)]
pub struct LineProbeResult {
    pub index: usize,
    pub elapsed_ms: u128,
    pub bytes: usize,
}

impl LineProbeResult {
    pub fn score(&self) -> u128 {
        self.elapsed_ms * (1 + 131_072 / self.bytes.max(1) as u128)
    }
}

pub enum Command {
    Browse {
        id: u64,
        kind: BrowseKind,
        force: bool,
        source_base: String,
    },
    Detail {
        id: u64,
        url: String,
        force: bool,
        source_base: String,
    },
    PlayInfo {
        id: u64,
        episode_url: String,
        work_title: String,
        episode_title: String,
        episode_no: u32,
        poster: String,
        force: bool,
        source_base: String,
    },
    ProbeLines {
        id: u64,
        lines: Vec<PlayLine>,
        referer: String,
        source_base: String,
    },
}

pub enum Event {
    Browse {
        id: u64,
        kind: BrowseKind,
        result: Result<BrowseData, String>,
        cached: bool,
    },
    Detail {
        id: u64,
        url: String,
        result: Result<WorkDetail, String>,
        cached: bool,
    },
    PlayInfo {
        id: u64,
        episode_url: String,
        result: Result<PlayInfo, String>,
    },
    LinesProbed {
        id: u64,
        result: Vec<LineProbeResult>,
    },
    PosterReady {
        epoch: u64,
        key: String,
        poster: DecodedPoster,
    },
    PosterFailed {
        epoch: u64,
        key: String,
    },
}

#[derive(Clone)]
pub enum PosterCommand {
    Fetch {
        epoch: u64,
        key: String,
        url: String,
        path: PathBuf,
        source_base: String,
    },
    Decode {
        epoch: u64,
        key: String,
        path: PathBuf,
    },
}

struct CachedSource {
    key: String,
    client: Option<SourceClient>,
}

impl CachedSource {
    fn new() -> Self {
        Self {
            key: String::new(),
            client: None,
        }
    }

    fn get(&mut self, source_base: &str) -> Result<&SourceClient, String> {
        let key = if source_base.trim().is_empty() {
            crate::config::DEFAULT_SOURCE_BASE.to_owned()
        } else {
            source_base.trim().trim_end_matches('/').to_owned()
        };
        if self.client.is_none() || self.key != key {
            self.client = Some(
                SourceClient::new(&key)
                    .map_err(|error| format!("无法创建网络客户端：{}", error.friendly()))?,
            );
            self.key = key;
        }
        Ok(self.client.as_ref().expect("client initialized"))
    }
}

struct FetchError {
    message: String,
    gone: bool,
}

impl From<SourceError> for FetchError {
    fn from(error: SourceError) -> Self {
        Self {
            message: error.friendly(),
            gone: error.is_gone(),
        }
    }
}

enum Fetched<T> {
    NotModified {
        etag: Option<String>,
        last_modified: Option<String>,
    },
    Loaded {
        data: T,
        etag: Option<String>,
        last_modified: Option<String>,
    },
}

fn cache_key(source_base: &str, key: &str) -> String {
    let base = if source_base.trim().is_empty() {
        crate::config::DEFAULT_SOURCE_BASE
    } else {
        source_base.trim().trim_end_matches('/')
    };
    format!("{base}|{key}")
}

/// 缓存优先读取；过期后请求网络，失败时在“最长可展示”窗口内使用旧缓存。
fn load_cached<T, F>(
    cache: &MetadataCache,
    source_base: &str,
    key: &str,
    policy: CachePolicy,
    force: bool,
    should_cache: impl Fn(&T) -> bool,
    fetch: F,
) -> Result<(T, bool), String>
where
    T: Serialize + DeserializeOwned + Clone,
    F: FnOnce(Option<&str>, Option<&str>) -> Result<Fetched<T>, FetchError>,
{
    let key = cache_key(source_base, key);
    let entry: Option<CacheEntry<T>> = if force { None } else { cache.load(&key) };

    if let Some(entry) = &entry {
        if !force && policy.is_fresh(entry.age_secs()) {
            return Ok((entry.data.clone(), true));
        }
    }

    let etag = entry.as_ref().and_then(|entry| entry.etag.as_deref());
    let last_modified = entry
        .as_ref()
        .and_then(|entry| entry.last_modified.as_deref());

    match fetch(etag, last_modified) {
        Ok(Fetched::NotModified {
            etag,
            last_modified,
        }) => {
            if let Some(entry) = entry {
                cache.touch(&key, etag.as_deref(), last_modified.as_deref());
                Ok((entry.data, true))
            } else {
                Err("服务器返回 304，但本地没有可用缓存。".to_owned())
            }
        }
        Ok(Fetched::Loaded {
            data,
            etag,
            last_modified,
        }) => {
            if should_cache(&data) {
                let _ = cache.store(&key, &data, etag.as_deref(), last_modified.as_deref());
            }
            Ok((data, false))
        }
        Err(error) => {
            if error.gone {
                cache.remove(&key);
            }
            if !force {
                if let Some(entry) = entry {
                    if policy.is_usable(entry.age_secs()) && !error.gone {
                        return Ok((entry.data, true));
                    }
                }
            }
            Err(error.message)
        }
    }
}

/// 泛型返回时避免要求 T: Clone；调用方总是拿到所有权。
fn fetch_page(
    client: &SourceClient,
    path: &str,
    referer: &str,
    etag: Option<&str>,
    last_modified: Option<&str>,
) -> Result<crate::source::PageResponse, FetchError> {
    client
        .fetch_page_conditional(path, Some(referer), etag, last_modified)
        .map_err(FetchError::from)
}

fn load_browse(
    cache: &MetadataCache,
    client: &SourceClient,
    source_base: &str,
    kind: &BrowseKind,
    force: bool,
) -> Result<(BrowseData, bool), String> {
    let referer = format!("{}/", client.base());
    match kind {
        BrowseKind::Home => {
            let (data, cached) = load_cached(
                cache,
                source_base,
                "home",
                CachePolicy::HOME,
                force,
                |data: &HomeData| !data.hero.is_empty() || !data.recent.is_empty(),
                |etag, last_modified| {
                    let page = fetch_page(client, "/", &referer, etag, last_modified)?;
                    if page.not_modified {
                        return Ok(Fetched::NotModified {
                            etag: page.etag,
                            last_modified: page.last_modified,
                        });
                    }
                    Ok(Fetched::Loaded {
                        data: parser::parse_home_page(&page.html),
                        etag: page.etag,
                        last_modified: page.last_modified,
                    })
                },
            )?;
            Ok((BrowseData::Home(data), cached))
        }
        BrowseKind::Category {
            cat,
            page,
            area,
            year,
            sort,
        } => {
            let path = build_list_url(
                cat,
                *page,
                area.as_deref(),
                year.as_deref(),
                sort.as_deref(),
            );
            let requested_page = *page;
            let (data, cached) = load_cached(
                cache,
                source_base,
                &format!("list:{path}"),
                CachePolicy::LIST,
                force,
                |data: &PageResult| !data.items.is_empty() || data.total_items == Some(0),
                |etag, last_modified| {
                    let page = fetch_page(client, &path, &referer, etag, last_modified)?;
                    if page.not_modified {
                        return Ok(Fetched::NotModified {
                            etag: page.etag,
                            last_modified: page.last_modified,
                        });
                    }
                    Ok(Fetched::Loaded {
                        data: parser::parse_category_page(&page.html, requested_page),
                        etag: page.etag,
                        last_modified: page.last_modified,
                    })
                },
            )?;
            Ok((BrowseData::Page(data), cached))
        }
        BrowseKind::Top => {
            let (data, cached) = load_cached(
                cache,
                source_base,
                "top",
                CachePolicy::TOP,
                force,
                |data: &TopBoards| {
                    !data.day.is_empty() || !data.week.is_empty() || !data.month.is_empty()
                },
                |etag, last_modified| {
                    let page = fetch_page(client, "/top.html", &referer, etag, last_modified)?;
                    if page.not_modified {
                        return Ok(Fetched::NotModified {
                            etag: page.etag,
                            last_modified: page.last_modified,
                        });
                    }
                    Ok(Fetched::Loaded {
                        data: parser::parse_top_page(&page.html),
                        etag: page.etag,
                        last_modified: page.last_modified,
                    })
                },
            )?;
            Ok((BrowseData::Top(data), cached))
        }
        BrowseKind::Daily => {
            let (data, cached) = load_cached(
                cache,
                source_base,
                "daily",
                CachePolicy::DAILY,
                force,
                |data: &Vec<WorkItem>| !data.is_empty(),
                |etag, last_modified| {
                    let page = fetch_page(client, "/new.html", &referer, etag, last_modified)?;
                    if page.not_modified {
                        return Ok(Fetched::NotModified {
                            etag: page.etag,
                            last_modified: page.last_modified,
                        });
                    }
                    Ok(Fetched::Loaded {
                        data: parser::parse_daily_page(&page.html),
                        etag: page.etag,
                        last_modified: page.last_modified,
                    })
                },
            )?;
            Ok((BrowseData::Daily(data), cached))
        }
        BrowseKind::Search {
            keyword,
            kind,
            page,
        } => {
            let path = build_search_url(keyword, kind.cid(), *page);
            let requested_page = *page;
            let (data, cached) = load_cached(
                cache,
                source_base,
                &format!("search:{path}"),
                CachePolicy::SEARCH,
                force,
                |data: &PageResult| !data.items.is_empty() || data.total_items == Some(0),
                |etag, last_modified| {
                    let page = fetch_page(client, &path, &referer, etag, last_modified)?;
                    if page.not_modified {
                        return Ok(Fetched::NotModified {
                            etag: page.etag,
                            last_modified: page.last_modified,
                        });
                    }
                    Ok(Fetched::Loaded {
                        data: parser::parse_search_page(&page.html, requested_page),
                        etag: page.etag,
                        last_modified: page.last_modified,
                    })
                },
            )?;
            Ok((BrowseData::Page(data), cached))
        }
    }
}

fn load_detail(
    cache: &MetadataCache,
    client: &SourceClient,
    source_base: &str,
    url: &str,
    force: bool,
) -> Result<(WorkDetail, bool), String> {
    let referer = format!("{}/", client.base());
    load_cached(
        cache,
        source_base,
        &format!("detail:{url}"),
        CachePolicy::DETAIL,
        force,
        |detail: &WorkDetail| !detail.lines.is_empty(),
        |etag, last_modified| {
            let page = fetch_page(client, url, &referer, etag, last_modified)?;
            if page.not_modified {
                return Ok(Fetched::NotModified {
                    etag: page.etag,
                    last_modified: page.last_modified,
                });
            }
            Ok(Fetched::Loaded {
                data: parser::parse_detail_page(&page.html, url),
                etag: page.etag,
                last_modified: page.last_modified,
            })
        },
    )
}

#[allow(clippy::too_many_arguments)]
fn load_play_info(
    client: &SourceClient,
    cache: &mut HashMap<String, (Instant, PlayInfo)>,
    source_base: &str,
    episode_url: &str,
    work_title: &str,
    episode_title: &str,
    episode_no: u32,
    poster: &str,
    force: bool,
) -> Result<PlayInfo, String> {
    let key = format!("{}|{}", source_base.trim_end_matches('/'), episode_url);
    if force {
        cache.remove(&key);
    } else if let Some((saved_at, info)) = cache.get(&key) {
        if saved_at.elapsed() < PLAY_CACHE_TTL {
            return Ok(info.clone());
        }
        cache.remove(&key);
    }

    if player::is_direct_media_url(episode_url) {
        let info = PlayInfo {
            work_title: work_title.to_owned(),
            episode_title: episode_title.to_owned(),
            poster: Some(poster.to_owned()).filter(|value| !value.is_empty()),
            lines: vec![PlayLine {
                name: "视频源".to_owned(),
                url: episode_url.to_owned(),
            }],
            next_episode_url: None,
            service_url: Some(episode_url.to_owned()),
            episode_no,
        };
        remember_play(cache, key, info.clone());
        return Ok(info);
    }

    let referer = format!("{}/", client.base());
    let episode_page = client
        .fetch_page_conditional(episode_url, Some(&referer), None, None)
        .map_err(|error| error.friendly())?;
    let player_config = parser::extract_player_config(&episode_page.html)
        .ok_or_else(|| "播放页面没有返回可识别的播放器配置。".to_owned())?;
    let (parsed_work, parsed_episode) = parser::parse_play_title(&episode_page.html);
    let service_url =
        build_player_service_url(source_base, &player_config.apiurl, &player_config.url)
            .map_err(|error| error.friendly())?;
    let service_page = client
        .fetch_page_conditional(&service_url, Some(&episode_page.final_url), None, None)
        .map_err(|error| error.friendly())?;
    let lines =
        parser::extract_play_lines_from_player_html(&service_page.html, &service_page.final_url);
    if lines.is_empty() {
        return Err("播放源解析失败：未发现可用的流媒体线路。".to_owned());
    }
    let info = PlayInfo {
        work_title: if parsed_work.is_empty() {
            work_title.to_owned()
        } else {
            parsed_work
        },
        episode_title: if parsed_episode.is_empty() {
            episode_title.to_owned()
        } else {
            parsed_episode
        },
        poster: Some(poster.to_owned()).filter(|value| !value.is_empty()),
        lines,
        next_episode_url: player_config.next,
        service_url: Some(service_page.final_url),
        episode_no: episode_number_from_url(episode_url).unwrap_or(episode_no),
    };
    remember_play(cache, key, info.clone());
    Ok(info)
}

fn remember_play(cache: &mut HashMap<String, (Instant, PlayInfo)>, key: String, info: PlayInfo) {
    cache.insert(key, (Instant::now(), info));
    if cache.len() > PLAY_CACHE_MAX {
        let now = Instant::now();
        cache.retain(|_, (saved_at, _)| now.duration_since(*saved_at) < PLAY_CACHE_TTL);
        if cache.len() > PLAY_CACHE_MAX {
            if let Some(oldest) = cache
                .iter()
                .min_by_key(|(_, (saved_at, _))| *saved_at)
                .map(|(key, _)| key.clone())
            {
                cache.remove(&oldest);
            }
        }
    }
}

fn episode_number_from_url(url: &str) -> Option<u32> {
    let captures = regex::Regex::new(r"/(\d+)-(\d+)\.html(?:$|\?)")
        .ok()?
        .captures(url)?;
    captures
        .get(2)
        .and_then(|value| value.as_str().parse::<u32>().ok())
}

fn probe_lines(client: &SourceClient, lines: &[PlayLine], referer: &str) -> Vec<LineProbeResult> {
    let count = lines.len().min(PROBE_MAX_LINES);
    if count == 0 {
        return Vec::new();
    }
    let mut results = Vec::new();
    thread::scope(|scope| {
        let (tx, rx) = mpsc::channel();
        for (index, line) in lines.iter().take(count).enumerate() {
            let tx = tx.clone();
            let client = client.clone();
            let url = line.url.clone();
            let referer = referer.to_owned();
            scope.spawn(move || {
                let sample = client.fetch_media_sample(&url, Some(&referer), PROBE_SAMPLE_BYTES);
                let _ = tx.send((index, sample));
            });
        }
        drop(tx);
        let deadline = Instant::now() + Duration::from_millis(3600);
        while results.len() < count {
            let Some(remaining) = deadline.checked_duration_since(Instant::now()) else {
                break;
            };
            match rx.recv_timeout(remaining) {
                Ok((index, Ok((bytes, elapsed_ms)))) => results.push(LineProbeResult {
                    index,
                    elapsed_ms,
                    bytes,
                }),
                Ok((_, Err(_))) => {}
                Err(_) => break,
            }
        }
    });
    results.sort_by_key(LineProbeResult::score);
    results
}

fn emit_event(ctx: &egui::Context, tx: &Sender<Event>, event: Event) {
    if tx.send(event).is_ok() {
        ctx.request_repaint();
    }
}

/// 启动页面线程与海报线程。
pub fn spawn_workers(
    ctx: egui::Context,
    metadata_cache: MetadataCache,
    poster_cache: PosterCache,
    browse_epoch: Arc<AtomicU64>,
    detail_epoch: Arc<AtomicU64>,
    play_epoch: Arc<AtomicU64>,
    poster_epoch: Arc<AtomicU64>,
) -> (
    Sender<Command>,
    Receiver<Event>,
    Vec<SyncSender<PosterCommand>>,
) {
    let (command_tx, command_rx) = mpsc::channel::<Command>();
    let (event_tx, event_rx) = mpsc::channel::<Event>();

    {
        let ctx = ctx.clone();
        let event_tx = event_tx.clone();
        let metadata_cache = metadata_cache.clone();
        let browse_epoch = browse_epoch.clone();
        let detail_epoch = detail_epoch.clone();
        let play_epoch = play_epoch.clone();
        thread::Builder::new()
            .name("hm-page-worker".to_owned())
            .spawn(move || {
                let mut source = CachedSource::new();
                let mut play_cache: HashMap<String, (Instant, PlayInfo)> = HashMap::new();
                while let Ok(command) = command_rx.recv() {
                    match command {
                        Command::Browse {
                            id,
                            kind,
                            force,
                            source_base,
                        } => {
                            if browse_epoch.load(Ordering::SeqCst) != id {
                                continue;
                            }
                            let result = source.get(&source_base).and_then(|client| {
                                load_browse(&metadata_cache, client, &source_base, &kind, force)
                            });
                            let (result, cached) = match result {
                                Ok((data, cached)) => (Ok(data), cached),
                                Err(error) => (Err(error), false),
                            };
                            emit_event(
                                &ctx,
                                &event_tx,
                                Event::Browse {
                                    id,
                                    kind,
                                    result,
                                    cached,
                                },
                            );
                        }
                        Command::Detail {
                            id,
                            url,
                            force,
                            source_base,
                        } => {
                            if detail_epoch.load(Ordering::SeqCst) != id {
                                continue;
                            }
                            let result = source.get(&source_base).and_then(|client| {
                                load_detail(&metadata_cache, client, &source_base, &url, force)
                            });
                            let (result, cached) = match result {
                                Ok((detail, cached)) => (Ok(detail), cached),
                                Err(error) => (Err(error), false),
                            };
                            emit_event(
                                &ctx,
                                &event_tx,
                                Event::Detail {
                                    id,
                                    url,
                                    result,
                                    cached,
                                },
                            );
                        }
                        Command::PlayInfo {
                            id,
                            episode_url,
                            work_title,
                            episode_title,
                            episode_no,
                            poster,
                            force,
                            source_base,
                        } => {
                            if play_epoch.load(Ordering::SeqCst) != id {
                                continue;
                            }
                            let result = match source.get(&source_base) {
                                Ok(client) => load_play_info(
                                    client,
                                    &mut play_cache,
                                    &source_base,
                                    &episode_url,
                                    &work_title,
                                    &episode_title,
                                    episode_no,
                                    &poster,
                                    force,
                                ),
                                Err(error) => Err(error),
                            };
                            emit_event(
                                &ctx,
                                &event_tx,
                                Event::PlayInfo {
                                    id,
                                    episode_url,
                                    result,
                                },
                            );
                        }
                        Command::ProbeLines {
                            id,
                            lines,
                            referer,
                            source_base,
                        } => {
                            if play_epoch.load(Ordering::SeqCst) != id {
                                continue;
                            }
                            let result = match source.get(&source_base) {
                                Ok(client) => probe_lines(client, &lines, &referer),
                                Err(_) => Vec::new(),
                            };
                            emit_event(&ctx, &event_tx, Event::LinesProbed { id, result });
                        }
                    }
                }
            })
            .expect("start page worker");
    }

    let mut poster_txs = Vec::new();
    for _ in 0..2 {
        let (poster_tx, poster_rx) = mpsc::sync_channel::<PosterCommand>(POSTER_QUEUE_CAPACITY);
        poster_txs.push(poster_tx);
        let ctx = ctx.clone();
        let event_tx = event_tx.clone();
        let poster_cache = poster_cache.clone();
        let poster_epoch = poster_epoch.clone();
        thread::Builder::new()
            .name("hm-poster-worker".to_owned())
            .spawn(move || {
                let mut source = CachedSource::new();
                while let Ok(command) = poster_rx.recv() {
                    let (epoch, key, result) = match command {
                        PosterCommand::Fetch {
                            epoch,
                            key,
                            url,
                            path,
                            source_base,
                        } => {
                            if poster_epoch.load(Ordering::SeqCst) != epoch {
                                continue;
                            }
                            let result = (|| -> Result<DecodedPoster, ()> {
                                let client = source.get(&source_base).map_err(|_| ())?;
                                let bytes = client.fetch_image(&url, None).map_err(|_| ())?;
                                // 缓存目录只读时仍应显示海报，落盘失败不阻断解码。
                                let _ = poster_cache.store(&path, &bytes);
                                poster_cache.decode(&bytes).map_err(|error| {
                                    if error.is_corrupt_image() {
                                        poster_cache.remove(&path);
                                    }
                                })
                            })();
                            (epoch, key, result)
                        }
                        PosterCommand::Decode { epoch, key, path } => {
                            if poster_epoch.load(Ordering::SeqCst) != epoch {
                                continue;
                            }
                            let result = poster_cache.read(&path).map_err(|_| {
                                // 文件损坏或不可读时删除，下一次请求改走网络。
                                poster_cache.remove(&path);
                            });
                            (epoch, key, result)
                        }
                    };
                    match result {
                        Ok(poster) => {
                            emit_event(&ctx, &event_tx, Event::PosterReady { epoch, key, poster })
                        }
                        Err(()) => emit_event(&ctx, &event_tx, Event::PosterFailed { epoch, key }),
                    }
                }
            })
            .expect("start poster worker");
    }

    (command_tx, event_rx, poster_txs)
}
