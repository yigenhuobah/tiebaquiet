package io.github.yigenhuobah.tiebaquiet.anchor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.yigenhuobah.tiebaquiet.Feature;
import io.github.yigenhuobah.tiebaquiet.FeatureRegistry;

/**
 * 锚点契约（纯 JVM）：anchors.json、生成的 AnchorKeys.java 与 hook 源码三方一致。
 *
 * <p>覆盖：常量 ↔ 表双向一致、源码引用的常量存在、遗留的字面量 key 仍在表里、
 * 每个锚点都有 feature/critical/note，且 feature 是真实功能编号。
 * 这样锚点 key 改名只会在编译期/单测失败，而不是运行时静默 fail-open。
 *
 * <p>Android 单测里的 org.json 是 stub（returnDefaultValues），这里用最小 JSON
 * 解析器读取资源，保证契约在纯 JVM 下真实生效。
 */
public class AnchorContractTest {

    private static Map<String, Object> loadAnchors() throws Exception {
        InputStream stream = AnchorContractTest.class.getClassLoader()
                .getResourceAsStream("anchors.json");
        assertNotNull("anchors.json 必须在测试 classpath 上", stream);
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                text.append(buffer, 0, read);
            }
        }
        Object parsed = new JsonParser(text.toString()).parse();
        assertTrue("anchors.json 顶层必须是对象", parsed instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> json = (Map<String, Object>) parsed;
        return json;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        assertTrue("期望 JSON 对象，实际是 " + value, value instanceof Map);
        return (Map<String, Object>) value;
    }

    private static Set<String> tableKeys(Map<String, Object> json) {
        Set<String> keys = new HashSet<>();
        keys.addAll(object(json.get("anchors")).keySet());
        keys.addAll(object(json.get("classes")).keySet());
        return keys;
    }

    /** 与 tools/gen_anchors.py 的 java_constant_name 保持同一规则。 */
    private static String constantName(String key) {
        return key.replaceAll("[^0-9A-Za-z]+", "_").toUpperCase();
    }

    /** AnchorKeys 的常量名 → 常量值（锚点 key）。 */
    private static Map<String, String> anchorKeys() throws Exception {
        Map<String, String> out = new HashMap<>();
        for (Field field : AnchorKeys.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (!Modifier.isStatic(modifiers)) {
                continue;
            }
            assertTrue("AnchorKeys." + field.getName() + " 必须是 public",
                    Modifier.isPublic(modifiers));
            assertTrue("AnchorKeys." + field.getName() + " 必须是 final",
                    Modifier.isFinal(modifiers));
            assertEquals("AnchorKeys." + field.getName() + " 必须是 String",
                    String.class, field.getType());
            field.setAccessible(true);
            out.put(field.getName(), (String) field.get(null));
        }
        return out;
    }

    @Test
    public void schemaAndMetadataAreVersioned() throws Exception {
        Map<String, Object> json = loadAnchors();
        assertEquals("schemaVersion 必须为 1", 1L, json.get("schemaVersion"));
        assertEquals("generatedBy 必须指向生成器",
                "tools/gen_anchors.py", json.get("generatedBy"));
        assertTrue("anchors 段必须是对象", json.get("anchors") instanceof Map);
        assertTrue("classes 段必须是对象", json.get("classes") instanceof Map);
    }

    /**
     * 数量守卫：新增/删除锚点时必须同步 README 的计数描述。
     * 不改这里就让单测失败，避免 README 停留在旧数字（2026-11-06 前为 57+7）。
     */
    @Test
    public void anchorCountMatchesExpected() throws Exception {
        Map<String, Object> json = loadAnchors();
        assertEquals("anchors 数量变化时必须同步 README/check-mounts 期望", 63,
                object(json.get("anchors")).size());
        assertEquals("classes 数量变化时必须同步 README", 13,
                object(json.get("classes")).size());
    }

    @Test
    public void anchorKeysAndTableAreBidirectional() throws Exception {
        Map<String, Object> json = loadAnchors();
        Set<String> keys = tableKeys(json);
        Map<String, String> constants = anchorKeys();

        Set<String> constantValues = new HashSet<>(constants.values());
        for (String key : keys) {
            assertTrue("anchors.json 的 key 缺少 AnchorKeys 常量：" + key,
                    constantValues.contains(key));
            assertTrue("常量名不符合生成规则: " + key + " -> " + constantName(key),
                    constants.containsKey(constantName(key)));
        }
        for (Map.Entry<String, String> entry : constants.entrySet()) {
            assertTrue("AnchorKeys." + entry.getKey() + " 指向表中不存在的 key："
                            + entry.getValue(),
                    keys.contains(entry.getValue()));
        }
    }

    @Test
    public void everyAnchorHasFeatureCriticalAndNote() throws Exception {
        Map<String, Object> json = loadAnchors();
        Set<String> featureCodes = new HashSet<>();
        for (Feature feature : FeatureRegistry.all()) {
            featureCodes.add(feature.code);
        }

        for (Map.Entry<String, Object> item : object(json.get("anchors")).entrySet()) {
            String key = item.getKey();
            Map<String, Object> entry = object(item.getValue());
            for (String field : new String[]{"c", "m", "s", "feature", "note"}) {
                Object value = entry.get(field);
                assertTrue("锚点 " + key + " 缺少 " + field,
                        value instanceof String && !((String) value).isEmpty());
            }
            String feature = (String) entry.get("feature");
            assertTrue("锚点 " + key + " 的 feature 不是已知功能编号：" + feature,
                    featureCodes.contains(feature));
            assertTrue("锚点 " + key + " 的 critical 必须是布尔值",
                    entry.get("critical") instanceof Boolean);
        }
    }

    @Test
    public void sourceAnchorKeysReferencesExist() throws Exception {
        Set<String> constants = anchorKeys().keySet();
        Pattern reference = Pattern.compile("\\bAnchorKeys\\.([A-Za-z_][A-Za-z0-9_]*)");
        boolean sawReference = false;

        for (Path source : javaSources()) {
            String text = read(source);
            Matcher matcher = reference.matcher(text);
            while (matcher.find()) {
                sawReference = true;
                String name = matcher.group(1);
                assertTrue("源码引用了不存在的 AnchorKeys." + name + "：" + source,
                        constants.contains(name));
            }
        }
        assertTrue("源码中应至少有一处 AnchorKeys 引用（生成器/契约失效？）", sawReference);
    }

    /** 旧写法 anchors.method("key")/clazz("key")/field("key") 必须仍能在表里找到。 */
    @Test
    public void legacyLiteralAnchorKeysExistInTable() throws Exception {
        Set<String> keys = tableKeys(loadAnchors());
        Pattern literal = Pattern.compile(
                "\\banchors\\s*\\.\\s*(?:method|clazz|field)\\s*\\(\\s*\"([^\"]+)\"\\s*\\)");
        for (Path source : javaSources()) {
            Matcher matcher = literal.matcher(read(source));
            while (matcher.find()) {
                String key = matcher.group(1);
                assertTrue("源码字面量锚点不在表里：" + key + "（" + source + "）",
                        keys.contains(key));
            }
        }
    }

    /** method/clazz/field 访问器必须使用对应段：method→anchors，clazz→classes，field→字段锚点。 */
    @Test
    public void anchorAccessorsUseCorrectSections() throws Exception {
        Map<String, Object> json = loadAnchors();
        Map<String, Object> anchors = object(json.get("anchors"));
        Map<String, Object> classes = object(json.get("classes"));
        Map<String, String> constants = anchorKeys();
        Set<String> fieldKeys = new HashSet<>();
        for (Map.Entry<String, Object> entry : anchors.entrySet()) {
            Object signature = object(entry.getValue()).get("s");
            if (signature instanceof String && !((String) signature).startsWith("(")) {
                fieldKeys.add(entry.getKey());
            }
        }

        Pattern accessor = Pattern.compile(
                "\\banchors\\s*\\.\\s*(method|clazz|field)\\s*\\(\\s*AnchorKeys\\.([A-Za-z0-9_]+)\\s*\\)");
        for (Path source : javaSources()) {
            Matcher matcher = accessor.matcher(read(source));
            while (matcher.find()) {
                String kind = matcher.group(1);
                String constant = matcher.group(2);
                String key = constants.get(constant);
                assertNotNull("引用了不存在的 AnchorKeys." + constant + "：" + source, key);
                if ("method".equals(kind) || "field".equals(kind)) {
                    assertTrue("anchors." + kind + " 使用了非对应段 key：" + key + "（"
                                    + source + "）",
                            "method".equals(kind) ? anchors.containsKey(key)
                                    : fieldKeys.contains(key));
                } else {
                    assertTrue("anchors.clazz 使用了 classes 段之外的 key：" + key + "（"
                                    + source + "）", classes.containsKey(key));
                }
            }
        }
    }

    @Test
    public void constantNamesMatchGeneratedRule() throws Exception {
        for (Map.Entry<String, String> entry : anchorKeys().entrySet()) {
            assertEquals("常量名必须是 key 的大写下划线形式",
                    constantName(entry.getValue()), entry.getKey());
        }
    }

    /** 从测试工作目录向上找到 app/src/main/java（Gradle 单测 cwd 多为模块目录）。 */
    private static Path mainSourceRoot() {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            Path direct = dir.resolve("src").resolve("main").resolve("java");
            if (Files.isDirectory(direct)) {
                return direct;
            }
            Path module = dir.resolve("app").resolve("src").resolve("main").resolve("java");
            if (Files.isDirectory(module)) {
                return module;
            }
            dir = dir.getParent();
        }
        fail("找不到 app/src/main/java（cwd=" + System.getProperty("user.dir") + "）");
        return null;
    }

    private static List<Path> javaSources() throws Exception {
        List<Path> out = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(mainSourceRoot())) {
            stream.filter(path -> path.toString().endsWith(".java")).forEach(out::add);
        }
        return out;
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    /** 最小 JSON 解析器：对象/数组/字符串/数字/布尔/null，足够覆盖 anchors.json。 */
    static final class JsonParser {
        private final String text;
        private int index;

        JsonParser(String text) {
            this.text = text;
        }

        Object parse() {
            Object value = parseValue();
            skipWhitespace();
            if (index != text.length()) {
                throw new IllegalArgumentException("JSON 尾部有多余内容: " + index);
            }
            return value;
        }

        private Object parseValue() {
            skipWhitespace();
            if (index >= text.length()) {
                throw new IllegalArgumentException("JSON 意外结束");
            }
            char c = text.charAt(index);
            switch (c) {
                case '{':
                    return parseObject();
                case '[':
                    return parseArray();
                case '"':
                    return parseString();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    return parseNumber();
            }
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> out = new HashMap<>();
            index++; // {
            skipWhitespace();
            if (peek('}')) {
                index++;
                return out;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                require(':');
                Object value = parseValue();
                if (out.put(key, value) != null) {
                    throw new IllegalArgumentException("重复键: " + key);
                }
                skipWhitespace();
                if (peek(',')) {
                    index++;
                    continue;
                }
                require('}');
                return out;
            }
        }

        private List<Object> parseArray() {
            List<Object> out = new ArrayList<>();
            index++; // [
            skipWhitespace();
            if (peek(']')) {
                index++;
                return out;
            }
            while (true) {
                out.add(parseValue());
                skipWhitespace();
                if (peek(',')) {
                    index++;
                    continue;
                }
                require(']');
                return out;
            }
        }

        private String parseString() {
            require('"');
            StringBuilder out = new StringBuilder();
            while (index < text.length()) {
                char c = text.charAt(index++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (index >= text.length()) {
                    throw new IllegalArgumentException("字符串转义未结束");
                }
                char esc = text.charAt(index++);
                switch (esc) {
                    case '"':
                    case '\\':
                    case '/':
                        out.append(esc);
                        break;
                    case 'b':
                        out.append('\b');
                        break;
                    case 'f':
                        out.append('\f');
                        break;
                    case 'n':
                        out.append('\n');
                        break;
                    case 'r':
                        out.append('\r');
                        break;
                    case 't':
                        out.append('\t');
                        break;
                    case 'u':
                        if (index + 4 > text.length()) {
                            throw new IllegalArgumentException("\\u 转义不完整");
                        }
                        out.append((char) Integer.parseInt(
                                text.substring(index, index + 4), 16));
                        index += 4;
                        break;
                    default:
                        throw new IllegalArgumentException("未知转义: \\" + esc);
                }
            }
            throw new IllegalArgumentException("字符串未闭合");
        }

        private Object parseNumber() {
            int start = index;
            while (index < text.length()
                    && "-+.eE0123456789".indexOf(text.charAt(index)) >= 0) {
                index++;
            }
            String raw = text.substring(start, index);
            if (raw.isEmpty()) {
                throw new IllegalArgumentException("非法 JSON 值，位置 " + start);
            }
            if (raw.indexOf('.') < 0 && raw.indexOf('e') < 0 && raw.indexOf('E') < 0) {
                return Long.parseLong(raw);
            }
            return Double.parseDouble(raw);
        }

        private void skipWhitespace() {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
        }

        private boolean peek(char c) {
            return index < text.length() && text.charAt(index) == c;
        }

        private void require(char c) {
            if (!peek(c)) {
                throw new IllegalArgumentException(
                        "期望 '" + c + "'，位置 " + index);
            }
            index++;
        }

        private void expect(String literal) {
            if (!text.startsWith(literal, index)) {
                throw new IllegalArgumentException(
                        "期望 " + literal + "，位置 " + index);
            }
            index += literal.length();
        }
    }
}
