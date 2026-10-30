package io.github.yigenhuobah.tiebaquiet;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 诊断回传通道的纯 Java 协议逻辑：键白名单、取值长度、命中计数合并、
 * 会话/新鲜度判定与失败语义。
 *
 * <p>不依赖任何 android.*，可直接在 JVM 单测（见 ReportProtocolTest）；
 * StatusProvider 只做 Binder / SharedPreferences 胶水，校验规则集中在这里。
 */
public final class ReportProtocol {

    public static final String PREFIX_REG = "reg.";
    public static final String PREFIX_HIT = "hit.";
    public static final String PREFIX_SKIP = "skip.";
    /** 主锚点缺失标记：crit=1 时该功能计入失败横幅。 */
    public static final String PREFIX_CRIT = "crit.";

    /** 回传快照的元数据：目标进程会话标识与落盘时间。 */
    public static final String KEY_META_SESSION = "meta.session";
    public static final String KEY_META_UPDATED_AT = "meta.updated_at";

    public static final int MAX_KEY = 128;
    public static final int MAX_VALUE = 256;

    /** 超过 10 分钟没有新回传就视为过期，失败计数按“未知”处理（0）。 */
    public static final long DEFAULT_TTL_MS = 10 * 60 * 1000L;

    /** 25 个 Feature × 4 个前缀 = 100 个功能键，加 2 个 meta 键共 102 个，构建后不可变。 */
    private static final Set<String> ALLOWED_KEYS = buildAllowedKeys();

    private ReportProtocol() {
    }

    private static Set<String> buildAllowedKeys() {
        Set<String> keys = new HashSet<>();
        for (Feature feature : FeatureRegistry.all()) {
            keys.add(PREFIX_REG + feature.key);
            keys.add(PREFIX_HIT + feature.key);
            keys.add(PREFIX_SKIP + feature.key);
            keys.add(PREFIX_CRIT + feature.key);
        }
        keys.add(KEY_META_SESSION);
        keys.add(KEY_META_UPDATED_AT);
        return Collections.unmodifiableSet(keys);
    }

    /** 只接受白名单键；非空、长度上限一并校验，其他一律拒绝。 */
    public static boolean validKey(String key) {
        return key != null && !key.isEmpty() && key.length() <= MAX_KEY
                && ALLOWED_KEYS.contains(key);
    }

    /** 值只校验非 null 与长度上限；hit 的可解析性由调用方再校验。 */
    public static boolean isValidValue(String value) {
        return value != null && value.length() <= MAX_VALUE;
    }

    /**
     * 命中计数合并：多进程各自回传本进程计数，历史值取最大，避免重复累加，
     * 也避免较大计数被较小值覆盖。负数（异常输入）按 0 处理。
     */
    public static long mergeHit(long previous, long next) {
        long p = previous < 0 ? 0 : previous;
        long n = next < 0 ? 0 : next;
        return Math.max(p, n);
    }

    /**
     * reg/skip/crit 文本合并：最新值无条件覆盖旧值，空串也覆盖。
     *
     * <p>目标进程每次启动都是新的 HookState：本次会话没有 skip 时必须发送空串，
     * 才能清掉上一次会话落盘的旧 skip；缺失不发送会让失败横幅永久误报。
     */
    public static String mergeText(String previous, String next) {
        return next;
    }

    /** 已启用功能是否有回传（reg/skip 非空、critical 标记或 hit &gt; 0）。 */
    private static boolean reported(String reg, String skip, boolean criticalSkip, long hit) {
        return !isEmpty(reg) || !isEmpty(skip) || criticalSkip || hit > 0L;
    }

    /**
     * 已启用且有回传的功能是否明确失败：reg 为空或存在 critical 标记。
     *
     * <p>普通 skip 只用于诊断展示，不再计入失败：零安装规则下
     * “有注册标签 + 部分锚点缺失”属于部分可用，不应把整个功能判死。
     */
    public static boolean featureFailed(String reg, String skip, boolean criticalSkip, long hit) {
        if (!reported(reg, skip, criticalSkip, hit)) {
            return false;
        }
        return isEmpty(reg) || criticalSkip;
    }

    /** crit 值固定为 "1"；其余（空串/缺失/旧数据）都按可选处理。 */
    public static boolean isCritical(String value) {
        return "1".equals(value);
    }

    /**
     * 回传数据是否仍新鲜。updatedAt &lt;= 0 表示从未回传或旧版本没有时间戳；
     * now &lt; updatedAt（设备时钟回拨）按新鲜处理，避免误伤。
     */
    public static boolean isFresh(long updatedAt, long now, long ttlMs) {
        if (updatedAt <= 0L || ttlMs <= 0L) {
            return false;
        }
        return now - updatedAt <= ttlMs;
    }

    /**
     * 迟到的旧会话写入：已存会话存在，而本次没有会话标识（旧版回传）
     * 或会话启动时间更早，整批必须丢弃，不能覆盖新会话的 reg/skip。
     */
    public static boolean isStaleSession(String storedSession, String incomingSession) {
        long stored = parseLong(storedSession);
        if (stored <= 0L) {
            return false;
        }
        long incoming = parseLong(incomingSession);
        return incoming <= 0L || incoming < stored;
    }

    /** 新会话：本次会话启动时间更晚（或首次带 session 回传），需要先清掉旧会话数据。 */
    public static boolean isNewSession(String storedSession, String incomingSession) {
        long incoming = parseLong(incomingSession);
        if (incoming <= 0L) {
            return false;
        }
        long stored = parseLong(storedSession);
        return stored <= 0L || incoming > stored;
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }

    private static long parseLong(String value) {
        if (value == null || value.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
