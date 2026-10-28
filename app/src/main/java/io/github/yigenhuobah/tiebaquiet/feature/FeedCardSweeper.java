package io.github.yigenhuobah.tiebaquiet.feature;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.yigenhuobah.tiebaquiet.HookEnv;

/**
 * 信息流卡片清扫器：只按 adapter 中的卡片数据判断类型，隐藏确认的直播/热点卡。
 *
 * <p>旧实现按“直播中/正在直播/实时热点”等自由文本匹配整张卡片，正文里含这些词的
 * 普通帖子也会被整卡 GONE；RecyclerView 复用被隐藏的 ViewHolder 后还会留下空白行。
 * 现在改为读取 item 数据（{@link CardFilter#typeKey} / {@link CardFilter#isLiveCard}），
 * 无法确认类型时不隐藏，保持 fail-open。
 *
 * <p>每个 adapter 同一时刻只保留一条 sweep 链：重复 {@link #schedule} 只合并
 * live/hot 策略，不叠加遍历。首次找到 RecyclerView 后缓存弱引用，后续只扫这一棵
 * 子树的当前子项；RecyclerView 脱离窗口或 adapter 被替换时提前结束。
 *
 * <p>能挂上 OnChildAttachStateChangeListener 时，在 item attach 时重新判定并
 * 恢复非目标卡片的 visibility/height；item detach 时立即恢复，避免复用空白行。
 *
 * <p>模块与宿主不是同一个 ClassLoader，不能 instanceof RecyclerView，只能按类名判断、
 * 用反射取 adapter 与子项位置。
 */
public final class FeedCardSweeper {

    private static final int MAX_ATTEMPTS = 15;
    private static final long RETRY_DELAY_MS = 800L;
    private static final long FIRST_DELAY_MS = 500L;

    /** 被本类改过状态的 View 用这个 key 保存原始 visibility/height。 */
    private static Integer sHiddenTag;

    /** adapter -> 当前唯一一条 sweep 链的状态。value 不持有 adapter 强引用。 */
    private static final Map<Object, SweepState> ACTIVE =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** 已挂过 child attach 监听器的 RecyclerView，避免重复 add。 */
    private static final Map<View, Boolean> WATCHED =
            Collections.synchronizedMap(new WeakHashMap<>());

    private FeedCardSweeper() {
    }

    /** 一个 adapter 的 sweep 策略与运行状态。 */
    static final class SweepState {
        boolean live;
        boolean hot;
        boolean scheduled;
        WeakReference<View> recyclerView;
        Method positionMethod;
        Method itemMethod;
    }

    /** 原始 UI 状态；只在 View 自己的 tag 中保存，不用静态表持有 View。 */
    private static final class OriginalState {
        final int visibility;
        final int height;

        OriginalState(int visibility, int height) {
            this.visibility = visibility;
            this.height = height;
        }
    }

    /**
     * schedule 一条 sweep 链。hideLive/hideHot 来自直播卡/热点卡功能各自的开关。
     *
     * <p>同一 adapter 已有链时只合并策略并返回，只有第一次调用会真正 post。
     * 调用方在 hook 里可能因为同一次 setList 被两个功能各调用一次，这里负责去重。
     */
    public static void schedule(HookEnv env, Object adapter, boolean hideLive, boolean hideHot) {
        if (env == null || adapter == null || (!hideLive && !hideHot)) {
            return;
        }
        if (!beginSweep(adapter, hideLive, hideHot)) {
            return;
        }
        final SweepState state = stateOf(adapter);
        if (state == null) {
            return;
        }

        try {
            final Handler handler = new Handler(Looper.getMainLooper());
            final Runnable task = new Runnable() {
                private int attempts;

                @Override
                public void run() {
                    boolean keep;
                    try {
                        keep = sweepOnce(env, adapter, state);
                    } catch (Throwable ignored) {
                        // 单次遍历失败不能把整条链打断；继续重试，仍保持 fail-open。
                        keep = true;
                    }
                    attempts++;
                    if (keep && attempts < MAX_ATTEMPTS) {
                        handler.postDelayed(this, RETRY_DELAY_MS);
                    } else {
                        endSweep(adapter, state);
                    }
                }
            };
            handler.postDelayed(task, FIRST_DELAY_MS);
        } catch (Throwable ignored) {
            endSweep(adapter, state);
        }
    }

