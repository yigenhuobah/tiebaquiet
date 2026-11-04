package io.github.yigenhuobah.tiebaquiet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * ConfigStore 的 JVM 行为：remote 不可用拒绝写入、legacy group 独立兜底、
 * commit 结果异步回调、新 group 优先读取。
 *
 * <p>用假 SharedPreferences 和可注入 executor 覆盖 Android 胶水层的决策，
 * 不需要 Robolectric。
 */
public class ConfigStoreTest {

    @Test
    public void putRejectedWhenNoRemoteGroup() {
        FakePrefs local = new FakePrefs();
        RecordingCallback callback = new RecordingCallback();
        ConfigStore store = new ConfigStore(group -> null, local, null,
                Runnable::run, Runnable::run);

        assertFalse("没有 remote 存储时 remote() 必须为 false", store.remote());
        assertFalse("remote 不可用时 put 必须返回 false", store.put("splash_no_ad", true, callback));
        assertFalse("拒绝写入时不得写本地暂存假装成功（put 的文档承诺）",
                local.values.containsKey("splash_no_ad"));
        assertEquals("拒绝写入时不得触发本地 commit", 0, local.commitCount);
        assertFalse("本地暂存未被污染，get 必须仍回默认值", store.get("splash_no_ad", false));
        assertTrue("拒绝写入时不应触发回调", callback.results.isEmpty());
    }

    @Test
    public void legacyGroupIsBoundIndependentlyOfNewGroup() {
        FakePrefs features = new FakePrefs();
        List<String> requested = new ArrayList<>();
        ConfigStore store = new ConfigStore(group -> {
            requested.add(group);
            return ConfigStore.LEGACY_GROUP.equals(group) ? features : null;
        }, null, null, Runnable::run, Runnable::run);

        assertEquals("新 group 失败后仍必须尝试旧 group",
                Arrays.asList(ConfigStore.GROUP, ConfigStore.LEGACY_GROUP), requested);
        assertTrue("旧 group 可用时 remote() 必须为 true", store.remote());
        assertTrue("只写旧 group 也应被接受", store.put("splash_no_ad", true));
        assertEquals(Boolean.TRUE, features.values.get("splash_no_ad"));
    }

    @Test
    public void legacyGroupIsUsedForReadsWhenNewGroupMissing() {
        FakePrefs features = new FakePrefs();
        features.values.put("splash_no_ad", true);
        ConfigStore store = store(null, features, Runnable::run, Runnable::run);

        assertTrue("新 group 缺失时必须读旧 group", store.get("splash_no_ad", false));
    }

    @Test
    public void newGroupWinsOverLegacyForReads() {
        FakePrefs config = new FakePrefs();
        config.values.put("splash_no_ad", false);
        FakePrefs features = new FakePrefs();
        features.values.put("splash_no_ad", true);
        ConfigStore store = store(config, features, Runnable::run, Runnable::run);

        assertFalse("新 group 的显式 false 必须压过旧 group 的 true",
                store.get("splash_no_ad", true));
    }

    @Test
    public void legacyReadStillWorksWhenNewGroupThrows() {
        FakePrefs config = new FakePrefs();
        config.throwOnRead = true;
        FakePrefs features = new FakePrefs();
        features.values.put("splash_no_ad", true);
        ConfigStore store = store(config, features, Runnable::run, Runnable::run);

        assertTrue("新 group 读取异常时旧 group 仍必须兜底",
                store.get("splash_no_ad", false));
    }

    @Test
    public void localPrefsOnlyUsedWhenNoRemoteGroup() {
        FakePrefs local = new FakePrefs();
        local.values.put("splash_no_ad", true);
        ConfigStore store = new ConfigStore(group -> null, local, null,
                Runnable::run, Runnable::run);

        assertFalse(store.remote());
        assertTrue("remote 不可用时 UI 读本地暂存", store.get("splash_no_ad", false));
    }

