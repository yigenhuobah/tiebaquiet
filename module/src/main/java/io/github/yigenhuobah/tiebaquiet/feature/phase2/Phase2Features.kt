package io.github.yigenhuobah.tiebaquiet.feature.phase2

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import io.github.yigenhuobah.tiebaquiet.feature.Feature
import io.github.yigenhuobah.tiebaquiet.feature.HookEnv

/** 二期公用：从 hook 进程拿到宿主 Application Context。 */
internal object HostApp {
    fun context(): Context? = try {
        val at = Class.forName("android.app.ActivityThread")
        at.getMethod("currentApplication").invoke(null) as? Context
    } catch (_: Throwable) {
        null
    }

    fun toast(message: String) {
        val ctx = context() ?: return
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * 广告请求源头掐断：
 * - FunAd/ADX：BasePidLoader.load → false
 * - 聚屏/插屏/激励（Nad）：直接回调 "Blocked by client"
 * - 贴吧自研异步 AFD：qy8.c → 失败回调
 */
object AdRequestBlockFeature : Feature(
    id = "ad_request_block",
    title = "广告请求源头掐断",
    summary = "FunAd/Nad/getFeedAd 三类广告请求不再发出",
    phase = 2,
    defaultEnabled = true,
) {
    override fun install(env: HookEnv): Boolean {
        var any = false

        if (replace(env, env.anchors.method("pid_loader_load"), false)) any = true

        // 信息流广告插入闸门：请求被拦后仍可能有缓存物料，这里再断一次插入。
        if (replace(env, env.anchors.method("ad_insert_gate"), false)) any = true
        // 推广 App 卡单卡展示闸门。
        if (replace(env, env.anchors.method("ad_card_gate"), false)) any = true

        val nad = env.anchors.method("nad_requester_a")
        if (nad != null) {
            val installed = hook(env, nad) { chain ->
                val callback = chain.args.getOrNull(1)
                try {
                    val errorClass = env.loader.loadClass("com.baidu.nadcore.requester.NadRequester\$Error")
                    val error = errorClass.getConstructor(String::class.java)
                        .newInstance("Blocked by client")
                    val iface = env.loader.loadClass("com.baidu.nadcore.requester.NadRequester\$b")
                    iface.getMethod("a", errorClass).invoke(callback, error)
                } catch (_: Throwable) {
                    // 回调失败也不影响：请求已不发出
                }
                null
            }
            any = any || installed
        }

        val qy8 = env.anchors.method("qy8_c")
        if (qy8 != null) {
            val installed = hook(env, qy8) { chain ->
                val request = chain.args.getOrNull(0)
                val callback = chain.args.getOrNull(1)
                try {
                    val requestClass = env.loader.loadClass("com.baidu.tieba.c18")
                    val iface = env.loader.loadClass("com.baidu.tieba.i18")
                    iface.getMethod("c", requestClass, Int::class.javaPrimitiveType, String::class.java)
                        .invoke(callback, request, 0, "blocked")
                } catch (_: Throwable) {
                }
                null
            }
            any = any || installed
        }

        return any
    }
}

/** 只看楼主：请求里强制 lz=1。 */
object HostOnlyFeature : Feature(
    id = "host_only",
    title = "只看楼主",
    summary = "帖子页请求始终按只看楼主过滤",
    phase = 2,
    defaultEnabled = true,
) {
    override fun install(env: HookEnv): Boolean {
        val method = env.anchors.method("add_lz") ?: return false
        return hook(env, method) { chain ->
            val builder = chain.args.getOrNull(0)
            val result = chain.proceed()
            try {
                builder?.javaClass?.getField("lz")?.set(builder, 1)
            } catch (_: Throwable) {
            }
            result
        }
    }
}

/** 楼层倒序：请求里强制 r=1。 */
object ReverseOrderFeature : Feature(
    id = "reverse_order",
    title = "楼层倒序",
    summary = "帖子楼层按倒序加载",
    phase = 2,
    defaultEnabled = true,
) {
    override fun install(env: HookEnv): Boolean {
        val method = env.anchors.method("add_r") ?: return false
        return hook(env, method) { chain ->
            val builder = chain.args.getOrNull(0)
            val result = chain.proceed()
            try {
                builder?.javaClass?.getField("r")?.set(builder, 1)
            } catch (_: Throwable) {
            }
            result
        }
    }
}

/** 图片去水印：强制水印类型为“不显示”（默认值 2）。 */
object NoWatermarkFeature : Feature(
    id = "no_watermark",
    title = "图片去水印",
    summary = "图片加载不再附加用户名/吧名水印",
    phase = 2,
    defaultEnabled = true,
) {
    override fun install(env: HookEnv): Boolean =
        replace(env, env.anchors.method("watermark_type"), 2)
}

/** 原图入口常显：强制“查看原图”按钮可用。 */
object OriginalImageFeature : Feature(
    id = "original_image",
    title = "原图入口常显",
    summary = "图片查看器始终显示“查看原图”入口",
    phase = 2,
    defaultEnabled = true,
) {
    override fun install(env: HookEnv): Boolean =
        replace(env, env.anchors.method("original_button"), true)
}

/** 视频直链复制：播放时把直链写入剪贴板。 */
object VideoLinkFeature : Feature(
    id = "video_link",
    title = "视频直链复制",
    summary = "播放视频时自动复制直链到剪贴板",
    phase = 2,
    defaultEnabled = true,
) {
    @Volatile
    private var lastUrl: String? = null

    override fun install(env: HookEnv): Boolean {
        val method = env.anchors.method("video_set_path") ?: return false
        return hook(env, method) { chain ->
            val url = chain.args.getOrNull(0) as? String
            if (!url.isNullOrEmpty() && url != lastUrl) {
                lastUrl = url
                copyToClipboard(url)
            }
            chain.proceed()
        }
    }

    private fun copyToClipboard(url: String) {
        val ctx = HostApp.context() ?: return
        Handler(Looper.getMainLooper()).post {
            try {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("video", url))
                Toast.makeText(ctx, "视频直链已复制", Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {
            }
        }
    }
}

/**
 * 一键签到：模块 App 写入 sign_request 时间戳，
 * hook 侧监听变化后拉起贴吧自带的签到页（自动执行签到）。
 */
object SignInFeature : Feature(
    id = "one_key_sign",
    title = "一键签到",
    summary = "设置页按钮触发贴吧自带的全部吧签到",
    phase = 2,
    defaultEnabled = false,
) {
    const val ACTION_SIGN = "io.github.yigenhuobah.tiebaquiet.action.SIGN"
    const val TARGET_PACKAGE = "com.baidu.tieba"

    /**
     * 无论开关状态都注册命令接收器：设置页按钮发显式广播，
     * 贴吧进程内的接收器在触发时再判断开关。
     */
    fun installAlways(env: HookEnv): Boolean {
        if (env.anchors.method("sign_all_load") == null) return false
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != ACTION_SIGN) return
                // 显式广播本身就是用户操作，不再依赖 hook 侧的开关快照。
                env.module.log(3, TAG, "sign request received")
                triggerSign(env)
            }
        }
        val filter = android.content.IntentFilter(ACTION_SIGN)
        // onPackageReady 时 Application 可能尚未创建，后台重试拿 Context 再注册。
        Thread {
            repeat(30) { attempt ->
                val ctx = HostApp.context()
                if (ctx != null) {
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= 33) {
                            ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                        } else {
                            @Suppress("UnspecifiedRegisterReceiverFlag")
                            ctx.registerReceiver(receiver, filter)
                        }
                        env.module.log(3, TAG, "sign receiver registered (attempt ${attempt + 1})")
                        return@Thread
                    } catch (t: Throwable) {
                        env.module.log(4, TAG, "sign receiver register failed", t)
                        return@Thread
                    }
                }
                Thread.sleep(500)
            }
            env.module.log(4, TAG, "sign receiver: context unavailable after retries")
        }.apply { isDaemon = true }.start()
        return true
    }

    override fun install(env: HookEnv): Boolean =
        env.anchors.method("sign_all_load") != null

    private fun triggerSign(env: HookEnv) {
        val ctx = HostApp.context() ?: return
        Handler(Looper.getMainLooper()).post {
            try {
                val intent = Intent().apply {
                    setClassName(ctx.packageName, "com.baidu.tieba.signall.SignAllForumActivity")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(intent)
            } catch (t: Throwable) {
                env.module.log(4, TAG, "sign launch failed", t)
            }
        }
    }
}