    /**
     * 纯状态机：返回 true 表示需要启动新链，false 表示该 adapter 已有链、只合并策略。
     * 抽成静态方法便于 JVM 单测，不依赖 Android。
     */
    static boolean beginSweep(Object adapter, boolean hideLive, boolean hideHot) {
        if (adapter == null || (!hideLive && !hideHot)) {
            return false;
        }
        synchronized (ACTIVE) {
            SweepState state = ACTIVE.get(adapter);
            if (state == null) {
                state = new SweepState();
                ACTIVE.put(adapter, state);
            }
            state.live |= hideLive;
            state.hot |= hideHot;
            if (state.scheduled) {
                return false;
            }
            state.scheduled = true;
            return true;
        }
    }

    /** 链结束（遍历完成、RecyclerView 消失或 adapter 被替换）。幂等。 */
    static void endSweep(Object adapter, SweepState state) {
        if (adapter == null || state == null) {
            return;
        }
        synchronized (ACTIVE) {
            if (ACTIVE.get(adapter) == state) {
                ACTIVE.remove(adapter);
            }
        }
    }

    /** 仅供测试与 attach 回调查询当前 adapter 的活跃策略。 */
    static SweepState stateOf(Object adapter) {
        if (adapter == null) {
            return null;
        }
        synchronized (ACTIVE) {
            return ACTIVE.get(adapter);
        }
    }

    /**
     * 一次 sweep。返回 false 表示链应终止（目标 RecyclerView 已 detach/adapter 已换）。
     */
    private static boolean sweepOnce(HookEnv env, Object adapter, SweepState state) {
        View recycler = state.recyclerView != null ? state.recyclerView.get() : null;
        if (recycler != null) {
            if (adapterOf(recycler) != adapter || !recycler.isAttachedToWindow()) {
                // 页面已销毁或 adapter 被复用：这条链不再有意义。
                return false;
            }
            sweepChildren(recycler, adapter, env, state);
            return true;
        }

        Activity activity = currentActivity();
        View decor = activity != null && activity.getWindow() != null
                ? activity.getWindow().getDecorView() : null;
        View found = findRecyclerView(decor, adapter);
        if (found == null) {
            // 页面可能还没布局完，继续重试；最多 MAX_ATTEMPTS 次。
            return true;
        }
        state.recyclerView = new WeakReference<>(found);
        resolveMethods(found, adapter, state);
        ensureChildWatcher(found, env);
        sweepChildren(found, adapter, env, state);
        return true;
    }

    private static void resolveMethods(View recycler, Object adapter, SweepState state) {
        if (state.positionMethod == null) {
            state.positionMethod = findMethod(recycler, "getChildAdapterPosition", View.class);
        }
        if (state.itemMethod == null) {
            state.itemMethod = findMethod(adapter, "getItem", int.class);
        }
    }

