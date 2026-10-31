# Yoga Tablet 8 HM影视维护基线

检查日期：2026-10-29。目标：Lenovo B6000-F / Android 4.4.2 / API 19。

## 工作区与范围

- 源：`C:\Users\sz_jm\CodexWorkSpaces\LeTV\hm-video`，基线提交 `2286fd7`。
- 独立副本：`C:\Users\sz_jm\CodexWorkSpaces\YogaTablet8\vod`。复制了 111 个 Git 跟踪文件的当前内容及独立 `.git`，不是共享 Git 元数据的 worktree，没有配置远程。
- 初始副本与源 HEAD 无差异；源码逐文件 SHA-256 记录在忽略的 `.build_cache/source-sha256.json`。
- 仅准备工作区、构建入口和检查说明；未改应用源码、包名、版本、权限或运行行为，未操作设备。源 README 中 LeTV/X4-43 的部署记录仅属历史。

## 工程和构建

- `android_app/` 是 Gradle 根，应用模块 `app/`；sourceSets 指向 `android_app/src/main/{java,res,AndroidManifest.xml}`。
- `android_app/app/src/test/` 为现有 JVM/Robolectric 测试。网页 `src/`、`public/` 和 React/Vite 配置不参与 APK 编译。
- applicationId `com.hm.video.letv`；namespace / Kotlin 包根 `com.hm.video`；versionCode 6，versionName `2.4.0-tv`。
- 当前 minSdk **23**，compileSdk / targetSdk **34**。Android Gradle Plugin 8.5.2，Kotlin 1.9.24，Gradle 8.9，JDK 17；Java/Kotlin 字节码目标 17，启用 desugar_jdk_libs 2.1.2。
- 当前依赖包括 OkHttp 4.12.0、Okio 3.6.0、jsoup 1.17.2、Gson 2.10.1、ZXing core 3.5.3、Media3 1.4.1。
- 源清单权限为 INTERNET、ACCESS_NETWORK_STATE、WAKE_LOCK；最终 APK 还含既有 AndroidX Core 1.9.0 合并的签名级 `com.hm.video.letv.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`，本轮没有新增或删除权限。启动 Activity 为 `com.hm.video.ui.MainActivity`，Application 为 `com.hm.video.HMVideoApplication`。
- 工具及离线依赖缓存从 LeTV 共用工具只读复制到 `.build_cache/tools/`，不复用源目录中的可写缓存。新入口 `./build-yoga.ps1` 运行 `assembleDebug testDebugUnitTest lintDebug --offline`；项目无 Gradle Wrapper 文件，脚本使用本地 Gradle 8.9。
- APK：`android_app/app/build/outputs/apk/debug/app-debug.apk`。这是原始 minSdk 23 检查基线，**不能安装到 API 19**，不是已完成适配的发布包。
- 旧构建/安装入口依赖原目录结构，不在此副本使用。构建成功与 API 19 适配是不同验收项。

## API 19 初查：待后续明确需求再修复

