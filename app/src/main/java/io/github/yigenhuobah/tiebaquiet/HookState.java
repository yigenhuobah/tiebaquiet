package io.github.yigenhuobah.tiebaquiet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import io.github.yigenhuobah.core.HookStateCore;

/**
 * 目标进程内诊断状态的模块适配器。
 *
 * <p>账本语义（命中/错误计数、去重后的 skip 原因列表、critical 标记、挂载点
 * 安装成功后撤销失败）全部由 core 的 {@link HookStateCore} 承担；本类只负责
 * {@link Feature} → 稳定 key 的映射，并保持模块既有的静态 API 不变，
 * 这样 Feature/TiebaQuietModule/hooks 的调用点无需改动。
 *
 * <p>命中可能来自任意线程（含 binder 线程）：{@link HookStateCore} 内部使用
 * 并发容器与原子计数，本适配器不再自行加锁。
 */
public final class HookState {

    private static final int MAX_VALUE = ReportProtocol.MAX_VALUE;

    /** 进程级账本；所有 Feature 共用一个实例，按 {@code feature.key} 分区。 */
    private static final HookStateCore CORE = new HookStateCore();

    private static final Comparator<Feature> BY_CODE = new Comparator<Feature>() {
        @Override
        public int compare(Feature a, Feature b) {
            return a.code.compareTo(b.code);
        }
    };

    private HookState() {
    }

    /**
     * 功能开始安装。创建空账本条目，确保“已启用但安装阶段完全没有 touch”的功能
     * 也会进入快照并发送空 reg/skip，从而清掉上一会话的旧 skip。
     */
    public static void enable(Feature feature) {
        if (feature != null) {
            CORE.enable(feature.key);
        }
    }

    public static void installed(Feature feature, String label) {
        if (feature != null) {
            CORE.installed(feature.key, label);
        }
    }

    /**
     * 普通锚点缺失：只记录 skip 供诊断页展示，不计入失败横幅。
     */
    public static void markNotFound(Feature feature, String reason) {
        if (feature != null) {
            CORE.markNotFound(feature.key, reason);
        }
    }

    /**
     * 主锚点缺失（该功能零成功安装锚点）：除记录 skip 外再打 critical 标记，
     * 让 {@link ReportProtocol#featureFailed} 把该功能计入失败横幅。
     */
    public static void markNotFoundCritical(Feature feature, String reason) {
        if (feature != null) {
            CORE.markNotFoundCritical(feature.key, reason);
        }
    }

    /** 本会话是否出现过主锚点缺失。 */
    public static boolean hasCriticalSkip(Feature feature) {
        return feature != null && CORE.hasCriticalSkip(feature.key);
    }

    /**
     * 当前会话成功安装的锚点数量（去重后的标签数，保持旧实现语义）。
     * core 的 {@code installedCount} 统计调用次数；这里继续用 labels 的数量，
     * 避免同一个挂载点被重复安装时计数膨胀。
     */
    public static int installedCount(Feature feature) {
        return feature == null ? 0 : CORE.labels(feature.key).size();
    }

    /** 命中计数 +1，返回累加后的次数（供日志采样）。 */
    public static long hit(Feature feature) {
        return feature == null ? 0L : CORE.hit(feature.key);
    }

    /**
     * hook 体异常计数 +1，返回累加后的次数（供日志采样）。
     * 与 hit 分开统计：抛异常的调用不算命中，但仍要让诊断页/横幅看到失败。
     */
    public static long error(Feature feature) {
        return feature == null ? 0L : CORE.error(feature.key);
    }

    /** 快照只包含被触碰过的功能，按功能编号排序，便于测试与日志比对。 */
    public static List<Feature> touched() {
        List<Feature> out = new ArrayList<>();
        for (String key : CORE.touched()) {
            Feature feature = FeatureRegistry.byKey(key);
            if (feature != null) {
                out.add(feature);
            }
        }
        Collections.sort(out, BY_CODE);
        return out;
    }

    public static List<String> labels(Feature feature) {
        return feature == null ? Collections.<String>emptyList() : CORE.labels(feature.key);
    }

    public static long hitCount(Feature feature) {
        return feature == null ? 0L : CORE.hits(feature.key);
    }

    public static long errorCount(Feature feature) {
        return feature == null ? 0L : CORE.errors(feature.key);
    }

    /** 最后一条 skip 原因；没有原因返回 {@code null}。 */
    public static String skipReason(Feature feature) {
        return feature == null ? null : CORE.skipReason(feature.key);
    }

    /**
     * 诊断页展示用 skip 摘要：首个 critical 原因 + 最后一条原因（相同时只留一条），
     * 由 core 的去重原因列表生成。保证“主锚点缺失”不会因为后面又记录了一条
     * 可选 skip 而从诊断展示里消失。
     *
     * <p><b>不进入回传路径</b>：回传沿用旧协议，只发 {@link #skipReason(Feature)}
     * （最后一条原因），见 {@code hooks.Report#snapshot()}。
     */
    public static String skipSummary(Feature feature) {
        return feature == null ? null : CORE.skipSummary(feature.key);
    }

    /**
     * 打平为 {@code reg.<key> / hit.<key> / err.<key> / skip.<key>} 的 Map。
     * 没有注册标签、没有命中、没有错误、没有 skip 的功能不会出现在快照里。
     *
     * <p><b>仅供单测使用</b>（HookStateTest），禁止接入上报路径：生产上报必须走
     * {@code hooks.Report#snapshot()}，后者对每个 touched 功能无条件发送
     * reg/skip/crit（缺失填空串）以清掉上一会话遗留的 skip/crit，并附带
     * meta.session / meta.updated_at。本方法缺空值与 crit./meta 键，一旦接入上报
     * 会破坏“空串清旧 skip”协议并丢失 critical 标记；收窄为包私有以保证
     * hooks 包（及任何生产代码）无法调用，仅同包测试可用。
     */
    static Map<String, String> snapshot() {
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
            long errors = errorCount(feature);
            if (errors > 0) {
                out.put(ReportProtocol.PREFIX_ERR + feature.key, String.valueOf(errors));
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
