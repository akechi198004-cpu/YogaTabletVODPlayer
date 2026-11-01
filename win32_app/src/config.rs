//! 配置、用户数据与本地路径。
//!
//! 配置使用 TOML，收藏/历史/搜索记录使用独立 JSON，避免用户手改配置时误删记录。

use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

use std::collections::HashSet;

use directories::ProjectDirs;
use serde::{Deserialize, Serialize};
use thiserror::Error;

use crate::models::{FavoriteItem, HistoryItem};

static TEMP_SEQ: AtomicU64 = AtomicU64::new(0);

const SEARCH_HISTORY_LIMIT: usize = 10;
const FAVORITE_LIMIT: usize = 200;
const HISTORY_LIMIT: usize = 100;

pub const DEFAULT_SOURCE_BASE: &str = "https://www.xingfan.cc";

/// 夜间铁灰蓝、白天浅灰蓝两套界面色。
#[derive(Clone, Copy, Debug, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ThemeMode {
    #[default]
    Night,
    Day,
}

/// 可持久化的连接与播放设置。
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct AppConfig {
    #[serde(default = "default_source_base")]
    pub source_base: String,
    #[serde(default)]
    pub vlc_path: String,
    #[serde(default)]
    pub theme: ThemeMode,
}

impl Default for AppConfig {
    fn default() -> Self {
        Self {
            source_base: default_source_base(),
            vlc_path: String::new(),
            theme: ThemeMode::Night,
        }
    }
}

fn default_source_base() -> String {
    DEFAULT_SOURCE_BASE.to_owned()
}

impl AppConfig {
    pub fn load_or_default(path: &Path) -> Result<Self, ConfigError> {
        if !path.exists() {
            return Ok(Self::default());
        }
        let content = fs::read_to_string(path)?;
        let mut config = toml::from_str::<Self>(content.trim_start_matches('\u{feff}'))?;
        config.source_base = config.source_base.trim().trim_end_matches('/').to_owned();
        if config.source_base.is_empty() {
            config.source_base = default_source_base();
        }
        Ok(config)
    }

    /// 原子保存：先写同目录临时文件，成功后再替换。
    pub fn save(&self, path: &Path) -> Result<(), ConfigError> {
        let content = toml::to_string_pretty(self)?;
        atomic_write(path, content.as_bytes())?;
        Ok(())
    }

    pub fn source_base_trimmed(&self) -> String {
        let value = self.source_base.trim().trim_end_matches('/');
        if value.is_empty() {
            DEFAULT_SOURCE_BASE.to_owned()
        } else {
            value.to_owned()
        }
    }

    pub fn vlc_path_trimmed(&self) -> &str {
        self.vlc_path.trim()
    }

    /// VLC 路径留空时尝试自动检测；发现后写入内存配置，用户保存时才落盘。
    pub fn ensure_vlc_path(&mut self) -> bool {
        if !self.vlc_path_trimmed().is_empty() {
            return true;
        }
        match crate::player::find_vlc() {
            Some(path) => {
                self.vlc_path = path.to_string_lossy().into_owned();
                true
            }
            None => false,
        }
    }
}

