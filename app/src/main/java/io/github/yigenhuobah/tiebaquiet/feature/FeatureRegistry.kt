package io.github.yigenhuobah.tiebaquiet.feature

import androidx.annotation.DrawableRes
import io.github.yigenhuobah.tiebaquiet.R
import io.github.yigenhuobah.tiebaquiet.feature.phase1.DefaultChannelFeature
import io.github.yigenhuobah.tiebaquiet.feature.phase1.EasterEggFeature
import io.github.yigenhuobah.tiebaquiet.feature.phase1.FeedAdFilterFeature
import io.github.yigenhuobah.tiebaquiet.feature.phase1.FloatAdFeature
import io.github.yigenhuobah.tiebaquiet.feature.phase1.ForceNightFeature
import io.github.yigenhuobah.tiebaquiet.feature.phase1.ForumPromoFilterFeature
import io.github.yigenhuobah.tiebaquiet.feature.phase1.HotCardFilterFeature
import io.github.yigenhuobah.tiebaquiet.feature.phase1.LiveCardFilterFeature
import io.github.yigenhuobah.tiebaquiet.feature.phase1.NoRecForumFeature
import io.github.yigenhuobah.tiebaquiet.feature.phase1.ProfileCleanupFeature
import io.github.yigenhuobah.tiebaquiet.feature.phase1.SplashNoAdFeature
import io.github.yigenhuobah.tiebaquiet.feature.phase1.TabRedDotFeature
import io.github.yigenhuobah.tiebaquiet.feature.phase1.UpdateDialogFeature
import io.github.yigenhuobah.tiebaquiet.feature.phase1.YunDialogFeature
import io.github.yigenhuobah.tiebaquiet.feature.phase2.AdRequestBlockFeature
import io.github.yigenhuobah.tiebaquiet.feature.phase2.HostOnlyFeature
import io.github.yigenhuobah.tiebaquiet.feature.phase2.NoWatermarkFeature
import io.github.yigenhuobah.tiebaquiet.feature.phase2.OriginalImageFeature
import io.github.yigenhuobah.tiebaquiet.feature.phase2.ReverseOrderFeature
import io.github.yigenhuobah.tiebaquiet.feature.phase2.SignInFeature
import io.github.yigenhuobah.tiebaquiet.feature.phase2.VideoLinkFeature
import io.github.yigenhuobah.tiebaquiet.feature.phase3.ClipboardGuardFeature
import io.github.yigenhuobah.tiebaquiet.feature.phase3.KeepAliveBlockFeature
import io.github.yigenhuobah.tiebaquiet.feature.phase3.PersonalizedAdOffFeature

/** 设置页分组：按用途而不是按开发批次，和静盒一致。 */
enum class FeatureGroup(val title: String, @DrawableRes val iconRes: Int) {
    ADS("广告清理", R.drawable.ic_ads),
    UI("界面精简", R.drawable.ic_ui),
    CONTENT("功能增强", R.drawable.ic_content),
    PRIVACY("隐私保护", R.drawable.ic_privacy),
}

object FeatureRegistry {
    val all: List<Feature> = listOf(
        // phase 1
        SplashNoAdFeature,
        YunDialogFeature,
        FloatAdFeature,
        FeedAdFilterFeature,
        TabRedDotFeature,
        NoRecForumFeature,
        UpdateDialogFeature,
        EasterEggFeature,
        DefaultChannelFeature,
        ForceNightFeature,
        ForumPromoFilterFeature,
        LiveCardFilterFeature,
        HotCardFilterFeature,
        ProfileCleanupFeature,
        // phase 2
        AdRequestBlockFeature,
        HostOnlyFeature,
        ReverseOrderFeature,
        NoWatermarkFeature,
        OriginalImageFeature,
        VideoLinkFeature,
        SignInFeature,
        // phase 3
        ClipboardGuardFeature,
        PersonalizedAdOffFeature,
        KeepAliveBlockFeature,
    )

    /** 设置页展示顺序与分组。 */
    val groups: List<Pair<FeatureGroup, List<Feature>>> = listOf(
        FeatureGroup.ADS to listOf(
            SplashNoAdFeature,
            AdRequestBlockFeature,
            FeedAdFilterFeature,
            ForumPromoFilterFeature,
            LiveCardFilterFeature,
            HotCardFilterFeature,
            FloatAdFeature,
            YunDialogFeature,
            NoRecForumFeature,
            EasterEggFeature,
        ),
        FeatureGroup.UI to listOf(
            TabRedDotFeature,
            UpdateDialogFeature,
            ProfileCleanupFeature,
            DefaultChannelFeature,
            ForceNightFeature,
        ),
        FeatureGroup.CONTENT to listOf(
            HostOnlyFeature,
            ReverseOrderFeature,
            NoWatermarkFeature,
            OriginalImageFeature,
            VideoLinkFeature,
            SignInFeature,
        ),
        FeatureGroup.PRIVACY to listOf(
            ClipboardGuardFeature,
            PersonalizedAdOffFeature,
            KeepAliveBlockFeature,
        ),
    )

    fun byId(id: String): Feature? = all.firstOrNull { it.id == id }
}
