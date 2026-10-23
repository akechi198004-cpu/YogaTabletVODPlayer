> 历史快照：仅记录当时的代码、验证及部署过程，不代表当前实现、当前缺陷或电视当前版本。文中旧行号、相对路径和“已安装/未安装”等状态只适用于记录当时。当前入口为项目根目录 README.md。

# HM影视电视端代码审计

日期：2026-10-21。范围：原生 Android 全部 Kotlin 源码、布局和主题、Gradle 与打包脚本，以及网页预览的状态、按键和下载流程。本轮未修改业务实现。

## 结论与验证边界

当前版本存在可复现的播放与遥控器问题，暂不适合作为已完成电视适配的版本交付。上一轮新增播放器代码也包含缺陷；编译成功不足以证明运行正确。

本次使用本地缓存进行了离线检查，未安装或下载组件：

- 5 项临时 Robolectric / Android API 23 诊断探针全部确认了下文对应的缺陷。这些断言用于确认“当前错误行为确实存在”，不代表功能验收通过。临时测试源码随后删除。
- 继续审计时，额外用已编译类和本地缓存 JAR 运行了 2 项 JVM 解析检查：确认带下划线分类名被拆错，以及含点号的 CSS 类名无法按当前选择器匹配。未联网或安装依赖。
- 核对了本地 Media3 1.4.1 的按键分发字节码：关闭内置控制器后，内置媒体按键分发也关闭，不能补救 Activity 中遗漏的媒体键处理。
- 项目原有的 1 项单元测试通过；它只检查 Jsoup 能否初始化并解析简单 HTML，不覆盖播放、遥控器或续播。
- Android lint 未通过：9 个 error、122 个 warning。9 个 error 均位于生产播放器代码，原因是使用 Media3 不稳定 API 时缺少显式 opt-in。
- 没有运行模拟器、电视实机或真实视频源；原始电视上的“退出”究竟是崩溃、系统回收还是播放器错误，仍需日志才能归因。本文没有把代码风险当成已捕获的实机崩溃。
- 网页依赖未安装，未执行网页构建；网页结论来自源码审查。

清理结果：未发现残留 SDK Manager 下载进程；SDK 下不存在此次尝试产生的 `emulator`、`system-images` 或 `.temp` 下载目录。保留原有编译工具、SDK 平台和项目构建缓存。

## P1：应先处理

### A01：HLS 兼容播放分支无法选中 HLS 播放器

位置：[PlayerActivity.kt:391](C:/Users/sz_jm/CodexWorkSpaces/LeTV/hm-video/android_app/src/main/java/com/hm/video/ui/PlayerActivity.kt:391)、[HlsLocalProxy.kt:42](C:/Users/sz_jm/CodexWorkSpaces/LeTV/hm-video/android_app/src/main/java/com/hm/video/player/HlsLocalProxy.kt:42)。

直连失败后，HLS 地址被改为 `/hls?url=...m3u8`，随后仅通过 `MediaItem.fromUri` 交给播放器。Media3 根据 URI 路径推断类型，不使用 query 中的 `.m3u8`；代理路径只有 `/hls`，因此会选中普通渐进式媒体源，无法解析 HLS 播放列表。

离线探针确认：原始 `.m3u8` URI 被识别为 HLS，同一源的本地代理 URI 被识别为 OTHER。这会使“正在尝试兼容播放方式”这一恢复路径继续失败。应在播放模型中保留真实媒体类型，为 HLS 显式设置 MIME type，而非依赖代理 URL 后缀。

### A02：榜单、更新、收藏、历史和搜索结果在旧版电视上仍不可聚焦

位置：[MainActivity.kt:802](C:/Users/sz_jm/CodexWorkSpaces/LeTV/hm-video/android_app/src/main/java/com/hm/video/ui/MainActivity.kt:802)、[MainActivity.kt:1198](C:/Users/sz_jm/CodexWorkSpaces/LeTV/hm-video/android_app/src/main/java/com/hm/video/ui/MainActivity.kt:1198)、[SearchActivity.kt:252](C:/Users/sz_jm/CodexWorkSpaces/LeTV/hm-video/android_app/src/main/java/com/hm/video/ui/SearchActivity.kt:252)。

