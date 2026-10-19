package io.github.yigenhuobah.tiebaquiet.feature

import android.content.SharedPreferences
import io.github.libxposed.api.XposedInterface
import io.github.yigenhuobah.tiebaquiet.TiebaQuietModule
import io.github.yigenhuobah.tiebaquiet.anchor.AnchorTable
import java.lang.reflect.Method

class HookEnv(
    val module: TiebaQuietModule,
    val loader: ClassLoader,
    val anchors: AnchorTable,
    val prefs: SharedPreferences,
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

    fun isEnabled(env: HookEnv): Boolean = env.prefs.getBoolean(id, defaultEnabled)

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