    @Test
    public void commitRunsOnInjectedExecutorNotCallerThread() {
        FakePrefs config = new FakePrefs();
        List<Runnable> queued = new ArrayList<>();
        RecordingCallback callback = new RecordingCallback();
        ConfigStore store = store(config, null, queued::add, Runnable::run);

        assertTrue("remote 可用时 put 应接受并排队", store.put("splash_no_ad", true, callback));
        assertEquals("put 返回时不应已经 commit", 0, config.commitCount);
        assertTrue("结果必须等后台任务执行后才回调", callback.results.isEmpty());

        assertEquals(1, queued.size());
        queued.get(0).run();
        assertEquals(1, config.commitCount);
        assertEquals(Collections.singletonList(true), callback.results);
    }

    @Test
    public void bothCommitFailuresAreReported() {
        FakePrefs config = new FakePrefs();
        config.commitResult = false;
        FakePrefs features = new FakePrefs();
        features.commitResult = false;
        RecordingCallback callback = new RecordingCallback();
        ConfigStore store = store(config, features, Runnable::run, Runnable::run);

        assertTrue("put 只表示已排队", store.put("splash_no_ad", true, callback));
        assertEquals("两个 group 都失败必须回调 false",
                Collections.singletonList(false), callback.results);
    }

    @Test
    public void newGroupCommitSuccessIsEnough() {
        FakePrefs config = new FakePrefs();
        FakePrefs features = new FakePrefs();
        features.commitResult = false;
        RecordingCallback callback = new RecordingCallback();
        ConfigStore store = store(config, features, Runnable::run, Runnable::run);

        assertTrue(store.put("splash_no_ad", true, callback));
        assertEquals(Collections.singletonList(true), callback.results);
    }

    @Test
    public void legacyCommitSuccessIsEnoughWhenNewGroupFails() {
        FakePrefs config = new FakePrefs();
        config.commitResult = false;
        FakePrefs features = new FakePrefs();
        RecordingCallback callback = new RecordingCallback();
        ConfigStore store = store(config, features, Runnable::run, Runnable::run);

        assertTrue(store.put("splash_no_ad", true, callback));
        assertEquals("旧 group 成功时目标进程仍能读到", Collections.singletonList(true),
                callback.results);
        assertEquals(Boolean.TRUE, features.values.get("splash_no_ad"));
    }

    @Test
    public void legacySuccessIsRefusedWhenFailedNewGroupStillHoldsOldValue() {
        // 生产路径回归锁：新 group 此前已持有该 key（残留旧值），本次新 group
        // commit 失败、旧 group 成功。读取侧新 key 优先，残留旧值会压过旧 group
        // 刚写入的新值——目标进程仍读旧值，必须回调 false 让 UI 回滚，
        // 不能沿用两参“或”合并误报成功。
        FakePrefs config = new FakePrefs();
        config.commitResult = false;
        config.values.put("splash_no_ad", true);
        FakePrefs features = new FakePrefs();
        RecordingCallback callback = new RecordingCallback();
        ConfigStore store = store(config, features, Runnable::run, Runnable::run);

        assertTrue(store.put("splash_no_ad", false, callback));
        assertEquals("残留旧值遮蔽旧 group 新值时必须报告失败",
                Collections.singletonList(false), callback.results);
        assertEquals("旧 group 仍按实际写入落盘", Boolean.FALSE,
                features.values.get("splash_no_ad"));
    }

    @Test
    public void putWithoutCallbackStillCommits() {
        FakePrefs config = new FakePrefs();
        ConfigStore store = store(config, null, Runnable::run, Runnable::run);

        assertTrue(store.put("splash_no_ad", false));
        assertEquals(1, config.commitCount);
        assertEquals(Boolean.FALSE, config.values.get("splash_no_ad"));
    }

