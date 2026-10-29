package io.github.yigenhuobah.tiebaquiet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

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
    public void validKeyAcceptsAll98GeneratedKeys() {
        int count = 0;
        for (Feature f : FeatureRegistry.all()) {
            assertTrue(ReportProtocol.validKey(ReportProtocol.PREFIX_REG + f.key));
            assertTrue(ReportProtocol.validKey(ReportProtocol.PREFIX_HIT + f.key));
            assertTrue(ReportProtocol.validKey(ReportProtocol.PREFIX_SKIP + f.key));
            assertTrue(ReportProtocol.validKey(ReportProtocol.PREFIX_CRIT + f.key));
            count += 4;
        }
        count += 2; // meta.session / meta.updated_at
        assertEquals(98, count);
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

        // 旧会话迟到写入必须整批丢弃；新会话可以覆盖。
        assertTrue(ReportProtocol.isStaleSession("200", "100"));
        assertTrue("旧版回传没有 session 也算迟到", ReportProtocol.isStaleSession("200", null));
        assertFalse(ReportProtocol.isStaleSession("200", "200"));
        assertFalse(ReportProtocol.isStaleSession("200", "300"));
        assertFalse(ReportProtocol.isStaleSession(null, "100"));

        assertTrue(ReportProtocol.isNewSession("100", "200"));
        assertFalse(ReportProtocol.isNewSession("200", "100"));
        assertFalse("非法 session 不触发新会话", ReportProtocol.isNewSession("abc", "xyz"));
    }

    /**
     * 旧会话落盘的 skip 必须被本次会话的空串覆盖，否则 failedFeatureCount
     * 会一直把已恢复正常的功能算作失败，横幅无法归零。
     */
    @Test
    public void emptyTextOverwritesStaleSkipAndResetsFailure() {
        String regKey = ReportProtocol.PREFIX_REG + "feed_ad_filter";
        String skipKey = ReportProtocol.PREFIX_SKIP + "feed_ad_filter";
        Map<String, String> store = new HashMap<>();
        store.put(regKey, "");
        store.put(skipKey, "上一次会话残留");
        long oldHit = ReportProtocol.mergeHit(0L, 7L);

        assertTrue("旧状态应计入失败", featureFailed(store, "feed_ad_filter", oldHit));

        store.put(regKey, ReportProtocol.mergeText(store.get(regKey), "template_set_list"));
        store.put(skipKey, ReportProtocol.mergeText(store.get(skipKey), ""));
        long hitAfter = ReportProtocol.mergeHit(oldHit, 0L);

        assertEquals("", store.get(skipKey));
        assertEquals("旧 hit 必须保留", 7L, hitAfter);
        assertFalse("横幅计数应归零",
                featureFailed(store, "feed_ad_filter", hitAfter));
    }

    private static boolean featureFailed(Map<String, String> store, String key, long hit) {
        return ReportProtocol.featureFailed(
                store.get(ReportProtocol.PREFIX_REG + key),
                store.get(ReportProtocol.PREFIX_SKIP + key),
                false, hit);
    }
}
