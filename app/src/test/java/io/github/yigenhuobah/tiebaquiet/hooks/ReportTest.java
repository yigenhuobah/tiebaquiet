package io.github.yigenhuobah.tiebaquiet.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Map;
import java.util.TreeMap;

import io.github.yigenhuobah.tiebaquiet.Feature;
import io.github.yigenhuobah.tiebaquiet.FeatureRegistry;
import io.github.yigenhuobah.tiebaquiet.HookState;
import io.github.yigenhuobah.tiebaquiet.ReportProtocol;

/**
 * Report 的快照完整性与回传节流规则。
 *
 * <p>快照：reg/skip/crit 对每个 touched Feature 都必须存在（可为空串），hit 只在
 * &gt; 0 时出现；每次发送带 meta.session / meta.updated_at。
 *
 * <p>节流：内容变化立即发；内容不变时按 5 分钟心跳重发。只有回传成功（provider
 * 非 null，即 commit 成功）才更新 sLast/sLastSentAt；provider 失败时调用方保持旧值，
 * 同一快照仍会被判定为待发送。比较时必须剔除 meta 键，否则时间戳每轮变化都会
 * 让心跳失效。这里用纯函数覆盖该契约，不依赖 Android / Robolectric。
 *
 * <p>HookState 是进程级静态状态：每个快照用例使用不同 Feature，避免互相污染。
 */
public class ReportTest {

