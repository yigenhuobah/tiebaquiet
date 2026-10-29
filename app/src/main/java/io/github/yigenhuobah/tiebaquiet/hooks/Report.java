package io.github.yigenhuobah.tiebaquiet.hooks;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import io.github.yigenhuobah.tiebaquiet.Feature;
import io.github.yigenhuobah.tiebaquiet.HookState;
import io.github.yigenhuobah.tiebaquiet.ReportProtocol;
import io.github.yigenhuobah.tiebaquiet.StatusProvider;

/**
 * 目标进程 → 模块 App 的诊断回传（registered / hit / skip / critical）。
 *
 * <p>hook 侧只能主动 call 进模块的 ContentProvider（remote prefs 在目标进程只读）。
 * Context 用 ActivityThread 反射拿；onPackageReady 之后 Application 很快可用，
 * 拿不到就等下一轮刷新。
 *
 * <p>节流：后台单线程每 3 秒把 HookState 快照打平上报一次，内容没变不发；
 * 但同一快照至少每 {@link #HEARTBEAT_MS} 重发一次，避免 provider 写失败或目标进程
 * 被杀后快照永久停在上一次“以为成功”的状态。provider 不可达或提交失败时退避 60 秒，
 * 不刷日志。只在主进程启动（由入口类保证）。
 *
 * <p>每次回传都带 {@code meta.session} 与 {@code meta.updated_at}：前者用于丢弃
 * 迟到的旧会话写入，后者用于 10 分钟 TTL 判定。内容去重时必须剔除 meta 键，
 * 否则每轮时间戳变化都会让快照“看起来变了”。
 */
public final class Report {

    private static final String TAG = "TiebaQuiet";
    private static final int MAX_VALUE = ReportProtocol.MAX_VALUE;
    private static final long INITIAL_DELAY_SECONDS = 2L;
    private static final long PERIOD_SECONDS = 3L;
    private static final long BACKOFF_MS = 60_000L;

    /** 本次目标进程会话标识：用启动时刻毫秒值，便于跨会话比较新旧。 */
    static final String SESSION = String.valueOf(System.currentTimeMillis());

    /** 内容未变化时的重发间隔（心跳）：5 分钟，必须小于 provider 的 10 分钟 TTL。 */
    static final long HEARTBEAT_MS = 5 * 60 * 1000L;

    private static volatile boolean sStarted;
    private static volatile Context sContext;
    private static volatile Map<String, String> sLast = new TreeMap<>();
    private static volatile long sLastSentAt;
    private static volatile long sNextTry;
    private static volatile int sErrors;

    private Report() {
    }