| 项目 | 证据及运行路径 | 判断 |
| --- | --- | --- |
| 安装门槛 | `app/build.gradle.kts` 的 minSdk=23 | API 19 无法安装原始 APK；单独降低数值不能完成适配。 |
| 网络库 | OkHttp 4.12.0 原始 JAR 的 `AndroidPlatform` 检查 SDK>=21，否则抛 IllegalStateException。基线 APK 的 classes11.dex 确实包含该类，但 D8 按 minSdk=23 优化后移除了这段检查；其 `isCleartextTrafficPermitted` 的 SDK<24 分支直接调用 API 23 的 NetworkSecurityPolicy。首页/分类/搜索/海报/解析通过 `XingfanClient` 使用该库。 | 不能把 JAR 的异常误报为此 APK 仍存在的指令；依赖本身不支持 API 19，降低 minSdk 后须重建并重新核对。证据 `.build_cache/okhttp-AndroidPlatform.txt`（JAR）和 `AndroidPlatform-apk-dex.txt`（实际 APK）。 |
| 主题 | `res/values/styles.xml` 基础主题继承框架 `Theme.Material.NoActionBar`，没有 values-v21 分离和旧平台主题。所有页面使用该主题。 | 框架 Material 主题自 API 21 才有；需 API 19 主题兼容并保留现有视觉。 |
| 首页精选 | `HeroCarouselView.kt` 构造时无版本保护地调用 `Resources.getDrawable(id, theme)` 和 `clipToOutline=true`；首页创建该控件。classes9.dex 反汇编确认两个调用实际存在。 | 两者为 API 21 接口，需要 API 19 替代实现。FrameLayout 的 foreground 本身不列为新 API 缺陷。 |
| 图标资源 | 实际 APK 仅包含五个未分版本的 vector 启动/播放器图标 XML，未见同名 PNG 回退；aapt xmltree 确认播放图标根节点是 vector。播放器平台 ImageButton 的 android:src 直接引用。 | API 19 框架不能直接加载 vector；须处理并重新核对资源及布局加载。 |
| 多 DEX | 当前 APK 实际有 classes.dex 到 classes11.dex；Application 继承平台 Application，未集成 pre-21 multidex 初始化。 | API 19 不原生加载多 DEX。降低 minSdk 后应检查最终 DEX 数量、主 DEX 和启动依赖；不能只检查清单。 |
| 播放器 | 本地九个 Media3 1.4.1 AAR 清单均声明 minSdk=19；`PlayerActivity` 使用 ExoPlayer、HLS 和 PlayerView。 | 不能因 Media3 名称断言必须降级；还需核查传递依赖、打包代码的版本保护、API 19 实际解码/切线/倍速。 |
| TLS | `LegacyTls` 在 SDK<24 增补 ISRG Root X1，使用 SSLContext("TLS")；OkHttp 与 Media3 HttpsURLConnection 分别走请求路径。 | 根证书扩展不等于 KitKat 已启用所需协议及密码套件；待验证实际 HTTPS 和 HLS 路径。 |
| 测试 | 当前 Robolectric 测试显式 SDK 23；播放器测试用 fake ExoPlayer，不运行 onCreate/initPlayer 或真实解码。当前 lint 按 minSdk=23 检查。 | 现有测试和 lint 通过不能证明 API 19 安全。 |

这是重点检查，不是全部依赖的完整 API 可达性证明。测试源码的 java.time 用法不等于 APK 缺陷；已受 SDK>=28 保护的 longVersionCode 不列为 API 19 缺陷。

