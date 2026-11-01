//! 外部 VLC 播放与线路显示名。
//!
//! 桌面版不嵌入 libVLC：进程启动 VLC，把媒体直链、Referer 和标题传过去。
//! 这样低配 Win32 平板可以把解码交给 VLC，避免浏览器/内嵌播放器的兼容问题。

use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};

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

/// 启动 VLC。返回后不等待播放器退出。
pub fn launch_vlc(
    vlc_path: &str,
    media_url: &str,
    media_title: &str,
    referer: Option<&str>,
) -> Result<(), PlayerError> {
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
        .map_err(PlayerError::LaunchFailed)?;
    Ok(())
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

    #[test]
    fn direct_media_detection_ignores_query_extension() {
        assert!(is_direct_media_url("https://cdn.example/a.m3u8?token=1"));
        assert!(is_direct_media_url("https://cdn.example/a.mp4"));
        assert!(!is_direct_media_url("https://www.xingfan.cc/a/1-1.html"));
    }
}
