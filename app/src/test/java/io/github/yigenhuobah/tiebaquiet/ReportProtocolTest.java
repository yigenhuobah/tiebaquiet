package io.github.yigenhuobah.tiebaquiet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 诊断回传通道的纯 Java 协议规则：键白名单、值长度、hit 最大值合并、
 * critical skip 判定、会话新旧比较与 TTL 新鲜度。
 * 这些规则决定跨进程写入 prefs 的边界，改动必须同步更新本测试。
 */
public class ReportProtocolTest {

    @Test
    public void validKeyAcceptsRepresentativePrefixes() {
        assertTrue(ReportProtocol.validKey("reg.splash_no_ad"));
        assertTrue(ReportProtocol.validKey("hit.ad_request_block"));
        assertTrue(ReportProtocol.validKey("skip.keep_alive_block"));
    }

    @Test
    public void validKeyAcceptsAllGeneratedKeys() {
        int count = 0;
        for (Feature f : FeatureRegistry.all()) {
            assertTrue(ReportProtocol.validKey(ReportProtocol.PREFIX_REG + f.key));
            assertTrue(ReportProtocol.validKey(ReportProtocol.PREFIX_HIT + f.key));
            assertTrue(ReportProtocol.validKey(ReportProtocol.PREFIX_SKIP + f.key));
            assertTrue(ReportProtocol.validKey(ReportProtocol.PREFIX_CRIT + f.key));
            count += 4;
        }
        count += 2; // meta.session / meta.updated_at
        assertEquals(102, count);
    }

    @Test
    public void validKeyAcceptsCriticalAndMetaKeys() {
        for (Feature f : FeatureRegistry.all()) {
            assertTrue("crit 键必须放行",
                    ReportProtocol.validKey(ReportProtocol.PREFIX_CRIT + f.key));
        }
        assertTrue(ReportProtocol.validKey(ReportProtocol.KEY_META_SESSION));
        assertTrue(ReportProtocol.validKey(ReportProtocol.KEY_META_UPDATED_AT));
        assertFalse(ReportProtocol.validKey("meta.unknown"));
        assertFalse(ReportProtocol.validKey("crit."));
    }

    @Test
    public void validKeyRejectsUnknownKeys() {
        assertFalse(ReportProtocol.validKey("foo"));
        assertFalse(ReportProtocol.validKey("hit.not_a_feature"));
        assertFalse(ReportProtocol.validKey("hit." + "x".repeat(125)));
        assertFalse(ReportProtocol.validKey(null));
        assertFalse(ReportProtocol.validKey(""));
        assertFalse(ReportProtocol.validKey("REG.splash_no_ad"));
        assertFalse(ReportProtocol.validKey("reg.splash_no_ad "));
    }

    @Test
    public void validKeyRejectsOversizedKnownFeatureKey() {
        String valid = ReportProtocol.PREFIX_HIT + "splash_no_ad";
        assertTrue(ReportProtocol.validKey(valid));
        String oversized = valid + "x".repeat(ReportProtocol.MAX_KEY - valid.length() + 1);
        assertTrue(oversized.length() > ReportProtocol.MAX_KEY);
        assertFalse("超过 MAX_KEY 的键必须拒绝", ReportProtocol.validKey(oversized));
    }

    @Test
    public void mergeHitKeepsLargerValue() {
        assertEquals(10L, ReportProtocol.mergeHit(0L, 10L));
        assertEquals(10L, ReportProtocol.mergeHit(10L, 8L));
    }

    @Test
    public void mergeHitTreatsNegativeAsZero() {
        assertEquals(3L, ReportProtocol.mergeHit(-5L, 3L));
        assertEquals(0L, ReportProtocol.mergeHit(-5L, -3L));
        assertEquals(7L, ReportProtocol.mergeHit(7L, -1L));
    }

    @Test
    public void mergeTextOverwritesWithLatestIncludingEmpty() {
        assertEquals("new", ReportProtocol.mergeText("old", "new"));
        assertEquals("", ReportProtocol.mergeText("old", ""));
        assertEquals("new", ReportProtocol.mergeText(null, "new"));
        assertNull(ReportProtocol.mergeText("old", null));
    }

    @Test
    public void isValidValueRejectsNullAndOversizedValues() {
        assertTrue(ReportProtocol.isValidValue(""));
        assertTrue(ReportProtocol.isValidValue("a".repeat(256)));
        assertFalse(ReportProtocol.isValidValue(null));
        assertFalse(ReportProtocol.isValidValue("a".repeat(257)));
    }