官方依据：已访问 [Square 的 OkHttp 平台要求说明](https://developer.squareup.com/blog/okhttp-3-13-requires-android-5/) 和 [Android Resources API 文档](https://developer.android.com/reference/android/content/res/Resources)。具体依赖结论以本机打包版本及反汇编为准。

## 本轮验证

- 在本副本运行 `build-yoga.ps1`：`assembleDebug`、`testDebugUnitTest`、`lintDebug` 全部成功，55 个任务实际执行；73 项测试，0 失败/错误/跳过；lint 0 错误、131 警告，未顺带修改既有警告。
- 测试报告：`android_app/app/build/reports/tests/testDebugUnitTest/index.html`；lint 报告：`android_app/app/build/reports/lint-results-debug.html`；构建日志：`.build_cache/baseline-build.log`。
- 初次受限构建无法连接 Gradle 本机进程；获准在受限环境外执行后，清除冲突的旧 ANDROID_SDK_HOME，隔离构建成功。脚本会恢复进程环境变量。
- 实际 APK：包名 `com.hm.video.letv`，versionCode 6，versionName `2.4.0-tv`，minSdk 23，targetSdk 34，7,362,468 字节。
- APK SHA-256：`94f20fa2be74c1a314ef50f33903e30d78471169e01eb6bd80ce6dc714bb123e`。未打入原生 `.so`，但这不证明设备解码器或 TLS 可用。
- 已检查 APK 清单（含依赖合并权限）、DEX 中首页 API 调用与 OkHttp 分支，以及图标打包资源；实际证据在忽略的 `.build_cache/`，不是仅依据网页或 Gradle 声明判断。
- 原目录 111 个跟踪文件再次逐一比对初始 SHA-256，全部未变。本轮只修改本副本说明和构建辅助脚本；未运行 adb、未安装、未改设置。
- **未验证**：API 19 启动/主题/布局、真实网络与 TLS、硬件解码/HLS/倍速/切线、触控与旋转、API 19 多 DEX 启动及完整依赖可达性。本轮没有进行 API 19 功能适配，等待后续具体需求。

## 第二轮：真机适配（2026-10-29，提交 b32f6fd / 3f8cbf8）

### 先复核设备（不沿用文档假设）

```
adb -s 192.168.0.56:5555 shell getprop ro.build.version.sdk   -> 19
adb ... getprop ro.build.version.release                      -> 4.4.2
adb ... getprop ro.product.cpu.abi / ro.sf.lcd_density        -> armeabi-v7a / 213
adb ... wm size                                               -> Physical size: 1280x800
adb ... pm list features | grep touchscreen                   -> 存在 touchscreen
```

结论：本机有 `FEATURE_TOUCHSCREEN` 且 UI mode 不是 television，原 `isTvDevice()`
返回 false，所以装的是手机布局。源码没有落后于 LeTV 源（文件清单一致）。

### 已修复（按真机证据）

| 问题 | 证据 | 处理 |
| --- | --- | --- |
| HTTPS 全部失败 | logcat `error:1407742E ... tlsv1 alert protocol version`；设备 `supported=SSLv3,TLSv1,TLSv1.1,TLSv1.2`，默认 `enabled=TLSv1` | `LegacyTls` 包装 SSLSocketFactory 强制 1.2/1.1/1.0；OkHttp 显式给 `MODERN_TLS` + 三版本 spec；额外根证书换成 ISRG X1/X2、Amazon Root CA1、DigiCert G2，仅作平台库兜底 |
| 启动图标/播放键空白 | 五个资源都是 `<vector>`，4.4 框架无法加载 | 用 GDI+ 生成 mdpi..xxhdpi PNG（形状同原 pathData），`AppCompatImageButton` 改回 `ImageButton` |
| 界面不是"顶栏导航＋宽幅海报" | 平板被判成非 TV | 新增 `DisplayProfile.isLeanbackLayout()`：电视、无触屏或宽度 ≥600dp 都走 leanback 布局；MainActivity / DetailActivity 采用，PlayerActivity 保持原判定 |
| 首页失败无日志 | `runCatching` 静默吞异常 | MainActivity 用 `HMNetwork` 打印异常堆栈 |
| 搜索页"手机输入" | 平板自带键盘 | 删除 `remoteinput/*`、二维码弹窗、按钮与单测，并移除 zxing 依赖 |

### 真机结果（B6000-F）

- 首启 MultiDex 首次解压约 12s，之后冷启动 1s 内；无 FATAL。
- 网络：`200 www.xingfan.cc/`、`200 cdn.04pic.com/...`；`HMTls: TLS 1.2 enabled, extra roots merged (SDK 19)`。
- 首页：顶栏 5 个 Tab + 200dp 轮播（黑帮领地第二季）+ 6 列海报，图片全部出图。
- 分类（558 页 / 13374 部）、榜单、更新（10 页 / 224 部）、详情页渲染正常。
- 搜索：输入「繁花」出结果，键盘随输入框弹出，界面无"手机输入"按钮。
- 播放《警之光第四季》第 01 集：起播、字幕、进度 00:50/58:21；倍速 1.0x→1.25x、
  拖动到 41:49、竖屏/横屏切换、息屏再亮后继续播放均正常；该时段无
  FATAL/ANR/MediaCodec 错误。
- 《诡术》（2026 新片）播放页提示"播放源解析失败"，属该条线路失效，换片可播。

### 未验证 / 待办

- 5 分钟连续播放计时（HLS 与直链各一条）尚未在无人干扰下确认。
- 方向键（旋钮/外接遥控）遍历、收藏增删、分页翻页未逐项回归。
- Media3 在 4.4 上的长时间内存/温度表现未测。
- 单元测试与 lint 只在整包状态下通过；拆分后的提交未单独复跑。

## 第三轮：平板界面比例调整（2026-10-29）

- 首页顶栏从 72dp 收至 60dp，减小水平留白与导航字号；精选轮播从 200dp 调至 145dp，卡片标题改为单行，收紧精选区和海报网格上下边距。
- 播放器返回/方向/倍速/下一集按钮高度从 32dp 增至 40dp，字体和左右触控留白随之增大；中央播放、快退/快进触控区也适度放大。
- `build-yoga.ps1` 完整通过（Debug APK、单测、lint）；新 APK 已通过 adb 安装到 B6000-F。
- 真机截图确认首页 1280×800 横屏能完整显示一排六张海报（含标题与分类）；播放器已实际起播《警之光第四季》，新尺寸的返回、播放、进度和线路控件均在画面内。
- 采样脚本运行 5 分 41 秒：10 次采样均为 `PlayerActivity` 前台、HM 进程存活；总 PSS 约 99–108 MB，Dalvik Heap 约 45–53 MB，未见持续增长。日志中 6 条 FATAL/ANR 候选实际是 ADB_SERVICES 记录的 grep 命令文本，过滤后无 HM 应用崩溃/ANR。
- 本次没有在采样起止点都记录媒体进度，也没能从日志确认流类型，因此不算通过“全程无停顿的 5 分钟播放”；HLS 与直链各自 5 分钟仍待验证。

## 第四轮：播放器底部控制栏（2026-10-29）

- 线路切换移至底部栏左侧的横向滚动区；倍速与“下一集”固定在同一行右侧。移除重复的底部方向按钮，只保留顶部竖屏/横屏切换。
- `build-yoga.ps1` 完整通过并安装到 B6000-F。真机 1280×800 播放页确认四个线路入口在左、倍速和下一集在右，单行显示且无重叠；截图 `.build_cache/shots/49-bottomrow-player.png`。
- 本轮只验证了布局和真实播放器页面显示；线路切换后的起播与 HLS/直链长播仍待单独验收。

## 第五轮：四项隐患修补（2026-10-29，基线 a6106e6）

### 1. 页面总时限覆盖正文

- 页面客户端使用 OkHttp `callTimeout=20s`，覆盖 DNS、连接、响应头和正文读取；解析服务请求也走同一限制。
- `PageCallScope` 为前台页面请求提供取消范围；图片使用独立客户端，视频流保留独立长读超时。
- 本地慢速正文测试覆盖目录请求和解析服务请求。

### 2. 进程回收后恢复当前剧集

- 保存/恢复当前集、位置、暂停意图和倍速；不保存签名播放直链。
- 恢复时使用当前集历史与 Bundle 位置，避免恢复到初始集或覆盖另一集历史。

### 3. 音频焦点与耳机拔出

- Activity 管理音频焦点和耳机拔出暂停，区分用户暂停、临时/永久焦点丢失；自动暂停不会在回前台时无条件起播。

### 4. 无后缀/重定向 HLS

- 使用响应类型、最终地址和清单/容器特征判断媒体类型；未知地址不默认当 HLS。
- 受控本地样例覆盖普通/无后缀/重定向 HLS、MP4 与查询参数含 `.m3u8` 的 MP4。

### 本轮结果与未验证

- `build-yoga.ps1` 全通过：assembleDebug、testDebugUnitTest、lintDebug；84 项测试通过，lint 0 错误。
- 验证为 Robolectric/JVM 与本地受控 HTTP，不是 API 19 真机验收。耳机/焦点事件、系统进程回收恢复和真实线路仍待真机确认。

## 第六轮：三项遗漏修补（2026-10-29，基线 550fbc5）

### 1. 请求取消代次竞态

- `PageCallScope.newRequest()` 为每个前台操作签发代次 ticket；旧线程晚启动时会在发出网络请求前被拒绝，页面销毁会使所有旧 ticket 失效。
- 一个 ticket 可顺序完成播放页与解析服务请求；缓存后台刷新明确使用无 ticket 的路径，不会取消前台 Call。过期操作在缓存写入前再次核验代次。
- 测试以可控线程顺序覆盖旧任务晚启动、销毁后迟到注册，以及真实仓库 `scheduleRefresh` 与前台慢响应交错。

### 2. 音频焦点申请失败

- 播放条件现在要求用户播放意图、Activity 前台状态及成功获得音频焦点同时成立。申请失败时保留用户意图但保持暂停；生命周期返回不会自动重试失败申请，用户再次操作可重试。
- Robolectric AudioManager 拒绝焦点申请的测试覆盖暂停、保留意图、前后台往返和迟到 GAIN 回调。

### 3. 代理媒体判断与嵌套 HLS

- 直接探测与 HLS 代理共用响应分类器；去掉对完整 URL 查询串 `.m3u8` 子串的判断。代理对未知/可能的清单检查非消费式响应前缀，MP4 字节保持原样。
- 主清单仅对 `STREAM-INF`、媒体/iframe rendition 等子清单 URI 添加 HLS 提示；媒体分片与密钥不继承提示。无后缀子清单通过 `#EXTM3U` 识别后继续改写相对 URI。
- 本地 socket/HTTP 测试实际执行代理响应处理：查询参数含 `.m3u8` 的 MP4 字节未改写；主清单→无后缀子清单→相对分片及密钥完整验证。

### 本轮结果与未验证

- `build-yoga.ps1` 全通过：assembleDebug、testDebugUnitTest、lintDebug；89 项测试通过，lint 0 错误（133 条警告）。
- 测试均为 Robolectric/JVM 与本地受控服务，不是 API 19 真机验收。需在 B6000-F 验收真实焦点拒绝/恢复、页面切换时序及实际代理线路行为。

## 第七轮：代码审计与生命周期修补（2026-11-01，基线 3181a4f）

### 审计范围与结论

- 复核构建入口、数据层（XingfanClient/XingfanRepository/XingfanParser）、缓存（MetadataCache/DiskStore/ImageCache/ImageLoader）、播放（PlayerActivity/HlsLocalProxy/MediaStreamProbe/LineProbe）及页面请求取消链路。
- 本轮审查未发现可复现的 P0 崩溃、数据破坏或安全阻断问题；确认并修复一处 P1 生命周期资源问题。

### 1. PlayerActivity 进度轮询在后台持续调度（P1）

- 问题：`updateProgressRunnable` 在 `onPlayerReady()` 启动后每秒自我重排；`onStop()` 未移除，直到 `onDestroy()` 才停止。页面不可见期间仍会持续唤醒主线程，属于后台资源浪费。
- 修复：新增 `startProgressUpdates()`，仅在 `activityStarted && isVideoPrepared` 时启动；`onStop()` 移除回调；`onStart()` 在播放器已准备好时恢复。前台进度刷新、暂停落盘和恢复定位行为不变。
- 回归测试：`progressPollingStopsInBackgroundAndRestartsWhenVisible`，用暂停 Looper 的 `idleFor(6s)` 验证 `onStop` 后队列为空、`onStart` 恢复、再次 `onStop` 清空。已临时撤销修复确认该测试失败，再恢复修复。

### 2. 审计后未修改项

- `HlsLocalProxy` 仅绑定 `127.0.0.1`；未知或异常上游响应回 502，媒体分片与密钥不重写，保持现状。
- `DiskStore` 的 LRU 字节统计是近似值，读写/裁剪存在并发窗口；缓存异常只按 miss 处理，不影响收藏、历史等业务数据，未扩大改动。
- 过期 ticket 在网络失败回退时可能短暂读取磁盘缓存，但页面代次校验仍阻止过期结果展示；未改变现有缓存语义。
- 其余 lint 警告为既有资源、文案、依赖提示，不是本轮确认的缺陷，未做无证据的风格性修改。

### 本轮结果与未验证

- 修改前基线：`build-yoga.ps1` 通过，89 项测试、0 失败，lint 0 错误 / 133 警告。
- 修改后最终：`build-yoga.ps1` 通过，assembleDebug、testDebugUnitTest、lintDebug 成功；90 项测试、0 失败，lint 0 错误 / 132 警告。
- APK：`android_app/app/build/outputs/apk/debug/app-debug.apk`，12,819,504 字节，SHA-256 `db071fbd525bbb256d47e76c406a51c4ab5505bfd72c43bfac52e0fbcbcb256d`。
- 验证为 Robolectric/JVM 与本地受控服务，不是 API 19 真机验收；本轮未安装/更新设备，未访问真实内容站点。后台轮询停止与恢复仍需在 B6000-F 上结合真实播放、返回桌面和锁屏场景确认。

## 第八轮：解析失败界面增加返回入口（2026-11-01，基线 ef6ed8a）

- 问题：播放源解析失败、无可用线路或线路全部失败时，整屏错误卡片只能点击重试，没有可点的返回入口，只能依赖系统返回键。
- 修改：`layout_loading_view.xml` 增加 `loading_back_button`，默认隐藏；仅 `showPlaybackMessage()` 处于可重试错误态时显示，点击 `finish()` 返回详情页。加载中与正常播放态不显示。底部提示同步改为“按确认键重试 · 点‘返回’或按返回键退出播放器”。
- 遥控可达性：错误态下加载层 `nextFocusDownId` 指向返回按钮，按方向键下可聚焦；触摸可直接点击。
- 回归测试：`parseFailureShowsBackButtonAndReturnsToDetail`，验证加载态隐藏、错误态显示与焦点指向、点击后 Activity 进入 finishing。
- 验证：`build-yoga.ps1` 通过，91 项测试 0 失败，lint 0 错误 / 132 警告；APK 12,820,418 字节，SHA-256 `a281693260d4cb5d4f25225bbea0d1900aca07ef78da33ba525f1b658fe3a7b9`。仅为 Robolectric/JVM 验证，未安装设备；真机需确认错误态按钮的触控与遥控焦点表现。
