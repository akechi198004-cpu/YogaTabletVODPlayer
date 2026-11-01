//! 海报卡片与虚拟化网格计算。

use eframe::egui::{
    self, Color32, FontId, Pos2, Rect, Rounding, Sense, Stroke, TextureHandle, Vec2,
};

use super::theme::colors;
use crate::models::WorkItem;

pub const CARD_GAP_Y: f32 = 12.0;
const TITLE_HEIGHT: f32 = 34.0;
const META_HEIGHT: f32 = 18.0;
const TITLE_GAP: f32 = 6.0;

/// 列数随宽度变化，最多六列；1280×800 平板正好保持较大的触控海报。
pub fn grid_metrics(available_width: f32) -> (usize, f32) {
    let gap = 10.0;
    let min_card = 132.0;
    let columns = ((available_width + gap) / (min_card + gap))
        .floor()
        .max(2.0) as usize;
    let columns = columns.clamp(2, 6);
    let card_w = (available_width - gap * (columns.saturating_sub(1) as f32)) / columns as f32;
    (columns, card_w.max(96.0))
}

pub fn card_height(width: f32) -> f32 {
    width * 1.5 + TITLE_GAP + TITLE_HEIGHT + META_HEIGHT
}

pub fn row_height(width: f32) -> f32 {
    card_height(width) + CARD_GAP_Y
}

pub fn visible_row_range(
    viewport_min_y: f32,
    viewport_max_y: f32,
    row_height: f32,
    row_count: usize,
    preload: usize,
) -> std::ops::Range<usize> {
    if row_count == 0 || row_height <= 0.0 {
        return 0..0;
    }
    let first = (viewport_min_y / row_height).floor().max(0.0) as usize;
    let last = ((viewport_max_y / row_height).ceil().max(0.0) as usize).min(row_count);
    let first = first.saturating_sub(preload);
    let last = last.saturating_add(preload).min(row_count);
    first..last
}

pub fn draw_poster_card(
    ui: &mut egui::Ui,
    width: f32,
    texture: Option<&TextureHandle>,
    item: &WorkItem,
    selected: bool,
) -> egui::Response {
    let height = card_height(width);
    let (rect, response) = ui.allocate_exact_size(Vec2::new(width, height), Sense::click());
    let poster_height = width * 1.5;
    let poster_rect = Rect::from_min_size(rect.min, Vec2::new(width, poster_height));
    let title_rect = Rect::from_min_size(
        Pos2::new(rect.left(), poster_rect.bottom() + TITLE_GAP),
        Vec2::new(width, TITLE_HEIGHT),
    );
    let meta_rect = Rect::from_min_size(
        Pos2::new(rect.left(), title_rect.bottom()),
        Vec2::new(width, META_HEIGHT),
    );

    paint_poster(ui, poster_rect, texture);
    if let Some(rank) = item.rank {
        paint_rank(ui, poster_rect, rank);
    }
    if let Some(score) = item
        .score
        .as_deref()
        .filter(|value| !value.is_empty() && *value != "0.0")
    {
        paint_score(ui, poster_rect, score);
    }
    if let Some(progress) = item.progress.as_deref().filter(|value| !value.is_empty()) {
        paint_progress_text(ui, poster_rect, progress);
    }

    let stroke_color = if selected {
        colors().accent
    } else if response.hovered() {
        colors().poster_edge
    } else {
        Color32::TRANSPARENT
    };
    if stroke_color != Color32::TRANSPARENT {
        ui.painter().rect_stroke(
            poster_rect,
            Rounding::same(8.0),
            Stroke::new(if selected { 2.5 } else { 1.8 }, stroke_color),
        );
    }

    let title_font = FontId::proportional(12.5);
    let title_galley =
        ui.fonts(|fonts| fonts.layout(item.title.clone(), title_font, colors().text, width));
    ui.painter().with_clip_rect(title_rect).galley(
        title_rect.left_top(),
        title_galley,
        colors().text,
    );

    let subtitle = item.subtitle();
    if !subtitle.is_empty() {
        let display = elide_simple(ui, &subtitle, width, 11.0);
        ui.painter().text(
            meta_rect.left_top(),
            egui::Align2::LEFT_TOP,
            display,
            FontId::proportional(11.0),
            colors().muted,
        );
    }

    response.on_hover_text(item.title.clone())
}

