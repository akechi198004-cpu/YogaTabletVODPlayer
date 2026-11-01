//! 星帆影视站点访问：URL 规则、HTTP 客户端与分类常量。

use std::io::Read;
use std::time::Duration;

use regex::Regex;
use reqwest::blocking::Client;
use reqwest::header::CONTENT_LENGTH;
use thiserror::Error;
use url::{form_urlencoded, Url};

use crate::models::{Category, ChipOption};

pub const DEFAULT_SOURCE_BASE: &str = "https://www.xingfan.cc";
pub const USER_AGENT: &str = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";
/// 单张海报上限，避免异常响应占满 32 位进程内存。
pub const MAX_IMAGE_BYTES: usize = 10 * 1024 * 1024;

pub const TV_CATEGORIES: &[Category] = &[
    Category {
        slug: "dianshiju",
        label: "全部剧集",
    },
    Category {
        slug: "guocanju",
        label: "国产剧",
    },
    Category {
        slug: "xianggangju",
        label: "香港剧",
    },
    Category {
        slug: "oumeiju",
        label: "欧美剧",
    },
    Category {
        slug: "ribenju",
        label: "日本剧",
    },
    Category {
        slug: "haiwaiju",
        label: "海外剧",
    },
    Category {
        slug: "taiwanju",
        label: "台湾剧",
    },
    Category {
        slug: "hanguoju",
        label: "韩剧",
    },
    Category {
        slug: "taiguoju",
        label: "泰剧",
    },
];

pub const MOVIE_CATEGORIES: &[Category] = &[
    Category {
        slug: "dianying",
        label: "全部电影",
    },
    Category {
        slug: "dongzuopian",
        label: "动作片",
    },
    Category {
        slug: "xijupian",
        label: "喜剧片",
    },
    Category {
        slug: "aiqingpian",
        label: "爱情片",
    },
    Category {
        slug: "kehuanpian",
        label: "科幻片",
    },
    Category {
        slug: "kongbupian",
        label: "恐怖片",
    },
    Category {
        slug: "zhanzhengpian",
        label: "战争片",
    },
    Category {
        slug: "juqingpian",
        label: "剧情片",
    },
    Category {
        slug: "jilupian",
        label: "纪录片",
    },
    Category {
        slug: "donghuapian",
        label: "动画片",
    },
];

pub const ANIME_CATEGORIES: &[Category] = &[
    Category {
        slug: "dongman",
        label: "全部动漫",
    },
    Category {
        slug: "ribendongman",
        label: "日本动漫",
    },
    Category {
        slug: "guochandongman",
        label: "国创动画",
    },
];

pub const VARIETY_CATEGORIES: &[Category] = &[
    Category {
        slug: "zongyi",
        label: "全部综艺",
    },
    Category {
        slug: "dalu_variety",
        label: "大陆真人秀",
    },
    Category {
        slug: "talk_variety",
        label: "脱口秀相声",
    },
];

pub const ALL_CATEGORIES: &[Category] = &[
    Category {
        slug: "dianshiju",
        label: "全部剧集",
    },
    Category {
        slug: "guocanju",
        label: "国产剧",
    },
    Category {
        slug: "xianggangju",
        label: "香港剧",
    },
    Category {
        slug: "oumeiju",
        label: "欧美剧",
    },
    Category {
        slug: "ribenju",
        label: "日本剧",
    },
    Category {
        slug: "haiwaiju",
        label: "海外剧",
    },
    Category {
        slug: "taiwanju",
        label: "台湾剧",
    },
    Category {
        slug: "hanguoju",
        label: "韩剧",
    },
    Category {
        slug: "taiguoju",
        label: "泰剧",
    },
    Category {
        slug: "dianying",
        label: "全部电影",
    },
    Category {
        slug: "dongzuopian",
        label: "动作片",
    },
    Category {
        slug: "xijupian",
        label: "喜剧片",
    },
    Category {
        slug: "aiqingpian",
        label: "爱情片",
    },
    Category {
        slug: "kehuanpian",
        label: "科幻片",
    },
    Category {
        slug: "kongbupian",
        label: "恐怖片",
    },
    Category {
        slug: "zhanzhengpian",
        label: "战争片",
    },
    Category {
        slug: "juqingpian",
        label: "剧情片",
    },
    Category {
        slug: "jilupian",
        label: "纪录片",
    },
    Category {
        slug: "donghuapian",
        label: "动画片",
    },
    Category {
        slug: "dongman",
        label: "全部动漫",
    },
    Category {
        slug: "ribendongman",
        label: "日本动漫",
    },
    Category {
        slug: "guochandongman",
        label: "国创动画",
    },
    Category {
        slug: "zongyi",
        label: "全部综艺",
    },
    Category {
        slug: "dalu_variety",
        label: "大陆真人秀",
    },
    Category {
        slug: "talk_variety",
        label: "脱口秀相声",
    },
];

