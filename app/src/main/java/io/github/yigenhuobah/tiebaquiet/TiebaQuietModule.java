package io.github.yigenhuobah.tiebaquiet;

import android.util.Log;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import io.github.yigenhuobah.tiebaquiet.anchor.AnchorTable;
import io.github.yigenhuobah.tiebaquiet.feature.phase2.SignInFeature;
import io.github.yigenhuobah.tiebaquiet.hooks.Report;

/**
 * 模块入口：只在贴吧主进程安装 hook。
 * 任一功能锚点缺失只会跳过该功能，不影响其它功能与贴吧本体。
 */
public class TiebaQuietModule extends XposedModule {

    static final String TAG = "TiebaQuiet";
    static final String TARGET_PACKAGE = "com.baidu.tieba";

    private String processName = "";
    private boolean installed;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        processName = param.getProcessName();
        log(Log.INFO, TAG, "loaded in " + processName + " (api=" + getApiVersion() + ")");
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!TARGET_PACKAGE.equals(param.getPackageName())) {
            return;
        }
        if (!TARGET_PACKAGE.equals(processName)) {
            log(Log.INFO, TAG, "skip non-main process: " + processName);
            return;
        }
        if (installed) {
            return;
        }

        AnchorTable anchors = AnchorTable.load(param.getClassLoader());
        if (anchors == null) {
            log(Log.ERROR, TAG, "anchors.json not found in module apk");
            return;
        }

        // 配置迁移：新 group config 优先，旧 group features 只读兜底。
        Config config = Config.load(this);
        if (!config.available()) {
            log(Log.ERROR, TAG, "remote preferences unavailable");
            return;
        }

        HookEnv env = new HookEnv(this, param.getClassLoader(), anchors,
                config.prefs(), config.legacyPrefs(), processName);
        int ok = 0;
        int skipped = 0;
        for (Feature feature : FeatureRegistry.all()) {
            if (!feature.isEnabled(env)) {
                continue;
            }
            boolean result;
            try {
                result = feature.install(env);
            } catch (Throwable t) {
                log(Log.WARN, TAG, "feature " + feature.code + " crashed", t);
                result = false;
            }
            if (result) {
                ok++;
                log(Log.INFO, TAG, "feature " + feature.code + " installed");
            } else {
                skipped++;
                log(Log.INFO, TAG, "feature " + feature.code
                        + " skipped (anchor missing or hook failed)");
            }
        }
        installed = true;
        // 签到触发通道常驻注册，开关在触发时判断。
        SignInFeature.installAlways(env);
        // 诊断回传只在主进程启动。
        Report.start();
        log(Log.INFO, TAG, "ready: " + ok + " installed, " + skipped
                + " skipped, target=" + anchors.getAppVersion());
    }
}
