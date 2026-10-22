package io.github.yigenhuobah.tiebaquiet.feature;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.List;
import java.util.Map;

import io.github.yigenhuobah.tiebaquiet.HookEnv;

/**
 * 信息流卡片清扫器：在指定 adapter 所属的 RecyclerView 内，
 * 找到包含指定文案的 item 视图并整卡隐藏（高度压 0）。
 *
 * <p>用于由多个组件拼成、无法在数据层整卡删除的卡片（直播卡/热点卡）。
 * 模块与宿主不是同一个 ClassLoader，不能 instanceof RecyclerView，
 * 只能按类名判断并用反射取 adapter。
 */
public final class FeedCardSweeper {

    private static final int MAX_ATTEMPTS = 15;
    private static final long RETRY_DELAY_MS = 800L;
    private static final long FIRST_DELAY_MS = 500L;

    private FeedCardSweeper() {
    }

    public static void schedule(HookEnv env, Object adapter, List<String> texts) {
        if (adapter == null) {
            return;
        }
        final Handler handler = new Handler(Looper.getMainLooper());
        final int[] attempts = {0};
        final Object targetAdapter = adapter;
        final List<String> targetTexts = texts;
        Runnable task = new Runnable() {
            @Override
            public void run() {
                try {
                    Activity activity = currentActivity();
                    if (activity != null) {
                        sweep(activity.getWindow() != null
                                ? activity.getWindow().getDecorView() : null,
                                targetAdapter, targetTexts);
                    }
                } catch (Throwable ignored) {
                }
                attempts[0]++;
                if (attempts[0] < MAX_ATTEMPTS) {
                    handler.postDelayed(this, RETRY_DELAY_MS);
                }
            }
        };
        handler.postDelayed(task, FIRST_DELAY_MS);
    }

    private static void sweep(View view, Object adapter, List<String> texts) {
        if (view == null) {
            return;
        }
        if (isRecyclerView(view) && view instanceof ViewGroup) {
            Object listAdapter = adapterOf(view);
            if (listAdapter == adapter) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    View item = group.getChildAt(i);
                    if (containsAny(item, texts)) {
                        hide(item);
                    }
                }
                return;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                sweep(group.getChildAt(i), adapter, texts);
            }
        }
    }

    private static boolean isRecyclerView(View view) {
        Class<?> c = view.getClass();
        while (c != null) {
            if ("androidx.recyclerview.widget.RecyclerView".equals(c.getName())) {
                return true;
            }
            c = c.getSuperclass();
        }
        return false;
    }

    private static Object adapterOf(View view) {
        try {
            return view.getClass().getMethod("getAdapter").invoke(view);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean containsAny(View view, List<String> texts) {
        if (view instanceof TextView) {
            CharSequence raw = ((TextView) view).getText();
            String text = raw != null ? raw.toString() : "";
            for (String t : texts) {
                if (text.contains(t)) {
                    return true;
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (containsAny(group.getChildAt(i), texts)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void hide(View view) {
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (view.getVisibility() == View.GONE && lp != null && lp.height == 0) {
            return;
        }
        view.setVisibility(View.GONE);
        if (lp != null) {
            lp.height = 0;
            view.setLayoutParams(lp);
        }
    }

    /** 反射拿当前前台 Activity（ActivityThread.mActivities）。 */
    private static Activity currentActivity() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("currentActivityThread").invoke(null);
            java.lang.reflect.Field field = at.getDeclaredField("mActivities");
            field.setAccessible(true);
            Object raw = field.get(thread);
            if (!(raw instanceof Map)) {
                return null;
            }
            Map<?, ?> map = (Map<?, ?>) raw;
            Activity result = null;
            for (Object record : map.values()) {
                if (record == null) {
                    continue;
                }
                java.lang.reflect.Field af = record.getClass().getDeclaredField("activity");
                af.setAccessible(true);
                Object activity = af.get(record);
                if (activity instanceof Activity && !((Activity) activity).isFinishing()) {
                    result = (Activity) activity;
                }
            }
            return result;
        } catch (Throwable t) {
            return null;
        }
    }
}
