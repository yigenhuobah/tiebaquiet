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

    /** 配置结构版本；只写新 group，旧 group 不识别该 key。 */
    public static final String KEY_CONFIG_VERSION = "config_version";
    /** 当前模块支持的配置结构版本。 */
    public static final int CURRENT_CONFIG_VERSION = 1;

    private static final String TAG = "TiebaQuiet";

    private final SharedPreferences prefs;
    private final SharedPreferences legacyPrefs;
    private final int configVersion;

    private Config(SharedPreferences prefs, SharedPreferences legacyPrefs, int configVersion) {
        this.prefs = prefs;
        this.legacyPrefs = legacyPrefs;
        this.configVersion = configVersion;
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
        // 版本标记独立读取：缺失/损坏一律按当前版本处理，不影响开关读取。
        int configVersion = readConfigVersion(module, prefs);
        if (configVersion > CURRENT_CONFIG_VERSION) {
            // 只在 load 时告警一次；每功能读取不再重复刷日志。
            module.log(Log.WARN, TAG, "配置版本 v" + configVersion
                    + " 高于模块支持 v" + CURRENT_CONFIG_VERSION + "，按兼容模式继续");
        }
        return new Config(prefs, legacyPrefs, configVersion);
    }

    /**
     * 读取新 group 的 {@code config_version}：key 缺失按 {@link #CURRENT_CONFIG_VERSION}
     * 处理；读取异常也回退当前版本，避免版本标记拖垮整个配置加载。
     */
    static int readConfigVersion(XposedModule module, SharedPreferences prefs) {
        if (prefs == null) {
            return CURRENT_CONFIG_VERSION;
        }
        try {
            return prefs.getInt(KEY_CONFIG_VERSION, CURRENT_CONFIG_VERSION);
        } catch (Throwable t) {
            if (module != null) {
                module.log(Log.WARN, TAG, "read config_version failed", t);
            }
            return CURRENT_CONFIG_VERSION;
        }
    }

    public SharedPreferences prefs() {
        return prefs;
    }

    public SharedPreferences legacyPrefs() {
        return legacyPrefs;
    }

    /** 加载时读到的新 group 配置版本；缺失或读取失败为 {@link #CURRENT_CONFIG_VERSION}。 */
    public int configVersion() {
        return configVersion;
    }

    /** 两个分组都不可用时调用方应放弃安装，避免所有开关静默回退默认值。 */
    public boolean available() {
        return prefs != null || legacyPrefs != null;
    }
}