    @Test
    public void configVersionDefaultsToCurrentWhenAbsent() {
        assertEquals("缺失 config_version 按当前版本处理",
                Config.CURRENT_CONFIG_VERSION,
                Config.readConfigVersion(null, new FakePrefs()));
        assertEquals("新 group 未绑定时同样回退当前版本",
                Config.CURRENT_CONFIG_VERSION, Config.readConfigVersion(null, null));
    }

    @Test
    public void newerConfigVersionIsReadWithoutThrowing() {
        FakePrefs config = new FakePrefs();
        config.values.put(Config.KEY_CONFIG_VERSION, 2);

        assertEquals("更高版本只告警、按兼容模式继续",
                2, Config.readConfigVersion(null, config));
    }

    @Test
    public void loadDefaultsConfigVersionWhenAbsent() {
        FakePrefs config = new FakePrefs();
        List<String> logs = new ArrayList<>();

        Config loaded = Config.load(fakeModule(group ->
                Config.GROUP.equals(group) ? config : null, logs));

        assertEquals(Config.CURRENT_CONFIG_VERSION, loaded.configVersion());
        assertTrue("缺失版本不应告警", logs.isEmpty());
    }

    @Test
    public void loadAcceptsNewerConfigVersionWithSingleWarning() {
        FakePrefs config = new FakePrefs();
        config.values.put(Config.KEY_CONFIG_VERSION, 2);
        List<String> logs = new ArrayList<>();

        Config loaded = Config.load(fakeModule(group ->
                Config.GROUP.equals(group) ? config : null, logs));

        assertEquals("更高版本按兼容模式继续，读取结果保留原版本",
                2, loaded.configVersion());
        assertEquals("只允许告警一次", 1, logs.size());
        assertTrue("告警文案必须说明版本差异: " + logs.get(0),
                logs.get(0).contains("配置版本 v2 高于模块支持 v1，按兼容模式继续"));
    }

    @Test
    public void putStampsConfigVersionInNewGroupOnly() {
        FakePrefs config = new FakePrefs();
        FakePrefs features = new FakePrefs();
        ConfigStore store = store(config, features, Runnable::run, Runnable::run);

        assertTrue(store.put("splash_no_ad", false));
        assertEquals("版本标记必须与新值同一个新 group commit",
                Config.CURRENT_CONFIG_VERSION,
                config.values.get(Config.KEY_CONFIG_VERSION));
        assertEquals("新 group 只 commit 一次", 1, config.commitCount);
        assertFalse("旧 group 不写版本标记",
                features.values.containsKey(Config.KEY_CONFIG_VERSION));
    }

    @Test
    public void resetWithoutRemoteGroupsFailsWithoutLocalWrites() {
        FakePrefs local = new FakePrefs();
        local.values.put("splash_no_ad", true);
        RecordingResetCallback callback = new RecordingResetCallback();
        ConfigStore store = new ConfigStore(group -> null, local, null,
                Runnable::run, Runnable::run);

        assertFalse("remote 不可用时 reset 必须拒绝排队",
                store.resetToDefaults(callback));
        assertEquals("remote 不可用必须回调 false",
                Collections.singletonList(false), callback.results);
        assertEquals("不得写本地暂存", 0, local.commitCount);
        assertEquals("本地暂存内容不得被改动",
                Collections.singleton("splash_no_ad"), local.values.keySet());
    }

    @Test
    public void resetFailsWithoutNewGroupEvenIfLegacyIsAvailable() {
        FakePrefs features = new FakePrefs();
        features.values.put("splash_no_ad", true);
        RecordingResetCallback callback = new RecordingResetCallback();
        ConfigStore store = store(null, features, Runnable::run, Runnable::run);

        assertFalse("默认值与版本只能写进新 group，新 group 缺失必须失败",
                store.resetToDefaults(callback));
        assertEquals(Collections.singletonList(false), callback.results);
        assertEquals("不得只清理旧 group 留下半套状态", 0, features.commitCount);
        assertEquals(Boolean.TRUE, features.values.get("splash_no_ad"));
    }

