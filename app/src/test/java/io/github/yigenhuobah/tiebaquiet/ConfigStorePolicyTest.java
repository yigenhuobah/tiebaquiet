package io.github.yigenhuobah.tiebaquiet;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import io.github.yigenhuobah.core.ConfigPolicy;

/**
 * ConfigStore 的纯决策逻辑：remote 可用性、legacy 独立兜底、commit 结果与 store 复用。
 *
 * <p>这些分支决定“开关是否真的写进框架存储”，Android 胶水层无法用纯 JVM 覆盖，
 * 因此把判定抽到 {@link ConfigStorePolicy} 后在这里锁死。
 */
public class ConfigStorePolicyTest {

    @Test
    public void remoteUnavailableRejectsWrite() {
        assertFalse("两个 remote group 都不可用时必须拒绝写入",
                ConfigStorePolicy.canWrite(false, false));
    }

    @Test
    public void legacyGroupAloneStillAllowsWrite() {
        assertTrue("新 group 绑定失败时旧 group 仍可独立兜底",
                ConfigStorePolicy.canWrite(false, true));
        assertTrue("新 group 可用时当然允许写入",
                ConfigStorePolicy.canWrite(true, false));
        assertTrue(ConfigStorePolicy.canWrite(true, true));
    }

    @Test
    public void staleNewGroupValueShadowingLegacyWriteIsReportedAsFailure() {
        assertFalse("新 group 残留旧值会压过刚写进旧 group 的新值，必须报告失败让 UI 回滚",
                ConfigStorePolicy.committed(false, true, true));
    }

    @Test
    public void legacyFallbackCountsWhenNewGroupNeverHeldKey() {
        assertTrue("新 group 从未持有该 key 时旧 group 成功即目标可读",
                ConfigStorePolicy.committed(false, true, false));
    }

    @Test
    public void newGroupCommitAlwaysWins() {
        assertTrue(ConfigStorePolicy.committed(true, true, true));
        assertTrue("新 group 写成功后旧 group 结果不影响目标读取",
                ConfigStorePolicy.committed(true, false, true));
    }

    @Test
    public void bothGroupsFailingIsReportedAsFailure() {
        assertFalse(ConfigStorePolicy.committed(false, false, true));
        assertFalse(ConfigStorePolicy.committed(false, false, false));
    }

    @Test
    public void storeReuseDependsOnServiceIdentity() {
        Object service = new Object();
        assertTrue("同一 service 实例且已有 store 时复用",
                ConfigStorePolicy.reuseStore(true, service, service));
        assertFalse("service 变化时必须重建",
                ConfigStorePolicy.reuseStore(true, service, new Object()));
        assertFalse("尚无 store 时必须创建",
                ConfigStorePolicy.reuseStore(false, service, service));
        assertFalse("store 与 service 都为空时仍需创建",
                ConfigStorePolicy.reuseStore(false, null, null));
    }

    /**
     * 迁移守卫：模块 facade 的每个布尔组合都必须与 core {@link ConfigPolicy}
     * 完全一致；若有人以后在模块里另写一套判定，本用例立刻失败。
     */
    @Test
    public void facadeMatchesCoreDecisionTable() {
        Object service = new Object();
        Object other = new Object();
        for (int mask = 0; mask < 8; mask++) {
            boolean a = (mask & 1) != 0;
            boolean b = (mask & 2) != 0;
            boolean c = (mask & 4) != 0;
            assertTrue("canWrite(" + a + "," + b + ")",
                    ConfigPolicy.canWrite(a, b) == ConfigStorePolicy.canWrite(a, b));
            assertTrue("committed(" + a + "," + b + "," + c + ")",
                    ConfigPolicy.committed(a, b, c) == ConfigStorePolicy.committed(a, b, c));
        }
        assertTrue(ConfigPolicy.reuseStore(true, service, service)
                == ConfigStorePolicy.reuseStore(true, service, service));
        assertTrue(ConfigPolicy.reuseStore(true, service, other)
                == ConfigStorePolicy.reuseStore(true, service, other));
        assertTrue(ConfigPolicy.reuseStore(false, service, service)
                == ConfigStorePolicy.reuseStore(false, service, service));
    }
}
