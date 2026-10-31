package io.github.yigenhuobah.tiebaquiet;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.fragment.app.Fragment;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import io.github.libxposed.service.XposedService;
import io.github.yigenhuobah.tiebaquiet.feature.phase2.SignInFeature;

/**
 * 主设置页：状态卡 + 四态判断 + 异常横幅 + 一键签到 + AD/UI/PV/EN 分组 +
 * 二级页入口 + 页脚。
 *
 * <p>主页面零诊断：这里不展示逐功能 registered/hit/skip，只保留状态卡和聚合横幅；
 * 细节统一放到 {@link DiagnosticsFragment}。
 *
 * <p>线程模型：ConfigStore 构造、remote prefs 读取、PackageManager 查询、
 * failedFeatureCount 与框架状态查询全部在后台单线程执行，结果回主线程更新 UI。
 */
public class SettingsFragment extends PreferenceFragmentCompat {

    private static final String TAG = "TiebaQuiet";
    private static final String TARGET = ConfigStore.TARGET_PACKAGE;
    private static final String TARGET_VERSION = ConfigStore.TARGET_VERSION;

    /**
     * 一键签到（热启动）：目标进程已存活、接收器早已注册时，维持原固定延时发送。
     * 冷启动走 {@link #pollSignTargetAndBroadcast} 的就绪轮询，不再用固定延时冒充
     * “等待主界面就绪”。
     */
    private static final long SIGN_BROADCAST_DELAY_MS = 1200L;

    /** 冷启动轮询间隔：接收器注册线程按 500ms 重试，取同一节奏。 */
    private static final long SIGN_POLL_INTERVAL_MS = 500L;

    /**
     * 冷启动总超时：接收器最迟在 30×500ms 后注册，首次回传再晚 2-5s；
     * 超时后兜底尽力一发，避免永远不发。
     */
    private static final long SIGN_BROADCAST_TIMEOUT_MS = 25_000L;

    /** 失败功能数达到该阈值才显示异常横幅。 */
    private static final int FAILED_BANNER_THRESHOLD = 2;

