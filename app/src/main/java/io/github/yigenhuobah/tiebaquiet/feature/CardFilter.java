package io.github.yigenhuobah.tiebaquiet.feature;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.yigenhuobah.tiebaquiet.anchor.AnchorKeys;
import io.github.yigenhuobah.tiebaquiet.HookEnv;

/**
 * 信息流卡片过滤谓词。
 * 保守策略：只清广告/推广/活动卡；普通 UGC、视频、投票等一律保留。
 * 过滤只发生在展示层（列表数据），不改动埋点上报。
 */
public final class CardFilter {

    private static final String[] LEGO_AD_KEYS = {
            AnchorKeys.LEGO_AD_CARD, AnchorKeys.LEGO_AD_DATA, AnchorKeys.LEGO_AD_WRAPPER
    };

    private static final String[] LIVE_THREAD_KEYS = {
            AnchorKeys.THREAD_IS_LIVE_THREAD,
            AnchorKeys.THREAD_IS_LIVE_INTERVIEW_TYPE,
            AnchorKeys.THREAD_IS_INTERVIEW_LIVE_STYLE,
            AnchorKeys.THREAD_IS_ALA_LIVE_USER,
    };

    private CardFilter() {
    }

    public static boolean isAdCard(HookEnv env, Object item) {
        if (item == null) {
            return false;
        }
        Class<?> nt9 = env.anchors.clazz(AnchorKeys.NT9);
        if (nt9 == null || !nt9.isInstance(item)) {
            return false;
        }

        Object data = invoke(env.anchors.method(AnchorKeys.NT9_D), item);
        if (data == null) {
            return false;
        }

        Class<?> advert = env.anchors.clazz(AnchorKeys.ADVERT_APP_INFO);
        if (advert != null && advert.isInstance(data)) {
            return true;
        }

        // lego/Nad 广告卡：AdCard 及其数据包装类（“立即查看”卡片）。
        for (String key : LEGO_AD_KEYS) {
            Class<?> cls = env.anchors.clazz(key);
            if (cls != null && cls.isInstance(data)) {
                return true;
            }
        }

        Class<?> thread = env.anchors.clazz(AnchorKeys.THREAD_DATA);
        if (thread != null && thread.isInstance(data)) {
            Object adUrl = invoke(env.anchors.method(AnchorKeys.THREAD_AD_URL), data);
            if (adUrl instanceof String && !((String) adUrl).isEmpty()) {
                return true;
            }
            if (Boolean.TRUE.equals(invoke(env.anchors.method(AnchorKeys.THREAD_AD_SUBSCRIPT), data))) {
                return true;
            }
            if (Boolean.TRUE.equals(invoke(env.anchors.method(AnchorKeys.THREAD_IS_ACT), data))) {
                return true;
            }
        }
        return false;
    }

    public static String typeKey(HookEnv env, Object item) {
        if (item == null) {
            return null;
        }
        Class<?> nt9 = env.anchors.clazz(AnchorKeys.NT9);
        if (nt9 == null || !nt9.isInstance(item)) {
            return null;
        }
        Object value = invoke(env.anchors.method(AnchorKeys.NT9_B), item);
        return value instanceof String ? (String) value : null;
    }

    /**
     * 直播类型键判定：与 {@link FeedCardSweeper#isLiveTypeKey} 共用同一份明确
     * token 白名单，避免 "delivery"/"olive" 这类含 live 子串的普通类型键
     * 被 {@link #filterLive} 误删或被清扫器误隐藏。抽成静态方法便于 JVM 单测。
     */
    static boolean isLiveTypeKey(String typeKey) {
        return FeedCardSweeper.isLiveTypeKey(typeKey);
    }

    public static boolean isLiveCard(HookEnv env, Object item) {
        if (item == null) {
            return false;
        }
        Class<?> nt9 = env.anchors.clazz(AnchorKeys.NT9);
        if (nt9 == null || !nt9.isInstance(item)) {
            return false;
        }
        if (isLiveTypeKey(typeKey(env, item))) {
            return true;
        }
        Object data = invoke(env.anchors.method(AnchorKeys.NT9_D), item);
        if (data == null) {
            return false;
        }
        // 直播卡可能是 em8 包装的 ThreadData。
        Class<?> wrapper = env.anchors.clazz(AnchorKeys.LIVE_THREAD_WRAPPER);
        if (wrapper != null && wrapper.isInstance(data)) {
            Object inner = invoke(env.anchors.method(AnchorKeys.EM8_GET_THREAD), data);
            if (inner != null) {
                data = inner;
            }
        }
        Class<?> thread = env.anchors.clazz(AnchorKeys.THREAD_DATA);
        if (thread == null || !thread.isInstance(data)) {
            return false;
        }
        if (Boolean.TRUE.equals(invoke(env.anchors.method(AnchorKeys.THREAD_IS_LIVE), data))) {
            return true;
        }
        if (Boolean.TRUE.equals(invoke(env.anchors.method(AnchorKeys.THREAD_IS_INTERVIEW_LIVE), data))) {
            return true;
        }
        for (String key : LIVE_THREAD_KEYS) {
            if (Boolean.TRUE.equals(invoke(env.anchors.method(key), data))) {
                return true;
            }
        }
        Object alaInfo = invoke(env.anchors.method(AnchorKeys.THREAD_ALA_INFO), data);
        if (alaInfo != null
                && Boolean.TRUE.equals(invoke(env.anchors.method(AnchorKeys.ALA_INFO_YY_LIVE), alaInfo))) {
            return true;
        }
        Object isLive = invoke(env.anchors.method(AnchorKeys.THREAD_GET_IS_LIVE), data);
        return isLive instanceof Integer && ((Integer) isLive) != 0;
    }

    public static List<?> filterLive(HookEnv env, List<?> list) {
        if (list == null) {
            return null;
        }
        List<Object> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!isLiveCard(env, item)) {
                out.add(item);
            }
        }
        return out.size() == list.size() ? list : out;
    }

    public static List<?> filter(HookEnv env, List<?> list) {
        if (list == null) {
            return null;
        }
        List<Object> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!isAdCard(env, item)) {
                out.add(item);
            }
        }
        return out.size() == list.size() ? list : out;
    }

    private static Object invoke(Method method, Object receiver) {
        try {
            return method == null ? null : method.invoke(receiver);
        } catch (Throwable t) {
            return null;
        }
    }
}
