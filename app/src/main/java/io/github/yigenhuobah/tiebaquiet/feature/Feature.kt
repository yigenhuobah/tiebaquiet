package io.github.yigenhuobah.tiebaquiet.feature

import android.content.SharedPreferences
import io.github.libxposed.api.XposedInterface
import io.github.yigenhuobah.tiebaquiet.TiebaQuietModule
import io.github.yigenhuobah.tiebaquiet.anchor.AnchorTable
import io.github.yigenhuobah.tiebaquiet.settings.ConfigMigration
import java.lang.reflect.Method

/**
 * 目标进程的 hook 环境。
 *
 * [prefs] 是新的 config group；[legacyPrefs] 是旧 features group。
 * 两者都可能为 null（框架未提供 remote prefs），此时按编译期默认值处理。
 */
class HookEnv(
    val module: TiebaQuietModule,
    val loader: ClassLoader,
    val anchors: AnchorTable,
    val prefs: SharedPreferences?,
    val legacyPrefs: SharedPreferences?,
    val processName: String,
)

/**
 * 一个功能 = 一个或多个锚点 hook。
 * install() 返回 true 表示至少安装成功一个 hook；锚点缺失时返回 false，只禁用该功能。
 */
abstract class Feature(
    val id: String,
    val title: String,
    val summary: String,
    val phase: Int,
    val defaultEnabled: Boolean,
) {
    abstract fun install(env: HookEnv): Boolean

    fun isEnabled(env: HookEnv): Boolean = try {
        // 新 group 的 key 优先；缺失时回退旧 group；都没有才用默认值。
        val newValue = env.prefs?.let {
            if (it.contains(id)) it.getBoolean(id, defaultEnabled) else null
        }
        val legacyValue = env.legacyPrefs?.let {
            if (it.contains(id)) it.getBoolean(id, defaultEnabled) else null
        }
        ConfigMigration.resolve(newValue, legacyValue, defaultEnabled)
    } catch (t: Throwable) {
        // 读失败不能拖垮整个 hook 安装：按编译期默认值继续。
        env.module.log(4, TAG, "read config failed: $id", t)
        defaultEnabled
    }

    /** 拦截器链：不调用 chain.proceed() 即为 replace。 */
    protected fun hook(
        env: HookEnv,
        method: Method?,
        hooker: (XposedInterface.Chain) -> Any?,
    ): Boolean {
        if (method == null) return false
        return try {
            env.module.hook(method)
                .setId("$id:${method.declaringClass.simpleName}#${method.name}")
                .intercept { chain -> hooker(chain) }
            true
        } catch (t: Throwable) {
            env.module.log(4, TAG, "hook ${method.declaringClass.name}#${method.name} failed", t)
            false
        }
    }

    protected fun replace(env: HookEnv, method: Method?, value: Any?): Boolean = hook(env, method) { value }

    protected fun skip(env: HookEnv, method: Method?): Boolean = hook(env, method) { null }

    protected fun after(env: HookEnv, method: Method?, transform: (Any?) -> Any?): Boolean =
        hook(env, method) { chain -> transform(chain.proceed()) }

    companion object {
        const val TAG = "TiebaQuiet"
    }
}