这些入口是只设置点击监听的 `LinearLayout`，未设置 `isFocusable=true`。API 23 探针确认：设置点击监听后卡片仍不可聚焦。遥控器无法选中卡片，自然无法进入详情。首页布局切换的 `TextView` 也存在同类遗漏；已有海报网格明确设置了焦点，因此不能据此认定所有页面都已支持遥控器。

应统一封装可聚焦卡片/操作控件，并逐页检查方向键可达性、焦点高亮、点击后焦点恢复。

### A03：过期请求会覆盖当前栏目或搜索结果

位置：[MainActivity.kt:189](C:/Users/sz_jm/CodexWorkSpaces/LeTV/hm-video/android_app/src/main/java/com/hm/video/ui/MainActivity.kt:189)、[MainActivity.kt:383](C:/Users/sz_jm/CodexWorkSpaces/LeTV/hm-video/android_app/src/main/java/com/hm/video/ui/MainActivity.kt:383)、[SearchActivity.kt:193](C:/Users/sz_jm/CodexWorkSpaces/LeTV/hm-video/android_app/src/main/java/com/hm/video/ui/SearchActivity.kt:193)。

每次加载启动独立线程，回调直接替换页面，没有请求编号、当前栏目检查或取消机制。例如首页请求未完成时切换到电视剧分类，首页稍后返回仍会覆盖分类内容；导航高亮却保持分类。连续搜索两个词也会由最后返回的请求决定最终结果。分类请求还跨线程读取可变筛选字段，可能使标题、筛选状态和结果来自不同操作。

应为每次请求保存不可变参数和递增编号；仅接收当前有效请求的结果，并在页面退出/销毁时取消或忽略回调。

### A04：网络失败仍伪装成有效片库和排行榜

位置：[MainActivity.kt:177](C:/Users/sz_jm/CodexWorkSpaces/LeTV/hm-video/android_app/src/main/java/com/hm/video/ui/MainActivity.kt:177)、[MainActivity.kt:245](C:/Users/sz_jm/CodexWorkSpaces/LeTV/hm-video/android_app/src/main/java/com/hm/video/ui/MainActivity.kt:245)、[MainActivity.kt:377](C:/Users/sz_jm/CodexWorkSpaces/LeTV/hm-video/android_app/src/main/java/com/hm/video/ui/MainActivity.kt:377)。

首页、分类、榜单和更新页在请求失败后继续展示写死的影片、拼接的详情地址、Unsplash 图片及评分；分类失败还可能用首页最近更新代替筛选结果，并固定声称有 10 页。这些数据既不是服务器结果，也不是已验证可播放的离线缓存。

用户会看到“有影片、有源”的表象，但点击进入的地址可能无效，筛选和翻页也会失真。应删除生产环境的示例兜底；失败时展示可重试状态，若使用真实缓存则明确标注缓存时间和离线状态。

### A05：网页下载仍提供旧 APK，和当前改造版不是同一个安装包

位置：[App.tsx:265](C:/Users/sz_jm/CodexWorkSpaces/LeTV/hm-video/src/App.tsx:265)、[app/build.gradle.kts:11](C:/Users/sz_jm/CodexWorkSpaces/LeTV/hm-video/android_app/app/build.gradle.kts:11)。

已用本地 aapt2 读取两个 APK 的实际元数据：

| 产物 | 包名 | 版本 | 最低 Android API |
| --- | --- | --- | --- |
| `public/download/HMVideo-release.apk` | `com.hm.video` | 2.3.0 / code 4 | 21 |
| 当前 `app/build/outputs/apk/debug/app-debug.apk` | `com.hm.video.letv` | 2.3.1-letv / code 5 | 23 |

网页按钮指向前者；配套源码 ZIP、备用 APK 和 Base64 文件也仍是旧产物。用户从网页下载安装，拿不到本轮代码。两个包名不同，安装新版还会形成独立应用、独立收藏/历史，不能视为对旧应用的直接升级。

应明确电视版本的包名与迁移策略，统一下载产物、版本说明、签名和源码归档，并在打包后校验元数据及哈希。不能仅凭相同应用名称认定安装的是新版。

### A06：全局 HTTP 客户端关闭证书及主机名校验

