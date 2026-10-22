package io.github.yigenhuobah.tiebaquiet;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import io.github.libxposed.service.HookedTarget;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * 模块进程侧的 XposedService 绑定。
 *
 * <p>框架会先投一个 api=0 的占位 binder，必须丢弃；只有 api ≥ 101 的实例才可用。
 * 开关必须通过 {@link XposedService#getRemotePreferences} 写入，目标进程读的是那份存储。
 */
public final class ServiceBridge {

    private static final String TAG = "TiebaQuiet";
    private static final String AUTHORITY_SUFFIX = ".XposedService";

    private static final AtomicReference<XposedService> sService = new AtomicReference<>();
    private static boolean sListenerRegistered;

    /** service 真正绑定/断开后要跑的一次性重绘；由 UI 层设置。 */
    private static volatile Runnable sOnBound = new Runnable() {
        @Override
        public void run() {
        }
    };

    private ServiceBridge() {
    }

    /** UI 层在等 service 绑定时挂一个重绘回调；传 null 取消。 */
    public static void setOnBound(Runnable runnable) {
        sOnBound = runnable != null ? runnable : new Runnable() {
            @Override
            public void run() {
            }
        };
    }

    /**
     * 注册服务监听并补投一次 SendBinder。
     *
     * <p>manifest 里的 {@code io.github.libxposed.service.XposedProvider} 负责把 binder
     * 送进模块进程（authority 必须是 {@code <applicationId>.XposedService}）。
     */
    public static synchronized void bind(Context context) {
        if (sListenerRegistered) {
            if (sService.get() == null && context != null) {
                poke(context);
            }
            return;
        }
        sListenerRegistered = true;
        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override
            public void onServiceBind(XposedService service) {
                int api = safeApi(service);
                if (api < 101) {
                    Log.i(TAG, "ignore placeholder binder, api=" + api);
                    return;
                }
                sService.set(service);
                Log.i(TAG, "service bound, api=" + api);
                runOnBound();
            }

            @Override
            public void onServiceDied(XposedService service) {
                if (sService.compareAndSet(service, null)) {
                    Log.w(TAG, "service died");
                    runOnBound();
                }
            }
        });
        if (sService.get() == null && context != null) {
            poke(context);
        }
    }

    public static XposedService get() {
        return sService.get();
    }

    /**
     * 拿一份 remote preferences；service 未绑定、框架无 PROP_CAP_REMOTE 或读取失败返回 null。
     */
    public static SharedPreferences remotePreferences(String group) {
        XposedService service = sService.get();
        if (service == null) {
            Log.w(TAG, "remote prefs requested before service bound: " + group);
            return null;
        }
        try {
            if ((service.getFrameworkProperties() & XposedService.PROP_CAP_REMOTE) == 0L) {
                Log.w(TAG, "framework lacks PROP_CAP_REMOTE: " + group);
                return null;
            }
            return service.getRemotePreferences(group);
        } catch (Throwable t) {
            Log.w(TAG, "remote prefs unavailable: " + group, t);
            return null;
        }
    }

    /** 状态卡四态判定所需的框架信息。 */
    public static final class Status {
        public final boolean connected;
        public final int apiVersion;
        public final boolean scoped;
        public final boolean injected;

        Status(boolean connected, int apiVersion, boolean scoped, boolean injected) {
            this.connected = connected;
            this.apiVersion = apiVersion;
            this.scoped = scoped;
            this.injected = injected;
        }

        public boolean isConnected() {
            return connected;
        }

        public int getApiVersion() {
            return apiVersion;
        }

        public boolean isScoped() {
            return scoped;
        }

        public boolean isInjected() {
            return injected;
        }
    }

    /**
     * 同步 binder 查询，调用方必须放在后台线程。
     * 只有 getRunningTargets() 命中目标主进程或子进程才算“已注入”。
     */
    public static Status status(String targetPackage) {
        XposedService service = sService.get();
        if (service == null) {
            return new Status(false, 0, false, false);
        }
        int api = safeApi(service);
        boolean scoped = false;
        try {
            List<String> scope = service.getScope();
            scoped = scope != null && scope.contains(targetPackage);
        } catch (Throwable t) {
            Log.w(TAG, "getScope failed", t);
        }
        boolean injected = false;
        try {
            List<HookedTarget> targets = service.getRunningTargets();
            if (targets != null) {
                for (HookedTarget target : targets) {
                    String name = target.getProcessName();
                    if (targetPackage.equals(name) || name.startsWith(targetPackage + ":")) {
                        injected = true;
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "getRunningTargets failed", t);
        }
        return new Status(true, api, scoped, injected);
    }

    /** 向自己的 provider 发一次 call("SendBinder")，让框架重新投递 binder。 */
    public static void poke(Context context) {
        try {
            Bundle extras = new Bundle();
            extras.putBinder("binder", new android.os.Binder());
            context.getContentResolver().call(
                    android.net.Uri.parse("content://" + context.getPackageName()
                            + AUTHORITY_SUFFIX),
                    "SendBinder", null, extras);
        } catch (Throwable t) {
            Log.w(TAG, "poke failed", t);
        }
    }

    private static void runOnBound() {
        try {
            sOnBound.run();
        } catch (Throwable t) {
            Log.w(TAG, "onBound callback failed", t);
        }
    }

    private static int safeApi(XposedService service) {
        try {
            return service.getApiVersion();
        } catch (Throwable t) {
            return 0;
        }
    }
}
