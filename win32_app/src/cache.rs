//! 本地缓存：海报原图与解析后的列表/详情 JSON。
//!
//! 低配置平板优先保证内存稳定：海报只缓存缩小后的纹理，原图落盘；
//! 元数据使用 ETag/Last-Modified 条件请求，旧缓存只在网络失败时兜底。

use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};
use thiserror::Error;

use crate::config::now_unix;

pub const POSTER_MAX_WIDTH: u32 = 180;
pub const POSTER_MAX_HEIGHT: u32 = 270;
pub const POSTER_CACHE_VERSION: &str = "v1";
pub const IMAGE_MAX_BYTES: u64 = 200 * 1024 * 1024;
pub const IMAGE_MAX_ENTRIES: usize = 3200;
pub const META_MAX_BYTES: u64 = 32 * 1024 * 1024;
pub const META_MAX_ENTRIES: usize = 1600;

static TEMP_SEQ: AtomicU64 = AtomicU64::new(0);

/// 缓存新鲜度。
#[derive(Clone, Copy, Debug)]
pub struct CachePolicy {
    pub fresh_secs: i64,
    pub max_stale_secs: i64,
}

impl CachePolicy {
    pub const HOME: Self = Self {
        fresh_secs: 5 * 60,
        max_stale_secs: 7 * 24 * 3600,
    };
    pub const LIST: Self = Self {
        fresh_secs: 15 * 60,
        max_stale_secs: 30 * 24 * 3600,
    };
    pub const SEARCH: Self = Self {
        fresh_secs: 10 * 60,
        max_stale_secs: 7 * 24 * 3600,
    };
    pub const DAILY: Self = Self {
        fresh_secs: 20 * 60,
        max_stale_secs: 3 * 24 * 3600,
    };
    pub const TOP: Self = Self {
        fresh_secs: 20 * 60,
        max_stale_secs: 7 * 24 * 3600,
    };
    pub const DETAIL: Self = Self {
        fresh_secs: 6 * 3600,
        max_stale_secs: 60 * 24 * 3600,
    };

    pub fn is_fresh(&self, age_secs: i64) -> bool {
        age_secs >= 0 && age_secs < self.fresh_secs
    }

    pub fn is_usable(&self, age_secs: i64) -> bool {
        age_secs >= 0 && age_secs < self.max_stale_secs
    }
}

/// 元数据缓存条目。
#[derive(Clone, Debug)]
pub struct CacheEntry<T> {
    pub data: T,
    pub saved_at: i64,
    pub etag: Option<String>,
    pub last_modified: Option<String>,
}

impl<T> CacheEntry<T> {
    pub fn age_secs(&self) -> i64 {
        now_unix().saturating_sub(self.saved_at)
    }
}

#[derive(Serialize, Deserialize)]
struct Envelope<T> {
    key: String,
    saved_at: i64,
    #[serde(default)]
    etag: Option<String>,
    #[serde(default)]
    last_modified: Option<String>,
    data: T,
}

/// JSON 元数据缓存。
#[derive(Clone, Debug)]
pub struct MetadataCache {
    root: PathBuf,
}

impl MetadataCache {
    pub fn new(root: PathBuf) -> Self {
        Self { root }
    }

    pub fn ensure_dir(&self) -> std::io::Result<()> {
        fs::create_dir_all(&self.root)
    }

    fn path_for(&self, key: &str) -> PathBuf {
        self.root.join(format!("{:016x}.json", stable_hash(key)))
    }

    pub fn load<T: DeserializeOwned>(&self, key: &str) -> Option<CacheEntry<T>> {
        let path = self.path_for(key);
        let content = fs::read(&path).ok()?;
        let envelope = serde_json::from_slice::<Envelope<T>>(&content).ok()?;
        if envelope.key != key {
            let _ = fs::remove_file(path);
            return None;
        }
        Some(CacheEntry {
            data: envelope.data,
            saved_at: envelope.saved_at,
            etag: envelope.etag,
            last_modified: envelope.last_modified,
        })
    }

    pub fn store<T: Serialize>(
        &self,
        key: &str,
        data: &T,
        etag: Option<&str>,
        last_modified: Option<&str>,
    ) -> Result<(), CacheError> {
        self.ensure_dir()?;
        let envelope = Envelope {
            key: key.to_owned(),
            saved_at: now_unix(),
            etag: etag.map(str::to_owned),
            last_modified: last_modified.map(str::to_owned),
            data,
        };
        let content = serde_json::to_vec(&envelope)?;
        atomic_write(&self.path_for(key), &content)?;
        Ok(())
    }

