package io.github.yigenhuobah.tiebaquiet;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * 模块 App 侧的开关读写。
 *
 * <p>必须写框架的 remote preferences：贴吧进程读的是那份存储。框架未绑定或
 * 没有 PROP_CAP_REMOTE 时才退回本地暂存，同时 UI 会把开关置灰。
 *
 * <p>配置迁移：新 group {@code config} 优先，旧 group {@code features} 只读兜底并双写一个版本。
 */
public final class ConfigStore {

    public static final String GROUP = ConfigMigration.GROUP;
    public static final String LEGACY_GROUP = ConfigMigration.LEGACY_GROUP;

    /** 目标应用包名；<queries> 已声明，Android 11+ 才能读到版本与 UID。 */
    public static final String TARGET_PACKAGE = "com.baidu.tieba";
    public static final String TARGET_VERSION = "22.12.1.0";

    private static final String TAG = "TiebaQuiet";

    private final SharedPreferences remote;
    private final SharedPreferences legacyRemote;
    private final SharedPreferences local;
    private final SharedPreferences legacyLocal;

    public ConfigStore(Context context) {
        remote = ServiceBridge.remotePreferences(GROUP);
        legacyRemote = remote != null ? ServiceBridge.remotePreferences(LEGACY_GROUP) : null;
        Context app = context.getApplicationContext();
        local = app.getSharedPreferences(GROUP, Context.MODE_PRIVATE);
        legacyLocal = app.getSharedPreferences(LEGACY_GROUP, Context.MODE_PRIVATE);
    }

    /** true = 写的是框架存储，目标进程能读到。 */
    public boolean remote() {
        return remote != null;
    }

    /** 新 key 优先 → 旧 key 兜底 → 默认值；框架未绑定时读本地暂存供 UI 展示。 */
    public boolean get(String key, boolean defaultValue) {
        try {
            SharedPreferences newPrefs = remote != null ? remote : local;
            SharedPreferences oldPrefs = remote != null ? legacyRemote : legacyLocal;
            Boolean newValue = null;
            if (newPrefs != null && newPrefs.contains(key)) {
                newValue = newPrefs.getBoolean(key, defaultValue);
            }
            Boolean legacyValue = null;
            if (oldPrefs != null && oldPrefs.contains(key)) {
                legacyValue = oldPrefs.getBoolean(key, defaultValue);
            }
            return ConfigMigration.resolve(newValue, legacyValue, defaultValue);
        } catch (Throwable t) {
            Log.w(TAG, "read failed: " + key, t);
            return defaultValue;
        }
    }

    /**
     * 写开关：同时写新 group 与旧 group，保留一个版本供回滚。
     * 返回 false 表示两个存储都没写成，调用方需要提示并回滚 UI。
     */
    public boolean put(String key, boolean value) {
        try {
            SharedPreferences newPrefs = remote != null ? remote : local;
            SharedPreferences oldPrefs = remote != null ? legacyRemote : legacyLocal;
            if (newPrefs == null) {
                return false;
            }
            newPrefs.edit().putBoolean(key, value).apply();
            if (oldPrefs != null) {
                oldPrefs.edit().putBoolean(key, value).apply();
            }
            if (remote == null) {
                Log.w(TAG, "config written to LOCAL prefs (framework not bound): " + key);
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "write failed: " + key, t);
            return false;
        }
    }
}
