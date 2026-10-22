package io.github.yigenhuobah.tiebaquiet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * remote prefs 分组迁移：新 key 优先 → 旧 key 兜底 → 默认值。
 *
 * 旧版本把开关写在 group features；统一规范改为 config。
 * 新值即使是 false 也必须压过旧值，否则“用户在新版关掉、旧版残留开启”会失效。
 */
public class ConfigMigrationTest {

    private static final String KEY = "splash_no_ad";

    private static Map<String, Boolean> values(String key, boolean value) {
        Map<String, Boolean> map = new HashMap<>();
        map.put(key, value);
        return map;
    }

    @Test
    public void newKeyWinsOverLegacy() {
        assertTrue("新 key 为 true 时必须压过旧 key 的 false",
                ConfigMigration.resolve(KEY, values(KEY, true), values(KEY, false), false));
    }

    @Test
    public void newFalseStillWinsOverLegacyTrue() {
        assertFalse("新 key 显式 false 不能被旧 key 的 true 覆盖",
                ConfigMigration.resolve(KEY, values(KEY, false), values(KEY, true), true));
    }

    @Test
    public void legacyKeyUsedWhenNewMissing() {
        assertTrue("新 group 缺失时应回退旧 key",
                ConfigMigration.resolve(KEY, Collections.emptyMap(), values(KEY, true), false));
    }

    @Test
    public void legacyFalseUsedWhenNewMissing() {
        assertFalse("旧 key 显式 false 不能被默认值覆盖",
                ConfigMigration.resolve(KEY, Collections.emptyMap(), values(KEY, false), true));
    }

    @Test
    public void defaultUsedWhenBothMissing() {
        assertTrue("两个 group 都缺失时使用默认值 true",
                ConfigMigration.resolve(KEY, Collections.emptyMap(), Collections.emptyMap(), true));
    }

    @Test
    public void defaultFalseUsedWhenBothMissing() {
        assertFalse("两个 group 都缺失时使用默认值 false",
                ConfigMigration.resolve(KEY, Collections.emptyMap(), Collections.emptyMap(), false));
    }

    @Test
    public void unknownKeyIgnored() {
        assertTrue("无关 key 不应影响目标 key",
                ConfigMigration.resolve(KEY, values("other", false),
                        values("other", false), true));
    }

    @Test
    public void nullMapsFallBackToDefault() {
        assertTrue("null Map 与缺失 key 等价",
                ConfigMigration.resolve(KEY, null, null, true));
    }

    @Test
    public void nullValuesUseLegacyThenDefault() {
        assertTrue("新值为 null 时旧值优先",
                ConfigMigration.resolve(null, Boolean.TRUE, false));
        assertFalse("两个值都为 null 时使用默认值",
                ConfigMigration.resolve(null, null, false));
    }

    @Test
    public void groupNamesAreStable() {
        assertEquals("config", ConfigMigration.GROUP);
        assertEquals("features", ConfigMigration.LEGACY_GROUP);
    }
}