位置：[XingfanClient.kt:19](C:/Users/sz_jm/CodexWorkSpaces/LeTV/hm-video/android_app/src/main/java/com/hm/video/data/api/XingfanClient.kt:19)。

`checkServerTrusted` 是空实现，`hostnameVerifier` 永远返回 true。页面、海报和代理流量因而接受任意证书，局域网或代理中的中间人可篡改请求结果。注释所说的旧电视证书兼容不能保证连接对象真实。

应恢复正常验证，针对实际证书兼容问题采用明确的受信任证书链或受控信任配置。新增 Media3 直连客户端使用另一套系统验证策略，因此还存在页面能加载、媒体直连失败的行为差异，需在旧电视上核对证书链。

### A07：所谓“本地代理”实际监听全部网络接口，且资源无上限

位置：[HlsLocalProxy.kt:22](C:/Users/sz_jm/CodexWorkSpaces/LeTV/hm-video/android_app/src/main/java/com/hm/video/player/HlsLocalProxy.kt:22)、[HlsLocalProxy.kt:55](C:/Users/sz_jm/CodexWorkSpaces/LeTV/hm-video/android_app/src/main/java/com/hm/video/player/HlsLocalProxy.kt:55)、[HMVideoApplication.kt:17](C:/Users/sz_jm/CodexWorkSpaces/LeTV/hm-video/android_app/src/main/java/com/hm/video/HMVideoApplication.kt:17)。

`ServerSocket(port)` 未限制到 loopback，应用启动就开启固定端口，接受任意 HTTP(S) 目标地址。若设备网络策略允许局域网访问，该服务会成为无认证转发端点。每条连接新建线程，读取请求头又没有 socket 超时或长度限制，慢连接可能持续占用线程和内存。

应只绑定 `127.0.0.1`，限制目标和并发，设置请求头大小与读超时，并让端口启动成功/失败有明确状态。此项来自源码审查，未对真实电视进行网络探测。

## P2：需要一并修复的功能与工程问题

### A08：续播记录不区分实际剧集，会串进度

位置：[PlayerActivity.kt:187](C:/Users/sz_jm/CodexWorkSpaces/LeTV/hm-video/android_app/src/main/java/com/hm/video/ui/PlayerActivity.kt:187)、[PlayerActivity.kt:207](C:/Users/sz_jm/CodexWorkSpaces/LeTV/hm-video/android_app/src/main/java/com/hm/video/ui/PlayerActivity.kt:207)、[UserStorageManager.kt:73](C:/Users/sz_jm/CodexWorkSpaces/LeTV/hm-video/android_app/src/main/java/com/hm/video/data/storage/UserStorageManager.kt:73)。

所有播放记录和进度更新都硬编码 `episodeNo=1`；恢复时只按作品详情 URL 取记录，不检查当前 episodeUrl。播放下一集时再次将 `initialSeekApplied` 设为 false，因此会把上一集的位置当作下一集的恢复点。存储层也把默认同为 1 的两个剧集视为同一集并继承进度。离线探针复现了“第二集继承第一集 600 秒进度”。

应传递真实剧集与线路身份，按稳定剧集标识恢复；自动下一集从零开始，线路切换才考虑保留同一集的进度。

### A09：遥控器调进度条只改显示，不真正 seek

位置：[PlayerActivity.kt:150](C:/Users/sz_jm/CodexWorkSpaces/LeTV/hm-video/android_app/src/main/java/com/hm/video/ui/PlayerActivity.kt:150)。

`onProgressChanged(fromUser=true)` 只更新文字，实际 seek 只发生在 `onStopTrackingTouch`。API 23 探针确认方向键会产生前一个回调，但不会产生触摸结束回调。因此遥控器能改变进度条刻度，却没有改变播放位置；下一次定时更新还会把它覆盖。

应单独提交键盘 seek，或在用户修改进度时采用统一的预览/提交机制。

### A10：每次缓冲恢复都会强制播放并抢回焦点

位置：[PlayerActivity.kt:106](C:/Users/sz_jm/CodexWorkSpaces/LeTV/hm-video/android_app/src/main/java/com/hm/video/ui/PlayerActivity.kt:106)、[PlayerActivity.kt:174](C:/Users/sz_jm/CodexWorkSpaces/LeTV/hm-video/android_app/src/main/java/com/hm/video/ui/PlayerActivity.kt:174)。

