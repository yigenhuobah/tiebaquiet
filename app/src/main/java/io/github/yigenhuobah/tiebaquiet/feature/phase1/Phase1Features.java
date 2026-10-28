package io.github.yigenhuobah.tiebaquiet.feature.phase1;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.yigenhuobah.tiebaquiet.Feature;
import io.github.yigenhuobah.tiebaquiet.FeatureRegistry;
import io.github.yigenhuobah.tiebaquiet.HookEnv;
import io.github.yigenhuobah.tiebaquiet.feature.CardFilter;
import io.github.yigenhuobah.tiebaquiet.feature.FeedCardSweeper;

/**
 * 第一批 14 个功能的安装器。
 *
 * <p>每个方法对应原 Kotlin 功能对象的 {@code install(env)}，保留锚点 key、参数索引、
 * {@code chain.proceed()} 次数与反射调用。安装失败只返回 false，不影响其它功能。
 */
public final class Phase1Features {

    /** 个人页清理脚本，与 Kotlin 版 trimIndent 后的内容逐字一致。 */
    private static final String PROFILE_JS =
            "(function(){\n"
            + "  function hide(){\n"
            + "    var sels=['.vip-banner','.activity-area','.xiaoman-wallet','.game-zone',"
            + "'#banner-swiper','.banner-swiper'];\n"
            + "    for(var i=0;i<sels.length;i++){\n"
            + "      var els=document.querySelectorAll(sels[i]);\n"
            + "      for(var j=0;j<els.length;j++){els[j].style.display='none';}\n"
            + "    }\n"
            + "  }\n"
            + "  hide();\n"
            + "  if(!window.__tq_obs){\n"
            + "    window.__tq_obs=new MutationObserver(hide);\n"
            + "    window.__tq_obs.observe(document.body,{childList:true,subtree:true});\n"
            + "  }\n"
            + "})();";

    private static final String[] TAB_RED_DOT_KEYS = {
            "tab_add_tip", "tab_add_tip_2", "tab_add_tip_3", "tab_add_tip_4", "tab_add_tip_wrap",
            "msg_red_dot_refresh", "msg_red_dot_refresh_opt", "msg_red_dot_refresh_str",
    };

    private static final String[] LIVE_ITEM_KEYS = {
            "live_item_16_set_data", "live_item_45_set_data",
    };

    private Phase1Features() {
    }

    /** 开屏广告：走官方“会员无开屏”路径。 */
    public static boolean installSplashNoAd(HookEnv env) {
        Feature f = FeatureRegistry.byKey("splash_no_ad");
        return f.replace(env, env.anchors.method("splash_forbid_b"),
                "splash_forbid_b", Boolean.TRUE);
    }

    /** 营销/活动/签到弹窗总闸。 */
    public static boolean installYunDialog(HookEnv env) {
        Feature f = FeatureRegistry.byKey("yun_dialog");
        return f.replace(env, env.anchors.method("yun_dialog_can_show"),
                "yun_dialog_can_show", Boolean.FALSE);
    }

    /** 首页/帖子页悬浮广告。 */
    public static boolean installFloatAd(HookEnv env) {
        Feature f = FeatureRegistry.byKey("float_ad");
        boolean home = f.replace(env, env.anchors.method("home_ad_float"),
                "home_ad_float", null);
        boolean postBar = f.replace(env, env.anchors.method("pb_ad_float"),
                "pb_ad_float", null);
        return home || postBar;
    }