    @Test
    public void resetWritesDefaultsAndRemovesLegacyKeys() {
        FakePrefs config = new FakePrefs();
        FakePrefs features = new FakePrefs();
        for (Feature f : FeatureRegistry.all()) {
            config.values.put(f.key, !f.defaultEnabled);
            features.values.put(f.key, !f.defaultEnabled);
        }
        config.values.put("unrelated", "keep");
        features.values.put("unrelated_legacy", "keep");
        RecordingResetCallback callback = new RecordingResetCallback();
        ConfigStore store = store(config, features, Runnable::run, Runnable::run);

        assertTrue(store.resetToDefaults(callback));
        assertEquals(Collections.singletonList(true), callback.results);
        for (Feature f : FeatureRegistry.all()) {
            assertEquals(f.key + " 必须恢复默认值",
                    f.defaultEnabled, config.values.get(f.key));
            assertFalse("旧 group 不得残留 " + f.key,
                    features.values.containsKey(f.key));
        }
        assertEquals(Config.CURRENT_CONFIG_VERSION,
                config.values.get(Config.KEY_CONFIG_VERSION));
        assertEquals("新 group 只 commit 一次", 1, config.commitCount);
        assertEquals("旧 group 只 commit 一次", 1, features.commitCount);
        assertEquals("不得动无关 key", "keep", config.values.get("unrelated"));
        // 旧 group 整个属于历史配置，fresh install 为空；退休 key 也不得残留。
        assertTrue("旧 group 必须整组清空", features.values.isEmpty());
    }

    @Test
    public void resetSucceedsWithoutLegacyGroup() {
        FakePrefs config = new FakePrefs();
        RecordingResetCallback callback = new RecordingResetCallback();
        ConfigStore store = store(config, null, Runnable::run, Runnable::run);

        assertTrue(store.resetToDefaults(callback));
        assertEquals(Collections.singletonList(true), callback.results);
        for (Feature f : FeatureRegistry.all()) {
            assertEquals(f.defaultEnabled, config.values.get(f.key));
        }
        assertEquals(Config.CURRENT_CONFIG_VERSION,
                config.values.get(Config.KEY_CONFIG_VERSION));
    }

    @Test
    public void resetFailsWhenReadBackDoesNotMatch() {
        FakePrefs config = new FakePrefs();
        // commit 返回 true，但布尔值没有真正落盘：回读必须让 reset 报告失败。
        config.dropBooleanWrites = true;
        RecordingResetCallback callback = new RecordingResetCallback();
        ConfigStore store = store(config, null, Runnable::run, Runnable::run);

        assertTrue(store.resetToDefaults(callback));
        assertEquals("commit 成功但回读不一致必须报告失败",
                Collections.singletonList(false), callback.results);
    }

    @Test
    public void resetDoesNotClearLegacyWhenNewGroupCommitFails() {
        FakePrefs config = new FakePrefs();
        config.commitResult = false;
        FakePrefs features = new FakePrefs();
        features.values.put("splash_no_ad", true);
        RecordingResetCallback callback = new RecordingResetCallback();
        ConfigStore store = store(config, features, Runnable::run, Runnable::run);

        assertTrue(store.resetToDefaults(callback));
        assertEquals(Collections.singletonList(false), callback.results);
        assertEquals("新 group 未落盘时不得清理旧 group", 0, features.commitCount);
        assertEquals(Boolean.TRUE, features.values.get("splash_no_ad"));
    }

    @Test
    public void resetRunsOnInjectedExecutorAndCallbacksAfterCommit() {
        FakePrefs config = new FakePrefs();
        List<Runnable> queued = new ArrayList<>();
        RecordingResetCallback callback = new RecordingResetCallback();
        ConfigStore store = store(config, null, queued::add, Runnable::run);

        assertTrue(store.resetToDefaults(callback));
        assertEquals("reset 返回时不应已经 commit", 0, config.commitCount);
        assertTrue("结果必须等后台任务执行后才回调", callback.results.isEmpty());

        assertEquals(1, queued.size());
        queued.get(0).run();
        assertEquals(1, config.commitCount);
        assertEquals(Collections.singletonList(true), callback.results);
    }

