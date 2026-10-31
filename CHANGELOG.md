# CHANGELOG

## 0.3.1 - 2026-10-31

### 适配

- **目标版本升到贴吧 22.13.1.1**（versionCode 369950981，APK 取自设备）：`ConfigStore.TARGET_VERSION`、README、版本门禁测试字面量同步更新。
- **锚点全量重定位**：22.13.1.1 混淆名大洗牌且旧名被无关类复用，13 个方法锚点 + 1 个类锚点按结构指纹（方法表/父类/接口/字符串常量/调用方拓扑）重新定位，`tools/gen_anchors.py` 的 ANCHORS/CLASSES 表同步更新：
  - nt9→eu9（feed 条目包装接口，b()/d() 结构不变）、em8→tm8（HomePageAlaLiveThreadAdapter 调用方证实）、ssb→nsb、qy8→fz8（getFeedAd URL + asyncAfd 双指纹）、c18→r18、i18→x18、dw7→qw7、bie→ike、gje$a→nle$a；
  - 混淆参数/返回类型：fo6→po6、op6→yp6、y7a→i8a、ej9→vj9、rl8→gm8（AlaLiveAttentionCardView 绑定方法 c→b）。
- `Phase2Features` 中硬编码的 `com.baidu.tieba.c18`/`i18` 同步改为 `r18`/`x18`。
- 重跑 `gen_anchors`：55 方法 + 7 类全部命中（0 MISSING），`anchors.json` 写回（appVersion 22.13.1.1 / versionCode 369950981）。

### 构建

- 模块版本 0.3.0→0.3.1（versionCode 4→5）。

### 测试与验证

- 单测 107 个用例通过（0 失败 / 0 跳过）；lint 0 error（23 个存量反射 warning）；`assembleRelease` 通过。
- `gen_anchors` 对 22.13.1.1：55 方法 + 7 类全部命中（0 MISSING）。
- 真机 25102RKBEC / 贴吧 22.13.1.1：0.3.1 覆盖安装后状态卡「目标 22.13.1.1 · v0.3.1」，无版本不匹配横幅；冷启动 `ready: 16 installed, 0 skipped`，0 critical / 0 FATAL / 0 ANR；诊断页 25 项注册、无失败横幅。
- UI-04：底栏 4 Tab（首页/进吧/消息/我的）+ 发布按钮，无「小卖部」；AD-01 冷启动无开屏广告。
- 已命中：AD-01/02/03/05/06/07/08、UI-01、UI-04；AD-04/09/10、UI-02/03、PV-03 为 registered、场景未构造，不宣称生效。

## 0.3.0 - 2026-10-30

### 新增

- **UI-04 底栏小卖部**：hook `UbsABTestHelper#retailStorePage()` 返回 false，让贴吧走自身的“发布入口迁移”形态：隐藏底部“小卖部”Tab，恢复底栏发布按钮，并关闭小卖部引导与热事件提示。默认开，锚点缺失时 fail-open。

### 测试

- 107 个单测通过（Feature 数量/分组、ReportProtocol 102 个键的断言同步更新），lint 0 error，release 构建通过。

### 验证

- 真机 25102RKBEC / 贴吧 22.13.1.1（设备自动升级，模块仍按 22.12.1.0 适配、best-effort）：UI-04 开 → 底栏 4 Tab（首页/进吧/消息/我的）+ 发布按钮，点击发布进入 `WebWriteActivity`；关 → 恢复 5 Tab（含小卖部），发布按钮消失；开关切换后 force-stop 重进均生效，无 FATAL/ANR。
- 22.13.1.1 未做逐项回归；正式支持版本仍为 22.12.1.0。

## 0.2.1 - 2026-10-29

### 修复

