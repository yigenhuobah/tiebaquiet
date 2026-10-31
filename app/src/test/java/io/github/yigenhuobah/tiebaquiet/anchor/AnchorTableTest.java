package io.github.yigenhuobah.tiebaquiet.anchor;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.lang.reflect.Method;

/**
 * JVM 方法签名解析：锚点 JSON 里的 {@code s} 字段格式为 JVM 描述符，
 * 解析错误会让所有功能静默跳过，因此这里覆盖原始类型、对象、数组与非法输入。
 */
public class AnchorTableTest {

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
