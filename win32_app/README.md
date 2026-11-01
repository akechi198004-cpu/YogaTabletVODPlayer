# HM 影视 Windows 桌面版

把本仓库的 Android 点播应用改写成可在老旧 Win32 平板运行的桌面程序：浏览星帆影视、搜索、查看详情与选集，默认用**随包 mpv** 在应用窗口内播放，外部 VLC 仅作可选兼容模式。

目标设备是 **32 位 Windows 10、约 2GB 内存、1280×800 触屏平板**。不引入 Web 技术栈，不需要用户安装 VLC。

---

## 内置播放技术

- 后端：随包 **mpv i686（FFmpeg）**，成熟播放器，不自行实现解码
- 嵌入方式：mpv 通过 `--wid` 渲染到应用自己的 Win32 子窗口
- 控制方式：Windows 命名管道 JSON IPC，异步读取/写入，不阻塞 UI
- 进度：订阅 mpv 的 `time-pos` / `duration` / `pause` / `volume` 属性，支持拖动定位
- 生命周期：工作线程串行管理启动、停止和清理；应用退出时由 `KILL_ON_JOB_CLOSE` 作业对象兜底，避免残留 mpv
- 运行时：发布包 `mpv\mpv.exe` + `mpv\d3dcompiler_43.dll`；不依赖 VLC/libVLC
- 兼容模式：外部 VLC 仅在用户选择或主动点击「改用 VLC 播放」时启动

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
- 内置 mpv 播放：播放/暂停、音量、进度拖动、全屏、退出播放
- 内置失败时明确提示，用户主动点击「改用 VLC 播放」后才启动外部 VLC
- 外部 VLC 兼容模式，自动带 Referer 与 User-Agent
- VLC 路径支持「选择文件」「选择安装目录」原生选择器
- 海报与列表/详情缓存，断网时在有效期内回退旧内容
- 夜间深蓝灰 / 白天浅灰蓝两套主题
- Windows 触屏键盘（TabTip）自动弹出

## 非目标 / 已知限制

- 内置播放依赖发布包中的 `mpv\mpv.exe`；缺失时不会自动启动外部 VLC
- 不支持 DRM/加密流、网页地址，以及随包 mpv/FFmpeg 不支持的编码
- 不做字幕、音轨、倍速、转码策略等播放器内部控制
- 不持久化精确播放进度；「继续播放」会重新打开上次的剧集
- 不做账号、多用户、下载、投屏
- 仅适配星帆影视当前 HTML 结构；站点改版可能导致解析失败
- 分类筛选的「类型」使用内置分类，地区/年份/排序来自站点返回
- 线路测速最多测前 6 条，结果只代表短样本，不保证持续带宽
- 不移植 Android 的「手机输入 / 二维码」功能；Windows 平板直接使用系统键盘
- 外部 VLC 模式在 32 位 Windows 上需要 32 位 VLC；内置播放不需要
- 未做代码签名，Windows SmartScreen 可能提示未知发布者
- 未在真实 32 位 Windows 平板上完成长时间播放验收；发布前建议按下方清单自测

---

## 构建

### 前置

- Rust stable（建议使用当前 stable；`Cargo.lock` 为 v4）
- Windows 上构建 32 位包需要 MSVC 构建工具
- 打包内置播放器需要 7-Zip（脚本/CI 会下载并校验 32 位 mpv）
- 外部 VLC 模式可选安装 VLC 3.x；内置播放不需要

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
- 下载并校验 32 位 mpv，解压 `mpv.exe` 与 `d3dcompiler_43.dll` 到发布包的 `mpv\`
- 打包到 `dist/HMVideo-win32.zip`

`.cargo/config.toml` 已启用 `+crt-static`，静态链接 MSVC C 运行库，尽量减少目标机器缺少 `vcruntime140.dll` 的情况。

### GitHub Actions

仓库根目录 `.github/workflows/windows-win32.yml` 在推送到 `main` / `master` 后，
仅当最新提交消息包含 `[deploy]` 时，才会在 `windows-latest` 上执行构建。
例如：`git commit -m "[deploy] build Win32 package"`。
普通提交会跳过构建任务；PR 和手动运行不触发该工作流。

- 使用锁定依赖运行 `i686-pc-windows-msvc` 目标测试
- 使用锁定依赖构建 `i686-pc-windows-msvc` 发布版
- 下载并校验固定版本的 32 位 mpv，随包放入 `mpv\`
- 上传包含内置播放器运行时的 `HMVideo-win32.zip` 构件

本地没有 MSVC 交叉工具链时，建议用该工作流产出 Win32 包。

---

## 运行与配置

发布包目录结构：

```text
HMVideo-win32/
  hm-video-win32.exe
  mpv/
    mpv.exe
    d3dcompiler_43.dll
  config.toml
  DroidSansFallbackFull.ttf
  DroidSansFallbackFull.LICENSE.txt
  MPV-NOTICE.txt
  MPV-GPL-2.0.txt
  README.md
