package io.github.yigenhuobah.tiebaquiet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * SettingsFragment 写回状态机、签到发送决策与目标版本缓存的纯逻辑。
 *
 * <p>不构造 Fragment / ConfigStore，只重放判定函数：
 * 刷新读到旧值 vs 已确认落盘值的取舍（confirmed 只由成功落盘更新，刷新只读不写）、
 * 冷启动签到广播的就绪判定、目标版本查询失败不缓存 null。
 */
public class SettingsFragmentTest {

    private static final long READ_TOKEN = 10L;

    @Test
    public void pendingWriteWinsOverEverything() {
        assertEquals("写入尚未 commit：保留用户乐观值",
                Boolean.TRUE, SettingsFragment.resolveDisplayedValue(
                        Boolean.TRUE, new SettingsFragment.ConfirmedWrite(false, 99L),
                        READ_TOKEN, Boolean.FALSE));
        assertEquals(Boolean.FALSE, SettingsFragment.resolveDisplayedValue(
                Boolean.FALSE, null, READ_TOKEN, Boolean.TRUE));
    }

    @Test
    public void confirmedWriteAfterReadWinsOverStaleRefreshValue() {
        // 写回调先于 apply 到达：刷新在 commit 之前读取，旧读值不得覆盖刚写成功的开关。
        SettingsFragment.ConfirmedWrite confirmed =
                new SettingsFragment.ConfirmedWrite(true, READ_TOKEN + 1L);
        assertEquals(Boolean.TRUE, SettingsFragment.resolveDisplayedValue(
                null, confirmed, READ_TOKEN, Boolean.FALSE));
        assertEquals(Boolean.FALSE, SettingsFragment.resolveDisplayedValue(
                null, new SettingsFragment.ConfirmedWrite(false, READ_TOKEN + 1L),
                READ_TOKEN, Boolean.TRUE));
    }

    @Test
    public void confirmedWriteAlreadyReadFallsBackToRefreshedValue() {
        SettingsFragment.ConfirmedWrite confirmed =
                new SettingsFragment.ConfirmedWrite(true, READ_TOKEN);
        assertEquals("确认值已随本次读取落盘：以刷新读值为准",
                Boolean.FALSE, SettingsFragment.resolveDisplayedValue(
                        null, confirmed, READ_TOKEN + 5L, Boolean.FALSE));
        assertNull("没有确认值时用刷新读值", SettingsFragment.resolveDisplayedValue(
                null, null, READ_TOKEN, null));
    }

    @Test
    public void signStepReadyOnlyForReportAfterLaunch() {
        long launchAt = 1_000_000L;
        assertEquals("本次拉起后的新回传 = 目标进程已初始化、接收器已注册",
                SettingsFragment.SIGN_STEP_READY,
                SettingsFragment.signBroadcastStep(launchAt + 1L, launchAt, 3_000L, 25_000L));
        assertEquals("旧会话的回传（拉起之前落盘）不能当就绪信号",
                SettingsFragment.SIGN_STEP_WAIT,
                SettingsFragment.signBroadcastStep(launchAt - 1L, launchAt, 3_000L, 25_000L));
        assertEquals("从未回传继续等待",
                SettingsFragment.SIGN_STEP_WAIT,
                SettingsFragment.signBroadcastStep(0L, launchAt, 3_000L, 25_000L));
    }

    @Test
    public void signStepTimeoutFiresBestEffortSend() {
        long launchAt = 1_000_000L;
        assertEquals("未超时继续等待",
                SettingsFragment.SIGN_STEP_WAIT,
                SettingsFragment.signBroadcastStep(0L, launchAt, 24_999L, 25_000L));
        assertEquals("超时兜底必须发送，不能永远不发",
                SettingsFragment.SIGN_STEP_TIMEOUT,
                SettingsFragment.signBroadcastStep(0L, launchAt, 25_000L, 25_000L));
        assertEquals("就绪判定优先于超时",
                SettingsFragment.SIGN_STEP_READY,
                SettingsFragment.signBroadcastStep(launchAt + 1L, launchAt, 25_000L, 25_000L));
    }

    @Test
    public void targetVersionKeepsLastKnownGoodOnQueryFailure() {
        assertEquals("查询成功覆盖缓存", "22.14.0.0",
                SettingsFragment.targetVersionAfterQuery("22.14.0.0", "22.13.1.1"));
        assertEquals("查询失败沿用最近一次成功值，不缓存 null",
                "22.13.1.1", SettingsFragment.targetVersionAfterQuery(null, "22.13.1.1"));
        assertNull("从未成功过保持 null（状态卡按未知展示）",
                SettingsFragment.targetVersionAfterQuery(null, null));
    }
}
