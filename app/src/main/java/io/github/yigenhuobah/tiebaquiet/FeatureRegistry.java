package io.github.yigenhuobah.tiebaquiet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.yigenhuobah.tiebaquiet.feature.phase1.Phase1Features;
import io.github.yigenhuobah.tiebaquiet.feature.phase2.Phase2Features;
import io.github.yigenhuobah.tiebaquiet.feature.phase3.Phase3Features;

/**
 * 25 个功能的唯一清单：编号、remote key、文案、默认值、分组与安装器。
 *
 * <p>顺序固定 AD → UI → PV → EN（没有 EX 功能），设置页与诊断页都按这个顺序渲染。
 * 安装器指向各 phase 类的静态方法；模块 App 只读取元数据，不执行安装。
 */
public final class FeatureRegistry {

    private static final List<Feature> ALL;
    private static final Map<String, Feature> BY_KEY;
    private static final List<Feature.Group> GROUPS;

    static {
        List<Feature> all = new ArrayList<>();

        // AD 广告净化（10 项，默认开）
        all.add(new Feature("AD-01", "splash_no_ad", R.string.f_splash_no_ad_title,
                R.string.f_splash_no_ad_summary, true, Feature.Group.AD,
                new Feature.Installer() {
                    @Override
                    public boolean install(HookEnv env) {
                        return Phase1Features.installSplashNoAd(env);
                    }
                }));
        all.add(new Feature("AD-02", "ad_request_block", R.string.f_ad_request_block_title,
                R.string.f_ad_request_block_summary, true, Feature.Group.AD,
                new Feature.Installer() {
                    @Override
                    public boolean install(HookEnv env) {
                        return Phase2Features.installAdRequestBlock(env);
                    }
                }));
        all.add(new Feature("AD-03", "feed_ad_filter", R.string.f_feed_ad_filter_title,
                R.string.f_feed_ad_filter_summary, true, Feature.Group.AD,
                new Feature.Installer() {
                    @Override
                    public boolean install(HookEnv env) {
                        return Phase1Features.installFeedAdFilter(env);
                    }
                }));
        all.add(new Feature("AD-04", "forum_promo_filter",
                R.string.f_forum_promo_filter_title,
                R.string.f_forum_promo_filter_summary, true, Feature.Group.AD,
                new Feature.Installer() {
                    @Override
                    public boolean install(HookEnv env) {
                        return Phase1Features.installForumPromoFilter(env);
                    }
                }));
        all.add(new Feature("AD-05", "live_card_filter", R.string.f_live_card_filter_title,
                R.string.f_live_card_filter_summary, true, Feature.Group.AD,
                new Feature.Installer() {
                    @Override
                    public boolean install(HookEnv env) {
                        return Phase1Features.installLiveCardFilter(env);
                    }
                }));
        all.add(new Feature("AD-06", "hot_card_filter", R.string.f_hot_card_filter_title,
                R.string.f_hot_card_filter_summary, true, Feature.Group.AD,
                new Feature.Installer() {
                    @Override
                    public boolean install(HookEnv env) {
                        return Phase1Features.installHotCardFilter(env);
                    }
                }));
        all.add(new Feature("AD-07", "float_ad", R.string.f_float_ad_title,
                R.string.f_float_ad_summary, true, Feature.Group.AD,
                new Feature.Installer() {
                    @Override
                    public boolean install(HookEnv env) {
                        return Phase1Features.installFloatAd(env);
                    }
                }));
        all.add(new Feature("AD-08", "yun_dialog", R.string.f_yun_dialog_title,
                R.string.f_yun_dialog_summary, true, Feature.Group.AD,
                new Feature.Installer() {
                    @Override
                    public boolean install(HookEnv env) {
                        return Phase1Features.installYunDialog(env);
                    }
                }));
        all.add(new Feature("AD-09", "rec_forum", R.string.f_rec_forum_title,
                R.string.f_rec_forum_summary, true, Feature.Group.AD,
                new Feature.Installer() {
                    @Override
                    public boolean install(HookEnv env) {
                        return Phase1Features.installNoRecForum(env);
                    }
                }));
        all.add(new Feature("AD-10", "easter_egg", R.string.f_easter_egg_title,
                R.string.f_easter_egg_summary, true, Feature.Group.AD,
                new Feature.Installer() {
                    @Override
                    public boolean install(HookEnv env) {
                        return Phase1Features.installEasterEgg(env);
                    }
                }));

        // UI 界面精简（4 项，默认开）
        all.add(new Feature("UI-01", "tab_red_dot", R.string.f_tab_red_dot_title,
                R.string.f_tab_red_dot_summary, true, Feature.Group.UI,
                new Feature.Installer() {
                    @Override
                    public boolean install(HookEnv env) {
                        return Phase1Features.installTabRedDot(env);
                    }
                }));
        all.add(new Feature("UI-02", "update_dialog", R.string.f_update_dialog_title,
                R.string.f_update_dialog_summary, true, Feature.Group.UI,
                new Feature.Installer() {
                    @Override
                    public boolean install(HookEnv env) {
                        return Phase1Features.installUpdateDialog(env);
                    }
                }));
        all.add(new Feature("UI-03", "profile_cleanup", R.string.f_profile_cleanup_title,
                R.string.f_profile_cleanup_summary, true, Feature.Group.UI,
                new Feature.Installer() {
                    @Override
                    public boolean install(HookEnv env) {
                        return Phase1Features.installProfileCleanup(env);
                    }
                }));
        all.add(new Feature("UI-04", "hide_store_tab", R.string.f_hide_store_tab_title,
                R.string.f_hide_store_tab_summary, true, Feature.Group.UI,
                new Feature.Installer() {
                    @Override
                    public boolean install(HookEnv env) {
                        return Phase1Features.installHideStoreTab(env);
                    }
                }));

        // PV 隐私保护（3 项，默认关）
        all.add(new Feature("PV-01", "clipboard_guard", R.string.f_clipboard_guard_title,
                R.string.f_clipboard_guard_summary, false, Feature.Group.PV,
                new Feature.Installer() {
                    @Override
                    public boolean install(HookEnv env) {
                        return Phase3Features.installClipboardGuard(env);
                    }
                }));
        all.add(new Feature("PV-02", "personalized_ad_off",
                R.string.f_personalized_ad_off_title,
                R.string.f_personalized_ad_off_summary, false, Feature.Group.PV,
                new Feature.Installer() {
                    @Override
                    public boolean install(HookEnv env) {
                        return Phase3Features.installPersonalizedAdOff(env);
                    }
                }));
        all.add(new Feature("PV-03", "keep_alive_block", R.string.f_keep_alive_block_title,
                R.string.f_keep_alive_block_summary, false, Feature.Group.PV,
                new Feature.Installer() {
                    @Override
                    public boolean install(HookEnv env) {
                        return Phase3Features.installKeepAliveBlock(env);
                    }
                }));

        // EN 功能增强（8 项，默认关）
        all.add(new Feature("EN-01", "default_channel", R.string.f_default_channel_title,
                R.string.f_default_channel_summary, false, Feature.Group.EN,
                new Feature.Installer() {
                    @Override
                    public boolean install(HookEnv env) {
                        return Phase1Features.installDefaultChannel(env);
                    }
                }));
        all.add(new Feature("EN-02", "force_night", R.string.f_force_night_title,
                R.string.f_force_night_summary, false, Feature.Group.EN,
                new Feature.Installer() {
                    @Override
                    public boolean install(HookEnv env) {
                        return Phase1Features.installForceNight(env);
                    }
                }));
        all.add(new Feature("EN-03", "host_only", R.string.f_host_only_title,
                R.string.f_host_only_summary, false, Feature.Group.EN,
                new Feature.Installer() {
                    @Override
                    public boolean install(HookEnv env) {
                        return Phase2Features.installHostOnly(env);
                    }
                }));
        all.add(new Feature("EN-04", "reverse_order", R.string.f_reverse_order_title,
                R.string.f_reverse_order_summary, false, Feature.Group.EN,
                new Feature.Installer() {
                    @Override
                    public boolean install(HookEnv env) {
                        return Phase2Features.installReverseOrder(env);
                    }
                }));
        all.add(new Feature("EN-05", "no_watermark", R.string.f_no_watermark_title,
                R.string.f_no_watermark_summary, false, Feature.Group.EN,
                new Feature.Installer() {
                    @Override
                    public boolean install(HookEnv env) {
                        return Phase2Features.installNoWatermark(env);
                    }
                }));
        all.add(new Feature("EN-06", "original_image", R.string.f_original_image_title,
                R.string.f_original_image_summary, false, Feature.Group.EN,
                new Feature.Installer() {
                    @Override
                    public boolean install(HookEnv env) {
                        return Phase2Features.installOriginalImage(env);
                    }
                }));
        all.add(new Feature("EN-07", "video_link", R.string.f_video_link_title,
                R.string.f_video_link_summary, false, Feature.Group.EN,
                new Feature.Installer() {
                    @Override
                    public boolean install(HookEnv env) {
                        return Phase2Features.installVideoLink(env);
                    }
                }));
        all.add(new Feature("EN-08", "one_key_sign", R.string.f_one_key_sign_title,
                R.string.f_one_key_sign_summary, false, Feature.Group.EN,
                new Feature.Installer() {
                    @Override
                    public boolean install(HookEnv env) {
                        return Phase2Features.installSignIn(env);
                    }
                }));

        ALL = Collections.unmodifiableList(all);

        Map<String, Feature> byKey = new LinkedHashMap<>();
        for (Feature feature : all) {
            byKey.put(feature.key, feature);
        }
        BY_KEY = Collections.unmodifiableMap(byKey);

        List<Feature.Group> groups = new ArrayList<>();
        for (Feature.Group group : Feature.Group.values()) {
            for (Feature feature : all) {
                if (feature.group == group) {
                    groups.add(group);
                    break;
                }
            }
        }
        GROUPS = Collections.unmodifiableList(groups);
    }

    private FeatureRegistry() {
    }

    public static List<Feature> all() {
        return ALL;
    }

    /** 有功能的分组，顺序 AD → UI → PV → EN → EX。 */
    public static List<Feature.Group> groups() {
        return GROUPS;
    }

    public static List<Feature> byGroup(Feature.Group group) {
        List<Feature> out = new ArrayList<>();
        for (Feature feature : ALL) {
            if (feature.group == group) {
                out.add(feature);
            }
        }
        return out;
    }

    public static Feature byKey(String key) {
        return BY_KEY.get(key);
    }
}
