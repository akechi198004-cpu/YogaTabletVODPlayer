//! 界面绘制：顶栏、海报墙、分类/榜单/更新、收藏历史、详情与播放线路弹窗。

use std::collections::HashSet;

use eframe::egui::{
    self, Color32, Frame, Margin, RichText, Rounding, Sense, Stroke, TextureHandle, Vec2,
};

use crate::models::{
    ChipOption, Episode, FavoriteItem, FilterOptions, HomeData, MainTab, MineTab, SearchKind,
    WorkItem,
};
use crate::player;
use crate::source::{
    fallback_areas, fallback_sorts, fallback_years, get_category_label, ANIME_CATEGORIES,
    MOVIE_CATEGORIES, TV_CATEGORIES, VARIETY_CATEGORIES,
};
use crate::touch_keyboard;
use crate::ui::poster_grid;
use crate::ui::theme::colors;
use crate::worker::BrowseData;

use super::{App, ConfirmAction, SettingsDraft, TopPeriod, DAILY_PAGE_SIZE, GRID_PRELOAD_ROWS};

const PAD: f32 = 14.0;
const TOP_H: f32 = 56.0;
const STATUS_H: f32 = 30.0;
const DETAIL_POSTER_W: f32 = 176.0;
const DETAIL_POSTER_H: f32 = 264.0;
const MINE_ROW_H: f32 = 120.0;
const SEARCH_HISTORY_WIDTH: f32 = 310.0;

const HOT_KEYWORDS: &[&str] = &[
    "繁花",
    "漫长的季节",
    "三体",
    "庆余年",
    "狂飙",
    "奥本海默",
    "沙丘",
    "一人之下",
    "进击的巨人",
    "咒术回战",
];

impl App {
    pub(super) fn draw_ui(&mut self, ctx: &egui::Context) {
        self.draw_status(ctx);
        if self.show_settings {
            self.draw_settings(ctx);
            return;
        }

        self.draw_top_bar(ctx);
        egui::CentralPanel::default()
            .frame(
                Frame::none()
                    .fill(colors().bg)
                    .inner_margin(Margin::symmetric(PAD, 8.0)),
            )
            .show(ctx, |ui| {
                if self.detail.is_some() {
                    self.draw_detail(ui, ctx);
                } else if self.search_active {
                    self.draw_search(ui, ctx);
                } else {
                    match self.tab {
                        MainTab::Home => self.draw_home(ui, ctx),
                        MainTab::Category => self.draw_category(ui, ctx),
                        MainTab::Top => self.draw_top(ui, ctx),
                        MainTab::Daily => self.draw_daily(ui, ctx),
                        MainTab::Mine => self.draw_mine(ui, ctx),
                    }
                }
            });

        if self.show_search_history {
            self.draw_search_history(ctx);
        }
        if self.play.is_some() {
            self.draw_play_dialog(ctx);
        }
        if self.confirm.is_some() {
            self.draw_confirm(ctx);
        }
    }

