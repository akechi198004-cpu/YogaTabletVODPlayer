//! 外部 VLC 播放与线路显示名。
//!
//! 桌面版不嵌入 libVLC：进程启动 VLC，把媒体直链、Referer 和标题传过去。
//! 这样低配 Win32 平板可以把解码交给 VLC，避免浏览器/内嵌播放器的兼容问题。

use std::fs;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};

use thiserror::Error;
use url::Url;

use crate::models::PlayLine;
use crate::source::USER_AGENT;

/// 在常见安装位置与 PATH 中查找 VLC。
pub fn find_vlc() -> Option<PathBuf> {
    if let Some(value) = std::env::var_os("HM_VLC_PATH") {
        let path = PathBuf::from(value);
        if path.is_file() {
            return Some(path);
        }
    }

    let mut candidates: Vec<PathBuf> = Vec::new();
    for key in ["ProgramFiles", "ProgramFiles(x86)", "ProgramW6432"] {
        if let Some(root) = std::env::var_os(key) {
            candidates.push(PathBuf::from(root).join("VideoLAN/VLC/vlc.exe"));
        }
    }
    candidates.push(PathBuf::from(r"C:\Program Files\VideoLAN\VLC\vlc.exe"));
    candidates.push(PathBuf::from(
        r"C:\Program Files (x86)\VideoLAN\VLC\vlc.exe",
    ));
    for path in ["/usr/bin/vlc", "/usr/local/bin/vlc", "/snap/bin/vlc"] {
        candidates.push(PathBuf::from(path));
    }
    for path in candidates {
        if path.is_file() {
            return Some(path);
        }
    }

    // 最后查 PATH；使用系统自带的 where/which，不引入额外依赖。
    #[cfg(target_os = "windows")]
    let lookup = ("where", "vlc.exe");
    #[cfg(not(target_os = "windows"))]
    let lookup = ("which", "vlc");
    let output = Command::new(lookup.0).arg(lookup.1).output().ok()?;
    if !output.status.success() {
        return None;
    }
    let stdout = String::from_utf8_lossy(&output.stdout);
    let first = stdout.lines().next()?.trim();
    if first.is_empty() {
        None
    } else {
        Some(PathBuf::from(first))
    }
}

/// 启动 VLC。返回子进程句柄，调用方负责在换集/退出时结束它。
pub fn launch_vlc(
    vlc_path: &str,
    media_url: &str,
    media_title: &str,
    referer: Option<&str>,
) -> Result<Child, PlayerError> {
    let executable = resolve_executable(vlc_path)?;
    let mut command = Command::new(&executable);

    if !media_title.trim().is_empty() {
        command
            .arg("--meta-title")
            .arg(media_title)
            .arg("--input-title-format")
            .arg(media_title);
    }
    if let Some(referer) = referer.filter(|value| !value.trim().is_empty()) {
        command.arg(format!("--http-referrer={referer}"));
    }
    command.arg(format!("--http-user-agent={USER_AGENT}"));
    command.arg(media_url);

    command
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .map_err(PlayerError::LaunchFailed)
}

/// 在用户选择的目录（或其上级目录）中查找 vlc.exe。
///
/// 先检查常见布局，再在最多两层子目录里查找，兼容便携版解压到自定义文件夹。
pub fn find_vlc_in_dir(dir: &Path) -> Option<PathBuf> {
    for candidate in [
        dir.join("vlc.exe"),
        dir.join("VLC").join("vlc.exe"),
        dir.join("VideoLAN").join("VLC").join("vlc.exe"),
    ] {
        if candidate.is_file() {
            return Some(candidate);
        }
    }

    let mut pending = vec![(dir.to_path_buf(), 0usize)];
    let mut visited = 0usize;
    while let Some((current, depth)) = pending.pop() {
        if depth >= 2 || visited >= 400 {
            continue;
        }
        let Ok(entries) = fs::read_dir(&current) else {
            continue;
        };
        for entry in entries.flatten() {
            visited += 1;
            let Ok(file_type) = entry.file_type() else {
                continue;
            };
            if !file_type.is_dir() {
                continue;
            }
            let child = entry.path();
            let candidate = child.join("vlc.exe");
            if candidate.is_file() {
                return Some(candidate);
            }
            pending.push((child, depth + 1));
        }
    }
    None
}

