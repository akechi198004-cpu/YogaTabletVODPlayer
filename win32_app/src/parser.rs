//! 星帆影视 HTML 解析。
//!
//! 站点结构可能调整；解析失败时返回空结果而不是 panic，界面会展示可重试的错误。

use std::collections::{HashMap, HashSet};
use std::sync::OnceLock;

use regex::Regex;
use scraper::{ElementRef, Html, Selector};
use serde_json::Value;

use crate::models::{
    ChipOption, Episode, FilterOptions, HeroSlide, HomeData, PageResult, PlayLine, TopBoards,
    WorkDetail, WorkItem, WorkLine,
};
use crate::source::{is_detail_path, is_episode_path, ALL_CATEGORIES, DEFAULT_SOURCE_BASE};

const EMPTY_STATE_TEXTS: &[&str] = &[
    "没有找到相关内容",
    "未找到相关内容",
    "暂无相关内容",
    "没有相关结果",
    "暂无结果",
];

fn sel(value: &str) -> Selector {
    Selector::parse(value).unwrap_or_else(|_| panic!("invalid selector: {value}"))
}

/// 收集元素文字。保留原文空格，避免中文被强行拆开。
fn text_of(element: &ElementRef<'_>) -> String {
    let raw: String = element.text().collect::<Vec<_>>().join("");
    raw.split_whitespace().collect::<Vec<_>>().join(" ")
}

fn attr(element: &ElementRef<'_>, name: &str) -> String {
    element.value().attr(name).unwrap_or("").trim().to_owned()
}

fn first_text(root: &ElementRef<'_>, selector: &Selector) -> Option<String> {
    root.select(selector)
        .next()
        .map(|element| text_of(&element))
        .filter(|value| !value.is_empty())
}

fn first_attr(root: &ElementRef<'_>, selector: &Selector, name: &str) -> Option<String> {
    root.select(selector)
        .next()
        .map(|element| attr(&element, name))
        .filter(|value| !value.is_empty())
}

