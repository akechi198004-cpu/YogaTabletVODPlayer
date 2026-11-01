//! 界面配色：夜间深蓝灰、白天浅灰蓝，贴近 HM影视 CalmHome 风格。

use std::sync::atomic::{AtomicU8, Ordering};

use eframe::egui::{self, Color32, Rounding, Stroke, Vec2};

use crate::config::ThemeMode;

static MODE: AtomicU8 = AtomicU8::new(0);

#[derive(Clone, Copy)]
#[allow(dead_code)]
pub struct Palette {
    pub light: bool,
    pub bg: Color32,
    pub top: Color32,
    pub panel: Color32,
    pub field: Color32,
    pub muted: Color32,
    pub text: Color32,
    pub accent: Color32,
    pub accent_text: Color32,
    pub tab_active: Color32,
    pub tab_text: Color32,
    pub danger: Color32,
    pub danger_bg: Color32,
    pub online: Color32,
    pub line: Color32,
    pub hover: Color32,
    pub stroke: Color32,
    pub window: Color32,
    pub card: Color32,
    pub card_line: Color32,
    pub track: Color32,
    pub summary: Color32,
    pub veil: Color32,
    pub poster: Color32,
    pub poster_edge: Color32,
    pub badge_bg: Color32,
    pub badge_text: Color32,
    pub brand: Color32,
    pub brand_ink: Color32,
}

const NIGHT: Palette = Palette {
    light: false,
    bg: Color32::from_rgb(0x0b, 0x10, 0x20),
    top: Color32::from_rgb(0x0f, 0x17, 0x2a),
    panel: Color32::from_rgb(0x11, 0x1a, 0x2e),
    field: Color32::from_rgb(0x1b, 0x27, 0x40),
    muted: Color32::from_rgb(0x93, 0xa4, 0xbd),
    text: Color32::from_rgb(0xee, 0xf2, 0xff),
    accent: Color32::from_rgb(0x7a, 0xa2, 0xf7),
    accent_text: Color32::from_rgb(0x0b, 0x10, 0x20),
    tab_active: Color32::from_rgb(0x1d, 0x35, 0x57),
    tab_text: Color32::from_rgb(0xb6, 0xc9, 0xe2),
    danger: Color32::from_rgb(0xf8, 0x71, 0x71),
    danger_bg: Color32::from_rgb(0x3b, 0x1f, 0x2a),
    online: Color32::from_rgb(0x6e, 0xe7, 0xb7),
    line: Color32::from_rgb(0x24, 0x32, 0x4d),
    hover: Color32::from_rgb(0x1b, 0x29, 0x42),
    stroke: Color32::from_rgb(0x2a, 0x3b, 0x59),
    window: Color32::from_rgb(0x13, 0x1d, 0x33),
    card: Color32::from_rgb(0x12, 0x1c, 0x30),
    card_line: Color32::from_rgb(0x24, 0x32, 0x4d),
    track: Color32::from_rgb(0x22, 0x30, 0x4a),
    summary: Color32::from_rgb(0xc7, 0xd2, 0xe5),
    veil: Color32::from_rgba_premultiplied(0x03, 0x07, 0x12, 0xb8),
    poster: Color32::from_rgb(0x1a, 0x23, 0x36),
    poster_edge: Color32::from_rgb(0x3b, 0x82, 0xf6),
    badge_bg: Color32::from_rgba_premultiplied(0x10, 0x2a, 0x20, 0xe8),
    badge_text: Color32::from_rgb(0xa7, 0xf3, 0xd0),
    brand: Color32::from_rgb(0x6e, 0xa8, 0xfe),
    brand_ink: Color32::from_rgb(0x0b, 0x10, 0x20),
};

