package io.github.yigenhuobah.tiebaquiet;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 模块 App 侧的开关读写。
 *
 * <p>必须写框架的 remote preferences：贴吧进程读的是那份存储。框架未绑定或
 * 没有 PROP_CAP_REMOTE 时 {@link #put} 直接返回 false，由 UI 回滚；不再写本地
 * 暂存假装成功。
 *
 * <p>配置迁移：新 group {@code config} 优先，旧 group {@code features} 只读兜底并双写。
 * 两个 group 的 remote prefs 各自独立获取——新 group 绑定失败时仍会尝试旧 group。
 *
 * <p>线程模型：{@link #put} 只做决策并排队，真正的 {@code commit()} 在单线程后台
 * executor 上串行执行，结果通过 {@link WriteCallback} 回主线程。主线程不会发生
 * binder / 磁盘写入。
 */
public final class ConfigStore {

    public static final String GROUP = ConfigMigration.GROUP;
    public static final String LEGACY_GROUP = ConfigMigration.LEGACY_GROUP;

    /** 目标应用包名；<queries> 已声明，Android 11+ 才能读到版本与 UID。 */
    public static final String TARGET_PACKAGE = "com.baidu.tieba";
    public static final String TARGET_VERSION = "22.13.1.1";

    private static final String TAG = "TiebaQuiet";

    /** 所有 remote commit 串行执行，避免快速连续切换时后写覆盖先写。 */
    private static final ExecutorService WRITE_EXECUTOR =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "tiebaquiet-config-write");
                t.setDaemon(true);
                return t;
            });

    /** 写入结果统一回主线程；用 lambda 延迟取 Looper，JVM 单测可注入直接执行器。 */
    private static final Executor MAIN_EXECUTOR = command -> {
        try {
            new Handler(Looper.getMainLooper()).post(command);
        } catch (Throwable t) {
            Log.w(TAG, "post config callback failed", t);
        }
    };

    /** 生产实现是 {@link ServiceBridge#remotePreferences(String)}；单测可注入假实现。 */
    interface RemotePrefsProvider {
        SharedPreferences get(String group);
    }

    /** 后台 commit 完成后的主线程回调。 */
    public interface WriteCallback {
        /**
         * @param key     写入的开关 key
         * @param success 新 group 或旧 group 至少一个真实 commit 成功
         */
        void onWriteResult(String key, boolean success);
    }

    private final SharedPreferences remote;
    private final SharedPreferences legacyRemote;
    private final SharedPreferences local;
    private final SharedPreferences legacyLocal;
    private final Executor writeExecutor;
    private final Executor callbackExecutor;

    public ConfigStore(Context context) {
        this(ServiceBridge::remotePreferences, context, WRITE_EXECUTOR, MAIN_EXECUTOR);
    }

    private ConfigStore(RemotePrefsProvider provider, Context context,
                        Executor writeExecutor, Executor callbackExecutor) {
        this(provider,
                localPrefs(context, GROUP),
                localPrefs(context, LEGACY_GROUP),
                writeExecutor, callbackExecutor);
    }

    /** JVM 单测入口：remote 两个 group 由 provider 独立提供，local 可直接给假实现。 */
    ConfigStore(RemotePrefsProvider provider, SharedPreferences local,
                SharedPreferences legacyLocal, Executor writeExecutor,
                Executor callbackExecutor) {
        this(safeRemote(provider, GROUP), safeRemote(provider, LEGACY_GROUP),
                local, legacyLocal, writeExecutor, callbackExecutor);
    }

    private ConfigStore(SharedPreferences remote, SharedPreferences legacyRemote,
                        SharedPreferences local, SharedPreferences legacyLocal,
                        Executor writeExecutor, Executor callbackExecutor) {
        this.remote = remote;
        // 旧 group 独立获取：新 group 绑定失败时仍然尝试 features 兜底。
        this.legacyRemote = legacyRemote;
        this.local = local;
        this.legacyLocal = legacyLocal;
        this.writeExecutor = writeExecutor;
        this.callbackExecutor = callbackExecutor;
    }

    private static SharedPreferences localPrefs(Context context, String group) {
        try {
            return context.getApplicationContext()
                    .getSharedPreferences(group, Context.MODE_PRIVATE);
        } catch (Throwable t) {
            return null;
        }
    }

    private static SharedPreferences safeRemote(RemotePrefsProvider provider, String group) {
        if (provider == null) {
            return null;
        }
        try {
            return provider.get(group);
        } catch (Throwable t) {
            return null;
        }
    }

    /** true = 至少一个新/旧 remote group 可用，写入能到达框架存储。 */
    public boolean remote() {
        return remote != null || legacyRemote != null;
    }

    /** 新 key 优先 → 旧 key 兜底 → 默认值；remote 都不可用时读本地暂存供 UI 展示。 */
    public boolean get(String key, boolean defaultValue) {
        boolean remoteMode = remote != null || legacyRemote != null;
        SharedPreferences newPrefs = remoteMode ? remote : local;
        SharedPreferences oldPrefs = remoteMode ? legacyRemote : legacyLocal;
        // 两个 group 分开读取：新 group 抛异常时旧 group 仍要兜底。
        Boolean newValue = readBoolean(newPrefs, key);
        Boolean legacyValue = readBoolean(oldPrefs, key);
        return ConfigMigration.resolve(newValue, legacyValue, defaultValue);
    }

    private static Boolean readBoolean(SharedPreferences prefs, String key) {
        if (prefs == null) {
            return null;
        }
        try {
            return prefs.contains(key) ? prefs.getBoolean(key, false) : null;
        } catch (Throwable t) {
            Log.w(TAG, "read failed: " + key, t);
            return null;
        }
    }

    /**
     * 写开关：remote 可用时排队，新 group 与旧 group 各自独立 commit。
     *
     * @return true = remote 可用，写入已排队（真实结果见 callback）；
     * false = remote 不可用，未写入，调用方必须提示并回滚 UI。
     */
    public boolean put(String key, boolean value) {
        return put(key, value, null);
    }

    /**
     * 异步写开关。主线程只做可用性判断，{@code commit()} 在后台单线程执行；
     * 结果通过 callback 回主线程，失败时调用方应 Toast 并回滚开关。
     */
    public boolean put(final String key, final boolean value, final WriteCallback callback) {
        if (key == null) {
            return false;
        }
        final SharedPreferences newPrefs = remote;
        final SharedPreferences oldPrefs = legacyRemote;
        if (!ConfigStorePolicy.canWrite(newPrefs != null, oldPrefs != null)) {
            // 不写本地暂存：目标进程读不到，静默成功只会造成“已保存”的假象。
            return false;
        }
        try {
            writeExecutor.execute(() -> {
                boolean newCommitted = false;
                boolean legacyCommitted = false;
                if (newPrefs != null) {
                    try {
                        newCommitted = commit(newPrefs, key, value);
                    } catch (Throwable t) {
                        Log.w(TAG, "commit failed (new group): " + key, t);
                    }
                }
                if (oldPrefs != null) {
                    try {
                        legacyCommitted = commit(oldPrefs, key, value);
                    } catch (Throwable t) {
                        Log.w(TAG, "commit failed (legacy group): " + key, t);
                    }
                }
                final boolean success = ConfigStorePolicy.committed(newCommitted, legacyCommitted);
                if (!success) {
                    Log.w(TAG, "config write failed: " + key);
                }
                if (callback != null && callbackExecutor != null) {
                    callbackExecutor.execute(() -> callback.onWriteResult(key, success));
                }
            });
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "config write rejected: " + key, t);
            return false;
        }
    }

    @SuppressLint("ApplySharedPref") // 后台线程同步落盘，必须拿到真实 commit 结果。
    private static boolean commit(SharedPreferences prefs, String key, boolean value) {
        return prefs.edit().putBoolean(key, value).commit();
    }
}
