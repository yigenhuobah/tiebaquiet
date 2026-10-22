package io.github.yigenhuobah.tiebaquiet;

/**
 * 设置页与诊断页的功能元数据。
 *
 * <p>本轮只把 UI 元数据从 Kotlin {@code Feature} 对象中独立出来，避免重写 24 个 hook
 * 功能类。{@code key} 是 remote prefs 键，必须与 Kotlin {@code Feature.id} 完全一致，
 * 发布后不可修改；{@code code} 是统一功能编号，只用于展示、日志与文档。
 *
 * <p>枚举顺序按 FEATURE_ID_MAP.md 2.4 节 Tieba 表：AD-01~10、UI-01~03、EN-01~08、
 * PV-01~03。设置页按 AD → UI → PV → EN 重新分组显示，因此这里的顺序不必等于页面顺序。
 *
 * <p>默认值遵循统一策略：AD 广告净化、UI 界面精简默认开；EN 功能增强、PV 隐私保护
 * 默认关。默认值只影响新安装，已保存值不迁移、不覆盖。
 */
public enum UiFeature {

    // AD 广告净化（10 项）
    SPLASH_NO_AD("AD-01", "splash_no_ad", R.string.f_splash_no_ad_title,
            R.string.f_splash_no_ad_summary, true, Group.AD),
    AD_REQUEST_BLOCK("AD-02", "ad_request_block", R.string.f_ad_request_block_title,
            R.string.f_ad_request_block_summary, true, Group.AD),
    FEED_AD_FILTER("AD-03", "feed_ad_filter", R.string.f_feed_ad_filter_title,
            R.string.f_feed_ad_filter_summary, true, Group.AD),
    FORUM_PROMO_FILTER("AD-04", "forum_promo_filter", R.string.f_forum_promo_filter_title,
            R.string.f_forum_promo_filter_summary, true, Group.AD),
    LIVE_CARD_FILTER("AD-05", "live_card_filter", R.string.f_live_card_filter_title,
            R.string.f_live_card_filter_summary, true, Group.AD),
    HOT_CARD_FILTER("AD-06", "hot_card_filter", R.string.f_hot_card_filter_title,
            R.string.f_hot_card_filter_summary, true, Group.AD),
    FLOAT_AD("AD-07", "float_ad", R.string.f_float_ad_title,
            R.string.f_float_ad_summary, true, Group.AD),
    YUN_DIALOG("AD-08", "yun_dialog", R.string.f_yun_dialog_title,
            R.string.f_yun_dialog_summary, true, Group.AD),
    REC_FORUM("AD-09", "rec_forum", R.string.f_rec_forum_title,
            R.string.f_rec_forum_summary, true, Group.AD),
    EASTER_EGG("AD-10", "easter_egg", R.string.f_easter_egg_title,
            R.string.f_easter_egg_summary, true, Group.AD),

    // UI 界面精简（3 项）
    TAB_RED_DOT("UI-01", "tab_red_dot", R.string.f_tab_red_dot_title,
            R.string.f_tab_red_dot_summary, true, Group.UI),
    UPDATE_DIALOG("UI-02", "update_dialog", R.string.f_update_dialog_title,
            R.string.f_update_dialog_summary, true, Group.UI),
    PROFILE_CLEANUP("UI-03", "profile_cleanup", R.string.f_profile_cleanup_title,
            R.string.f_profile_cleanup_summary, true, Group.UI),

    // EN 功能增强（8 项）
    DEFAULT_CHANNEL("EN-01", "default_channel", R.string.f_default_channel_title,
            R.string.f_default_channel_summary, false, Group.EN),
    FORCE_NIGHT("EN-02", "force_night", R.string.f_force_night_title,
            R.string.f_force_night_summary, false, Group.EN),
    HOST_ONLY("EN-03", "host_only", R.string.f_host_only_title,
            R.string.f_host_only_summary, false, Group.EN),
    REVERSE_ORDER("EN-04", "reverse_order", R.string.f_reverse_order_title,
            R.string.f_reverse_order_summary, false, Group.EN),
    NO_WATERMARK("EN-05", "no_watermark", R.string.f_no_watermark_title,
            R.string.f_no_watermark_summary, false, Group.EN),
    ORIGINAL_IMAGE("EN-06", "original_image", R.string.f_original_image_title,
            R.string.f_original_image_summary, false, Group.EN),
    VIDEO_LINK("EN-07", "video_link", R.string.f_video_link_title,
            R.string.f_video_link_summary, false, Group.EN),
    ONE_KEY_SIGN("EN-08", "one_key_sign", R.string.f_one_key_sign_title,
            R.string.f_one_key_sign_summary, false, Group.EN),

    // PV 隐私保护（3 项）
    CLIPBOARD_GUARD("PV-01", "clipboard_guard", R.string.f_clipboard_guard_title,
            R.string.f_clipboard_guard_summary, false, Group.PV),
    PERSONALIZED_AD_OFF("PV-02", "personalized_ad_off", R.string.f_personalized_ad_off_title,
            R.string.f_personalized_ad_off_summary, false, Group.PV),
    KEEP_ALIVE_BLOCK("PV-03", "keep_alive_block", R.string.f_keep_alive_block_title,
            R.string.f_keep_alive_block_summary, false, Group.PV);

    /** 统一功能编号，格式 AD/UI/PV/EN/EX-NN。 */
    public final String code;
    /** remote prefs 键，必须与 Kotlin Feature.id 一致，发布后不可改。 */
    public final String key;
    public final int titleRes;
    public final int summaryRes;
    public final boolean defaultEnabled;
    public final Group group;

    UiFeature(String code, String key, int titleRes, int summaryRes, boolean defaultEnabled,
              Group group) {
        this.code = code;
        this.key = key;
        this.titleRes = titleRes;
        this.summaryRes = summaryRes;
        this.defaultEnabled = defaultEnabled;
        this.group = group;
    }

    /** 分组顺序固定 AD → UI → PV → EN → EX；没有功能的分组不显示。 */
    public enum Group { AD, UI, PV, EN, EX }
}