    /// 304 时只续期，不重写数据。
    pub fn touch(&self, key: &str, etag: Option<&str>, last_modified: Option<&str>) {
        let path = self.path_for(key);
        let Ok(content) = fs::read(&path) else {
            return;
        };
        let Ok(mut value) = serde_json::from_slice::<serde_json::Value>(&content) else {
            return;
        };
        if let Some(object) = value.as_object_mut() {
            object.insert("saved_at".to_owned(), serde_json::json!(now_unix()));
            if let Some(etag) = etag {
                object.insert("etag".to_owned(), serde_json::json!(etag));
            }
            if let Some(last_modified) = last_modified {
                object.insert("last_modified".to_owned(), serde_json::json!(last_modified));
            }
            if let Ok(bytes) = serde_json::to_vec(&value) {
                let _ = atomic_write(&path, &bytes);
            }
        }
    }

    pub fn remove(&self, key: &str) {
        let _ = fs::remove_file(self.path_for(key));
    }

    /// 启动时清理过大的元数据缓存。失败只记录，不影响使用。
    pub fn trim(&self) {
        trim_directory(&self.root, META_MAX_BYTES, META_MAX_ENTRIES);
    }
}

/// 海报文件缓存。
#[derive(Clone, Debug)]
pub struct PosterCache {
    root: PathBuf,
}

impl PosterCache {
    pub fn new(root: PathBuf) -> Self {
        Self { root }
    }

    pub fn ensure_dir(&self) -> std::io::Result<()> {
        fs::create_dir_all(&self.root)
    }

    /// 同一个 URL 只对应一个文件；URL 变化时自然使用新文件。
    pub fn poster_path(&self, poster_url: &str) -> PathBuf {
        self.root.join(format!(
            "{POSTER_CACHE_VERSION}-{:016x}.bin",
            stable_hash(poster_url)
        ))
    }

    pub fn has(&self, path: &Path) -> bool {
        path.is_file()
    }

    pub fn store(&self, path: &Path, bytes: &[u8]) -> Result<(), PosterError> {
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent)?;
        }
        atomic_write(path, bytes)?;
        Ok(())
    }

    pub fn read(&self, path: &Path) -> Result<DecodedPoster, PosterError> {
        let bytes = fs::read(path)?;
        decode_rgba(&bytes, POSTER_MAX_WIDTH, POSTER_MAX_HEIGHT)
    }

    pub fn decode(&self, bytes: &[u8]) -> Result<DecodedPoster, PosterError> {
        decode_rgba(bytes, POSTER_MAX_WIDTH, POSTER_MAX_HEIGHT)
    }

    pub fn remove(&self, path: &Path) {
        let _ = fs::remove_file(path);
    }

    pub fn trim(&self) {
        trim_directory(&self.root, IMAGE_MAX_BYTES, IMAGE_MAX_ENTRIES);
    }
}

/// 已解码、等待上传为 egui 纹理的海报像素。
#[derive(Clone, Debug)]
pub struct DecodedPoster {
    pub pixels: Vec<u8>,
    pub size: [usize; 2],
}

fn decode_rgba(
    bytes: &[u8],
    max_width: u32,
    max_height: u32,
) -> Result<DecodedPoster, PosterError> {
    let image = image::load_from_memory(bytes)?;
    let image = if image.width() > max_width || image.height() > max_height {
        image.resize(max_width, max_height, image::imageops::FilterType::Triangle)
    } else {
        image
    };
    let image = image.to_rgba8();
    let size = [image.width() as usize, image.height() as usize];
    Ok(DecodedPoster {
        pixels: image.into_raw(),
        size,
    })
}

