package io.github.yigenhuobah.tiebaquiet.feature;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * FeedCardSweeper 的纯 JVM 判定逻辑：
 * 只按卡片类型键/数据判断，普通帖子绝不命中；同一 adapter 的重复 schedule 只留一条链。
 *
 * <p>这些方法不触碰 Handler、RecyclerView 或 View，因此不需要 Robolectric。
 */
public class FeedCardSweeperTest {

    @Test
    public void normalNoteIsNeverHidden() {
        // 普通帖子/图片/带货卡的类型键都不应命中，即使正文里可能出现直播/热点字样。
        assertFalse(FeedCardSweeper.shouldHide("thread_detail", false, true, true));
        assertFalse(FeedCardSweeper.shouldHide("note", false, true, true));
        // 防止裸 contains("hot") 把 photo 误判为热点卡。
        assertFalse(FeedCardSweeper.shouldHide("photo", false, true, true));
        // 防止裸 contains("live") 把 delivery/olive 误判为直播卡。
        assertFalse(FeedCardSweeper.shouldHide("delivery_card", false, true, true));
        assertFalse(FeedCardSweeper.shouldHide("olive_oil", false, true, true));
        assertFalse(FeedCardSweeper.shouldHide(null, false, true, true));
        assertFalse(FeedCardSweeper.shouldHide("  ", false, true, true));
    }

    @Test
    public void liveCardIsHiddenByTypeKeyOrData() {
        assertTrue(FeedCardSweeper.shouldHide("live_card", false, true, false));
        assertTrue(FeedCardSweeper.shouldHide("feed_live_thread", false, true, false));
        assertTrue(FeedCardSweeper.shouldHide("LIVE_CARD", false, true, false));
        // typeKey 缺失时，数据层 isLiveCard 仍可确认直播卡。
        assertTrue(FeedCardSweeper.shouldHide(null, true, true, false));
        // 直播开关关闭时，即使数据是直播也不隐藏。
        assertFalse(FeedCardSweeper.shouldHide("live_card", true, false, true));
    }

    @Test
    public void hotCardIsHiddenOnlyWhenEnabled() {
        assertTrue(FeedCardSweeper.shouldHide("hot_topic_card", false, false, true));
        assertTrue(FeedCardSweeper.shouldHide("hotTopicCard", false, false, true));
        assertTrue(FeedCardSweeper.shouldHide("realtime_hot", false, false, true));
        assertFalse(FeedCardSweeper.shouldHide("hot_topic_card", false, true, false));
    }

    @Test
    public void duplicateScheduleIsDeduped() {
        Object adapter = new Object();
        try {
            assertTrue("首次 schedule 应启动新链",
                    FeedCardSweeper.beginSweep(adapter, true, false));
            assertFalse("同一 adapter 的第二次 schedule 不得再启动链",
                    FeedCardSweeper.beginSweep(adapter, false, true));

            FeedCardSweeper.SweepState state = FeedCardSweeper.stateOf(adapter);
            assertNotNull(state);
            assertTrue("重复 schedule 应合并直播策略", state.live);
            assertTrue("重复 schedule 应合并热点策略", state.hot);
            assertTrue("合并后仍然只有一条已调度链", state.scheduled);
        } finally {
            FeedCardSweeper.endSweep(adapter, FeedCardSweeper.stateOf(adapter));
        }
        assertNull("链结束后必须释放 adapter 状态", FeedCardSweeper.stateOf(adapter));
        assertTrue("链结束后允许重新调度",
                FeedCardSweeper.beginSweep(adapter, true, false));
        FeedCardSweeper.endSweep(adapter, FeedCardSweeper.stateOf(adapter));
    }

    @Test
    public void differentAdaptersKeepIndependentChains() {
        Object first = new Object();
        Object second = new Object();
        try {
            assertTrue(FeedCardSweeper.beginSweep(first, true, false));
            assertTrue("不同 adapter 必须各自拥有独立链",
                    FeedCardSweeper.beginSweep(second, true, false));
        } finally {
            FeedCardSweeper.endSweep(first, FeedCardSweeper.stateOf(first));
            FeedCardSweeper.endSweep(second, FeedCardSweeper.stateOf(second));
        }
    }

    @Test
    public void emptyPolicyDoesNotCreateState() {
        Object adapter = new Object();
        assertFalse(FeedCardSweeper.beginSweep(adapter, false, false));
        assertNull(FeedCardSweeper.stateOf(adapter));
        assertFalse(FeedCardSweeper.beginSweep(null, true, true));
    }
}