    fn draw_top_bar(&mut self, ctx: &egui::Context) {
        let compact = ctx.screen_rect().width() < 1120.0;
        let mut next_tab = None;
        let mut submit = false;
        let mut clear = false;
        let mut toggle_theme = false;
        let mut open_settings = false;
        let mut toggle_history = false;

        egui::TopBottomPanel::top("top_bar")
            .exact_height(TOP_H)
            .frame(bar_frame(colors().top))
            .show(ctx, |ui| {
                ui.horizontal_centered(|ui| {
                    ui.spacing_mut().item_spacing.x = 8.0;
                    brand_mark(ui);
                    if !compact {
                        brand_titles(ui);
                    }
                    for tab in [
                        MainTab::Home,
                        MainTab::Category,
                        MainTab::Top,
                        MainTab::Daily,
                        MainTab::Mine,
                    ] {
                        let active = self.tab == tab && !self.search_active;
                        let label = if compact {
                            tab.compact_label()
                        } else {
                            tab.label()
                        };
                        if tab_button(ui, label, active).clicked() {
                            next_tab = Some(tab);
                        }
                    }

                    // 右侧控件固定占位，搜索框只占剩余空间。
                    // 预留值略大于控件实际宽度，避免 1024/1120 宽度下挤压。
                    let right_width = 190.0;
                    let search_width =
                        (ui.available_width() - right_width - 110.0).clamp(150.0, 340.0);
                    let response = ui.add_sized(
                        Vec2::new(search_width, 34.0),
                        egui::TextEdit::singleline(&mut self.search_text)
                            .hint_text("搜索影视…")
                            .margin(Margin::symmetric(8.0, 6.0)),
                    );
                    if response.gained_focus() {
                        touch_keyboard::show_touch_keyboard();
                    }
                    if response.has_focus() {
                        ui.ctx()
                            .send_viewport_cmd(egui::ViewportCommand::IMEAllowed(true));
                    }
                    if response.lost_focus()
                        && ui.input(|input| input.key_pressed(egui::Key::Enter))
                    {
                        submit = true;
                    }
                    if ui
                        .add_sized(Vec2::new(54.0, 34.0), egui::Button::new("搜索"))
                        .clicked()
                    {
                        submit = true;
                    }
                    if !self.search_text.is_empty()
                        && ui
                            .add_sized(
                                Vec2::new(24.0, 34.0),
                                egui::Button::new(RichText::new("×").size(16.0)).frame(false),
                            )
                            .clicked()
                    {
                        clear = true;
                    }

                    ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                        if icon_button(ui, "设置").clicked() {
                            open_settings = true;
                        }
                        let theme_label = match self.config.theme {
                            crate::config::ThemeMode::Night => "夜间",
                            crate::config::ThemeMode::Day => "白天",
                        };
                        if ui
                            .add_sized(Vec2::new(58.0, 34.0), egui::Button::new(theme_label))
                            .clicked()
                        {
                            toggle_theme = true;
                        }
                        if icon_button(ui, "历史").clicked() {
                            toggle_history = true;
                        }
                    });
                });
                edge_line(ui, true);
            });

        if let Some(tab) = next_tab {
            self.load_tab(tab);
        } else if clear {
            self.search_text.clear();
            if self.search_active {
                self.exit_search();
            }
        } else if submit {
            self.submit_search_text();
        }
        if toggle_theme {
            self.config.theme = match self.config.theme {
                crate::config::ThemeMode::Night => crate::config::ThemeMode::Day,
                crate::config::ThemeMode::Day => crate::config::ThemeMode::Night,
            };
            crate::ui::theme::set_mode(self.config.theme);
            crate::ui::theme::apply(ctx);
            self.save_config_silently();
        }
        if open_settings {
            self.open_settings();
        }
        if toggle_history {
            self.show_search_history = !self.show_search_history;
        }
    }

    fn draw_status(&self, ctx: &egui::Context) {
        let loading = self.browse.loading
            || self
                .detail
                .as_ref()
                .map(|view| view.loading)
                .unwrap_or(false)
            || self
                .play
                .as_ref()
                .map(|dialog| dialog.loading)
                .unwrap_or(false);
        let (dot, label) = if self.error_text.is_some() {
            (colors().danger, "注意")
        } else if loading {
            (colors().accent, "加载中")
        } else {
            (colors().online, "就绪")
        };
        egui::TopBottomPanel::bottom("status_bar")
            .exact_height(STATUS_H)
            .frame(bar_frame(colors().top))
            .show(ctx, |ui| {
                edge_line(ui, false);
                ui.horizontal_centered(|ui| {
                    let (rect, _) = ui.allocate_exact_size(Vec2::splat(12.0), Sense::hover());
                    ui.painter().circle_filled(rect.center(), 4.0, dot);
                    ui.label(RichText::new(label).size(11.0).color(colors().muted));
                    ui.separator();
                    ui.label(
                        RichText::new("HM 影视 · 外部 VLC 播放")
                            .size(11.0)
                            .color(colors().muted),
                    );
                    ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                        let (text, color) = if let Some(error) = &self.error_text {
                            (error.as_str(), colors().danger)
                        } else {
                            (self.status_text.as_str(), colors().muted)
                        };
                        let max_width = (ui.available_width() - 8.0).max(60.0);
                        let display =
                            elide_text(ui, text, max_width, egui::FontId::proportional(11.0));
                        let response = ui.label(RichText::new(&display).size(11.0).color(color));
                        if display != text {
                            response.on_hover_text(text);
                        }
                    });
                });
            });
    }

    fn draw_home(&mut self, ui: &mut egui::Ui, ctx: &egui::Context) {
        let data = match &self.browse.data {
            Some(BrowseData::Home(home)) => Some(home.clone()),
            _ => None,
        };
        let loading = self.browse.loading;
        let error = self.browse.error.clone();
        match data {
            Some(home) => self.draw_home_content(ui, ctx, home),
            None if loading => draw_loading(ui, "正在载入首页…"),
            None => {
                let retry = draw_error(ui, error.as_deref().unwrap_or("首页内容暂时无法读取。"));
                if retry {
                    self.reload_browse(true);
                }
            }
        }
    }

    fn draw_home_content(&mut self, ui: &mut egui::Ui, ctx: &egui::Context, home: HomeData) {
        let mut seen = HashSet::new();
        let slides: Vec<_> = home
            .hero
            .into_iter()
            .filter(|slide| seen.insert(slide.url.clone()))
            .take(8)
            .collect();
        if slides.is_empty() {
            self.hero_index = 0;
        } else {
            self.hero_index %= slides.len();
            self.draw_hero(ui, ctx, &slides);
        }

        let mut refresh = false;
        section_header(
            ui,
            "最近更新 · 热门影视",
            home.recent.len(),
            true,
            &mut refresh,
        );
        if refresh {
            self.reload_browse(true);
        }
        if home.recent.is_empty() {
            draw_empty(ui, "首页暂时没有推荐内容，稍后再来看看。");
            return;
        }
        if let Some(item) = self.draw_grid(ui, ctx, home.recent, false, ()) {
            self.open_detail(item);
        }
    }

    fn draw_hero(
        &mut self,
        ui: &mut egui::Ui,
        ctx: &egui::Context,
        slides: &[crate::models::HeroSlide],
    ) {
        let slide = &slides[self.hero_index];
        self.ensure_poster_texture(ctx, &slide.poster);
        let mut delta = 0i32;
        let mut open = false;
        Frame::none()
            .fill(colors().card)
            .stroke(Stroke::new(1.0, colors().card_line))
            .rounding(Rounding::same(13.0))
            .inner_margin(Margin::same(12.0))
            .show(ui, |ui| {
                ui.horizontal(|ui| {
                    poster_image(
                        ui,
                        self.texture_for(&slide.poster),
                        self.texture_size(&slide.poster),
                        96.0,
                        144.0,
                    );
                    ui.add_space(12.0);
                    ui.vertical(|ui| {
                        ui.spacing_mut().item_spacing.y = 5.0;
                        Frame::none()
                            .fill(colors().accent)
                            .rounding(Rounding::same(6.0))
                            .inner_margin(Margin::symmetric(8.0, 2.0))
                            .show(ui, |ui| {
                                ui.label(
                                    RichText::new("今日精选")
                                        .size(10.0)
                                        .strong()
                                        .color(colors().accent_text),
                                );
                            });
                        ui.label(
                            RichText::new(&slide.title)
                                .size(22.0)
                                .strong()
                                .color(colors().text),
                        );
                        let meta = [slide.category.as_deref(), slide.year.as_deref()]
                            .into_iter()
                            .flatten()
                            .collect::<Vec<_>>()
                            .join(" · ");
                        if !meta.is_empty() {
                            ui.label(RichText::new(meta).size(12.0).color(colors().muted));
                        }
                        ui.add_space(4.0);
                        if ui
                            .add_sized(
                                Vec2::new(104.0, 34.0),
                                egui::Button::new(
                                    RichText::new("进入详情")
                                        .size(13.0)
                                        .color(colors().accent_text),
                                )
                                .fill(colors().accent)
                                .rounding(Rounding::same(8.0)),
                            )
                            .clicked()
                        {
                            open = true;
                        }
                    });
                    ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                        if slides.len() > 1 {
                            if ui
                                .add_sized(Vec2::splat(36.0), egui::Button::new("〉"))
                                .clicked()
                            {
                                delta = 1;
                            }
                            if ui
                                .add_sized(Vec2::splat(36.0), egui::Button::new("〈"))
                                .clicked()
                            {
                                delta = -1;
                            }
                            ui.label(
                                RichText::new(format!("{}/{}", self.hero_index + 1, slides.len()))
                                    .size(11.0)
                                    .color(colors().muted),
                            );
                        }
                    });
                });
            });
        ui.add_space(8.0);
        self.touch_texture(&slide.poster);
        let mut protected = HashSet::new();
        protected.insert(slide.poster.clone());
        self.enforce_texture_budget(&protected);
        if delta != 0 {
            let count = slides.len() as i32;
            self.hero_index = ((self.hero_index as i32 + delta).rem_euclid(count)) as usize;
        }
        if open {
            self.open_detail(WorkItem {
                title: slide.title.clone(),
                url: slide.url.clone(),
                poster: slide.poster.clone(),
                category: slide.category.clone(),
                year: slide.year.clone(),
                area: None,
                progress: None,
                score: None,
                rank: None,
            });
        }
    }

    /// 虚拟化海报网格。只对可见行创建纹理请求，返回被点击的条目。
    fn draw_grid(
        &mut self,
        ui: &mut egui::Ui,
        ctx: &egui::Context,
        items: Vec<WorkItem>,
        show_rank: bool,
        grid_key: impl std::hash::Hash,
    ) -> Option<WorkItem> {
        let mut clicked = None;
        let mut visible_posters = HashSet::new();
        let mut seen_textures = Vec::new();
        let width = ui.available_width().max(160.0);
        let (columns, card_w) = poster_grid::grid_metrics(width);
        let row_h = poster_grid::row_height(card_w);
        let row_count = items.len().div_ceil(columns);
        let grid_id = self.browse.id;

        egui::ScrollArea::vertical()
            .id_source(("poster_grid", grid_id, grid_key))
            .auto_shrink([false, false])
            .show_viewport(ui, |ui, viewport| {
                ui.set_width(width);
                ui.set_height(row_count as f32 * row_h + 4.0);
                let range = poster_grid::visible_row_range(
                    viewport.min.y,
                    viewport.max.y,
                    row_h,
                    row_count,
                    GRID_PRELOAD_ROWS,
                );
                let origin = ui.min_rect().min;
                for row in range {
                    let y = origin.y + row as f32 * row_h;
                    let row_rect =
                        egui::Rect::from_min_size(egui::pos2(origin.x, y), Vec2::new(width, row_h));
                    let mut row_ui =
                        ui.child_ui(row_rect, egui::Layout::left_to_right(egui::Align::TOP));
                    row_ui.spacing_mut().item_spacing.x = 10.0;
                    for column in 0..columns {
                        let index = row * columns + column;
                        if index >= items.len() {
                            break;
                        }
                        let item = &items[index];
                        self.ensure_poster_texture(ctx, &item.poster);
                        if !item.poster.is_empty() {
                            visible_posters.insert(item.poster.clone());
                        }
                        let texture = self.texture_for(&item.poster);
                        let display = if show_rank {
                            item.clone()
                        } else {
                            let mut item = item.clone();
                            item.rank = None;
                            item
                        };
                        let selected = self.selected_url.as_deref() == Some(item.url.as_str());
                        if poster_grid::draw_poster_card(
                            &mut row_ui,
                            card_w,
                            texture,
                            &display,
                            selected,
                        )
                        .clicked()
                        {
                            clicked = Some(item.clone());
                        }
                        if texture.is_some() {
                            seen_textures.push(item.poster.clone());
                        }
                    }
                }
            });

        for key in seen_textures {
            self.touch_texture(&key);
        }
        self.enforce_texture_budget(&visible_posters);
        clicked
    }

    fn draw_category(&mut self, ui: &mut egui::Ui, ctx: &egui::Context) {
        let filters = self
            .category_filters
            .clone()
            .unwrap_or_else(|| FilterOptions {
                areas: fallback_areas(),
                years: fallback_years(),
                sorts: fallback_sorts(),
                ..FilterOptions::default()
            });
        let mut next_main: Option<&'static str> = None;
        let mut next_slug: Option<&'static str> = None;
        let mut reset = false;

        ui.horizontal_wrapped(|ui| {
            ui.spacing_mut().item_spacing.x = 6.0;
            for (key, label) in [
                ("movie", "电影专区"),
                ("tv", "电视剧场"),
                ("anime", "动漫新番"),
                ("variety", "综艺娱乐"),
            ] {
                if chip_button(ui, label, self.category.main_type == key).clicked() {
                    next_main = Some(key);
                }
            }
        });

        let subcategories = match self.category.main_type {
            "tv" => TV_CATEGORIES,
            "anime" => ANIME_CATEGORIES,
            "variety" => VARIETY_CATEGORIES,
            _ => MOVIE_CATEGORIES,
        };
        ui.horizontal_wrapped(|ui| {
            ui.spacing_mut().item_spacing.x = 5.0;
            for category in subcategories {
                if chip_button(ui, category.label, self.category.cat_slug == category.slug)
                    .clicked()
                {
                    next_slug = Some(category.slug);
                }
            }
        });

        let areas = if filters.areas.is_empty() {
            fallback_areas()
        } else {
            filters.areas.clone()
        };
        let years = if filters.years.is_empty() {
            fallback_years()
        } else {
            filters.years.clone()
        };
        let sorts = if filters.sorts.is_empty() {
            fallback_sorts()
        } else {
            filters.sorts.clone()
        };
        let mut next_area = self.category.area.clone();
        let mut next_year = self.category.year.clone();
        let mut next_sort = self.category.sort.clone();
        ui.horizontal_wrapped(|ui| {
            ui.spacing_mut().item_spacing.x = 6.0;
            ui.label(RichText::new("地区").size(11.0).color(colors().muted));
            egui::ComboBox::from_id_source("category_area")
                .selected_text(option_label(&areas, &next_area, "全部地区"))
                .width(112.0)
                .show_ui(ui, |ui| {
                    ui.selectable_value(&mut next_area, None, "全部地区");
                    for option in &areas {
                        ui.selectable_value(
                            &mut next_area,
                            Some(option.value.clone()),
                            &option.label,
                        );
                    }
                });
            ui.label(RichText::new("年份").size(11.0).color(colors().muted));
            egui::ComboBox::from_id_source("category_year")
                .selected_text(option_label(&years, &next_year, "全部年份"))
                .width(96.0)
                .show_ui(ui, |ui| {
                    ui.selectable_value(&mut next_year, None, "全部年份");
                    for option in &years {
                        ui.selectable_value(
                            &mut next_year,
                            Some(option.value.clone()),
                            &option.label,
                        );
                    }
                });
            ui.label(RichText::new("排序").size(11.0).color(colors().muted));
            egui::ComboBox::from_id_source("category_sort")
                .selected_text(option_label(&sorts, &next_sort, "默认排序"))
                .width(96.0)
                .show_ui(ui, |ui| {
                    for option in &sorts {
                        ui.selectable_value(
                            &mut next_sort,
                            Some(option.value.clone()),
                            &option.label,
                        );
                    }
                });
            if ui
                .add_sized(Vec2::new(78.0, 30.0), egui::Button::new("↺ 重置"))
                .clicked()
            {
                reset = true;
            }
        });

        if let Some(main_type) = next_main {
            self.category.main_type = main_type;
            self.category.cat_slug = match main_type {
                "tv" => "dianshiju",
                "anime" => "dongman",
                "variety" => "zongyi",
                _ => "dianying",
            };
            self.category.page = 1;
            self.load_tab(MainTab::Category);
            return;
        }
        if let Some(slug) = next_slug {
            if self.category.cat_slug != slug {
                self.category.cat_slug = slug;
                self.category.page = 1;
                self.load_tab(MainTab::Category);
                return;
            }
        }
        if reset {
            self.category.area = None;
            self.category.year = None;
            self.category.sort = Some("addtime".to_owned());
            self.category.cat_slug = match self.category.main_type {
                "tv" => "dianshiju",
                "anime" => "dongman",
                "variety" => "zongyi",
                _ => "dianying",
            };
            self.category.page = 1;
            self.load_tab(MainTab::Category);
            return;
        }
        if next_area != self.category.area
            || next_year != self.category.year
            || next_sort != self.category.sort
        {
            self.category.area = next_area;
            self.category.year = next_year;
            self.category.sort = next_sort;
            self.category.page = 1;
            self.load_tab(MainTab::Category);
            return;
        }

        let page_data = match &self.browse.data {
            Some(BrowseData::Page(page))
                if matches!(
                    self.browse.kind,
                    Some(crate::models::BrowseKind::Category { .. })
                ) =>
            {
                Some(page.clone())
            }
            _ => None,
        };
        let loading = self.browse.loading;
        let error = self.browse.error.clone();

        match page_data {
            Some(page) => {
                let label = get_category_label(self.category.cat_slug);
                let total = page.total_items.unwrap_or(page.items.len() as u32);
                let summary = format!(
                    "{} · 第 {}/{} 页 · 共 {} 部",
                    label,
                    page.page,
                    page.total_pages.max(1),
                    total
                );
                let mut refresh = false;
                section_header(ui, &summary, page.items.len(), true, &mut refresh);
                if refresh {
                    self.reload_browse(true);
                }
                if page.items.is_empty() {
                    draw_empty(ui, "没有符合当前条件的影片，请更换条件或重置筛选。");
                } else if let Some(item) = self.draw_grid(ui, ctx, page.items, false, page.page) {
                    self.open_detail(item);
                }
                if let Some(delta) = pager(ui, page.page, page.total_pages) {
                    let next = page.page as i32 + delta;
                    if next >= 1 && next <= page.total_pages as i32 {
                        self.category.page = next as u32;
                        self.load_tab(MainTab::Category);
                    }
                }
            }
            None if loading => draw_loading(ui, "正在读取分类库…"),
            None => {
                let retry = draw_error(ui, error.as_deref().unwrap_or("分类内容暂时无法读取。"));
                if retry {
                    self.reload_browse(true);
                }
            }
        }
    }

    fn draw_top(&mut self, ui: &mut egui::Ui, ctx: &egui::Context) {
        let data = match &self.browse.data {
            Some(BrowseData::Top(boards)) => Some(boards.clone()),
            _ => None,
        };
        let loading = self.browse.loading;
        let error = self.browse.error.clone();
        let Some(boards) = data else {
            if loading {
                draw_loading(ui, "正在载入榜单…");
            } else {
                let retry = draw_error(ui, error.as_deref().unwrap_or("榜单暂时无法读取。"));
                if retry {
                    self.reload_browse(true);
                }
            }
            return;
        };

        let mut refresh = false;
        ui.horizontal_wrapped(|ui| {
            ui.spacing_mut().item_spacing.x = 6.0;
            for period in [TopPeriod::Day, TopPeriod::Week, TopPeriod::Month] {
                if chip_button(ui, period.label(), self.top_period == period).clicked() {
                    self.top_period = period;
                }
            }
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                if ui
                    .add_sized(Vec2::new(64.0, 30.0), egui::Button::new("刷新"))
                    .clicked()
                {
                    refresh = true;
                }
            });
        });
        if refresh {
            self.reload_browse(true);
        }
        let items = match self.top_period {
            TopPeriod::Day => boards.day,
            TopPeriod::Week => boards.week,
            TopPeriod::Month => boards.month,
        };
        section_header(ui, self.top_period.label(), items.len(), false, &mut false);
        if items.is_empty() {
            draw_empty(ui, "这份榜单暂时没有内容。");
            return;
        }
        if let Some(item) = self.draw_grid(ui, ctx, items, true, self.top_period) {
            self.open_detail(item);
        }
    }

    fn draw_daily(&mut self, ui: &mut egui::Ui, ctx: &egui::Context) {
        let data = match &self.browse.data {
            Some(BrowseData::Daily(items)) => {
                let page_count = items.len().div_ceil(DAILY_PAGE_SIZE).max(1);
                let page = self.daily_page.clamp(1, page_count as u32);
                let start = (page as usize - 1) * DAILY_PAGE_SIZE;
                let page_items: Vec<WorkItem> = items
                    .iter()
                    .skip(start)
                    .take(DAILY_PAGE_SIZE)
                    .cloned()
                    .collect();
                Some((page_items, items.len(), page_count, page))
            }
            _ => None,
        };
        let loading = self.browse.loading;
        let error = self.browse.error.clone();
        let Some((page_items, total_items, page_count, page)) = data else {
            if loading {
                draw_loading(ui, "正在载入最近更新…");
            } else {
                let retry = draw_error(ui, error.as_deref().unwrap_or("更新内容暂时无法读取。"));
                if retry {
                    self.reload_browse(true);
                }
            }
            return;
        };
        self.daily_page = page;

        let summary = format!(
            "最近更新 · 第 {}/{} 页（共 {} 部）",
            self.daily_page, page_count, total_items
        );
        let mut refresh = false;
        section_header(ui, &summary, page_items.len(), true, &mut refresh);
        if refresh {
            self.reload_browse(true);
        }
        if page_items.is_empty() {
            draw_empty(ui, "暂时没有更新内容，稍后再来看看。");
            return;
        }
        if let Some(item) = self.draw_grid(ui, ctx, page_items, false, self.daily_page) {
            self.open_detail(item);
        }
        if let Some(delta) = pager(ui, self.daily_page, page_count as u32) {
            let next = self.daily_page as i32 + delta;
            if next >= 1 && next <= page_count as i32 {
                self.daily_page = next as u32;
            }
        }
    }

    fn draw_mine(&mut self, ui: &mut egui::Ui, ctx: &egui::Context) {
        let favorites = self.user_data.favorites.clone();
        let history = self.user_data.history.clone();
        let mut open = None;
        let mut remove = None;
        let mut clear = false;
        let mut tab_changed = false;

        ui.horizontal_wrapped(|ui| {
            ui.spacing_mut().item_spacing.x = 6.0;
            if chip_button(
                ui,
                &format!("★ 我的收藏 ({})", favorites.len()),
                self.mine_tab == MineTab::Favorites,
            )
            .clicked()
            {
                self.mine_tab = MineTab::Favorites;
                tab_changed = true;
            }
            if chip_button(
                ui,
                &format!("播放历史 ({})", history.len()),
                self.mine_tab == MineTab::History,
            )
            .clicked()
            {
                self.mine_tab = MineTab::History;
                tab_changed = true;
            }
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                let label = match self.mine_tab {
                    MineTab::Favorites => "清空收藏",
                    MineTab::History => "清空历史",
                };
                if ui
                    .add_sized(
                        Vec2::new(86.0, 30.0),
                        egui::Button::new(RichText::new(label).size(11.0).color(colors().danger)),
                    )
                    .clicked()
                {
                    clear = true;
                }
            });
        });
        if tab_changed {
            self.status_text = match self.mine_tab {
                MineTab::Favorites => format!("收藏 {} 部", favorites.len()),
                MineTab::History => format!("观看历史 {} 条", history.len()),
            };
        }
        if clear {
            self.confirm = Some(match self.mine_tab {
                MineTab::Favorites => ConfirmAction::ClearFavorites,
                MineTab::History => ConfirmAction::ClearHistory,
            });
        }

        let rows: Vec<MineRow> = match self.mine_tab {
            MineTab::Favorites => favorites
                .iter()
                .map(|item| MineRow {
                    work: favorite_to_work_item(item),
                    secondary: item
                        .category
                        .clone()
                        .or_else(|| item.year.clone())
                        .unwrap_or_else(|| "已收藏".to_owned()),
                    is_history: false,
                })
                .collect(),
            MineTab::History => history
                .iter()
                .map(|item| MineRow {
                    work: item.to_work_item(),
                    secondary: item.episode_line(),
                    is_history: true,
                })
                .collect(),
        };

        if rows.is_empty() {
            draw_empty(
                ui,
                match self.mine_tab {
                    MineTab::Favorites => {
                        "您还没有收藏任何影视作品。\n在详情页点「收藏」即可在这里查看。"
                    }
                    MineTab::History => "暂无观看记录。\n用 VLC 播放后会自动记录最后观看的剧集。",
                },
            );
            return;
        }

        let width = ui.available_width().max(240.0);
        let mut visible_posters = HashSet::new();
        let mine_history = self.mine_tab == MineTab::History;
        egui::ScrollArea::vertical()
            .id_source(("mine_grid", mine_history))
            .auto_shrink([false, false])
            .show_viewport(ui, |ui, viewport| {
                ui.set_width(width);
                ui.set_height(rows.len() as f32 * MINE_ROW_H);
                let first = (viewport.min.y / MINE_ROW_H).floor().max(0.0) as usize;
                let last = ((viewport.max.y / MINE_ROW_H).ceil().max(0.0) as usize).min(rows.len());
                let origin = ui.min_rect().min;
                for (index, row) in rows
                    .iter()
                    .enumerate()
                    .skip(first)
                    .take(last.saturating_sub(first))
                {
                    let y = origin.y + index as f32 * MINE_ROW_H;
                    let row_rect = egui::Rect::from_min_size(
                        egui::pos2(origin.x, y),
                        Vec2::new(width, MINE_ROW_H - 4.0),
                    );
                    let mut row_ui =
                        ui.child_ui(row_rect, egui::Layout::left_to_right(egui::Align::Center));
                    Frame::none()
                        .fill(colors().card)
                        .stroke(Stroke::new(1.0, colors().card_line))
                        .rounding(Rounding::same(10.0))
                        .inner_margin(Margin::same(8.0))
                        .show(&mut row_ui, |ui| {
                            ui.horizontal(|ui| {
                                self.ensure_poster_texture(ctx, &row.work.poster);
                                if !row.work.poster.is_empty() {
                                    visible_posters.insert(row.work.poster.clone());
                                }
                                poster_image(
                                    ui,
                                    self.texture_for(&row.work.poster),
                                    self.texture_size(&row.work.poster),
                                    64.0,
                                    96.0,
                                );
                                ui.add_space(12.0);
                                ui.vertical(|ui| {
                                    ui.spacing_mut().item_spacing.y = 4.0;
                                    ui.label(
                                        RichText::new(&row.work.title)
                                            .size(16.0)
                                            .strong()
                                            .color(colors().text),
                                    );
                                    ui.label(RichText::new(&row.secondary).size(12.0).color(
                                        if row.is_history {
                                            colors().accent
                                        } else {
                                            colors().muted
                                        },
                                    ));
                                    let meta = row.work.subtitle();
                                    if !meta.is_empty() {
                                        ui.label(
                                            RichText::new(meta).size(11.0).color(colors().muted),
                                        );
                                    }
                                });
                                ui.with_layout(
                                    egui::Layout::right_to_left(egui::Align::Center),
                                    |ui| {
                                        if ui
                                            .add_sized(
                                                Vec2::new(64.0, 32.0),
                                                egui::Button::new(
                                                    RichText::new("删除")
                                                        .size(11.0)
                                                        .color(colors().danger),
                                                ),
                                            )
                                            .clicked()
                                        {
                                            remove = Some(index);
                                        }
                                        if ui
                                            .add_sized(
                                                Vec2::new(64.0, 32.0),
                                                egui::Button::new("打开"),
                                            )
                                            .clicked()
                                        {
                                            open = Some(row.work.clone());
                                        }
                                    },
                                );
                            });
                        });
                }
            });

        self.enforce_texture_budget(&visible_posters);
        if let Some(item) = open {
            self.open_detail(item);
        }
        if let Some(index) = remove {
            if let Some(row) = rows.get(index) {
                let url = row.work.url.clone();
                match self.mine_tab {
                    MineTab::Favorites => self.remove_favorite(&url),
                    MineTab::History => self.remove_history(&url),
                }
            }
        }
    }

    fn draw_search(&mut self, ui: &mut egui::Ui, ctx: &egui::Context) {
        let keyword = self.search.keyword.clone();
        let mut next_kind = None;
        let mut clear = false;
        ui.horizontal_wrapped(|ui| {
            ui.spacing_mut().item_spacing.x = 6.0;
            ui.label(
                RichText::new(format!("搜索「{keyword}」"))
                    .size(18.0)
                    .strong()
                    .color(colors().text),
            );
            for kind in [
                SearchKind::All,
                SearchKind::Movie,
                SearchKind::Tv,
                SearchKind::Anime,
            ] {
                if chip_button(ui, kind.label(), self.search.kind == kind).clicked() {
                    next_kind = Some(kind);
                }
            }
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                if ui
                    .add_sized(Vec2::new(86.0, 30.0), egui::Button::new("清除搜索"))
                    .clicked()
                {
                    clear = true;
                }
            });
        });
        if let Some(kind) = next_kind {
            self.start_search(keyword.clone(), kind, 1, false);
            return;
        }
        if clear {
            self.exit_search();
            return;
        }

        let page_data = match &self.browse.data {
            Some(BrowseData::Page(page))
                if matches!(
                    self.browse.kind,
                    Some(crate::models::BrowseKind::Search { .. })
                ) =>
            {
                Some(page.clone())
            }
            _ => None,
        };
        let loading = self.browse.loading;
        let error = self.browse.error.clone();
        match page_data {
            Some(page) => {
                let total = page.total_items.unwrap_or(page.items.len() as u32);
                let summary = format!(
                    "{} · 第 {}/{} 页 · 共 {} 部",
                    self.search.kind.label(),
                    page.page,
                    page.total_pages.max(1),
                    total
                );
                let mut refresh = false;
                section_header(ui, &summary, page.items.len(), true, &mut refresh);
                if refresh {
                    self.reload_browse(true);
                }
                if page.items.is_empty() {
                    draw_empty(ui, "未找到相关影视作品，试试其他关键词。");
                    let mut picked = None;
                    draw_hot_keywords(ui, &mut |word| {
                        picked = Some(word.to_owned());
                    });
                    if let Some(word) = picked {
                        self.search_text = word;
                        self.submit_search_text();
                    }
                } else if let Some(item) = self.draw_grid(ui, ctx, page.items, false, page.page) {
                    self.open_detail(item);
                }
                if let Some(delta) = pager(ui, page.page, page.total_pages) {
                    let next = page.page as i32 + delta;
                    if next >= 1 && next <= page.total_pages as i32 {
                        self.start_search(keyword, self.search.kind, next as u32, false);
                    }
                }
            }
            None if loading => draw_loading(ui, "正在全网检索…"),
            None => {
                let retry = draw_error(ui, error.as_deref().unwrap_or("搜索暂时无法完成。"));
                if retry {
                    self.reload_browse(true);
                }
            }
        }
    }

    fn draw_detail(&mut self, ui: &mut egui::Ui, ctx: &egui::Context) {
        let poster_url = self
            .detail
            .as_ref()
            .map(|view| {
                view.detail
                    .as_ref()
                    .map(|detail| detail.poster.clone())
                    .unwrap_or_else(|| view.fallback.poster.clone())
            })
            .unwrap_or_default();
        self.ensure_poster_texture(ctx, &poster_url);
        let mut action: Option<DetailAction> = None;

        if let Some(view) = self.detail.as_ref() {
            let work = App::work_item_for_detail(view);
            let title = work.title.clone();
            let favorite = self.user_data.is_favorite(&work.url);
            let history = self.user_data.history_for(&work.url).cloned();
            let detail = view.detail.as_ref();
            let loading = view.loading;
            let error = view.error.clone();
            let detail_id = view.id;

            ui.horizontal(|ui| {
                ui.spacing_mut().item_spacing.x = 8.0;
                if ui
                    .add_sized(Vec2::new(76.0, 34.0), egui::Button::new("〈 返回"))
                    .clicked()
                {
                    action = Some(DetailAction::Back);
                }
                let display = elide_text(
                    ui,
                    &title,
                    (ui.available_width() - 220.0).max(120.0),
                    egui::FontId::proportional(20.0),
                );
                ui.label(
                    RichText::new(display)
                        .size(20.0)
                        .strong()
                        .color(colors().text),
                );
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    if ui
                        .add_sized(
                            Vec2::new(88.0, 32.0),
                            egui::Button::new(if favorite {
                                "★ 已收藏"
                            } else {
                                "☆ 收藏"
                            })
                            .fill(if favorite {
                                colors().tab_active
                            } else {
                                colors().field
                            }),
                        )
                        .clicked()
                    {
                        action = Some(DetailAction::ToggleFavorite);
                    }
                    if ui
                        .add_sized(Vec2::new(76.0, 32.0), egui::Button::new("重新读取"))
                        .clicked()
                    {
                        action = Some(DetailAction::Retry);
                    }
                });
            });
            ui.add_space(6.0);

            egui::ScrollArea::vertical()
                .id_source(("detail_scroll", detail_id))
                .auto_shrink([false, false])
                .show(ui, |ui| {
                    ui.horizontal(|ui| {
                        poster_image(
                            ui,
                            self.texture_for(&poster_url),
                            self.texture_size(&poster_url),
                            DETAIL_POSTER_W,
                            DETAIL_POSTER_H,
                        );
                        ui.add_space(16.0);
                        ui.vertical(|ui| {
                            ui.spacing_mut().item_spacing.y = 6.0;
                            ui.label(
                                RichText::new(&title)
                                    .size(26.0)
                                    .strong()
                                    .color(colors().text),
                            );
                            let meta = detail
                                .map(|detail| detail.meta_line())
                                .filter(|value| !value.is_empty())
                                .unwrap_or_else(|| work.subtitle());
                            if !meta.is_empty() {
                                ui.label(RichText::new(meta).size(13.0).color(colors().muted));
                            }
                            let total = detail
                                .and_then(|detail| detail.total_episodes)
                                .map(|count| format!("共 {count} 集"))
                                .unwrap_or_else(|| {
                                    if loading {
                                        "正在读取剧集…".to_owned()
                                    } else {
                                        "剧集信息暂缺".to_owned()
                                    }
                                });
                            ui.label(RichText::new(total).size(12.0).color(colors().muted));

                            let description = detail
                                .and_then(|detail| detail.description.clone())
                                .unwrap_or_else(|| "暂无简介信息。".to_owned());
                            let expanded = view.description_expanded;
                            let shown = if expanded {
                                description.clone()
                            } else {
                                truncate_chars(&description, 230)
                            };
                            ui.add_space(4.0);
                            ui.label(RichText::new(shown).size(13.0).color(colors().summary));
                            if description.chars().count() > 230
                                && ui
                                    .add(
                                        egui::Button::new(
                                            RichText::new(if expanded {
                                                "收起简介"
                                            } else {
                                                "展开简介"
                                            })
                                            .size(11.0)
                                            .color(colors().accent),
                                        )
                                        .frame(false),
                                    )
                                    .clicked()
                            {
                                action = Some(DetailAction::ToggleDescription);
                            }

                            ui.add_space(10.0);
                            ui.horizontal(|ui| {
                                ui.spacing_mut().item_spacing.x = 8.0;
                                let play_label = history
                                    .as_ref()
                                    .filter(|item| !item.episode_url.trim().is_empty())
                                    .map(|item| {
                                        format!(
                                            "继续播放 · {}",
                                            if item.episode_title.trim().is_empty() {
                                                format!("第{:02}集", item.episode_no)
                                            } else {
                                                item.episode_title.clone()
                                            }
                                        )
                                    })
                                    .unwrap_or_else(|| "立即播放".to_owned());
                                let play_enabled = detail.is_some() || history.is_some();
                                if ui
                                    .add_enabled(
                                        play_enabled,
                                        egui::Button::new(
                                            RichText::new(play_label)
                                                .size(13.0)
                                                .color(colors().accent_text),
                                        )
                                        .fill(colors().accent)
                                        .min_size(Vec2::new(160.0, 38.0))
                                        .rounding(Rounding::same(9.0)),
                                    )
                                    .clicked()
                                {
                                    action = Some(DetailAction::PlayFirst);
                                }
                                if let Some(history) = &history {
                                    ui.label(
                                        RichText::new(format!("上次 · {}", history.episode_line()))
                                            .size(11.0)
                                            .color(colors().muted),
                                    );
                                }
                            });
                        });
                    });

                    if let Some(error) = &error {
                        ui.add_space(10.0);
                        Frame::none()
                            .fill(colors().danger_bg)
                            .rounding(Rounding::same(9.0))
                            .inner_margin(Margin::same(10.0))
                            .show(ui, |ui| {
                                ui.label(RichText::new(error).size(12.0).color(colors().danger));
                            });
                    }
                    if loading {
                        ui.add_space(10.0);
                        ui.horizontal(|ui| {
                            ui.spinner();
                            ui.label(
                                RichText::new("正在读取播放线路与剧集…")
                                    .size(12.0)
                                    .color(colors().muted),
                            );
                        });
                    }

                    if let Some(detail) = detail {
                        ui.add_space(14.0);
                        ui.label(RichText::new("播放线路").size(13.0).color(colors().text));
                        ui.add_space(6.0);
                        if detail.lines.is_empty() {
                            ui.label(
                                RichText::new("暂无可用播放线路，可点「重新读取」重试。")
                                    .size(12.0)
                                    .color(colors().muted),
                            );
                        } else {
                            let active = view
                                .active_line_id
                                .filter(|id| detail.lines.iter().any(|line| line.id == *id))
                                .unwrap_or(detail.lines[0].id);
                            ui.horizontal_wrapped(|ui| {
                                ui.spacing_mut().item_spacing.x = 6.0;
                                for line in &detail.lines {
                                    let label = if line.episodes.is_empty() {
                                        format!("{}（空）", line.name)
                                    } else {
                                        line.name.clone()
                                    };
                                    if chip_button(ui, &label, line.id == active).clicked() {
                                        action = Some(DetailAction::SelectLine(line.id));
                                    }
                                }
                            });

                            let active_line = detail
                                .lines
                                .iter()
                                .find(|line| line.id == active)
                                .or_else(|| detail.lines.first());
                            if let Some(line) = active_line {
                                ui.add_space(12.0);
                                ui.label(
                                    RichText::new(format!(
                                        "选集 · {}（{} 集）",
                                        line.name,
                                        line.episodes.len()
                                    ))
                                    .size(13.0)
                                    .color(colors().text),
                                );
                                ui.add_space(6.0);
                                if line.episodes.is_empty() {
                                    ui.label(
                                        RichText::new("此线路没有提供可播放剧集。")
                                            .size(12.0)
                                            .color(colors().muted),
                                    );
                                } else {
                                    let columns = 6usize;
                                    let gap = 8.0;
                                    let cell_w = ((ui.available_width()
                                        - gap * (columns as f32 - 1.0))
                                        / columns as f32)
                                        .max(64.0);
                                    for chunk in line.episodes.chunks(columns) {
                                        ui.horizontal(|ui| {
                                            ui.spacing_mut().item_spacing.x = gap;
                                            for episode in chunk {
                                                let label = elide_text(
                                                    ui,
                                                    &episode.title,
                                                    cell_w - 14.0,
                                                    egui::FontId::proportional(12.0),
                                                );
                                                let response = ui.add_sized(
                                                    Vec2::new(cell_w, 42.0),
                                                    egui::Button::new(label),
                                                );
                                                let response =
                                                    response.on_hover_text(&episode.title);
                                                if response.clicked() {
                                                    action = Some(DetailAction::PlayEpisode(
                                                        episode.clone(),
                                                    ));
                                                }
                                            }
                                        });
                                        ui.add_space(gap);
                                    }
                                }
                            }
                        }
                    }
                });
        }

        if !poster_url.is_empty() {
            let mut protected = HashSet::new();
            protected.insert(poster_url.clone());
            self.enforce_texture_budget(&protected);
        }

        match action {
            Some(DetailAction::Back) => {
                self.detail = None;
                self.play = None;
                self.status_text = "已返回列表".to_owned();
            }
            Some(DetailAction::Retry) => self.retry_detail(),
            Some(DetailAction::ToggleFavorite) => self.toggle_favorite_current(),
            Some(DetailAction::PlayFirst) => self.play_first(),
            Some(DetailAction::PlayEpisode(episode)) => self.play_episode(episode),
            Some(DetailAction::SelectLine(id)) => {
                if let Some(view) = self.detail.as_mut() {
                    view.active_line_id = Some(id);
                }
                self.status_text = "已切换播放线路".to_owned();
            }
            Some(DetailAction::ToggleDescription) => {
                if let Some(view) = self.detail.as_mut() {
                    view.description_expanded = !view.description_expanded;
                }
            }
            None => {}
        }
    }

    fn draw_settings(&mut self, ctx: &egui::Context) {
        let mut draft = self
            .settings_draft
            .take()
            .unwrap_or_else(|| SettingsDraft::from_config(&self.config));
        let mut back = false;
        let mut save = false;

        egui::CentralPanel::default()
            .frame(Frame::none().fill(colors().bg))
            .show(ctx, |ui| {
                ui.spacing_mut().item_spacing.y = 0.0;
                let (header, _) = ui.allocate_exact_size(
                    Vec2::new(ui.available_width(), TOP_H),
                    Sense::hover(),
                );
                let mut header_ui = ui.child_ui_with_id_source(
                    header,
                    egui::Layout::left_to_right(egui::Align::Center),
                    "settings_header",
                );
                header_ui.spacing_mut().item_spacing.x = 10.0;
                header_ui.add_space(PAD);
                if header_ui
                    .add_sized(Vec2::new(76.0, 34.0), egui::Button::new("〈 返回"))
                    .clicked()
                {
                    back = true;
                }
                header_ui.label(
                    RichText::new("连接与播放设置")
                        .size(20.0)
                        .strong()
                        .color(colors().text),
                );
                header_ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    ui.add_space(PAD);
                    if let Some(mode) = theme_switch(ui, draft.theme) {
                        draft.theme = mode;
                    }
                });
                edge_line(&header_ui, true);

                egui::ScrollArea::vertical().show(ui, |ui| {
                    ui.add_space(14.0);
                    let width = (ui.available_width() - PAD * 2.0).clamp(300.0, 720.0);
                    let side = ((ui.available_width() - width) * 0.5).max(PAD);
                    ui.horizontal(|ui| {
                        ui.add_space(side);
                        ui.vertical(|ui| {
                            ui.spacing_mut().item_spacing.y = 0.0;
                            ui.set_width(width);
                            if self.config_load_failed {
                                Frame::none()
                                    .fill(colors().danger_bg)
                                    .rounding(Rounding::same(10.0))
                                    .inner_margin(Margin::same(12.0))
                                    .show(ui, |ui| {
                                        ui.label(
                                            RichText::new(
                                                "原配置文件解析失败。当前使用默认设置，保存后才会替换原文件。",
                                            )
                                            .size(12.0)
                                            .color(colors().danger),
                                        );
                                    });
                                ui.add_space(12.0);
                            }
                            ui.label(
                                RichText::new(
                                    "外部 VLC 播放，不内嵌播放器；低配平板只负责浏览与解析。",
                                )
                                .color(colors().muted),
                            );
                            ui.add_space(14.0);
                            Frame::none()
                                .fill(colors().card)
                                .stroke(Stroke::new(1.0, colors().card_line))
                                .rounding(Rounding::same(14.0))
                                .inner_margin(Margin::same(18.0))
                                .show(ui, |ui| {
                                    ui.spacing_mut().item_spacing.y = 0.0;
                                    labeled_field(
                                        ui,
                                        "站点地址",
                                        &mut draft.source_base,
                                        false,
                                        "默认 https://www.xingfan.cc；一般不需要修改。",
                                    );
                                    labeled_field(
                                        ui,
                                        "VLC 可执行文件路径",
                                        &mut draft.vlc_path,
                                        false,
                                        r"例如 C:\Program Files\VideoLAN\VLC\vlc.exe；留空会尝试自动检测。",
                                    );
                                    if ui
                                        .add_sized(
                                            Vec2::new(132.0, 34.0),
                                            egui::Button::new("自动检测 VLC"),
                                        )
                                        .clicked()
                                    {
                                        if let Some(path) = player::find_vlc() {
                                            draft.vlc_path =
                                                path.to_string_lossy().into_owned();
                                            draft.detected_vlc = Some(draft.vlc_path.clone());
                                        } else {
                                            draft.detected_vlc = None;
                                        }
                                    }
                                    if let Some(path) = &draft.detected_vlc {
                                        ui.add_space(6.0);
                                        ui.label(
                                            RichText::new(format!("已检测到：{path}"))
                                                .size(11.0)
                                                .color(colors().online),
                                        );
                                    }
                                    ui.add_space(16.0);
                                    if ui
                                        .add_sized(
                                            Vec2::new(132.0, 40.0),
                                            egui::Button::new(
                                                RichText::new("保存设置")
                                                    .strong()
                                                    .color(colors().accent_text),
                                            )
                                            .fill(colors().accent)
                                            .rounding(Rounding::same(9.0)),
                                        )
                                        .clicked()
                                    {
                                        save = true;
                                    }
                                });
                            ui.add_space(14.0);
                            ui.label(
                                RichText::new(format!(
                                    "配置文件：{}",
                                    self.config_path.display()
                                ))
                                .size(10.5)
                                .color(colors().muted),
                            );
                            ui.label(
                                RichText::new(format!(
                                    "缓存目录：{}",
                                    self.cache_dir.display()
                                ))
                                .size(10.5)
                                .color(colors().muted),
                            );
                            ui.add_space(28.0);
                        });
                    });
                });
            });

        if back {
            self.settings_draft = None;
            self.show_settings = false;
        } else if save {
            self.apply_settings(draft, ctx);
        } else {
            self.settings_draft = Some(draft);
        }
    }

    fn draw_search_history(&mut self, ctx: &egui::Context) {
        if ctx.input(|input| input.key_pressed(egui::Key::Escape)) {
            self.show_search_history = false;
            return;
        }
        let entries = self.user_data.search_history.clone();
        let mut pick = None;
        let mut remove_index = None;
        let mut clear_all = false;
        let mut close = false;
        let parent = ctx.screen_rect();
        let width = SEARCH_HISTORY_WIDTH.min(parent.width() - 24.0).max(220.0);
        let pos = egui::pos2(parent.right() - width - 12.0, TOP_H + 8.0);

        egui::Window::new("搜索历史")
            .id(egui::Id::new("search_history_popup"))
            .title_bar(false)
            .collapsible(false)
            .resizable(false)
            .fixed_pos(pos)
            .default_width(width)
            .max_width(width)
            .max_height(parent.height() * 0.6)
            .frame(
                Frame::popup(ctx.style().as_ref())
                    .fill(colors().window)
                    .rounding(Rounding::same(12.0))
                    .inner_margin(Margin::same(14.0)),
            )
            .show(ctx, |ui| {
                ui.set_width((width - 28.0).max(180.0));
                ui.horizontal(|ui| {
                    ui.label(RichText::new("搜索历史").strong());
                    ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                        if ui
                            .add_sized(Vec2::new(58.0, 30.0), egui::Button::new("关闭"))
                            .clicked()
                        {
                            close = true;
                        }
                    });
                });
                hairline(ui);
                if entries.is_empty() {
                    ui.add_space(8.0);
                    ui.label(
                        RichText::new("还没有保存的搜索记录")
                            .size(12.0)
                            .color(colors().muted),
                    );
                } else {
                    egui::ScrollArea::vertical()
                        .max_height(parent.height() * 0.45)
                        .show(ui, |ui| {
                            ui.spacing_mut().item_spacing.y = 6.0;
                            for (index, entry) in entries.iter().enumerate() {
                                ui.horizontal(|ui| {
                                    let delete_width = 32.0;
                                    let button_width = (ui.available_width()
                                        - delete_width
                                        - ui.spacing().item_spacing.x)
                                        .max(80.0);
                                    let display = elide_text(
                                        ui,
                                        entry,
                                        (button_width - 12.0).max(48.0),
                                        egui::FontId::proportional(13.0),
                                    );
                                    let response = ui.add_sized(
                                        Vec2::new(button_width, 32.0),
                                        egui::Button::new(display),
                                    );
                                    if response.clicked() {
                                        pick = Some(entry.clone());
                                    }
                                    if ui
                                        .add_sized(
                                            Vec2::new(delete_width, 32.0),
                                            egui::Button::new(
                                                RichText::new("×").color(colors().danger),
                                            ),
                                        )
                                        .clicked()
                                    {
                                        remove_index = Some(index);
                                    }
                                });
                            }
                        });
                }
                hairline(ui);
                ui.horizontal(|ui| {
                    if ui
                        .add_sized(Vec2::new(92.0, 30.0), egui::Button::new("清空全部"))
                        .clicked()
                    {
                        clear_all = true;
                    }
                });
            });

        if close {
            self.show_search_history = false;
        }
        if let Some(index) = remove_index {
            self.user_data.remove_search_at(index);
            self.save_user_data();
        }
        if clear_all {
            self.clear_search_history();
        }
        if let Some(keyword) = pick {
            self.search_text = keyword;
            self.show_search_history = false;
            self.submit_search_text();
        }
    }

    fn draw_play_dialog(&mut self, ctx: &egui::Context) {
        if ctx.input(|input| input.key_pressed(egui::Key::Escape)) {
            self.close_play();
            return;
        }
        let Some(dialog) = self.play.as_ref() else {
            return;
        };
        let mut launch: Option<usize> = None;
        let mut retry = false;
        let mut probe = false;
        let mut close = false;
        let labels = dialog
            .info
            .as_ref()
            .map(|info| player::line_labels(&info.lines))
            .unwrap_or_default();
        let best_index = dialog
            .probe
            .values()
            .min_by_key(|result| result.score())
            .map(|result| result.index);

        show_modal(ctx, "play_dialog", 500.0, |ui| {
            ui.horizontal(|ui| {
                ui.label(
                    RichText::new("播放线路")
                        .size(18.0)
                        .strong()
                        .color(colors().text),
                );
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    if ui
                        .add_sized(Vec2::new(30.0, 30.0), egui::Button::new("×"))
                        .clicked()
                    {
                        close = true;
                    }
                });
            });
            ui.label(
                RichText::new(format!(
                    "{} · {} · 详情线路：{}",
                    dialog.work.title,
                    if dialog.episode_title.trim().is_empty() {
                        format!("第{:02}集", dialog.episode_no)
                    } else {
                        dialog.episode_title.clone()
                    },
                    dialog.line_name
                ))
                .size(12.0)
                .color(colors().muted),
            );
            ui.add_space(10.0);

            if dialog.loading {
                ui.horizontal(|ui| {
                    ui.spinner();
                    ui.label(
                        RichText::new("正在解析播放源，请稍候…")
                            .size(12.0)
                            .color(colors().muted),
                    );
                });
            }
            if let Some(error) = &dialog.error {
                Frame::none()
                    .fill(colors().danger_bg)
                    .rounding(Rounding::same(9.0))
                    .inner_margin(Margin::same(10.0))
                    .show(ui, |ui| {
                        ui.label(RichText::new(error).size(12.0).color(colors().danger));
                    });
                ui.add_space(8.0);
                if ui
                    .add_sized(Vec2::new(88.0, 32.0), egui::Button::new("重新解析"))
                    .clicked()
                {
                    retry = true;
                }
            }

            if let Some(info) = &dialog.info {
                ui.label(
                    RichText::new(format!(
                        "共 {} 条线路。外部 VLC 打开后，本程序不跟踪播放进度。",
                        info.lines.len()
                    ))
                    .size(11.0)
                    .color(colors().muted),
                );
                ui.add_space(8.0);
                egui::ScrollArea::vertical()
                    .max_height(ctx.screen_rect().height() * 0.45)
                    .show(ui, |ui| {
                        ui.spacing_mut().item_spacing.y = 7.0;
                        for (index, line) in info.lines.iter().enumerate() {
                            let selected = best_index == Some(index);
                            Frame::none()
                                .fill(if selected {
                                    colors().tab_active
                                } else {
                                    colors().card
                                })
                                .stroke(Stroke::new(
                                    1.0,
                                    if selected {
                                        colors().accent
                                    } else {
                                        colors().card_line
                                    },
                                ))
                                .rounding(Rounding::same(9.0))
                                .inner_margin(Margin::same(8.0))
                                .show(ui, |ui| {
                                    ui.horizontal(|ui| {
                                        if ui
                                            .add_sized(
                                                Vec2::new(58.0, 32.0),
                                                egui::Button::new(
                                                    RichText::new("播放")
                                                        .color(colors().accent_text),
                                                )
                                                .fill(colors().accent)
                                                .rounding(Rounding::same(7.0)),
                                            )
                                            .clicked()
                                        {
                                            launch = Some(index);
                                        }
                                        ui.vertical(|ui| {
                                            ui.spacing_mut().item_spacing.y = 2.0;
                                            let label = labels
                                                .get(index)
                                                .cloned()
                                                .unwrap_or_else(|| line.name.clone());
                                            ui.horizontal(|ui| {
                                                ui.label(
                                                    RichText::new(label)
                                                        .size(13.0)
                                                        .strong()
                                                        .color(colors().text),
                                                );
                                                if selected {
                                                    ui.label(
                                                        RichText::new("推荐")
                                                            .size(10.0)
                                                            .color(colors().online),
                                                    );
                                                }
                                                if player::is_direct_media_url(&line.url) {
                                                    ui.label(
                                                        RichText::new("直链")
                                                            .size(10.0)
                                                            .color(colors().muted),
                                                    );
                                                }
                                            });
                                            ui.label(
                                                RichText::new(format!(
                                                    "{} · {}",
                                                    line.name,
                                                    host_of(&line.url)
                                                ))
                                                .size(10.5)
                                                .color(colors().muted),
                                            );
                                        });
                                        ui.with_layout(
                                            egui::Layout::right_to_left(egui::Align::Center),
                                            |ui| {
                                                if let Some(result) = dialog.probe.get(&index) {
                                                    ui.label(
                                                        RichText::new(format!(
                                                            "{} ms",
                                                            result.elapsed_ms
                                                        ))
                                                        .size(11.0)
                                                        .color(if selected {
                                                            colors().online
                                                        } else {
                                                            colors().muted
                                                        }),
                                                    );
                                                } else if dialog.probing {
                                                    ui.spinner();
                                                }
                                            },
                                        );
                                    });
                                });
                        }
                    });
            }

            ui.add_space(10.0);
            ui.horizontal(|ui| {
                ui.spacing_mut().item_spacing.x = 8.0;
                let can_probe = dialog
                    .info
                    .as_ref()
                    .map(|info| info.lines.len() >= 2)
                    .unwrap_or(false)
                    && !dialog.probing;
                if ui
                    .add_enabled(
                        can_probe,
                        egui::Button::new(if dialog.probing {
                            "测速中…"
                        } else {
                            "测速线路"
                        })
                        .min_size(Vec2::new(88.0, 32.0)),
                    )
                    .clicked()
                {
                    probe = true;
                }
                if ui
                    .add_sized(Vec2::new(88.0, 32.0), egui::Button::new("重新解析"))
                    .clicked()
                {
                    retry = true;
                }
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    if ui
                        .add_sized(Vec2::new(72.0, 32.0), egui::Button::new("关闭"))
                        .clicked()
                    {
                        close = true;
                    }
                });
            });
        });

        if let Some(index) = launch {
            self.launch_line(index);
        } else if retry {
            self.retry_play();
        } else if probe {
            self.probe_play_lines();
        } else if close {
            self.close_play();
        }
    }

    fn draw_confirm(&mut self, ctx: &egui::Context) {
        let Some(action) = self.confirm.clone() else {
            return;
        };
        let escape = ctx.input(|input| input.key_pressed(egui::Key::Escape));
        let mut confirm = false;
        let mut cancel = false;
        let (title, message) = match action {
            ConfirmAction::ClearHistory => {
                ("清空观看历史", "确定清空全部观看历史吗？此操作无法撤销。")
            }
            ConfirmAction::ClearFavorites => ("清空收藏", "确定清空全部收藏吗？此操作无法撤销。"),
        };
        show_modal(ctx, "confirm_dialog", 400.0, |ui| {
            ui.label(
                RichText::new(title)
                    .size(18.0)
                    .strong()
                    .color(colors().text),
            );
            ui.add_space(12.0);
            ui.label(RichText::new(message).size(13.0).color(colors().muted));
            ui.add_space(18.0);
            ui.horizontal(|ui| {
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    if ui
                        .add_sized(
                            Vec2::new(92.0, 38.0),
                            egui::Button::new(RichText::new("确认清空").color(Color32::WHITE))
                                .fill(colors().danger)
                                .min_size(Vec2::new(92.0, 38.0)),
                        )
                        .clicked()
                    {
                        confirm = true;
                    }
                    if ui
                        .add_sized(Vec2::new(80.0, 38.0), egui::Button::new("取消"))
                        .clicked()
                    {
                        cancel = true;
                    }
                });
            });
        });
        if escape || cancel {
            self.confirm = None;
        } else if confirm {
            self.confirm_action();
        }
    }
}

