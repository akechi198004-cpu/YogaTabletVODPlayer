# HM 影视 Windows 桌面版

把本仓库的 Android 点播应用改写成可在老旧 Win32 平板运行的桌面程序：浏览星帆影视、搜索、查看详情与选集，并用**外部 VLC** 播放。

目标设备是 **32 位 Windows 10、约 2GB 内存、1280×800 触屏平板**。程序本身不内嵌播放器，也不引入 Web 技术栈。

---

## 功能

- 首页精选与「最近更新」海报墙
- 分类：电影 / 电视剧 / 动漫 / 综艺，地区、年份、排序与分页
- 榜单：今日 / 本周 / 月度
- 更新：每页 24 条，前后翻页
- 搜索：全部 / 电影 / 剧集 / 动漫，分页与搜索历史
- 收藏、播放历史（本地 JSON，不上传）
- 详情：海报、分类/年份/地区、简介、线路、选集
- 播放线路解析与可选测速
- 外部 VLC 播放，自动带 Referer 与 User-Agent
- 海报与列表/详情缓存，断网时在有效期内回退旧内容
- 夜间深蓝灰 / 白天浅灰蓝两套主题
- Windows 触屏键盘（TabTip）自动弹出

## 非目标 / 已知限制

- **不内嵌 VLC / libVLC**，只负责启动外部 VLC
- 不跟踪 VLC 播放进度，因此没有精确续播；「继续播放」会重新打开上次的剧集
- 不做字幕、音轨、倍速、转码策略等播放器内部控制
- 不做账号、多用户、下载、投屏
- 仅适配星帆影视当前 HTML 结构；站点改版可能导致解析失败
- 分类筛选的「类型」使用内置分类，地区/年份/排序来自站点返回
- 线路测速最多测前 6 条，结果只代表短样本，不保证持续带宽
- 不移植 Android 的「手机输入 / 二维码」功能；Windows 平板直接使用系统键盘
- 未做代码签名，Windows SmartScreen 可能提示未知发布者
- 未在真实 32 位 Windows 平板上完成长时间播放验收；发布前建议按下方清单自测

---

## 构建

### 前置

- Rust stable（建议使用当前 stable；`Cargo.lock` 为 v4）
- Windows 上构建 32 位包需要 MSVC 构建工具
- VLC 3.x（运行程序时使用）

### Linux / macOS 本地开发

```bash
./start.sh
```

或：

```bash
cargo run
```

`start.sh` 会把配置与缓存放到 `win32_app/.local/`，首次运行复制 `config.example.toml`。

### Windows 32 位发布包

在 PowerShell 中：

```powershell
cd win32_app
.\build-win32.ps1
```

脚本会：

- 安装 `i686-pc-windows-msvc` 目标
- 运行 `cargo test`
- 构建 `target/i686-pc-windows-msvc/release/hm-video-win32.exe`
- 打包到 `dist/HMVideo-win32.zip`

`.cargo/config.toml` 已启用 `+crt-static`，静态链接 MSVC C 运行库，尽量减少目标机器缺少 `vcruntime140.dll` 的情况。

### GitHub Actions

仓库根目录 `.github/workflows/windows-win32.yml` 会在 `windows-latest` 上：

- 运行主机测试
- 构建 `i686-pc-windows-msvc`
- 上传 `HMVideo-win32.zip` 构件

本地没有 MSVC 交叉工具链时，建议用该工作流产出 Win32 包。

---

## 运行与配置

发布包目录结构：

```text
HMVideo-win32/
  hm-video-win32.exe
  config.toml
  DroidSansFallbackFull.ttf
  DroidSansFallbackFull.LICENSE.txt
  README.md
```

`config.toml`：

```toml
source_base = "https://www.xingfan.cc"
vlc_path = ""
theme = "night"
```

- `vlc_path` 留空时自动在常见安装位置和 PATH 中查找 `vlc.exe`
- 也可以填写完整路径，例如：
  `vlc_path = "C:\\Program Files\\VideoLAN\\VLC\\vlc.exe"`
- `theme` 可选 `night` / `day`

配置查找顺序：

