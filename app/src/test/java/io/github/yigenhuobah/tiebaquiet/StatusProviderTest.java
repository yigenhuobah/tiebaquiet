package io.github.yigenhuobah.tiebaquiet;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * StatusProvider 失败统计的纯逻辑：TTL、启用状态与 failure 语义。
 *
 * <p>不构造 ContentProvider / SharedPreferences，只用 key → value 的 Map 重放
 * provider 的判定路径：过期 / 未回传 / 未启用都不计失败；reg 为空或 crit=1 才算失败，
 * 普通 skip 只作诊断。
 */
public class StatusProviderTest {

    private static final long NOW = 10_000_000L;

    @Test
    public void staleReportReturnsZero() {
        Set<String> enabled = enabled("splash_no_ad");
        Map<String, String> report = failing("splash_no_ad");
        assertEquals("超过 TTL 按未知处理",
                0, StatusProvider.countFailed(enabled, report,
                        NOW - ReportProtocol.DEFAULT_TTL_MS - 1L, NOW));
        assertEquals("恰好等于 TTL 仍有效",
                1, StatusProvider.countFailed(enabled, report,
                        NOW - ReportProtocol.DEFAULT_TTL_MS, NOW));
    }

    @Test
    public void missingTimestampReturnsZero() {
        assertEquals(0, StatusProvider.countFailed(enabled("splash_no_ad"),
                failing("splash_no_ad"), 0L, NOW));
        assertEquals(0, StatusProvider.countFailed(enabled("splash_no_ad"),
                failing("splash_no_ad"), -1L, NOW));
    }

    @Test
    public void missingRegistrationFails() {
        Map<String, String> report = new HashMap<>();
        report.put(StatusProvider.PREFIX_REG + "splash_no_ad", "");
        report.put(StatusProvider.PREFIX_SKIP + "splash_no_ad", "锚点缺失 x");
        report.put(StatusProvider.PREFIX_CRIT + "splash_no_ad", "1");
        assertEquals(1, StatusProvider.countFailed(enabled("splash_no_ad"), report, NOW - 1L, NOW));
    }

    @Test
    public void optionalSkipWithRegistrationDoesNotFail() {
        Map<String, String> report = new HashMap<>();
        report.put(StatusProvider.PREFIX_REG + "feed_ad_filter", "template_set_list");
        report.put(StatusProvider.PREFIX_SKIP + "feed_ad_filter", "方法未找到 x");
        report.put(StatusProvider.PREFIX_CRIT + "feed_ad_filter", "");
        assertEquals("部分锚点缺失保持普通 skip",
                0, StatusProvider.countFailed(enabled("feed_ad_filter"), report, NOW - 1L, NOW));
    }

    @Test
    public void criticalWithRegistrationFails() {
        Map<String, String> report = new HashMap<>();
        report.put(StatusProvider.PREFIX_REG + "hot_card_filter", "template_set_list");
        report.put(StatusProvider.PREFIX_CRIT + "hot_card_filter", "1");
        assertEquals(1, StatusProvider.countFailed(enabled("hot_card_filter"), report, NOW - 1L, NOW));
    }

    @Test
    public void hitWithoutRegistrationFails() {
        Map<String, String> report = new HashMap<>();
        report.put(StatusProvider.PREFIX_HIT + "live_card_filter", "5");
        assertEquals(1, StatusProvider.countFailed(enabled("live_card_filter"), report, NOW - 1L, NOW));
    }

    @Test
    public void disabledFeatureIsIgnored() {
        assertEquals(0, StatusProvider.countFailed(new HashSet<String>(),
                failing("splash_no_ad"), NOW - 1L, NOW));
    }

    @Test
    public void unreportedFeatureIsIgnored() {
        assertEquals(0, StatusProvider.countFailed(enabled("splash_no_ad"),
                new HashMap<String, String>(), NOW - 1L, NOW));
    }

    @Test
    public void countsOnlyFailedEnabledFeatures() {
        Set<String> enabled = enabled("splash_no_ad", "feed_ad_filter", "hot_card_filter");
        Map<String, String> report = new HashMap<>();
        // 明确失败：零锚点。
        report.put(StatusProvider.PREFIX_REG + "splash_no_ad", "");
        report.put(StatusProvider.PREFIX_SKIP + "splash_no_ad", "无可用锚点");
        report.put(StatusProvider.PREFIX_CRIT + "splash_no_ad", "1");
        // 正常：有注册，可选锚点缺失。
        report.put(StatusProvider.PREFIX_REG + "feed_ad_filter", "template_set_list");
        report.put(StatusProvider.PREFIX_SKIP + "feed_ad_filter", "方法未找到 x");
        // 明确失败：crit=1。
        report.put(StatusProvider.PREFIX_REG + "hot_card_filter", "template_set_list");
        report.put(StatusProvider.PREFIX_CRIT + "hot_card_filter", "1");

        assertEquals(2, StatusProvider.countFailed(enabled, report, NOW - 1L, NOW));
    }

    @Test
    public void nullInputsAreSafe() {
        assertEquals(0, StatusProvider.countFailed(null, new HashMap<String, String>(),
                NOW - 1L, NOW));
        assertEquals(0, StatusProvider.countFailed(enabled("splash_no_ad"), null,
                NOW - 1L, NOW));
    }

    private static Set<String> enabled(String... keys) {
        Set<String> set = new HashSet<>();
        for (String key : keys) {
            set.add(key);
        }
        return set;
    }

    private static Map<String, String> failing(String key) {
        Map<String, String> report = new HashMap<>();
        report.put(StatusProvider.PREFIX_REG + key, "");
        report.put(StatusProvider.PREFIX_SKIP + key, "无可用锚点");
        report.put(StatusProvider.PREFIX_CRIT + key, "1");
        return report;
    }
}
