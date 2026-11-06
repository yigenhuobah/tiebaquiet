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
import io.github.yigenhuobah.tiebaquiet.anchor.AnchorKeys;
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
            AnchorKeys.TAB_ADD_TIP, AnchorKeys.TAB_ADD_TIP_2, AnchorKeys.TAB_ADD_TIP_3,
            AnchorKeys.TAB_ADD_TIP_4, AnchorKeys.TAB_ADD_TIP_WRAP,
            AnchorKeys.MSG_RED_DOT_REFRESH, AnchorKeys.MSG_RED_DOT_REFRESH_OPT,
            AnchorKeys.MSG_RED_DOT_REFRESH_STR,
    };

    private static final String[] LIVE_ITEM_KEYS = {
            AnchorKeys.LIVE_ITEM_16_SET_DATA, AnchorKeys.LIVE_ITEM_45_SET_DATA,
    };

    /** 个人页注入脚本的重试上限（约 0.8s + 14×0.7s ≈ 10.6s 后放弃）。 */
    static final int INJECT_MAX_ATTEMPTS = 15;

    private Phase1Features() {
    }

    /** 开屏广告：走官方“会员无开屏”路径。 */
    public static boolean installSplashNoAd(HookEnv env) {
        Feature f = FeatureRegistry.byKey("splash_no_ad");
        return f.replace(env, env.anchors.method(AnchorKeys.SPLASH_FORBID_B),
                "splash_forbid_b", Boolean.TRUE);
    }

    /** 营销/活动/签到弹窗总闸。 */
    public static boolean installYunDialog(HookEnv env) {
        Feature f = FeatureRegistry.byKey("yun_dialog");
        return f.replace(env, env.anchors.method(AnchorKeys.YUN_DIALOG_CAN_SHOW),
                "yun_dialog_can_show", Boolean.FALSE);
    }

    /** 首页/帖子页悬浮广告。 */
    public static boolean installFloatAd(HookEnv env) {
        Feature f = FeatureRegistry.byKey("float_ad");
        boolean home = f.replace(env, env.anchors.method(AnchorKeys.HOME_AD_FLOAT),
                "home_ad_float", null);
        boolean postBar = f.replace(env, env.anchors.method(AnchorKeys.PB_AD_FLOAT),
                "pb_ad_float", null);
        return home || postBar;
    }

    /** 信息流广告/推广卡过滤（全列表统一入口）。 */
    public static boolean installFeedAdFilter(HookEnv env) {
        final Feature f = FeatureRegistry.byKey("feed_ad_filter");
        Method method = env.anchors.method(AnchorKeys.TEMPLATE_SET_LIST);
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
                return Feature.proceed(chain, out);
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
        return f.replace(env, env.anchors.method(AnchorKeys.REC_FORUM_STRATEGY_H),
                "rec_forum_strategy_h", Boolean.FALSE);
    }

    /** 更新弹窗。 */
    public static boolean installUpdateDialog(HookEnv env) {
        Feature f = FeatureRegistry.byKey("update_dialog");
        return f.skip(env, env.anchors.method(AnchorKeys.UPGRADE_POP), "upgrade_pop");
    }

    /** 彩蛋广告。 */
    public static boolean installEasterEgg(HookEnv env) {
        Feature f = FeatureRegistry.byKey("easter_egg");
        return f.skip(env, env.anchors.method(AnchorKeys.EASTER_EGG_O), "easter_egg_o");
    }

    /** 频道栏默认“关注”（口味项，默认关）。 */
    public static boolean installDefaultChannel(HookEnv env) {
        final Feature f = FeatureRegistry.byKey("default_channel");
        Method method = env.anchors.method(AnchorKeys.NEW_SCROLL_R);
        return f.hook(env, method, "new_scroll_r", new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object[] out = chain.getArgs().toArray();
                Object raw = out.length > 1 ? out[1] : null;
                if (raw instanceof List) {
                    List<?> list = (List<?>) raw;
                    List<Object> focus = new ArrayList<>();
                    for (Object item : list) {
                        if (looksLikeFollowTab(env, item)) {
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
                return Feature.proceed(chain, out);
            }
        });
    }

    /** 强制夜间（口味项，默认关）。 */
    public static boolean installForceNight(HookEnv env) {
        Feature f = FeatureRegistry.byKey("force_night");
        Method method = env.anchors.method(AnchorKeys.SKIN_TYPE);
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

        Method setList = env.anchors.method(AnchorKeys.TEMPLATE_SET_LIST);
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
                            return Feature.proceed(chain, out);
                        }
                    });
            any = any || installed;
        } else {
            f.markSkip("锚点缺失 template_set_list");
        }

        // 兜底：首页直播卡专用适配器，把条目高度压成 0。
        Method fill = env.anchors.method(AnchorKeys.LIVE_ADAPTER_FILL);
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
        Method liveCard = env.anchors.method(AnchorKeys.ALA_LIVE_CARD_BIND);
        if (liveCard != null) {
            boolean installed = f.hook(env, liveCard, "ala_live_card_bind",
                    new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            env.module.log(Log.INFO, Feature.TAG,
                                    f.code + " live filter: attention card bind");
                            Object view = chain.getThisObject();
                            Object result = Feature.proceed(chain);
                            hideView(view);
                            return result;
                        }
                    });
            any = any || installed;
        }

        // 直播推荐卡（含内嵌直播列表）：创建后直接压成 0 高。
        Method create = env.anchors.method(AnchorKeys.LIVE_CARD_CREATE);
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

        // FRS/吧内插入直播卡：绑定数据后隐藏整卡（22.13.1.1 的 z88#s）。
        Method frsBind = env.anchors.method(AnchorKeys.FRS_INSERT_LIVE_BIND);
        if (frsBind != null) {
            boolean installed = f.hook(env, frsBind, "frs_insert_live_bind",
                    new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object result = Feature.proceed(chain);
                            env.module.log(Log.INFO, Feature.TAG,
                                    f.code + " live filter: frs insert card bind");
                            hideView(result);
                            return result;
                        }
                    });
            any = any || installed;
        } else {
            f.markSkip("锚点缺失 frs_insert_live_bind");
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
                    Object result = Feature.proceed(chain);
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
        Method method = env.anchors.method(AnchorKeys.TEMPLATE_SET_LIST);
        return f.hook(env, method, "template_set_list", new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                // 与直播卡共用一条按 adapter 去重的 sweep 链，只合并热点策略。
                FeedCardSweeper.schedule(env, chain.getThisObject(), false, true);
                return Feature.proceed(chain);
            }
        });
    }

    /** 底栏小卖部：强制走“发布入口迁移”形态，隐藏小卖部 Tab 并恢复底栏发布按钮。 */
    public static boolean installHideStoreTab(HookEnv env) {
        Feature f = FeatureRegistry.byKey("hide_store_tab");
        Method method = env.anchors.method(AnchorKeys.RETAIL_STORE_PAGE);
        return f.replace(env, method, "retail_store_page", Boolean.FALSE);
    }

    /** 个人中心精简：H5 “我的”页注入 CSS/JS 隐藏会员横幅、钱包、任务与游戏专区。 */
    public static boolean installProfileCleanup(HookEnv env) {
        final Feature f = FeatureRegistry.byKey("profile_cleanup");
        // 注入链 k()/getInnerWebView 已收编进 anchors.json（person_tab_web_k /
        // tbwebview_inner）：由 gen_anchors 按目标 APK 校验，AnchorTable 再按
        // 签名解析并 setAccessible。改名/改可见性时这里得到 null，功能按锚点
        // 缺失显式跳过，不再 hook 后靠运行期硬编码反射名静默失效。
        final Method getWeb = env.anchors.method(AnchorKeys.PERSON_TAB_WEB_K);
        final Method getInner = env.anchors.method(AnchorKeys.TBWEBVIEW_INNER);
        if (getWeb == null || getInner == null) {
            f.markSkip("锚点缺失 person_tab_web_k/tbwebview_inner");
            return false;
        }
        Method method = env.anchors.method(AnchorKeys.PERSON_TAB_ATTACH);
        return f.hook(env, method, "person_tab_attach", new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                final Object controller = chain.getThisObject();
                Object result = Feature.proceed(chain);
                if (controller != null) {
                    env.module.log(Log.INFO, Feature.TAG,
                            f.code + " profile cleanup: controller attached");
                    final Handler handler = new Handler(Looper.getMainLooper());
                    final int[] attempts = {0};
                    Runnable inject = new Runnable() {
                        @Override
                        public void run() {
                            try {
                                Object tbWebView = getWeb.invoke(controller);
                                WebView inner = null;
                                if (tbWebView != null) {
                                    Object raw = getInner.invoke(tbWebView);
                                    if (raw instanceof WebView) {
                                        inner = (WebView) raw;
                                    }
                                }
                                if (inner != null) {
                                    if (attempts[0] == 0) {
                                        env.module.log(Log.INFO, Feature.TAG,
                                                f.code + " profile cleanup js applied");
                                    }
                                    inner.evaluateJavascript(PROFILE_JS, null);
                                }
                                String skip = profileCleanupSkipReason(
                                        attempts[0], INJECT_MAX_ATTEMPTS, inner != null);
                                if (skip != null) {
                                    env.module.log(Log.WARN, Feature.TAG,
                                            f.code + " profile cleanup: " + skip);
                                    f.markSkip(skip);
                                }
                            } catch (Throwable t) {
                                // 锚点已在安装期校验，这里只剩运行期异常（页面尚未
                                // 就绪等）：记日志，次数用尽才标记 skip，避免把瞬时
                                // 失败当成锚点失效。
                                env.module.log(Log.WARN, Feature.TAG,
                                        f.code + " profile cleanup inject failed", t);
                                String skip = profileCleanupSkipReason(
                                        attempts[0], INJECT_MAX_ATTEMPTS, false);
                                if (skip != null) {
                                    f.markSkip(skip);
                                }
                            }
                            attempts[0]++;
                            if (attempts[0] < INJECT_MAX_ATTEMPTS) {
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
        Method method = env.anchors.method(AnchorKeys.FORUM_RES_PARSER_A);
        return f.after(env, method, "forum_res_parser_a", new Feature.Transformer() {
            @Override
            public Object apply(Object result) {
                List<?> filtered = CardFilter.filter(env,
                        result instanceof List ? (List<?>) result : null);
                return filtered != null ? filtered : result;
            }
        });
    }

    /**
     * 个人页注入的收尾决策（纯函数，便于 JVM 单测）：本轮拿到内嵌 WebView
     * 返回 null；重试次数用尽仍拿不到时返回诊断 skip 文案。
     *
     * <p>{@code k()}/{@code getInnerWebView} 已收编进 anchors.json（键
     * {@code person_tab_web_k}/{@code tbwebview_inner}，gen_anchors 按 APK 校验、
     * AnchorTable 按签名解析）；安装期解析失败会直接按锚点缺失跳过，走到这里的
     * 只有安装成功后的运行期取不到 WebView（页面未就绪等），用尽重试后必须
     * 标记 skip，不能静默假成功。
     */
    static String profileCleanupSkipReason(int attempt, int maxAttempts, boolean injected) {
        if (injected || attempt < maxAttempts - 1) {
            return null;
        }
        return "重试用尽仍未注入（person_tab_web_k/tbwebview_inner）";
    }

    /**
     * 频道 Tab 判定：字段名 b/c 已收编为字段锚点（home_tab_item_b/c），
     * 由 AnchorTable 按目标类 + 字段类型解析；改名/改类型时按锚点缺失返回 false。
     */
    private static boolean looksLikeFollowTab(HookEnv env, Object item) {
        if (item == null) {
            return false;
        }
        Class<?> cls = env.anchors.clazz(AnchorKeys.HOME_TAB_ITEM);
        if (cls == null || !cls.isInstance(item)) {
            return false;
        }
        String b = readString(env, AnchorKeys.HOME_TAB_ITEM_B, item);
        String c = readString(env, AnchorKeys.HOME_TAB_ITEM_C, item);
        return (b != null && b.contains("关注")) || (c != null && c.contains("关注"));
    }

    private static String readString(HookEnv env, String key, Object item) {
        try {
            Field field = env.anchors.field(key);
            if (field == null) {
                return null;
            }
            Object value = field.get(item);
            return value instanceof String ? (String) value : null;
        } catch (Throwable t) {
            return null;
        }
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
