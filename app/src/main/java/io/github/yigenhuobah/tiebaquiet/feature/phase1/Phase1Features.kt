package io.github.yigenhuobah.tiebaquiet.feature.phase1

import io.github.libxposed.api.XposedInterface
import io.github.yigenhuobah.tiebaquiet.feature.CardFilter
import io.github.yigenhuobah.tiebaquiet.feature.Feature
import io.github.yigenhuobah.tiebaquiet.feature.HookEnv

/** ① 开屏广告：走官方“会员无开屏”路径。 */
object SplashNoAdFeature : Feature(
    id = "splash_no_ad",
    title = "无开屏广告",
    summary = "复用官方禁开屏开关，启动不再加载广告",
    phase = 1,
    defaultEnabled = true,
) {
    override fun install(env: HookEnv): Boolean =
        replace(env, env.anchors.method("splash_forbid_b"), true)
}

/** ② 营销/活动/签到弹窗总闸。 */
object YunDialogFeature : Feature(
    id = "yun_dialog",
    title = "营销与活动弹窗",
    summary = "关闭云端下发的活动、签到、营销类弹窗",
    phase = 1,
    defaultEnabled = true,
) {
    override fun install(env: HookEnv): Boolean =
        replace(env, env.anchors.method("yun_dialog_can_show"), false)
}

/** ③ 首页/帖子页悬浮广告。 */
object FloatAdFeature : Feature(
    id = "float_ad",
    title = "悬浮广告",
    summary = "隐藏首页与帖子页的悬浮广告浮标",
    phase = 1,
    defaultEnabled = true,
) {
    override fun install(env: HookEnv): Boolean {
        val home = replace(env, env.anchors.method("home_ad_float"), null)
        val pb = replace(env, env.anchors.method("pb_ad_float"), null)
        return home || pb
    }
}

/** ④ 信息流广告/推广卡过滤（全列表统一入口）。 */
object FeedAdFilterFeature : Feature(
    id = "feed_ad_filter",
    title = "信息流广告卡",
    summary = "过滤首页/吧内/评论列表中的广告与推广卡片",
    phase = 1,
    defaultEnabled = true,
) {
    override fun install(env: HookEnv): Boolean {
        val method = env.anchors.method("template_set_list") ?: return false
        return hook(env, method) { chain ->
            val args = chain.args.toTypedArray()
            val original = args.getOrNull(0) as? List<*>
            val filtered = CardFilter.filter(env, original)
            if (filtered != null && filtered !== original) args[0] = filtered
            chain.proceed(args)
        }
    }
}

/** ⑤ 底部 Tab 红点。 */
object TabRedDotFeature : Feature(
    id = "tab_red_dot",
    title = "底部 Tab 红点",
    summary = "隐藏底部导航的红点与角标",
    phase = 1,
    defaultEnabled = true,
) {
    override fun install(env: HookEnv): Boolean {
        var any = false
        for (key in listOf(
            "tab_add_tip", "tab_add_tip_2", "tab_add_tip_3", "tab_add_tip_4", "tab_add_tip_wrap",
            "msg_red_dot_refresh", "msg_red_dot_refresh_opt", "msg_red_dot_refresh_str",
        )) {
            if (skip(env, env.anchors.method(key))) any = true
        }
        return any
    }
}

/** ⑥ 推荐吧卡。 */
object NoRecForumFeature : Feature(
    id = "rec_forum",
    title = "推荐吧卡",
    summary = "不展示“推荐吧/常逛吧”推广卡片",
    phase = 1,
    defaultEnabled = true,
) {
    override fun install(env: HookEnv): Boolean =
        replace(env, env.anchors.method("rec_forum_strategy_h"), false)
}

/** ⑦ 更新弹窗。 */
object UpdateDialogFeature : Feature(
    id = "update_dialog",
    title = "更新弹窗",
    summary = "拦截“发现新版本”强制更新弹窗",
    phase = 1,
    defaultEnabled = true,
) {
    override fun install(env: HookEnv): Boolean =
        skip(env, env.anchors.method("upgrade_pop"))
}

/** ⑧ 彩蛋广告。 */
object EasterEggFeature : Feature(
    id = "easter_egg",
    title = "彩蛋广告",
    summary = "关闭节日彩蛋类广告弹层",
    phase = 1,
    defaultEnabled = true,
) {
    override fun install(env: HookEnv): Boolean =
        skip(env, env.anchors.method("easter_egg_o"))
}