    public static synchronized void start() {
        if (sStarted) {
            return;
        }
        sStarted = true;
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
                new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "TiebaQuiet-Report");
                        t.setDaemon(true);
                        return t;
                    }
                });
        executor.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try {
                    flush();
                } catch (Throwable ignored) {
                }
            }
        }, INITIAL_DELAY_SECONDS, PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * 是否需要回传当前快照。纯函数，便于 JVM 单测（见 ReportTest）。
     *
     * <p>规则：剔除 meta 后内容为空不发；内容与上次成功回传不同立即发；内容相同则仅在
     * 距上次成功回传达到 {@link #HEARTBEAT_MS} 时重发（心跳）；从未成功回传过
     * （lastSentAt &lt;= 0）也要发。失败的回传不会更新 last/lastSentAt，因此下一轮
     * 仍会命中重发条件。
     *
     * <p>比较必须剔除 {@code meta.session} / {@code meta.updated_at}：时间戳每轮都在变，
     * 带着它比较会让每一轮都被判定为“内容变化”，心跳节流完全失效。
     */
    static boolean shouldSend(Map<String, String> batch, Map<String, String> last,
                              long now, long lastSentAt) {
        Map<String, String> content = contentOf(batch);
        if (content.isEmpty()) {
            return false;
        }
        if (last == null || !content.equals(contentOf(last))) {
            return true;
        }
        if (lastSentAt <= 0) {
            return true;
        }
        return now - lastSentAt >= HEARTBEAT_MS;
    }

    private static void flush() {
        Map<String, String> batch = snapshot();
        long now = System.currentTimeMillis();
        if (!shouldSend(batch, sLast, now, sLastSentAt)) {
            return;
        }
        if (now < sNextTry) {
            return;
        }
        Context context = context();
        if (context == null) {
            return;
        }
        List<String> keys = new ArrayList<>(batch.keySet());
        List<String> values = new ArrayList<>();
        for (String key : keys) {
            values.add(batch.get(key));
        }
        Bundle extras = new Bundle();
        extras.putStringArray("keys", keys.toArray(new String[0]));
        extras.putStringArray("values", values.toArray(new String[0]));
        try {
            Bundle result = context.getContentResolver().call(
                    Uri.parse("content://" + StatusProvider.AUTHORITY),
                    "report", null, extras);
            if (result != null) {
                // provider 已同步 commit 成功：此时才把业务内容（不含 meta）记为已发送。
                sLast = contentOf(batch);
                sLastSentAt = now;
                return;
            }
            // call() 返回 null 视为 provider 暂不可达或落盘失败：退避 60 秒，不更新 sLast。
            sNextTry = System.currentTimeMillis() + BACKOFF_MS;
            if (sErrors++ < 3) {
                Log.w(TAG, "report provider returned null");
            }
        } catch (Throwable t) {
            // 模块 App 未启动、被系统拦截等情况：一分钟后再试，别每 3 秒刷日志。
            sNextTry = System.currentTimeMillis() + BACKOFF_MS;
            if (sErrors++ < 3) {
                Log.w(TAG, "report provider unavailable", t);
            }
        }
    }

    /**
     * HookState 快照 → 平铺的 key/value（key 用 reg./hit./skip./crit. 前缀，
     * value 截断到 256），并附带 meta.session / meta.updated_at。
     *
     * <p>reg / skip / crit 对每个 touched Feature 都必须发送，缺失时发空串：
     * StatusProvider 用最新值覆盖旧值，只有空串才能清掉上一次目标进程会话遗留的
     * skip/crit。hit 仍只在 &gt; 0 时发送，由 provider 取会话内最大值。
     */
    static Map<String, String> snapshot() {
        Map<String, String> out = new TreeMap<>();
        for (Feature feature : HookState.touched()) {
            out.put(ReportProtocol.PREFIX_REG + feature.key,
                    truncate(join(HookState.labels(feature))));
            long hits = HookState.hitCount(feature);
            if (hits > 0) {
                out.put(ReportProtocol.PREFIX_HIT + feature.key, String.valueOf(hits));
            }
            out.put(ReportProtocol.PREFIX_SKIP + feature.key,
                    truncate(HookState.skipReason(feature)));
            out.put(ReportProtocol.PREFIX_CRIT + feature.key,
                    HookState.hasCriticalSkip(feature) ? "1" : "");
        }
        out.put(ReportProtocol.KEY_META_SESSION, SESSION);
        // 每次发送都刷新，心跳才能把 TTL 续上。
        out.put(ReportProtocol.KEY_META_UPDATED_AT, String.valueOf(System.currentTimeMillis()));
        return out;
    }

    /** 去掉会话/时间戳后的业务内容，用于判断“内容是否真的变化”。 */
    static Map<String, String> contentOf(Map<String, String> batch) {
        Map<String, String> out = new TreeMap<>();
        if (batch == null || batch.isEmpty()) {
            return out;
        }
        out.putAll(batch);
        out.remove(ReportProtocol.KEY_META_SESSION);
        out.remove(ReportProtocol.KEY_META_UPDATED_AT);
        return out;
    }

    private static String join(List<String> labels) {
        StringBuilder sb = new StringBuilder();
        for (String label : labels) {
            if (sb.length() > 0) {
                sb.append('|');
            }
            sb.append(label);
        }
        return sb.toString();
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= MAX_VALUE ? value : value.substring(0, MAX_VALUE);
    }

    private static Context context() {
        Context cached = sContext;
        if (cached != null) {
            return cached;
        }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) {
                sContext = (Context) app;
                return sContext;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
