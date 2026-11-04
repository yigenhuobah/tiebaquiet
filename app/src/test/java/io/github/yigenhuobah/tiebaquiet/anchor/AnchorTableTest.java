package io.github.yigenhuobah.tiebaquiet.anchor;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * JVM 方法签名解析：锚点 JSON 里的 {@code s} 字段格式为 JVM 描述符，
 * 解析错误会让所有功能静默跳过，因此这里覆盖原始类型、对象、数组与非法输入。
 */
public class AnchorTableTest {

    /** 字段锚点测试夹具：字段名/类型与 anchors.json 的 s 描述符对应。 */
    static final class FieldFixture {
        String name;
        int count;
    }

    private static ClassLoader loader() {
        return AnchorTable.class.getClassLoader();
    }

    @Test
    public void supportedVersionMatchesExactAndAllowsUnknown() {
        assertTrue(AnchorTable.isSupportedVersion("22.13.1.1", "22.13.1.1"));
        assertFalse(AnchorTable.isSupportedVersion("22.13.1.1", "22.11.0.0"));
        assertTrue("未知版本按 fail-open 处理",
                AnchorTable.isSupportedVersion("22.13.1.1", null));
    }

    @Test
    public void emptyParameterListForVoidReturn() {
        assertEquals("()V 不应产生参数",
                0, AnchorTable.parseParameterTypes("()V", loader()).length);
    }

    @Test
    public void parsesAllPrimitives() {
        assertArrayEquals("原始类型必须逐项映射",
                new Class<?>[]{boolean.class, byte.class, char.class, short.class,
                        int.class, long.class, float.class, double.class},
                AnchorTable.parseParameterTypes("(ZBCSIJFD)V", loader()));
    }

    @Test
    public void parsesObjectAndPrimitiveArray() {
        assertArrayEquals("对象、int 与一维数组",
                new Class<?>[]{String.class, int.class, boolean[].class},
                AnchorTable.parseParameterTypes("(Ljava/lang/String;I[Z)V", loader()));
    }

    @Test
    public void parsesObjectArrayAndMultiDimensionalArray() {
        assertArrayEquals("对象数组与二维数组",
                new Class<?>[]{String[].class, int[][].class},
                AnchorTable.parseParameterTypes("([Ljava/lang/String;[[I)V", loader()));
    }