pub fn get_category_label(slug: &str) -> String {
    ALL_CATEGORIES
        .iter()
        .find(|category| category.slug == slug)
        .map(|category| category.label.to_owned())
        .unwrap_or_else(|| slug.to_owned())
}

/// 分类筛选的默认值，站点解析失败时仍能展示。
pub fn fallback_areas() -> Vec<ChipOption> {
    [
        ("大陆", "Dalu"),
        ("港台", "Gangtai"),
        ("日韩", "Rihan"),
        ("欧美", "Oumei"),
        ("香港", "HongKong"),
        ("台湾", "Taiwan"),
        ("美国", "USA"),
        ("韩国", "Hanguo"),
        ("日本", "Japan"),
        ("泰国", "Thailand"),
        ("新加坡", "Singapore"),
        ("马来西亚", "Malaysia"),
        ("印度", "India"),
        ("英国", "England"),
        ("法国", "France"),
        ("加拿大", "Canada"),
        ("其它", "Qita"),
    ]
    .into_iter()
    .map(|(label, value)| ChipOption {
        label: label.to_owned(),
        value: value.to_owned(),
    })
    .collect()
}

pub fn fallback_years() -> Vec<ChipOption> {
    let current = current_year();
    (current.saturating_sub(10)..=current)
        .rev()
        .map(|year| ChipOption {
            label: year.to_string(),
            value: year.to_string(),
        })
        .collect()
}

pub fn fallback_sorts() -> Vec<ChipOption> {
    [("最新", "addtime"), ("最热", "hits"), ("评分", "gold")]
        .into_iter()
        .map(|(label, value)| ChipOption {
            label: label.to_owned(),
            value: value.to_owned(),
        })
        .collect()
}

fn current_year() -> u32 {
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|duration| duration.as_secs())
        .unwrap_or(0);
    // 以 1970 起的秒数粗略换算年份，避免为取年份引入时间库。
    let days = now / 86_400;
    let mut year = 1970u32;
    let mut remaining = days as i64;
    loop {
        let leap = is_leap(year);
        let year_days = if leap { 366 } else { 365 };
        if remaining < year_days {
            break;
        }
        remaining -= year_days;
        year += 1;
    }
    year
}

#[allow(clippy::manual_is_multiple_of)]
fn is_leap(year: u32) -> bool {
    year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
}

/// 一次页面请求的响应。
#[derive(Clone, Debug)]
pub struct PageResponse {
    pub html: String,
    pub final_url: String,
    pub etag: Option<String>,
    pub last_modified: Option<String>,
    pub not_modified: bool,
}

/// 站点 HTTP 客户端。页面、图片与未来媒体探测共用连接池，但使用独立超时。
#[derive(Clone, Debug)]
pub struct SourceClient {
    base: String,
    http: Client,
    image_http: Client,
    probe_http: Client,
}

