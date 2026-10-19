package io.github.yigenhuobah.tiebaquiet.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * 模块 App 侧：框架服务绑定 + 状态探测 + RemotePreferences 读写。
 *
 * 参考静盒（quietbox）的实现：
 * - 框架会先投一个 api=0 的占位 binder，必须丢弃；
 * - 只有 getRunningTargets 里出现目标进程才算“已注入”；
 * - 写不进框架存储时（service 未绑定/无 PROP_CAP_REMOTE）开关置灰，避免“改了没用”。
 */
object SettingsRepository {
    const val TARGET_PACKAGE = "com.baidu.tieba"
    const val TARGET_VERSION = "22.12.1.0"
    private const val GROUP = "features"
    private const val AUTHORITY_SUFFIX = ".XposedService"
    private const val TAG = "TiebaQuiet"

    data class Status(
        val connected: Boolean,
        val apiVersion: Int,
        val scoped: Boolean,
        val injected: Boolean,
    )

    private var service: XposedService? = null
    private var remote: SharedPreferences? = null
    private var local: SharedPreferences? = null
    private val listeners = LinkedHashSet<() -> Unit>()
    private var registered = false

    fun init(context: Context) {
        if (registered) return
        registered = true
        local = context.applicationContext.getSharedPreferences(GROUP, Context.MODE_PRIVATE)

        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                val api = safeApi(service)
                if (api < 101) {
                    Log.i(TAG, "ignore placeholder binder, api=$api")
                    return
                }
                this@SettingsRepository.service = service
                remote = try {
                    if ((service.frameworkProperties and XposedService.PROP_CAP_REMOTE) == 0L) {
                        Log.w(TAG, "framework lacks PROP_CAP_REMOTE")
                        null
                    } else {
                        service.getRemotePreferences(GROUP)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "remote prefs unavailable", t)
                    null
                }
                Log.i(TAG, "service bound, api=$api, remote=${remote != null}")
                notifyChanged()
            }

            override fun onServiceDied(service: XposedService) {
                if (this@SettingsRepository.service === service) {
                    this@SettingsRepository.service = null
                    remote = null
                    notifyChanged()
                }
            }
        })

        poke(context)
    }

    /** 页面恢复时重新探测一次状态。 */
    fun refresh() = notifyChanged()

    fun status(): Status {
        val s = service ?: return Status(false, 0, false, false)
        val api = safeApi(s)
        val scoped = try {
            s.scope?.contains(TARGET_PACKAGE) == true
        } catch (_: Throwable) {
            false
        }
        val injected = try {
            s.runningTargets?.any { target ->
                val name = target.processName
                name == TARGET_PACKAGE || name.startsWith("$TARGET_PACKAGE:")
            } == true
        } catch (_: Throwable) {
            false
        }
        return Status(true, api, scoped, injected)
    }

    /** 只有写进框架存储目标进程才读得到。 */
    fun writable(): Boolean = remote != null

    fun getBoolean(key: String, default: Boolean): Boolean = try {
        (remote ?: local)?.getBoolean(key, default) ?: default
    } catch (_: Throwable) {
        default
    }

    fun setBoolean(key: String, value: Boolean) {
        try {
            (remote ?: local)?.edit()?.putBoolean(key, value)?.apply()
        } catch (t: Throwable) {
            Log.w(TAG, "write failed: $key", t)
        }
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyChanged() {
        listeners.toList().forEach { it() }
    }

    private fun safeApi(s: XposedService): Int = try {
        s.apiVersion
    } catch (_: Throwable) {
        0
    }

    /** 主动向自己的 provider 发一次 call，让框架重新投递 binder。 */
    private fun poke(context: Context) {
        try {
            val extras = android.os.Bundle()
            extras.putBinder("binder", android.os.Binder())
            context.contentResolver.call(
                android.net.Uri.parse("content://${context.packageName}$AUTHORITY_SUFFIX"),
                "SendBinder",
                null,
                extras,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "poke failed", t)
        }
    }
}
