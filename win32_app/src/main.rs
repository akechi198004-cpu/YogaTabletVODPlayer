//! HM影视 Windows 桌面版入口。
//!
//! 目标：老旧 Win32 32 位平板（内存约 2GB）上浏览星帆影视、搜索、选集，
//! 默认用随包 mpv 在应用窗口内播放，外部 VLC 作为兼容模式。
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod app;
mod cache;
mod config;
mod embedded;
mod models;
mod parser;
mod player;
mod source;
mod touch_keyboard;
mod ui;
mod worker;

use std::fs;

use eframe::egui::{self, FontData, FontDefinitions, FontFamily};

pub use config::AppPaths;

fn main() -> Result<(), eframe::Error> {
    let paths = config::app_paths();
    if let Err(error) = config::ensure_dirs(&paths) {
        eprintln!("failed to create app directories: {error}");
    }

    let native_options = eframe::NativeOptions {
        renderer: eframe::Renderer::Glow,
        viewport: egui::ViewportBuilder::default()
            .with_inner_size([1280.0, 800.0])
            .with_min_inner_size([960.0, 600.0])
            .with_title("HM 影视"),
        ..Default::default()
    };

    eframe::run_native(
        "HM 影视",
        native_options,
        Box::new(move |cc| {
            configure_fonts(&cc.egui_ctx);
            Box::new(app::App::new(cc, paths.clone()))
        }),
    )
}

fn configure_fonts(ctx: &egui::Context) {
    let Some(font_bytes) = load_cjk_font_bytes() else {
        return;
    };
    let mut fonts = FontDefinitions::default();
    fonts
        .font_data
        .insert("cjk_ui".to_owned(), FontData::from_owned(font_bytes));
    if let Some(family) = fonts.families.get_mut(&FontFamily::Proportional) {
        family.insert(0, "cjk_ui".to_owned());
    }
    if let Some(family) = fonts.families.get_mut(&FontFamily::Monospace) {
        family.push("cjk_ui".to_owned());
    }
    ctx.set_fonts(fonts);
}

/// 字体查找顺序：可执行文件同目录、当前目录、字体子目录，最后使用内置字体。
fn load_cjk_font_bytes() -> Option<Vec<u8>> {
    let mut candidates = Vec::new();
    if let Ok(exe) = std::env::current_exe() {
        if let Some(dir) = exe.parent() {
            candidates.push(dir.join("DroidSansFallbackFull.ttf"));
            candidates.push(dir.join("fonts/DroidSansFallbackFull.ttf"));
        }
    }
    candidates.push(std::path::PathBuf::from("./DroidSansFallbackFull.ttf"));
    candidates.push(std::path::PathBuf::from(
        "./fonts/DroidSansFallbackFull.ttf",
    ));
    for path in candidates {
        if let Ok(bytes) = fs::read(path) {
            return Some(bytes);
        }
    }
    Some(include_bytes!("../assets/DroidSansFallbackFull.ttf").to_vec())
}