```

`mpv\` 是内置播放器运行时，不要只复制 exe 而丢掉该目录。

`config.toml`：

```toml
source_base = "https://www.xingfan.cc"
player_backend = "embedded"
vlc_path = ""
embedded_volume = 80
theme = "night"
```

- `player_backend`：`embedded`（默认）或 `external_vlc`
- `vlc_path` 仅外部 VLC 模式使用；留空时播放前自动检测，不会阻止内置播放或保存设置
- 也可以填写完整路径，例如：
  `vlc_path = "C:\\Program Files\\VideoLAN\\VLC\\vlc.exe"`
- `embedded_volume` 为内置播放器音量 0-100
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

1. 解压完整发布包（保留 `mpv` 文件夹）并启动
2. 首页浏览精选与最近更新
3. 顶栏切换分类 / 榜单 / 更新 / 收藏历史
4. 点海报进入详情，选择线路和剧集
5. 点击剧集后，程序解析播放页与播放服务
6. 在「播放线路」弹窗中选择线路：
   - 点「播放」后默认用内置 mpv 在应用窗口内打开
   - 可点「测速线路」看短样本延迟
   - 解析失败可「重新解析」
7. 内置播放器支持暂停/继续、音量、进度拖动、全屏和退出播放
8. 播放成功后会自动记录作品、线路和剧集
9. 内置播放失败时，画面会提示原因；只有点击「改用 VLC 播放」才会启动外部 VLC
10. 设置页的 VLC 路径旁可用「选择文件」或「选择安装目录」；选择结果只在点击「保存设置」后生效

如果 VLC 路径为空或无效，内置播放不受影响；外部模式播放失败时再到设置页选择 VLC。

---

## 低配置设计

- 海报缩略图最多 **180×270**，避免大图常驻显存/内存
- 纹理预算 **24MB / 120 条**，按最近使用淘汰
- 每帧最多上传 **2** 张纹理、发起 **4** 个海报请求
- 2 个海报工作线程，队列 24；页面请求单线程串行，避免抢占
- 海报墙只绘制可见行，上下各预加载 1 行
- 无过渡动画；`animation_time = 0.0`
- 列表/详情分页或按需加载；分类/搜索/更新页为分页栏预留固定高度
- 搜索历史 10 条、收藏 200 条、历史 100 条上限
- `user_data.json` 读取失败时暂停自动保存，避免空数据覆盖；设置页提供重试读取与备份重建
- 静态 CRT 的 32 位 MSVC 构建，减少运行库依赖

---

## 数据与网络说明

- 站点页面请求超时 20 秒；海报请求 30 秒；线路探测 3 秒
- 列表/详情使用 `ETag` / `Last-Modified` 条件请求，304 只续期
- 网络失败或页面结构无法识别时，在缓存「最长可展示」窗口内回退；404/410 删除缓存
- 明确的空结果（如「共 0 部」或可见空态）正常显示并缓存；无法识别的页面显示可重试错误
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

- 46 项单元测试通过，0 失败
- 1 项真实站点烟测默认忽略
- clippy 无警告
- 布局回归：1280×800 与最小窗口 960×600 下，分页栏保持在窗口内
- 构建脚本：用假 `rustup` / `cargo` 验证测试失败、构建失败时立即停止，不生成 zip、不输出成功提示
- 用户数据：损坏文件、目录或非 NotFound 读取错误均进入保护，受保护的保存不会覆盖原文件
- 解析异常：支持「共 <b>0</b> 部」等可见内联标签；隐藏/无关子区域的「共 0 部」不会覆盖有效缓存
- 仅凭 h1 的维护/验证页返回可重试错误

真实站点烟测（会访问 `www.xingfan.cc`，可能受站点限流影响）：

```bash
cargo test live_source_smoke -- --ignored --nocapture
```

覆盖首页、分类（含筛选）、搜索、榜单、更新、详情、剧集页、播放服务与线路解析。

Windows 32 位构建需要在 Windows/MSVC 或 GitHub Actions 上完成；本地 Linux 环境无法交叉链接 MSVC 目标。

---

## 建议真机自测清单

- [ ] 32 位 Windows 10 首次启动，配置文件与缓存目录可写
- [ ] 发布包包含 `mpv\mpv.exe`，无需安装 VLC 即可播放
- [ ] 首页、分类、榜单、更新、搜索均能加载
- [ ] 海报滚动时不卡顿，内存无明显持续增长
- [ ] 详情页线路切换、选集、收藏、继续播放
- [ ] 线路解析失败时可重新解析
- [ ] 内置播放器能播放 HLS 与 MP4，暂停/继续、音量、拖动进度、全屏正常
- [ ] 换线路、换集、退出播放和关闭程序后没有残留 mpv 进程或声音
- [ ] 内置播放失败时只显示提示，点击「改用 VLC 播放」后才启动外部 VLC
- [ ] 设置页「选择文件」「选择安装目录」「自动检测」均可用，中文/空格路径正常
- [ ] 取消选择或选择无效目录时保留原 VLC 路径，保存后配置才生效
- [ ] 断网后有效期内仍能显示缓存列表与海报
- [ ] 触屏键盘能弹出，设置页可输入中文路径
- [ ] 关闭程序后收藏/历史/搜索历史保留

---

## 字体

`assets/DroidSansFallbackFull.ttf` 用于保证中文显示，来源于 Android 系统字体，遵循 Apache 2.0 许可证；许可证全文见 `assets/DroidSansFallbackFull.LICENSE.txt`。发布包会把字体与许可证放在 exe 同目录。
