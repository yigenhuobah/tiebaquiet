package io.github.yigenhuobah.tiebaquiet.feature.phase2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 一键签到接收器注册通道的重试语义（纯 JVM）：
 * Context 未就绪与注册抛异常都必须退避重试，只有次数用尽才放弃——
 * 发送端冷启动只发一次广播且无回执，注册一旦永久放弃，签到请求就被静默丢弃。
 */
public class SignInFeatureTest {

    /** 注册抛异常（权限被拦等）不得像旧实现那样直接 return 放弃。 */
    @Test
    public void registerFailureIsRetriedUntilSuccess() throws Exception {
        final int[] calls = {0};
        assertTrue(SignInFeature.registerWithRetry(new SignInFeature.RegisterAttempt() {
            @Override
            public boolean run() {
                calls[0]++;
                if (calls[0] < 3) {
                    throw new IllegalStateException("receiver register failed");
                }
                return true;
            }
        }, 1L, SignInFeature.REGISTER_ATTEMPTS));
        assertEquals("失败后必须重试到成功", 3, calls[0]);
    }

    /** Context 未就绪：与旧实现一致，退避重试直到拿到；用尽后返回 false。 */
    @Test
    public void contextUnavailableKeepsRetrying() throws Exception {
        final int[] calls = {0};
        assertFalse("一直拿不到 Context 时不能误报成功",
                SignInFeature.registerWithRetry(new SignInFeature.RegisterAttempt() {
                    @Override
                    public boolean run() {
                        calls[0]++;
                        return false; // HostApp.context() == null
                    }
                }, 1L, 5));
        assertEquals("每次未就绪都要重试，不得提前放弃", 5, calls[0]);
    }

    @Test
    public void givesUpOnlyAfterAllAttemptsExhausted() throws Exception {
        final int[] calls = {0};
        assertFalse("全部次数用尽才返回 false",
                SignInFeature.registerWithRetry(new SignInFeature.RegisterAttempt() {
                    @Override
                    public boolean run() {
                        calls[0]++;
                        return false;
                    }
                }, 1L, 4));
        assertEquals("不得提前放弃", 4, calls[0]);
    }

    /** 成功即停：不再多发一次注册调用。 */
    @Test
    public void firstSuccessStopsTheLoop() throws Exception {
        final int[] calls = {0};
        assertTrue(SignInFeature.registerWithRetry(new SignInFeature.RegisterAttempt() {
            @Override
            public boolean run() {
                calls[0]++;
                return true;
            }
        }, 1L, SignInFeature.REGISTER_ATTEMPTS));
        assertEquals(1, calls[0]);
    }
}
