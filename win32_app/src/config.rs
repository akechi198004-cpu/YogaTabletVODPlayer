//! 配置、用户数据与本地路径。
//!
//! 配置使用 TOML，收藏/历史/搜索记录使用独立 JSON，避免用户手改配置时误删记录。

use std::fs;
use std::io::{ErrorKind, Write};
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

/// 播放后端。默认使用随包 mpv；外部 VLC 仅作兼容模式。
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum PlayerBackend {
    #[default]
    Embedded,
    ExternalVlc,
}

/// 可持久化的连接与播放设置。
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct AppConfig {
    #[serde(default = "default_source_base")]
    pub source_base: String,
    #[serde(default)]
    pub player_backend: PlayerBackend,
    #[serde(default)]
    pub vlc_path: String,
    #[serde(default = "default_embedded_volume")]
    pub embedded_volume: u8,
    #[serde(default)]
    pub theme: ThemeMode,
}

impl Default for AppConfig {
    fn default() -> Self {
        Self {
            source_base: default_source_base(),
            player_backend: PlayerBackend::default(),
            vlc_path: String::new(),
            embedded_volume: default_embedded_volume(),
            theme: ThemeMode::Night,
        }
    }
}

fn default_source_base() -> String {
    DEFAULT_SOURCE_BASE.to_owned()
}

