package io.github.yigenhuobah.tiebaquiet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
 * UiFeature 编号、key、分组与默认值。
 *
 * key 是 remote prefs 两读一写共用的键，改了会连带断掉已保存的开关状态，必须锁死；
 * code 是统一功能编号，顺序与 FEATURE_ID_MAP.md 2.4 节 Tieba 表一致。
 * 默认值遵循统一策略：AD/UI 默认开，EN/PV/EX 默认关。
 */
public class UiFeatureTest {

    /** 统一编号格式：AD/UI/PV/EN/EX + 两位序号。 */
    private static final Pattern CODE_PATTERN = Pattern.compile("^(AD|UI|PV|EN|EX)-\\d{2}$");

    /** FEATURE_ID_MAP.md 2.4 节 Tieba 表：key → code，顺序即枚举顺序。 */
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
        map.put("default_channel", "EN-01");
        map.put("force_night", "EN-02");
        map.put("host_only", "EN-03");
        map.put("reverse_order", "EN-04");
        map.put("no_watermark", "EN-05");
        map.put("original_image", "EN-06");
        map.put("video_link", "EN-07");
        map.put("one_key_sign", "EN-08");
        map.put("clipboard_guard", "PV-01");
        map.put("personalized_ad_off", "PV-02");
        map.put("keep_alive_block", "PV-03");
        return map;
    }

    @Test
    public void twentyFourFeaturesPresent() {
        assertEquals("UiFeature 数量必须是 24", 24, UiFeature.values().length);
    }

    @Test
    public void keysMatchSpec() {
        Set<String> actual = new HashSet<>();
        for (UiFeature f : UiFeature.values()) {
            actual.add(f.key);
        }
        assertEquals(new HashSet<>(EXPECTED.keySet()), actual);
    }

    @Test
    public void codesMatchSpecAndEnumOrder() {
        assertEquals("枚举顺序必须与 FEATURE_ID_MAP 的 Tieba 表一致",
                new ArrayList<>(EXPECTED.keySet()), keysInEnumOrder());
        for (UiFeature f : UiFeature.values()) {
            assertEquals("编号与 FEATURE_ID_MAP 不一致: " + f.key,
                    EXPECTED.get(f.key), f.code);
        }
    }

    @Test
    public void codesAreUnique() {
        Set<String> seen = new HashSet<>();
        for (UiFeature f : UiFeature.values()) {
            assertTrue("code 重复: " + f.code, seen.add(f.code));
        }
        assertEquals(EXPECTED.size(), seen.size());
    }

    @Test
    public void keysAreUnique() {
        Set<String> seen = new HashSet<>();
        for (UiFeature f : UiFeature.values()) {
            assertTrue("key 重复: " + f.key, seen.add(f.key));
        }
        assertEquals(EXPECTED.size(), seen.size());
    }

    @Test
    public void codesUseUnifiedFormat() {
        for (UiFeature f : UiFeature.values()) {
            assertTrue("code 格式不合法: " + f.code, CODE_PATTERN.matcher(f.code).matches());
        }
    }

    @Test
    public void groupCountsMatchSpec() {
        Map<UiFeature.Group, Integer> counts = new EnumMap<>(UiFeature.Group.class);
        for (UiFeature f : UiFeature.values()) {
            Integer old = counts.get(f.group);
            counts.put(f.group, old == null ? 1 : old + 1);
        }
        assertEquals("AD 分组必须是 10 项", Integer.valueOf(10), counts.get(UiFeature.Group.AD));
        assertEquals("UI 分组必须是 3 项", Integer.valueOf(3), counts.get(UiFeature.Group.UI));
        assertEquals("EN 分组必须是 8 项", Integer.valueOf(8), counts.get(UiFeature.Group.EN));
        assertEquals("PV 分组必须是 3 项", Integer.valueOf(3), counts.get(UiFeature.Group.PV));
        assertFalse("TiebaQuiet 没有 EX 分组", counts.containsKey(UiFeature.Group.EX));
    }

    @Test
    public void adAndUiDefaultEnabled() {
        for (UiFeature f : UiFeature.values()) {
            if (f.group == UiFeature.Group.AD || f.group == UiFeature.Group.UI) {
                assertTrue(f.code + " " + f.key + " 应默认开启", f.defaultEnabled);
            }
        }
    }

    @Test
    public void enAndPvDefaultDisabled() {
        for (UiFeature f : UiFeature.values()) {
            if (f.group == UiFeature.Group.EN || f.group == UiFeature.Group.PV) {
                assertFalse(f.code + " " + f.key + " 应默认关闭", f.defaultEnabled);
            }
        }
    }

    @Test
    public void defaultEnabledMatchesGroupPolicy() {
        for (UiFeature f : UiFeature.values()) {
            boolean expected = f.group == UiFeature.Group.AD || f.group == UiFeature.Group.UI;
            assertEquals(f.code + " " + f.key + " 默认值与分组策略不符",
                    expected, f.defaultEnabled);
        }
    }

    private static List<String> keysInEnumOrder() {
        List<String> keys = new ArrayList<>();
        for (UiFeature f : UiFeature.values()) {
            keys.add(f.key);
        }
        return keys;
    }
}