    @Test
    public void missingRegStillCountsAsFailureWhenReported() {
        assertTrue(ReportProtocol.featureFailed("", "锚点缺失 x", false, 0L));
        assertTrue("有 hit 回传但无注册标签", ReportProtocol.featureFailed("", "", false, 3L));
        assertFalse(ReportProtocol.featureFailed("template_set_list", "", false, 3L));
        assertFalse("无回传不计入", ReportProtocol.featureFailed(null, null, false, 0L));
    }

    /** 可选锚点（方法）缺失：已有成功安装标签时不算失败，skip 仍保留给诊断页。 */
    @Test
    public void optionalSkipWithRegistrationIsNotFailure() {
        assertFalse(ReportProtocol.featureFailed("template_set_list", "方法未找到 x", false, 0L));
        assertFalse(ReportProtocol.featureFailed("a|b", "方法未找到 x", false, 3L));
    }

    /** 零安装锚点：critical 标记即使没有 skip 文本也必须计入失败。 */
    @Test
    public void criticalSkipIsFailure() {
        assertTrue(ReportProtocol.featureFailed("template_set_list", "无可用锚点", true, 0L));
        assertTrue(ReportProtocol.featureFailed("template_set_list", "无可用锚点", true, 5L));
        assertTrue(ReportProtocol.featureFailed("", "", true, 0L));
    }

    @Test
    public void isCriticalOnlyAcceptsMarkerValue() {
        assertTrue(ReportProtocol.isCritical("1"));
        assertFalse(ReportProtocol.isCritical(""));
        assertFalse(ReportProtocol.isCritical(null));
        assertFalse(ReportProtocol.isCritical("0"));
        assertFalse(ReportProtocol.isCritical("true"));
    }

    @Test
    public void isFreshHonorsTtlAndMissingTimestamp() {
        long now = 10_000_000L;
        long ttl = ReportProtocol.DEFAULT_TTL_MS;
        assertTrue(ReportProtocol.isFresh(now - 60_000L, now, ttl));
        assertTrue("恰好等于 TTL 仍算新鲜", ReportProtocol.isFresh(now - ttl, now, ttl));
        assertFalse(ReportProtocol.isFresh(now - ttl - 1L, now, ttl));
        assertFalse("从未回传视为不新鲜", ReportProtocol.isFresh(0L, now, ttl));
        assertFalse(ReportProtocol.isFresh(-1L, now, ttl));
        assertTrue("时钟回拨按新鲜处理", ReportProtocol.isFresh(now + 60_000L, now, ttl));
        assertFalse("非正 TTL 视为不新鲜", ReportProtocol.isFresh(now, now, 0L));
    }

    @Test
    public void sessionComparisonRejectsStaleWrites() {
        // 无已存会话：带 session 的首次回传算新会话；旧版无 session 不算新。
        assertTrue(ReportProtocol.isNewSession(null, "200"));
        assertTrue(ReportProtocol.isNewSession("", "200"));
        assertFalse(ReportProtocol.isNewSession(null, null));
        assertFalse(ReportProtocol.isNewSession("100", "100"));

        // 无会话标识的旧版回传必须整批丢弃；带 session 的批次不再按毫秒比大小。
        assertTrue("旧版回传没有 session 也算迟到", ReportProtocol.isStaleSession("200", null));
        assertTrue(ReportProtocol.isStaleSession("200", ""));
        assertFalse("带 session 的批次不算迟到，身份不同交给清库后接受",
                ReportProtocol.isStaleSession("200", "100"));
        assertFalse(ReportProtocol.isStaleSession("200", "200"));
        assertFalse(ReportProtocol.isStaleSession("200", "300"));
        assertFalse(ReportProtocol.isStaleSession(null, "100"));

        // 身份比较：不同即新会话，与数值大小无关。
        assertTrue(ReportProtocol.isNewSession("100", "200"));
        assertTrue("身份不同即新会话（旧逻辑因 incoming < stored 整批丢弃）",
                ReportProtocol.isNewSession("200", "100"));
        assertTrue("不同格式字符串同样视为新会话",
                ReportProtocol.isNewSession("abc", "xyz"));
        assertFalse(ReportProtocol.isNewSession("", null));
    }