    @Test
    public void resetVerifiesReadBackOnFreshNewGroupHandle() {
        FakePrefs config = new FakePrefs();
        // 新句柄不反映 commit 结果（模拟旧实例缓存掩盖落盘失败）：
        // 版本与绝大多数默认值都正确，仅一个开关仍是旧值。
        FakePrefs staleConfig = new FakePrefs();
        staleConfig.values.put(Config.KEY_CONFIG_VERSION, Config.CURRENT_CONFIG_VERSION);
        for (Feature f : FeatureRegistry.all()) {
            staleConfig.values.put(f.key, f.defaultEnabled);
        }
        staleConfig.values.put("splash_no_ad", false);
        AtomicBoolean fresh = new AtomicBoolean(false);
        RecordingResetCallback callback = new RecordingResetCallback();
        ConfigStore store = new ConfigStore(group -> {
            if (ConfigStore.GROUP.equals(group)) {
                return fresh.get() ? staleConfig : config;
            }
            return null;
        }, null, null, Runnable::run, Runnable::run);

        fresh.set(true);
        assertTrue(store.resetToDefaults(callback));
        assertEquals("必须在新取得的句柄上回读，不能复用 commit 改写过的旧实例",
                Collections.singletonList(false), callback.results);
        assertEquals("旧句柄上的 commit 仍然发生了", 1, config.commitCount);
    }

    @Test
    public void resetVerifiesLegacyRemovalOnFreshHandle() {
        FakePrefs config = new FakePrefs();
        FakePrefs features = new FakePrefs();
        features.values.put("splash_no_ad", true);
        // 新句柄仍残留 Feature key：remove 静默失败不能被当成成功。
        FakePrefs staleFeatures = new FakePrefs();
        staleFeatures.values.put("splash_no_ad", true);
        AtomicBoolean fresh = new AtomicBoolean(false);
        RecordingResetCallback callback = new RecordingResetCallback();
        ConfigStore store = new ConfigStore(group -> {
            if (ConfigStore.GROUP.equals(group)) {
                return config;
            }
            if (ConfigStore.LEGACY_GROUP.equals(group)) {
                return fresh.get() ? staleFeatures : features;
            }
            return null;
        }, null, null, Runnable::run, Runnable::run);

        fresh.set(true);
        assertTrue(store.resetToDefaults(callback));
        assertEquals("旧 group 删除后必须在新句柄上确认 key 不存在",
                Collections.singletonList(false), callback.results);
        assertEquals("旧句柄上的删除 commit 仍然发生了", 1, features.commitCount);
        assertFalse("旧句柄上的 key 已被删除",
                features.values.containsKey("splash_no_ad"));
    }

