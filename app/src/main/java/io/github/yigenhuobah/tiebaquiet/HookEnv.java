package io.github.yigenhuobah.tiebaquiet;

import android.content.SharedPreferences;

import io.github.yigenhuobah.tiebaquiet.anchor.AnchorTable;

/**
 * 目标进程的 hook 环境。
 *
 * <p>{@code prefs} 是新 group {@code config}；{@code legacyPrefs} 是旧 group
 * {@code features}。两者都可能为 null（框架未提供 remote prefs），此时按编译期默认值处理。
 */
public final class HookEnv {

    public final TiebaQuietModule module;
    public final ClassLoader loader;
    public final AnchorTable anchors;
    public final SharedPreferences prefs;
    public final SharedPreferences legacyPrefs;
    public final String processName;

    public HookEnv(TiebaQuietModule module, ClassLoader loader, AnchorTable anchors,
                   SharedPreferences prefs, SharedPreferences legacyPrefs,
                   String processName) {
        this.module = module;
        this.loader = loader;
        this.anchors = anchors;
        this.prefs = prefs;
        this.legacyPrefs = legacyPrefs;
        this.processName = processName;
    }
}