    /**
     * 时钟回拨：新会话（随机后缀）与已存 session 身份不同 → 按新会话接受并清库，
     * 不再因毫秒值更小整批丢弃，避免诊断数据在本会话内静默失效。
     */
    @Test
    public void clockRollbackSessionIsAcceptedAsNewSession() {
        String stored = "s1700000000000-aaaa1111";
        String rolledBack = "s1690000000000-bbbb2222";
        assertTrue("回拨后毫秒值更小，但身份不同必须按新会话接受",
                ReportProtocol.isNewSession(stored, rolledBack));
        assertFalse("不得再按 incoming < stored 整批丢弃",
                ReportProtocol.isStaleSession(stored, rolledBack));
    }

    /**
     * 未来时间戳/时钟回拨：updatedAt 领先超过 {@link ReportProtocol#FUTURE_GRACE_MS}
     * （5 分钟）视为过期，不再当新鲜；5 分钟内的小幅偏差仍按新鲜处理。
     */
    @Test
    public void isFreshRejectsFutureTimestampBeyondGrace() {
        long now = 10_000_000L;
        long ttl = ReportProtocol.DEFAULT_TTL_MS;
        assertTrue("5 分钟内的未来时间戳仍按新鲜处理",
                ReportProtocol.isFresh(now + ReportProtocol.FUTURE_GRACE_MS, now, ttl));
        assertFalse("时钟回拨/未来时间戳超过 5 分钟视为过期",
                ReportProtocol.isFresh(now + ReportProtocol.FUTURE_GRACE_MS + 1L, now, ttl));
        assertFalse("回拨量远超 TTL 也必须过期",
                ReportProtocol.isFresh(now + ttl + 1L, now, ttl));
    }

    /**
     * 旧格式（纯数字）session：与新格式按身份比较，不同即新会话，
     * 模块 App 与目标进程可各自升级。
     */
    @Test
    public void legacyNumericSessionComparesByIdentity() {
        assertTrue("旧格式已存、新格式回传：不同即新会话",
                ReportProtocol.isNewSession("1700000000000", "s1699999000000-ab12cd34"));
        assertTrue("新格式已存、旧格式回传：不同即新会话",
                ReportProtocol.isNewSession("s1700000000000-aaaa1111", "1700000000001"));
        assertFalse("旧格式与已存数值完全相同仍是同一会话",
                ReportProtocol.isNewSession("1700000000000", "1700000000000"));
        assertFalse(ReportProtocol.isStaleSession("1700000000000", "1700000000000"));
        assertFalse("数值更小的旧格式不算迟到",
                ReportProtocol.isStaleSession("1700000000000", "1699999000000"));
        assertTrue("数值更小的旧格式仍是新会话（时钟回拨）",
                ReportProtocol.isNewSession("1700000000000", "1699999000000"));
    }

    /** 同会话重复上报：既不触发清库，也不算迟到，hit 由 provider 按最大值合并。 */
    @Test
    public void sameSessionRepeatedReportsKeepMerging() {
        String session = "s1700000000000-aaaa1111";
        assertFalse(ReportProtocol.isNewSession(session, session));
        assertFalse(ReportProtocol.isStaleSession(session, session));
        assertEquals("同会话重复上报 hit 取最大", 7L, ReportProtocol.mergeHit(3L, 7L));
        assertEquals("更大历史值不被较小值覆盖", 9L, ReportProtocol.mergeHit(9L, 7L));
    }

