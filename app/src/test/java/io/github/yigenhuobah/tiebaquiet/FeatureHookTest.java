package io.github.yigenhuobah.tiebaquiet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

/**
 * Feature 包装层的错误计数契约：hook 体抛异常必须记 error 且不计 hit，
 * 异常本身仍原样抛出（交给 PROTECTIVE 模式兜底，fail-open 不变）。
 * 宿主 chain.proceed 抛出的异常不是 hook 体错误，不能计入 error。
 *
 * <p>每个用例构造独立 Feature（HookState 以 Feature 实例为键），
 * 避免污染 FeatureRegistry 中的真实功能状态。
 */
public class FeatureHookTest {

    private static final String LABEL = "test_hook";

    /** 每个用例使用独立 key：HookState 按 Feature.key 分区，共享 key 会互相污染计数。 */
    private static final java.util.concurrent.atomic.AtomicInteger FEATURE_SEQ =
            new java.util.concurrent.atomic.AtomicInteger();

    private static Feature feature() {
        return new Feature("AD-01", "test_hook_error_count_" + FEATURE_SEQ.incrementAndGet(),
                0, 0, true, Feature.Group.AD, env -> true);
    }

    /**
     * wrapHit 是私有实现细节，但“异常记 error、成功才记 hit”是本次修复的核心契约，
     * 因此用反射直接驱动。env 传 null：计数先抬到日志采样阈值以上，包装层不会访问 env。
     */
    private static XposedInterface.Hooker wrapper(Feature f, XposedInterface.Hooker delegate)
            throws Exception {
        return wrapper(f, null, delegate);
    }

    private static XposedInterface.Hooker wrapper(Feature f, HookEnv env,
                                                  XposedInterface.Hooker delegate)
            throws Exception {
        Method wrap = Feature.class.getDeclaredMethod("wrapHit", HookEnv.class,
                String.class, XposedInterface.Hooker.class);
        wrap.setAccessible(true);
        return (XposedInterface.Hooker) wrap.invoke(f, env, LABEL, delegate);
    }

    @Test
    public void hookBodyExceptionCountsErrorWithoutHit() throws Throwable {
        Feature f = feature();
        for (int i = 0; i < 5; i++) {
            HookState.hit(f);
            HookState.error(f);
        }
        long baseHits = HookState.hitCount(f);
        long baseErrors = HookState.errorCount(f);
        IllegalStateException boom = new IllegalStateException("boom");
        XposedInterface.Hooker wrapper = wrapper(f, chain -> {
            throw boom;
        });

        try {
            wrapper.intercept(null);
            fail("hook 体异常必须原样抛出，交给 PROTECTIVE 模式兜底");
        } catch (IllegalStateException e) {
            assertSame("必须抛出原始异常", boom, e);
        }
        assertEquals("异常必须计入 error", baseErrors + 1L, HookState.errorCount(f));
        assertEquals("异常不能计入 hit", baseHits, HookState.hitCount(f));
    }

    @Test
    public void hookBodySuccessCountsHitWithoutError() throws Throwable {
        Feature f = feature();
        for (int i = 0; i < 5; i++) {
            HookState.hit(f);
            HookState.error(f);
        }
        long baseHits = HookState.hitCount(f);
        long baseErrors = HookState.errorCount(f);
        XposedInterface.Hooker wrapper = wrapper(f, chain -> "ok");

        assertEquals("ok", wrapper.intercept(null));
        assertEquals("成功必须计入 hit", baseHits + 1L, HookState.hitCount(f));
        assertEquals("成功不能计入 error", baseErrors, HookState.errorCount(f));
    }

    /**
     * after() 的 chain.proceed() 调用的是宿主方法：它抛出的异常不是 hook 体错误，
     * 不能计入 err（否则宿主偶发异常会误报失败横幅）；异常本身必须原样抛给
     * PROTECTIVE 模式，fail-open 行为不变。
     */
    @Test
    public void afterProceedHostExceptionIsNotCountedAsHookError() throws Throwable {
        Feature f = feature();
        for (int i = 0; i < 5; i++) {
            HookState.hit(f);
            HookState.error(f);
        }
        long baseHits = HookState.hitCount(f);
        long baseErrors = HookState.errorCount(f);
        IllegalStateException host = new IllegalStateException("host");
        XposedInterface.Hooker delegate = Feature.afterHooker(f, null, LABEL,
                result -> result);
        XposedInterface.Hooker wrapper = wrapper(f, delegate);

        try {
            wrapper.intercept(new ThrowingChain(host));
            fail("宿主异常必须原样抛出，交给 PROTECTIVE 模式兜底");
        } catch (IllegalStateException e) {
            assertSame("必须抛出原始宿主异常", host, e);
        }
        assertEquals("宿主异常不能计入 hook error", baseErrors, HookState.errorCount(f));
        assertEquals("宿主异常不能计入 hit", baseHits, HookState.hitCount(f));
    }

