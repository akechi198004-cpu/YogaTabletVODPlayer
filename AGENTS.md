# Yoga Tablet 8：HM影视点播维护约束

- 所有回复以 `[您谦卑的AI奴仆]` 开始，语气谦卑平和；只提供已确认可访问、有依据的链接。
- 本目录为独立工作副本和独立 Git；LeTV/hm-video 源目录只读，不在源目录或其工具缓存运行构建/提交。不要改直播 APP 或 Yoga 桌面。
- 本会话承担 HM 点播执行维护；源文档旧 LeTV 六会话分工属于历史上下文，不要求自动派生审计会话。
- 目标 Lenovo B6000-F，Android 4.4.2 / API 19。只按用户明确需求修改；保持包名 com.hm.video.letv、现有界面及行为，避免无关重构。
- APK 实际源码 android_app/src/main/；模块 android_app/app/ 通过 sourceSets 引用。网页 src/ 不是 APK 实现。
- 检查实际打包且运行可达的代码和资源；Java 脱糖不能补 Android 框架 API，也不能消除依赖库的平台版本检查。构建或 API 23 测试通过不等于 API 19 可运行。
- 不擅自增删权限、改变默认应用或设备设置。不安装/更新设备 APK，不刷机；这些操作须有用户明确要求。
- 每完成一组修改，在本目录本地 Git 提交，不推送；先检查差异，执行相关测试及 debug APK 构建，交回提交、APK 路径、实际验证范围和未验证事项。
- 当前构建入口 build-yoga.ps1；工具和缓存均在忽略的 .build_cache/tools/。不要运行旧 build-letv.ps1、device-test-*/build.ps1 或安装脚本；旧入口不适合本工作目录。
- 当前维护入口 YOGA_MAINTENANCE.md；README 的旧 LeTV 安装结果不代表 Yoga 设备状态。
- Python 优先 C:/Users/sz_jm/AppData/Local/Python/bin/python.exe，备用 C:/Users/sz_jm/AppData/Local/Programs/Python/Python310/python.exe；Git C:/Program Files/Git/cmd/git.exe。不要假定系统 Node/NPM 可用。
- 边界清晰的后续编码任务可按用户提供的 Cursor Agent CLI 规范委派；必须由 Codex 检查差异及实际测试。非编码检查不需委派。
