package io.github.yigenhuobah.tiebaquiet;

import java.util.LinkedHashSet;
import java.util.Set;

import io.github.yigenhuobah.core.ReportProtocolCore;

/**
 * 诊断回传通道的模块适配器。
 *
 * <p>键白名单、取值长度、计数/文本合并、失败语义与新鲜度全部由 core 的
 * {@link ReportProtocolCore} 承担；本类只做两件事：
 * <ul>
 *   <li>在类初始化时用本模块 25 个 {@link Feature} 的稳定 key 配置 core，
 *       崩溃摘要使用既有 {@code crash0/1/2} 平铺键布局；</li>
 *   <li>保留模块既有的常量与静态方法名，供 StatusProvider / hooks.Report /
 *       诊断页无改动调用。</li>
 * </ul>
 *
 * <p>会话身份比较（{@link #isNewSession}）是模块侧协议，core 没有对应方法；
 * 按“core 缺方法只在模块内加适配 helper”的约束保留在这里。
 */
public final class ReportProtocol {

    public static final String PREFIX_REG = ReportProtocolCore.PREFIX_REG;
    public static final String PREFIX_HIT = ReportProtocolCore.PREFIX_HIT;
    /** hook 体异常计数（不含宿主 chain.proceed 异常）：err&gt;0 时该功能计入失败横幅。 */
    public static final String PREFIX_ERR = ReportProtocolCore.PREFIX_ERR;
    public static final String PREFIX_SKIP = ReportProtocolCore.PREFIX_SKIP;
    /** 主锚点缺失标记：crit=1 时该功能计入失败横幅。 */
    public static final String PREFIX_CRIT = ReportProtocolCore.PREFIX_CRIT;

    /** 回传快照的元数据：目标进程会话标识与落盘时间。 */
    public static final String KEY_META_SESSION = ReportProtocolCore.KEY_META_SESSION;
    public static final String KEY_META_UPDATED_AT = ReportProtocolCore.KEY_META_UPDATED_AT;

    /** 最近崩溃回传键（crash0 最新，crash2 最旧）：键值通道的固定键名。 */
    public static final String KEY_CRASH_0 = ReportProtocolCore.KEY_CRASH_0;
    public static final String KEY_CRASH_1 = ReportProtocolCore.KEY_CRASH_1;
    public static final String KEY_CRASH_2 = ReportProtocolCore.KEY_CRASH_2;

    public static final int MAX_KEY = ReportProtocolCore.MAX_KEY;
    public static final int MAX_VALUE = ReportProtocolCore.MAX_VALUE;

    /** 超过 10 分钟没有新回传就视为过期，失败计数按“未知”处理（0）。 */
    public static final long DEFAULT_TTL_MS = ReportProtocolCore.DEFAULT_TTL_MS;

    /** 未来时间戳容忍上限：updatedAt 领先 now 超过该值视为时钟回拨/异常，直接判过期。 */
    public static final long MAX_FUTURE_SKEW_MS = ReportProtocolCore.MAX_FUTURE_SKEW_MS;

    static {
        // 进程级单次配置：feature key × 5 个前缀 + meta + crash0/1/2。
        Set<String> featureKeys = new LinkedHashSet<>();
        for (Feature feature : FeatureRegistry.all()) {
            featureKeys.add(feature.key);
        }
        ReportProtocolCore.configure(featureKeys, ReportProtocolCore.CrashLayout.FLAT_KEYS);
    }

    private ReportProtocol() {
    }

    /** 只接受白名单键；非空、长度上限一并校验，其他一律拒绝。 */
    public static boolean validKey(String key) {
        return ReportProtocolCore.validKey(key);
    }

    /** 值只校验非 null 与长度上限；hit 的可解析性由调用方再校验。 */
    public static boolean isValidValue(String value) {
        return ReportProtocolCore.isValidValue(value);
    }

    /**
     * 命中计数合并：多进程各自回传本进程计数，历史值取最大，避免重复累加，
     * 也避免较大计数被较小值覆盖。负数（异常输入）按 0 处理。
     */
    public static long mergeHit(long previous, long next) {
        return ReportProtocolCore.mergeHit(previous, next);
    }

    /**
     * reg/skip/crit 文本合并：最新值无条件覆盖旧值，空串也覆盖。
     *
     * <p>目标进程每次启动都是新的 HookState：本次会话没有 skip 时必须发送空串，
     * 才能清掉上一次会话落盘的旧 skip；缺失不发送会让失败横幅永久误报。
     */
    public static String mergeText(String previous, String next) {
        return ReportProtocolCore.mergeText(previous, next);
    }

    /**
     * 已启用且有回传的功能是否明确失败：reg 为空、存在 critical 标记，
     * 或 hook 体已记录错误（err &gt; 0，不含宿主 proceed 异常）。
     *
     * <p>普通 skip 只用于诊断展示，不再计入失败：零安装规则下
     * “有注册标签 + 部分锚点缺失”属于部分可用，不应把整个功能判死；
     * 但 hook 体反复抛异常说明功能实际不可用，必须计入失败横幅。
     */
    public static boolean featureFailed(String reg, String skip, boolean criticalSkip, long hit) {
        return ReportProtocolCore.featureFailed(reg, skip, criticalSkip, hit);
    }

    /** 带 hook 体错误计数的失败判定（见 {@link #PREFIX_ERR}）。 */
    public static boolean featureFailed(String reg, String skip, boolean criticalSkip,
                                        long hit, long errors) {
        return ReportProtocolCore.featureFailed(reg, skip, criticalSkip, hit, errors);
    }

    /** crit 值固定为 "1"；其余（空串/缺失/旧数据）都按可选处理。 */
    public static boolean isCritical(String value) {
        return ReportProtocolCore.isCritical(value);
    }

    /**
     * 回传数据是否仍新鲜。updatedAt &lt;= 0 表示从未回传或旧版本没有时间戳；
     * updatedAt 领先本机时间超过 {@link #MAX_FUTURE_SKEW_MS}（设备时钟回拨/
     * 未来时间戳）同样视为过期——否则回拨后旧快照会因 updatedAt 落在“未来”
     * 而被长期当作新鲜数据；小幅偏差仍按新鲜处理，正常 TTL 逻辑保留。
     */
    public static boolean isFresh(long updatedAt, long now, long ttlMs) {
        return ReportProtocolCore.isFresh(updatedAt, now, ttlMs);
    }

    /**
     * 会话身份比较：session 字符串不同即新会话（上一会话数据必须重置），
     * 相同即同进程。旧格式（纯数字毫秒）同样按字符串比较，不做大小比较——
     * 设备时钟回拨会让新进程的毫秒值更小，按大小比较会把整批回写误判为旧会话丢弃。
     *
     * <p>旧版目标进程没有 session 字段（incoming 为空）：无从比较，按同会话兼容
     * 合并进当前数据，保证模块 App 与目标进程可各自升级；首个带 session 的
     * 回传仍会重置旧数据。
     *
     * <p><b>core 缺口</b>：{@link ReportProtocolCore} 没有会话身份比较方法，
     * 按约束不改 core，保留在模块内。
     */
    public static boolean isNewSession(String storedSession, String incomingSession) {
        if (incomingSession == null || incomingSession.isEmpty()) {
            return false;
        }
        return !incomingSession.equals(storedSession);
    }
}