`STATE_READY` 并非只在首次准备完成时触发；seek 或重新缓冲完成后也会触发。当前每次回调都设置 `playWhenReady=true`、聚焦播放按钮并显示控制层。用户暂停后拖动到需要重新缓冲的位置，恢复 READY 后可能自行播放；正在操作其他按钮时也可能被抢焦点。

应把首次准备、后续缓冲和用户播放意图分开处理，仅在首次进入时安排初始焦点，并通过真实播放状态统一更新图标。

### A11：切集解析期间旧流继续播放，进入后台也没有暂停策略

位置：[PlayerActivity.kt:283](C:/Users/sz_jm/CodexWorkSpaces/LeTV/hm-video/android_app/src/main/java/com/hm/video/ui/PlayerActivity.kt:283)、[PlayerActivity.kt:423](C:/Users/sz_jm/CodexWorkSpaces/LeTV/hm-video/android_app/src/main/java/com/hm/video/ui/PlayerActivity.kt:423)、[PlayerActivity.kt:437](C:/Users/sz_jm/CodexWorkSpaces/LeTV/hm-video/android_app/src/main/java/com/hm/video/ui/PlayerActivity.kt:437)。

切集先更新 epUrl/标题再请求新源，但 `loadPlayInfo` 不停止旧播放器或进度任务；新源解析失败后旧声音仍可能播放，画面却显示失败提示。播放器只在 onDestroy 释放，没有 onPause/onStop 的前后台策略，因此按 Home 或切换应用时旧流仍可能运行。网络线程回调也未随 Activity 销毁失效。

应在切集开始时停止旧媒体与进度记录，并对生命周期、当前请求和前后台播放行为做统一处理。当前没有后台媒体服务，不能将继续播放解释成完整的后台播放支持。

### A12：“重试”可能只重新读取旧缓存

位置：[XingfanRepository.kt:99](C:/Users/sz_jm/CodexWorkSpaces/LeTV/hm-video/android_app/src/main/java/com/hm/video/data/api/XingfanRepository.kt:99)、[XingfanRepository.kt:109](C:/Users/sz_jm/CodexWorkSpaces/LeTV/hm-video/android_app/src/main/java/com/hm/video/data/api/XingfanRepository.kt:109)。

详情缓存 10 分钟，成功解析的播放信息缓存 30 分钟；这两个接口都没有强制刷新参数。详情为空或播放 URL 已失效时，界面“重新读取/重试”仍调用相同接口并命中缓存，不会重新向源站获取新数据。

应提供显式失效或 forceRefresh 路径，用户重试及已确认线路失效时跳过缓存；空详情也应避免长时间缓存。

### A13：备用剧集解析的 CSS 选择器写错

位置：[XingfanParser.kt:355](C:/Users/sz_jm/CodexWorkSpaces/LeTV/hm-video/android_app/src/main/java/com/hm/video/data/api/XingfanParser.kt:355)。

`a[href$=\\.html]` 是 CSS 属性后缀匹配，不是正则表达式；多余的反斜杠使普通 `.html` 链接无法匹配。离线探针向该分支输入合法剧集链接，得到 0 条线路。源站没有预期的 tab 标记时，明明有剧集链接也会被报告为无源。

应修正后缀选择器，再按实际线路 ID 分组和去重；当前备用分支还会把所有线路的集数混到“默认线路”。

### A14：代理把上游错误伪装成成功，并使用重定向前的基准地址

位置：[HlsLocalProxy.kt:106](C:/Users/sz_jm/CodexWorkSpaces/LeTV/hm-video/android_app/src/main/java/com/hm/video/player/HlsLocalProxy.kt:106)。

无论上游返回 403、404 还是 500，HLS 分支都返回 200；其他分支除了 206 也统一返回 200。播放器因此把错误页当成媒体，失去按 HTTP 错误分类和切换线路的依据。其次，OkHttp 跟随重定向后仍按原始 targetUrl 重写相对分片地址，若最终 CDN 目录不同，子播放列表、分片或密钥会请求到错误位置。

