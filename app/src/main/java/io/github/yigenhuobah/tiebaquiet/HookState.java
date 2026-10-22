package io.github.yigenhuobah.tiebaquiet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 目标进程内的诊断状态：每个功能的注册标签、命中次数、跳过原因。
 *
 * <p>命中可能来自任意线程（含 binder 线程），全部使用并发容器。
 * 快照由 {@link io.github.yigenhuobah.tiebaquiet.hooks.Report} 定期回传，
 * 键使用 {@code reg./hit./skip.} 前缀，值截断到 256 字符。
 */
public final class HookState {

    private static final int MAX_VALUE = ReportProtocol.MAX_VALUE;

    private static final ConcurrentMap<Feature, State> STATES = new ConcurrentHashMap<>();

    private HookState() {
    }

    private static final class State {
        final Set<String> labels = ConcurrentHashMap.newKeySet();
        final AtomicLong hits = new AtomicLong();
        volatile String skip;

        void markSkip(String reason) {
            if (reason != null) {
                skip = reason;
            }
        }
    }

    private static State state(Feature feature) {
        State s = STATES.get(feature);
        if (s == null) {
            State created = new State();
            State previous = STATES.putIfAbsent(feature, created);
            s = previous != null ? previous : created;
        }
        return s;
    }

    public static void installed(Feature feature, String label) {
        state(feature).labels.add(label);
    }

    public static void markNotFound(Feature feature, String reason) {
        state(feature).markSkip(reason);
    }

    /** 命中计数 +1，返回累加后的次数（供日志采样）。 */
    public static long hit(Feature feature) {
        return state(feature).hits.incrementAndGet();
    }

    /** 快照只包含被触碰过的功能，按功能编号排序，便于测试与日志比对。 */
    public static List<Feature> touched() {
        List<Feature> out = new ArrayList<>(STATES.keySet());
        Collections.sort(out, new Comparator<Feature>() {
            @Override
            public int compare(Feature a, Feature b) {
                return a.code.compareTo(b.code);
            }
        });
        return out;
    }

    public static List<String> labels(Feature feature) {
        State s = STATES.get(feature);
        if (s == null) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>(s.labels);
        Collections.sort(out);
        return out;
    }

    public static long hitCount(Feature feature) {
        State s = STATES.get(feature);
        return s == null ? 0L : s.hits.get();
    }

    public static String skipReason(Feature feature) {
        State s = STATES.get(feature);
        return s == null ? null : s.skip;
    }

    /**
     * 打平为 {@code reg.<key> / hit.<key> / skip.<key>} 的 Map。
     * 没有注册标签、没有命中、没有 skip 的功能不会出现在快照里。
     */
    public static Map<String, String> snapshot() {
        Map<String, String> out = new TreeMap<>();
        for (Feature feature : touched()) {
            List<String> labels = labels(feature);
            if (!labels.isEmpty()) {
                out.put(ReportProtocol.PREFIX_REG + feature.key, truncate(join(labels)));
            }
            long hits = hitCount(feature);
            if (hits > 0) {
                out.put(ReportProtocol.PREFIX_HIT + feature.key, String.valueOf(hits));
            }
            String skip = skipReason(feature);
            if (skip != null) {
                out.put(ReportProtocol.PREFIX_SKIP + feature.key, truncate(skip));
            }
        }
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
}
