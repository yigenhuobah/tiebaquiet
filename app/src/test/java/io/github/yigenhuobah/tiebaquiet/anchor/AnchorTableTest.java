package io.github.yigenhuobah.tiebaquiet.anchor;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * JVM 方法签名解析：锚点 JSON 里的 {@code s} 字段格式为 JVM 描述符，
 * 解析错误会让所有功能静默跳过，因此这里覆盖原始类型、对象、数组与非法输入。
 */
public class AnchorTableTest {

    private static ClassLoader loader() {
        return AnchorTable.class.getClassLoader();
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
}