impl SourceClient {
    pub fn new(base: &str) -> Result<Self, SourceError> {
        let base = if base.trim().is_empty() {
            DEFAULT_SOURCE_BASE.to_owned()
        } else {
            base.trim().trim_end_matches('/').to_owned()
        };
        // 先验证地址，避免直到第一次请求才报错。
        Url::parse(&base)?;
        let http = Client::builder()
            .user_agent(USER_AGENT)
            .timeout(Duration::from_secs(20))
            .connect_timeout(Duration::from_secs(6))
            .gzip(true)
            .build()?;
        let image_http = Client::builder()
            .user_agent(USER_AGENT)
            .timeout(Duration::from_secs(30))
            .connect_timeout(Duration::from_secs(6))
            .gzip(true)
            .build()?;
        let probe_http = Client::builder()
            .user_agent(USER_AGENT)
            .timeout(Duration::from_secs(3))
            .connect_timeout(Duration::from_secs(2))
            .build()?;
        Ok(Self {
            base,
            http,
            image_http,
            probe_http,
        })
    }

    pub fn base(&self) -> &str {
        &self.base
    }

    pub fn abs_url(&self, path: &str) -> Result<String, SourceError> {
        let path = path.trim();
        if path.starts_with("http://") || path.starts_with("https://") {
            return Ok(path.to_owned());
        }
        let base = Url::parse(&format!("{}/", self.base))?;
        if path.starts_with("//") {
            return Ok(format!("https:{}", path));
        }
        let clean = if path.starts_with('/') {
            path.to_owned()
        } else {
            format!("/{path}")
        };
        Ok(base.join(&clean)?.to_string())
    }

    pub fn fetch_page_conditional(
        &self,
        url: &str,
        referer: Option<&str>,
        etag: Option<&str>,
        last_modified: Option<&str>,
    ) -> Result<PageResponse, SourceError> {
        let target_url = self.abs_url(url)?;
        let referer = referer
            .map(str::to_owned)
            .unwrap_or_else(|| format!("{}/", self.base));
        let mut request = self
            .http
            .get(&target_url)
            .header("Referer", referer)
            .header("User-Agent", USER_AGENT);
        if let Some(etag) = etag.filter(|value| !value.is_empty()) {
            request = request.header("If-None-Match", etag);
        }
        if let Some(last_modified) = last_modified.filter(|value| !value.is_empty()) {
            request = request.header("If-Modified-Since", last_modified);
        }
        let response = request.send()?;
        let status = response.status();
        let final_url = response.url().to_string();
        if status == reqwest::StatusCode::NOT_MODIFIED {
            return Ok(PageResponse {
                html: String::new(),
                final_url,
                etag: response
                    .headers()
                    .get("etag")
                    .and_then(|value| value.to_str().ok())
                    .map(str::to_owned)
                    .or_else(|| etag.map(str::to_owned)),
                last_modified: response
                    .headers()
                    .get("last-modified")
                    .and_then(|value| value.to_str().ok())
                    .map(str::to_owned)
                    .or_else(|| last_modified.map(str::to_owned)),
                not_modified: true,
            });
        }
        if !status.is_success() {
            return Err(SourceError::HttpStatus {
                code: status.as_u16(),
                url: target_url,
            });
        }
        let etag = response
            .headers()
            .get("etag")
            .and_then(|value| value.to_str().ok())
            .map(str::to_owned);
        let last_modified = response
            .headers()
            .get("last-modified")
            .and_then(|value| value.to_str().ok())
            .map(str::to_owned);
        let html = response.text()?;
        Ok(PageResponse {
            html,
            final_url,
            etag,
            last_modified,
            not_modified: false,
        })
    }

    /// 下载海报。限制单张大小，失败时只影响占位图。
    pub fn fetch_image(&self, url: &str, referer: Option<&str>) -> Result<Vec<u8>, SourceError> {
        let target_url = self.abs_url(url)?;
        let referer = referer
            .map(str::to_owned)
            .unwrap_or_else(|| format!("{}/", self.base));
        let response = self
            .image_http
            .get(&target_url)
            .header("Referer", referer)
            .header("User-Agent", USER_AGENT)
            .send()?;
        let status = response.status();
        if !status.is_success() {
            return Err(SourceError::HttpStatus {
                code: status.as_u16(),
                url: target_url,
            });
        }
        if let Some(length) = response
            .headers()
            .get(CONTENT_LENGTH)
            .and_then(|value| value.to_str().ok())
            .and_then(|value| value.parse::<usize>().ok())
        {
            if length > MAX_IMAGE_BYTES {
                return Err(SourceError::ImageTooLarge);
            }
        }
        let mut buffer = Vec::new();
        response
            .take(MAX_IMAGE_BYTES as u64 + 1)
            .read_to_end(&mut buffer)?;
        if buffer.len() > MAX_IMAGE_BYTES {
            return Err(SourceError::ImageTooLarge);
        }
        Ok(buffer)
    }