struct MineRow {
    work: WorkItem,
    secondary: String,
    is_history: bool,
}

fn favorite_to_work_item(item: &FavoriteItem) -> WorkItem {
    WorkItem {
        title: item.title.clone(),
        url: item.url.clone(),
        poster: item.poster.clone(),
        category: item.category.clone(),
        year: item.year.clone(),
        area: item.area.clone(),
        progress: None,
        score: None,
        rank: None,
    }
}

#[derive(Clone, Debug)]
enum DetailAction {
    Back,
    Retry,
    ToggleFavorite,
    PlayFirst,
    PlayEpisode(Episode),
    SelectLine(u32),
    ToggleDescription,
}

fn draw_hot_keywords(ui: &mut egui::Ui, on_pick: &mut dyn FnMut(&str)) {
    ui.add_space(8.0);
    ui.label(RichText::new("热门搜索").size(12.0).color(colors().muted));
    ui.horizontal_wrapped(|ui| {
        ui.spacing_mut().item_spacing.x = 5.0;
        for keyword in HOT_KEYWORDS {
            if chip_button(ui, keyword, false).clicked() {
                on_pick(keyword);
            }
        }
    });
}

fn show_modal(ctx: &egui::Context, id: &str, desired_width: f32, add: impl FnOnce(&mut egui::Ui)) {
    let parent = ctx.screen_rect();
    let width = desired_width.min(parent.width() - 28.0).max(280.0);
    egui::Area::new(egui::Id::new(id))
        .order(egui::Order::Foreground)
        .anchor(egui::Align2::CENTER_CENTER, Vec2::ZERO)
        .constrain_to(parent)
        .movable(false)
        .show(ctx, |ui| {
            ui.painter()
                .rect_filled(parent, Rounding::ZERO, colors().veil);
            ui.interact(parent, ui.id().with("modal_veil"), Sense::click_and_drag());
            Frame::popup(ctx.style().as_ref())
                .fill(colors().window)
                .stroke(Stroke::new(1.0, colors().card_line))
                .rounding(Rounding::same(13.0))
                .inner_margin(Margin::same(20.0))
                .show(ui, |ui| {
                    ui.set_width((width - 40.0).max(220.0));
                    add(ui);
                });
        });
}