#[derive(Debug, Error)]
pub enum PosterError {
    #[error("读取或写入海报缓存失败：{0}")]
    Io(#[from] std::io::Error),
    #[error("海报图片损坏：{0}")]
    Decode(#[from] image::ImageError),
}

impl PosterError {
    pub fn is_corrupt_image(&self) -> bool {
        matches!(self, Self::Decode(_))
    }
}

#[derive(Debug, Error)]
pub enum CacheError {
    #[error("缓存读写失败：{0}")]
    Io(#[from] std::io::Error),
    #[error("缓存序列化失败：{0}")]
    Json(#[from] serde_json::Error),
}

/// FNV-1a，稳定生成缓存文件名，不依赖进程随机哈希种子。
pub fn stable_hash(value: &str) -> u64 {
    let mut hash = 0xcbf29ce484222325u64;
    for byte in value.as_bytes() {
        hash ^= *byte as u64;
        hash = hash.wrapping_mul(0x100000001b3);
    }
    hash
}

fn atomic_write(path: &Path, contents: &[u8]) -> std::io::Result<()> {
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

/// 按容量与数量淘汰旧文件；只看文件修改时间，避免为每张图保存索引。
fn trim_directory(root: &Path, max_bytes: u64, max_entries: usize) {
    let Ok(read_dir) = fs::read_dir(root) else {
        return;
    };
    let mut files: Vec<(PathBuf, u64, SystemTime)> = Vec::new();
    for entry in read_dir.flatten() {
        let path = entry.path();
        if !path.is_file() {
            continue;
        }
        if path
            .file_name()
            .map(|name| name.to_string_lossy().contains(".tmp-"))
            .unwrap_or(false)
        {
            let _ = fs::remove_file(&path);
            continue;
        }
        let Ok(metadata) = entry.metadata() else {
            continue;
        };
        files.push((
            path,
            metadata.len(),
            metadata.modified().unwrap_or(UNIX_EPOCH),
        ));
    }
    let mut total: u64 = files.iter().map(|(_, size, _)| *size).sum();
    let mut count = files.len();
    if total <= max_bytes && count <= max_entries {
        return;
    }
    files.sort_by_key(|(_, _, modified)| *modified);
    for (path, size, _) in files {
        if total <= max_bytes && count <= max_entries {
            break;
        }
        if fs::remove_file(&path).is_ok() {
            total = total.saturating_sub(size);
            count = count.saturating_sub(1);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_root(name: &str) -> PathBuf {
        let root = std::env::temp_dir().join(format!(
            "hm-win32-cache-{name}-{}-{}",
            std::process::id(),
            TEMP_SEQ.fetch_add(1, Ordering::Relaxed)
        ));
        let _ = fs::remove_dir_all(&root);
        root
    }

    fn png_bytes(width: u32, height: u32) -> Vec<u8> {
        let image = image::RgbaImage::from_pixel(width, height, image::Rgba([30, 40, 50, 255]));
        let mut cursor = std::io::Cursor::new(Vec::new());
        image::DynamicImage::ImageRgba8(image)
            .write_to(&mut cursor, image::ImageFormat::Png)
            .expect("encode png");
        cursor.into_inner()
    }

    #[test]
    fn poster_path_is_stable_and_versioned() {
        let cache = PosterCache::new(PathBuf::from("/cache"));
        let first = cache.poster_path("https://cdn.example/a.jpg");
        let second = cache.poster_path("https://cdn.example/a.jpg");
        let other = cache.poster_path("https://cdn.example/b.jpg");
        assert_eq!(first, second);
        assert_ne!(first, other);
        assert!(first.to_string_lossy().contains(POSTER_CACHE_VERSION));
    }

    #[test]
    fn decode_shrinks_but_never_upscales() {
        let cache = PosterCache::new(PathBuf::new());
        let large = cache.decode(&png_bytes(400, 600)).expect("decode large");
        assert_eq!(large.size, [180, 270]);
        let small = cache.decode(&png_bytes(90, 135)).expect("decode small");
        assert_eq!(small.size, [90, 135]);
    }

    #[test]
    fn corrupt_image_is_reported() {
        let cache = PosterCache::new(PathBuf::new());
        let error = cache.decode(b"not-an-image").expect_err("must fail");
        assert!(error.is_corrupt_image());
    }

    #[test]
    fn metadata_round_trip_and_touch() {
        let root = temp_root("meta");
        let cache = MetadataCache::new(root.clone());
        cache
            .store("home", &vec![1, 2, 3], Some("etag"), Some("date"))
            .expect("store");
        let entry = cache.load::<Vec<i32>>("home").expect("load");
        assert_eq!(entry.data, vec![1, 2, 3]);
        assert_eq!(entry.etag.as_deref(), Some("etag"));
        cache.touch("home", Some("etag2"), None);
        let entry = cache.load::<Vec<i32>>("home").expect("load after touch");
        assert_eq!(entry.etag.as_deref(), Some("etag2"));
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn cache_policy_windows() {
        assert!(CachePolicy::HOME.is_fresh(10));
        assert!(!CachePolicy::HOME.is_fresh(600));
        assert!(CachePolicy::HOME.is_usable(600));
        assert!(!CachePolicy::HOME.is_usable(8 * 24 * 3600));
    }
}
