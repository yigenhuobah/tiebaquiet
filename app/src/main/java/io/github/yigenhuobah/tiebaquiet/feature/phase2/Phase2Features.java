package io.github.yigenhuobah.tiebaquiet.feature.phase2;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.yigenhuobah.tiebaquiet.Feature;
import io.github.yigenhuobah.tiebaquiet.FeatureRegistry;
import io.github.yigenhuobah.tiebaquiet.HookEnv;

/**
 * 第二批 7 个功能的安装器（含一键签到）。
 *
 * <p>逻辑与 Kotlin 版逐项对应：广告请求断源、只看楼主、楼层倒序、去水印、
 * 原图入口、视频直链复制、一键签到。
 */
public final class Phase2Features {

    private static final String TAG = Feature.TAG;

    /** 视频直链去重：同一个 URL 只复制一次。 */
    private static volatile String sLastVideoUrl;

    private Phase2Features() {
    }

    /** 二期公用：从 hook 进程拿到宿主 Application Context。 */
    static final class HostApp {

        private HostApp() {
        }

        static Context context() {
            try {
                Class<?> at = Class.forName("android.app.ActivityThread");
                Object app = at.getMethod("currentApplication").invoke(null);
                return app instanceof Context ? (Context) app : null;
            } catch (Throwable t) {
                return null;
            }
        }

        static void toast(final String message) {
            final Context ctx = context();
            if (ctx == null) {
                return;
            }
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    /**
     * 广告请求源头掐断：
     * FunAd/ADX：BasePidLoader.load → false；
     * 聚屏/插屏/激励（Nad）：直接回调 "Blocked by client"；
     * 贴吧自研异步 AFD：qy8.c → 失败回调。
     */
    public static boolean installAdRequestBlock(HookEnv env) {
        Feature f = FeatureRegistry.byKey("ad_request_block");
        boolean any = false;

        if (f.replace(env, env.anchors.method("pid_loader_load"),
                "pid_loader_load", Boolean.FALSE)) {
            any = true;
        }

        // 信息流广告插入闸门：请求被拦后仍可能有缓存物料，这里再断一次插入。
        if (f.replace(env, env.anchors.method("ad_insert_gate"),
                "ad_insert_gate", Boolean.FALSE)) {
            any = true;
        }
        // 推广 App 卡单卡展示闸门。
        if (f.replace(env, env.anchors.method("ad_card_gate"),
                "ad_card_gate", Boolean.FALSE)) {
            any = true;
        }

        Method nad = env.anchors.method("nad_requester_a");
        if (nad != null) {
            boolean installed = f.hook(env, nad, "nad_requester_a",
                    new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            List<Object> args = chain.getArgs();
                            Object callback = args.size() > 1 ? args.get(1) : null;
                            try {
                                Class<?> errorClass = env.loader.loadClass(
                                        "com.baidu.nadcore.requester.NadRequester$Error");
                                Object error = errorClass.getConstructor(String.class)
                                        .newInstance("Blocked by client");
                                Class<?> iface = env.loader.loadClass(
                                        "com.baidu.nadcore.requester.NadRequester$b");
                                iface.getMethod("a", errorClass).invoke(callback, error);
                            } catch (Throwable ignored) {
                                // 回调失败也不影响：请求已不发出。
                            }
                            return null;
                        }
                    });
            any = any || installed;
        }

        Method qy8 = env.anchors.method("qy8_c");
        if (qy8 != null) {
            boolean installed = f.hook(env, qy8, "qy8_c",
                    new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            List<Object> args = chain.getArgs();
                            Object request = args.size() > 0 ? args.get(0) : null;
                            Object callback = args.size() > 1 ? args.get(1) : null;
                            try {
                                Class<?> requestClass = env.loader.loadClass(
                                        "com.baidu.tieba.r18");
                                Class<?> iface = env.loader.loadClass(
                                        "com.baidu.tieba.x18");
                                iface.getMethod("c", requestClass, int.class, String.class)
                                        .invoke(callback, request, Integer.valueOf(0), "blocked");
                            } catch (Throwable ignored) {
                            }
                            return null;
                        }
                    });
            any = any || installed;
        }

        return any;
    }

    /** 只看楼主：请求里强制 lz=1。 */
    public static boolean installHostOnly(HookEnv env) {
        final Feature f = FeatureRegistry.byKey("host_only");
        Method method = env.anchors.method("add_lz");
        return f.hook(env, method, "add_lz", new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object builder = chain.getArgs().size() > 0 ? chain.getArgs().get(0) : null;
                Object result = chain.proceed();
                try {
                    if (builder != null) {
                        Field field = builder.getClass().getField("lz");
                        field.set(builder, Integer.valueOf(1));
                    }
                } catch (Throwable ignored) {
                }
                return result;
            }
        });
    }

    /** 楼层倒序：请求里强制 r=1。 */
    public static boolean installReverseOrder(HookEnv env) {
        final Feature f = FeatureRegistry.byKey("reverse_order");
        Method method = env.anchors.method("add_r");
        return f.hook(env, method, "add_r", new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object builder = chain.getArgs().size() > 0 ? chain.getArgs().get(0) : null;
                Object result = chain.proceed();
                try {
                    if (builder != null) {
                        Field field = builder.getClass().getField("r");
                        field.set(builder, Integer.valueOf(1));
                    }
                } catch (Throwable ignored) {
                }
                return result;
            }
        });
    }

    /** 图片去水印：强制水印类型为“不显示”（默认值 2）。 */
    public static boolean installNoWatermark(HookEnv env) {
        Feature f = FeatureRegistry.byKey("no_watermark");
        return f.replace(env, env.anchors.method("watermark_type"),
                "watermark_type", Integer.valueOf(2));
    }

    /** 原图入口常显：强制“查看原图”按钮可用。 */
    public static boolean installOriginalImage(HookEnv env) {
        Feature f = FeatureRegistry.byKey("original_image");
        return f.replace(env, env.anchors.method("original_button"),
                "original_button", Boolean.TRUE);
    }

    /** 视频直链复制：播放时把直链写入剪贴板。 */
    public static boolean installVideoLink(HookEnv env) {
        final Feature f = FeatureRegistry.byKey("video_link");
        Method method = env.anchors.method("video_set_path");
        return f.hook(env, method, "video_set_path", new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object raw = chain.getArgs().size() > 0 ? chain.getArgs().get(0) : null;
                String url = raw instanceof String ? (String) raw : null;
                if (url != null && !url.isEmpty() && !url.equals(sLastVideoUrl)) {
                    sLastVideoUrl = url;
                    copyToClipboard(url);
                }
                return chain.proceed();
            }
        });
    }

    /** 一键签到：与独立类 {@link SignInFeature} 共用同一套逻辑。 */
    public static boolean installSignIn(HookEnv env) {
        return SignInFeature.install(env);
    }

    private static void copyToClipboard(final String url) {
        final Context ctx = HostApp.context();
        if (ctx == null) {
            return;
        }
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    ClipboardManager cm = (ClipboardManager) ctx
                            .getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("video", url));
                    Toast.makeText(ctx, "视频直链已复制", Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {
                }
            }
        });
    }
}
