package io.github.yigenhuobah.tiebaquiet

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.yigenhuobah.tiebaquiet.anchor.AnchorTable
import io.github.yigenhuobah.tiebaquiet.feature.FeatureRegistry
import io.github.yigenhuobah.tiebaquiet.feature.HookEnv

/**
 * 模块入口：只在贴吧主进程安装 hook。
 * 任一功能锚点缺失只会跳过该功能，不影响其它功能与贴吧本体。
 */
class TiebaQuietModule : XposedModule() {

    private var processName: String = ""
    private var installed = false

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        processName = param.processName
        log(3, TAG, "loaded in $processName (api=$apiVersion)")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != TARGET_PACKAGE) return
        if (processName != TARGET_PACKAGE) {
            log(3, TAG, "skip non-main process: $processName")
            return
        }
        if (installed) return

        val anchors = AnchorTable.load(param.classLoader)
        if (anchors == null) {
            log(6, TAG, "anchors.json not found in module apk")
            return
        }

        val prefs = try {
            getRemotePreferences("features")
        } catch (t: Throwable) {
            log(6, TAG, "remote preferences unavailable", t)
            return
        }

        val env = HookEnv(this, param.classLoader, anchors, prefs, processName)
        var ok = 0
        var skipped = 0
        for (feature in FeatureRegistry.all) {
            if (!feature.isEnabled(env)) continue
            val result = try {
                feature.install(env)
            } catch (t: Throwable) {
                log(4, TAG, "feature ${feature.id} crashed", t)
                false
            }
            if (result) {
                ok++
                log(3, TAG, "feature ${feature.id} installed")
            } else {
                skipped++
                log(3, TAG, "feature ${feature.id} skipped (anchor missing or hook failed)")
            }
        }
        installed = true
        // 签到触发通道常驻注册，开关在触发时判断。
        io.github.yigenhuobah.tiebaquiet.feature.phase2.SignInFeature.installAlways(env)
        log(3, TAG, "ready: $ok installed, $skipped skipped, target=${anchors.appVersion}")
    }

    companion object {
        private const val TAG = "TiebaQuiet"
        private const val TARGET_PACKAGE = "com.baidu.tieba"
    }
}
