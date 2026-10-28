package io.github.yigenhuobah.tiebaquiet.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Map;
import java.util.TreeMap;

/**
 * Report 的回传节流规则：内容变化立即发；内容不变时按 5 分钟心跳重发。
 *
 * <p>只有回传成功（provider 非 null，即 commit 成功）才更新 sLast/sLastSentAt；
 * provider 失败时调用方保持旧值，同一快照仍会被判定为待发送。这里用纯函数覆盖该契约，
 * 不依赖 Android / Robolectric。
 */
public class ReportTest {

    private static Map<String, String> batch(String... pairs) {
        Map<String, String> map = new TreeMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
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
