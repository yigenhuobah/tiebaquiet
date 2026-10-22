package io.github.yigenhuobah.tiebaquiet.feature;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.github.yigenhuobah.tiebaquiet.HookEnv;

/**
 * 信息流卡片过滤谓词。
 * 保守策略：只清广告/推广/活动卡；普通 UGC、视频、投票等一律保留。
 * 过滤只发生在展示层（列表数据），不改动埋点上报。
 */
public final class CardFilter {

    private static final String[] LEGO_AD_KEYS = {
            "lego_ad_card", "lego_ad_data", "lego_ad_wrapper"
    };

    private static final String[] LIVE_THREAD_KEYS = {
            "thread_is_live_thread",
            "thread_is_live_interview_type",
            "thread_is_interview_live_style",
            "thread_is_ala_live_user",
    };

    private CardFilter() {
    }

    public static boolean isAdCard(HookEnv env, Object item) {
        if (item == null) {
            return false;
        }
        Class<?> nt9 = env.anchors.clazz("nt9");
        if (nt9 == null || !nt9.isInstance(item)) {
            return false;
        }

        Object data = invoke(env.anchors.method("nt9_d"), item);
        if (data == null) {
            return false;
        }

        Class<?> advert = env.anchors.clazz("advert_app_info");
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

        Class<?> thread = env.anchors.clazz("thread_data");
        if (thread != null && thread.isInstance(data)) {
            Object adUrl = invoke(env.anchors.method("thread_ad_url"), data);
            if (adUrl instanceof String && !((String) adUrl).isEmpty()) {
                return true;
            }
            if (Boolean.TRUE.equals(invoke(env.anchors.method("thread_ad_subscript"), data))) {
                return true;
            }
            if (Boolean.TRUE.equals(invoke(env.anchors.method("thread_is_act"), data))) {
                return true;
            }
        }
        return false;
    }

    public static String typeKey(HookEnv env, Object item) {
        if (item == null) {
            return null;
        }
        Class<?> nt9 = env.anchors.clazz("nt9");
        if (nt9 == null || !nt9.isInstance(item)) {
            return null;
        }
        Object value = invoke(env.anchors.method("nt9_b"), item);
        return value instanceof String ? (String) value : null;
    }

    public static Object dataOf(HookEnv env, Object item) {
        if (item == null) {
            return null;
        }
        Class<?> nt9 = env.anchors.clazz("nt9");
        if (nt9 == null || !nt9.isInstance(item)) {
            return null;
        }
        return invoke(env.anchors.method("nt9_d"), item);
    }

    public static boolean isLiveCard(HookEnv env, Object item) {
        if (item == null) {
            return false;
        }
        Class<?> nt9 = env.anchors.clazz("nt9");
        if (nt9 == null || !nt9.isInstance(item)) {
            return false;
        }
        // 卡片类型键里带 live 的直接判定为直播卡（稳定字符串，跨版本可用）。
        String typeKey = typeKey(env, item);
        if (typeKey != null && typeKey.toLowerCase(Locale.ROOT).contains("live")) {
            return true;
        }
        Object data = invoke(env.anchors.method("nt9_d"), item);
        if (data == null) {
            return false;
        }
        // 直播卡可能是 em8 包装的 ThreadData。
        Class<?> wrapper = env.anchors.clazz("live_thread_wrapper");
        if (wrapper != null && wrapper.isInstance(data)) {
            Object inner = invoke(env.anchors.method("em8_get_thread"), data);
            if (inner != null) {
                data = inner;
            }
        }
        Class<?> thread = env.anchors.clazz("thread_data");
        if (thread == null || !thread.isInstance(data)) {
            return false;
        }
        if (Boolean.TRUE.equals(invoke(env.anchors.method("thread_is_live"), data))) {
            return true;
        }
        if (Boolean.TRUE.equals(invoke(env.anchors.method("thread_is_interview_live"), data))) {
            return true;
        }
        for (String key : LIVE_THREAD_KEYS) {
            if (Boolean.TRUE.equals(invoke(env.anchors.method(key), data))) {
                return true;
            }
        }
        Object alaInfo = invoke(env.anchors.method("thread_ala_info"), data);
        if (alaInfo != null
                && Boolean.TRUE.equals(invoke(env.anchors.method("ala_info_yy_live"), alaInfo))) {
            return true;
        }
        Object isLive = invoke(env.anchors.method("thread_get_is_live"), data);
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
