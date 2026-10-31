package io.github.yigenhuobah.tiebaquiet.feature;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * CardFilter 的直播类型键判定（纯 JVM）：
 * 必须与 FeedCardSweeper.isLiveTypeKey 共用同一份明确 token 白名单，
 * 含 live 子串的普通类型键（delivery/olive）绝不能命中，
 * 否则 filterLive 会把它们从 setList 数据中整条删除。
 */
public class CardFilterTest {

    @Test
    public void liveTypeKeysAreRecognized() {
        assertTrue(CardFilter.isLiveTypeKey("live"));
        assertTrue(CardFilter.isLiveTypeKey("live_card"));
        assertTrue(CardFilter.isLiveTypeKey("feed_live_thread"));
        assertTrue(CardFilter.isLiveTypeKey("LIVE_CARD"));
        assertTrue(CardFilter.isLiveTypeKey("ala_live_room"));
    }

    @Test
    public void typeKeysMerelyContainingLiveAreNotLive() {
        assertFalse("delivery 含 live 子串，但不是直播卡",
                CardFilter.isLiveTypeKey("delivery_card"));
        assertFalse("olive 含 live 子串，但不是直播卡",
                CardFilter.isLiveTypeKey("olive_oil"));
        assertFalse("liverpool 类词不得命中",
                CardFilter.isLiveTypeKey("liverpool_topic"));
        assertFalse(CardFilter.isLiveTypeKey(null));
        assertFalse(CardFilter.isLiveTypeKey("  "));
        assertFalse(CardFilter.isLiveTypeKey("thread_detail"));
    }

    /** 与清扫器的白名单保持同一份：两边判定必须一致。 */
    @Test
    public void agreesWithSweeperWhitelist() {
        for (String key : new String[]{"live_card", "delivery_card", "olive_oil",
                "hot_topic_card", "thread_detail", null}) {
            assertTrue("CardFilter 与 FeedCardSweeper 判定不一致: " + key,
                    CardFilter.isLiveTypeKey(key)
                            == FeedCardSweeper.isLiveTypeKey(key));
        }
    }
}
