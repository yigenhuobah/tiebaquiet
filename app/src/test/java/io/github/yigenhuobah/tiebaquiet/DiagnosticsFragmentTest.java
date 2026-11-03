package io.github.yigenhuobah.tiebaquiet;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * DiagnosticsFragment 诊断行判定的纯逻辑：新鲜度门控。
 *
 * <p>不构造 Fragment / SharedPreferences，只重放“单功能回传是否算有数据”的判定：
 * 回传数据超过 TTL（{@link StatusProvider#REPORT_TTL_MS}）后一律按无数据处理，
 * 与主页失败横幅（StatusProvider.failedFeatureCount 过期返回 0）的语义一致，
 * 避免目标进程停报后旧会话的 registered/hit/skip 仍被当作当前状态展示。
 */
public class DiagnosticsFragmentTest {

    @Test
    public void freshReportWithAnyValueCountsAsData() {
        assertTrue(DiagnosticsFragment.reportReported("anchor_a", "", 0L, true));
        assertTrue("仅 skip 也算有数据",
                DiagnosticsFragment.reportReported("", "锚点缺失 x", 0L, true));
        assertTrue("仅 hit 也算有数据",
                DiagnosticsFragment.reportReported(null, null, 3L, true));
        assertTrue("仅 hook 体错误也算有数据",
                DiagnosticsFragment.reportReported(null, null, 0L, 2L, true));
    }

    @Test
    public void freshReportWithoutAnyValueIsNoData() {
        assertFalse("从未回传不算有数据",
                DiagnosticsFragment.reportReported(null, null, 0L, true));
        assertFalse("空串不算有数据",
                DiagnosticsFragment.reportReported("", "", 0L, true));
        assertFalse("err=0 不算有数据",
                DiagnosticsFragment.reportReported(null, null, 0L, 0L, true));
    }

    @Test
    public void staleReportIsNeverTreatedAsCurrentData() {
        assertFalse("过期回传：有 reg 也不算有数据",
                DiagnosticsFragment.reportReported("anchor_a", "", 0L, false));
        assertFalse("过期回传：有 skip 也不算有数据",
                DiagnosticsFragment.reportReported("", "锚点缺失 x", 0L, false));
        assertFalse("过期回传：有 hit 也不算有数据",
                DiagnosticsFragment.reportReported(null, null, 9L, false));
        assertFalse("过期回传：有 err 也不算有数据",
                DiagnosticsFragment.reportReported(null, null, 0L, 4L, false));
    }
}
