# HM影视 Windows 桌面版：项目任务说明书

## 项目名称

HM 影视 Windows 版（`hm-video-win32`）

## 项目目标

把本仓库的 Android 点播应用转换成可直接在老旧 Win32 平板上运行的桌面程序：

- 连接固定站点（默认 `https://www.xingfan.cc`）
- 浏览首页、分类、榜单、最近更新
- 搜索、收藏、观看历史
- 查看作品详情、线路与选集
- 点击剧集后用本机外部 VLC 播放
- 适配 32 位 Windows 10、约 2GB 内存、1280×800 触屏

## 目标设备

- Windows 10 32 位
- 约 2GB 内存
- 1280×800 触控平板
- 本机已安装 VLC；程序不内嵌播放器

## 功能范围

1. 首页精选与最近更新海报墙
2. 分类：电影 / 电视剧 / 动漫 / 综艺，地区、年份、排序、分页
3. 榜单：今日、本周、月度
4. 更新：每页 24 条，前后翻页
5. 搜索：全部 / 电影 / 剧集 / 动漫，分页与搜索历史
6. 收藏、播放历史（本地 JSON）
7. 详情：海报、元数据、简介、线路、选集、收藏、继续播放
8. 播放：解析播放页与播放服务，列出线路，可测速，用外部 VLC 打开
9. 缓存：海报原图落盘、列表/详情 JSON 落盘、ETag/Last-Modified 条件请求
10. 设置：站点地址、VLC 路径、夜间/白天主题

## 非目标

- 不内嵌 VLC / libVLC
- 不做账号登录、多用户
- 不做视频下载
- 不做完整播放控制、字幕/音轨切换、播放进度回传
- 不做复杂动画、皮肤系统或 Web 技术栈
- 不兼容所有第三方站点；只针对星帆影视当前 HTML 结构
- 不保证 64 位专属能力；发布目标优先 32 位

## 技术栈

- Rust 2021
- `eframe` / `egui` 0.27（glow）
- `reqwest` blocking + rustls
- `scraper` + `regex` + `serde_json`
- `image` 仅解码 jpeg/png/webp
- `serde` + `toml` + `directories`

## 目录结构

```text
win32_app/
  Cargo.toml
  config.example.toml
  build-win32.ps1
  start.sh
  assets/DroidSansFallbackFull.ttf
  packaging/windows/config.toml
  src/
    main.rs
    app.rs
    app_ui.rs
    cache.rs
    config.rs
    models.rs
    parser.rs
    player.rs
    source.rs
    touch_keyboard.rs
    ui/
      mod.rs
      poster_grid.rs
      theme.rs
    worker.rs
```

## 低配设备约束

- 海报缩略图最多 180×270，避免大图常驻纹理
- 纹理预算 24MB / 120 条，按最近使用淘汰
- 每帧最多 2 张纹理上传、4 个海报请求
- 2 个海报工作线程、队列 24；页面请求单线程串行
- 网格只绘制可见行与上下各 1 行预加载
- 不做动画；`style.animation_time = 0.0`
- 列表/详情按页或按需加载，不一次性加载全部海报
- 分类/搜索/更新页为分页栏预留高度，滚动区不能把按钮挤出窗口
- 搜索历史 10 条、收藏 200 条、历史 100 条

## 播放链路

1. 详情页选集 -> 发送 `Command::PlayInfo`
2. 工作线程请求剧集页，提取 `zanpiancms_player`
3. 构造播放服务地址，请求并解析 `#line-list a[data-url]` 或 `quality` JSON
4. 播放线路弹窗展示线路与源站；可选测速（最多 6 条、3 秒超时）
5. 点击播放 -> `player::launch_vlc`
   - 传 `--http-referrer` 与 `--http-user-agent`
   - 传 `--meta-title` / `--input-title-format`
6. 记录最后播放的作品、线路、剧集；不记录进度

## 缓存策略

| 数据 | 磁盘新鲜窗口 | 最长可展示 |
| --- | --- | --- |
| 首页 | 5 分钟 | 7 天 |
| 分类 | 15 分钟 | 30 天 |
| 搜索 | 10 分钟 | 7 天 |
| 更新 | 20 分钟 | 3 天 |
| 榜单 | 20 分钟 | 7 天 |
| 详情 | 6 小时 | 60 天 |
| 播放线路 | 仅内存 30 分钟 | 不落盘 |

网络失败或页面无法识别时，在最长可展示窗口内回退旧缓存；404/410 删除缓存。
明确空结果（结果区域内可见的「共 0 部」或可见空态）正常显示并缓存；隐藏、模板、无关区域不计入。
详情页需有线路、分类、简介或集数等作品结构；仅带 h1 的维护/验证页视为无法识别。
无法识别时显示可重试错误。
`user_data.json` 仅将 NotFound 视为首次运行；其他读取错误进入保护，暂停自动保存并保留原文件。
设置页提供重试读取与备份重建；无法确认文件状态时不得跳过备份。
播放直链含时效签名，不写磁盘。

## 构建与验证

- Linux 开发：`./start.sh` 或 `cargo run`
- 测试：`cargo test`
- 真实站点烟测：`cargo test live_source_smoke -- --ignored --nocapture`
- Win32 发布：PowerShell `.\build-win32.ps1`
- 目标：`i686-pc-windows-msvc`
- `.cargo/config.toml` 启用 `+crt-static`，尽量避免缺少 `vcruntime140.dll`
- GitHub Actions：仓库根目录 `.github/workflows/windows-win32.yml`

## 编码原则

- 先可用、后优雅；不要过度抽象
- UI 线程不做网络、磁盘读取或图片解码
- 模块职责清晰：站点访问、解析、缓存、播放、UI 分离
- 错误给用户可理解的中文提示，并保留重试入口
- 改动后至少运行 `cargo test` 与 `cargo clippy --all-targets`
- 不修改 Android 工程；本目录是独立桌面实现