    /// 小样本探测：只读取前 `limit` 字节，用于判断线路是否可响应。
    /// 目前桌面端只在用户手动点“测速”时使用。
    pub fn fetch_media_sample(
        &self,
        url: &str,
        referer: Option<&str>,
        limit: usize,
    ) -> Result<(usize, u128), SourceError> {
        let target_url = self.abs_url(url)?;
        let referer = referer
            .map(str::to_owned)
            .unwrap_or_else(|| format!("{}/", self.base));
        let started = std::time::Instant::now();
        let response = self
            .probe_http
            .get(&target_url)
            .header("Referer", referer)
            .header("User-Agent", USER_AGENT)
            .header("Range", format!("bytes=0-{}", limit.saturating_sub(1)))
            .send()?;
        if !response.status().is_success() {
            return Err(SourceError::HttpStatus {
                code: response.status().as_u16(),
                url: target_url,
            });
        }
        let content_type = response
            .headers()
            .get("content-type")
            .and_then(|value| value.to_str().ok())
            .unwrap_or("")
            .to_ascii_lowercase();
        if content_type.contains("text/html") || content_type.contains("application/json") {
            return Err(SourceError::NotMedia);
        }
        let mut buffer = Vec::new();
        response.take(limit as u64).read_to_end(&mut buffer)?;
        if buffer.is_empty() {
            return Err(SourceError::NotMedia);
        }
        Ok((buffer.len(), started.elapsed().as_millis()))
    }
}

