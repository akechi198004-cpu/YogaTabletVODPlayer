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