应透传/正确映射上游状态，以最终响应 URL 为相对路径基准，并在错误响应上停止媒体重写。

### A15：详情“立即播放”只看第一条线路，也不跟随所选线路

位置：[DetailActivity.kt:119](C:/Users/sz_jm/CodexWorkSpaces/LeTV/hm-video/android_app/src/main/java/com/hm/video/ui/DetailActivity.kt:119)。

第一条线路为空但后续线路有剧集时，按钮仍显示无可播放剧集；用户选择其他线路后，该按钮也仍播放第一条线路第一集。线路点击时重建整个按钮区且没有恢复焦点，还重复调用了剧集渲染。

应从当前所选线路取可播放剧集；初始选择跳过空线路，重建控件时恢复语义焦点。

### A16：海报按原图解码，低内存电视存在退出风险

位置：[ImageLoader.kt:18](C:/Users/sz_jm/CodexWorkSpaces/LeTV/hm-video/android_app/src/main/java/com/hm/video/util/ImageLoader.kt:18)、[ImageLoader.kt:62](C:/Users/sz_jm/CodexWorkSpaces/LeTV/hm-video/android_app/src/main/java/com/hm/video/util/ImageLoader.kt:62)。

四个工作线程直接 `BitmapFactory.decodeStream`，未按控件尺寸采样或限制解码像素；缓存还有最少 16MB 的下限。遇到大海报时，多个完整 Bitmap 可在入缓存前同时占用大量内存。`OutOfMemoryError` 不属于当前捕获的 Exception，可能使低内存设备退出。此项是条件明确的风险，未取得用户电视上的 OOM 日志。

应按实际显示尺寸解码，限制图片尺寸与队列、取消离屏请求，并按设备内存预算设置缓存。另有一个较小的正确性问题：空 URL 和缓存命中分支在更新 tag 前返回，可能使前一项异步请求覆盖当前图片。

### A17：完整 Windows 构建检查当前无法通过

位置：[PlayerActivity.kt:93](C:/Users/sz_jm/CodexWorkSpaces/LeTV/hm-video/android_app/src/main/java/com/hm/video/ui/PlayerActivity.kt:93)、[build-letv.ps1:12](C:/Users/sz_jm/CodexWorkSpaces/LeTV/hm-video/build-letv.ps1:12)。

已离线执行 lint，生产播放器有 9 项 `UnsafeOptInUsageError`；项目标准脚本会同时运行 lint，因此即使单独 assembleDebug 能产 APK，标准构建仍会失败。应按 Media3 API 的要求显式声明 opt-in，并理解不稳定 API 的升级约束，而非直接禁用 lint。

### A18：旧 Linux 手工打包脚本不能编译新增 Media3 实现

位置：[build_apk.sh:41](C:/Users/sz_jm/CodexWorkSpaces/LeTV/hm-video/build_apk.sh:41)。

手工 Kotlin/D8 classpath 只列出 OkHttp、Okio、Jsoup 和 Gson，没有 Media3 或其传递依赖，也没有合并 AAR 资源流程。当前 Kotlin 已直接引用 Media3，沿用这条脚本会出现类缺失等构建错误。它还写死旧版本号和 API 21，与 Gradle 产物不一致。

应统一使用 Gradle 产物流程，停用或重写过时入口；本次没有在 Linux 环境执行该脚本，此结论来自明确的依赖缺失。

### A19：相对媒体地址缺少播放器服务页面基准

位置：[XingfanParser.kt:449](C:/Users/sz_jm/CodexWorkSpaces/LeTV/hm-video/android_app/src/main/java/com/hm/video/data/api/XingfanParser.kt:449)、[XingfanParser.kt:489](C:/Users/sz_jm/CodexWorkSpaces/LeTV/hm-video/android_app/src/main/java/com/hm/video/data/api/XingfanParser.kt:489)、[XingfanRepository.kt:120](C:/Users/sz_jm/CodexWorkSpaces/LeTV/hm-video/android_app/src/main/java/com/hm/video/data/api/XingfanRepository.kt:120)。