    /** setList 类 hook 使用带替换参数的 proceed 重载，同样不能把宿主异常算作 hook 错误。 */
    @Test
    public void proceedWithArgsHostExceptionIsNotCountedAsHookError() throws Throwable {
        Feature f = feature();
        long baseHits = HookState.hitCount(f);
        long baseErrors = HookState.errorCount(f);
        IllegalStateException host = new IllegalStateException("host-args");
        XposedInterface.Hooker wrapper = wrapper(f,
                chain -> Feature.proceed(chain, new Object[]{Boolean.TRUE}));

        try {
            wrapper.intercept(new ThrowingChain(host));
            fail("宿主异常必须原样抛出");
        } catch (IllegalStateException e) {
            assertSame(host, e);
        }
        assertEquals("宿主异常不能计入 hook error", baseErrors, HookState.errorCount(f));
        assertEquals("宿主异常不能计入 hit", baseHits, HookState.hitCount(f));
    }

    /**
     * after() 的 transform 抛异常只回退原结果并记 WARN，不能计入 err：
     * err&gt;0 会让 ReportProtocol.featureFailed 把已安装且宿主调用成功的功能
     * 永久判失败并弹横幅。异常本身不外抛，PROTECTIVE/fail-open 语义不变。
     */
    @Test
    public void transformExceptionFallsBackWithoutPermanentFailure() throws Throwable {
        Feature f = feature();
        long baseHits = HookState.hitCount(f);
        long baseErrors = HookState.errorCount(f);
        Object original = new Object();
        HookEnv env = envWithNoopLogger();
        XposedInterface.Hooker delegate = Feature.afterHooker(f, env, LABEL,
                result -> {
                    throw new IllegalStateException("transform boom");
                });
        XposedInterface.Hooker wrapper = wrapper(f, env, delegate);

        assertEquals("transform 异常必须回退原结果", original,
                wrapper.intercept(new FixedChain(original)));
        assertEquals("transform 异常不能计入 err", baseErrors, HookState.errorCount(f));
        assertEquals("宿主调用成功仍算一次命中", baseHits + 1L, HookState.hitCount(f));
        assertFalse("瞬时 transform 异常不能让功能永久判失败",
                ReportProtocol.featureFailed(f.key, "", false,
                        HookState.hitCount(f), HookState.errorCount(f)));
    }

    /**
     * transform 失败路径会调用 env.module.log；JVM 上 XposedModule 未 attach 时
     * 直接调用会 NPE，因此构造一个 attach 了空实现 XposedInterface 的真实 module。
     */
    private static HookEnv envWithNoopLogger() {
        XposedInterface base = (XposedInterface) Proxy.newProxyInstance(
                FeatureHookTest.class.getClassLoader(),
                new Class<?>[]{XposedInterface.class},
                (proxy, method, args) -> {
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) {
                        return false;
                    }
                    if (type == int.class) {
                        return 0;
                    }
                    if (type == long.class) {
                        return 0L;
                    }
                    if (type == float.class) {
                        return 0f;
                    }
                    if (type == double.class) {
                        return 0d;
                    }
                    if (type == short.class) {
                        return (short) 0;
                    }
                    if (type == byte.class) {
                        return (byte) 0;
                    }
                    if (type == char.class) {
                        return (char) 0;
                    }
                    return null;
                });
        TiebaQuietModule module = new TiebaQuietModule();
        module.attachFramework(base, () -> { });
        return new HookEnv(module, FeatureHookTest.class.getClassLoader(), null, null, null,
                "test");
    }

    /** 只覆盖 proceed：afterHooker 在 proceed 处抛宿主异常，其余方法不应被调用。 */
    private static final class ThrowingChain implements XposedInterface.Chain {
        private final Throwable error;

        ThrowingChain(Throwable error) {
            this.error = error;
        }

        @Override
        public Object proceed() throws Throwable {
            throw error;
        }

        @Override
        public Object proceed(Object[] args) throws Throwable {
            throw error;
        }

        @Override
        public Object proceedWith(Object arg) throws Throwable {
            throw error;
        }

        @Override
        public Object proceedWith(Object arg, Object[] args) throws Throwable {
            throw error;
        }

        @Override
        public Executable getExecutable() {
            return null;
        }

        @Override
        public Object getThisObject() {
            return null;
        }

        @Override
        public List getArgs() {
            return Collections.emptyList();
        }

        @Override
        public Object getArg(int index) {
            throw new IndexOutOfBoundsException();
        }
    }

    /** 返回固定结果的 chain：用于驱动 afterHooker 成功调用宿主后的 transform 路径。 */
    private static final class FixedChain implements XposedInterface.Chain {
        private final Object result;

        FixedChain(Object result) {
            this.result = result;
        }

        @Override
        public Object proceed() {
            return result;
        }

        @Override
        public Object proceed(Object[] args) {
            return result;
        }

        @Override
        public Object proceedWith(Object arg) {
            return result;
        }

        @Override
        public Object proceedWith(Object arg, Object[] args) {
            return result;
        }

        @Override
        public Executable getExecutable() {
            return null;
        }

        @Override
        public Object getThisObject() {
            return null;
        }

        @Override
        public List getArgs() {
            return Collections.emptyList();
        }

        @Override
        public Object getArg(int index) {
            throw new IndexOutOfBoundsException();
        }
    }
}
