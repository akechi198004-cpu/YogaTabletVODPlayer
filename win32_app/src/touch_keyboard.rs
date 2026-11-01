//! Windows 触屏键盘。输入框获得焦点时尝试打开系统 TabTip。

#[cfg(target_os = "windows")]
pub fn show_touch_keyboard() {
    use std::path::PathBuf;
    use std::process::Command;

    let mut candidates = Vec::new();
    if let Some(common) = std::env::var_os("CommonProgramFiles") {
        candidates.push(PathBuf::from(common).join("microsoft shared/ink/TabTip.exe"));
    }
    if let Some(common) = std::env::var_os("CommonProgramFiles(x86)") {
        candidates.push(PathBuf::from(common).join("microsoft shared/ink/TabTip.exe"));
    }
    candidates.push(PathBuf::from(
        r"C:\Program Files\Common Files\microsoft shared\ink\TabTip.exe",
    ));
    candidates.push(PathBuf::from(
        r"C:\Program Files (x86)\Common Files\microsoft shared\ink\TabTip.exe",
    ));

    for candidate in candidates {
        if candidate.is_file() {
            let _ = Command::new(candidate).spawn();
            break;
        }
    }
}

#[cfg(not(target_os = "windows"))]
pub fn show_touch_keyboard() {}
