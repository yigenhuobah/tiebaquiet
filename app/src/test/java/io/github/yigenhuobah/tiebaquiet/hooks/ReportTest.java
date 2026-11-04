package io.github.yigenhuobah.tiebaquiet.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
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
 * &gt; 0 时出现；crash0/1/2（0 最新）始终存在，无崩溃为空串；每次发送带
 * meta.session / meta.updated_at。
 *
 * <p>节流：内容变化立即发；内容不变时按 5 分钟心跳重发。只有回传成功（provider
 * 非 null，即 commit 成功）才更新 sLast/sLastSentAt；provider 失败时调用方保持旧值，
 * 同一快照仍会被判定为待发送。比较时必须剔除 meta 键，否则时间戳每轮变化都会
 * 让心跳失效。这里用纯函数覆盖该契约，不依赖 Android / Robolectric。
 *
 * <p>HookState 与崩溃环都是进程级静态状态：每个快照用例使用不同 Feature，
 * 崩溃环用例各自先写入再读取，避免互相污染。
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

    /** hook 体异常计数：err 只在 &gt; 0 时回传，且与 hit 分开统计。 */
    @Test
    public void snapshotCarriesHookBodyErrorCount() {
        Feature f = feature("personalized_ad_off");
        long baseErrors = HookState.errorCount(f);
        long baseHits = HookState.hitCount(f);
        HookState.error(f);
        HookState.error(f);

        Map<String, String> snap = Report.snapshot();

        assertEquals("2 次 hook 体异常必须回传累计 err", String.valueOf(baseErrors + 2L),
                snap.get(ReportProtocol.PREFIX_ERR + f.key));
        assertEquals("异常不能算命中", baseHits, HookState.hitCount(f));
    }

    @Test
    public void snapshotCarriesStableSessionAndUpdatedAt() {
        Map<String, String> first = Report.snapshot();
        String session = first.get(ReportProtocol.KEY_META_SESSION);
        assertNotNull("必须回传 meta.session", session);
        assertTrue("session 必须是 s<millis>-<8位hex> 格式，带进程随机后缀",
                session.matches("s\\d+-[0-9a-f]{8}"));
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

    /**
     * 安装器抛异常（TiebaQuietModule 的 catch 分支）必须标 critical：
     * reg 为空 + crit=1 时 featureFailed 为 true，失败横幅不再漏报
     * “整个功能崩了”，诊断页也不会只剩一个“—”。
     */
    @Test
    public void installerCrashIsVisibleAsCriticalFailure() {
        Feature f = feature("ad_request_block");
        HookState.enable(f);
        // 与 TiebaQuietModule catch 分支一致：markNotFoundCritical(feature, "安装异常 …")。
        HookState.markNotFoundCritical(f, "安装异常 OutOfMemoryError");

        Map<String, String> snap = Report.snapshot();

        assertEquals("崩溃功能的 reg 仍为空", "", snap.get(ReportProtocol.PREFIX_REG + f.key));
        assertEquals("安装异常 OutOfMemoryError", snap.get(ReportProtocol.PREFIX_SKIP + f.key));
        assertEquals("崩溃必须带 critical 标记", "1",
                snap.get(ReportProtocol.PREFIX_CRIT + f.key));
        assertTrue("reg 为空也必须计入失败横幅",
                ReportProtocol.featureFailed(
                        snap.get(ReportProtocol.PREFIX_REG + f.key),
                        snap.get(ReportProtocol.PREFIX_SKIP + f.key),
                        ReportProtocol.isCritical(snap.get(ReportProtocol.PREFIX_CRIT + f.key)),
                        HookState.hitCount(f)));
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

    /** crash0/1/2 必须始终出现在快照里：无崩溃时为空串（覆盖清掉旧会话残留）。 */
    @Test
    public void snapshotAlwaysCarriesThreeCrashKeys() {
        Map<String, String> snap = Report.snapshot();

        assertTrue("crash0 必须回传", snap.containsKey(ReportProtocol.KEY_CRASH_0));
        assertTrue("crash1 必须回传", snap.containsKey(ReportProtocol.KEY_CRASH_1));
        assertTrue("crash2 必须回传", snap.containsKey(ReportProtocol.KEY_CRASH_2));
        for (int i = 0; i < 3; i++) {
            String value = snap.get("crash" + i);
            assertNotNull(value);
            assertTrue("crash 值必须通过回传长度校验", ReportProtocol.isValidValue(value));
        }
    }

    /** 崩溃环：最新在前、最多 3 条，新的顶掉最旧的。 */
    @Test
    public void crashRingKeepsLatestThreeNewestFirst() {
        Report.recordCrash(new Thread("tb-crash-t1"), new RuntimeException("one"));
        Report.recordCrash(new Thread("tb-crash-t2"), new RuntimeException("two"));
        Report.recordCrash(new Thread("tb-crash-t3"), new RuntimeException("three"));
        Report.recordCrash(new Thread("tb-crash-t4"), new RuntimeException("four"));

        Map<String, String> snap = Report.snapshot();

        assertTrue("crash0 是最新一条",
                snap.get(ReportProtocol.KEY_CRASH_0).contains("|tb-crash-t4|"));
        assertTrue(snap.get(ReportProtocol.KEY_CRASH_1).contains("|tb-crash-t3|"));
        assertTrue("第 4 条新崩溃顶掉了第 1 条",
                !snap.get(ReportProtocol.KEY_CRASH_2).contains("|tb-crash-t1|"));
        assertTrue(snap.get(ReportProtocol.KEY_CRASH_2).contains("|tb-crash-t2|"));
    }

    /** 崩溃记录格式：时间（epoch 毫秒）|线程名|异常类名|栈前 2 帧。 */
    @Test
    public void crashEntryFormatCarriesTimeThreadClassAndTwoFrames() {
        RuntimeException boom = new RuntimeException("format");
        // 换成受控短栈帧：JUnit 反射栈太长会被 240 截断，无法逐段断言。
        boom.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("com.a.B", "d", "B.java", 1),
                new StackTraceElement("com.e.F", "h", "F.java", 2)});
        Report.recordCrash(new Thread("tb-crash-fmt"), boom);

        String crash0 = Report.snapshot().get(ReportProtocol.KEY_CRASH_0);
        String[] parts = crash0.split("\\|", -1);

        assertEquals("时间|线程|异常类名|帧1|帧2 共 5 段", 5, parts.length);
        long ts = Long.parseLong(parts[0]);
        assertTrue("首段必须是当前毫秒时间",
                Math.abs(System.currentTimeMillis() - ts) < 60_000L);
        assertEquals("tb-crash-fmt", parts[1]);
        assertEquals("java.lang.RuntimeException", parts[2]);
        assertEquals("栈前 2 帧按 getStackTrace() 顺序拼接",
                "com.a.B.d(B.java:1)|com.e.F.h(F.java:2)", parts[3] + "|" + parts[4]);
    }

    /** 单条截断 ≤240（MAX_VALUE=256 之内）：超长线程名只保留前 240 个字符。 */
    @Test
    public void crashEntryTruncatedTo240Chars() {
        Report.recordCrash(new Thread("x".repeat(300)), new RuntimeException("trunc"));

        String crash0 = Report.snapshot().get(ReportProtocol.KEY_CRASH_0);

        assertEquals("单条崩溃必须截断到 240 字符", 240, crash0.length());
        assertTrue("截断结果必须通过回传值长度校验", ReportProtocol.isValidValue(crash0));
    }

    /** 防御：null 线程 / null 异常不得抛出（UEH 内任何异常都会破坏原有处理链）。 */
    @Test
    public void recordCrashToleratesNullThreadAndThrowable() {
        Report.recordCrash(null, null);

        String crash0 = Report.snapshot().get(ReportProtocol.KEY_CRASH_0);
        assertNotNull("null 输入也要落一条占位记录", crash0);
        assertTrue("线程名/异常类名缺失时以 ? 占位", crash0.endsWith("|?"));
    }

    /**
     * UEH 包装：幂等（static boolean 只包一次）、先记录崩溃再透传 previous；
     * previous 的异常处理行为不被改变（透传后原样交回）。
     */
    @Test
    public void crashHandlerWrapsPreviousOnceAndForwards() {
        Thread.UncaughtExceptionHandler original = Thread.getDefaultUncaughtExceptionHandler();
        try {
            List<Throwable> forwarded = new ArrayList<>();
            Thread.UncaughtExceptionHandler previous = (t, e) -> forwarded.add(e);
            Thread.setDefaultUncaughtExceptionHandler(previous);

            Report.installCrashHandler();
            Thread.UncaughtExceptionHandler wrapped = Thread.getDefaultUncaughtExceptionHandler();
            assertNotNull("必须替换默认 UEH", wrapped);
            assertNotSame("默认 UEH 必须被包装", previous, wrapped);

            Report.installCrashHandler();
            assertSame("静态 boolean 必须保证只包一次", wrapped,
                    Thread.getDefaultUncaughtExceptionHandler());

            RuntimeException boom = new RuntimeException("wrapped");
            wrapped.uncaughtException(Thread.currentThread(), boom);

            assertEquals("previous 必须被透传", 1, forwarded.size());
            assertSame("透传的必须是原始异常", boom, forwarded.get(0));
            assertTrue("崩溃必须先落进快照",
                    Report.snapshot().get(ReportProtocol.KEY_CRASH_0)
                            .contains("java.lang.RuntimeException"));
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original);
        }
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
        // 心跳到期后重发同一快照：provider 返回 null（不可达 / commit 失败）视为失败，
        // 调用方必须保持 last / lastSentAt 为上一次成功值，下一轮同一快照仍待发送。
        Map<String, String> snapshot = batch("hit.splash_no_ad", "1");
        long lastSuccessAt = 1_000_000L;
        long failedAt = lastSuccessAt + Report.HEARTBEAT_MS;

        assertTrue("心跳到期必须触发本次回传",
                Report.shouldSend(snapshot, snapshot, failedAt, lastSuccessAt));

        // 失败路径：sLast / sLastSentAt 不更新（Report.flush 的调用方契约），
        // 一个发送周期（3 秒）后的下一轮必须再次判定待发送。
        long nextRound = failedAt + 3_000L;
        assertTrue("provider 失败后同一快照必须仍待发送",
                Report.shouldSend(snapshot, snapshot, nextRound, lastSuccessAt));

        // 回归信号：若失败被误当成成功（lastSentAt 被推进到 failedAt），
        // 心跳窗口未到就不会重发，同一快照会静默卡住整个心跳周期。
        assertFalse("失败不得推进 lastSentAt，否则重试会被心跳吞掉",
                Report.shouldSend(snapshot, snapshot, nextRound, failedAt));
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

    /**
     * 回传 skip 沿用旧协议：只发最后一条原因，不合并 critical 摘要。critical 原因
     * 之后又记录可选 skip 时，skip 键必须是最后一条；{@link HookState#skipSummary}
     * 仅用于诊断页展示，不得影响回传。
     */
    @Test
    public void snapshotSkipSendsLatestReasonOnly() {
        Feature f = feature("keep_alive_block");
        HookState.markNotFoundCritical(f, "无可用锚点");
        HookState.markNotFound(f, "可选锚点缺失");

        Map<String, String> snap = Report.snapshot();

        assertEquals("回传只发最后一条 skip 原因",
                "可选锚点缺失", snap.get(ReportProtocol.PREFIX_SKIP + f.key));
        assertEquals("诊断摘要仍保留 critical 原因",
                "无可用锚点 | 可选锚点缺失", HookState.skipSummary(f));
        assertEquals("1", snap.get(ReportProtocol.PREFIX_CRIT + f.key));
    }

    /**
     * ALL-CRASH-01：崩溃路径的同步 flush 在没有 provider（JVM 无 Android
     * Context）时必须 fail-safe 返回，不抛异常；刚记录的崩溃仍留在快照里。
     */
    @Test
    public void flushNowIsFailSafeWithoutProvider() {
        Report.recordCrash(new Thread("tb-flush-now"), new RuntimeException("flush-now"));

        Report.flushNow();

        assertTrue("flushNow 失败不得丢崩溃记录",
                Report.snapshot().get(ReportProtocol.KEY_CRASH_0)
                        .contains("|tb-flush-now|"));
    }
}
