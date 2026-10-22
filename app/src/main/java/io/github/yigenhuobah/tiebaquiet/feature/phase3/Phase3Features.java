package io.github.yigenhuobah.tiebaquiet.feature.phase3;

import io.github.yigenhuobah.tiebaquiet.Feature;
import io.github.yigenhuobah.tiebaquiet.FeatureRegistry;
import io.github.yigenhuobah.tiebaquiet.HookEnv;

/**
 * 第三批 3 个隐私类功能的安装器，全部默认关。
 */
public final class Phase3Features {

    private Phase3Features() {
    }

    /** 剪贴板保护：掐断口令监听与图片口令读取的共同源头。 */
    public static boolean installClipboardGuard(HookEnv env) {
        Feature f = FeatureRegistry.byKey("clipboard_guard");
        return f.replace(env, env.anchors.method("clipboard"), "clipboard", "");
    }

    /** 个性化广告：强制关闭（1 = 关闭态）。 */
    public static boolean installPersonalizedAdOff(HookEnv env) {
        Feature f = FeatureRegistry.byKey("personalized_ad_off");
        return f.replace(env, env.anchors.method("personalized_ad"),
                "personalized_ad", Integer.valueOf(1));
    }

    /** 保活拦截：不拉起 ALa 保活服务。 */
    public static boolean installKeepAliveBlock(HookEnv env) {
        Feature f = FeatureRegistry.byKey("keep_alive_block");
        return f.skip(env, env.anchors.method("keep_alive"), "keep_alive");
    }
}