fn default_embedded_volume() -> u8 {
    80
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
        config.embedded_volume = config.embedded_volume.min(100);
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
    /// 读取用户数据。`Ok(None)` 表示文件尚不存在（首次运行），
    /// `Err` 表示文件存在但读取/解析失败；调用方应保护原文件。
    pub fn load_optional(path: &Path) -> Result<Option<Self>, UserDataError> {
        // 直接读取：只有明确的 NotFound 才视为首次运行。
        // 权限、目录、设备错误等必须进入保护状态，不能用空数据覆盖。
        let content = match fs::read_to_string(path) {
            Ok(content) => content,
            Err(error) if error.kind() == ErrorKind::NotFound => return Ok(None),
            Err(error) => return Err(UserDataError::Io(error)),
        };
        let mut data = serde_json::from_str::<Self>(content.trim_start_matches('\u{feff}'))?;
        data.normalize();
        Ok(Some(data))
    }

    pub fn save(&self, path: &Path) -> Result<(), UserDataError> {
        let content = serde_json::to_vec_pretty(self)?;
        atomic_write(path, &content)?;
        Ok(())
    }

    /// 加载失败后禁止自动保存，避免用空数据覆盖原文件。
    /// 返回 false 表示因保护策略未写入。
    pub fn save_if_allowed(
        &self,
        path: &Path,
        allow_overwrite: bool,
    ) -> Result<bool, UserDataError> {
        if !allow_overwrite {
            return Ok(false);
        }
        self.save(path)?;
        Ok(true)
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

/// 检查用户数据路径是否存在。只有 NotFound 视为不存在，
/// 权限/元数据错误必须返回 Err，避免恢复流程误判后解除保护。
pub fn path_exists_checked(path: &Path) -> Result<bool, UserDataError> {
    match fs::symlink_metadata(path) {
        Ok(_) => Ok(true),
        Err(error) if error.kind() == ErrorKind::NotFound => Ok(false),
        Err(error) => Err(UserDataError::Io(error)),
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
            player_backend: PlayerBackend::ExternalVlc,
            vlc_path: "C:\\VLC\\vlc.exe".to_owned(),
            embedded_volume: 55,
            theme: ThemeMode::Day,
        };
        config.save(&path).expect("save");
        let loaded = AppConfig::load_or_default(&path).expect("load");
        assert_eq!(loaded.source_base, DEFAULT_SOURCE_BASE);
        assert_eq!(loaded.player_backend, PlayerBackend::ExternalVlc);
        assert_eq!(loaded.vlc_path, "C:\\VLC\\vlc.exe");
        assert_eq!(loaded.embedded_volume, 55);
        assert_eq!(loaded.theme, ThemeMode::Day);
        let _ = fs::remove_file(path);
    }

    #[test]
    fn missing_player_fields_default_to_embedded() {
        let path = temp_path("old-player-fields");
        fs::write(
            &path,
            "source_base = \"https://www.xingfan.cc\"\nvlc_path = \"\"\n",
        )
        .expect("write");
        let loaded = AppConfig::load_or_default(&path).expect("load");
        assert_eq!(loaded.player_backend, PlayerBackend::Embedded);
        assert_eq!(loaded.embedded_volume, 80);
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

    #[test]
    fn missing_user_data_is_distinct_from_corrupt_file() {
        let missing = temp_path("user-missing");
        assert!(UserData::load_optional(&missing)
            .expect("missing")
            .is_none());

        let corrupt = temp_path("user-corrupt");
        fs::write(&corrupt, b"{not-json").expect("write corrupt");
        assert!(UserData::load_optional(&corrupt).is_err());
        assert_eq!(fs::read(&corrupt).expect("original"), b"{not-json");
        let _ = fs::remove_file(corrupt);
    }

    #[test]
    fn directory_is_not_treated_as_missing_user_data() {
        let path = temp_path("user-dir");
        fs::create_dir(&path).expect("create dir");
        let error = UserData::load_optional(&path).expect_err("directory must be an error");
        match error {
            UserDataError::Io(error) => assert_ne!(error.kind(), ErrorKind::NotFound),
            other => panic!("unexpected error: {other}"),
        }

        let written = UserData::default()
            .save_if_allowed(&path, false)
            .expect("guarded save");
        assert!(!written);
        assert!(path.is_dir(), "I/O error must not remove or overwrite path");
        fs::remove_dir(&path).expect("remove dir");
    }

    #[test]
    fn invalid_path_is_not_treated_as_first_run() {
        let path = PathBuf::from("invalid\0path");
        assert!(path_exists_checked(&path).is_err());
        assert!(UserData::load_optional(&path).is_err());
    }

    #[test]
    fn path_state_only_treats_not_found_as_missing() {
        let missing = temp_path("state-missing");
        assert!(!path_exists_checked(&missing).expect("missing state"));

        let existing = temp_path("state-existing");
        fs::write(&existing, b"{}").expect("write");
        assert!(path_exists_checked(&existing).expect("existing state"));
        let _ = fs::remove_file(existing);
    }

    #[cfg(unix)]
    #[test]
    fn unreadable_parent_is_not_reported_missing() {
        use std::os::unix::fs::PermissionsExt;

        let dir = temp_path("user-perm-dir");
        fs::create_dir(&dir).expect("create dir");
        let file = dir.join("user_data.json");
        fs::write(&file, b"{}").expect("write user data");
        let original = fs::metadata(&dir).expect("metadata").permissions();
        fs::set_permissions(&dir, fs::Permissions::from_mode(0o000)).expect("chmod");

        let state = path_exists_checked(&file);
        let _load = UserData::load_optional(&file);

        fs::set_permissions(&dir, original).expect("restore chmod");
        assert!(
            !matches!(state, Ok(false)),
            "permission/metadata error must not be treated as missing"
        );
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn guarded_save_after_load_failure_keeps_original_file() {
        let path = temp_path("user-guard");
        fs::write(&path, b"{broken-user-data").expect("write corrupt");

        let load_failed = UserData::load_optional(&path).is_err();
        assert!(load_failed);

        let empty = UserData::default();
        let written = empty
            .save_if_allowed(&path, !load_failed)
            .expect("guarded save");
        assert!(!written);
        assert_eq!(fs::read(&path).expect("original"), b"{broken-user-data");

        // 用户显式恢复/重建后允许写入新文件。
        let written = empty.save_if_allowed(&path, true).expect("allowed save");
        assert!(written);
        assert!(UserData::load_optional(&path).expect("load new").is_some());
        let _ = fs::remove_file(path);
    }

    #[test]
    fn user_data_load_round_trip() {
        let path = temp_path("user-roundtrip");
        let data = UserData::default();
        data.save(&path).expect("save");
        let loaded = UserData::load_optional(&path)
            .expect("load")
            .expect("existing");
        assert!(loaded.favorites.is_empty());
        let _ = fs::remove_file(path);
    }
}