const DAY: Palette = Palette {
    light: true,
    bg: Color32::from_rgb(0xee, 0xf2, 0xf8),
    top: Color32::from_rgb(0xff, 0xff, 0xff),
    panel: Color32::from_rgb(0xff, 0xff, 0xff),
    field: Color32::from_rgb(0xff, 0xff, 0xff),
    muted: Color32::from_rgb(0x5b, 0x67, 0x7a),
    text: Color32::from_rgb(0x1e, 0x29, 0x3b),
    accent: Color32::from_rgb(0x25, 0x63, 0xeb),
    accent_text: Color32::from_rgb(0xff, 0xff, 0xff),
    tab_active: Color32::from_rgb(0xdb, 0xea, 0xfe),
    tab_text: Color32::from_rgb(0x1d, 0x4e, 0xd8),
    danger: Color32::from_rgb(0xdc, 0x26, 0x26),
    danger_bg: Color32::from_rgb(0xfe, 0xe2, 0xe2),
    online: Color32::from_rgb(0x16, 0xa3, 0x4a),
    line: Color32::from_rgb(0xd7, 0xde, 0xea),
    hover: Color32::from_rgb(0xf1, 0xf5, 0xfb),
    stroke: Color32::from_rgb(0xcb, 0xd5, 0xe1),
    window: Color32::from_rgb(0xff, 0xff, 0xff),
    card: Color32::from_rgb(0xff, 0xff, 0xff),
    card_line: Color32::from_rgb(0xdb, 0xe3, 0xef),
    track: Color32::from_rgb(0xdb, 0xe3, 0xef),
    summary: Color32::from_rgb(0x47, 0x55, 0x69),
    veil: Color32::from_rgba_premultiplied(0x0f, 0x17, 0x2a, 0x72),
    poster: Color32::from_rgb(0xe2, 0xe8, 0xf0),
    poster_edge: Color32::from_rgb(0x93, 0xc5, 0xfd),
    badge_bg: Color32::from_rgba_premultiplied(0xdc, 0xfc, 0xe7, 0xf0),
    badge_text: Color32::from_rgb(0x16, 0x65, 0x34),
    brand: Color32::from_rgb(0x25, 0x63, 0xeb),
    brand_ink: Color32::from_rgb(0xff, 0xff, 0xff),
};

pub fn set_mode(mode: ThemeMode) {
    MODE.store(
        match mode {
            ThemeMode::Night => 0,
            ThemeMode::Day => 1,
        },
        Ordering::Relaxed,
    );
}

pub fn mode() -> ThemeMode {
    if MODE.load(Ordering::Relaxed) == 1 {
        ThemeMode::Day
    } else {
        ThemeMode::Night
    }
}

pub fn colors() -> Palette {
    if mode() == ThemeMode::Day {
        DAY
    } else {
        NIGHT
    }
}

pub fn apply(ctx: &egui::Context) {
    let palette = colors();
    let mut visuals = if palette.light {
        egui::Visuals::light()
    } else {
        egui::Visuals::dark()
    };
    visuals.panel_fill = palette.bg;
    visuals.window_fill = palette.window;
    visuals.extreme_bg_color = palette.field;
    visuals.faint_bg_color = palette.field;
    visuals.code_bg_color = palette.field;
    visuals.widgets.noninteractive.bg_fill = palette.bg;
    visuals.widgets.noninteractive.fg_stroke = Stroke::new(1.0, palette.muted);
    visuals.widgets.inactive.bg_fill = palette.field;
    visuals.widgets.inactive.fg_stroke = Stroke::new(1.0, palette.text);
    visuals.widgets.inactive.rounding = Rounding::same(9.0);
    visuals.widgets.hovered.bg_fill = palette.hover;
    visuals.widgets.hovered.fg_stroke = Stroke::new(1.0, palette.text);
    visuals.widgets.hovered.rounding = Rounding::same(9.0);
    visuals.widgets.active.bg_fill = palette.tab_active;
    visuals.widgets.active.fg_stroke = Stroke::new(1.0, palette.text);
    visuals.widgets.active.rounding = Rounding::same(9.0);
    visuals.widgets.open.bg_fill = palette.field;
    visuals.widgets.open.rounding = Rounding::same(9.0);
    visuals.selection.bg_fill = palette.accent;
    visuals.selection.stroke = Stroke::new(1.0, palette.accent_text);
    visuals.window_rounding = Rounding::same(13.0);
    visuals.window_stroke = Stroke::new(1.0, palette.card_line);
    visuals.hyperlink_color = palette.accent;
    ctx.set_visuals(visuals);

    let mut style = (*ctx.style()).clone();
    style.spacing.item_spacing = Vec2::new(10.0, 8.0);
    style.spacing.button_padding = Vec2::new(14.0, 6.0);
    style.spacing.interact_size.y = 34.0;
    // 低配置平板不用过渡动画，减少每帧重绘。
    style.animation_time = 0.0;
    ctx.set_style(style);
}
