package io.github.yigenhuobah.tiebaquiet.anchor

import org.json.JSONObject
import java.lang.reflect.Method

/**
 * 锚点表：构建期由 tools/gen_anchors.py 用 DexSQL 校验生成。
 * 运行时按逻辑键解析目标类/方法；任一锚点失效时只影响对应功能。
 */
class AnchorTable private constructor(
    val appVersion: String,
    val versionCode: Long,
    private val root: JSONObject,
    private val loader: ClassLoader,
) {
    private val methodCache = HashMap<String, Method?>()
    private val classCache = HashMap<String, Class<*>?>()

    fun method(key: String): Method? = methodCache.getOrPut(key) {
        val entry = root.optJSONObject("anchors")?.optJSONObject(key) ?: return@getOrPut null
        val cls = loadClass(entry.optString("c")) ?: return@getOrPut null
        val name = entry.optString("m")
        val sig = entry.optString("s")
        try {
            val params = parseParameterTypes(sig, loader)
            cls.getDeclaredMethod(name, *params).apply { isAccessible = true }
        } catch (_: Throwable) {
            null
        }
    }

    fun clazz(key: String): Class<*>? = classCache.getOrPut(key) {
        val name = root.optJSONObject("classes")?.optString(key).orEmpty()
        if (name.isEmpty()) null else loadClass(name)
    }

    fun isSupportedVersion(installedVersion: String?): Boolean =
        installedVersion == null || installedVersion == appVersion

    private fun loadClass(name: String): Class<*>? = try {
        Class.forName(name, false, loader)
    } catch (_: Throwable) {
        null
    }

    companion object {
        fun load(loader: ClassLoader): AnchorTable? = try {
            val stream = AnchorTable::class.java.classLoader?.getResourceAsStream("anchors.json")
                ?: return null
            val text = stream.bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            AnchorTable(
                appVersion = json.optString("appVersion"),
                versionCode = json.optLong("versionCode"),
                root = json,
                loader = loader,
            )
        } catch (_: Throwable) {
            null
        }

        /** 解析 "(Lcom/x;I[Ljava/lang/String;Z)V" 的参数部分为 Class[]。 */
        private fun parseParameterTypes(signature: String, loader: ClassLoader): Array<Class<*>> {
            require(signature.startsWith("("))
            val end = signature.indexOf(')')
            require(end > 0)
            val params = signature.substring(1, end)
            val out = ArrayList<Class<*>>()
            var i = 0
            while (i < params.length) {
                var dims = 0
                while (i < params.length && params[i] == '[') {
                    dims++
                    i++
                }
                val cls: Class<*> = when (val c = params[i]) {
                    'Z' -> Boolean::class.javaPrimitiveType!!
                    'B' -> Byte::class.javaPrimitiveType!!
                    'C' -> Char::class.javaPrimitiveType!!
                    'S' -> Short::class.javaPrimitiveType!!
                    'I' -> Int::class.javaPrimitiveType!!
                    'J' -> Long::class.javaPrimitiveType!!
                    'F' -> Float::class.javaPrimitiveType!!
                    'D' -> Double::class.javaPrimitiveType!!
                    'V' -> Void.TYPE
                    'L' -> {
                        val semi = params.indexOf(';', i)
                        val name = params.substring(i + 1, semi).replace('/', '.')
                        i = semi
                        Class.forName(name, false, loader)
                    }
                    else -> throw IllegalArgumentException("bad signature: $signature")
                }
                var result = cls
                repeat(dims) { result = java.lang.reflect.Array.newInstance(result, 0).javaClass }
                out.add(result)
                i++
            }
            return out.toTypedArray()
        }
    }
}
