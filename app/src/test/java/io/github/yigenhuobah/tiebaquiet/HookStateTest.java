package io.github.yigenhuobah.tiebaquiet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
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
        assertEquals(1L, HookState.hit(f));
        assertEquals(2L, HookState.hit(f));
        assertEquals(2L, HookState.hitCount(f));
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
    public void snapshotTruncatesSkipReason() {
        Feature f = FeatureRegistry.byKey("profile_cleanup");
        String reason = "x".repeat(300);
        HookState.markNotFound(f, reason);
        String value = HookState.snapshot().get(ReportProtocol.PREFIX_SKIP + f.key);
        assertEquals(ReportProtocol.MAX_VALUE, value.length());
    }

    @Test
    public void snapshotIsSortedByKey() {
        Feature f = FeatureRegistry.byKey("clipboard_guard");
        HookState.installed(f, "clipboard");
        Map<String, String> snapshot = HookState.snapshot();
        String previous = "";
        for (String key : snapshot.keySet()) {
            assertTrue("快照键必须按字典序: " + previous + " > " + key,
                    previous.compareTo(key) <= 0);
            previous = key;
        }
    }

    @Test
    public void touchedIsSortedByCode() {
        Feature f = FeatureRegistry.byKey("one_key_sign");
        HookState.installed(f, "sign");
        String previous = "";
        for (Feature feature : HookState.touched()) {
            assertTrue("touched 必须按编号排序: " + previous + " > " + feature.code,
                    previous.compareTo(feature.code) <= 0);
            previous = feature.code;
        }
    }
}