/** ⑨ 频道栏默认“关注”（口味项，默认关）。 */
object DefaultChannelFeature : Feature(
    id = "default_channel",
    title = "频道栏默认关注",
    summary = "把“关注”频道排到首页频道栏第一位",
    phase = 1,
    defaultEnabled = false,
) {
    override fun install(env: HookEnv): Boolean {
        val method = env.anchors.method("new_scroll_r") ?: return false
        return hook(env, method) { chain ->
            val args = chain.args.toTypedArray()
            val list = args.getOrNull(1) as? List<*>
            if (list != null) {
                val focus = list.filter { looksLikeFollowTab(it) }
                if (focus.isNotEmpty() && list.firstOrNull() !== focus.first()) {
                    val reordered = ArrayList<Any?>(list.size)
                    reordered.addAll(focus)
                    for (item in list) {
                        if (focus.none { it === item }) reordered.add(item)
                    }
                    args[1] = reordered
                }
            }
            chain.proceed(args)
        }
    }

    private fun looksLikeFollowTab(item: Any?): Boolean {
        if (item == null) return false
        return try {
            val cls = item.javaClass
            val b = readString(cls, item, "b")
            val c = readString(cls, item, "c")
            (b?.contains("关注") == true) || (c?.contains("关注") == true)
        } catch (_: Throwable) {
            false
        }
    }

    private fun readString(cls: Class<*>, item: Any, name: String): String? {
        var c: Class<*>? = cls
        while (c != null) {
            try {
                val f = c.getDeclaredField(name).apply { isAccessible = true }
                return f.get(item) as? String
            } catch (_: Throwable) {
                c = c.superclass
            }
        }
        return null
    }
}

/** ⑩ 强制夜间（口味项，默认关）。 */
object ForceNightFeature : Feature(
    id = "force_night",
    title = "强制夜间模式",
    summary = "固定使用夜间皮肤（4）",
    phase = 1,
    defaultEnabled = false,
) {
    override fun install(env: HookEnv): Boolean =
        after(env, env.anchors.method("skin_type")) { 4 }
}

/** ⑫ 直播卡过滤：信息流中的开播/直播卡片。 */
object LiveCardFilterFeature : Feature(
    id = "live_card_filter",
    title = "直播卡过滤",
    summary = "信息流中不再展示“直播中/开播”卡片",
    phase = 1,
    defaultEnabled = true,
) {
    override fun install(env: HookEnv): Boolean {
        var any = false
        val setList = env.anchors.method("template_set_list")
        if (setList != null) {
            val installed = hook(env, setList) { chain ->
                val adapter = chain.thisObject
                val args = chain.args.toTypedArray()
                val original = args.getOrNull(0) as? List<*>
                val filtered = CardFilter.filterLive(env, original)
                if (filtered != null && filtered !== original) {
                    args[0] = filtered
                }
                // 直播卡由多个组件拼成，数据层删不干净，视图层再整卡隐藏。
                io.github.yigenhuobah.tiebaquiet.feature.FeedCardSweeper.schedule(
                    env, adapter, listOf("分钟前开播", "直播中", "正在直播")
                )
                chain.proceed(args)
            }
            any = any || installed
        }
        // 兜底：首页直播卡专用适配器，把条目高度压成 0。
        val fill = env.anchors.method("live_adapter_fill")
        if (fill != null) {
            val installed = after(env, fill) { view ->
                env.module.log(3, TAG, "live filter: adapter fill")
                (view as? android.view.View)?.let { v ->
                    v.visibility = android.view.View.GONE
                    v.layoutParams?.let { lp ->
                        lp.height = 0
                        v.layoutParams = lp
                    }
                }
                view
            }
            any = any || installed
        }
        // “关注的主播在直播”卡片：绑定数据后直接隐藏并压成 0 高。
        val liveCard = env.anchors.method("ala_live_card_bind")
        if (liveCard != null) {
            val installed = hook(env, liveCard) { chain ->
                env.module.log(3, TAG, "live filter: attention card bind")
                val view = chain.thisObject as? android.view.View
                val result = chain.proceed()
                view?.let { v ->
                    v.visibility = android.view.View.GONE
                    v.layoutParams?.let { lp ->
                        lp.height = 0
                        v.layoutParams = lp
                    }
                }
                result
            }
            any = any || installed
        }
        // 直播推荐卡（含内嵌直播列表）：创建后直接压成 0 高。
        val create = env.anchors.method("live_card_create")
        if (create != null) {
            val installed = after(env, create) { view ->
                env.module.log(3, TAG, "live filter: card create")
                (view as? android.view.View)?.let { v ->
                    v.visibility = android.view.View.GONE
                    v.layoutParams?.let { lp ->
                        lp.height = 0
                        v.layoutParams = lp
                    }
                }
                view
            }
            any = any || installed
        }
        // 直播 SDK 的卡片 item：绑定数据后隐藏并压成 0 高。
        for (key in listOf("live_item_16_set_data", "live_item_45_set_data")) {
            val m = env.anchors.method(key) ?: continue
            val installed = hook(env, m) { chain ->
                val view = chain.thisObject as? android.view.View
                val result = chain.proceed()
                view?.let { v ->
                    v.visibility = android.view.View.GONE
                    v.layoutParams?.let { lp ->
                        lp.height = 0
                        v.layoutParams = lp
                    }
                }
                result
            }
            any = any || installed
        }
        return any
    }
}