    @Test
    public void resetClearsLocalFallbackPrefs() {
        FakePrefs config = new FakePrefs();
        FakePrefs features = new FakePrefs();
        FakePrefs local = new FakePrefs();
        FakePrefs legacyLocal = new FakePrefs();
        for (Feature f : FeatureRegistry.all()) {
            local.values.put(f.key, !f.defaultEnabled);
            legacyLocal.values.put(f.key, !f.defaultEnabled);
        }
        local.values.put(Config.KEY_CONFIG_VERSION, 99);
        legacyLocal.values.put(Config.KEY_CONFIG_VERSION, 99);
        local.values.put("unrelated", "keep");
        legacyLocal.values.put("unrelated_legacy", "keep");
        RecordingResetCallback callback = new RecordingResetCallback();
        ConfigStore store = new ConfigStore(group -> {
            if (ConfigStore.GROUP.equals(group)) {
                return config;
            }
            if (ConfigStore.LEGACY_GROUP.equals(group)) {
                return features;
            }
            return null;
        }, local, legacyLocal, Runnable::run, Runnable::run);

        assertTrue(store.resetToDefaults(callback));
        assertEquals(Collections.singletonList(true), callback.results);
        for (Feature f : FeatureRegistry.all()) {
            assertFalse("本地 config fallback 不得残留 " + f.key,
                    local.values.containsKey(f.key));
            assertFalse("本地 features fallback 不得残留 " + f.key,
                    legacyLocal.values.containsKey(f.key));
        }
        assertFalse("本地 config fallback 不得残留 config_version",
                local.values.containsKey(Config.KEY_CONFIG_VERSION));
        assertFalse("本地 features fallback 不得残留 config_version",
                legacyLocal.values.containsKey(Config.KEY_CONFIG_VERSION));
        assertEquals("不得动本地无关 key", "keep", local.values.get("unrelated"));
        assertEquals("不得动本地无关旧 key", "keep",
                legacyLocal.values.get("unrelated_legacy"));
        assertEquals(1, local.commitCount);
        assertEquals(1, legacyLocal.commitCount);
    }

    @Test
    public void resetFailsWhenLocalFallbackCommitFails() {
        FakePrefs config = new FakePrefs();
        FakePrefs local = new FakePrefs();
        local.values.put("splash_no_ad", true);
        local.commitResult = false;
        RecordingResetCallback callback = new RecordingResetCallback();
        ConfigStore store = new ConfigStore(group ->
                ConfigStore.GROUP.equals(group) ? config : null,
                local, null, Runnable::run, Runnable::run);

        assertTrue(store.resetToDefaults(callback));
        assertEquals("本地 fallback 清理失败必须计入 reset 结果",
                Collections.singletonList(false), callback.results);
        assertEquals("本地 key 未被改动", Boolean.TRUE,
                local.values.get("splash_no_ad"));
    }

    private static ConfigStore store(SharedPreferences config, SharedPreferences features,
                                     java.util.concurrent.Executor writeExecutor,
                                     java.util.concurrent.Executor callbackExecutor) {
        return new ConfigStore(group -> {
            if (ConfigStore.GROUP.equals(group)) {
                return config;
            }
            if (ConfigStore.LEGACY_GROUP.equals(group)) {
                return features;
            }
            return null;
        }, null, null, writeExecutor, callbackExecutor);
    }