播放器服务 HTML 可能来自独立域名，但归一化函数把根相对地址拼到固定影视站域名，普通相对地址则原样返回。服务页若返回 `/media/abc.m3u8` 或 `media/abc.m3u8`，会变成错误站点地址或缺少 scheme 的 URI。实际媒体请求还统一用 epUrl 作 Referer，没有保留服务页来源。

应把服务页最终 URL 作为解析上下文，使用 URL/HttpUrl 的标准相对路径解析，并让来源头在播放模型中明确传递。是否有线路依赖服务页 Referer，需要实际源响应进一步确认。

### A20：海报信息区的类名含点号，当前 CSS 选择器匹配错误

位置：[XingfanParser.kt:62](C:/Users/sz_jm/CodexWorkSpaces/LeTV/hm-video/android_app/src/main/java/com/hm/video/data/api/XingfanParser.kt:62)、[XingfanParser.kt:104](C:/Users/sz_jm/CodexWorkSpaces/LeTV/hm-video/android_app/src/main/java/com/hm/video/data/api/XingfanParser.kt:104)。

源代码将 `.p-2.5 p`、`.p-2.5 span` 用作选择器，但 CSS 中点号会分隔类选择器，不能直接代表名为 `p-2.5` 的完整类。已用包含这一真实类名的 HTML 检查：可见片名为 Actual Title，却解析成图片 alt 的 Poster Alt；分类与评分均为 null。若图片也没有 alt，合法条目还会被当成空片名跳过。

应正确转义类名，或优先使用稳定的语义属性及容器结构，并对“图片没有 alt，但正文有标题”的页面保留回归检查。

### A21：带下划线的分类名无法与列表 URL 格式往返转换

位置：[XingfanParser.kt:582](C:/Users/sz_jm/CodexWorkSpaces/LeTV/hm-video/android_app/src/main/java/com/hm/video/data/api/XingfanParser.kt:582)、[XingfanConfig.kt](C:/Users/sz_jm/CodexWorkSpaces/LeTV/hm-video/android_app/src/main/java/com/hm/video/data/api/XingfanConfig.kt)。

配置包含 `dalu_variety` 和 `talk_variety`，列表构建器把完整分类名放进下划线分隔的路径；解析器却用 `([^_]+)` 读取分类名。离线检查 `/vodlist/dalu_variety_____hot_1.html` 得到 cat=dalu、type=variety、sort=空，分类与筛选字段整体错位。

应明确 URL 协议，按已知尾部字段解析或使用无歧义的字段编码，保证构造和解析互为逆操作。此处确认的是本地协议不一致，没有据此宣称这些分类在源站的实际路由已验证。

### A22：数据缓存没有容量限制，过期内容仍可能一直占用内存

位置：[XingfanRepository.kt:11](C:/Users/sz_jm/CodexWorkSpaces/LeTV/hm-video/android_app/src/main/java/com/hm/video/data/api/XingfanRepository.kt:11)。

各类缓存都是无容量限制的 ConcurrentHashMap，只有再次读取同一个 key 时才检查并删除过期值。用户不断浏览新影片、搜索新词或翻页时，之前不再访问的过期条目仍会被全局对象长期持有；TTL 不等于自动释放内存。包含大量剧集链接的详情会进一步放大占用。

应增加条目数或内存预算、访问淘汰和过期清理。此项是源码确认的无界保留行为，尚未测得真实电视上的内存增长曲线。

### A23：专用快进/快退键在控制层显示时失效

位置：[PlayerActivity.kt:460](C:/Users/sz_jm/CodexWorkSpaces/LeTV/hm-video/android_app/src/main/java/com/hm/video/ui/PlayerActivity.kt:460)、[PlayerActivity.kt:468](C:/Users/sz_jm/CodexWorkSpaces/LeTV/hm-video/android_app/src/main/java/com/hm/video/ui/PlayerActivity.kt:468)。

专用 MEDIA_REWIND / MEDIA_FAST_FORWARD 与方向键共用分支。当控制层显示且焦点位于普通按钮时，代码直接交回父类，不执行 seek；这些专用媒体键也不会像方向键一样移动焦点。Media3 的内置控制器已经关闭，检查其本地字节码也确认不会代为处理这些媒体键。