1. 环境变量 `HM_VOD_HOME` 指定的目录
2. 可执行文件同目录存在 `config.toml`（便携包）
3. 当前目录存在 `config.toml`
4. 系统应用数据目录

收藏、历史与搜索历史保存在同目录的 `user_data.json`。

### 缓存位置

默认在配置目录下的 `cache/`：

```text
cache/
  meta/      列表、详情、首页、榜单等 JSON 缓存
  poster/    海报原图缓存
```

缓存只保存海报与元数据，**不保存视频文件、不保存播放直链**。播放直链通常带时效签名，只在内存中保留 30 分钟。

---

## 使用流程

1. 启动程序；首次会自动检测 VLC
2. 首页浏览精选与最近更新
3. 顶栏切换分类 / 榜单 / 更新 / 收藏历史
4. 点海报进入详情，选择线路和剧集
5. 点击剧集后，程序解析播放页与播放服务
6. 在「播放线路」弹窗中选择线路：
   - 可直接点「播放」调用 VLC
   - 可点「测速线路」看短样本延迟
   - 解析失败可「重新解析」
7. 播放成功后会自动记录作品、线路和剧集

如果 VLC 路径为空或无效，程序会给出提示；可在右上角「设置」中填写或点「自动检测 VLC」。

---

## 低配置设计

- 海报缩略图最多 **180×270**，避免大图常驻显存/内存
- 纹理预算 **24MB / 120 条**，按最近使用淘汰
- 每帧最多上传 **2** 张纹理、发起 **4** 个海报请求
- 2 个海报工作线程，队列 24；页面请求单线程串行，避免抢占
- 海报墙只绘制可见行，上下各预加载 1 行
- 无过渡动画；`animation_time = 0.0`
- 列表/详情分页或按需加载
- 搜索历史 10 条、收藏 200 条、历史 100 条上限
- 静态 CRT 的 32 位 MSVC 构建，减少运行库依赖

---

## 数据与网络说明

- 站点页面请求超时 20 秒；海报请求 30 秒；线路探测 3 秒
- 列表/详情使用 `ETag` / `Last-Modified` 条件请求，304 只续期
- 网络失败时在缓存「最长可展示」窗口内回退；404/410 删除缓存
- 播放线路解析：
  - 请求剧集页，提取 `zanpiancms_player`
  - 请求播放服务页
  - 优先解析 `#line-list a[data-url]`
  - 回退解析 `quality` JSON 与 `url: '...m3u8/mp4'`
- 不记录 Plex/站点 Token；本程序不需要登录 Token

---

## 验证

本地已执行：

```bash
cargo test
cargo clippy --all-targets
```

结果：

- 24 项单元测试通过，0 失败
- 1 项真实站点烟测默认忽略
- clippy 无警告

真实站点烟测（会访问 `www.xingfan.cc`，可能受站点限流影响）：

```bash
cargo test live_source_smoke -- --ignored --nocapture
```

覆盖首页、分类（含筛选）、搜索、榜单、更新、详情、剧集页、播放服务与线路解析。

Windows 32 位构建需要在 Windows/MSVC 或 GitHub Actions 上完成；本地 Linux 环境无法交叉链接 MSVC 目标。

---

## 建议真机自测清单

- [ ] 32 位 Windows 10 首次启动，配置文件与缓存目录可写
- [ ] 自动检测到 VLC，或手动填写路径后能启动
- [ ] 首页、分类、榜单、更新、搜索均能加载
- [ ] 海报滚动时不卡顿，内存无明显持续增长
- [ ] 详情页线路切换、选集、收藏、继续播放
- [ ] 线路解析失败时可重新解析
- [ ] VLC 能播放 HLS 与直链，中文标题正常
- [ ] 断网后有效期内仍能显示缓存列表与海报
- [ ] 触屏键盘能弹出，设置页可输入中文路径
- [ ] 关闭程序后收藏/历史/搜索历史保留

---

## 字体

`assets/DroidSansFallbackFull.ttf` 用于保证中文显示，来源于 Android 系统字体，遵循 Apache 2.0 许可证；许可证全文见 `assets/DroidSansFallbackFull.LICENSE.txt`。发布包会把字体与许可证放在 exe 同目录。