fn detail_title(root: &ElementRef<'_>) -> String {
    let p_sel = sel(r#"[class~="p-2.5"] p"#);
    first_text(root, &p_sel)
        .or_else(|| first_attr(root, &sel("img"), "alt"))
        .unwrap_or_default()
}

pub fn parse_home_page(html: &str) -> HomeData {
    let document = Html::parse_document(html);
    let root = document.root_element();
    let mut hero = Vec::new();
    let slide_sel = sel("[data-hero-slide]");
    let title_sel = sel("h1, h2");
    let img_sel = sel("img");
    let vtag_sel = sel(r#"[class~="proto-vtag"]"#);
    let anchor_sel = sel("a[href]");
    let year_re = year_regex();

    for element in root.select(&slide_sel) {
        let poster = first_attr(&element, &img_sel, "src").unwrap_or_default();
        let alt = first_attr(&element, &img_sel, "alt").unwrap_or_default();
        let title = first_text(&element, &title_sel).unwrap_or(alt);
        if title.is_empty() {
            continue;
        }

        let mut category = None;
        let mut year = None;
        for tag in element.select(&vtag_sel) {
            let value = text_of(&tag);
            if year_re.is_match(&value) {
                year = Some(value);
            } else if !value.is_empty() {
                category = Some(value);
            }
        }

        let mut play_url = None;
        let mut url = None;
        for anchor in element.select(&anchor_sel) {
            let href = attr(&anchor, "href");
            if href.ends_with(".html") {
                play_url = Some(href);
            } else if is_detail_path(&href) {
                url = Some(href);
            }
        }
        let Some(url) = url else { continue };
        hero.push(HeroSlide {
            title,
            poster,
            url,
            play_url,
            category,
            year,
        });
    }

    let mut recent = Vec::new();
    let article_sel = sel("article.proto-rim");
    let article_anchor_sel = sel("a[href]");
    let span_sel = sel(r#"[class~="p-2.5"] span"#);
    let p_sel = sel("p");
    let progress_re = progress_regex();
    let score_re = score_regex();

    for article in root.select(&article_sel) {
        let Some(anchor) = article.select(&article_anchor_sel).next() else {
            continue;
        };
        let url = attr(&anchor, "href");
        if !is_detail_path(&url) {
            continue;
        }
        let poster = first_attr(&article, &img_sel, "src").unwrap_or_default();
        let alt = first_attr(&article, &img_sel, "alt").unwrap_or_default();
        let title = detail_title(&article);
        let title = if title.is_empty() { alt } else { title };
        let title = if title.is_empty() {
            attr(&article, "aria-label")
        } else {
            title
        };
        if title.is_empty() {
            continue;
        }

        let mut category = None;
        let mut score = None;
        for span in article.select(&span_sel) {
            let value = text_of(&span);
            if score_re.is_match(&value) {
                score = Some(value);
            } else if !value.is_empty() {
                category = Some(value);
            }
        }

        let mut progress = None;
        for paragraph in article.select(&p_sel) {
            let value = text_of(&paragraph);
            if progress_re.is_match(&value) {
                progress = Some(value);
                break;
            }
        }

        recent.push(WorkItem {
            title,
            url,
            poster,
            category,
            year: None,
            area: None,
            progress,
            score,
            rank: None,
        });
    }

    HomeData { hero, recent }
}

pub fn parse_category_page(html: &str, requested_page: u32) -> PageResult {
    let document = Html::parse_document(html);
    let root = document.root_element();
    let card_sel = sel("a.proto-rim");
    let img_sel = sel("img");
    let span_sel = sel(r#"[class~="p-2.5"] span"#);
    let p_sel = sel("p");
    let year_re = year_regex();
    let progress_re = progress_regex();
    let mut items = Vec::new();
    let mut seen = HashSet::new();

    for card in root.select(&card_sel) {
        let url = attr(&card, "href");
        if !is_detail_path(&url) || !seen.insert(url.clone()) {
            continue;
        }
        let poster = first_attr(&card, &img_sel, "src").unwrap_or_default();
        let alt = first_attr(&card, &img_sel, "alt").unwrap_or_default();
        let title = detail_title(&card);
        let title = if title.is_empty() { alt } else { title };
        if title.is_empty() {
            continue;
        }

        let mut year = None;
        let mut area = None;
        for span in card.select(&span_sel) {
            let value = text_of(&span);
            if year_re.is_match(&value) {
                year = Some(value);
            } else if !value.is_empty() {
                area = Some(value);
            }
        }

        let mut progress = None;
        for paragraph in card.select(&p_sel) {
            let value = text_of(&paragraph);
            if progress_re.is_match(&value) {
                progress = Some(value);
                break;
            }
        }

        items.push(WorkItem {
            title,
            url,
            poster,
            category: None,
            year,
            area,
            progress,
            score: None,
            rank: None,
        });
    }

    let (page, total_pages) = parse_pagination(&document);
    let confirmed_empty = items.is_empty() && has_confirmed_empty_state(&document);
    let total_items = if confirmed_empty {
        Some(0)
    } else if items.is_empty() {
        None
    } else {
        parse_total_items(&document)
    };
    PageResult {
        items,
        page: if requested_page > 0 {
            requested_page
        } else {
            page
        },
        total_pages,
        total_items,
        filters: Some(parse_filter_options(&document)),
    }
}

pub fn parse_search_page(html: &str, requested_page: u32) -> PageResult {
    let document = Html::parse_document(html);
    let root = document.root_element();
    let card_sel = sel("a.rim-card");
    let img_sel = sel("img");
    let p_sel = sel("p");
    let vtag_sel = sel("span.vtag");
    let year_re = year_regex();
    let mut items = Vec::new();
    let mut seen = HashSet::new();

    for card in root.select(&card_sel) {
        let url = attr(&card, "href");
        if !is_detail_path(&url) || !seen.insert(url.clone()) {
            continue;
        }
        let poster = first_attr(&card, &img_sel, "src").unwrap_or_default();
        let alt = first_attr(&card, &img_sel, "alt").unwrap_or_default();
        let title = first_text(&card, &p_sel).unwrap_or(alt);
        if title.is_empty() {
            continue;
        }

        let mut category = None;
        for tag in card.select(&vtag_sel) {
            let value = text_of(&tag);
            if !value.is_empty() && !year_re.is_match(&value) {
                category = Some(value);
                break;
            }
        }

        let mut year = None;
        for paragraph in card.select(&p_sel) {
            let value = text_of(&paragraph);
            if year_re.is_match(&value) {
                year = Some(value);
                break;
            }
        }

        items.push(WorkItem {
            title,
            url,
            poster,
            category,
            year,
            area: None,
            progress: None,
            score: None,
            rank: None,
        });
    }

    let (page, total_pages) = parse_pagination(&document);
    let confirmed_empty = items.is_empty() && has_confirmed_empty_state(&document);
    let total_items = if confirmed_empty {
        Some(0)
    } else if items.is_empty() {
        None
    } else {
        parse_total_items(&document)
    };
    PageResult {
        items,
        page: if requested_page > 0 {
            requested_page
        } else {
            page
        },
        total_pages,
        total_items,
        filters: None,
    }
}

pub fn parse_daily_page(html: &str) -> Vec<WorkItem> {
    let document = Html::parse_document(html);
    let root = document.root_element();
    let card_sel = sel("a.update-card");
    let img_sel = sel("img");
    let p_sel = sel("p");
    let mut items = Vec::new();
    let mut seen = HashSet::new();

    for card in root.select(&card_sel) {
        let url = attr(&card, "href");
        if !is_detail_path(&url) || !seen.insert(url.clone()) {
            continue;
        }
        let poster = first_attr(&card, &img_sel, "src").unwrap_or_default();
        let alt = first_attr(&card, &img_sel, "alt").unwrap_or_default();
        let texts: Vec<String> = card.select(&p_sel).map(|p| text_of(&p)).collect();
        let title = texts
            .first()
            .cloned()
            .filter(|value| !value.is_empty())
            .unwrap_or(alt);
        if title.is_empty() {
            continue;
        }
        let category = texts
            .get(1)
            .cloned()
            .filter(|value| !value.is_empty() && !value.contains("更新"));
        items.push(WorkItem {
            title,
            url,
            poster,
            category,
            year: None,
            area: None,
            progress: None,
            score: None,
            rank: None,
        });
    }
    items
}

pub fn parse_top_page(html: &str) -> TopBoards {
    let document = Html::parse_document(html);
    let root = document.root_element();
    let box_sel = sel("div.space-y-2");
    let anchor_sel = sel("a.rank-row");
    let rank_sel = sel(".rank-hollow");
    let img_sel = sel("img");
    let p_sel = sel("p");
    let year_re = year_regex();
    let progress_re = progress_regex();
    let top_score_re = Regex::new(r"(?:评分|★)\s*([\d.]+)").expect("top score regex");

    let mut day = Vec::new();
    let mut week = Vec::new();
    let mut month = Vec::new();

    for board in root.select(&box_sel) {
        let show = attr(&board, "x-show");
        if !show.contains("activeCat==='all'") {
            continue;
        }
        let target = if show.contains("activeTime==='day'") {
            &mut day
        } else if show.contains("activeTime==='week'") {
            &mut week
        } else if show.contains("activeTime==='month'") {
            &mut month
        } else {
            continue;
        };

        for anchor in board.select(&anchor_sel) {
            let url = attr(&anchor, "href");
            if !is_detail_path(&url) {
                continue;
            }
            let fallback_rank = target.len() as u32 + 1;
            let rank = first_text(&anchor, &rank_sel)
                .and_then(|value| value.parse::<u32>().ok())
                .unwrap_or(fallback_rank);
            let poster = first_attr(&anchor, &img_sel, "src").unwrap_or_default();
            let alt = first_attr(&anchor, &img_sel, "alt").unwrap_or_default();
            let title = first_text(&anchor, &p_sel).unwrap_or(alt);
            if title.is_empty() {
                continue;
            }

            let mut category = None;
            let mut year = None;
            let mut area = None;
            let mut progress = None;
            let paragraphs: Vec<String> = anchor.select(&p_sel).map(|p| text_of(&p)).collect();
            if let Some(meta) = paragraphs.get(1) {
                for part in meta.split(|ch: char| ch == '·' || ch.is_whitespace()) {
                    let value = part.trim();
                    if value.is_empty() {
                        continue;
                    }
                    if year_re.is_match(value) {
                        year = Some(value.to_owned());
                    } else if progress_re.is_match(value) || value.contains('集') {
                        progress = Some(value.to_owned());
                    } else if category.is_none() {
                        category = Some(value.to_owned());
                    } else {
                        area = Some(value.to_owned());
                    }
                }
            }

            let all_text = text_of(&anchor);
            let score = top_score_re
                .captures(&all_text)
                .and_then(|captures| captures.get(1).map(|value| value.as_str().to_owned()));

            target.push(WorkItem {
                title,
                url,
                poster,
                category,
                year,
                area,
                progress,
                score,
                rank: Some(rank),
            });
        }
    }

    TopBoards { day, week, month }
}

pub fn parse_detail_page(html: &str, detail_url: &str) -> WorkDetail {
    let document = Html::parse_document(html);
    let root = document.root_element();
    let h1_sel = sel("h1");
    let og_title_sel = sel(r#"meta[property="og:title"]"#);
    let og_image_sel = sel(r#"meta[property="og:image"]"#);
    let twitter_image_sel = sel(r#"meta[name="twitter:image"]"#);
    let span_sel = sel("span");
    let vtag_sel = sel(".vtag, .proto-vtag");
    let p_sel = sel("p");
    let year_re = year_regex();
    let date_re = date_regex();
    let total_eps_re = total_episodes_regex();
    let line_button_sel = sel(r#"button[data-tab-group="detail-lines"]"#);
    let all_anchor_sel = sel(r#"a[href$=".html"]"#);
    let episode_re = episode_regex();
    let line_target_re = Regex::new(r"detail_line_(\d+)").expect("line target regex");

    let title = first_text(&root, &h1_sel)
        .or_else(|| first_attr(&root, &og_title_sel, "content"))
        .unwrap_or_default();
    let poster = first_attr(&root, &og_image_sel, "content")
        .or_else(|| first_attr(&root, &twitter_image_sel, "content"))
        .unwrap_or_default();

    let status_tags = ["热播中", "已完结", "连载中", "连载", "预告", "更新中"];
    let mut year = None;
    let mut area = None;
    for span in root.select(&span_sel) {
        let value = text_of(&span);
        if !value.contains('·') {
            continue;
        }
        for part in value.split('·') {
            let part = part.trim();
            if part.is_empty() || date_re.is_match(part) {
                continue;
            }
            if year_re.is_match(part) {
                if year.is_none() {
                    year = Some(part.to_owned());
                }
            } else if !part.chars().all(|ch| ch.is_ascii_digit())
                && !part.contains('集')
                && !status_tags.contains(&part)
                && area.is_none()
            {
                area = Some(part.to_owned());
            }
        }
    }

    let mut category = None;
    for tag in root.select(&vtag_sel) {
        let value = text_of(&tag);
        if value.is_empty()
            || year_re.is_match(&value)
            || status_tags.iter().any(|status| *status == value)
        {
            continue;
        }
        category = Some(value);
        break;
    }

    let mut description = None;
    for paragraph in root.select(&p_sel) {
        let value = text_of(&paragraph);
        if value.len() > 30 && value.starts_with('《') {
            description = Some(value);
            break;
        }
    }
    if description.is_none() {
        for paragraph in root.select(&p_sel) {
            let value = text_of(&paragraph);
            if value.len() > 30 && value.contains("剧情") {
                description = Some(value);
                break;
            }
        }
    }

    let total_episodes = total_eps_re
        .captures(html)
        .and_then(|captures| captures.get(1))
        .and_then(|value| value.as_str().parse::<u32>().ok());

    let mut lines = Vec::new();
    let mut seen_lines = HashSet::new();
    for button in root.select(&line_button_sel) {
        let target = attr(&button, "data-tab-target");
        let Some(captures) = line_target_re.captures(&target) else {
            continue;
        };
        let Some(id) = captures
            .get(1)
            .and_then(|value| value.as_str().parse::<u32>().ok())
        else {
            continue;
        };
        if !seen_lines.insert(id) {
            continue;
        }
        let name = {
            let value = text_of(&button);
            if value.is_empty() {
                format!("播放线路 {id}")
            } else {
                value
            }
        };

        let grid_selector = sel(&format!("#detail_grid_{id} a.episode-link"));
        let mut episodes = Vec::new();
        for anchor in root.select(&grid_selector) {
            let href = attr(&anchor, "href");
            let Some(captures) = episode_re.captures(&href) else {
                continue;
            };
            let line_id = captures
                .get(1)
                .and_then(|value| value.as_str().parse::<u32>().ok());
            let Some(no) = captures
                .get(2)
                .and_then(|value| value.as_str().parse::<u32>().ok())
            else {
                continue;
            };
            if line_id != Some(id) {
                continue;
            }
            let title = text_of(&anchor);
            let title = if title.is_empty() {
                format!("第{no:02}集")
            } else {
                title
            };
            episodes.push(Episode {
                no,
                title,
                url: href,
            });
        }
        episodes.sort_by_key(|episode| episode.no);
        lines.push(WorkLine { id, name, episodes });
    }

    if lines.is_empty() {
        let mut grouped: HashMap<u32, Vec<Episode>> = HashMap::new();
        for anchor in root.select(&all_anchor_sel) {
            let href = attr(&anchor, "href");
            if !is_episode_path(&href) {
                continue;
            }
            let Some(captures) = episode_re.captures(&href) else {
                continue;
            };
            let Some(line_id) = captures
                .get(1)
                .and_then(|value| value.as_str().parse::<u32>().ok())
            else {
                continue;
            };
            let no = captures
                .get(2)
                .and_then(|value| value.as_str().parse::<u32>().ok())
                .unwrap_or(1);
            let title = text_of(&anchor);
            let title = if title.is_empty() {
                format!("第{no:02}集")
            } else {
                title
            };
            grouped.entry(line_id).or_default().push(Episode {
                no,
                title,
                url: href,
            });
        }
        let mut ids: Vec<u32> = grouped.keys().copied().collect();
        ids.sort_unstable();
        for id in ids {
            let mut episodes = grouped.remove(&id).unwrap_or_default();
            let mut seen = HashSet::new();
            episodes.retain(|episode| seen.insert(episode.url.clone()));
            episodes.sort_by_key(|episode| episode.no);
            if !episodes.is_empty() {
                lines.push(WorkLine {
                    id,
                    name: format!("线路 {id}"),
                    episodes,
                });
            }
        }
    }

    WorkDetail {
        title: title.trim().to_owned(),
        url: detail_url.to_owned(),
        poster,
        category,
        year,
        area,
        description,
        total_episodes,
        lines,
    }
}

#[derive(Clone, Debug)]
pub struct ParsedPlayerConfig {
    pub url: String,
    pub apiurl: String,
    pub next: Option<String>,
    #[allow(dead_code)]
    pub name: Option<String>,
}

/// 从 `zanpiancms_player = {...}` 中提取播放器配置。使用花括号配对，避免被 JS 干扰。
pub fn extract_player_config(html: &str) -> Option<ParsedPlayerConfig> {
    let anchor = html.find("zanpiancms_player")?;
    let start = html[anchor..].find('{')? + anchor;
    let mut depth = 0i32;
    let mut in_string = false;
    let mut escaped = false;
    for (offset, ch) in html[start..].char_indices() {
        if in_string {
            if escaped {
                escaped = false;
            } else if ch == '\\' {
                escaped = true;
            } else if ch == '"' {
                in_string = false;
            }
            continue;
        }
        match ch {
            '"' => in_string = true,
            '{' => depth += 1,
            '}' => {
                depth -= 1;
                if depth == 0 {
                    let raw = &html[start..start + offset + 1];
                    let value: Value = serde_json::from_str(raw).ok()?;
                    let object = value.as_object()?;
                    let url = object.get("url")?.as_str()?.to_owned();
                    let apiurl = object.get("apiurl")?.as_str()?.to_owned();
                    let next = object
                        .get("next")
                        .and_then(Value::as_str)
                        .filter(|value| !value.is_empty())
                        .map(str::to_owned);
                    let name = object
                        .get("name")
                        .and_then(Value::as_str)
                        .filter(|value| !value.is_empty())
                        .map(str::to_owned);
                    if url.is_empty() || apiurl.is_empty() {
                        return None;
                    }
                    return Some(ParsedPlayerConfig {
                        url,
                        apiurl,
                        next,
                        name,
                    });
                }
            }
            _ => {}
        }
    }
    None
}

/// 从播放页 h1 中拆分作品名与集名。
pub fn parse_play_title(html: &str) -> (String, String) {
    let document = Html::parse_document(html);
    let root = document.root_element();
    let raw = first_text(&root, &sel("h1")).unwrap_or_default();
    if raw.is_empty() {
        return (String::new(), String::new());
    }
    let parts: Vec<&str> = raw
        .split('·')
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .collect();
    if parts.len() >= 2 && parts.last().is_some_and(|value| value.contains('集')) {
        (
            parts[..parts.len() - 1].join(" · "),
            parts[parts.len() - 1].to_owned(),
        )
    } else {
        (raw, String::new())
    }
}

pub fn extract_play_lines_from_player_html(html: &str, base_url: &str) -> Vec<PlayLine> {
    let document = Html::parse_document(html);
    let root = document.root_element();
    let mut lines = Vec::new();

    for anchor in root.select(&sel("#line-list a[data-url]")) {
        let url = normalize_play_url(&attr(&anchor, "data-url"), base_url);
        if !url.is_empty() {
            let name = {
                let value = text_of(&anchor);
                if value.is_empty() {
                    format!("线路 {}", lines.len() + 1)
                } else {
                    value
                }
            };
            lines.push(PlayLine { name, url });
        }
    }

    if lines.is_empty() {
        if let Some(raw) = extract_json_array_after(html, "quality:") {
            if let Ok(values) = serde_json::from_str::<Vec<Value>>(&raw) {
                for (index, value) in values.iter().enumerate() {
                    let url = value
                        .get("url")
                        .and_then(Value::as_str)
                        .map(|value| normalize_play_url(value, base_url))
                        .unwrap_or_default();
                    if url.is_empty() {
                        continue;
                    }
                    let name = value
                        .get("html")
                        .and_then(Value::as_str)
                        .filter(|value| !value.is_empty())
                        .map(str::to_owned)
                        .unwrap_or_else(|| format!("线路 {}", index + 1));
                    lines.push(PlayLine { name, url });
                }
            }
        }
    }

    if lines.is_empty() {
        static URL_RE: OnceLock<Regex> = OnceLock::new();
        let regex = URL_RE.get_or_init(|| {
            Regex::new(r#"(?i)url:\s*'([^']+\.(?:m3u8|mp4|m4v|flv)[^']*)'"#)
                .expect("play url regex")
        });
        if let Some(captures) = regex.captures(html) {
            if let Some(value) = captures.get(1) {
                let url = normalize_play_url(value.as_str(), base_url);
                if !url.is_empty() {
                    lines.push(PlayLine {
                        name: "极速源".to_owned(),
                        url,
                    });
                }
            }
        }
    }

    let mut seen = HashSet::new();
    lines.retain(|line| seen.insert(line.url.clone()));
    lines
}

fn normalize_play_url(value: &str, base_url: &str) -> String {
    let cleaned = value.trim().replace("\\/", "/");
    if cleaned.is_empty() {
        return String::new();
    }
    let base = if base_url.starts_with("http") {
        base_url
    } else {
        DEFAULT_SOURCE_BASE
    };
    UrlResolver::new(base)
        .and_then(|resolver| resolver.resolve(&cleaned))
        .unwrap_or(cleaned)
}

struct UrlResolver {
    base: url::Url,
}

impl UrlResolver {
    fn new(base: &str) -> Option<Self> {
        url::Url::parse(base).ok().map(|base| Self { base })
    }

    fn resolve(&self, value: &str) -> Option<String> {
        if value.starts_with("http://") || value.starts_with("https://") {
            return url::Url::parse(value).ok().map(|url| url.to_string());
        }
        self.base.join(value).ok().map(|url| url.to_string())
    }
}

/// 提取 `marker` 之后第一个 JSON 数组，支持字符串中的括号与转义。
fn extract_json_array_after(html: &str, marker: &str) -> Option<String> {
    let anchor = html.find(marker)?;
    let start = html[anchor..].find('[')? + anchor;
    let mut depth = 0i32;
    let mut in_string = false;
    let mut escaped = false;
    for (offset, ch) in html[start..].char_indices() {
        if in_string {
            if escaped {
                escaped = false;
            } else if ch == '\\' {
                escaped = true;
            } else if ch == '"' {
                in_string = false;
            }
            continue;
        }
        match ch {
            '"' => in_string = true,
            '[' => depth += 1,
            ']' => {
                depth -= 1;
                if depth == 0 {
                    return Some(html[start..start + offset + 1].to_owned());
                }
            }
            _ => {}
        }
    }
    None
}

fn parse_pagination(document: &Html) -> (u32, u32) {
    let root = document.root_element();
    let Some(pager) = root.select(&sel(".pagination-wrap")).next() else {
        return (1, 1);
    };
    let mut page = 1u32;
    let mut total_pages = 1u32;

    for span in pager.select(&sel("li.active span")) {
        if span
            .value()
            .classes()
            .any(|class| class.eq_ignore_ascii_case("num"))
        {
            continue;
        }
        if let Ok(value) = text_of(&span).parse::<u32>() {
            page = value;
        }
    }

    for anchor in pager.select(&sel(r#"a[data^="p-"]"#)) {
        let value = attr(&anchor, "data");
        if let Some(number) = value
            .strip_prefix("p-")
            .and_then(|number| number.parse::<u32>().ok())
        {
            total_pages = total_pages.max(number);
        }
    }

    if let Some(num) = first_text(&pager, &sel(".num")) {
        static NUM_RE: OnceLock<Regex> = OnceLock::new();
        let regex = NUM_RE.get_or_init(|| Regex::new(r"(\d+)\s*/\s*(\d+)").expect("num regex"));
        if let Some(captures) = regex.captures(&num) {
            page = captures
                .get(1)
                .and_then(|value| value.as_str().parse::<u32>().ok())
                .unwrap_or(1);
            let total = captures
                .get(2)
                .and_then(|value| value.as_str().parse::<u32>().ok())
                .unwrap_or(1);
            total_pages = total_pages.max(total);
        }
    }

    (page, total_pages.max(1))
}

fn parse_total_items(document: &Html) -> Option<u32> {
    let body = document.root_element();
    let text: String = body.text().collect::<Vec<_>>().join(" ");
    static TOTAL_RE: OnceLock<Regex> = OnceLock::new();
    let regex =
        TOTAL_RE.get_or_init(|| Regex::new(r"共\s*(\d+)\s*[条个部]").expect("total items regex"));
    regex
        .captures(&text)
        .and_then(|captures| captures.get(1))
        .and_then(|value| value.as_str().parse::<u32>().ok())
}

fn has_confirmed_empty_state(document: &Html) -> bool {
    let root = document.root_element();
    let main = root.select(&sel("main")).next().unwrap_or(root);
    if main
        .select(&sel("img.empty-img, .empty-img"))
        .any(|node| is_visible_result_node(&node))
    {
        return true;
    }
    for node in main.select(&sel("p, div, span")) {
        let text = text_of(&node);
        if EMPTY_STATE_TEXTS.iter().any(|value| *value == text) && is_visible_result_node(&node) {
            return true;
        }
    }
    false
}

/// 排除模板、脚本、隐藏的旧空态 DOM，避免把异常空页写进缓存。
fn is_visible_result_node(element: &ElementRef<'_>) -> bool {
    let mut current = Some(*element);
    while let Some(node) = current {
        let tag = node.value().name();
        if matches!(tag, "template" | "script" | "style" | "noscript") {
            return false;
        }
        if node.value().attr("hidden").is_some()
            || node
                .value()
                .attr("aria-hidden")
                .is_some_and(|value| value.eq_ignore_ascii_case("true"))
        {
            return false;
        }
        if node
            .value()
            .classes()
            .any(|class| class == "hidden" || class == "x-cloak")
        {
            return false;
        }
        let style = node
            .value()
            .attr("style")
            .unwrap_or("")
            .to_ascii_lowercase();
        let compact: String = style.chars().filter(|ch| !ch.is_whitespace()).collect();
        if compact.contains("display:none") || compact.contains("visibility:hidden") {
            return false;
        }
        current = node.parent().and_then(ElementRef::wrap);
    }
    true
}

fn parse_filter_options(document: &Html) -> FilterOptions {
    let root = document.root_element();
    let mut types = Vec::new();
    let mut areas = Vec::new();
    let mut years = Vec::new();
    let mut letters = Vec::new();
    let mut sorts = Vec::new();
    let mut seen_years = HashSet::new();
    let mut seen_letters = HashSet::new();
    let mut seen_sorts = HashSet::new();
    let mut seen_types = HashSet::new();
    let mut seen_areas = HashSet::new();
    let year_re = year_regex();

    for chip in root.select(&sel(r#"a.f-chip[href^="/vodlist/"]"#)) {
        let label = text_of(&chip);
        let href = attr(&chip, "href");
        if label.is_empty() || label.starts_with("全部") {
            continue;
        }
        if year_re.is_match(&label) {
            if seen_years.insert(label.clone()) {
                years.push(ChipOption {
                    label: label.clone(),
                    value: label,
                });
            }
            continue;
        }
        if label.len() == 1 && label.chars().all(|ch| ch.is_ascii_uppercase()) {
            if seen_letters.insert(label.clone()) {
                letters.push(ChipOption {
                    label: label.clone(),
                    value: label,
                });
            }
            continue;
        }
        let Some(parts) = parse_vodlist_parts(&href) else {
            continue;
        };
        if !parts.type_.is_empty() {
            if seen_types.insert(parts.type_.clone()) {
                types.push(ChipOption {
                    label,
                    value: parts.type_,
                });
            }
        } else if !parts.area.is_empty() && seen_areas.insert(parts.area.clone()) {
            areas.push(ChipOption {
                label,
                value: parts.area,
            });
        }
    }

    for sort in root.select(&sel(r#"a.sort-btn[href^="/vodlist/"]"#)) {
        let label = text_of(&sort);
        let href = attr(&sort, "href");
        let Some(parts) = parse_vodlist_parts(&href) else {
            continue;
        };
        if !parts.sort.is_empty() && seen_sorts.insert(parts.sort.clone()) {
            sorts.push(ChipOption {
                label,
                value: parts.sort,
            });
        }
    }

    FilterOptions {
        types,
        areas,
        years,
        letters,
        sorts,
    }
}

#[derive(Clone, Debug)]
#[allow(dead_code)]
pub struct VodlistParts {
    pub cat: &'static str,
    pub type_: String,
    pub area: String,
    pub year: String,
    pub letter: String,
    pub sort: String,
    pub page: u32,
}

pub fn parse_vodlist_parts(href: &str) -> Option<VodlistParts> {
    static VODLIST_RE: OnceLock<Regex> = OnceLock::new();
    let regex = VODLIST_RE
        .get_or_init(|| Regex::new(r"^/vodlist/(.+)_(\d+)\.html$").expect("vodlist regex"));
    let path = href.split('?').next().unwrap_or(href);
    let captures = regex.captures(path)?;
    let encoded_fields = captures.get(1)?.as_str();
    let page = captures.get(2)?.as_str().parse::<u32>().ok()?;
    let mut categories: Vec<&'static str> = ALL_CATEGORIES
        .iter()
        .map(|category| category.slug)
        .collect();
    categories.sort_by_key(|slug| std::cmp::Reverse(slug.len()));
    categories.dedup();
    let cat = categories
        .into_iter()
        .find(|slug| encoded_fields == *slug || encoded_fields.starts_with(&format!("{slug}_")))?;
    let remainder = encoded_fields.strip_prefix(cat)?;
    let remainder = remainder.strip_prefix('_')?;
    let mut fields: Vec<String> = remainder.splitn(5, '_').map(str::to_owned).collect();
    while fields.len() < 5 {
        fields.push(String::new());
    }
    Some(VodlistParts {
        cat,
        type_: fields[0].clone(),
        area: fields[1].clone(),
        year: fields[2].clone(),
        letter: fields[3].clone(),
        sort: fields[4].clone(),
        page,
    })
}

fn year_regex() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| Regex::new(r"^\d{4}$").expect("year regex"))
}

fn date_regex() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| Regex::new(r"^\d{4}-\d{2}-\d{2}$").expect("date regex"))
}

fn score_regex() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| Regex::new(r"^\d+(\.\d+)?$").expect("score regex"))
}

fn progress_regex() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| {
        Regex::new(r"更新至\s*\d+\s*集|全\s*\d+\s*集|第\s*\d+\s*集(?:\s*/\s*共\s*\d+\s*集)?")
            .expect("progress regex")
    })
}

fn total_episodes_regex() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| Regex::new(r"共\s*(\d+)\s*集").expect("total episodes regex"))
}

fn episode_regex() -> &'static Regex {
    static RE: OnceLock<Regex> = OnceLock::new();
    RE.get_or_init(|| {
        Regex::new(r"^/[a-z]+/[a-z0-9_-]+/(\d+)-(\d+)\.html$").expect("episode regex")
    })
}

/// 分类筛选值对应的中文标签，供界面显示。
#[allow(dead_code)]
pub fn category_filter_label(parts: &VodlistParts) -> String {
    if !parts.cat.is_empty() {
        crate::source::get_category_label(parts.cat)
    } else {
        String::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const HOME_HTML: &str = r#"
    <main>
      <article class="proto-hero-slide" data-hero-slide>
        <img src="https://cdn.example/poster.jpg" alt="示例剧">
        <span class="proto-vtag">国产剧</span><span class="proto-vtag">2026</span>
        <a href="/guocanju/demo/1-1.html">立即播放</a>
        <a href="/guocanju/demo/">详情</a>
      </article>
      <article class="proto-rim">
        <a href="/guocanju/demo/"><img src="https://cdn.example/a.jpg" alt="示例剧"></a>
        <div class="p-2.5"><p>示例剧</p><span>国产剧</span><span>8.2</span><p>第12集 / 共24集</p></div>
      </article>
    </main>
    "#;

    const CATEGORY_HTML: &str = r#"
    <main><div data-infinite-root>
      <a href="/guocanju/demo/" class="proto-rim group">
        <img src="https://cdn.example/a.jpg" alt="示例剧">
        <div class="p-2.5"><p>示例剧</p><span>中国大陆</span><span>2026</span><p>全24集</p></div>
      </a>
    </div>
    <div class="pagination-wrap">
      <li class="active"><span>2</span></li>
      <li><a data="p-3" href="/dianshiju/index-3.html">3</a></li>
      <span class="num">2/3</span>
    </div>
    <span>共 48 部</span>
    <a class="f-chip" href="/vodlist/dianshiju__Dalu____1.html">大陆</a>
    <a class="f-chip" href="/vodlist/dianshiju___2024___1.html">2024</a>
    <a class="sort-btn" href="/vodlist/dianshiju_____hits_1.html">最热</a>
    </main>
    "#;

    const DETAIL_HTML: &str = r#"
    <html><head>
      <meta property="og:title" content="示例剧">
      <meta property="og:image" content="https://cdn.example/poster.jpg">
    </head><body>
      <h1>示例剧</h1>
      <span class="vtag">热播中</span>
      <span class="vtag">国产剧</span>
      <span>2026 · 中国大陆 · 24集 · 2026-01-02</span>
      <p>《示例剧》讲述了一个用于测试的剧情简介，长度足够超过三十个字符，确保解析器能够识别。</p>
      <button data-tab-group="detail-lines" data-tab-target="detail_line_1">云播</button>
      <div id="detail_grid_1"><a class="episode-link" href="/guocanju/demo/1-1.html">第01集</a><a class="episode-link" href="/guocanju/demo/1-2.html">第02集</a></div>
      <p>共 24 集</p>
    </body></html>
    "#;

    const PLAYER_HTML: &str = r#"
    <html><body>
      <ul id="line-list">
        <li><a data-url="https://cdn.example/a.m3u8">线路1</a></li>
        <li><a data-url="https://cdn.example/b.m3u8">线路2</a></li>
      </ul>
      <script>var art = new Artplayer({quality: [{"html":"线路3","url":"https:\/\/cdn.example\/c.m3u8"}], url: 'https://cdn.example/a.m3u8'});</script>
    </body></html>
    "#;

    #[test]
    #[ignore = "访问真实站点，需手动执行"]
    fn live_source_smoke() {
        use crate::source::{build_player_service_url, SourceClient};

        let client = SourceClient::new(DEFAULT_SOURCE_BASE).expect("client");
        let home_page = client
            .fetch_page_conditional("/", None, None, None)
            .expect("home");
        let home = parse_home_page(&home_page.html);
        println!("home hero={} recent={}", home.hero.len(), home.recent.len());
        assert!(!home.recent.is_empty());

        let category_page = client
            .fetch_page_conditional("/dianying/", None, None, None)
            .expect("category");
        let category = parse_category_page(&category_page.html, 1);
        println!(
            "category items={} pages={} total={:?}",
            category.items.len(),
            category.total_pages,
            category.total_items
        );
        assert!(!category.items.is_empty());

        let mut found = None;
        for item in category.items.iter().take(6) {
            let detail_page = client
                .fetch_page_conditional(&item.url, None, None, None)
                .expect("detail");
            let detail = parse_detail_page(&detail_page.html, &item.url);
            println!(
                "detail {} lines={} title={}",
                item.url,
                detail.lines.len(),
                detail.title
            );
            if let Some(line) = detail.lines.iter().find(|line| !line.episodes.is_empty()) {
                found = Some((item.url.clone(), line.episodes[0].url.clone()));
                break;
            }
        }
        let (detail_url, episode_url) = found.expect("a detail with episodes");
        let episode_page = client
            .fetch_page_conditional(&episode_url, Some(&detail_url), None, None)
            .expect("episode");
        let config = extract_player_config(&episode_page.html).expect("player config");
        let service = build_player_service_url(DEFAULT_SOURCE_BASE, &config.apiurl, &config.url)
            .expect("service url");
        let service_page = client
            .fetch_page_conditional(&service, Some(&episode_page.final_url), None, None)
            .expect("service");
        let lines =
            extract_play_lines_from_player_html(&service_page.html, &service_page.final_url);
        println!("play lines={}", lines.len());
        assert!(!lines.is_empty());

        let search_page = client
            .fetch_page_conditional("/search/?wd=%E7%B9%81%E8%8A%B1", None, None, None)
            .expect("search");
        let search = parse_search_page(&search_page.html, 1);
        println!(
            "search items={} pages={}",
            search.items.len(),
            search.total_pages
        );
        assert!(!search.items.is_empty());

        let top_page = client
            .fetch_page_conditional("/top.html", None, None, None)
            .expect("top");
        let top = parse_top_page(&top_page.html);
        println!(
            "top day={} week={} month={}",
            top.day.len(),
            top.week.len(),
            top.month.len()
        );
        assert!(!top.day.is_empty());

        let daily_page = client
            .fetch_page_conditional("/new.html", None, None, None)
            .expect("daily");
        let daily = parse_daily_page(&daily_page.html);
        println!("daily items={}", daily.len());
        assert!(!daily.is_empty());

        let filtered_page = client
            .fetch_page_conditional(
                "/vodlist/dianying__Dalu_2024__addtime_1.html",
                None,
                None,
                None,
            )
            .expect("filtered category");
        let filtered = parse_category_page(&filtered_page.html, 1);
        println!("filtered category items={}", filtered.items.len());
        assert!(!filtered.items.is_empty());

        let known_page = client
            .fetch_page_conditional("/oumeiju/heibanglingdidierji/", None, None, None)
            .expect("known detail");
        let known = parse_detail_page(&known_page.html, "/oumeiju/heibanglingdidierji/");
        println!(
            "known detail lines={} episodes={} year={:?} area={:?}",
            known.lines.len(),
            known
                .lines
                .iter()
                .map(|line| line.episodes.len())
                .max()
                .unwrap_or(0),
            known.year,
            known.area
        );
        assert!(!known.lines.is_empty());
        assert_ne!(known.area.as_deref(), Some("热播中"));
    }

    #[test]
    fn parses_home_hero_and_cards() {
        let home = parse_home_page(HOME_HTML);
        assert_eq!(home.hero.len(), 1);
        assert_eq!(home.hero[0].title, "示例剧");
        assert_eq!(home.hero[0].year.as_deref(), Some("2026"));
        assert_eq!(
            home.hero[0].play_url.as_deref(),
            Some("/guocanju/demo/1-1.html")
        );
        assert_eq!(home.recent.len(), 1);
        assert_eq!(home.recent[0].score.as_deref(), Some("8.2"));
        assert!(home.recent[0]
            .progress
            .as_deref()
            .unwrap()
            .contains("第12集"));
    }

    #[test]
    fn parses_category_pagination_and_filters() {
        let page = parse_category_page(CATEGORY_HTML, 2);
        assert_eq!(page.items.len(), 1);
        assert_eq!(page.total_pages, 3);
        assert_eq!(page.total_items, Some(48));
        let filters = page.filters.expect("filters");
        assert_eq!(filters.areas[0].value, "Dalu");
        assert_eq!(filters.years[0].value, "2024");
        assert_eq!(filters.sorts[0].value, "hits");
    }

    #[test]
    fn parses_detail_lines_and_episodes() {
        let detail = parse_detail_page(DETAIL_HTML, "/guocanju/demo/");
        assert_eq!(detail.title, "示例剧");
        assert_eq!(detail.year.as_deref(), Some("2026"));
        assert_eq!(detail.area.as_deref(), Some("中国大陆"));
        assert_eq!(detail.category.as_deref(), Some("国产剧"));
        assert_eq!(detail.total_episodes, Some(24));
        assert_eq!(detail.lines.len(), 1);
        assert_eq!(detail.lines[0].episodes.len(), 2);
        assert!(detail.description.unwrap().starts_with("《示例剧》"));
    }

    #[test]
    fn parses_player_lines_and_deduplicates() {
        let lines = extract_play_lines_from_player_html(PLAYER_HTML, "https://www.xingfan.cc/");
        assert_eq!(lines.len(), 2);
        assert_eq!(lines[0].name, "线路1");
        assert_eq!(lines[0].url, "https://cdn.example/a.m3u8");
    }

    #[test]
    fn extracts_player_config_with_escaped_slashes() {
        let html = r#"<script>zanpiancms_player = {"player":"\/","url":"ABC","next":"\/x\/y\/1-2.html","name":"juhe","apiurl":"\/\/php.example.com\/mjplay\/?id="};</script>"#;
        let config = extract_player_config(html).expect("config");
        assert_eq!(config.url, "ABC");
        assert_eq!(config.apiurl, "//php.example.com/mjplay/?id=");
        assert_eq!(config.next.as_deref(), Some("/x/y/1-2.html"));
    }

    #[test]
    fn parses_play_title() {
        let (work, episode) = parse_play_title("<h1>示例剧 <span>·</span> 第01集</h1>");
        assert_eq!(work, "示例剧");
        assert_eq!(episode, "第01集");
    }

    #[test]
    fn parses_vodlist_parts() {
        let parts = parse_vodlist_parts("/vodlist/dianying__Dalu_2024__addtime_1.html").unwrap();
        assert_eq!(parts.cat, "dianying");
        assert_eq!(parts.area, "Dalu");
        assert_eq!(parts.year, "2024");
        assert_eq!(parts.sort, "addtime");
        assert_eq!(parts.page, 1);
    }
}