    private static Map<String, String> batch(String... pairs) {
        Map<String, String> map = new TreeMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static Feature feature(String key) {
        Feature f = FeatureRegistry.byKey(key);
        assertNotNull("测试引用的 Feature 必须存在: " + key, f);
        return f;
    }

    @Test
    public void snapshotSendsEmptyRegSkipCritForEnabledFeature() {
        Feature f = feature("yun_dialog");
        HookState.enable(f);

        Map<String, String> snap = Report.snapshot();

        assertEquals("enable 后必须发送空 reg，清掉旧会话标签", "",
                snap.get(ReportProtocol.PREFIX_REG + f.key));
        assertEquals("enable 后必须发送空 skip，清掉旧会话 skip", "",
                snap.get(ReportProtocol.PREFIX_SKIP + f.key));
        assertEquals("enable 后必须发送空 crit，清掉旧会话 critical", "",
                snap.get(ReportProtocol.PREFIX_CRIT + f.key));
        assertFalse("hit 为 0 时不发送", snap.containsKey(ReportProtocol.PREFIX_HIT + f.key));
    }

    @Test
    public void snapshotSendsEmptyRegForSkipOnlyFeature() {
        Feature f = feature("rec_forum");
        HookState.markNotFound(f, "锚点缺失 rec_forum_strategy_h");

        Map<String, String> snap = Report.snapshot();

        assertTrue("未注册也必须发送 reg 键", snap.containsKey(ReportProtocol.PREFIX_REG + f.key));
        assertEquals("空串才能覆盖旧注册标签", "", snap.get(ReportProtocol.PREFIX_REG + f.key));
        assertEquals("锚点缺失 rec_forum_strategy_h",
                snap.get(ReportProtocol.PREFIX_SKIP + f.key));
        assertEquals("普通 skip 不能标 critical", "",
                snap.get(ReportProtocol.PREFIX_CRIT + f.key));
    }

    @Test
    public void snapshotKeepsPositiveHit() {
        Feature f = feature("tab_red_dot");
        HookState.installed(f, "tab_red_dot_home");
        HookState.hit(f);
        HookState.hit(f);

        Map<String, String> snap = Report.snapshot();

        assertEquals("2", snap.get(ReportProtocol.PREFIX_HIT + f.key));
        assertEquals("tab_red_dot_home", snap.get(ReportProtocol.PREFIX_REG + f.key));
        assertEquals("", snap.get(ReportProtocol.PREFIX_SKIP + f.key));
        assertEquals("", snap.get(ReportProtocol.PREFIX_CRIT + f.key));
    }

    @Test
    public void snapshotCarriesStableSessionAndUpdatedAt() {
        Map<String, String> first = Report.snapshot();
        String session = first.get(ReportProtocol.KEY_META_SESSION);
        assertNotNull("必须回传 meta.session", session);
        assertTrue("session 必须是可比较的启动时刻", Long.parseLong(session) > 0L);
        assertTrue("必须回传 meta.updated_at",
                Long.parseLong(first.get(ReportProtocol.KEY_META_UPDATED_AT)) > 0L);
        assertEquals("SESSION 常量即进程会话标识", Report.SESSION, session);

        Map<String, String> second = Report.snapshot();
        assertEquals("同一进程会话标识必须稳定", session,
                second.get(ReportProtocol.KEY_META_SESSION));
    }

    @Test
    public void snapshotMarksCriticalSkip() {
        Feature f = feature("easter_egg");
        HookState.markNotFoundCritical(f, "无可用锚点");

        Map<String, String> snap = Report.snapshot();

        assertEquals("1", snap.get(ReportProtocol.PREFIX_CRIT + f.key));
        assertEquals("无可用锚点", snap.get(ReportProtocol.PREFIX_SKIP + f.key));
        assertEquals("", snap.get(ReportProtocol.PREFIX_REG + f.key));
    }

    @Test
    public void snapshotKeepsOptionalSkipNonCritical() {
        Feature f = feature("default_channel");
        HookState.installed(f, "new_scroll_r");
        HookState.markNotFound(f, "方法未找到 x");

        Map<String, String> snap = Report.snapshot();

        assertEquals("new_scroll_r", snap.get(ReportProtocol.PREFIX_REG + f.key));
        assertEquals("方法未找到 x", snap.get(ReportProtocol.PREFIX_SKIP + f.key));
        assertEquals("部分锚点缺失不能标 critical", "",
                snap.get(ReportProtocol.PREFIX_CRIT + f.key));
    }

    @Test
    public void contentOfRemovesMetaKeys() {
        Map<String, String> content = Report.contentOf(batch(
                ReportProtocol.PREFIX_REG + "splash_no_ad", "",
                ReportProtocol.KEY_META_SESSION, "100",
                ReportProtocol.KEY_META_UPDATED_AT, "200"));
        assertEquals(batch(ReportProtocol.PREFIX_REG + "splash_no_ad", ""), content);
        assertTrue("null 快照应返回空内容", Report.contentOf(null).isEmpty());
    }

    @Test
    public void sameBatchWithinHeartbeatIsNotResent() {
        Map<String, String> snapshot = batch("hit.splash_no_ad", "1");
        long lastSentAt = 1_000_000L;
        assertFalse(Report.shouldSend(snapshot, batch("hit.splash_no_ad", "1"),
                lastSentAt + Report.HEARTBEAT_MS - 1, lastSentAt));
    }

    @Test
    public void sameBatchAtOrAfterHeartbeatIsResent() {
        Map<String, String> snapshot = batch("hit.splash_no_ad", "1");
        long lastSentAt = 1_000_000L;
        assertTrue("达到心跳边界必须重发",
                Report.shouldSend(snapshot, batch("hit.splash_no_ad", "1"),
                        lastSentAt + Report.HEARTBEAT_MS, lastSentAt));
        assertTrue("超过心跳必须重发",
                Report.shouldSend(snapshot, batch("hit.splash_no_ad", "1"),
                        lastSentAt + Report.HEARTBEAT_MS + 1, lastSentAt));
    }

    @Test
    public void differentBatchIsSentImmediately() {
        long now = 1_000_000L;
        assertTrue("值变化立即重发",
                Report.shouldSend(batch("hit.splash_no_ad", "2"),
                        batch("hit.splash_no_ad", "1"), now, now));
        assertTrue("键变化立即重发",
                Report.shouldSend(batch("reg.splash_no_ad", "splash"),
                        batch("hit.splash_no_ad", "1"), now, now));
    }

    @Test
    public void metaTimestampChangeAloneDoesNotTriggerSend() {
        long now = 1_000_000L;
        Map<String, String> current = batch(
                "hit.splash_no_ad", "1",
                ReportProtocol.KEY_META_SESSION, "100",
                ReportProtocol.KEY_META_UPDATED_AT, "999999");
        Map<String, String> last = batch(
                "hit.splash_no_ad", "1",
                ReportProtocol.KEY_META_SESSION, "100",
                ReportProtocol.KEY_META_UPDATED_AT, "111111");
        assertFalse("meta.updated_at 每轮都变，不能算内容变化",
                Report.shouldSend(current, last, now, now));
    }

    @Test
    public void metaOnlyBatchIsNeverSent() {
        Map<String, String> current = batch(
                ReportProtocol.KEY_META_SESSION, "100",
                ReportProtocol.KEY_META_UPDATED_AT, "999999");
        assertFalse("没有逐功能内容时无需回传", Report.shouldSend(current, batch(), 1_000_000L, 0L));
    }

    @Test
    public void emptyBatchIsNeverSent() {
        assertFalse(Report.shouldSend(batch(), batch(), 1_000_000L, 0L));
        assertFalse(Report.shouldSend(batch(), batch(), 10_000_000L, 1_000_000L));
    }

    @Test
    public void neverSentBatchIsSent() {
        assertTrue(Report.shouldSend(batch("hit.splash_no_ad", "1"),
                batch(), 1_000_000L, 0L));
        // last 与 batch 相同但从未成功发送（时间戳为 0）时也必须重发。
        assertTrue(Report.shouldSend(batch("hit.splash_no_ad", "1"),
                batch("hit.splash_no_ad", "1"), 1_000_000L, 0L));
    }

    @Test
    public void providerFailureLeavesBatchPending() {
        Map<String, String> snapshot = batch("hit.splash_no_ad", "1");
        // 上一次成功回传的是另一个快照。
        Map<String, String> last = batch("reg.splash_no_ad", "splash");
        long lastSentAt = 1_000_000L;
        long now = lastSentAt + 1_000L;

        // provider 返回 null（不可达 / commit 失败）：调用方不更新 last/lastSentAt。
        assertTrue("失败后同一快照必须仍待发送",
                Report.shouldSend(snapshot, last, now, lastSentAt));
        assertTrue("下一轮仍需重试",
                Report.shouldSend(snapshot, last, now + 1L, lastSentAt));

        // shouldSend 是纯函数，不得顺带把失败快照写成已发送。
        assertEquals(batch("reg.splash_no_ad", "splash"), last);
        assertEquals(1_000_000L, lastSentAt);
    }

    @Test
    public void successfulSendStartsHeartbeatWindow() {
        Map<String, String> snapshot = batch("hit.splash_no_ad", "1");
        long sentAt = 2_000_000L;
        // 成功回传后调用方更新 last/lastSentAt，心跳窗口内不再重发。
        assertFalse(Report.shouldSend(snapshot, snapshot, sentAt + 1_000L, sentAt));
        assertTrue(Report.shouldSend(snapshot, snapshot,
                sentAt + Report.HEARTBEAT_MS, sentAt));
    }
}
