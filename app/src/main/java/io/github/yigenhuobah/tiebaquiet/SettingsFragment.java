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
     * 状态卡的框架查询是同步 binder，放后台单线程执行，避免框架卡顿时拖住设置页首帧。
     */
    private static final ExecutorService STATUS_EXECUTOR =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "tiebaquiet-settings-status");
                t.setDaemon(true);
                return t;
            });

    private ConfigStore mStore;
    /** mStore 对应的 service 实例；同一实例内 remote prefs 不变，可复用。 */
    private XposedService mStoreService;

    /** 后台状态查询句柄：新查询提交前取消旧的，onDestroyView 取消全部。 */
    private Future<?> mStatusFuture;
    /** 查询代次：旧查询即使无法中断，也不能覆盖新查询的结果。 */
    private int mStatusGeneration;
    /** versionName 在 Fragment 生命周期内不变，只读一次 PackageManager。 */
    private String mVersionName;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.prefs, rootKey);
        mStoreService = ServiceBridge.get();
        mStore = new ConfigStore(requireContext());
        bindFeatures();
        bindPages();
        refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public void onDestroyView() {
        // view 已销毁，后台查询结果不再有用；cancel(true) 让阻塞中的 binder 尽快返回。
        if (mStatusFuture != null) {
            mStatusFuture.cancel(true);
            mStatusFuture = null;
        }
        mStatusGeneration++;
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

            p.setOnPreferenceChangeListener((pref, value) -> {
                if (value instanceof Boolean) {
                    boolean ok = mStore.put(f.key, (Boolean) value);
                    if (!ok) {
                        Toast.makeText(requireContext(), R.string.action_write_failed,
                                Toast.LENGTH_SHORT).show();
                        // 返回 false：MaterialSwitch 回滚到旧状态。
                        return false;
                    }
                }
                return true;
            });
        }
    }

    /** 一键签到、二级页入口与页脚。 */
    private void bindPages() {
        Preference sign = findPreference("action_sign_in");
        if (sign != null) {
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
     * 一键签到：先写 one_key_sign，再拉起贴吧，1.2 秒后发显式广播。
     * 贴吧在后台时不允许其进程直接拉起 Activity，所以必须先带到前台。
     */
    private void triggerSignIn() {
        if (getContext() == null) {
            return;
        }
        if (!mStore.put(SignInFeature.KEY, true)) {
            Toast.makeText(requireContext(), R.string.action_write_failed,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        Intent launch = requireContext().getPackageManager().getLaunchIntentForPackage(TARGET);
        if (launch == null) {
            Toast.makeText(requireContext(), R.string.action_launch_failed,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final Context appContext = requireContext().getApplicationContext();
        requireContext().startActivity(launch);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                appContext.sendBroadcast(new Intent(SignInFeature.ACTION_SIGN).setPackage(TARGET));
            } catch (Throwable t) {
                Log.w(TAG, "sign broadcast failed", t);
            }
        }, SIGN_BROADCAST_DELAY_MS);
    }

    /** 重新读取框架状态与开关值。service 绑定后由 MainActivity 调一次。 */
    void refresh() {
        if (getContext() == null) {
            return;
        }
        XposedService service = ServiceBridge.get();
        // 同一 service 实例内 remote prefs 不会变，复用 mStore 可省掉每次 refresh 的 binder。
        if (mStore == null || service != mStoreService) {
            mStore = new ConfigStore(requireContext());
            mStoreService = service;
        }
        boolean writable = mStore.remote();

        for (Feature f : FeatureRegistry.all()) {
            Preference p = findPreference(f.key);
            if (p instanceof MaterialSwitchPreference) {
                MaterialSwitchPreference sw = (MaterialSwitchPreference) p;
                sw.setCheckedValue(mStore.get(f.key, f.defaultEnabled));
                // 框架没绑定时写不进去，置灰避免“改了没用”；blocked 始终置灰。
                sw.setEnabled(writable && !f.blocked());
            }
        }

        Preference sign = findPreference("action_sign_in");
        if (sign != null) {
            sign.setEnabled(writable);
        }

        updateBanner();
        loadStatusAsync();
    }

    /**
     * 状态卡的 service 查询全在后台单线程跑，结果回主线程写 UI。
     * Fragment 已 detach 或 Activity 已更换时整份结果丢弃。
     */
    private void loadStatusAsync() {
        final boolean writable = mStore.remote();
        final String targetVersion = shownTargetVersion();
        final String moduleVersion = versionName();
        final Activity host = requireActivity();
        final int generation = ++mStatusGeneration;

        if (mStatusFuture != null) {
            mStatusFuture.cancel(false);
        }
        mStatusFuture = STATUS_EXECUTOR.submit(() -> {
            ServiceBridge.Status status = ServiceBridge.status(TARGET);
            if (!isAdded() || getActivity() != host) {
                return;
            }
            host.runOnUiThread(() -> {
                if (generation != mStatusGeneration || !isAdded() || getActivity() != host) {
                    return;
                }
                Preference p = findPreference("status");
                if (p == null) {
                    return;
                }
                String targetApp = getString(R.string.app_target_name);
                if (!status.isConnected()) {
                    p.setTitle(R.string.status_connecting);
                    p.setSummary(getString(R.string.status_detail_connecting, targetVersion));
                } else if (!status.isScoped()) {
                    p.setTitle(R.string.status_inactive);
                    p.setSummary(getString(R.string.status_detail_no_scope,
                            status.getApiVersion(), targetApp, targetVersion, moduleVersion));
                } else if (status.isInjected()) {
                    p.setTitle(R.string.status_active);
                    p.setSummary(getString(R.string.status_detail_active,
                            status.getApiVersion(), targetApp, targetVersion, moduleVersion));
                } else {
                    p.setTitle(R.string.status_connected);
                    p.setSummary(getString(R.string.status_detail_not_running,
                            status.getApiVersion(), targetApp, targetVersion, moduleVersion));
                }
                appendRemoteState(p, writable);
            });
        });
    }

    /**
     * 异常横幅：版本不匹配，或“已启用且明确失败”的功能数 ≥ 2。
     * 不新增第五种状态卡状态。
     */
    private void updateBanner() {
        Preference banner = findPreference("warning");
        if (banner == null || getContext() == null) {
            return;
        }
        String target = targetVersion();
        if (target != null && !TARGET_VERSION.equals(target)) {
            banner.setSummary(getString(R.string.status_banner_version, target,
                    TARGET_VERSION));
            banner.setVisible(true);
            return;
        }
        int failed = StatusProvider.failedFeatureCount(requireContext());
        if (failed >= FAILED_BANNER_THRESHOLD) {
            banner.setSummary(getString(R.string.status_banner_failed, failed));
            banner.setVisible(true);
        } else {
            // 目标应用未安装或失败数不足：不显示横幅，避免误导。
            banner.setVisible(false);
        }
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

    private String targetVersion() {
        try {
            PackageInfo pi = requireContext().getPackageManager().getPackageInfo(TARGET, 0);
            return pi.versionName;
        } catch (Throwable t) {
            return null;
        }
    }

    private String shownTargetVersion() {
        String v = targetVersion();
        return v != null ? v : getString(R.string.status_unknown_version);
    }

    private String versionName() {
        if (mVersionName != null) {
            return mVersionName;
        }
        try {
            String v = requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0).versionName;
            mVersionName = v != null ? v : "?";
        } catch (Throwable t) {
            mVersionName = "?";
        }
        return mVersionName;
    }
}
