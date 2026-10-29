# 贴吧静净 (TiebaQuiet)

基于 [libxposed API 102](https://repo.maven.apache.org/maven2/io/github/libxposed/) 的 LSPosed 模块，为百度贴吧（`com.baidu.tieba`）提供去广告、界面精简、功能增强与隐私保护。

- 模块版本：**0.2.1**（versionCode 3）
- 目标版本：百度贴吧 **22.12.1.0**（versionCode 369885440）
- 包名：`io.github.yigenhuobah.tiebaquiet`
- 运行环境：LSPosed 2.1+ / Vector 2.2+（Zygisk），API 102

## 功能

设置页按用途分组，顺序固定 **AD 广告净化 → UI 界面精简 → PV 隐私保护 → EN 功能增强**。编号用于文档、日志与诊断页；remote prefs 键保持不变。

### AD 广告净化（默认开）

| 编号 | 功能 | 说明 |
|---|---|---|
| AD-01 | 无开屏广告 | 复用官方禁开屏开关，启动不再加载广告 |
| AD-02 | 广告请求源头掐断 | FunAd/Nad/getFeedAd 三类广告请求不再发出 |
| AD-03 | 信息流广告卡 | 过滤首页/吧内/评论列表中的广告与推广卡片 |
| AD-04 | 吧页推广卡 | 过滤吧页面解析出的推广/活动卡片 |
| AD-05 | 直播卡过滤 | 信息流中不再展示“直播中/开播”卡片 |
| AD-06 | 实时热点卡过滤 | 信息流中不再展示“实时热点”卡片 |
| AD-07 | 悬浮广告 | 隐藏首页与帖子页的悬浮广告浮标 |
| AD-08 | 营销与活动弹窗 | 关闭云端下发的活动、签到、营销类弹窗 |
| AD-09 | 推荐吧卡 | 不展示“推荐吧/常逛吧”推广卡片 |
| AD-10 | 彩蛋广告 | 关闭节日彩蛋类广告弹层 |

### UI 界面精简（默认开）

| 编号 | 功能 | 说明 |
|---|---|---|
| UI-01 | 底部 Tab 红点 | 隐藏底部导航的红点与角标 |
| UI-02 | 更新弹窗 | 拦截“发现新版本”强制更新弹窗 |
| UI-03 | 个人中心精简 | 隐藏“我的”页的会员横幅、钱包、任务赚贴贝与游戏专区 |

### PV 隐私保护（默认关）

| 编号 | 功能 | 说明 |
|---|---|---|
| PV-01 | 剪贴板保护 | 贴吧不再读取剪贴板内容（口令/图片口令失效） |
| PV-02 | 个性化广告强制关闭 | 忽略设置项，始终按“关闭个性化广告”运行 |
| PV-03 | 保活服务拦截 | 阻止 ALa 保活服务启动，减少后台驻留 |

### EN 功能增强（默认关）

| 编号 | 功能 | 说明 |
|---|---|---|
| EN-01 | 频道栏默认关注 | 把“关注”频道排到首页频道栏第一位 |
| EN-02 | 强制夜间模式 | 固定使用夜间皮肤（4） |
| EN-03 | 只看楼主 | 帖子页请求始终按只看楼主过滤 |
| EN-04 | 楼层倒序 | 帖子楼层按倒序加载 |
| EN-05 | 图片去水印 | 图片加载不再附加用户名/吧名水印 |
| EN-06 | 原图入口常显 | 图片查看器始终显示“查看原图”入口 |
| EN-07 | 视频直链复制 | 播放视频时自动复制直链到剪贴板 |
| EN-08 | 一键签到 | 设置页按钮触发贴吧自带的全部吧签到 |

默认值只影响新安装；已保存的开关不会被覆盖。旧版本写在 `features` 分组中的配置会继续读取，新版本写入 `config` 分组并同时回写旧分组一个版本。

## 设置页状态

顶部状态卡有四种状态，用 `getScope()` + `getRunningTargets()` 判断：

- **正在连接框架…**：service 尚未绑定（绑定后自动刷新）
- **未激活**：已连接框架，但作用域没勾选百度贴吧
- **框架已连接**：作用域已勾选，但贴吧进程当前未注入（启动贴吧后生效）
- **已激活**：贴吧进程已注入，显示框架 API 版本

异常横幅只在两种情况下出现：贴吧版本不是 22.12.1.0，或已启用且明确失败的功能数 ≥ 2。框架未绑定时开关置灰，避免“改了没用”。

## 诊断与关于

- **诊断页**：逐功能显示 `编号 标题` 与 `registered · hit N · skip 原因`；无数据显示 `—`。页首显示刷新时间，页尾可刷新或复制全部诊断信息。诊断数据只来自目标进程回传，不包含用户内容。
- **关于页**：模块版本、目标应用与适配版本、源码地址、四模块互链、免责声明与 MIT 许可证。

## 安装

1. 从 [Releases](https://github.com/yigenhuobah/tiebaquiet/releases) 下载最新 APK 安装。
2. 在 LSPosed 管理器中启用「贴吧静净」，作用域勾选「百度贴吧」。
3. 强制停止并重新打开贴吧。

要求：LSPosed 2.1+ / Vector 2.2+（Zygisk），libxposed API 102；百度贴吧 22.12.1.0。

## 构建

要求：JDK 17 及以上（CI 使用 21）、Android SDK Platform 37（libxposed 102 的 aar 要求 `compileSdk 37`）。

```bash
./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`；release 产物为 `app/build/outputs/apk/release/tiebaquiet-<versionName>.apk`（需在仓库根提供 `keystore.properties` 与签名文件，缺失时 release 构建直接失败）。

完整验证：

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease --no-daemon
```

当前共 **107 个 JVM 单测**（`app/src/test`），CI 在 push 到 main 和 PR 时运行单测与 lint。

## 架构

全部为 Java 17 + XML + Material 3，不使用 Kotlin/Compose。

- `TiebaQuietModule`：libxposed 入口，仅注入贴吧主进程；安装完成后启动诊断回传。
- `Feature` / `FeatureRegistry`：24 个功能的编号、remote key、文案、默认值、分组与安装器。
- `anchor/AnchorTable`：读取打包进 APK 的 `anchors.json`（当前 54 个方法锚点 + 7 个类锚点），运行时反射校验类/方法是否存在；缺失时只禁用对应功能，版本不匹配只告警不阻断。
- `feature/phase1|2|3`：各功能的 hook 安装器；`CardFilter` / `FeedCardSweeper` 为信息流过滤公共逻辑。
- `Config` / `ConfigStore` / `ConfigMigration`：目标进程读、模块 App 写，新分组 `config` 优先、旧分组 `features` 兜底。
- `ServiceBridge`：XposedService 绑定、占位 binder 过滤与 remote prefs 获取。
- `HookState` / `hooks/Report` / `StatusProvider`：目标进程每 3 秒检查注册/命中/跳过状态，内容变化才回传、不变时每 5 分钟心跳；模块 App 侧校验 UID 后同步 `commit()` 落盘，并按会话标识、10 分钟 TTL 与 critical 标记供诊断页和失败横幅读取。
- `tools/gen_anchors.py`：构建期用 [DexSQL](https://github.com/LSPosed/dexsql-release) 校验全部锚点并生成 `anchors.json`。

贴吧更新后：

```bash
python tools/gen_anchors.py --apk <新版本apk> --out app/src/main/resources/anchors.json
```

锚点未命中的功能会在日志中标记为 skipped，其余功能继续工作。

## 兼容性与风险

- 仅声明支持 22.12.1.0；混淆类（`qy8` 等）通过字符串锚点在 `gen_anchors.py` 中定位，跨版本需重新生成。
- 贴吧业务方法普遍带 Titan 热修桩（`$ic`）。模块遵循：优先 replace/after，before 不 return，不 hook clinit/构造器。
- 已知暴露面：贴吧内置 sofire/weapon 等检测会随请求上报 Xposed 环境特征；本模块不做任何检测绕过，只减少主动触碰（不 hook 框架方法、不碰签名/发送层、不做设备号伪造）。
- “启动任务精简”曾 hook `LaunchTask.run()`，但它是所有启动任务的模板方法，跳过会导致 Fresco 图片管线未初始化、贴吧启动即闪退，已移除。
- 修改开关后需要强制停止并重新打开贴吧生效（RemotePreferences 在进程启动时读取）。

### 已知限制（0.2.1）

- **一键签到需 EN-08 开启**：关闭 EN-08 时接收器忽略签到广播，设置页按钮提示“请先开启”；点击按钮不再静默改写开关。
- **正向签到未真机触发**：本轮只在 JVM 单测与构建层面验证，未在真机上实际完成一次签到流程。
- **24 项逐项回归未做**：本轮未对 24 个功能逐项在真机做开启/关闭回归，仅完成单测、lint 与 release 构建验证。

## 许可证

MIT
