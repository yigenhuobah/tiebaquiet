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

    /** 一键签到：拉起贴吧后等待主界面就绪，再发显式广播。 */
    private static final long SIGN_BROADCAST_DELAY_MS = 1200L;

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

    /** PackageManager 结果缓存；只在后台单线程读写，避免每次 onResume 重复 IPC。 */
    private boolean mTargetVersionQueried;
    private String mTargetVersion;
    private boolean mModuleVersionQueried;
    private String mModuleVersion;

    /**
     * 已接受但尚未收到 commit 结果的写入。刷新若先读到旧值，不能用它覆盖
     * 用户刚切换的 UI；回调到达后按 key 清理。
     */
    private final Map<String, Boolean> mPendingWrites = new HashMap<>();

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
                final boolean previous = pref instanceof MaterialSwitchPreference
                        ? ((MaterialSwitchPreference) pref).isCheckedValue() : !next;
                final ConfigStore store = mStore;
                if (store == null) {
                    Toast.makeText(requireContext(), R.string.action_write_failed,
                            Toast.LENGTH_SHORT).show();
                    return false;
                }
                mPendingWrites.put(f.key, next);
                if (!store.put(f.key, next, (key, success) -> {
                    Boolean pending = mPendingWrites.get(key);
                    if (pending != null && pending.booleanValue() == next) {
                        mPendingWrites.remove(key);
                    } else {
                        // 已有更新的写入排队，本次结果不再影响 UI。
                        return;
                    }
                    if (success) {
                        return;
                    }
                    // commit 失败：Toast 并回滚，避免 UI 显示“已保存”但目标进程读不到。
                    if (!isAdded() || getContext() == null) {
                        return;
                    }
                    Toast.makeText(requireContext(), R.string.action_write_failed,
                            Toast.LENGTH_SHORT).show();
                    Preference current = findPreference(key);
                    if (current instanceof MaterialSwitchPreference) {
                        ((MaterialSwitchPreference) current).setCheckedValue(previous);
                    }
                    // 再从真实存储回读一次，处理快速连点导致 previous 本身是乐观值的场景。
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
     * 一键签到：EN-08 开关关闭时提示先开启；开启时直接拉起贴吧并在 1.2 秒后发显式广播。
     * 不再静默把开关写成 true，也不再因为按钮点击而改变用户配置。
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

    private void launchTargetAndBroadcast(final Activity host, final Context appContext) {
        BG_EXECUTOR.execute(() -> {
            Intent launch = null;
            try {
                launch = appContext.getPackageManager().getLaunchIntentForPackage(TARGET);
            } catch (Throwable t) {
                Log.w(TAG, "getLaunchIntent failed", t);
            }
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
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        appContext.sendBroadcast(
                                new Intent(SignInFeature.ACTION_SIGN).setPackage(TARGET));
                    } catch (Throwable t) {
                        Log.w(TAG, "sign broadcast failed", t);
                    }
                }, SIGN_BROADCAST_DELAY_MS);
            });
        });
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
                        failed, status);
            });
        });
    }

    /** 后台结果回主线程后一次性更新开关、状态卡与横幅。 */
    private void applyLoadedState(boolean writable, Map<String, Boolean> values,
                                  String targetVersion, String moduleVersion,
                                  int failedCount, ServiceBridge.Status status) {
        for (Feature f : FeatureRegistry.all()) {
            Preference p = findPreference(f.key);
            if (p instanceof MaterialSwitchPreference) {
                MaterialSwitchPreference sw = (MaterialSwitchPreference) p;
                Boolean pending = mPendingWrites.get(f.key);
                if (pending != null) {
                    // 写入尚未 commit，保留用户的乐观值，避免刷新读到的旧值覆盖它。
                    sw.setCheckedValue(pending);
                } else {
                    Boolean value = values.get(f.key);
                    sw.setCheckedValue(value != null ? value : f.defaultEnabled);
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

    /** 目标版本只在首次刷新时查一次；后续 onResume/框架回调复用缓存。 */
    private String targetVersion(Context context) {
        if (!mTargetVersionQueried) {
            mTargetVersion = queryVersionName(context, TARGET);
            mTargetVersionQueried = true;
        }
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