#[derive(Debug, Error)]
pub enum SourceError {
    #[error("地址格式错误：{0}")]
    Url(#[from] url::ParseError),
    #[error("网络请求失败：{0}")]
    Http(#[from] reqwest::Error),
    #[error("读取响应失败：{0}")]
    Io(#[from] std::io::Error),
    #[error("HTTP {code}：{url}")]
    HttpStatus { code: u16, url: String },
    #[error("海报超过大小限制")]
    ImageTooLarge,
    #[error("响应不是媒体流")]
    NotMedia,
}

impl SourceError {
    /// 源站明确表示内容已下架；此时不应继续使用旧缓存。
    pub fn is_gone(&self) -> bool {
        matches!(
            self,
            Self::HttpStatus {
                code: 404 | 410,
                ..
            }
        )
    }

    pub fn friendly(&self) -> String {
        match self {
            Self::HttpStatus { code, .. } => match *code {
                403 => "播放服务拒绝了请求，线路可能暂时不可用。".to_owned(),
                404 | 410 => "内容或线路已失效。".to_owned(),
                500..=599 => "站点暂时繁忙，请稍后重试。".to_owned(),
                other => format!("站点返回 HTTP {other}。"),
            },
            Self::ImageTooLarge => "海报文件过大，已跳过。".to_owned(),
            Self::NotMedia => "线路返回的不是媒体流，可能已失效。".to_owned(),
            Self::Url(_) => "站点地址格式不正确。".to_owned(),
            Self::Io(_) | Self::Http(_) => "网络连接失败，请检查网络后重试。".to_owned(),
        }
    }
}

/// 分类列表地址，与 Android 版规则一致。
pub fn build_list_url(
    cat: &str,
    page: u32,
    area: Option<&str>,
    year: Option<&str>,
    sort: Option<&str>,
) -> String {
    if area.is_none() && year.is_none() && sort.is_none() {
        return if page > 1 {
            format!("/{cat}/index-{page}.html")
        } else {
            format!("/{cat}/")
        };
    }
    let page = page.max(1);
    // 顺序：cat_type_area_year_letter_sort_page；桌面版只使用地区/年份/排序。
    format!(
        "/vodlist/{cat}__{area}_{year}__{sort}_{page}.html",
        area = area.unwrap_or(""),
        year = year.unwrap_or(""),
        sort = sort.unwrap_or("")
    )
}

/// 搜索地址。cid 仅支持 1=电影、2=剧集、3=动漫。
pub fn build_search_url(keyword: &str, cid: Option<u32>, page: u32) -> String {
    let encoded: String = form_urlencoded::byte_serialize(keyword.trim().as_bytes()).collect();
    let cid = cid.filter(|value| (1..=3).contains(value));
    if page > 1 {
        let path = format!("/search/{encoded}-{page}.html");
        match cid {
            Some(cid) => format!("{path}?cid={cid}"),
            None => path,
        }
    } else {
        match cid {
            Some(cid) => format!("/search/?wd={encoded}&cid={cid}"),
            None => format!("/search/?wd={encoded}"),
        }
    }
}

/// 播放器服务地址：apiurl 可能是协议相对、绝对或站内相对地址。
pub fn build_player_service_url(base: &str, apiurl: &str, id: &str) -> Result<String, SourceError> {
    let normalized = if apiurl.starts_with("//") {
        format!("https:{apiurl}")
    } else if apiurl.starts_with("http://") || apiurl.starts_with("https://") {
        apiurl.to_owned()
    } else {
        let base = if base.trim().is_empty() {
            DEFAULT_SOURCE_BASE
        } else {
            base.trim().trim_end_matches('/')
        };
        let base = Url::parse(&format!("{base}/"))?;
        base.join(apiurl)?.to_string()
    };
    Ok(format!("{normalized}{id}"))
}

/// 判断是否为站内作品详情地址。
pub fn is_detail_path(path: &str) -> bool {
    static DETAIL_RE: std::sync::OnceLock<Regex> = std::sync::OnceLock::new();
    DETAIL_RE
        .get_or_init(|| Regex::new(r"^/[a-z]+/[a-z0-9_-]+/$").expect("detail regex"))
        .is_match(path)
}

/// 判断是否为站内剧集地址。
pub fn is_episode_path(path: &str) -> bool {
    static EPISODE_RE: std::sync::OnceLock<Regex> = std::sync::OnceLock::new();
    EPISODE_RE
        .get_or_init(|| {
            Regex::new(r"^/[a-z]+/[a-z0-9_-]+/(\d+)-(\d+)\.html$").expect("episode regex")
        })
        .is_match(path)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn list_url_matches_android_rules() {
        assert_eq!(
            build_list_url("dianying", 1, None, None, None),
            "/dianying/"
        );
        assert_eq!(
            build_list_url("dianying", 2, None, None, None),
            "/dianying/index-2.html"
        );
        assert_eq!(
            build_list_url("dianying", 1, Some("Dalu"), Some("2024"), Some("addtime")),
            "/vodlist/dianying__Dalu_2024__addtime_1.html"
        );
        assert_eq!(
            build_list_url("dianying", 1, None, None, Some("addtime")),
            "/vodlist/dianying_____addtime_1.html"
        );
        assert_eq!(
            build_list_url("dianying", 1, Some("Dalu"), None, None),
            "/vodlist/dianying__Dalu____1.html"
        );
    }

    #[test]
    fn search_url_encodes_and_pages() {
        assert_eq!(
            build_search_url("繁花", None, 1),
            "/search/?wd=%E7%B9%81%E8%8A%B1"
        );
        assert_eq!(
            build_search_url("繁花", Some(2), 2),
            "/search/%E7%B9%81%E8%8A%B1-2.html?cid=2"
        );
    }

    #[test]
    fn player_service_url_handles_protocol_relative() {
        assert_eq!(
            build_player_service_url(DEFAULT_SOURCE_BASE, "//php.playerla.com/mjplay/?id=", "ABC")
                .unwrap(),
            "https://php.playerla.com/mjplay/?id=ABC"
        );
    }
}
