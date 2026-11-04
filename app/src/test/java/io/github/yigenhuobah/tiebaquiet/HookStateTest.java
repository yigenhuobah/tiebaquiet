package io.github.yigenhuobah.tiebaquiet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * HookState 的纯 JVM 行为：命中计数、标签排序、skip 覆盖与快照格式。
 *
 * 每个用例使用不同的 Feature：HookState 是进程级静态状态，测试方法之间不能互相污染。
 */
public class HookStateTest {

    @Test
    public void hitIncrementsCount() {
        Feature f = FeatureRegistry.byKey("splash_no_ad");
        // HookState 是进程级静态状态：按基线增量断言，其他用例触碰同一 Feature 也不影响。
        long baseline = HookState.hitCount(f);
        assertEquals("每次 hit 恰好 +1", baseline + 1L, HookState.hit(f));
        assertEquals(baseline + 2L, HookState.hit(f));
        assertEquals("hitCount 与累加结果一致", baseline + 2L, HookState.hitCount(f));
    }

    @Test
    public void labelsAreSorted() {
        Feature f = FeatureRegistry.byKey("hot_card_filter");
        HookState.installed(f, "beta");
        HookState.installed(f, "alpha");
        assertEquals(Arrays.asList("alpha", "beta"), HookState.labels(f));
    }

    @Test
    public void markNotFoundKeepsLastReason() {
        Feature f = FeatureRegistry.byKey("update_dialog");
        HookState.markNotFound(f, "first");
        HookState.markNotFound(f, "second");
        assertEquals("second", HookState.skipReason(f));
        assertFalse("普通 skip 不能标 critical", HookState.hasCriticalSkip(f));
    }

    @Test
    public void markNotFoundCriticalSetsSkipAndCriticalFlag() {
        Feature f = FeatureRegistry.byKey("host_only");
        HookState.markNotFoundCritical(f, "无可用锚点");
        assertEquals("无可用锚点", HookState.skipReason(f));
        assertTrue("零成功安装锚点必须标 critical", HookState.hasCriticalSkip(f));
    }

    @Test
    public void criticalFlagSurvivesLaterOptionalSkip() {
        Feature f = FeatureRegistry.byKey("reverse_order");
        HookState.markNotFoundCritical(f, "无可用锚点");
        HookState.markNotFound(f, "部分锚点缺失");
        assertEquals("诊断页展示最后一个原因", "部分锚点缺失", HookState.skipReason(f));
        assertTrue("后续可选 skip 不能清掉 critical 标记", HookState.hasCriticalSkip(f));
    }

    @Test
    public void untouchedFeatureHasNoCriticalSkip() {
        Feature f = FeatureRegistry.byKey("no_watermark");
        assertFalse(HookState.hasCriticalSkip(f));
    }

    @Test
    public void untouchedFeatureHasEmptyState() {
        Feature f = FeatureRegistry.byKey("original_image");
        assertEquals(0L, HookState.hitCount(f));
        assertTrue("未触碰的 Feature 不应有标签", HookState.labels(f).isEmpty());
        assertNull("未触碰的 Feature 不应有 skip 原因", HookState.skipReason(f));
        assertEquals(0, HookState.installedCount(f));
    }

    @Test
    public void enableCreatesEmptyStateForUntouchedFeature() {
        Feature f = FeatureRegistry.byKey("force_night");
        HookState.enable(f);
        assertTrue("enable 后 Feature 必须进入 touched，才能回传空 reg/skip",
                HookState.touched().contains(f));
        assertTrue(HookState.labels(f).isEmpty());
        assertNull(HookState.skipReason(f));
        assertEquals(0L, HookState.hitCount(f));
        assertEquals(0, HookState.installedCount(f));
        assertFalse(HookState.hasCriticalSkip(f));
    }

    @Test
    public void installedCountTracksSuccessfulAnchors() {
        Feature f = FeatureRegistry.byKey("float_ad");
        assertEquals(0, HookState.installedCount(f));
        HookState.installed(f, "home_ad_float");
        HookState.installed(f, "pb_ad_float");
        assertEquals("每个成功安装标签都算一个锚点", 2, HookState.installedCount(f));
        HookState.installed(f, "home_ad_float");
        assertEquals("同一挂载点重复安装不重复计数", 2, HookState.installedCount(f));
    }

    @Test
    public void snapshotUsesProtocolPrefixes() {
        Feature f = FeatureRegistry.byKey("video_link");
        HookState.installed(f, "video_set_path");
        HookState.hit(f);
        HookState.hit(f);
        Map<String, String> snapshot = HookState.snapshot();
        assertEquals("video_set_path", snapshot.get(ReportProtocol.PREFIX_REG + f.key));
        assertEquals("2", snapshot.get(ReportProtocol.PREFIX_HIT + f.key));
    }