fn section_header(
    ui: &mut egui::Ui,
    title: &str,
    count: usize,
    show_refresh: bool,
    refresh: &mut bool,
) {
    ui.horizontal(|ui| {
        ui.spacing_mut().item_spacing.x = 10.0;
        let display = elide_text(
            ui,
            title,
            (ui.available_width() - 180.0).max(100.0),
            egui::FontId::proportional(17.0),
        );
        let is_elided = display != title;
        let response = ui.label(
            RichText::new(display)
                .size(17.0)
                .strong()
                .color(colors().text),
        );
        if is_elided {
            response.on_hover_text(title);
        }
        ui.label(
            RichText::new(format!("共 {count} 部"))
                .size(11.0)
                .color(colors().muted),
        );
        if show_refresh {
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                if ui
                    .add_sized(Vec2::new(64.0, 30.0), egui::Button::new("刷新"))
                    .clicked()
                {
                    *refresh = true;
                }
            });
        }
    });
    ui.add_space(2.0);
}

fn pager(ui: &mut egui::Ui, page: u32, total_pages: u32) -> Option<i32> {
    if total_pages <= 1 {
        return None;
    }
    let mut delta = None;
    ui.add_space(6.0);
    ui.horizontal(|ui| {
        ui.with_layout(egui::Layout::left_to_right(egui::Align::Center), |ui| {
            ui.add_space((ui.available_width() * 0.5 - 180.0).max(0.0));
            if ui
                .add_enabled(
                    page > 1,
                    egui::Button::new("〈 上一页").min_size(Vec2::new(88.0, 32.0)),
                )
                .clicked()
            {
                delta = Some(-1);
            }
            ui.label(
                RichText::new(format!("第 {page} / {total_pages} 页"))
                    .size(12.0)
                    .color(colors().muted),
            );
            if ui
                .add_enabled(
                    page < total_pages,
                    egui::Button::new("下一页 〉").min_size(Vec2::new(88.0, 32.0)),
                )
                .clicked()
            {
                delta = Some(1);
            }
        });
    });
    delta
}