    /**
     * 最小 XposedModule：attachFramework 的代理只实现 Config.load 用到的
     * getRemotePreferences 与 log，避免依赖真实框架。
     */
    private static XposedModule fakeModule(ConfigStore.RemotePrefsProvider provider,
                                           List<String> logs) {
        XposedModule module = new XposedModule() {
        };
        XposedInterface base = (XposedInterface) Proxy.newProxyInstance(
                ConfigStoreTest.class.getClassLoader(),
                new Class<?>[]{XposedInterface.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        switch (method.getName()) {
                            case "toString":
                                return "fake XposedInterface";
                            case "hashCode":
                                return System.identityHashCode(proxy);
                            case "equals":
                                return proxy == args[0];
                            default:
                                throw new AssertionError(method.getName());
                        }
                    }
                    if ("getRemotePreferences".equals(method.getName())) {
                        return provider.get((String) args[0]);
                    }
                    if ("log".equals(method.getName())) {
                        logs.add((String) args[2]);
                        return null;
                    }
                    throw new AssertionError("unexpected XposedInterface call: "
                            + method.getName());
                });
        module.attachFramework(base, () -> {
        });
        return module;
    }

    private static final class RecordingCallback implements ConfigStore.WriteCallback {
        final List<Boolean> results = new ArrayList<>();

        @Override
        public void onWriteResult(String key, boolean success) {
            results.add(success);
        }
    }

    private static final class RecordingResetCallback implements ConfigStore.ResetCallback {
        final List<Boolean> results = new ArrayList<>();

        @Override
        public void onResetResult(boolean success) {
            results.add(success);
        }
    }

    private static final class FakePrefs implements SharedPreferences {
        final Map<String, Object> values = new HashMap<>();
        boolean commitResult = true;
        boolean throwOnRead;
        /** 模拟 commit 返回 true 但布尔值未真正落盘（回读失败用例）。 */
        boolean dropBooleanWrites;
        int commitCount;
        int applyCount;

        @Override
        public Map<String, ?> getAll() {
            return new HashMap<>(values);
        }

        @Override
        public String getString(String key, String defValue) {
            Object value = values.get(key);
            return value instanceof String ? (String) value : defValue;
        }

        @Override
        public Set<String> getStringSet(String key, Set<String> defValues) {
            Object value = values.get(key);
            if (value instanceof Set) {
                @SuppressWarnings("unchecked")
                Set<String> set = (Set<String>) value;
                return set;
            }
            return defValues;
        }

        @Override
        public int getInt(String key, int defValue) {
            Object value = values.get(key);
            return value instanceof Integer ? (Integer) value : defValue;
        }

        @Override
        public long getLong(String key, long defValue) {
            Object value = values.get(key);
            return value instanceof Long ? (Long) value : defValue;
        }

        @Override
        public float getFloat(String key, float defValue) {
            Object value = values.get(key);
            return value instanceof Float ? (Float) value : defValue;
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            Object value = values.get(key);
            return value instanceof Boolean ? (Boolean) value : defValue;
        }

        @Override
        public boolean contains(String key) {
            if (throwOnRead) {
                throw new IllegalStateException("fake read failure");
            }
            return values.containsKey(key);
        }

        @Override
        public SharedPreferences.Editor edit() {
            return new FakeEditor(this);
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener) {
        }

        @Override
        public void unregisterOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener) {
        }
    }

    private static final class FakeEditor implements SharedPreferences.Editor {
        private final FakePrefs prefs;
        private final Map<String, Object> pending = new HashMap<>();
        private final Set<String> removals = new HashSet<>();
        private boolean clear;

        FakeEditor(FakePrefs prefs) {
            this.prefs = prefs;
        }

        @Override
        public SharedPreferences.Editor putString(String key, String value) {
            pending.put(key, value);
            return this;
        }

        @Override
        public SharedPreferences.Editor putStringSet(String key, Set<String> values) {
            pending.put(key, values);
            return this;
        }

        @Override
        public SharedPreferences.Editor putInt(String key, int value) {
            pending.put(key, value);
            return this;
        }

        @Override
        public SharedPreferences.Editor putLong(String key, long value) {
            pending.put(key, value);
            return this;
        }

        @Override
        public SharedPreferences.Editor putFloat(String key, float value) {
            pending.put(key, value);
            return this;
        }

        @Override
        public SharedPreferences.Editor putBoolean(String key, boolean value) {
            pending.put(key, value);
            return this;
        }

        @Override
        public SharedPreferences.Editor remove(String key) {
            removals.add(key);
            return this;
        }

        @Override
        public SharedPreferences.Editor clear() {
            clear = true;
            return this;
        }

        @Override
        public boolean commit() {
            prefs.commitCount++;
            if (!prefs.commitResult) {
                return false;
            }
            applyChanges();
            return true;
        }

        @Override
        public void apply() {
            prefs.applyCount++;
            if (prefs.commitResult) {
                applyChanges();
            }
        }

        private void applyChanges() {
            if (clear) {
                prefs.values.clear();
            }
            prefs.values.keySet().removeAll(removals);
            if (prefs.dropBooleanWrites) {
                for (Map.Entry<String, Object> entry : pending.entrySet()) {
                    if (!(entry.getValue() instanceof Boolean)) {
                        prefs.values.put(entry.getKey(), entry.getValue());
                    }
                }
            } else {
                prefs.values.putAll(pending);
            }
        }
    }
}