/// 校验路径确实是 vlc.exe（开发环境的 Linux VLC 允许无扩展名）。
pub fn validate_vlc_path(path: &str) -> Result<PathBuf, PlayerError> {
    let trimmed = path.trim().trim_matches('"').trim();
    if trimmed.is_empty() {
        return Err(PlayerError::MissingPath);
    }
    let path = PathBuf::from(trimmed);
    if !path.is_file() {
        return Err(PlayerError::MissingExecutable(trimmed.to_owned()));
    }
    let is_vlc = path
        .file_name()
        .and_then(|name| name.to_str())
        .is_some_and(|name| {
            name.eq_ignore_ascii_case("vlc.exe")
                || (!cfg!(target_os = "windows") && name.eq_ignore_ascii_case("vlc"))
        });
    if !is_vlc {
        return Err(PlayerError::NotVlc(trimmed.to_owned()));
    }
    Ok(path)
}

fn resolve_executable(vlc_path: &str) -> Result<PathBuf, PlayerError> {
    let trimmed = vlc_path.trim();
    if trimmed.is_empty() {
        return Err(PlayerError::MissingPath);
    }
    let path = Path::new(trimmed);
    if path.is_file() {
        return Ok(path.to_path_buf());
    }
    // 允许用户在设置里只写 `vlc` / `vlc.exe`，再从 PATH 查找。
    if path.components().count() == 1 {
        if let Some(found) = find_in_path(trimmed) {
            return Ok(found);
        }
    }
    Err(PlayerError::MissingExecutable(trimmed.to_owned()))
}

fn find_in_path(name: &str) -> Option<PathBuf> {
    let path = std::env::var_os("PATH")?;
    let names = if cfg!(target_os = "windows") && !name.to_ascii_lowercase().ends_with(".exe") {
        vec![name.to_owned(), format!("{name}.exe")]
    } else {
        vec![name.to_owned()]
    };
    for directory in std::env::split_paths(&path) {
        for candidate_name in &names {
            let candidate = directory.join(candidate_name);
            if candidate.is_file() {
                return Some(candidate);
            }
        }
    }
    None
}

/// 由线路 URL 推导简短源名，并对同名源按出现顺序编号。
/// 例如 `svipplay(1)`、`svipplay(2)`、`jisuzyv`。
pub fn line_labels(lines: &[PlayLine]) -> Vec<String> {
    let bases: Vec<String> = lines.iter().map(provider_label).collect();
    let mut totals = std::collections::HashMap::<String, usize>::new();
    for base in &bases {
        *totals.entry(base.clone()).or_default() += 1;
    }
    let mut seen = std::collections::HashMap::<String, usize>::new();
    bases
        .into_iter()
        .map(|base| {
            if totals.get(&base).copied().unwrap_or(0) > 1 {
                let next = seen.entry(base.clone()).or_default();
                *next += 1;
                format!("{base}({next})")
            } else {
                base
            }
        })
        .collect()
}

fn provider_label(line: &PlayLine) -> String {
    let Ok(url) = Url::parse(&line.url) else {
        return line.name.clone();
    };
    let Some(host) = url.host_str() else {
        return line.name.clone();
    };
    let host = host.trim_start_matches("www.").to_ascii_lowercase();
    if host.is_empty() {
        return line.name.clone();
    }
    if host
        .split('.')
        .all(|part| !part.is_empty() && part.chars().all(|ch| ch.is_ascii_digit()))
    {
        return host;
    }
    let parts: Vec<&str> = host.split('.').collect();
    if parts.len() < 2 {
        return host;
    }
    let second_level = parts[parts.len() - 2];
    let top_level = parts[parts.len() - 1];
    let common_second_levels = [
        "com", "net", "org", "gov", "edu", "co", "ac", "go", "or", "ne",
    ];
    let two_part_suffixes = ["cn", "uk", "jp", "kr", "tw", "hk"];
    if two_part_suffixes.contains(&top_level)
        && common_second_levels.contains(&second_level)
        && parts.len() >= 3
    {
        parts[parts.len() - 3].to_owned()
    } else {
        second_level.to_owned()
    }
}

/// 直接媒体地址：详情里少数剧集本身就是 m3u8/mp4，不必再解析播放页。
pub fn is_direct_media_url(url: &str) -> bool {
    let lower = url.trim().to_ascii_lowercase();
    if !(lower.starts_with("http://") || lower.starts_with("https://")) {
        return false;
    }
    let path = lower.split('?').next().unwrap_or(&lower);
    path.ends_with(".m3u8")
        || path.ends_with(".mp4")
        || path.ends_with(".m4v")
        || path.ends_with(".flv")
}