fn chip_button(ui: &mut egui::Ui, label: &str, active: bool) -> egui::Response {
    let (fill, color) = if active {
        (colors().accent, colors().accent_text)
    } else {
        (colors().field, colors().muted)
    };
    ui.add(
        egui::Button::new(RichText::new(label).size(12.0).color(color))
            .fill(fill)
            .min_size(Vec2::new(0.0, 30.0))
            .rounding(Rounding::same(8.0)),
    )
}

fn tab_button(ui: &mut egui::Ui, label: &str, active: bool) -> egui::Response {
    let color = if active {
        colors().tab_text
    } else {
        colors().muted
    };
    let fill = if active {
        colors().tab_active
    } else {
        Color32::TRANSPARENT
    };
    ui.add(
        egui::Button::new(RichText::new(label).size(12.5).color(color))
            .fill(fill)
            .frame(active)
            .min_size(Vec2::new(64.0, 34.0))
            .rounding(Rounding::same(8.0)),
    )
}

fn icon_button(ui: &mut egui::Ui, label: &str) -> egui::Response {
    ui.add(
        egui::Button::new(RichText::new(label).size(11.0))
            .min_size(Vec2::new(34.0, 34.0))
            .rounding(Rounding::same(8.0)),
    )
}

fn brand_mark(ui: &mut egui::Ui) {
    let (rect, _) = ui.allocate_exact_size(Vec2::splat(30.0), Sense::hover());
    ui.painter()
        .rect_filled(rect, Rounding::same(9.0), colors().brand);
    ui.painter().text(
        rect.center(),
        egui::Align2::CENTER_CENTER,
        "HM",
        egui::FontId::proportional(11.0),
        colors().brand_ink,
    );
}