应将方向键导航与媒体按键命令分开。媒体播放/暂停分支还绕过了按钮图标更新，应从播放器真实状态统一同步 UI。

### A24：搜索结果没有翻页或继续加载入口

位置：[SearchActivity.kt:195](C:/Users/sz_jm/CodexWorkSpaces/LeTV/hm-video/android_app/src/main/java/com/hm/video/ui/SearchActivity.kt:195)、[SearchActivity.kt:231](C:/Users/sz_jm/CodexWorkSpaces/LeTV/hm-video/android_app/src/main/java/com/hm/video/ui/SearchActivity.kt:231)。

搜索请求总是传 page=1，收到 PageResult 后只取 items 渲染，丢弃 totalPages，也没有翻页按钮或加载下一页动作。当匹配作品不在第一页时，无论触摸还是遥控器都无法到达。

应为搜索实现可聚焦的分页/继续加载，并保留关键词、分类和请求编号；列表 URL 构建器的搜索翻页分支也需要继续传递分类条件，避免翻页后失去筛选。

### A25：播放失败提示与正常控制层没有形成互斥状态

位置：[PlayerActivity.kt:337](C:/Users/sz_jm/CodexWorkSpaces/LeTV/hm-video/android_app/src/main/java/com/hm/video/ui/PlayerActivity.kt:337)、[PlayerActivity.kt:400](C:/Users/sz_jm/CodexWorkSpaces/LeTV/hm-video/android_app/src/main/java/com/hm/video/ui/PlayerActivity.kt:400)、[activity_player.xml:42](C:/Users/sz_jm/CodexWorkSpaces/LeTV/hm-video/android_app/src/main/res/layout/activity_player.xml:42)。

布局中正常控制层绘制在加载/错误层之后，显示失败消息时只打开 loadingLayout，没有关闭控制层。失败状态仍可能被控制按钮覆盖；点击播放按钮又会因 isVideoPrepared=false 提示“视频正在连接，请稍候”，即使该次连接已经终止。若控制层原本隐藏，确认键还会优先打开控制层，再次确认才可能触发错误层的重试操作。

应明确区分解析中、缓冲中、播放中、暂停、失败、无源等状态；失败时独立显示可聚焦的重试/返回操作，确保文本、按键与实际连接状态一致。

## 网页预览的额外问题

- **最大化模式有两个进度计时器。** [App.tsx:234](C:/Users/sz_jm/CodexWorkSpaces/LeTV/hm-video/src/App.tsx:234) 和 [CinemaPlayerModal.tsx:151](C:/Users/sz_jm/CodexWorkSpaces/LeTV/hm-video/src/components/CinemaPlayerModal.tsx:151) 同时更新同一份 playTime；最大化后 currentActivity 仍是 player，两个计时器都会运行，模拟进度约以两倍速度前进。
- **Enter 可能触发两次按钮操作。** [App.tsx:440](C:/Users/sz_jm/CodexWorkSpaces/LeTV/hm-video/src/App.tsx:440) 中手动调用 `.click()`，但 preventDefault 列表没有 Enter。焦点在原生 button 上时，浏览器默认激活仍可能继续执行。应统一键盘事件所有权并阻止重复默认行为。
- **预览和原生实现是独立系统。** 网页使用 mockCatalog、图片及计时器模拟播放，没有真实媒体验证；代码浏览区也内嵌另一份 Kotlin 字符串。网页视觉、按键或假播放成功不能证明 APK 的布局、焦点或视频解码正确。应明确预览属性，并使源码与产物来自同一构建版本。

## 修复顺序

1. 修复真实播放链：A01、A08–A15、A19、A23、A25；用带相对分片、重定向、403、无源和自动下一集的可控输入验证。
2. 修复全站遥控器、解析和请求竞争：A02、A03、A20、A21、A24；覆盖首页、分类、搜索、榜单、更新、收藏、历史、详情、播放页。
3. 去掉示例片库并修正交付：A04、A05、A17、A18，保证用户拿到被审查的那一份 APK。
4. 收紧网络服务、缓存及图片资源：A06、A07、A14、A16、A22，再对低内存电视收集播放与崩溃日志。

建议保留的回归检查应断言正确行为，不能把本次用于复现缺陷的诊断断言直接当作通过标准。