/** ⑭ 实时热点卡过滤：信息流中的“实时热点”卡片。 */
object HotCardFilterFeature : Feature(
    id = "hot_card_filter",
    title = "实时热点卡过滤",
    summary = "信息流中不再展示“实时热点”卡片",
    phase = 1,
    defaultEnabled = true,
) {
    override fun install(env: HookEnv): Boolean {
        val method = env.anchors.method("template_set_list") ?: return false
        return hook(env, method) { chain ->
            io.github.yigenhuobah.tiebaquiet.feature.FeedCardSweeper.schedule(
                env, chain.thisObject, listOf("实时热点")
            )
            chain.proceed()
        }
    }
}

/** ⑬ 个人中心精简：H5 “我的”页注入 CSS/JS 隐藏会员横幅、钱包、任务与游戏专区。 */object ProfileCleanupFeature : Feature(
    id = "profile_cleanup",
    title = "个人中心精简",
    summary = "隐藏“我的”页的会员横幅、钱包、任务赚贴贝与游戏专区",
    phase = 1,
    defaultEnabled = true,
) {
    private const val USER_CENTER_MARK = "hybrid-main-usercenter"

    private val JS = """
        (function(){
          function hide(){
            var sels=['.vip-banner','.activity-area','.xiaoman-wallet','.game-zone','#banner-swiper','.banner-swiper'];
            for(var i=0;i<sels.length;i++){
              var els=document.querySelectorAll(sels[i]);
              for(var j=0;j<els.length;j++){els[j].style.display='none';}
            }
          }
          hide();
          if(!window.__tq_obs){
            window.__tq_obs=new MutationObserver(hide);
            window.__tq_obs.observe(document.body,{childList:true,subtree:true});
          }
        })();
    """.trimIndent()

    override fun install(env: HookEnv): Boolean {
        // 个人页 WebView 由 PersonTabWebController 持有；页面创建后定时注入清理脚本。
        val method = env.anchors.method("person_tab_attach") ?: return false
        return hook(env, method) { chain ->
            val controller = chain.thisObject
            val result = chain.proceed()
            if (controller != null) {
                env.module.log(3, TAG, "profile cleanup: controller attached")
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                var attempts = 0
                val inject = object : Runnable {
                    override fun run() {
                        try {
                            val tbWebView = controller.javaClass.getMethod("k").invoke(controller)
                            val inner = tbWebView?.javaClass
                                ?.getMethod("getInnerWebView")
                                ?.invoke(tbWebView) as? android.webkit.WebView
                            if (attempts == 0) {
                                env.module.log(3, TAG, "profile cleanup js applied")
                            }
                            inner?.evaluateJavascript(JS, null)
                        } catch (_: Throwable) {
                        }
                        attempts++
                        if (attempts < 15) handler.postDelayed(this, 700)
                    }
                }
                handler.postDelayed(inject, 800)
            }
            result
        }
    }
}

/** ⑪ 吧页推广卡过滤。 */
object ForumPromoFilterFeature : Feature(
    id = "forum_promo_filter",
    title = "吧页推广卡",
    summary = "过滤吧页面解析出的推广/活动卡片",
    phase = 1,
    defaultEnabled = true,
) {
    override fun install(env: HookEnv): Boolean {
        val method = env.anchors.method("forum_res_parser_a") ?: return false
        return after(env, method) { result ->
            CardFilter.filter(env, result as? List<*>) ?: result
        }
    }
}