fn brand_titles(ui: &mut egui::Ui) {
    ui.vertical(|ui| {
        ui.spacing_mut().item_spacing.y = -4.0;
        ui.label(
            RichText::new("HM 影视")
                .size(16.0)
                .strong()
                .color(colors().text),
        );
        ui.label(RichText::new("星帆点播").size(9.5).color(colors().muted));
    });
}

fn poster_image(
    ui: &mut egui::Ui,
    texture: Option<&TextureHandle>,
    size: Option<[usize; 2]>,
    width: f32,
    height: f32,
) -> egui::Response {
    let (rect, response) = ui.allocate_exact_size(Vec2::new(width, height), Sense::hover());
    if let Some(texture) = texture {
        let mut image = egui::Image::from_texture(texture)
            .rounding(8.0)
            .bg_fill(colors().poster);
        if let Some(uv) = cover_uv(size, width, height) {
            image = image.uv(uv);
        }
        image.paint_at(ui, rect);
    } else {
        ui.painter()
            .rect_filled(rect, Rounding::same(8.0), colors().poster);
        ui.painter().text(
            rect.center(),
            egui::Align2::CENTER_CENTER,
            "无图",
            egui::FontId::proportional(13.0),
            colors().muted,
        );
    }
    response
}

/// 计算中心裁切的 UV，让宽幅精选图填满 2:3 卡片而不拉伸。
fn cover_uv(size: Option<[usize; 2]>, target_width: f32, target_height: f32) -> Option<egui::Rect> {
    let [image_width, image_height] = size?;
    if image_width == 0 || image_height == 0 || target_width <= 0.0 || target_height <= 0.0 {
        return None;
    }
    let image_aspect = image_width as f32 / image_height as f32;
    let target_aspect = target_width / target_height;
    let (u0, v0, u1, v1) = if image_aspect > target_aspect {
        let visible = target_aspect / image_aspect;
        ((1.0 - visible) / 2.0, 0.0, (1.0 + visible) / 2.0, 1.0)
    } else {
        let visible = image_aspect / target_aspect;
        (0.0, (1.0 - visible) / 2.0, 1.0, (1.0 + visible) / 2.0)
    };
    Some(egui::Rect::from_min_max(
        egui::pos2(u0, v0),
        egui::pos2(u1, v1),
    ))
}

