package io.github.yigenhuobah.tiebaquiet.feature.phase1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import io.github.yigenhuobah.tiebaquiet.FeatureRegistry;
import io.github.yigenhuobah.tiebaquiet.HookEnv;
import io.github.yigenhuobah.tiebaquiet.HookState;
import io.github.yigenhuobah.tiebaquiet.anchor.AnchorTable;

/**
 * 个人页注入（UI-03）的行为（纯 JVM）：
 * k()/getInnerWebView 已收编进 anchors.json（person_tab_web_k/tbwebview_inner），
 * 由 gen_anchors 按 APK 校验；安装期解析不到时必须显式 skip，不得 hook 后静默假成功；
 * 只有重试次数全部用尽才标记 skip，中途失败继续重试。
 */
public class Phase1FeaturesTest {

    @Test
    public void noSkipBeforeRetriesExhausted() {
        for (int attempt = 0; attempt < Phase1Features.INJECT_MAX_ATTEMPTS - 1; attempt++) {
            assertNull("第 " + attempt + " 次失败还应继续重试",
                    Phase1Features.profileCleanupSkipReason(attempt,
                            Phase1Features.INJECT_MAX_ATTEMPTS, false));
        }
    }

    @Test
    public void skipReasonAfterAllRetriesExhausted() {
        int last = Phase1Features.INJECT_MAX_ATTEMPTS - 1;
        assertEquals("重试用尽后必须给出 skip 文案，不能静默假成功",
                "重试用尽仍未注入（person_tab_web_k/tbwebview_inner）",
                Phase1Features.profileCleanupSkipReason(last,
                        Phase1Features.INJECT_MAX_ATTEMPTS, false));
    }

    @Test
    public void injectedNeverMarksSkip() {
        for (int attempt = 0; attempt < Phase1Features.INJECT_MAX_ATTEMPTS; attempt++) {
            assertNull(Phase1Features.profileCleanupSkipReason(attempt,
                    Phase1Features.INJECT_MAX_ATTEMPTS, true));
        }
    }

    /**
     * JVM 上 com.baidu.tieba.* 不可加载：注入锚点必然解析不到。
     * 安装器必须在此直接 markSkip 并返回 false，而不是照常 hook
     * person_tab_attach 后靠运行期反射失败“可见地”失效。
     */
    @Test
    public void installMarksSkipWhenInjectAnchorsUnavailable() {
        AnchorTable anchors = AnchorTable.load(Phase1Features.class.getClassLoader());
        assertNotNull("anchors.json 必须在测试 classpath 上", anchors);
        HookEnv env = new HookEnv(null, Phase1Features.class.getClassLoader(),
                anchors, null, null, null);

        assertFalse("注入锚点不可用时不得再 hook 假装功能可用",
                Phase1Features.installProfileCleanup(env));

        String reason = HookState.skipReason(FeatureRegistry.byKey("profile_cleanup"));
        assertNotNull("必须留下可见的 skip 原因供诊断页展示", reason);
        assertTrue("skip 原因必须点名缺失的锚点: " + reason,
                reason.contains("person_tab_web_k") && reason.contains("tbwebview_inner"));
    }
}