    /** 信息流广告/推广卡过滤（全列表统一入口）。 */
    public static boolean installFeedAdFilter(HookEnv env) {
        final Feature f = FeatureRegistry.byKey("feed_ad_filter");
        Method method = env.anchors.method("template_set_list");
        return f.hook(env, method, "template_set_list", new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                List<Object> args = chain.getArgs();
                Object first = args.size() > 0 ? args.get(0) : null;
                List<?> original = first instanceof List ? (List<?>) first : null;
                List<?> filtered = CardFilter.filter(env, original);
                Object[] out = args.toArray();
                if (filtered != null && filtered != original) {
                    out[0] = filtered;
                }
                return chain.proceed(out);
            }
        });
    }

    /** 底部 Tab 红点。 */
    public static boolean installTabRedDot(HookEnv env) {
        Feature f = FeatureRegistry.byKey("tab_red_dot");
        boolean any = false;
        for (String key : TAB_RED_DOT_KEYS) {
            if (f.skip(env, env.anchors.method(key), key)) {
                any = true;
            }
        }
        return any;
    }

    /** 推荐吧卡。 */
    public static boolean installNoRecForum(HookEnv env) {
        Feature f = FeatureRegistry.byKey("rec_forum");
        return f.replace(env, env.anchors.method("rec_forum_strategy_h"),
                "rec_forum_strategy_h", Boolean.FALSE);
    }

    /** 更新弹窗。 */
    public static boolean installUpdateDialog(HookEnv env) {
        Feature f = FeatureRegistry.byKey("update_dialog");
        return f.skip(env, env.anchors.method("upgrade_pop"), "upgrade_pop");
    }

    /** 彩蛋广告。 */
    public static boolean installEasterEgg(HookEnv env) {
        Feature f = FeatureRegistry.byKey("easter_egg");
        return f.skip(env, env.anchors.method("easter_egg_o"), "easter_egg_o");
    }

    /** 频道栏默认“关注”（口味项，默认关）。 */
    public static boolean installDefaultChannel(HookEnv env) {
        final Feature f = FeatureRegistry.byKey("default_channel");
        Method method = env.anchors.method("new_scroll_r");
        return f.hook(env, method, "new_scroll_r", new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object[] out = chain.getArgs().toArray();
                Object raw = out.length > 1 ? out[1] : null;
                if (raw instanceof List) {
                    List<?> list = (List<?>) raw;
                    List<Object> focus = new ArrayList<>();
                    for (Object item : list) {
                        if (looksLikeFollowTab(item)) {
                            focus.add(item);
                        }
                    }
                    if (!focus.isEmpty() && list.get(0) != focus.get(0)) {
                        List<Object> reordered = new ArrayList<>(list.size());
                        reordered.addAll(focus);
                        for (Object item : list) {
                            boolean inFocus = false;
                            for (Object focusItem : focus) {
                                if (focusItem == item) {
                                    inFocus = true;
                                    break;
                                }
                            }
                            if (!inFocus) {
                                reordered.add(item);
                            }
                        }
                        out[1] = reordered;
                    }
                }
                return chain.proceed(out);
            }
        });
    }

    /** 强制夜间（口味项，默认关）。 */
    public static boolean installForceNight(HookEnv env) {
        Feature f = FeatureRegistry.byKey("force_night");
        Method method = env.anchors.method("skin_type");
        return f.after(env, method, "skin_type", new Feature.Transformer() {
            @Override
            public Object apply(Object result) {
                return Integer.valueOf(4);
            }
        });
    }

    /** 直播卡过滤：信息流中的开播/直播卡片。 */
    public static boolean installLiveCardFilter(HookEnv env) {
        final Feature f = FeatureRegistry.byKey("live_card_filter");
        boolean any = false;

        Method setList = env.anchors.method("template_set_list");
        if (setList != null) {
            boolean installed = f.hook(env, setList, "template_set_list",
                    new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object adapter = chain.getThisObject();
                            List<Object> args = chain.getArgs();
                            Object first = args.size() > 0 ? args.get(0) : null;
                            List<?> original = first instanceof List ? (List<?>) first : null;
                            List<?> filtered = CardFilter.filterLive(env, original);
                            Object[] out = args.toArray();
                            if (filtered != null && filtered != original) {
                                out[0] = filtered;
                            }
                            // 直播卡由多个组件拼成，数据层删不干净，视图层再按卡片数据整卡隐藏。
                            FeedCardSweeper.schedule(env, adapter, true, false);
                            return chain.proceed(out);
                        }
                    });
            any = any || installed;
        } else {
            f.markSkip("锚点缺失 template_set_list");
        }

        // 兜底：首页直播卡专用适配器，把条目高度压成 0。
        Method fill = env.anchors.method("live_adapter_fill");
        if (fill != null) {
            boolean installed = f.after(env, fill, "live_adapter_fill",
                    new Feature.Transformer() {
                        @Override
                        public Object apply(Object result) {
                            env.module.log(Log.INFO, Feature.TAG,
                                    f.code + " live filter: adapter fill");
                            hideView(result);
                            return result;
                        }
                    });
            any = any || installed;
        }

        // “关注的主播在直播”卡片：绑定数据后直接隐藏并压成 0 高。
        Method liveCard = env.anchors.method("ala_live_card_bind");
        if (liveCard != null) {
            boolean installed = f.hook(env, liveCard, "ala_live_card_bind",
                    new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            env.module.log(Log.INFO, Feature.TAG,
                                    f.code + " live filter: attention card bind");
                            Object view = chain.getThisObject();
                            Object result = chain.proceed();
                            hideView(view);
                            return result;
                        }
                    });
            any = any || installed;
        }

        // 直播推荐卡（含内嵌直播列表）：创建后直接压成 0 高。
        Method create = env.anchors.method("live_card_create");
        if (create != null) {
            boolean installed = f.after(env, create, "live_card_create",
                    new Feature.Transformer() {
                        @Override
                        public Object apply(Object result) {
                            env.module.log(Log.INFO, Feature.TAG,
                                    f.code + " live filter: card create");
                            hideView(result);
                            return result;
                        }
                    });
            any = any || installed;
        }

        // 直播 SDK 的卡片 item：绑定数据后隐藏并压成 0 高。
        for (String key : LIVE_ITEM_KEYS) {
            Method m = env.anchors.method(key);
            if (m == null) {
                continue;
            }
            boolean installed = f.hook(env, m, key, new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object view = chain.getThisObject();
                    Object result = chain.proceed();
                    hideView(view);
                    return result;
                }
            });
            any = any || installed;
        }
        return any;
    }

    /** 实时热点卡过滤：信息流中的“实时热点”卡片。 */
    public static boolean installHotCardFilter(HookEnv env) {
        final Feature f = FeatureRegistry.byKey("hot_card_filter");
        Method method = env.anchors.method("template_set_list");
        return f.hook(env, method, "template_set_list", new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                // 与直播卡共用一条按 adapter 去重的 sweep 链，只合并热点策略。
                FeedCardSweeper.schedule(env, chain.getThisObject(), false, true);
                return chain.proceed();
            }
        });
    }

    /** 个人中心精简：H5 “我的”页注入 CSS/JS 隐藏会员横幅、钱包、任务与游戏专区。 */
    public static boolean installProfileCleanup(HookEnv env) {
        final Feature f = FeatureRegistry.byKey("profile_cleanup");
        Method method = env.anchors.method("person_tab_attach");
        return f.hook(env, method, "person_tab_attach", new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                final Object controller = chain.getThisObject();
                Object result = chain.proceed();
                if (controller != null) {
                    env.module.log(Log.INFO, Feature.TAG,
                            f.code + " profile cleanup: controller attached");
                    final Handler handler = new Handler(Looper.getMainLooper());
                    final int[] attempts = {0};
                    Runnable inject = new Runnable() {
                        @Override
                        public void run() {
                            try {
                                Object tbWebView = controller.getClass()
                                        .getMethod("k").invoke(controller);
                                WebView inner = null;
                                if (tbWebView != null) {
                                    Object raw = tbWebView.getClass()
                                            .getMethod("getInnerWebView").invoke(tbWebView);
                                    if (raw instanceof WebView) {
                                        inner = (WebView) raw;
                                    }
                                }
                                if (attempts[0] == 0) {
                                    env.module.log(Log.INFO, Feature.TAG,
                                            f.code + " profile cleanup js applied");
                                }
                                if (inner != null) {
                                    inner.evaluateJavascript(PROFILE_JS, null);
                                }
                            } catch (Throwable ignored) {
                            }
                            attempts[0]++;
                            if (attempts[0] < 15) {
                                handler.postDelayed(this, 700);
                            }
                        }
                    };
                    handler.postDelayed(inject, 800);
                }
                return result;
            }
        });
    }

    /** 吧页推广卡过滤。 */
    public static boolean installForumPromoFilter(HookEnv env) {
        Feature f = FeatureRegistry.byKey("forum_promo_filter");
        Method method = env.anchors.method("forum_res_parser_a");
        return f.after(env, method, "forum_res_parser_a", new Feature.Transformer() {
            @Override
            public Object apply(Object result) {
                List<?> filtered = CardFilter.filter(env,
                        result instanceof List ? (List<?>) result : null);
                return filtered != null ? filtered : result;
            }
        });
    }

    private static boolean looksLikeFollowTab(Object item) {
        if (item == null) {
            return false;
        }
        try {
            Class<?> cls = item.getClass();
            String b = readString(cls, item, "b");
            String c = readString(cls, item, "c");
            return (b != null && b.contains("关注")) || (c != null && c.contains("关注"));
        } catch (Throwable t) {
            return false;
        }
    }

    private static String readString(Class<?> cls, Object item, String name) {
        Class<?> c = cls;
        while (c != null) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                Object value = field.get(item);
                return value instanceof String ? (String) value : null;
            } catch (Throwable t) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    private static void hideView(Object view) {
        if (!(view instanceof View)) {
            return;
        }
        View v = (View) view;
        v.setVisibility(View.GONE);
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp != null) {
            lp.height = 0;
            v.setLayoutParams(lp);
        }
    }
}