fn draw_loading(ui: &mut egui::Ui, text: &str) {
    ui.add_space(48.0);
    ui.vertical_centered(|ui| {
        ui.spinner();
        ui.add_space(6.0);
        ui.label(RichText::new(text).size(12.0).color(colors().muted));
    });
}

fn draw_empty(ui: &mut egui::Ui, text: &str) {
    ui.add_space(36.0);
    ui.vertical_centered(|ui| {
        ui.label(RichText::new(text).size(13.0).color(colors().muted));
    });
}

fn draw_error(ui: &mut egui::Ui, text: &str) -> bool {
    let mut retry = false;
    ui.add_space(24.0);
    ui.vertical_centered(|ui| {
        Frame::none()
            .fill(colors().card)
            .stroke(Stroke::new(1.0, colors().card_line))
            .rounding(Rounding::same(12.0))
            .inner_margin(Margin::same(18.0))
            .show(ui, |ui| {
                ui.label(RichText::new(text).size(13.0).color(colors().danger));
                ui.add_space(8.0);
                if ui
                    .add_sized(Vec2::new(92.0, 32.0), egui::Button::new("重试"))
                    .clicked()
                {
                    retry = true;
                }
            });
    });
    retry
}

fn bar_frame(fill: Color32) -> Frame {
    Frame::none()
        .fill(fill)
        .inner_margin(Margin::symmetric(PAD, 0.0))
}