    @Test
    public void rejectsMissingParenthesis() {
        try {
            AnchorTable.parseParameterTypes("Ljava/lang/String;", loader());
            fail("缺少左括号必须抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
    }

    @Test
    public void rejectsUnterminatedObjectType() {
        try {
            AnchorTable.parseParameterTypes("(Ljava/lang/String)V", loader());
            fail("对象类型缺少分号必须抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
    }

    @Test
    public void rejectsUnknownClass() {
        try {
            AnchorTable.parseParameterTypes("(Lnot/real/ClassName;)V", loader());
            fail("未知类必须抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
    }

    @Test
    public void parseReturnTypeHandlesVoidPrimitiveObjectAndArray() {
        assertEquals("()V 的返回类型是 void",
                void.class, AnchorTable.parseReturnType("()V", loader()));
        assertEquals("原始类型返回值",
                int.class, AnchorTable.parseReturnType("(I)I", loader()));
        assertEquals("对象返回值",
                String.class, AnchorTable.parseReturnType("(Z)Ljava/lang/String;", loader()));
        assertEquals("二维对象数组返回值",
                String[][].class, AnchorTable.parseReturnType("()[[Ljava/lang/String;", loader()));
    }

    @Test
    public void parseReturnTypeRejectsMalformedDescriptor() {
        assertBadReturnType("()Vgarbage");
        assertBadReturnType("()Ljava/lang/String");
        assertBadReturnType("I)V");
        assertBadReturnType(null);
        assertBadReturnType("()Z?bad");
    }

    private static void assertBadReturnType(String signature) {
        try {
            AnchorTable.parseReturnType(signature, loader());
            fail("非法返回类型必须抛 IllegalArgumentException: " + signature);
        } catch (IllegalArgumentException expected) {
            // 预期
        }
    }

    /** 成功解析：类、方法、签名与返回类型全部匹配。 */
    @Test
    public void resolveMethodSucceedsForRealMethod() {
        AnchorTable.Lookup<Method> lookup = AnchorTable.resolveMethod(loader(),
                "splash_no_ad", "java.lang.String", "charAt", "(I)C");
        assertNotNull("真实方法必须解析成功", lookup.value);
        assertNull("成功时不应带失败类别", lookup.failure);
        assertNull(lookup.error);
    }

    /** 失败原因：锚点类在目标 ClassLoader 中不存在。 */
    @Test
    public void resolveMethodReportsClassNotFound() {
        AnchorTable.Lookup<Method> lookup = AnchorTable.resolveMethod(loader(),
                "splash_no_ad", "not.real.Missing", "m", "()V");
        assertNull("fail-open：失败返回 null", lookup.value);
        assertEquals(AnchorTable.Failure.CLASS_NOT_FOUND, lookup.failure);
        assertTrue("必须保留原始异常类型", lookup.error instanceof ClassNotFoundException);
    }

    /** 失败原因：类存在但方法名不存在。 */
    @Test
    public void resolveMethodReportsMethodNotFound() {
        AnchorTable.Lookup<Method> lookup = AnchorTable.resolveMethod(loader(),
                "splash_no_ad", "java.lang.String", "noSuchMethodForTiebaQuietTest", "()V");
        assertNull(lookup.value);
        assertEquals(AnchorTable.Failure.METHOD_NOT_FOUND, lookup.failure);
        assertTrue(lookup.error instanceof NoSuchMethodException);
    }

    /** 失败原因：参数描述符非法或引用了不存在的类。 */
    @Test
    public void resolveMethodReportsSignatureMismatch() {
        AnchorTable.Lookup<Method> unknownParam = AnchorTable.resolveMethod(loader(),
                "splash_no_ad", "java.lang.String", "charAt", "(Lnot/real/Missing;)V");
        assertNull(unknownParam.value);
        assertEquals(AnchorTable.Failure.SIGNATURE_MISMATCH, unknownParam.failure);
        assertTrue(unknownParam.error instanceof IllegalArgumentException);

        AnchorTable.Lookup<Method> malformed = AnchorTable.resolveMethod(loader(),
                "splash_no_ad", "java.lang.String", "charAt", "not-a-signature");
        assertNull(malformed.value);
        assertEquals("描述符格式错误同样归为签名不符",
                AnchorTable.Failure.SIGNATURE_MISMATCH, malformed.failure);
    }

    /** 失败原因：同名同参但返回类型已变（混淆方法语义已变）。 */
    @Test
    public void resolveMethodReportsReturnTypeMismatch() {
        AnchorTable.Lookup<Method> lookup = AnchorTable.resolveMethod(loader(),
                "splash_no_ad", "java.lang.String", "charAt", "(I)Z");
        assertNull(lookup.value);
        assertEquals(AnchorTable.Failure.RETURN_TYPE_MISMATCH, lookup.failure);
        assertNull("返回类型不符不是异常路径", lookup.error);
    }

    /** clazz 锚点同样区分类未找到与成功。 */
    @Test
    public void resolveClassReportsSuccessAndClassNotFound() {
        AnchorTable.Lookup<Class<?>> ok = AnchorTable.resolveClass(loader(),
                "home_fragment", "java.lang.String");
        assertEquals(String.class, ok.value);
        assertNull(ok.failure);

        AnchorTable.Lookup<Class<?>> missing = AnchorTable.resolveClass(loader(),
                "home_fragment", "not.real.Missing");
        assertNull(missing.value);
        assertEquals(AnchorTable.Failure.CLASS_NOT_FOUND, missing.failure);
    }

    @Test
    public void parseTypeHandlesPrimitiveObjectAndArray() {
        assertEquals(String.class, AnchorTable.parseType("Ljava/lang/String;", loader()));
        assertEquals(int.class, AnchorTable.parseType("I", loader()));
        assertEquals(String[][].class,
                AnchorTable.parseType("[[Ljava/lang/String;", loader()));
        try {
            AnchorTable.parseType("()V", loader());
            fail("方法描述符不能当作字段类型");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
    }

    /** 字段锚点：沿继承链查找，缺失/类型不符分别归类。 */
    @Test
    public void resolveFieldReportsSuccessAndFailures() {
        String fixture = FieldFixture.class.getName();
        AnchorTable.Lookup<Field> ok = AnchorTable.resolveField(loader(),
                "home_tab_item_b", fixture, "name", "Ljava/lang/String;");
        assertNotNull("真实字段必须解析成功", ok.value);
        assertNull(ok.failure);

        AnchorTable.Lookup<Field> missing = AnchorTable.resolveField(loader(),
                "home_tab_item_b", fixture, "noSuchFieldForTiebaQuietTest", "I");
        assertNull(missing.value);
        assertEquals(AnchorTable.Failure.FIELD_NOT_FOUND, missing.failure);

        AnchorTable.Lookup<Field> wrongType = AnchorTable.resolveField(loader(),
                "home_tab_item_b", fixture, "count", "Ljava/lang/String;");
        assertNull(wrongType.value);
        assertEquals(AnchorTable.Failure.FIELD_TYPE_MISMATCH, wrongType.failure);

        AnchorTable.Lookup<Field> badType = AnchorTable.resolveField(loader(),
                "home_tab_item_b", fixture, "name", "()V");
        assertNull(badType.value);
        assertEquals(AnchorTable.Failure.SIGNATURE_MISMATCH, badType.failure);
    }

    /** WARN 文案必须带锚点 key 与异常类型，便于 logcat 定位是哪个锚点坏了。 */
    @Test
    public void failureMessageCarriesKeyAndExceptionType() {
        String message = AnchorTable.failureMessage("feed_ad_filter",
                AnchorTable.Failure.METHOD_NOT_FOUND, "m()V",
                new NoSuchMethodException("missing"));
        assertTrue("必须带锚点 key: " + message, message.contains("feed_ad_filter"));
        assertTrue("必须带英文失败类别: " + message, message.contains("method not found"));
        assertTrue("必须带异常类型: " + message,
                message.contains("java.lang.NoSuchMethodException"));
        assertTrue("logcat 文案必须是可打印 ASCII: " + message,
                message.matches("[\\x20-\\x7E]+"));
    }

    /** DESIGN.md §10：logcat 文案必须是英文，失败标签不能出现中文。 */
    @Test
    public void failureLabelsAreEnglishLogcatTokens() {
        for (AnchorTable.Failure failure : AnchorTable.Failure.values()) {
            assertTrue("失败标签必须是英文小写 token: " + failure.label,
                    failure.label.matches("[a-z][a-z ]*"));
        }
    }

    /**
     * 信息流会逐条调用 CardFilter → AnchorTable：同一缺失锚点必须只写一次 WARN，
     * 否则 logcat 被每帧的重复失败刷屏。
     */
    @Test
    public void repeatedMissingAnchorWarnsOnlyOnce() {
        AnchorTable table = AnchorTable.load(loader());
        assertNotNull("anchors.json 必须在测试 classpath 上", table);
        int before = table.warnedCount();

        assertNull(table.method("missing_anchor_for_log_test"));
        assertEquals("首次失败必须记录 WARN", before + 1, table.warnedCount());
        assertNull(table.method("missing_anchor_for_log_test"));
        assertEquals("重复解析同一缺失锚点不能重复写 WARN",
                before + 1, table.warnedCount());
    }

    /**
     * 日志实现抛异常时，锚点解析仍必须 fail-open 返回 null，而不是把日志异常抛给
     * method()/clazz() 的调用方。覆盖 resolveClass/resolveMethod 的失败路径，
     * 以及 method()/clazz() 外层 catch 再次调用 warn 的逃逸路径。
     */
    @Test
    public void throwingLogSinkStillReturnsNullOnResolveFailures() {
        AnchorTable.setLogSinkForTest((tag, message) -> {
            throw new IllegalStateException("log boom");
        });
        try {
            AnchorTable.Lookup<Method> missingClass = AnchorTable.resolveMethod(loader(),
                    "splash_no_ad", "not.real.Missing", "m", "()V");
            assertNull("resolveMethod 失败路径必须返回 null 而不是抛日志异常",
                    missingClass.value);
            assertEquals(AnchorTable.Failure.CLASS_NOT_FOUND, missingClass.failure);

            AnchorTable.Lookup<Method> missingMethod = AnchorTable.resolveMethod(loader(),
                    "splash_no_ad", "java.lang.String", "noSuchMethodForTiebaQuietTest", "()V");
            assertNull(missingMethod.value);
            assertEquals(AnchorTable.Failure.METHOD_NOT_FOUND, missingMethod.failure);

            AnchorTable.Lookup<Class<?>> missing = AnchorTable.resolveClass(loader(),
                    "home_fragment", "not.real.Missing");
            assertNull(missing.value);
            assertEquals(AnchorTable.Failure.CLASS_NOT_FOUND, missing.failure);

            AnchorTable.Lookup<Method> ok = AnchorTable.resolveMethod(loader(),
                    "splash_no_ad", "java.lang.String", "charAt", "(I)C");
            assertNotNull("成功解析不应受日志实现影响", ok.value);

            AnchorTable table = AnchorTable.load(loader());
            assertNotNull("anchors.json 必须在测试 classpath 上", table);
            assertNull("method() 不能在日志异常时逃逸",
                    table.method("missing_anchor_for_log_test"));
            assertNull("clazz() 不能在日志异常时逃逸",
                    table.clazz("missing_class_for_log_test"));
        } finally {
            AnchorTable.setLogSinkForTest(null);
        }
    }

    /** 锚点解析必须校验返回类型：同名同参但返回类型已变的方法判为锚点失效。 */
    @Test
    public void returnTypeMatchesRealMethodsAndRejectsChangedReturnType() throws Exception {
        Method charAt = String.class.getMethod("charAt", int.class);
        assertTrue("返回类型一致",
                AnchorTable.returnTypeMatches(charAt, "(I)C", loader()));
        assertFalse("同名同参但返回类型已变必须判失效",
                AnchorTable.returnTypeMatches(charAt, "(I)Z", loader()));
        assertFalse("同名同参但返回类型改成对象也必须判失效",
                AnchorTable.returnTypeMatches(charAt, "(I)Ljava/lang/String;", loader()));
        assertFalse("描述符非法按锚点失效处理",
                AnchorTable.returnTypeMatches(charAt, "(I)Vgarbage", loader()));
        assertFalse("method 为 null 直接判失效",
                AnchorTable.returnTypeMatches(null, "()V", loader()));
        assertTrue("hashCode 无参返回 int",
                AnchorTable.returnTypeMatches(Object.class.getMethod("hashCode"),
                        "()I", loader()));
        assertFalse("同名同参但返回类型改成 void 必须判失效",
                AnchorTable.returnTypeMatches(Object.class.getMethod("hashCode"),
                        "()V", loader()));
    }
}