fn paint_poster(ui: &egui::Ui, rect: Rect, texture: Option<&TextureHandle>) {
    if let Some(texture) = texture {
        egui::Image::from_texture(texture)
            .rounding(8.0)
            .bg_fill(colors().poster)
            .paint_at(ui, rect);
    } else {
        ui.painter()
            .rect_filled(rect, Rounding::same(8.0), colors().poster);
        ui.painter().text(
            rect.center(),
            egui::Align2::CENTER_CENTER,
            "无图",
            FontId::proportional(15.0),
            colors().muted,
        );
    }
}

fn paint_rank(ui: &egui::Ui, poster_rect: Rect, rank: u32) {
    let color = if rank <= 3 {
        colors().accent
    } else {
        colors().window
    };
    let text_color = if rank <= 3 {
        colors().accent_text
    } else {
        colors().text
    };
    let badge = Rect::from_min_size(
        Pos2::new(poster_rect.left() + 7.0, poster_rect.top() + 7.0),
        Vec2::new(38.0, 22.0),
    );
    ui.painter().rect_filled(badge, Rounding::same(6.0), color);
    ui.painter().text(
        badge.center(),
        egui::Align2::CENTER_CENTER,
        format!("#{rank}"),
        FontId::proportional(11.0),
        text_color,
    );
}

fn paint_score(ui: &egui::Ui, poster_rect: Rect, score: &str) {
    let badge = Rect::from_min_size(
        Pos2::new(poster_rect.right() - 54.0, poster_rect.top() + 7.0),
        Vec2::new(47.0, 20.0),
    );
    ui.painter()
        .rect_filled(badge, Rounding::same(6.0), colors().badge_bg);
    ui.painter().text(
        badge.center(),
        egui::Align2::CENTER_CENTER,
        format!("★ {score}"),
        FontId::proportional(10.5),
        colors().badge_text,
    );
}

fn paint_progress_text(ui: &egui::Ui, poster_rect: Rect, progress: &str) {
    let bar = Rect::from_min_max(
        Pos2::new(poster_rect.left() + 1.0, poster_rect.bottom() - 25.0),
        Pos2::new(poster_rect.right() - 1.0, poster_rect.bottom() - 1.0),
    );
    ui.painter().rect_filled(
        bar,
        Rounding::same(5.0),
        Color32::from_rgba_unmultiplied(0, 0, 0, 0xb8),
    );
    let display = elide_simple(ui, progress, bar.width() - 8.0, 9.5);
    ui.painter().text(
        bar.center(),
        egui::Align2::CENTER_CENTER,
        display,
        FontId::proportional(9.5),
        Color32::WHITE,
    );
}

fn elide_simple(ui: &egui::Ui, text: &str, max_width: f32, size: f32) -> String {
    let font = FontId::proportional(size);
    let measure = |value: &str| {
        ui.fonts(|fonts| {
            fonts
                .layout_no_wrap(value.to_owned(), font.clone(), Color32::WHITE)
                .size()
                .x
        })
    };
    if measure(text) <= max_width {
        return text.to_owned();
    }
    let ellipsis = "…";
    let mut chars: Vec<char> = text.chars().collect();
    while !chars.is_empty() {
        chars.pop();
        let candidate: String = chars.iter().collect::<String>() + ellipsis;
        if measure(&candidate) <= max_width {
            return candidate;
        }
    }
    String::new()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn visible_range_depends_on_viewport_not_total_count() {
        let small = visible_row_range(0.0, 600.0, 200.0, 10, 1);
        let large = visible_row_range(0.0, 600.0, 200.0, 100_000, 1);
        assert_eq!(small, 0..4);
        assert_eq!(large, 0..4);
    }

    #[test]
    fn grid_columns_are_capped_at_six() {
        let (columns, _) = grid_metrics(2400.0);
        assert_eq!(columns, 6);
        let (columns, _) = grid_metrics(700.0);
        assert!((2..=5).contains(&columns));
    }

    #[test]
    fn card_height_matches_content() {
        let width = 120.0;
        assert!(
            (card_height(width) - (180.0 + TITLE_GAP + TITLE_HEIGHT + META_HEIGHT)).abs() < 0.01
        );
    }
}