    /** 只遍历目标 RecyclerView 的当前子项，不再全树查找。 */
    private static void sweepChildren(View recycler, Object adapter, HookEnv env,
                                      SweepState state) {
        if (!(recycler instanceof ViewGroup)) {
            return;
        }
        resolveMethods(recycler, adapter, state);
        ViewGroup group = (ViewGroup) recycler;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            try {
                evaluateChild(child, recycler, adapter, env, state);
            } catch (Throwable ignored) {
                // 单个 item 判定失败不能中断整次 sweep；恢复该行，保持 fail-open。
                restore(child);
            }
        }
    }

    /**
     * 判定一个子项：确认是目标卡片才隐藏；无法确定（position/item/typeKey 缺失）
     * 一律恢复而不是隐藏，避免把普通帖子误删。
     */
    private static void evaluateChild(View child, View recycler, Object adapter,
                                      HookEnv env, SweepState state) {
        int position = childPosition(recycler, child, state);
        if (position < 0) {
            // 正在消失/尚未绑定的子项：只清理本类留下的隐藏状态。
            restore(child);
            return;
        }
        Object item = adapterItem(adapter, position, state);
        if (item == null) {
            restore(child);
            return;
        }
        String typeKey = CardFilter.typeKey(env, item);
        boolean liveData = state.live && CardFilter.isLiveCard(env, item);
        if (shouldHide(typeKey, liveData, state.live, state.hot)) {
            hide(child);
        } else {
            restore(child);
        }
    }

    private static int childPosition(View recycler, View child, SweepState state) {
        Object value = invoke(state.positionMethod, recycler, child);
        return value instanceof Integer ? (Integer) value : -1;
    }

    private static Object adapterItem(Object adapter, int position, SweepState state) {
        return invoke(state.itemMethod, adapter, position);
    }

    /**
     * 纯判定：只认模板类型键与数据层直播标记，绝不读取卡片文本。
     *
     * @param typeKey   adapter item 的模板类型键，可为 null
     * @param liveData  CardFilter.isLiveCard 的结果
     * @param hideLive  直播卡开关是否开启
     * @param hideHot   热点卡开关是否开启
     */
    static boolean shouldHide(String typeKey, boolean liveData, boolean hideLive, boolean hideHot) {
        if (hideLive && (liveData || isLiveTypeKey(typeKey))) {
            return true;
        }
        return hideHot && isHotTypeKey(typeKey);
    }

    /**
     * 直播类型键：用明确 token 而不是裸 contains("live")，
     * 避免 "delivery"/"olive" 之类的普通类型被误判。
     */
    static boolean isLiveTypeKey(String typeKey) {
        String key = normalize(typeKey);
        if (key == null) {
            return false;
        }
        return key.equals("live")
                || key.startsWith("live_")
                || key.endsWith("_live")
                || key.contains("live_card")
                || key.contains("livecard")
                || key.contains("live_thread")
                || key.contains("livethread")
                || key.contains("live_topic")
                || key.contains("livetopic")
                || key.contains("live_room")
                || key.contains("liveroom")
                || key.contains("ala_live")
                || key.contains("alalive");
    }

    /** 热点类型键：同样用明确 token，避免 "photo" 里含 "hot" 被误判。 */
    static boolean isHotTypeKey(String typeKey) {
        String key = normalize(typeKey);
        if (key == null) {
            return false;
        }
        return key.equals("hot")
                || key.startsWith("hot_")
                || key.endsWith("_hot")
                || key.contains("hot_topic")
                || key.contains("hottopic")
                || key.contains("hot_card")
                || key.contains("hotcard")
                || key.contains("hotspot")
                || key.contains("hot_list")
                || key.contains("hotlist")
                || key.contains("hot_rank")
                || key.contains("hot_search")
                || key.contains("realtime_hot");
    }

    private static String normalize(String typeKey) {
        if (typeKey == null) {
            return null;
        }
        String key = typeKey.trim().toLowerCase(Locale.ROOT);
        return key.isEmpty() ? null : key;
    }

    private static View findRecyclerView(View view, Object adapter) {
        if (view == null) {
            return null;
        }
        if (isRecyclerView(view) && adapterOf(view) == adapter) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findRecyclerView(group.getChildAt(i), adapter);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
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
        return invoke(findMethod(view, "getAdapter"), view);
    }

    /**
     * 给目标 RecyclerView 挂 attach/detach 监听：attach 时按数据重新判定，
     * detach 时恢复原始 visibility/height，解决 ViewHolder 复用空白行。
     * 监听器只挂一次；失败时静默降级为周期性 sweep。
     */
    private static void ensureChildWatcher(View recycler, HookEnv env) {
        synchronized (WATCHED) {
            if (WATCHED.containsKey(recycler)) {
                return;
            }
        }
        try {
            Class<?> listenerClass = Class.forName(
                    "androidx.recyclerview.widget.RecyclerView$OnChildAttachStateChangeListener",
                    false, recycler.getClass().getClassLoader());
            Object listener = Proxy.newProxyInstance(listenerClass.getClassLoader(),
                    new Class<?>[]{listenerClass}, (proxy, method, args) -> {
                        try {
                            String name = method.getName();
                            if ("onChildViewAttachedToWindow".equals(name)) {
                                if (args != null && args.length == 1 && args[0] instanceof View) {
                                    onChildAttached(recycler, (View) args[0], env);
                                }
                            } else if ("onChildViewDetachedFromWindow".equals(name)) {
                                if (args != null && args.length == 1 && args[0] instanceof View) {
                                    restore((View) args[0]);
                                }
                            } else if ("hashCode".equals(name)) {
                                return System.identityHashCode(proxy);
                            } else if ("equals".equals(name)) {
                                return proxy == (args != null && args.length == 1 ? args[0] : null);
                            } else if ("toString".equals(name)) {
                                return "TiebaQuietFeedCardWatcher";
                            }
                        } catch (Throwable ignored) {
                            // 回调失败不影响 RecyclerView 自身流程。
                        }
                        return null;
                    });
            Method add = findMethod(recycler, "addOnChildAttachStateChangeListener",
                    listenerClass);
            if (add == null) {
                return;
            }
            add.invoke(recycler, listener);
            synchronized (WATCHED) {
                WATCHED.put(recycler, Boolean.TRUE);
            }
        } catch (Throwable ignored) {
            // 某些版本没有该监听器或 Proxy 不可用：保持周期性 sweep 兜底。
        }
    }

    private static void onChildAttached(View recycler, View child, HookEnv env) {
        Object adapter = adapterOf(recycler);
        SweepState state = stateOf(adapter);
        if (state == null || (!state.live && !state.hot)) {
            // 没有活跃策略时不判定，避免在链结束后重新引入误删。
            return;
        }
        resolveMethods(recycler, adapter, state);
        evaluateChild(child, recycler, adapter, env, state);
    }

    private static void hide(View view) {
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (!(view.getTag(hiddenTag()) instanceof OriginalState)) {
            int height = lp != null ? lp.height : ViewGroup.LayoutParams.WRAP_CONTENT;
            view.setTag(hiddenTag(), new OriginalState(view.getVisibility(), height));
        }
        if (view.getVisibility() != View.GONE) {
            view.setVisibility(View.GONE);
        }
        if (lp != null && lp.height != 0) {
            lp.height = 0;
            view.setLayoutParams(lp);
        }
    }

    /** 只恢复本类隐藏过的 View；普通 View 原样不动。 */
    private static void restore(View view) {
        Object tag = view.getTag(hiddenTag());
        if (!(tag instanceof OriginalState)) {
            return;
        }
        OriginalState original = (OriginalState) tag;
        view.setTag(hiddenTag(), null);
        if (view.getVisibility() != original.visibility) {
            view.setVisibility(original.visibility);
        }
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp != null && lp.height != original.height) {
            lp.height = original.height;
            view.setLayoutParams(lp);
        }
    }

    private static synchronized int hiddenTag() {
        if (sHiddenTag == null) {
            try {
                sHiddenTag = View.generateViewId();
            } catch (Throwable t) {
                // 极端情况下退回固定值；只作为 View tag key 使用。
                sHiddenTag = 0x7f0f0001;
            }
        }
        return sHiddenTag;
    }

    /**
     * 反射查方法：先找 public（含继承），再沿父类找 declared。
     * 返回 null 时调用方一律 fail-open。
     */
    private static Method findMethod(Object target, String name, Class<?>... params) {
        if (target == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(name, params);
            method.setAccessible(true);
            return method;
        } catch (Throwable ignored) {
            // fall through
        }
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Method method = c.getDeclaredMethod(name, params);
                method.setAccessible(true);
                return method;
            } catch (Throwable ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    private static Object invoke(Method method, Object receiver, Object... args) {
        if (method == null || receiver == null) {
            return null;
        }
        try {
            return method.invoke(receiver, args);
        } catch (Throwable t) {
            return null;
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
