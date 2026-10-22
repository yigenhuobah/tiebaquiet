package io.github.yigenhuobah.tiebaquiet.settings

/**
 * remote prefs 分组迁移的纯逻辑，不依赖 Android，便于 JVM 单元测试。
 *
 * 旧版本把所有开关写在 group {@code features}；统一规范要求 group {@code config}。
 * 读取顺序固定：新 key 优先 → 旧 key 兜底 → 编译期默认值。
 * 写入时由 [SettingsRepository] 同时写新、旧两个 group，保留一个版本供回滚。
 */
object ConfigMigration {

    /** 统一 remote prefs group。 */
    const val GROUP = "config"

    /** 旧 group，只读兜底 + 双写一个版本，下个大版本删除。 */
    const val LEGACY_GROUP = "features"

    /**
     * 三态合并：新值存在（包括 false）就用新值；否则用旧值；都缺失才用默认值。
     * {@code null} 表示该 group 中没有这个 key。
     */
    @JvmStatic
    fun resolve(newValue: Boolean?, legacyValue: Boolean?, default: Boolean): Boolean =
        newValue ?: legacyValue ?: default

    /** 便于测试与批量场景的 Map 重载；缺失 key 映射为 {@code null}。 */
    @JvmStatic
    fun resolve(
        key: String,
        newValues: Map<String, Boolean>,
        legacyValues: Map<String, Boolean>,
        default: Boolean,
    ): Boolean = resolve(newValues[key], legacyValues[key], default)
}