#[derive(Debug, Error)]
pub enum ConfigError {
    #[error("读取或写入配置文件失败：{0}")]
    Io(#[from] std::io::Error),
    #[error("配置文件格式错误：{0}")]
    TomlDe(#[from] toml::de::Error),
    #[error("序列化配置失败：{0}")]
    TomlSer(#[from] toml::ser::Error),
}

/// 收藏、播放历史与搜索历史。
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct UserData {
    #[serde(default)]
    pub favorites: Vec<FavoriteItem>,
    #[serde(default)]
    pub history: Vec<HistoryItem>,
    #[serde(default)]
    pub search_history: Vec<String>,
}

impl UserData {
    pub fn load(path: &Path) -> Result<Self, UserDataError> {
        if !path.exists() {
            return Ok(Self::default());
        }
        let content = fs::read_to_string(path)?;
        let mut data = serde_json::from_str::<Self>(content.trim_start_matches('\u{feff}'))?;
        data.normalize();
        Ok(data)
    }

    pub fn save(&self, path: &Path) -> Result<(), UserDataError> {
        let content = serde_json::to_vec_pretty(self)?;
        atomic_write(path, &content)?;
        Ok(())
    }

    fn normalize(&mut self) {
        let mut seen_search = HashSet::new();
        self.search_history
            .retain(|value| !value.trim().is_empty() && seen_search.insert(value.clone()));
        self.search_history.truncate(SEARCH_HISTORY_LIMIT);

        let mut seen_favorites = HashSet::new();
        self.favorites
            .retain(|item| seen_favorites.insert(item.url.clone()));
        self.favorites.truncate(FAVORITE_LIMIT);

        let mut seen_history = HashSet::new();
        self.history
            .retain(|item| seen_history.insert(item.url.clone()));
        self.history.truncate(HISTORY_LIMIT);
    }

    pub fn record_search(&mut self, keyword: &str) {
        let keyword = keyword.trim();
        if keyword.is_empty() {
            return;
        }
        self.search_history.retain(|item| item != keyword);
        self.search_history.insert(0, keyword.to_owned());
        self.search_history.truncate(SEARCH_HISTORY_LIMIT);
    }

    pub fn remove_search_at(&mut self, index: usize) {
        if index < self.search_history.len() {
            self.search_history.remove(index);
        }
    }

    pub fn clear_search_history(&mut self) {
        self.search_history.clear();
    }

    pub fn is_favorite(&self, url: &str) -> bool {
        self.favorites.iter().any(|item| item.url == url)
    }

    /// 返回 true 表示刚刚加入收藏。
    pub fn toggle_favorite(&mut self, item: FavoriteItem) -> bool {
        if let Some(index) = self
            .favorites
            .iter()
            .position(|current| current.url == item.url)
        {
            self.favorites.remove(index);
            false
        } else {
            self.favorites.insert(0, item);
            self.favorites.truncate(FAVORITE_LIMIT);
            true
        }
    }

    pub fn remove_favorite(&mut self, url: &str) {
        self.favorites.retain(|item| item.url != url);
    }

    pub fn clear_favorites(&mut self) {
        self.favorites.clear();
    }

    pub fn history_for(&self, url: &str) -> Option<&HistoryItem> {
        self.history.iter().find(|item| item.url == url)
    }

    /// 同一作品只保留最近一条记录，避免收藏页被同一剧的多集刷满。
    pub fn record_history(&mut self, item: HistoryItem) {
        self.history.retain(|current| current.url != item.url);
        self.history.insert(0, item);
        self.history.truncate(HISTORY_LIMIT);
    }

    pub fn remove_history(&mut self, url: &str) {
        self.history.retain(|item| item.url != url);
    }

    pub fn clear_history(&mut self) {
        self.history.clear();
    }
}

#[derive(Debug, Error)]
pub enum UserDataError {
    #[error("读取或写入用户数据失败：{0}")]
    Io(#[from] std::io::Error),
    #[error("用户数据格式错误：{0}")]
    Json(#[from] serde_json::Error),
}

/// 程序运行期间使用的目录。
#[derive(Clone, Debug)]
pub struct AppPaths {
    pub config_dir: PathBuf,
    pub config_file: PathBuf,
    pub user_file: PathBuf,
    pub cache_dir: PathBuf,
}

/// 路径优先级：
/// 1. `HM_VOD_HOME` 环境变量；
/// 2. 可执行文件同目录存在 `config.toml`（便携包）；
/// 3. 当前目录存在 `config.toml`（开发）；
/// 4. 系统应用数据目录。
pub fn app_paths() -> AppPaths {
    let exe_dir = std::env::current_exe()
        .ok()
        .and_then(|path| path.parent().map(PathBuf::from));
    let current_dir = std::env::current_dir().unwrap_or_else(|_| PathBuf::from("."));

    if let Some(root) = std::env::var_os("HM_VOD_HOME").map(PathBuf::from) {
        return paths_from_root(root);
    }
    if let Some(root) = exe_dir.filter(|dir| dir.join("config.toml").exists()) {
        return paths_from_root(root);
    }
    if current_dir.join("config.toml").exists() {
        return paths_from_root(current_dir);
    }
    if let Some(project) = ProjectDirs::from("com", "humble-ai", "HMVideoWin32") {
        return AppPaths {
            config_dir: project.config_dir().to_path_buf(),
            config_file: project.config_dir().join("config.toml"),
            user_file: project.config_dir().join("user_data.json"),
            cache_dir: project.cache_dir().to_path_buf(),
        };
    }
    paths_from_root(current_dir)
}

fn paths_from_root(root: PathBuf) -> AppPaths {
    AppPaths {
        config_file: root.join("config.toml"),
        user_file: root.join("user_data.json"),
        cache_dir: root.join("cache"),
        config_dir: root,
    }
}

pub fn ensure_dirs(paths: &AppPaths) -> std::io::Result<()> {
    fs::create_dir_all(&paths.config_dir)?;
    fs::create_dir_all(&paths.cache_dir)?;
    Ok(())
}

pub fn now_unix() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_secs() as i64)
        .unwrap_or(0)
}

/// 原子写文件。中断时最多留下临时文件，不会留下半个配置。
fn atomic_write(path: &Path, contents: &[u8]) -> std::io::Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let sequence = TEMP_SEQ.fetch_add(1, Ordering::Relaxed);
    let temp_path = path.with_extension(format!("tmp-{}-{}", std::process::id(), sequence));
    let result = (|| -> std::io::Result<()> {
        let mut file = fs::File::create(&temp_path)?;
        file.write_all(contents)?;
        file.sync_all()?;
        fs::rename(&temp_path, path)?;
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temp_path);
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_path(name: &str) -> PathBuf {
        std::env::temp_dir().join(format!(
            "hm-win32-config-{name}-{}-{}",
            std::process::id(),
            TEMP_SEQ.fetch_add(1, Ordering::Relaxed)
        ))
    }

    #[test]
    fn config_round_trip_and_source_base_normalized() {
        let path = temp_path("roundtrip");
        let config = AppConfig {
            source_base: " https://www.xingfan.cc/ ".to_owned(),
            vlc_path: "C:\\VLC\\vlc.exe".to_owned(),
            theme: ThemeMode::Day,
        };
        config.save(&path).expect("save");
        let loaded = AppConfig::load_or_default(&path).expect("load");
        assert_eq!(loaded.source_base, DEFAULT_SOURCE_BASE);
        assert_eq!(loaded.vlc_path, "C:\\VLC\\vlc.exe");
        assert_eq!(loaded.theme, ThemeMode::Day);
        let _ = fs::remove_file(path);
    }

    #[test]
    fn invalid_config_is_not_overwritten_on_load() {
        let path = temp_path("invalid");
        fs::write(&path, "source_base = [broken").expect("write");
        assert!(AppConfig::load_or_default(&path).is_err());
        assert_eq!(fs::read_to_string(&path).unwrap(), "source_base = [broken");
        let _ = fs::remove_file(path);
    }

    #[test]
    fn favorites_and_history_are_deduplicated() {
        let mut data = UserData::default();
        let favorite = FavoriteItem {
            url: "/a/".to_owned(),
            title: "A".to_owned(),
            poster: String::new(),
            category: None,
            year: None,
            area: None,
            added_at: 1,
        };
        assert!(data.toggle_favorite(favorite.clone()));
        assert!(!data.toggle_favorite(favorite));
        assert!(data.favorites.is_empty());

        for episode in 1..=3 {
            data.record_history(HistoryItem {
                url: "/a/".to_owned(),
                title: "A".to_owned(),
                poster: String::new(),
                category: None,
                year: None,
                area: None,
                line_name: None,
                episode_no: episode,
                episode_title: format!("第{episode}集"),
                episode_url: format!("/a/{episode}.html"),
                played_at: episode as i64,
            });
        }
        assert_eq!(data.history.len(), 1);
        assert_eq!(data.history[0].episode_no, 3);
    }

    #[test]
    fn search_history_is_trimmed_and_deduplicated() {
        let mut data = UserData::default();
        for index in 0..15 {
            data.record_search(&format!("关键词{index}"));
        }
        assert_eq!(data.search_history.len(), 10);
        data.record_search("关键词14");
        assert_eq!(data.search_history.len(), 10);
        assert_eq!(data.search_history[0], "关键词14");
    }
}
