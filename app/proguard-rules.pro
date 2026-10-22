# Keep the Xposed entry class and feature classes; they are loaded by the framework by name.
-keep class io.github.yigenhuobah.tiebaquiet.TiebaQuietModule { *; }
-keep class io.github.yigenhuobah.tiebaquiet.feature.** { *; }

# Framework lifecycle callbacks are invoked reflectively by name.
-keepclassmembers class * extends io.github.libxposed.api.XposedModule {
    public void onModuleLoaded(...);
    public void onPackageLoaded(...);
    public void onPackageReady(...);
    public void onSystemServerStarting(...);
    public boolean onHotReloading(...);
    public void onHotReloaded(...);
}

# compileOnly artifacts are absent at runtime; R8 must not complain about them.
-dontwarn io.github.libxposed.api.**

# libxposed service provider (declared in manifest, instantiated by Android).
-keep class io.github.libxposed.service.** { *; }

# UI 入口由 manifest / FragmentManager 按类名实例化，R8 看不到这层引用。
-keep class io.github.yigenhuobah.tiebaquiet.MainActivity { *; }
-keep class io.github.yigenhuobah.tiebaquiet.SettingsFragment { *; }
-keep class io.github.yigenhuobah.tiebaquiet.DiagnosticsFragment { *; }
-keep class io.github.yigenhuobah.tiebaquiet.AboutFragment { *; }

# 自定义 Preference 由 res/xml/prefs.xml 按类名反射实例化。
-keep class io.github.yigenhuobah.tiebaquiet.MaterialSwitchPreference {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
}
