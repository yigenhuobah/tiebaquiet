# CHANGELOG

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