    /**
     * 设置页的后台单线程：binder、PackageManager 与 prefs 读取都放这里，
     * 避免框架卡顿时拖住设置页首帧，也避免主线程同步 I/O。
     */
    private static final ExecutorService BG_EXECUTOR =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "tiebaquiet-settings-bg");
                t.setDaemon(true);
                return t;
            });

    private ConfigStore mStore;
    /** mStore 对应的 service 实例；同一实例内 remote prefs 不变，可复用。 */
    private XposedService mStoreService;

    /** 后台刷新句柄：新刷新提交前取消旧的，onDestroyView 取消全部。 */
    private Future<?> mLoadFuture;
    /** 刷新代次：旧任务即使无法中断，也不能覆盖新任务的结果。 */
    private int mLoadGeneration;

    /**
     * 目标版本：每次刷新重查（升级后能及时纠正），保留最近一次成功读到的值；
     * 查询失败不缓存 null，下次刷新重试。只在后台单线程读写。
     */
    private String mTargetVersion;
    private boolean mModuleVersionQueried;
    private String mModuleVersion;

    /**
     * 已接受但尚未收到 commit 结果的写入。刷新若先读到旧值，不能用它覆盖
     * 用户刚切换的 UI；回调到达后按 key 清理。
     */
    private final Map<String, Boolean> mPendingWrites = new HashMap<>();

    /**
     * 已确认成功落盘的写入：key → 值与单调令牌。只由成功回调更新（S5：refresh
     * 不得污染 confirmed）；若刷新在这些写入之前读取，applyLoadedState 优先用它，
     * 避免写回调先删掉 pending、随后旧读值把刚写成功的开关改回原状。
     */
    private final Map<String, ConfirmedWrite> mConfirmedWrites = new HashMap<>();

    /** 单调写令牌：每次成功落盘递增，供刷新记录读取时点后比较。仅主线程读写。 */
    private long mWriteToken;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.prefs, rootKey);
        bindFeatures();
        bindPages();
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public void onDestroyView() {
        // view 已销毁，后台结果不再有用；cancel(true) 让阻塞中的 binder 尽快返回。
        if (mLoadFuture != null) {
            mLoadFuture.cancel(true);
            mLoadFuture = null;
        }
        mLoadGeneration++;
        mPendingWrites.clear();
        super.onDestroyView();
    }

    /** 把 Feature 的标题/说明/开关接到 XML 声明的偏好项上。 */
    private void bindFeatures() {
        for (Feature f : FeatureRegistry.all()) {
            Preference p = findPreference(f.key);
            if (p == null) {
                Log.e(TAG, "preference missing for " + f.key);
                continue;
            }
            p.setTitle(f.titleRes);
            p.setSummary(f.summaryRes);

            if (f.blocked()) {
                // blocked 功能保留编号和行位，但不注册 hook、不允许切换。
                p.setEnabled(false);
                p.setSummary(getString(R.string.f_blocked_summary, f.blockedReason));
                continue;
            }

            // 后台确认 remote 可用之前不允许切换，避免首帧就能点到“写不进去”的开关。
            p.setEnabled(false);
            p.setOnPreferenceChangeListener((pref, value) -> {
                if (!(value instanceof Boolean)) {
                    return true;
                }
                final boolean next = (Boolean) value;
                final ConfigStore store = mStore;
                if (store == null) {
                    Toast.makeText(requireContext(), R.string.action_write_failed,
                            Toast.LENGTH_SHORT).show();
                    return false;
                }
                mPendingWrites.put(f.key, next);
                if (!store.put(f.key, next, (key, success) -> {
                    if (success) {
                        // confirmed 只由成功落盘更新，令牌单调递增；
                        // 刷新若在这些写入之前读取，不得用旧读值覆盖 UI。
                        mWriteToken++;
                        mConfirmedWrites.put(key, new ConfirmedWrite(next, mWriteToken));
                    }
                    Boolean pending = mPendingWrites.get(key);
                    if (pending == null || pending.booleanValue() != next) {
                        // 已有更新的写入排队，本次结果不再驱动 UI。
                        return;
                    }
                    mPendingWrites.remove(key);
                    if (success) {
                        return;
                    }
                    // commit 失败：Toast 并回滚到最近一次确认落盘的值，而不是乐观
                    // 更新前的 before（快速连点时它可能从未落盘）。
                    if (!isAdded() || getContext() == null) {
                        return;
                    }
                    Toast.makeText(requireContext(), R.string.action_write_failed,
                            Toast.LENGTH_SHORT).show();
                    ConfirmedWrite confirmed = mConfirmedWrites.get(key);
                    if (confirmed != null) {
                        Preference current = findPreference(key);
                        if (current instanceof MaterialSwitchPreference) {
                            ((MaterialSwitchPreference) current).setCheckedValue(confirmed.value);
                        }
                        return;
                    }
                    // 本会话没有该 key 的确认值：UI 以真实存储为准，刷新回读。
                    refresh();
                })) {
                    mPendingWrites.remove(f.key);
                    Toast.makeText(requireContext(), R.string.action_write_failed,
                            Toast.LENGTH_SHORT).show();
                    // 返回 false：MaterialSwitch 回滚到旧状态。
                    return false;
                }
                return true;
            });
        }
    }

    /** 一键签到、二级页入口与页脚。 */
    private void bindPages() {
        Preference status = findPreference("status");
        if (status != null) {
            status.setTitle(R.string.status_connecting);
            status.setSummary(getString(R.string.status_detail_connecting,
                    getString(R.string.status_unknown_version)));
        }

        Preference sign = findPreference("action_sign_in");
        if (sign != null) {
            sign.setEnabled(false);
            sign.setOnPreferenceClickListener(p -> {
                triggerSignIn();
                return true;
            });
        }

        Preference diag = findPreference("page_diagnostics");
        if (diag != null) {
            diag.setOnPreferenceClickListener(p -> {
                openSecondary(new DiagnosticsFragment());
                return true;
            });
        }
        Preference about = findPreference("page_about");
        if (about != null) {
            about.setOnPreferenceClickListener(p -> {
                openSecondary(new AboutFragment());
                return true;
            });
        }
        Preference footer = findPreference("footer");
        if (footer != null) {
            String target = getString(R.string.app_target_name);
            footer.setSummary(getString(R.string.footer_restart, target)
                    + "\n" + getString(R.string.footer_compat, target, TARGET_VERSION));
        }
    }

    /**
     * 一键签到：EN-08 开关关闭时提示先开启；开启时直接拉起贴吧，再等目标进程就绪后
     * 发显式广播。不再静默把开关写成 true，也不再因为按钮点击而改变用户配置。
     */
    private void triggerSignIn() {
        final ConfigStore store = mStore;
        if (store == null || !store.remote()) {
            Toast.makeText(requireContext(), R.string.action_write_failed,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (!store.get(SignInFeature.KEY, false)) {
            Toast.makeText(requireContext(), R.string.action_sign_in_disabled,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        launchTargetAndBroadcast(requireActivity(),
                requireContext().getApplicationContext());
    }

    /**
     * 拉起贴吧后按进程状态发签到广播：接收器在目标进程 onPackageReady 之后才动态注册
     * （Application 创建前最多重试 30×500ms），注册前的显式广播被系统静默丢弃且无异常。
     * 目标进程已存活（框架注入命中）时接收器早已注册，维持固定延时发送；冷启动时改为
     * 轮询回传时间戳，等目标进程完成初始化（新会话首次回传，必然晚于接收器注册）再发，
     * 超时兜底尽力一发。
     */
    private void launchTargetAndBroadcast(final Activity host, final Context appContext) {
        final long launchAtMs = System.currentTimeMillis();
        BG_EXECUTOR.execute(() -> {
            Intent launch = null;
            try {
                launch = appContext.getPackageManager().getLaunchIntentForPackage(TARGET);
            } catch (Throwable t) {
                Log.w(TAG, "getLaunchIntent failed", t);
            }
            // status() 是同步 binder 查询，必须在后台线程；查询失败按冷启动处理。
            final boolean targetRunning = isTargetRunning();
            final Intent intent = launch;
            host.runOnUiThread(() -> {
                if (!isAdded() || getActivity() != host) {
                    return;
                }
                if (intent == null) {
                    Toast.makeText(requireContext(), R.string.action_launch_failed,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    appContext.startActivity(intent);
                } catch (Throwable t) {
                    // 贴吧被禁用/冻结或 launch intent 被系统拒绝时，设置页不能跟着崩。
                    Log.w(TAG, "launch failed", t);
                    Toast.makeText(requireContext(), R.string.action_launch_failed,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                if (targetRunning) {
                    postSignBroadcast(appContext, SIGN_BROADCAST_DELAY_MS);
                    return;
                }
                pollSignTargetAndBroadcast(host, appContext, launchAtMs);
            });
        });
    }

    /** 目标进程是否已带着模块运行（getRunningTargets 命中）；调用方必须在后台线程。 */
    private static boolean isTargetRunning() {
        try {
            return ServiceBridge.status(TARGET).isInjected();
        } catch (Throwable t) {
            Log.w(TAG, "query running targets failed", t);
            return false;
        }
    }

    /**
     * 冷启动轮询：目标进程初始化完成（{@code meta.updated_at} 晚于本次拉起时刻）
     * 后发送广播；超时兜底尽力一发。每轮的 prefs 读取都在后台线程做，决策回主线程。
     */
    private void pollSignTargetAndBroadcast(final Activity host, final Context appContext,
                                            final long launchAtMs) {
        BG_EXECUTOR.execute(() -> {
            long updatedAt = 0L;
            try {
                updatedAt = StatusProvider.reportUpdatedAt(appContext);
            } catch (Throwable t) {
                Log.w(TAG, "read report updatedAt failed", t);
            }
            final long seen = updatedAt;
            final long elapsedMs = System.currentTimeMillis() - launchAtMs;
            host.runOnUiThread(() -> {
                if (!isAdded() || getActivity() != host) {
                    // 页面已销毁：停止轮询。
                    return;
                }
                int step = signBroadcastStep(seen, launchAtMs, elapsedMs,
                        SIGN_BROADCAST_TIMEOUT_MS);
                if (step == SIGN_STEP_WAIT) {
                    new Handler(Looper.getMainLooper()).postDelayed(() ->
                            pollSignTargetAndBroadcast(host, appContext, launchAtMs),
                            SIGN_POLL_INTERVAL_MS);
                    return;
                }
                if (step == SIGN_STEP_READY) {
                    // 接收器注册（500ms 轮询）先于首次回传（≥2s 初始延时），
                    // 再等一个轮询间隔作缓冲后发送。
                    postSignBroadcast(appContext, SIGN_POLL_INTERVAL_MS);
                    return;
                }
                // 超时兜底：接收器可能仍未注册，尽力发一次。
                postSignBroadcast(appContext, 0L);
            });
        });
    }

    /** 延时发送签到广播；发送失败不重试（接收器未注册时系统静默丢弃，无异常可接）。 */
    private static void postSignBroadcast(final Context appContext, long delayMs) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                appContext.sendBroadcast(
                        new Intent(SignInFeature.ACTION_SIGN).setPackage(TARGET));
            } catch (Throwable t) {
                Log.w(TAG, "sign broadcast failed", t);
            }
        }, delayMs);
    }

    /** 重新读取框架状态与开关值。service 绑定后由 MainActivity 调一次。 */
    void refresh() {
        if (getContext() == null) {
            return;
        }
        final Context appContext = requireContext().getApplicationContext();
        final Activity host = requireActivity();
        final XposedService service = ServiceBridge.get();
        final ConfigStore cachedStore = mStore;
        final XposedService cachedService = mStoreService;
        final boolean reuse = ConfigStorePolicy.reuseStore(
                cachedStore != null, cachedService, service);
        final int generation = ++mLoadGeneration;
        // 读取时点令牌：在此之后才确认落盘的写入，不得被本次读到的旧值覆盖。
        final long readToken = mWriteToken;

        if (mLoadFuture != null) {
            mLoadFuture.cancel(false);
        }
        mLoadFuture = BG_EXECUTOR.submit(() -> {
            ConfigStore store = reuse ? cachedStore : new ConfigStore(appContext);
            Map<String, Boolean> values = new HashMap<>();
            for (Feature f : FeatureRegistry.all()) {
                values.put(f.key, store.get(f.key, f.defaultEnabled));
            }
            String targetVersion = targetVersion(appContext);
            String moduleVersion = moduleVersion(appContext);
            int failed = StatusProvider.failedFeatureCount(appContext, store);
            ServiceBridge.Status status = ServiceBridge.status(TARGET);

            host.runOnUiThread(() -> {
                if (generation != mLoadGeneration || !isAdded() || getActivity() != host) {
                    return;
                }
                mStore = store;
                mStoreService = service;
                applyLoadedState(store.remote(), values, targetVersion, moduleVersion,
                        failed, status, readToken);
            });
        });
    }

    /** 后台结果回主线程后一次性更新开关、状态卡与横幅。 */
    private void applyLoadedState(boolean writable, Map<String, Boolean> values,
                                  String targetVersion, String moduleVersion,
                                  int failedCount, ServiceBridge.Status status,
                                  long readToken) {
        for (Feature f : FeatureRegistry.all()) {
            Preference p = findPreference(f.key);
            if (p instanceof MaterialSwitchPreference) {
                MaterialSwitchPreference sw = (MaterialSwitchPreference) p;
                ConfirmedWrite confirmed = mConfirmedWrites.get(f.key);
                Boolean shown = resolveDisplayedValue(mPendingWrites.get(f.key), confirmed,
                        readToken, values.get(f.key));
                sw.setCheckedValue(shown != null ? shown : f.defaultEnabled);
                // 确认值已被本次刷新读到：清理，之后的刷新以真实存储为准。
                if (confirmed != null && confirmed.token <= readToken) {
                    mConfirmedWrites.remove(f.key);
                }
                // 框架没绑定时写不进去，置灰避免“改了没用”；blocked 始终置灰。
                sw.setEnabled(writable && !f.blocked());
            }
        }

        Preference sign = findPreference("action_sign_in");
        if (sign != null) {
            sign.setEnabled(writable);
        }

        updateBanner(targetVersion, failedCount);
        updateStatusCard(status, writable, targetVersion, moduleVersion);
    }

    /**
     * 异常横幅：版本不匹配，或“已启用且明确失败”的功能数 ≥ 2。
     * 不新增第五种状态卡状态。
     */
    private void updateBanner(String targetVersion, int failedCount) {
        Preference banner = findPreference("warning");
        if (banner == null || getContext() == null) {
            return;
        }
        if (targetVersion != null && !TARGET_VERSION.equals(targetVersion)) {
            banner.setSummary(getString(R.string.status_banner_version, targetVersion,
                    TARGET_VERSION));
            banner.setVisible(true);
            return;
        }
        if (failedCount >= FAILED_BANNER_THRESHOLD) {
            banner.setSummary(getString(R.string.status_banner_failed, failedCount));
            banner.setVisible(true);
        } else {
            // 目标应用未安装或失败数不足：不显示横幅，避免误导。
            banner.setVisible(false);
        }
    }

    private void updateStatusCard(ServiceBridge.Status status, boolean writable,
                                  String targetVersion, String moduleVersion) {
        Preference p = findPreference("status");
        if (p == null) {
            return;
        }
        String shownVersion = targetVersion != null
                ? targetVersion : getString(R.string.status_unknown_version);
        String targetApp = getString(R.string.app_target_name);
        if (!status.isConnected()) {
            p.setTitle(R.string.status_connecting);
            p.setSummary(getString(R.string.status_detail_connecting, shownVersion));
        } else if (!status.isScoped()) {
            p.setTitle(R.string.status_inactive);
            p.setSummary(getString(R.string.status_detail_no_scope,
                    status.getApiVersion(), targetApp, shownVersion, moduleVersion));
        } else if (status.isInjected()) {
            p.setTitle(R.string.status_active);
            p.setSummary(getString(R.string.status_detail_active,
                    status.getApiVersion(), targetApp, shownVersion, moduleVersion));
        } else {
            p.setTitle(R.string.status_connected);
            p.setSummary(getString(R.string.status_detail_not_running,
                    status.getApiVersion(), targetApp, shownVersion, moduleVersion));
        }
        appendRemoteState(p, writable);
    }

    /** remote prefs 可用性挂在状态卡副标题第二行，抓“开关写不进框架”这个坑。 */
    private void appendRemoteState(Preference status, boolean writable) {
        status.setSummary(status.getSummary() + "\n"
                + getString(writable ? R.string.status_remote_ok : R.string.status_remote_fail));
    }

    /** 打开二级页。MainActivity 在 onBackStackChanged 中同步工具栏返回箭头。 */
    private void openSecondary(Fragment fragment) {
        if (!isAdded()) {
            return;
        }
        getParentFragmentManager().beginTransaction()
                .replace(R.id.settings_container, fragment)
                .addToBackStack(null)
                .commit();
    }

    /** 一次成功落盘的写入：值 + 单调令牌（纯数据，供 JVM 单测复用判定逻辑）。 */
    static final class ConfirmedWrite {
        final boolean value;
        final long token;

        ConfirmedWrite(boolean value, long token) {
            this.value = value;
            this.token = token;
        }
    }

    /**
     * 纯逻辑：刷新应用时该开关应显示的值。
     *
     * <p>pending（已受理未落盘）优先；其次是读取时点之后才确认落盘的值——刷新的后台
     * 读取可能发生在 commit 之前，写回调又先于 apply 到达并删掉 pending，旧读值不得
     * 覆盖刚写成功的开关；确认值不存在或已随本次读取落盘时，才用刷新读到的值。
     */
    static Boolean resolveDisplayedValue(Boolean pending, ConfirmedWrite confirmed,
                                         long readToken, Boolean refreshed) {
        if (pending != null) {
            return pending;
        }
        if (confirmed != null && confirmed.token > readToken) {
            return confirmed.value;
        }
        return refreshed;
    }

    /** 签到发送决策步骤：继续等待 / 就绪（拉起后有新回传）/ 超时兜底。 */
    static final int SIGN_STEP_WAIT = 0;
    static final int SIGN_STEP_READY = 1;
    static final int SIGN_STEP_TIMEOUT = 2;

    /**
     * 纯逻辑：冷启动签到广播的下一步动作。仅当本次拉起之后有新回传才算就绪——
     * 回传由目标进程初始化后发出，接收器注册（500ms 轮询）必然早于首次回传；
     * 旧会话的回传时间戳（≤ launchAtMs）不能当就绪信号。超时后兜底发送。
     */
    static int signBroadcastStep(long reportUpdatedAt, long launchAtMs,
                                 long elapsedMs, long timeoutMs) {
        if (reportUpdatedAt > launchAtMs) {
            return SIGN_STEP_READY;
        }
        if (elapsedMs >= timeoutMs) {
            return SIGN_STEP_TIMEOUT;
        }
        return SIGN_STEP_WAIT;
    }

    /**
     * 纯逻辑：目标版本刷新决策。查询成功才覆盖；失败沿用最近一次成功值，不缓存 null，
     * 下次刷新自动重试。
     */
    static String targetVersionAfterQuery(String queried, String cached) {
        return queried != null ? queried : cached;
    }

    /**
     * 目标版本每次刷新重查：贴吧可在模块进程存活期间升级，一次性缓存会一直停在旧值、
     * 漏报版本不匹配横幅；查询失败沿用最近一次成功值，下次刷新重试。
     */
    private String targetVersion(Context context) {
        mTargetVersion = targetVersionAfterQuery(queryVersionName(context, TARGET),
                mTargetVersion);
        return mTargetVersion;
    }

    /** 模块版本在进程生命周期内不变，查询一次即可。 */
    private String moduleVersion(Context context) {
        if (!mModuleVersionQueried) {
            String v = queryVersionName(context, context.getPackageName());
            mModuleVersion = v != null ? v : "?";
            mModuleVersionQueried = true;
        }
        return mModuleVersion;
    }

    private static String queryVersionName(Context context, String packageName) {
        try {
            PackageInfo pi = context.getPackageManager().getPackageInfo(packageName, 0);
            return pi.versionName;
        } catch (Throwable t) {
            return null;
        }
    }
}