- **一键签到开关语义**：目标进程接收器在触发时读取 EN-08 `one_key_sign`，关闭时忽略广播；设置页按钮不再静默把开关写成 `true`，关闭时提示“请先开启「一键签到」开关”。按钮只负责拉起贴吧并发送广播，不再改变用户配置。
- **签到广播权限**：一键签到广播改为要求 `io.github.yigenhuobah.tiebaquiet.permission.SIGN`（`signature` 级）权限，只有同签名应用能发送；贴吧侧注册接收器时校验该权限，其他应用无法伪造签到广播。
- **版本不匹配只告警**：贴吧版本与锚点表不一致时不再阻断注入，只在日志中输出 WARN，仍按锚点缺失 fail-open；锚点表方法/类缓存改为 `ConcurrentHashMap`，避免多线程并发解析反射结果时互相覆盖。
- **诊断回传落盘**：`StatusProvider.call()` 由 `apply()` 改为同步 `commit()` 并检查返回值；提交失败记录 warning 并返回 null，由目标进程按“provider 不可达”退避重试，不再回传假成功。
- **回传心跳**：`Report` 在内容未变化时至少每 5 分钟重发一次快照，避免 provider 写失败或目标进程被杀后诊断数据永久停在旧状态；心跳间隔小于 provider 的 10 分钟 TTL。
- **配置写入**：`ConfigStore.put()` 改为后台单线程串行 `commit()`，结果通过回调回主线程；remote 不可用时直接返回 false 并由 UI 回滚，不再写本地暂存假装成功。设置页、诊断页、关于页的 SharedPreferences / PackageManager / binder 读取全部移到后台线程，主线程不再做同步 I/O。
- **信息流卡片过滤**：`FeedCardSweeper` 改为读取 adapter 中的卡片数据（`CardFilter.typeKey` / `isLiveCard`）判断直播卡/热点卡，移除“直播中/正在直播/实时热点”等自由文本整卡匹配；无法确认类型时不隐藏（fail-open）。同一 adapter 只保留一条 sweep 链，并处理 RecyclerView 复用导致的空白行。
- **诊断会话与失败判定**：回传新增 `meta.session` / `meta.updated_at` / `crit.` 前缀。新会话首次写入前清掉上一会话的 reg/hit/skip/crit，迟到的旧会话整批丢弃；超过 10 分钟无新回传视为过期，失败计数按 0 处理；reg/skip/crit 以最新值覆盖（空串也覆盖），用于清理上一会话遗留的 skip；失败横幅只统计“零成功安装锚点”的 critical 功能，普通 skip 仅用于诊断展示。
- **锚点清理**：删除 8 个无任何引用的死锚点（`big_image_loader`、`save_image`、`sign_single_y0`、`tbwebview_load_url`、`thread_is_bjh`、`thread_is_smartapp`、`thread_is_ugc`、`webview_page_finished`），锚点由 62 个方法 + 7 个类收敛为 54 个方法 + 7 个类。
- **锚点生成工具**：`tools/gen_anchors.py` 支持 `--dexsql` / `DEXSQL` / PATH / `tools/dexsql/` 多路径解析，版本号从 APK 读取（aapt2/apkanalyzer），全部锚点命中后才原子写入 `anchors.json`；缺失时只打印 MISSING 并以非 0 退出，原文件保持不变。
- **Lint**：签到广播接收器改用 `ContextCompat` 的接收器标志重载，布局 tint 改用 `app:tint`。

### 新增

- CI 工作流（`.github/workflows/ci.yml`）：push 到 main 与 PR 时运行 `:app:testDebugUnitTest :app:lintDebug`，上传测试与 lint 报告；`gradlew` 补上可执行位。

### 测试

- 单测从 44 个增加到 **107 个**：新增 `ConfigStoreTest`、`ConfigStorePolicyTest`、`StatusProviderTest`、`FeedCardSweeperTest`、`hooks/ReportTest`，并扩充 `HookStateTest`、`ReportProtocolTest`、`AnchorTableTest`。

### 验证

- `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease --no-daemon` 通过；107 个单测全部通过，lint 0 error / 23 warning。
- `python tools/gen_anchors.py --apk "/e/download/base(3).apk" --out app/src/main/resources/anchors.json` 退出码 0，输出 54 methods + 7 classes（贴吧 22.12.1.0 / versionCode 369885440）。

APK: tiebaquiet-0.2.1.apk
versionCode: 3
大小: 2.42 MB（2,542,539 字节）
SHA-256: e1b0cebf9ae8d8d840f502af345bb51c7c77677d92c248641f90785067a6dcb2

## v0.2.0 — 2026-10-22

### 新增

- 诊断页：逐功能显示 `编号 标题` 与 `registered · hit N · skip 原因`，无数据显示 `—`；页首显示刷新时间，页尾支持刷新与复制诊断信息。
- 诊断回传通道：目标进程 `HookState` → `hooks/Report`（每 3 秒、内容变化才回传）→ `StatusProvider`（校验调用方 UID、键白名单、值长度上限、hit 取历史最大值）→ 模块 App 诊断页。
- 异常横幅新增“已启用且失败功能数 ≥ 2”条件，与版本不匹配条件并列；不新增第五种状态卡状态。

### 变更

- 核心与 24 个功能安装器全部从 Kotlin 迁移到 Java 17；移除 Kotlin 插件、项目内 Kotlin 源码与 Compose 残留，工程改为纯 Java + XML（AndroidX/Material 传递依赖的 kotlin-stdlib 保留）。
- 统一功能编号：AD-01~10、UI-01~03、PV-01~03、EN-01~08；remote prefs key、applicationId、namespace 均未改动。
- 默认值按统一策略调整：AD/UI 默认开；PV/EN 默认关。受影响的项为 EN-03 至 EN-07（只看楼主、楼层倒序、图片去水印、原图入口常显、视频直链复制）与 PV-01、PV-02（剪贴板保护、个性化广告强制关闭）。
- 配置迁移：读取顺序为新分组 `config` 优先 → 旧分组 `features` 兜底 → 编译期默认值；写入时同时写两个分组，保留一个版本供回滚。
- 设置页改用 `ConfigStore` / `ServiceBridge` / `FeatureRegistry`，移除 `UiFeature` 与 `SettingsRepository`。
- 构建脚本从 Kotlin DSL 改为 Groovy DSL；release 保持 R8 + `shrinkResources`，缺 `keystore.properties` 时硬失败。

### 测试

- 新增 `FeatureTest`、`ConfigMigrationTest`、`ReportProtocolTest`、`HookStateTest`、`AnchorTableTest`，共 44 个用例，全部为纯 JVM JUnit 4.13.2。

### 验证

- `./gradlew clean test assembleDebug assembleRelease` 通过。

APK: tiebaquiet-0.2.0.apk
versionCode: 2
大小: 2.41 MB（2,526,511 字节）
SHA-256: 1a0f0bf035bf4a8746e68b83e06d63a481637d840a4ecea66b04fa9dcc271481
