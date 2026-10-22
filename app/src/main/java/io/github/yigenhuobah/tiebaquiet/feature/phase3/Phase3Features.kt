package io.github.yigenhuobah.tiebaquiet.feature.phase3

import io.github.yigenhuobah.tiebaquiet.feature.Feature
import io.github.yigenhuobah.tiebaquiet.feature.HookEnv

/** 剪贴板保护：掐断口令监听与图片口令读取的共同源头。 */
object ClipboardGuardFeature : Feature(
    id = "clipboard_guard",
    title = "剪贴板保护",
    summary = "贴吧不再读取剪贴板内容（口令/图片口令失效）",
    phase = 3,
    defaultEnabled = false,
) {
    override fun install(env: HookEnv): Boolean =
        replace(env, env.anchors.method("clipboard"), "")
}

/** 个性化广告：强制关闭（1 = 关闭态）。 */
object PersonalizedAdOffFeature : Feature(
    id = "personalized_ad_off",
    title = "个性化广告强制关闭",
    summary = "忽略设置项，始终按“关闭个性化广告”运行",
    phase = 3,
    defaultEnabled = false,
) {
    override fun install(env: HookEnv): Boolean =
        replace(env, env.anchors.method("personalized_ad"), 1)
}

/** 保活拦截：不拉起 ALa 保活服务。 */
object KeepAliveBlockFeature : Feature(
    id = "keep_alive_block",
    title = "保活服务拦截",
    summary = "阻止 ALa 保活服务启动，减少后台驻留",
    phase = 3,
    defaultEnabled = false,
) {
    override fun install(env: HookEnv): Boolean =
        skip(env, env.anchors.method("keep_alive"))
}
