package io.github.yigenhuobah.tiebaquiet;

import android.content.SharedPreferences;
import android.util.Log;

import io.github.libxposed.api.XposedModule;

/**
 * 目标进程侧配置读取：只负责拿到 {@code config} 与旧 {@code features} 两个
 * remote preferences 句柄，具体 key 的合并逻辑在 {@link Feature#isEnabled(HookEnv)}。
 *
 * <p>任一分组读取失败都只记日志并保留 null，不影响另一个分组与编译期默认值。
 */
public final class Config {

    public static final String GROUP = ConfigMigration.GROUP;
    public static final String LEGACY_GROUP = ConfigMigration.LEGACY_GROUP;

    private static final String TAG = "TiebaQuiet";

    private final SharedPreferences prefs;
    private final SharedPreferences legacyPrefs;

    private Config(SharedPreferences prefs, SharedPreferences legacyPrefs) {
        this.prefs = prefs;
        this.legacyPrefs = legacyPrefs;
    }

    public static Config load(XposedModule module) {
        SharedPreferences prefs = null;
        SharedPreferences legacyPrefs = null;
        try {
            prefs = module.getRemotePreferences(GROUP);
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "config remote preferences unavailable", t);
        }
        try {
            legacyPrefs = module.getRemotePreferences(LEGACY_GROUP);
        } catch (Throwable t) {
            module.log(Log.WARN, TAG, "legacy remote preferences unavailable", t);
        }
        return new Config(prefs, legacyPrefs);
    }

    public SharedPreferences prefs() {
        return prefs;
    }

    public SharedPreferences legacyPrefs() {
        return legacyPrefs;
    }

    /** 两个分组都不可用时调用方应放弃安装，避免所有开关静默回退默认值。 */
    public boolean available() {
        return prefs != null || legacyPrefs != null;
    }
}