fn edge_line(ui: &egui::Ui, bottom: bool) {
    let clip = ui.clip_rect();
    let y = if bottom {
        clip.bottom() - 0.5
    } else {
        clip.top() + 0.5
    };
    ui.painter()
        .hline(clip.x_range(), y, Stroke::new(1.0, colors().line));
}

fn hairline(ui: &mut egui::Ui) {
    let (rect, _) = ui.allocate_exact_size(Vec2::new(ui.available_width(), 1.0), Sense::hover());
    ui.painter().hline(
        rect.x_range(),
        rect.center().y,
        Stroke::new(1.0, colors().line),
    );
}

fn elide_text(ui: &egui::Ui, text: &str, max_width: f32, font: egui::FontId) -> String {
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
    if max_width <= measure(ellipsis) {
        return String::new();
    }
    let chars: Vec<char> = text.chars().collect();
    let mut low = 0usize;
    let mut high = chars.len();
    while low < high {
        let mid = (low + high).div_ceil(2);
        let mut candidate: String = chars[..mid].iter().collect();
        candidate.push_str(ellipsis);
        if measure(&candidate) <= max_width {
            low = mid;
        } else {
            high = mid - 1;
        }
    }
    let mut output: String = chars[..low].iter().collect();
    output.push_str(ellipsis);
    output
}

fn truncate_chars(text: &str, limit: usize) -> String {
    if text.chars().count() <= limit {
        return text.to_owned();
    }
    let mut output: String = text.chars().take(limit).collect();
    output.push('…');
    output
}

fn option_label(options: &[ChipOption], selected: &Option<String>, fallback: &str) -> String {
    let Some(selected) = selected else {
        return fallback.to_owned();
    };
    options
        .iter()
        .find(|option| &option.value == selected)
        .map(|option| option.label.clone())
        .unwrap_or_else(|| selected.clone())
}

fn host_of(url: &str) -> String {
    url::Url::parse(url)
        .ok()
        .and_then(|url| url.host_str().map(str::to_owned))
        .unwrap_or_else(|| "未知源".to_owned())
}

fn theme_switch(
    ui: &mut egui::Ui,
    current: crate::config::ThemeMode,
) -> Option<crate::config::ThemeMode> {
    use crate::config::ThemeMode;
    let mut next = None;
    ui.scope(|ui| {
        ui.spacing_mut().item_spacing.x = 4.0;
        if theme_chip(ui, "白天", current == ThemeMode::Day).clicked() {
            next = Some(ThemeMode::Day);
        }
        if theme_chip(ui, "夜间", current == ThemeMode::Night).clicked() {
            next = Some(ThemeMode::Night);
        }
    });
    next.filter(|mode| *mode != current)
}

fn theme_chip(ui: &mut egui::Ui, label: &str, active: bool) -> egui::Response {
    let (fill, color) = if active {
        (colors().accent, colors().accent_text)
    } else {
        (colors().field, colors().muted)
    };
    ui.add(
        egui::Button::new(RichText::new(label).color(color))
            .fill(fill)
            .min_size(Vec2::new(58.0, 32.0))
            .rounding(Rounding::same(8.0)),
    )
}

fn labeled_field(ui: &mut egui::Ui, label: &str, value: &mut String, password: bool, hint: &str) {
    ui.label(RichText::new(label).strong().color(colors().text));
    ui.add_space(5.0);
    let response = ui.add_sized(
        Vec2::new(ui.available_width(), 40.0),
        egui::TextEdit::singleline(value)
            .password(password)
            .desired_width(f32::INFINITY)
            .margin(Margin::symmetric(10.0, 6.0)),
    );
    if response.gained_focus() {
        touch_keyboard::show_touch_keyboard();
    }
    if response.has_focus() {
        ui.ctx()
            .send_viewport_cmd(egui::ViewportCommand::IMEAllowed(true));
    }
    ui.add_space(5.0);
    ui.label(RichText::new(hint).size(10.5).color(colors().muted));
    ui.add_space(12.0);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cover_uv_center_crops_wide_backdrop() {
        let uv = cover_uv(Some([1600, 900]), 96.0, 144.0).expect("uv");
        assert!(uv.min.x > 0.0);
        assert!(uv.max.x < 1.0);
        assert_eq!(uv.min.y, 0.0);
        assert_eq!(uv.max.y, 1.0);
    }

    #[test]
    fn cover_uv_keeps_matching_poster_full() {
        let uv = cover_uv(Some([180, 270]), 96.0, 144.0).expect("uv");
        assert!((uv.min.x - 0.0).abs() < 0.001);
        assert!((uv.min.y - 0.0).abs() < 0.001);
        assert!((uv.max.x - 1.0).abs() < 0.001);
        assert!((uv.max.y - 1.0).abs() < 0.001);
    }
}
