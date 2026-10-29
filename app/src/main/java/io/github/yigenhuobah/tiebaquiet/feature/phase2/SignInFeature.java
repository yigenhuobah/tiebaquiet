package io.github.yigenhuobah.tiebaquiet.feature.phase2;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

import io.github.yigenhuobah.tiebaquiet.Feature;
import io.github.yigenhuobah.tiebaquiet.FeatureRegistry;
import io.github.yigenhuobah.tiebaquiet.HookEnv;

/**
 * 一键签到：模块 App 发送显式广播，目标进程内的接收器拉起贴吧自带签到页。
 *
 * <p>接收器常驻注册（{@link #installAlways(HookEnv)}），但触发时读取 EN-08
 * {@code one_key_sign} 开关：关闭时忽略广播，不再把“显式广播”当成无条件用户操作。
 */
public final class SignInFeature {

    public static final String KEY = "one_key_sign";
    public static final String ACTION_SIGN =
            "io.github.yigenhuobah.tiebaquiet.action.SIGN";
    public static final String TARGET_PACKAGE = "com.baidu.tieba";
    /** 签到广播权限：signature 级，只有模块自身（同签名）能发送。 */
    public static final String SIGN_PERMISSION =
            "io.github.yigenhuobah.tiebaquiet.permission.SIGN";

    private static final String TAG = Feature.TAG;
    private static final int REGISTER_ATTEMPTS = 30;
    private static final long REGISTER_RETRY_MS = 500L;

    private SignInFeature() {
    }

    public static boolean install(HookEnv env) {
        if (env.anchors.method("sign_all_load") == null) {
            Feature feature = FeatureRegistry.byKey(KEY);
            if (feature != null) {
                feature.markSkip("锚点缺失 sign_all_load");
            }
            return false;
        }
        return true;
    }

    /**
     * 无论开关状态都注册命令接收器：设置页按钮发显式广播，接收器在触发时
     * 读取 EN-08 开关，关闭则忽略。
     */
    public static boolean installAlways(final HookEnv env) {
        if (env.anchors.method("sign_all_load") == null) {
            return false;
        }
        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || !ACTION_SIGN.equals(intent.getAction())) {
                    return;
                }
                Feature feature = FeatureRegistry.byKey(KEY);
                if (feature == null || !feature.isEnabled(env)) {
                    env.module.log(Log.INFO, TAG, "sign request ignored (switch off)");
                    return;
                }
                env.module.log(Log.INFO, TAG, "sign request received");
                triggerSign(env);
            }
        };
        final IntentFilter filter = new IntentFilter(ACTION_SIGN);
        // onPackageReady 时 Application 可能尚未创建，后台重试拿 Context 再注册。
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                for (int attempt = 0; attempt < REGISTER_ATTEMPTS; attempt++) {
                    Context ctx = Phase2Features.HostApp.context();
                    if (ctx != null) {
                        try {
                            // 6 参版本要求发送方持有 SIGN_PERMISSION；模块 App 在
                            // manifest 中声明并 uses 该 signature 权限，其他应用无法伪造广播。
                            ContextCompat.registerReceiver(ctx, receiver, filter,
                                    SIGN_PERMISSION, null, ContextCompat.RECEIVER_EXPORTED);
                            env.module.log(Log.INFO, TAG,
                                    "sign receiver registered (attempt " + (attempt + 1) + ")");
                            return;
                        } catch (Throwable t) {
                            env.module.log(Log.WARN, TAG,
                                    "sign receiver register failed", t);
                            return;
                        }
                    }
                    try {
                        Thread.sleep(REGISTER_RETRY_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                env.module.log(Log.WARN, TAG,
                        "sign receiver: context unavailable after retries");
            }
        });
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    private static void triggerSign(final HookEnv env) {
        final Context ctx = Phase2Features.HostApp.context();
        if (ctx == null) {
            return;
        }
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    Intent intent = new Intent();
                    intent.setClassName(ctx.getPackageName(),
                            "com.baidu.tieba.signall.SignAllForumActivity");
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(intent);
                } catch (Throwable t) {
                    env.module.log(Log.WARN, TAG, "sign launch failed", t);
                }
            }
        });
    }
}
