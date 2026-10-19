# 贴吧静净 (TiebaQuiet)

基于 [libxposed API 102](https://repo.maven.apache.org/maven2/io/github/libxposed/) 的 LSPosed 模块，为百度贴吧（`com.baidu.tieba`）提供去广告、界面精简、功能增强与隐私保护。

- 目标版本：百度贴吧 **22.12.1.0**（versionCode 369885440）
- 包名：`io.github.yigenhuobah.tiebaquiet`
- 运行环境：LSPosed 2.1+ / Vector 2.2+（Zygisk），API 102

## 功能

设置页按用途分组（照静盒/LSPosed 管理器的结构：顶部状态卡 + 分组开关 + 页脚说明）。

### 广告清理
| 功能 | 默认 |
|---|---|
| 无开屏广告 | 开 |
| 广告请求源头掐断（FunAd / Nad / getFeedAd） | 开 |
| 信息流广告卡 | 开 |
| 吧页推广卡 | 开 |
| 直播卡过滤 | 开 |
| 实时热点卡过滤 | 开 |
| 悬浮广告 | 开 |
| 营销与活动弹窗 | 开 |
| 推荐吧卡 | 开 |
| 彩蛋广告 | 开 |

### 界面精简
| 功能 | 默认 |
|---|---|
| 底部 Tab 红点 | 开 |
| 更新弹窗 | 开 |
| 个人中心精简 | 开 |
| 频道栏默认关注 | 关 |
| 强制夜间模式 | 关 |

### 功能增强
| 功能 | 默认 |
|---|---|
| 只看楼主 | 开 |
| 楼层倒序 | 开 |
| 图片去水印 | 开 |
| 原图入口常显 | 开 |
| 视频直链复制 | 开 |
| 一键签到（设置页按钮） | 关 |

### 隐私保护
| 功能 | 默认 |
|---|---|
| 剪贴板保护 | 开 |
| 个性化广告强制关闭 | 开 |
| 保活服务拦截 | 关 |

## 设置页状态

顶部状态卡有四种状态，照静盒的做法用 `getScope()` + `getRunningTargets()` 判断：

- **正在连接框架…**：service 尚未绑定（绑定后自动刷新）
- **未激活**：已连接框架，但作用域没勾选百度贴吧
- **框架已连接**：作用域已勾选，但贴吧进程当前未注入（启动贴吧后生效）
- **已激活**：贴吧进程已注入，显示框架 API 版本

安装的贴吧版本不是 22.12.1.0 时会显示红色警告横幅。框架未绑定时开关置灰，避免“改了没用”。


## 安装

1. 从 [Releases](https://github.com/yigenhuobah/tiebaquiet/releases) 下载最新 APK 安装。
2. 在 LSPosed 管理器中启用「贴吧静净」，作用域勾选「百度贴吧」。
3. 强制停止并重新打开贴吧。

要求：LSPosed 2.1+ / Vector 2.2+（Zygisk），libxposed API 102；百度贴吧 22.12.1.0。

## 构建

要求：JDK 17+、Android SDK Platform 37（libxposed 102 的 aar 要求 `compileSdk 37`）。

```bash
./gradlew :module:assembleDebug
```

产物：`module/build/outputs/apk/debug/module-debug.apk`

安装后在 LSPosed 管理器中启用模块，作用域勾选「百度贴吧」，然后强制停止并重新打开贴吧。

## 架构

- `TiebaQuietModule`：libxposed 入口，仅注入贴吧主进程。
- `anchor/AnchorTable`：读取打包进 APK 的 `anchors.json`，运行时反射校验类/方法是否存在；缺失时只禁用对应功能。
- `feature/Feature`：每个功能一个对象，声明默认开关与 `install()`。
- `settings/SettingsRepository`：模块 App 通过 LSPosed 服务读写 RemotePreferences（组名 `features`）。
- `tools/gen_anchors.py`：构建期用 [DexSQL](https://github.com/LSPosed/dexsql-release) 校验全部锚点并生成 `anchors.json`。

贴吧更新后：

```bash
python tools/gen_anchors.py --apk <新版本apk> --out module/src/main/resources/anchors.json
```

锚点未命中的功能会在日志中标记为 skipped，其余功能继续工作。

## 兼容性与风险

- 仅声明支持 22.12.1.0；混淆类（`qy8` 等）通过字符串锚点在 `gen_anchors.py` 中定位，跨版本需重新生成。
- 贴吧业务方法普遍带 Titan 热修桩（`$ic`）。模块遵循：优先 replace/after，before 不 return，不 hook clinit/构造器。
- 已知暴露面：贴吧内置 sofire/weapon 等检测会随请求上报 Xposed 环境特征；本模块不做任何检测绕过，只减少主动触碰（不 hook 框架方法、不碰签名/发送层、不做设备号伪造）。
- “启动任务精简”曾 hook `LaunchTask.run()`，但它是所有启动任务的模板方法，跳过会导致 Fresco 图片管线未初始化、贴吧启动即闪退，已移除。
- 修改开关后需要强制停止并重新打开贴吧生效（RemotePreferences 在进程启动时读取）。

## 许可证

MIT
