package io.github.yigenhuobah.tiebaquiet;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.widget.Toast;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 诊断页：逐功能显示 registered / hit / skip。
 *
 * <p>主页面零诊断，所有细节集中在这里。数据由目标进程的 HookState 经
 * hooks/Report 回传到 StatusProvider，再落到 {@code tq_report}。
 * 进入页面和 onResume 时刷新，不自动轮询。
 *
 * <p>SharedPreferences 读取与 PackageManager 查询都在后台单线程完成，
 * 主线程只负责把准备好的数据渲染成 Preference。
 */
public class DiagnosticsFragment extends PreferenceFragmentCompat {

    private static final ExecutorService BG_EXECUTOR =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "tiebaquiet-diagnostics");
                t.setDaemon(true);
                return t;
            });

    private long mLastRefreshMs;
    /** 模块版本在进程生命周期内不变，查询一次后缓存。 */
    private String mVersionName;

    /** 最近一次后台快照，复制诊断信息时复用，避免主线程再读 prefs。 */
    private Snapshot mSnapshot;
    private Future<?> mLoadFuture;
    private int mLoadGeneration;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(requireContext());
        setPreferenceScreen(screen);
    }

    @Override
    public void onResume() {
        super.onResume();
        rebuild();
    }

    @Override
    public void onDestroyView() {
        if (mLoadFuture != null) {
            mLoadFuture.cancel(true);
            mLoadFuture = null;
        }
        mLoadGeneration++;
        super.onDestroyView();
    }

    /** 后台读快照，回主线程重建列表；旧任务的结果按代次丢弃。 */
    private void rebuild() {
        final PreferenceScreen screen = getPreferenceScreen();
        if (screen == null || getContext() == null) {
            return;
        }
        final Context appContext = requireContext().getApplicationContext();
        final Activity host = requireActivity();
        final int generation = ++mLoadGeneration;
        // 模块版本进程内不变；把已缓存值带进后台，避免每次 onResume 重复 IPC。
        final String cachedVersion = mVersionName;

        if (mLoadFuture != null) {
            mLoadFuture.cancel(false);
        }
        mLoadFuture = BG_EXECUTOR.submit(() -> {
            Snapshot snapshot = loadSnapshot(appContext, cachedVersion);
            host.runOnUiThread(() -> {
                if (generation != mLoadGeneration || !isAdded() || getActivity() != host) {
                    return;
                }
                mSnapshot = snapshot;
                mLastRefreshMs = snapshot.refreshedAt;
                if (snapshot.versionName != null) {
                    mVersionName = snapshot.versionName;
                }
                render(screen, snapshot);
            });
        });
    }

    private void render(PreferenceScreen screen, Snapshot snapshot) {
        screen.removeAll();

        Preference header = new Preference(requireContext());
        header.setTitle(R.string.diag_title);
        header.setSummary(getString(R.string.diag_header, refreshTime())
                + "\n" + getString(R.string.diag_hint));
        header.setSelectable(false);
        screen.addPreference(header);

        for (Feature.Group group : FeatureRegistry.groups()) {
            PreferenceCategory category = new PreferenceCategory(requireContext());
            category.setTitle(groupTitle(group));
            // 先把 category 挂到 screen，它才会拿到 PreferenceManager；
            // 否则 category.addPreference() 在 getNextId() 上 NPE。
            screen.addPreference(category);
            List<Feature> features = FeatureRegistry.byGroup(group);
            for (Feature f : features) {
                category.addPreference(row(f, snapshot.rows.get(f.key)));
            }
        }

        if (!snapshot.hasData) {
            Preference empty = new Preference(requireContext());
            empty.setTitle(R.string.diag_empty_title);
            empty.setSummary(getString(R.string.diag_empty, getString(R.string.app_target_name)));
            empty.setSelectable(false);
            screen.addPreference(empty);
        }

        Preference refresh = new Preference(requireContext());
        refresh.setTitle(R.string.diag_refresh);
        refresh.setSummary(getString(R.string.diag_refresh_summary, refreshTime()));
        refresh.setOnPreferenceClickListener(p -> {
            rebuild();
            return true;
        });
        screen.addPreference(refresh);

        Preference copy = new Preference(requireContext());
        copy.setTitle(R.string.diag_copy);
        copy.setSummary(R.string.diag_copy_summary);
        copy.setOnPreferenceClickListener(p -> {
            copyDiagnostics();
            return true;
        });
        screen.addPreference(copy);
    }

    private Preference row(Feature f, RowData data) {
        Preference p = new Preference(requireContext());
        p.setKey("diag_" + f.key);
        p.setTitle(f.code + "  " + getString(f.titleRes));
        p.setSummary(summary(f, data));
        p.setSelectable(false);
        return p;
    }

    /** 单功能状态：registered · hit N · skip 原因；无数据用“—”。 */
    private String summary(Feature f, RowData data) {
        if (f.blocked()) {
            return getString(R.string.diag_blocked, f.blockedReason);
        }
        if (data == null || !data.reported) {
            return getString(R.string.diag_no_data);
        }

        boolean hasReg = data.reg != null && !data.reg.isEmpty();
        boolean hasSkip = data.skip != null && !data.skip.isEmpty();
        String dash = getString(R.string.diag_no_data);
        StringBuilder sb = new StringBuilder();
        sb.append(hasReg ? "registered" : dash);
        sb.append(" · hit ").append(data.hit);
        sb.append(" · skip ").append(hasSkip ? data.skip : dash);
        return sb.toString();
    }

    private void copyDiagnostics() {
        Snapshot snapshot = mSnapshot;
        if (snapshot == null) {
            // 首次加载还没回来：触发一次刷新，本次不复制半成品。
            rebuild();
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.app_name)).append(' ').append(versionName()).append('\n');
        sb.append(getString(R.string.diag_header, refreshTime())).append('\n');
        for (Feature f : FeatureRegistry.all()) {
            sb.append(f.code).append(' ').append(getString(f.titleRes))
                    .append(": ").append(summary(f, snapshot.rows.get(f.key))).append('\n');
        }
        try {
            ClipboardManager cm = (ClipboardManager) requireContext()
                    .getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("diagnostics", sb.toString()));
                Toast.makeText(requireContext(), R.string.diag_copied, Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable ignored) {
            // 剪贴板不可用不阻塞诊断页。
        }
    }

    private String refreshTime() {
        return DateFormat.getTimeFormat(requireContext()).format(new Date(mLastRefreshMs));
    }

    private String versionName() {
        return mVersionName != null ? mVersionName : "?";
    }

    private int groupTitle(Feature.Group group) {
        switch (group) {
            case AD:
                return R.string.group_ad;
            case UI:
                return R.string.group_ui;
            case PV:
                return R.string.group_pv;
            case EN:
                return R.string.group_en;
            case EX:
            default:
                return R.string.group_ex;
        }
    }

    /** 在后台线程一次性读完所有功能的回传与模块版本。 */
    private static Snapshot loadSnapshot(Context context, String cachedVersion) {
        long refreshedAt = System.currentTimeMillis();
        Map<String, RowData> rows = new HashMap<>();
        boolean hasData = false;
        for (Feature f : FeatureRegistry.all()) {
            if (f.blocked()) {
                rows.put(f.key, new RowData(null, null, 0L, true));
                hasData = true;
                continue;
            }
            String reg = StatusProvider.reportString(context, StatusProvider.PREFIX_REG, f.key);
            long hit = StatusProvider.reportHit(context, f.key);
            String skip = StatusProvider.reportString(context, StatusProvider.PREFIX_SKIP, f.key);
            boolean reported = (reg != null && !reg.isEmpty())
                    || (skip != null && !skip.isEmpty())
                    || hit > 0;
            rows.put(f.key, new RowData(reg, skip, hit, reported));
            if (reported) {
                hasData = true;
            }
        }
        String version = cachedVersion != null
                ? cachedVersion : queryVersionName(context, context.getPackageName());
        return new Snapshot(refreshedAt, version, rows, hasData);
    }

    private static String queryVersionName(Context context, String packageName) {
        try {
            PackageInfo pi = context.getPackageManager().getPackageInfo(packageName, 0);
            return pi.versionName;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 单功能的后台读取结果。 */
    private static final class RowData {
        final String reg;
        final String skip;
        final long hit;
        final boolean reported;

        RowData(String reg, String skip, long hit, boolean reported) {
            this.reg = reg;
            this.skip = skip;
            this.hit = hit;
            this.reported = reported;
        }
    }

    /** 一次刷新的完整快照，主线程渲染与复制都只读它。 */
    private static final class Snapshot {
        final long refreshedAt;
        final String versionName;
        final Map<String, RowData> rows;
        final boolean hasData;

        Snapshot(long refreshedAt, String versionName, Map<String, RowData> rows,
                 boolean hasData) {
            this.refreshedAt = refreshedAt;
            this.versionName = versionName;
            this.rows = rows;
            this.hasData = hasData;
        }
    }
}
