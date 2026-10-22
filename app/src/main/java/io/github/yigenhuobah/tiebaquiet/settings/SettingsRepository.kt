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
 *
 * 配置迁移：新 group 为 [ConfigMigration.GROUP]（config），旧 group
 * [ConfigMigration.LEGACY_GROUP]（features）继续作为只读兜底并双写一个版本。
 * 读取顺序：新 key 优先 → 旧 key 兜底 → 默认值。
 */
object SettingsRepository {
    const val TARGET_PACKAGE = "com.baidu.tieba"
    const val TARGET_VERSION = "22.12.1.0"
    const val GROUP = ConfigMigration.GROUP
    const val LEGACY_GROUP = ConfigMigration.LEGACY_GROUP
    private const val AUTHORITY_SUFFIX = ".XposedService"
    private const val TAG = "TiebaQuiet"

    data class Status(
        val connected: Boolean,
        val apiVersion: Int,
        val scoped: Boolean,
        val injected: Boolean,
    )

    private var service: XposedService? = null

    /** 新 group（config）的框架存储；null = 框架未绑定或不可用。 */
    private var remote: SharedPreferences? = null

    /** 旧 group（features）的框架存储，只用于读取兜底与双写。 */
    private var legacyRemote: SharedPreferences? = null

    /** 框架不可用时的本地暂存，仅用于 UI 展示，不代表目标进程能读到。 */
    private var local: SharedPreferences? = null
    private var legacyLocal: SharedPreferences? = null

    private val listeners = LinkedHashSet<Runnable>()
    private var registered = false

    @JvmStatic
    fun init(context: Context) {
        if (registered) return
        registered = true
        val app = context.applicationContext
        local = app.getSharedPreferences(GROUP, Context.MODE_PRIVATE)
        legacyLocal = app.getSharedPreferences(LEGACY_GROUP, Context.MODE_PRIVATE)

        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                val api = safeApi(service)
                if (api < 101) {
                    Log.i(TAG, "ignore placeholder binder, api=$api")
                    return
                }
                this@SettingsRepository.service = service
                remote = remotePreferences(service, GROUP)
                legacyRemote = remotePreferences(service, LEGACY_GROUP)
                Log.i(
                    TAG,
                    "service bound, api=$api, remote=${remote != null}, legacy=${legacyRemote != null}",
                )
                notifyChanged()
            }

            override fun onServiceDied(service: XposedService) {
                if (this@SettingsRepository.service === service) {
                    this@SettingsRepository.service = null
                    remote = null
                    legacyRemote = null
                    notifyChanged()
                }
            }
        })

        poke(context)
    }

    /** 页面恢复时重新探测一次状态。 */
    @JvmStatic
    fun refresh() = notifyChanged()

    @JvmStatic
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
    @JvmStatic
    fun writable(): Boolean = remote != null

    /**
     * 读开关：新 group 的 key 优先；缺失时回退旧 group；都没有才用默认值。
     * 框架未绑定时读本地暂存（新 group 优先，旧 group 兜底），只用于 UI 展示。
     */
    @JvmStatic
    fun getBoolean(key: String, default: Boolean): Boolean = try {
        val newPrefs = remote ?: local
        val oldPrefs = if (remote != null) legacyRemote else legacyLocal
        val newValue = if (newPrefs != null && newPrefs.contains(key)) {
            newPrefs.getBoolean(key, default)
        } else {
            null
        }
        val legacyValue = if (oldPrefs != null && oldPrefs.contains(key)) {
            oldPrefs.getBoolean(key, default)
        } else {
            null
        }
        ConfigMigration.resolve(newValue, legacyValue, default)
    } catch (t: Throwable) {
        Log.w(TAG, "read failed: $key", t)
        default
    }

    /**
     * 写开关：同时写新 group 与旧 group，保留一个版本供回滚。
     * 返回 false 表示两个存储都没写成，调用方需要提示并回滚 UI。
     */
    @JvmStatic
    fun setBoolean(key: String, value: Boolean): Boolean = try {
        val newPrefs = remote ?: local
        val oldPrefs = if (remote != null) legacyRemote else legacyLocal
        if (newPrefs == null) {
            false
        } else {
            newPrefs.edit().putBoolean(key, value).apply()
            oldPrefs?.edit()?.putBoolean(key, value)?.apply()
            if (remote == null) {
                Log.w(TAG, "config written to LOCAL prefs (framework not bound): $key")
            }
            true
        }
    } catch (t: Throwable) {
        Log.w(TAG, "write failed: $key", t)
        false
    }

    @JvmStatic
    fun addListener(listener: Runnable) {
        listeners.add(listener)
    }

    @JvmStatic
    fun removeListener(listener: Runnable) {
        listeners.remove(listener)
    }

    private fun notifyChanged() {
        listeners.toList().forEach { listener ->
            try {
                listener.run()
            } catch (t: Throwable) {
                Log.w(TAG, "listener failed", t)
            }
        }
    }

    private fun remotePreferences(service: XposedService, group: String): SharedPreferences? = try {
        if ((service.frameworkProperties and XposedService.PROP_CAP_REMOTE) == 0L) {
            Log.w(TAG, "framework lacks PROP_CAP_REMOTE: $group")
            null
        } else {
            service.getRemotePreferences(group)
        }
    } catch (t: Throwable) {
        Log.w(TAG, "remote prefs unavailable: $group", t)
        null
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
