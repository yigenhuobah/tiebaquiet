package io.github.yigenhuobah.tiebaquiet.feature

/**
 * 信息流卡片过滤谓词。
 * 保守策略：只清广告/推广/活动卡；普通 UGC、视频、投票等一律保留。
 * 过滤只发生在展示层（列表数据），不改动埋点上报。
 */
object CardFilter {

    fun isAdCard(env: HookEnv, item: Any?): Boolean {
        if (item == null) return false
        val nt9 = env.anchors.clazz("nt9") ?: return false
        if (!nt9.isInstance(item)) return false

        val data = invoke(env.anchors.method("nt9_d"), item) ?: return false

        val advert = env.anchors.clazz("advert_app_info")
        if (advert != null && advert.isInstance(data)) return true

        // lego/Nad 广告卡：AdCard 及其数据包装类（“立即查看”卡片）。
        for (key in listOf("lego_ad_card", "lego_ad_data", "lego_ad_wrapper")) {
            val cls = env.anchors.clazz(key)
            if (cls != null && cls.isInstance(data)) return true
        }

        val thread = env.anchors.clazz("thread_data")
        if (thread != null && thread.isInstance(data)) {
            val adUrl = invoke(env.anchors.method("thread_ad_url"), data) as? String
            if (!adUrl.isNullOrEmpty()) return true
            if (invoke(env.anchors.method("thread_ad_subscript"), data) == true) return true
            if (invoke(env.anchors.method("thread_is_act"), data) == true) return true
        }
        return false
    }

    fun typeKey(env: HookEnv, item: Any?): String? {
        if (item == null) return null
        val nt9 = env.anchors.clazz("nt9") ?: return null
        if (!nt9.isInstance(item)) return null
        return invoke(env.anchors.method("nt9_b"), item) as? String
    }

    fun dataOf(env: HookEnv, item: Any?): Any? {
        if (item == null) return null
        val nt9 = env.anchors.clazz("nt9") ?: return null
        if (!nt9.isInstance(item)) return null
        return invoke(env.anchors.method("nt9_d"), item)
    }

    fun isLiveCard(env: HookEnv, item: Any?): Boolean {
        if (item == null) return false
        val nt9 = env.anchors.clazz("nt9") ?: return false
        if (!nt9.isInstance(item)) return false
        // 卡片类型键里带 live 的直接判定为直播卡（稳定字符串，跨版本可用）。
        val typeKey = invoke(env.anchors.method("nt9_b"), item) as? String
        if (typeKey != null && typeKey.contains("live", ignoreCase = true)) return true
        var data = invoke(env.anchors.method("nt9_d"), item) ?: return false
        // 直播卡可能是 em8 包装的 ThreadData。
        val wrapper = env.anchors.clazz("live_thread_wrapper")
        if (wrapper != null && wrapper.isInstance(data)) {
            data = invoke(env.anchors.method("em8_get_thread"), data) ?: data
        }
        val thread = env.anchors.clazz("thread_data") ?: return false
        if (!thread.isInstance(data)) return false
        if (invoke(env.anchors.method("thread_is_live"), data) == true) return true
        if (invoke(env.anchors.method("thread_is_interview_live"), data) == true) return true
        for (key in listOf(
            "thread_is_live_thread",
            "thread_is_live_interview_type",
            "thread_is_interview_live_style",
            "thread_is_ala_live_user",
        )) {
            if (invoke(env.anchors.method(key), data) == true) return true
        }
        val alaInfo = invoke(env.anchors.method("thread_ala_info"), data)
        if (alaInfo != null && invoke(env.anchors.method("ala_info_yy_live"), alaInfo) == true) {
            return true
        }
        val isLive = invoke(env.anchors.method("thread_get_is_live"), data) as? Int
        return isLive != null && isLive != 0
    }

    fun filterLive(env: HookEnv, list: List<*>?): List<*>? {
        if (list == null) return null
        val out = ArrayList<Any?>(list.size)
        for (item in list) {
            if (!isLiveCard(env, item)) out.add(item)
        }
        return if (out.size == list.size) list else out
    }

    fun filter(env: HookEnv, list: List<*>?): List<*>? {
        if (list == null) return null
        val out = ArrayList<Any?>(list.size)
        for (item in list) {
            if (!isAdCard(env, item)) out.add(item)
        }
        return if (out.size == list.size) list else out
    }

    private fun invoke(method: java.lang.reflect.Method?, receiver: Any?): Any? = try {
        method?.invoke(receiver)
    } catch (_: Throwable) {
        null
    }
}
