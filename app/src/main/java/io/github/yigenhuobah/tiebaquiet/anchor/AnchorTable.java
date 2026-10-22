package io.github.yigenhuobah.tiebaquiet.anchor;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * 锚点表：构建期由 tools/gen_anchors.py 用 DexSQL 校验生成。
 * 运行时按逻辑键解析目标类/方法；任一锚点失效时只影响对应功能。
 *
 * <p>缓存只保存解析成功的结果：缺失锚点每次重试，和 Kotlin 版 getOrPut 的行为一致。
 */
public final class AnchorTable {

    private final String appVersion;
    private final long versionCode;
    private final JSONObject root;
    private final ClassLoader loader;
    private final HashMap<String, Method> methodCache = new HashMap<>();
    private final HashMap<String, Class<?>> classCache = new HashMap<>();

    private AnchorTable(String appVersion, long versionCode, JSONObject root,
                        ClassLoader loader) {
        this.appVersion = appVersion;
        this.versionCode = versionCode;
        this.root = root;
        this.loader = loader;
    }

    public String getAppVersion() {
        return appVersion;
    }

    public long getVersionCode() {
        return versionCode;
    }

    public Method method(String key) {
        Method cached = methodCache.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            JSONObject anchors = root.optJSONObject("anchors");
            if (anchors == null) {
                return null;
            }
            JSONObject entry = anchors.optJSONObject(key);
            if (entry == null) {
                return null;
            }
            Class<?> cls = loadClass(entry.optString("c"));
            if (cls == null) {
                return null;
            }
            String name = entry.optString("m");
            String signature = entry.optString("s");
            Class<?>[] params = parseParameterTypes(signature, loader);
            Method method = cls.getDeclaredMethod(name, params);
            method.setAccessible(true);
            methodCache.put(key, method);
            return method;
        } catch (Throwable t) {
            return null;
        }
    }

    public Class<?> clazz(String key) {
        Class<?> cached = classCache.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            JSONObject classes = root.optJSONObject("classes");
            if (classes == null) {
                return null;
            }
            String name = classes.optString(key, "");
            if (name.isEmpty()) {
                return null;
            }
            Class<?> cls = loadClass(name);
            if (cls != null) {
                classCache.put(key, cls);
            }
            return cls;
        } catch (Throwable t) {
            return null;
        }
    }

    public boolean isSupportedVersion(String installedVersion) {
        return installedVersion == null || installedVersion.equals(appVersion);
    }

    private Class<?> loadClass(String name) {
        try {
            return Class.forName(name, false, loader);
        } catch (Throwable t) {
            return null;
        }
    }

    public static AnchorTable load(ClassLoader loader) {
        try {
            InputStream stream = AnchorTable.class.getClassLoader()
                    .getResourceAsStream("anchors.json");
            if (stream == null) {
                return null;
            }
            StringBuilder text = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                char[] buffer = new char[4096];
                int read;
                while ((read = reader.read(buffer)) >= 0) {
                    text.append(buffer, 0, read);
                }
            }
            JSONObject json = new JSONObject(text.toString());
            return new AnchorTable(json.optString("appVersion"),
                    json.optLong("versionCode"), json, loader);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 解析 "(Lcom/x;I[Ljava/lang/String;Z)V" 的参数部分为 Class[]。
     * 抽成静态方法便于 JVM 单测，不触碰 JSON 与框架。
     */
    public static Class<?>[] parseParameterTypes(String signature, ClassLoader loader) {
        if (signature == null || !signature.startsWith("(")) {
            throw new IllegalArgumentException("bad signature: " + signature);
        }
        int end = signature.indexOf(')');
        if (end <= 0) {
            throw new IllegalArgumentException("bad signature: " + signature);
        }
        String params = signature.substring(1, end);
        List<Class<?>> out = new ArrayList<>();
        int i = 0;
        while (i < params.length()) {
            int dims = 0;
            while (i < params.length() && params.charAt(i) == '[') {
                dims++;
                i++;
            }
            if (i >= params.length()) {
                throw new IllegalArgumentException("bad signature: " + signature);
            }
            char c = params.charAt(i);
            Class<?> cls;
            switch (c) {
                case 'Z':
                    cls = boolean.class;
                    break;
                case 'B':
                    cls = byte.class;
                    break;
                case 'C':
                    cls = char.class;
                    break;
                case 'S':
                    cls = short.class;
                    break;
                case 'I':
                    cls = int.class;
                    break;
                case 'J':
                    cls = long.class;
                    break;
                case 'F':
                    cls = float.class;
                    break;
                case 'D':
                    cls = double.class;
                    break;
                case 'V':
                    cls = void.class;
                    break;
                case 'L': {
                    int semi = params.indexOf(';', i);
                    if (semi < 0) {
                        throw new IllegalArgumentException("bad signature: " + signature);
                    }
                    String name = params.substring(i + 1, semi).replace('/', '.');
                    i = semi;
                    try {
                        cls = Class.forName(name, false, loader);
                    } catch (ClassNotFoundException e) {
                        throw new IllegalArgumentException("class not found: " + name, e);
                    }
                    break;
                }
                default:
                    throw new IllegalArgumentException("bad signature: " + signature);
            }
            for (int d = 0; d < dims; d++) {
                cls = Array.newInstance(cls, 0).getClass();
            }
            out.add(cls);
            i++;
        }
        return out.toArray(new Class<?>[0]);
    }
}
