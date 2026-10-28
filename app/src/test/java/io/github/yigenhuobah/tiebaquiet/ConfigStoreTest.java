package io.github.yigenhuobah.tiebaquiet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        RecordingCallback callback = new RecordingCallback();
        ConfigStore store = store(null, null, Runnable::run, Runnable::run);

        assertFalse("没有 remote 存储时 remote() 必须为 false", store.remote());
        assertFalse("remote 不可用时 put 必须返回 false", store.put("splash_no_ad", true, callback));
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
    public void putWithoutCallbackStillCommits() {
        FakePrefs config = new FakePrefs();
        ConfigStore store = store(config, null, Runnable::run, Runnable::run);

        assertTrue(store.put("splash_no_ad", false));
        assertEquals(1, config.commitCount);
        assertEquals(Boolean.FALSE, config.values.get("splash_no_ad"));
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

    private static final class RecordingCallback implements ConfigStore.WriteCallback {
        final List<Boolean> results = new ArrayList<>();

        @Override
        public void onWriteResult(String key, boolean success) {
            results.add(success);
        }
    }

    private static final class FakePrefs implements SharedPreferences {
        final Map<String, Object> values = new HashMap<>();
        boolean commitResult = true;
        boolean throwOnRead;
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
            prefs.values.putAll(pending);
        }
    }
}
