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
         * @param success 目标进程按“新 key 优先、旧 key 兜底”解析后能读到新值；
         *                新 group 失败但残留该 key 旧值时为 false（残留旧值会
         *                压过旧 group 的新值），调用方必须提示并回滚开关
         */
        void onWriteResult(String key, boolean success);
    }

    /** 恢复默认设置完成后的主线程回调。 */
    public interface ResetCallback {
        /**
         * @param success 新 group 默认值/版本回读一致，旧 group 已清理并在新句柄上
         *                确认无残留，本地 fallback 也已清理
         */
        void onResetResult(boolean success);
    }

    private final RemotePrefsProvider provider;
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
        this(provider, safeRemote(provider, GROUP), safeRemote(provider, LEGACY_GROUP),
                local, legacyLocal, writeExecutor, callbackExecutor);
    }

    private ConfigStore(RemotePrefsProvider provider,
                        SharedPreferences remote, SharedPreferences legacyRemote,
                        SharedPreferences local, SharedPreferences legacyLocal,
                        Executor writeExecutor, Executor callbackExecutor) {
        // 保留 provider：reset 的 commit 完成后必须重新取句柄回读，不能复用
        // commit 已改写内存状态的旧实例（见 resetToDefaults）。
        this.provider = provider;
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
                // 新 group 是否已持有该 key 必须在 commit 前记录：commit 失败不会
                // 改动已落盘内容，且框架 RemotePreferences 在 binder 提交前就更新
                // 本地缓存，失败后再 contains 读到的是不可信的新值。
                final boolean newGroupHadValue =
                        newPrefs != null && readBoolean(newPrefs, key) != null;
                if (newPrefs != null) {
                    try {
                        newCommitted = commit(newPrefs, key, value, true);
                    } catch (Throwable t) {
                        Log.w(TAG, "commit failed (new group): " + key, t);
                    }
                }
                if (oldPrefs != null) {
                    try {
                        legacyCommitted = commit(oldPrefs, key, value, false);
                    } catch (Throwable t) {
                        Log.w(TAG, "commit failed (legacy group): " + key, t);
                    }
                }
                final boolean success = ConfigStorePolicy.committed(
                        newCommitted, legacyCommitted, newGroupHadValue);
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

    /**
     * 恢复默认设置：后台串行执行，结果回主线程。
     *
     * <p>一次 reset 由两次 remote commit 组成：新 group 写入全部 Feature 的默认值 +
     * {@link Config#KEY_CONFIG_VERSION}（同一 commit），旧 group 移除全部 Feature key
     * （第二次 commit）。旧 group 存在时，其清理 commit 也必须成功——当前读取只看
     * 新 group（新 key 优先），但降级回旧版本后旧 group 会重新成为唯一来源，残留
     * 旧值会让“已恢复默认”在旧版本上失效。新 group 不可用时直接失败：默认值与
     * 版本只能写进新 group，仅清理旧 group 无法保证恢复结果。
     *
     * <p>两个 remote group 与本地 fallback 属于不同存储，无法跨 group 原子提交：
     * 固定按新 group → 旧 group → 本地 fallback 的顺序执行；任一步失败都报告
     * false，但已成功的步骤不会回滚。
     *
     * <p>回读使用 commit 后重新从 provider 取得的句柄，而不是 commit 已改写内存
     * 缓存的旧实例。若框架按 group 缓存并返回同一实例，这里仍无法穿透缓存；
     * {@code commit()} 的返回值仍是第一道判据。
     *
     * @return true = remote 可用，reset 已排队；false = 未排队，且回调（若提供）
     *         会收到 false（不写本地暂存）
     */
    public boolean resetToDefaults(final ResetCallback callback) {
        final SharedPreferences newPrefs = remote;
        final SharedPreferences oldPrefs = legacyRemote;
        if (!ConfigStorePolicy.canWrite(newPrefs != null, oldPrefs != null)
                || newPrefs == null) {
            // 本地暂存不是目标进程读取的存储，写进去只会造成“已恢复”的假象。
            Log.w(TAG, "config reset rejected: config group unavailable");
            notifyReset(callback, false);
            return false;
        }
        try {
            writeExecutor.execute(() -> {
                boolean success = performReset(newPrefs, oldPrefs);
                if (!success) {
                    Log.w(TAG, "config reset failed");
                }
                notifyReset(callback, success);
            });
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "config reset rejected", t);
            notifyReset(callback, false);
            return false;
        }
    }

    private boolean performReset(SharedPreferences newPrefs, SharedPreferences oldPrefs) {
        boolean newCommitted;
        try {
            newCommitted = commitDefaults(newPrefs);
        } catch (Throwable t) {
            Log.w(TAG, "reset commit failed (new group)", t);
            return false;
        }
        if (!newCommitted) {
            return false;
        }
        // 新 group 落盘后再清旧 group：新 group 失败时不留下“旧值已删”的半套状态。
        if (oldPrefs != null) {
            boolean legacyCleared;
            try {
                legacyCleared = clearLegacyFeatures(oldPrefs);
            } catch (Throwable t) {
                Log.w(TAG, "reset commit failed (legacy group)", t);
                return false;
            }
            if (!legacyCleared) {
                return false;
            }
        }

        // commit 会先更新同一 SharedPreferences 实例的内存缓存，复用旧实例回读
        // 可能掩盖 binder/磁盘失败；因此 commit 后重新取句柄再验证。
        final SharedPreferences freshNew = safeRemote(provider, GROUP);
        final SharedPreferences freshLegacy = safeRemote(provider, LEGACY_GROUP);
        if (freshNew == null) {
            Log.w(TAG, "reset verification failed: config group unavailable");
            return false;
        }
        if (!readBackDefaults(freshNew)) {
            return false;
        }
        if (freshLegacy != null) {
            // 旧 group 的删除必须在新句柄上确认；静默失败的 remove 不能报成功。
            if (!readBackLegacyCleared(freshLegacy)) {
                return false;
            }
        } else if (oldPrefs != null) {
            Log.w(TAG, "reset verification failed: legacy group unavailable");
            return false;
        }

        // remote 不可用时 get() 会回落到本地 config/features prefs；不清理的话，
        // 重置成功后在框架断开期间旧开关会重新出现。本地清理与 remote 写入
        // 无法原子提交，失败时整体仍报告 false。
        return clearLocalFallbacks();
    }

    /** 新 group 一次 commit 写入全部默认值与版本标记；不使用 clear()，不动无关 key。 */
    @SuppressLint("ApplySharedPref") // 后台线程同步落盘，必须拿到真实 commit 结果。
    private static boolean commitDefaults(SharedPreferences prefs) {
        SharedPreferences.Editor editor = prefs.edit();
        for (Feature feature : FeatureRegistry.all()) {
            editor.putBoolean(feature.key, feature.defaultEnabled);
        }
        editor.putInt(Config.KEY_CONFIG_VERSION, Config.CURRENT_CONFIG_VERSION);
        return editor.commit();
    }

    /** 旧 group 整组清空：全新安装时该 group 为空，退休 key 也不应残留。 */
    @SuppressLint("ApplySharedPref") // 后台线程同步落盘，必须拿到真实 commit 结果。
    private static boolean clearLegacyFeatures(SharedPreferences prefs) {
        return prefs.edit().clear().commit();
    }

    /** 回读新 group：全部开关必须等于默认值，版本标记必须为当前版本。 */
    private static boolean readBackDefaults(SharedPreferences prefs) {
        try {
            if (prefs.getInt(Config.KEY_CONFIG_VERSION, -1)
                    != Config.CURRENT_CONFIG_VERSION) {
                return false;
            }
            for (Feature feature : FeatureRegistry.all()) {
                if (!prefs.contains(feature.key)
                        || prefs.getBoolean(feature.key, !feature.defaultEnabled)
                        != feature.defaultEnabled) {
                    return false;
                }
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "reset read-back failed", t);
            return false;
        }
    }

    /** 在新取得的旧 group 句柄上确认整组已空（含退休 key）。 */
    private static boolean readBackLegacyCleared(SharedPreferences prefs) {
        try {
            if (!prefs.getAll().isEmpty()) {
                Log.w(TAG, "reset legacy group still has keys: " + prefs.getAll().keySet());
                return false;
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "reset legacy read-back failed", t);
            return false;
        }
    }

    /** 清理 get() 的本地回落值：Feature 开关与 config_version 都不能残留。 */
    private boolean clearLocalFallbacks() {
        // 两个本地文件分开 commit；即使一个失败也要尝试清另一个，避免留下更多残留。
        boolean configCleared = clearLocalFallback(local, "config");
        boolean featuresCleared = clearLocalFallback(legacyLocal, "features");
        return configCleared && featuresCleared;
    }

    @SuppressLint("ApplySharedPref") // 后台线程同步落盘，必须拿到真实 commit 结果。
    private static boolean clearLocalFallback(SharedPreferences prefs, String label) {
        if (prefs == null) {
            return true;
        }
        try {
            SharedPreferences.Editor editor = prefs.edit();
            for (Feature feature : FeatureRegistry.all()) {
                editor.remove(feature.key);
            }
            editor.remove(Config.KEY_CONFIG_VERSION);
            return editor.commit();
        } catch (Throwable t) {
            Log.w(TAG, "reset local fallback failed: " + label, t);
            return false;
        }
    }

    private void notifyReset(ResetCallback callback, boolean success) {
        if (callback == null || callbackExecutor == null) {
            return;
        }
        try {
            callbackExecutor.execute(() -> callback.onResetResult(success));
        } catch (Throwable t) {
            Log.w(TAG, "post reset callback failed", t);
        }
    }

    @SuppressLint("ApplySharedPref") // 后台线程同步落盘，必须拿到真实 commit 结果。
    private static boolean commit(SharedPreferences prefs, String key, boolean value,
                                  boolean stampConfigVersion) {
        SharedPreferences.Editor editor = prefs.edit().putBoolean(key, value);
        if (stampConfigVersion) {
            // 版本标记与开关同一个 commit：只写新 group，旧 group 保持原样。
            editor.putInt(Config.KEY_CONFIG_VERSION, Config.CURRENT_CONFIG_VERSION);
        }
        return editor.commit();
    }
}
