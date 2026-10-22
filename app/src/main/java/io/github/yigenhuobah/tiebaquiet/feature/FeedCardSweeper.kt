package io.github.yigenhuobah.tiebaquiet.feature

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * 信息流卡片清扫器：在指定 adapter 所属的 RecyclerView 内，
 * 找到包含指定文案的 item 视图并整卡隐藏（高度压 0）。
 *
 * 用于由多个组件拼成、无法在数据层整卡删除的卡片（直播卡/热点卡）。
 * 注意：模块与宿主不是同一个 ClassLoader，不能 instanceof RecyclerView，
 * 只能按类名判断并用反射取 adapter。
 */
object FeedCardSweeper {

    fun schedule(env: HookEnv, adapter: Any?, texts: List<String>) {
        if (adapter == null) return
        val handler = Handler(Looper.getMainLooper())
        var attempts = 0
        val task = object : Runnable {
            override fun run() {
                try {
                    val activity = currentActivity()
                    if (activity != null) {
                        sweep(activity.window?.decorView, adapter, texts)
                    }
                } catch (_: Throwable) {
                }
                attempts++
                if (attempts < 15) handler.postDelayed(this, 800)
            }
        }
        handler.postDelayed(task, 500)
    }

    private fun sweep(view: View?, adapter: Any, texts: List<String>) {
        if (view == null) return
        if (isRecyclerView(view) && view is ViewGroup) {
            val listAdapter = adapterOf(view)
            if (listAdapter === adapter) {
                for (i in 0 until view.childCount) {
                    val item = view.getChildAt(i)
                    if (containsAny(item, texts)) hide(item)
                }
                return
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) sweep(view.getChildAt(i), adapter, texts)
        }
    }

    private fun isRecyclerView(view: View): Boolean {
        var c: Class<*>? = view.javaClass
        while (c != null) {
            if (c.name == "androidx.recyclerview.widget.RecyclerView") return true
            c = c.superclass
        }
        return false
    }

    private fun adapterOf(view: View): Any? = try {
        view.javaClass.getMethod("getAdapter").invoke(view)
    } catch (_: Throwable) {
        null
    }

    private fun containsAny(view: View, texts: List<String>): Boolean {
        if (view is TextView) {
            val text = view.text?.toString() ?: ""
            for (t in texts) if (text.contains(t)) return true
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                if (containsAny(view.getChildAt(i), texts)) return true
            }
        }
        return false
    }

    private fun hide(view: View) {
        if (view.visibility == View.GONE && view.layoutParams?.height == 0) return
        view.visibility = View.GONE
        view.layoutParams?.let { lp ->
            lp.height = 0
            view.layoutParams = lp
        }
    }

    /** 反射拿当前前台 Activity（ActivityThread.mActivities）。 */
    private fun currentActivity(): Activity? = try {
        val at = Class.forName("android.app.ActivityThread")
        val thread = at.getMethod("currentActivityThread").invoke(null)
        val field = at.getDeclaredField("mActivities").apply { isAccessible = true }
        val map = field.get(thread) as? Map<*, *> ?: return null
        var result: Activity? = null
        for (record in map.values) {
            if (record == null) continue
            val af = record.javaClass.getDeclaredField("activity").apply { isAccessible = true }
            val activity = af.get(record) as? Activity
            if (activity != null && !activity.isFinishing) {
                result = activity
            }
        }
        result
    } catch (_: Throwable) {
        null
    }
}