#[derive(Debug, Error)]
pub enum PlayerError {
    #[error("还没有配置 VLC 路径。请到设置中填写 vlc.exe 的完整路径。")]
    MissingPath,
    #[error("找不到 VLC 可执行文件：{0}")]
    MissingExecutable(String),
    #[error("所选文件不是 vlc.exe：{0}")]
    NotVlc(String),
    #[error("启动 VLC 失败：{0}")]
    LaunchFailed(std::io::Error),
}

#[cfg(test)]
mod tests {
    use super::*;

    fn line(name: &str, url: &str) -> PlayLine {
        PlayLine {
            name: name.to_owned(),
            url: url.to_owned(),
        }
    }

    #[test]
    fn labels_use_provider_and_number_duplicates() {
        let lines = vec![
            line("线路1", "https://six.svipplay.com/a.m3u8"),
            line("线路2", "https://six.svipplay.com/b.m3u8"),
            line("线路3", "https://vv.jisuzyv.com/play/c/index.m3u8"),
        ];
        assert_eq!(
            line_labels(&lines),
            vec!["svipplay(1)", "svipplay(2)", "jisuzyv"]
        );
    }

    fn temp_dir(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "hm-vod-player-test-{name}-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap_or_default()
                .as_nanos()
        ));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).expect("create temp dir");
        dir
    }

    #[test]
    fn finds_vlc_in_common_and_nested_layouts() {
        let root = temp_dir("find-vlc");
        let direct = root.join("direct");
        fs::create_dir_all(&direct).expect("create direct dir");
        let direct_exe = direct.join("vlc.exe");
        fs::write(&direct_exe, b"test").expect("write direct vlc");
        assert_eq!(find_vlc_in_dir(&direct), Some(direct_exe));

        let nested = root.join("nested");
        let video_lan_vlc = nested.join("VideoLAN").join("VLC");
        fs::create_dir_all(&video_lan_vlc).expect("create VideoLAN dir");
        let nested_exe = video_lan_vlc.join("vlc.exe");
        fs::write(&nested_exe, b"test").expect("write nested vlc");
        assert_eq!(find_vlc_in_dir(&nested), Some(nested_exe));

        let portable = root.join("portable");
        let portable_vlc = portable.join("apps").join("vlc-3.0.21");
        fs::create_dir_all(&portable_vlc).expect("create portable dir");
        let portable_exe = portable_vlc.join("vlc.exe");
        fs::write(&portable_exe, b"test").expect("write portable vlc");
        assert_eq!(find_vlc_in_dir(&portable), Some(portable_exe));

        let empty = root.join("empty");
        fs::create_dir_all(&empty).expect("create empty dir");
        assert_eq!(find_vlc_in_dir(&empty), None);
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn handles_chinese_and_space_paths() {
        let root = temp_dir("中文 路径");
        let install = root.join("VLC 安装 目录");
        fs::create_dir_all(&install).expect("create unicode dir");
        let exe = install.join("vlc.exe");
        fs::write(&exe, b"test").expect("write unicode vlc");
        assert_eq!(find_vlc_in_dir(&install), Some(exe.clone()));
        assert_eq!(
            validate_vlc_path(&exe.to_string_lossy()).expect("unicode vlc"),
            exe
        );
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn validates_vlc_executable_name() {
        let root = temp_dir("validate-vlc");
        let not_vlc = root.join("player.exe");
        fs::write(&not_vlc, b"test").expect("write other exe");
        assert!(matches!(
            validate_vlc_path(&not_vlc.to_string_lossy()),
            Err(PlayerError::NotVlc(_))
        ));

        let vlc = root.join("vlc.exe");
        fs::write(&vlc, b"test").expect("write vlc");
        assert_eq!(
            validate_vlc_path(&vlc.to_string_lossy()).expect("valid vlc"),
            vlc
        );
        assert!(matches!(
            validate_vlc_path(&root.join("missing.exe").to_string_lossy()),
            Err(PlayerError::MissingExecutable(_))
        ));
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn direct_media_detection_ignores_query_extension() {
        assert!(is_direct_media_url("https://cdn.example/a.m3u8?token=1"));
        assert!(is_direct_media_url("https://cdn.example/a.mp4"));
        assert!(!is_direct_media_url("https://www.xingfan.cc/a/1-1.html"));
    }
}