    @Test
    public void errorIncrementsCountIndependentlyOfHit() {
        Feature f = FeatureRegistry.byKey("clipboard_guard");
        // HookState 是进程级静态状态：按基线增量断言，避免与其它用例互相污染。
        long baseErrors = HookState.errorCount(f);
        long baseHits = HookState.hitCount(f);
        assertEquals("每次 error 恰好 +1", baseErrors + 1L, HookState.error(f));
        assertEquals(baseErrors + 2L, HookState.error(f));
        assertEquals("errorCount 与累加结果一致", baseErrors + 2L, HookState.errorCount(f));
        assertEquals("hook 体异常不能算作命中", baseHits, HookState.hitCount(f));
    }

    @Test
    public void snapshotIncludesErrorCount() {
        Feature f = FeatureRegistry.byKey("clipboard_guard");
        HookState.error(f);
        Map<String, String> snapshot = HookState.snapshot();
        assertEquals("错误计数必须以 err.<key> 进入快照",
                String.valueOf(HookState.errorCount(f)),
                snapshot.get(ReportProtocol.PREFIX_ERR + f.key));
    }

    @Test
    public void snapshotTruncatesSkipReason() {
        Feature f = FeatureRegistry.byKey("profile_cleanup");
        String reason = "x".repeat(300);
        HookState.markNotFound(f, reason);
        String value = HookState.snapshot().get(ReportProtocol.PREFIX_SKIP + f.key);
        assertEquals(ReportProtocol.MAX_VALUE, value.length());
    }

    @Test
    public void snapshotIsSortedByKey() {
        // 先 touch 键序较大的功能：快照若退回插入序实现，本用例必须失败；
        // 两个键保证单独运行时排序断言也不再恒真。
        Feature larger = FeatureRegistry.byKey("personalized_ad_off"); // reg.personalized_ad_off
        Feature smaller = FeatureRegistry.byKey("hide_store_tab");     // reg.hide_store_tab
        HookState.installed(larger, "personalized_ad");
        HookState.installed(smaller, "hide_store_tab");
        Map<String, String> snapshot = HookState.snapshot();

        List<String> keys = new ArrayList<>(snapshot.keySet());
        String smallKey = ReportProtocol.PREFIX_REG + smaller.key;
        String largeKey = ReportProtocol.PREFIX_REG + larger.key;
        assertTrue("本次写入的两个键都必须出现在快照里",
                keys.containsAll(Arrays.asList(smallKey, largeKey)));
        assertTrue("小键必须排在大键之前: " + keys,
                keys.indexOf(smallKey) < keys.indexOf(largeKey));
        String previous = "";
        for (String key : keys) {
            assertTrue("快照键必须按字典序: " + previous + " > " + key,
                    previous.compareTo(key) <= 0);
            previous = key;
        }
    }

    @Test
    public void touchedIsSortedByCode() {
        // 先 touch 编号较大的功能（PV-03），再 touch 编号较小的（AD-04）：
        // touched 若退回插入序实现，本用例必须失败；两个元素保证单独运行时不再恒真。
        Feature larger = FeatureRegistry.byKey("keep_alive_block");    // PV-03
        Feature smaller = FeatureRegistry.byKey("forum_promo_filter"); // AD-04
        HookState.installed(larger, "keep_alive");
        HookState.installed(smaller, "forum_promo");
        List<Feature> touched = HookState.touched();

        assertTrue("本次触碰的两个 Feature 都必须在 touched 里",
                touched.containsAll(Arrays.asList(smaller, larger)));
        assertTrue("编号小的功能必须排在前面",
                touched.indexOf(smaller) < touched.indexOf(larger));
        String previous = "";
        for (Feature feature : touched) {
            assertTrue("touched 必须按编号排序: " + previous + " > " + feature.code,
                    previous.compareTo(feature.code) <= 0);
            previous = feature.code;
        }
    }

    /**
     * core 原因列表：critical 原因后仍可记录可选 skip（skipReason 取最后一条），
     * 但回传摘要必须保留首个 critical，避免失败横幅原因被后来的可选 skip 顶掉。
     */
    @Test
    public void skipSummaryKeepsFirstCriticalAndLatestOptionalReason() {
        Feature f = FeatureRegistry.byKey("one_key_sign");
        HookState.markNotFoundCritical(f, "无可用锚点");
        HookState.markNotFound(f, "部分锚点缺失");

        assertEquals("最后一条原因供诊断页展示", "部分锚点缺失", HookState.skipReason(f));
        assertEquals("回传摘要必须同时保留 critical 与最新原因",
                "无可用锚点 | 部分锚点缺失", HookState.skipSummary(f));
        assertTrue("后续可选 skip 不能清掉 critical 标记", HookState.hasCriticalSkip(f));
    }

    /** 没有 critical 时摘要就是最后一条原因（与旧 skipReason 行为一致）。 */
    @Test
    public void skipSummaryFallsBackToLatestOptionalReason() {
        Feature f = FeatureRegistry.byKey("live_card_filter");
        HookState.markNotFound(f, "first");
        HookState.markNotFound(f, "second");

        assertEquals("second", HookState.skipSummary(f));
        assertFalse(HookState.hasCriticalSkip(f));
    }
}
