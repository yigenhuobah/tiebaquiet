# Keep the Xposed entry class and feature classes; they are loaded by the framework by name.
-keep class io.github.yigenhuobah.tiebaquiet.TiebaQuietModule { *; }
-keep class io.github.yigenhuobah.tiebaquiet.feature.** { *; }

# libxposed service provider (declared in manifest, instantiated by Android).
-keep class io.github.libxposed.service.** { *; }