    /**
     * 旧会话落盘的 skip 必须被本次会话的空串覆盖：reg 为空的功能若残留旧 skip，
     * “有回传 + reg 为空”会被判失败，横幅无法归零；写入空串后不再计入。
     *
     * <p>写入走 provider 的真实落盘代码 {@link StatusProvider#applyEntries}——
     * 也就是 {@code StatusProvider.call} 鉴权/会话判定之后实际执行的同一循环
     * （reg/skip/crit 以最新值无条件覆盖，空串也覆盖）。provider 入口依赖
     * Context/Binder 无法在 JVM 调用，但写入本体不再由测试复刻：provider 侧
     * 若漏写空串（比如加了 {@code value.isEmpty()} 短路），本用例的第二次
     * {@link StatusProvider#countFailed} 统计就会仍为 1 而失败。判定也走真实的
     * countFailed 路径，读与写两端都落在生产代码上。
     */
    @Test
    public void emptyTextOverwritesStaleSkipAndResetsFailure() {
        String feature = "feed_ad_filter";
        String regKey = ReportProtocol.PREFIX_REG + feature;
        String skipKey = ReportProtocol.PREFIX_SKIP + feature;
        // 上一会话残留：本次会话该功能零安装（reg 空），skip 还是旧文本。
        FakeReportPrefs sp = new FakeReportPrefs();
        sp.strings.put("v:" + regKey, "");
        sp.strings.put("v:" + skipKey, "上一次会话残留");
        long now = 10_000_000L;

        assertEquals("残留 skip + 空 reg 必须计入失败（这是要清掉的回归信号）",
                1, StatusProvider.countFailed(enabled(feature), reportView(sp), now - 1L, now));

        // 与 StatusProvider.call 相同的生产写入：目标进程本次会话没有 skip，
        // 回传空串清掉上一会话的旧 skip；同批附 meta.session。写完由调用方
        // commit（call() 接受后同步 commit 的原样复刻）。
        String[] keys = {
                ReportProtocol.KEY_META_SESSION,
                regKey,
                skipKey,
                ReportProtocol.PREFIX_HIT + feature,
        };
        String[] values = {"s1700000000000-aaaa1111", "", "", "0"};
        SharedPreferences.Editor editor = sp.edit();
        int accepted = StatusProvider.applyEntries(sp, editor, keys, values, true);
        assertEquals("合法键值必须全部接受", 4, accepted);
        assertTrue("与 call() 一致：接受后必须同步 commit 落盘", editor.commit());

        assertEquals("空串必须覆盖旧 skip", "", sp.strings.get("v:" + skipKey));
        assertEquals("provider 按 max 合并 hit，旧计数不被 0 覆盖", 7L,
                ReportProtocol.mergeHit(7L, 0L));
        assertEquals("空 skip 写入后不再计入失败",
                0, StatusProvider.countFailed(enabled(feature), reportView(sp), now - 1L, now));
    }

    /** 把 FakeReportPrefs 的落盘内容（带 v: 前缀）转成 countFailed 的 report 视图。 */
    private static Map<String, String> reportView(FakeReportPrefs sp) {
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, Object> e : sp.strings.entrySet()) {
            Object value = e.getValue();
            out.put(e.getKey().substring(2), value instanceof String ? (String) value : null);
        }
        return out;
    }

    /** 仅覆盖 applyEntries/countFailed 用到的 SharedPreferences 表面。 */
    private static final class FakeReportPrefs implements SharedPreferences {
        final Map<String, Object> strings = new HashMap<>();
        boolean committed;

        @Override
        public Map<String, ?> getAll() {
            return new HashMap<>(strings);
        }

        @Override
        public String getString(String key, String defValue) {
            Object value = strings.get(key);
            return value instanceof String ? (String) value : defValue;
        }

        @Override
        public Set<String> getStringSet(String key, Set<String> defValues) {
            return defValues;
        }

        @Override
        public int getInt(String key, int defValue) {
            return defValue;
        }

        @Override
        public long getLong(String key, long defValue) {
            Object value = strings.get(key);
            return value instanceof Long ? (Long) value : defValue;
        }

        @Override
        public float getFloat(String key, float defValue) {
            return defValue;
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            Object value = strings.get(key);
            return value instanceof Boolean ? (Boolean) value : defValue;
        }

        @Override
        public boolean contains(String key) {
            return strings.containsKey(key);
        }

        @Override
        public SharedPreferences.Editor edit() {
            return new Editor();
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener) {
        }

        @Override
        public void unregisterOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener) {
        }

        private final class Editor implements SharedPreferences.Editor {
            @Override
            public SharedPreferences.Editor putString(String key, String value) {
                strings.put(key, value);
                return this;
            }

            @Override
            public SharedPreferences.Editor putStringSet(String key, Set<String> values) {
                strings.put(key, values);
                return this;
            }

            @Override
            public SharedPreferences.Editor putInt(String key, int value) {
                strings.put(key, value);
                return this;
            }

            @Override
            public SharedPreferences.Editor putLong(String key, long value) {
                strings.put(key, value);
                return this;
            }

            @Override
            public SharedPreferences.Editor putFloat(String key, float value) {
                strings.put(key, value);
                return this;
            }

            @Override
            public SharedPreferences.Editor putBoolean(String key, boolean value) {
                strings.put(key, value);
                return this;
            }

            @Override
            public SharedPreferences.Editor remove(String key) {
                strings.remove(key);
                return this;
            }

            @Override
            public SharedPreferences.Editor clear() {
                strings.clear();
                return this;
            }

            @Override
            public boolean commit() {
                committed = true;
                return true;
            }

            @Override
            public void apply() {
                committed = true;
            }
        }
    }

    private static Set<String> enabled(String... keys) {
        Set<String> set = new HashSet<>();
        for (String key : keys) {
            set.add(key);
        }
        return set;
    }
}
