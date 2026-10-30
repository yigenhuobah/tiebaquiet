package io.github.yigenhuobah.tiebaquiet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Feature 编号、key、分组与默认值。
 *
 * key 是 remote prefs 两读一写共用的键，改了会连带断掉已保存的开关状态，必须锁死；
 * code 是统一功能编号，顺序与 FEATURE_ID_MAP.md 2.4 节 Tieba 表及设置页一致。
 * 默认值遵循统一策略：AD/UI 默认开，EN/PV/EX 默认关。
 */
public class FeatureTest {

    /** 统一编号格式：AD/UI/PV/EN/EX + 两位序号。 */
    private static final Pattern CODE_PATTERN = Pattern.compile("^(AD|UI|PV|EN|EX)-\\d{2}$");

    /** FEATURE_ID_MAP.md 2.4 节 Tieba 表：key → code，顺序即注册表顺序。 */
    private static final Map<String, String> EXPECTED = buildExpected();

    private static Map<String, String> buildExpected() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("splash_no_ad", "AD-01");
        map.put("ad_request_block", "AD-02");
        map.put("feed_ad_filter", "AD-03");
        map.put("forum_promo_filter", "AD-04");
        map.put("live_card_filter", "AD-05");
        map.put("hot_card_filter", "AD-06");
        map.put("float_ad", "AD-07");
        map.put("yun_dialog", "AD-08");
        map.put("rec_forum", "AD-09");
        map.put("easter_egg", "AD-10");
        map.put("tab_red_dot", "UI-01");
        map.put("update_dialog", "UI-02");
        map.put("profile_cleanup", "UI-03");
        map.put("hide_store_tab", "UI-04");
        map.put("clipboard_guard", "PV-01");
        map.put("personalized_ad_off", "PV-02");
        map.put("keep_alive_block", "PV-03");
        map.put("default_channel", "EN-01");
        map.put("force_night", "EN-02");
        map.put("host_only", "EN-03");
        map.put("reverse_order", "EN-04");
        map.put("no_watermark", "EN-05");
        map.put("original_image", "EN-06");
        map.put("video_link", "EN-07");
        map.put("one_key_sign", "EN-08");
        return map;
    }

    @Test
    public void featureCountMatchesSpec() {
        assertEquals("Feature 数量必须是 25", 25, FeatureRegistry.all().size());
    }

    @Test
    public void keysMatchSpec() {
        Set<String> actual = new HashSet<>();
        for (Feature f : FeatureRegistry.all()) {
            actual.add(f.key);
        }
        assertEquals(new HashSet<>(EXPECTED.keySet()), actual);
    }

    @Test
    public void codesMatchSpecAndRegistryOrder() {
        assertEquals("注册表顺序必须与 FEATURE_ID_MAP 的 Tieba 表一致",
                new ArrayList<>(EXPECTED.keySet()), keysInRegistryOrder());
        for (Feature f : FeatureRegistry.all()) {
            assertEquals("编号与 FEATURE_ID_MAP 不一致: " + f.key,
                    EXPECTED.get(f.key), f.code);
        }
    }

    @Test
    public void codesAreUnique() {
        Set<String> seen = new HashSet<>();
        for (Feature f : FeatureRegistry.all()) {
            assertTrue("code 重复: " + f.code, seen.add(f.code));
        }
        assertEquals(EXPECTED.size(), seen.size());
    }

    @Test
    public void keysAreUnique() {
        Set<String> seen = new HashSet<>();
        for (Feature f : FeatureRegistry.all()) {
            assertTrue("key 重复: " + f.key, seen.add(f.key));
        }
        assertEquals(EXPECTED.size(), seen.size());
    }

    @Test
    public void codesUseUnifiedFormat() {
        for (Feature f : FeatureRegistry.all()) {
            assertTrue("code 格式不合法: " + f.code, CODE_PATTERN.matcher(f.code).matches());
        }
    }

    @Test
    public void groupCountsMatchSpec() {
        Map<Feature.Group, Integer> counts = new EnumMap<>(Feature.Group.class);
        for (Feature f : FeatureRegistry.all()) {
            Integer old = counts.get(f.group);
            counts.put(f.group, old == null ? 1 : old + 1);
        }
        assertEquals("AD 分组必须是 10 项", Integer.valueOf(10), counts.get(Feature.Group.AD));
        assertEquals("UI 分组必须是 4 项", Integer.valueOf(4), counts.get(Feature.Group.UI));
        assertEquals("PV 分组必须是 3 项", Integer.valueOf(3), counts.get(Feature.Group.PV));
        assertEquals("EN 分组必须是 8 项", Integer.valueOf(8), counts.get(Feature.Group.EN));
        assertFalse("TiebaQuiet 没有 EX 分组", counts.containsKey(Feature.Group.EX));
    }

    @Test
    public void groupsUseFixedOrder() {
        assertEquals("分组顺序必须是 AD → UI → PV → EN",
                java.util.Arrays.asList(Feature.Group.AD, Feature.Group.UI,
                        Feature.Group.PV, Feature.Group.EN),
                FeatureRegistry.groups());
    }

    @Test
    public void adAndUiDefaultEnabled() {
        for (Feature f : FeatureRegistry.all()) {
            if (f.group == Feature.Group.AD || f.group == Feature.Group.UI) {
                assertTrue(f.code + " " + f.key + " 应默认开启", f.defaultEnabled);
            }
        }
    }

    @Test
    public void pvAndEnDefaultDisabled() {
        for (Feature f : FeatureRegistry.all()) {
            if (f.group == Feature.Group.PV || f.group == Feature.Group.EN) {
                assertFalse(f.code + " " + f.key + " 应默认关闭", f.defaultEnabled);
            }
        }
    }

    @Test
    public void defaultEnabledMatchesGroupPolicy() {
        for (Feature f : FeatureRegistry.all()) {
            boolean expected = f.group == Feature.Group.AD || f.group == Feature.Group.UI;
            assertEquals(f.code + " " + f.key + " 默认值与分组策略不符",
                    expected, f.defaultEnabled);
        }
    }

    @Test
    public void noBlockedFeatures() {
        for (Feature f : FeatureRegistry.all()) {
            assertFalse(f.code + " 不应标记 blocked", f.blocked());
        }
    }

    @Test
    public void byKeyReturnsRegisteredFeature() {
        assertEquals("splash_no_ad", FeatureRegistry.byKey("splash_no_ad").key);
        assertNull("未知 key 必须返回 null", FeatureRegistry.byKey("not_a_feature"));
    }

    private static List<String> keysInRegistryOrder() {
        List<String> keys = new ArrayList<>();
        for (Feature f : FeatureRegistry.all()) {
            keys.add(f.key);
        }
        return keys;
    }
}
