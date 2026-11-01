package io.github.yigenhuobah.tiebaquiet;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.widget.Toast;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import java.util.ArrayList;
import java.util.Collections;
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
 *
 * <p>回传数据有 TTL：超过 {@link StatusProvider#REPORT_TTL_MS} 没有新回传时，
 * 旧会话的 registered/hit/skip 一律按无数据处理，与主页失败横幅的过期语义一致。
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

        addCrashSection(screen, snapshot.crashes);

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

        // 新文案无法进 strings.xml（不在本次修复的文件清单内），用字面量并在此说明。
        Preference export = new Preference(requireContext());
        export.setTitle("导出诊断包");
        export.setSummary("系统分享诊断文本：版本/系统/框架 API/目标版本/功能状态/最近命中/最近崩溃");
        export.setOnPreferenceClickListener(p -> {
            exportDiagnostics();
            return true;
        });
        screen.addPreference(export);
    }

    /** 最近崩溃区（crash0 最新）：无崩溃时不显示。 */
    private void addCrashSection(PreferenceScreen screen, List<String> crashes) {
        PreferenceCategory category = null;
        for (int i = 0; i < crashes.size() && i < 3; i++) {
            String crash = crashes.get(i);
            if (crash == null || crash.isEmpty()) {
                continue;
            }
            if (category == null) {
                category = new PreferenceCategory(requireContext());
                category.setTitle("最近崩溃");
                screen.addPreference(category);
            }
            category.addPreference(crashRow(crash));
        }
    }

    /** 崩溃行：标题为格式化时间，摘要为线程/异常/栈帧明细（首段解析失败则整行原文）。 */
    private Preference crashRow(String crash) {
        Preference p = new Preference(requireContext());
        String title = crash;
        String detail = crash;
        int sep = crash.indexOf('|');
        if (sep > 0) {
            try {
                long ms = Long.parseLong(crash.substring(0, sep));
                title = DateFormat.getTimeFormat(requireContext()).format(new Date(ms));
                detail = crash.substring(sep + 1);
            } catch (NumberFormatException ignored) {
            }
        }
        p.setTitle(title);
        p.setSummary(detail);
        p.setSelectable(false);
        return p;
    }

    /** 最近崩溃文本（crashN 原文逐行）；无崩溃返回空串。 */
    private static String crashText(List<String> crashes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < crashes.size() && i < 3; i++) {
            String crash = crashes.get(i);
            if (crash != null && !crash.isEmpty()) {
                sb.append("crash").append(i).append(' ').append(crash).append('\n');
            }
        }
        return sb.toString();
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
        String crashes = crashText(snapshot.crashes);
        if (!crashes.isEmpty()) {
            sb.append("最近崩溃:\n").append(crashes);
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

    /**
     * 导出诊断包：聚合模块版本/系统版本/框架 API/目标版本/各功能状态/最近命中/
     * 最近崩溃为一段文本，ACTION_SEND text/plain 系统分享——不写文件、不依赖
     * FileProvider。分享面板拉不起来只弹 Toast，不阻塞诊断页。
     */
    private void exportDiagnostics() {
        Snapshot snapshot = mSnapshot;
        if (snapshot == null) {
            // 首次加载还没回来：触发一次刷新，本次不导出半成品。
            rebuild();
            return;
        }
        try {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_TEXT, buildExportText(snapshot));
            startActivity(Intent.createChooser(send, "导出诊断包"));
        } catch (Throwable ignored) {
            Toast.makeText(requireContext(), "诊断包导出失败", Toast.LENGTH_SHORT).show();
        }
    }

    private String buildExportText(Snapshot snapshot) {
        StringBuilder header = new StringBuilder();
        header.append(getString(R.string.app_name)).append(' ')
                .append(versionName()).append('\n');
        header.append("系统: Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        header.append("框架 API: ").append(frameworkApi()).append('\n');
        header.append("目标版本: ").append(targetVersion()).append('\n');
        header.append(getString(R.string.diag_header, refreshTime()));

        List<String> featureLines = new ArrayList<>();
        for (Feature f : FeatureRegistry.all()) {
            featureLines.add(f.code + " " + getString(f.titleRes) + ": "
                    + summary(f, snapshot.rows.get(f.key)));
        }
        List<String> hitLines = new ArrayList<>();
        for (Feature f : FeatureRegistry.all()) {
            RowData data = snapshot.rows.get(f.key);
            long hit = data != null ? data.hit : 0L;
            if (hit > 0) {
                hitLines.add(f.code + " " + getString(f.titleRes) + ": hit " + hit);
            }
        }
        return joinExportText(header.toString(), featureLines, hitLines,
                crashText(snapshot.crashes), getString(R.string.diag_no_data));
    }

    /**
     * 导出文本的纯拼装（无 Android 依赖，静态包级可见供 JVM 单测锁定关键段，
     * 见 ReportProtocolTest）：headerBlock 为版本/系统/框架 API/目标版本/数据时间，
     * 功能状态/最近命中/最近崩溃三个段按固定顺序追加，空段用 noData 占位。
     */
    static String joinExportText(String headerBlock, List<String> featureLines,
            List<String> hitLines, String crashBlock, String noData) {
        StringBuilder sb = new StringBuilder();
        sb.append(headerBlock);
        sb.append("\n[功能状态]\n");
        for (String line : featureLines) {
            sb.append(line).append('\n');
        }
        sb.append("\n[最近命中]\n");
        if (hitLines.isEmpty()) {
            sb.append(noData).append('\n');
        } else {
            for (String line : hitLines) {
                sb.append(line).append('\n');
            }
        }
        sb.append("\n[最近崩溃]\n");
        sb.append(crashBlock.isEmpty() ? noData + '\n' : crashBlock);
        return sb.toString();
    }

    /** Xposed 框架 API 版本（模块 App 进程经 XposedService 绑定得知）；未绑定返回提示。 */
    private String frameworkApi() {
        try {
            io.github.libxposed.service.XposedService service = ServiceBridge.get();
            return service != null ? String.valueOf(service.getApiVersion()) : "未绑定";
        } catch (Throwable t) {
            return "?";
        }
    }

    /** 目标应用（百度贴吧）版本；未安装或查询失败返回提示。 */
    private String targetVersion() {
        try {
            PackageInfo info = requireContext().getPackageManager()
                    .getPackageInfo(ConfigStore.TARGET_PACKAGE, 0);
            String version = info.versionName != null ? info.versionName : "?";
            return version + " (" + info.versionCode + ")";
        } catch (Throwable t) {
            return "未安装";
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
        // 与主页 failedFeatureCount 的 TTL 语义一致：超过 TTL 没有新回传，
        // 旧会话的 reg/hit/skip 不能再当作当前状态展示。
        boolean fresh = StatusProvider.hasFreshReport(context);
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
            boolean reported = reportReported(reg, skip, hit, fresh);
            rows.put(f.key, new RowData(reg, skip, hit, reported));
            if (reported) {
                hasData = true;
            }
        }
        String version = cachedVersion != null
                ? cachedVersion : queryVersionName(context, context.getPackageName());
        // 最近崩溃（crash0 最新）：与功能行共用同一次后台读取；TTL 过期也照常展示，
        // 崩溃是历史证据，仍可导入诊断包排查。
        List<String> crashes = new ArrayList<>(3);
        for (int i = 0; i < 3; i++) {
            String crash = StatusProvider.reportCrash(context, i);
            crashes.add(crash != null ? crash : "");
        }
        return new Snapshot(refreshedAt, version, rows, hasData,
                Collections.unmodifiableList(crashes));
    }

    private static String queryVersionName(Context context, String packageName) {
        try {
            PackageInfo pi = context.getPackageManager().getPackageInfo(packageName, 0);
            return pi.versionName;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 纯逻辑：单功能回传是否算“有数据”。仅新鲜会话计入——回传超过 TTL
     * （{@link StatusProvider#REPORT_TTL_MS}）后一律按无数据处理，不再把旧会话的
     * registered/hit/skip 当作当前状态，与主页失败横幅的过期语义保持一致。
     */
    static boolean reportReported(String reg, String skip, long hit, boolean fresh) {
        if (!fresh) {
            return false;
        }
        return (reg != null && !reg.isEmpty())
                || (skip != null && !skip.isEmpty())
                || hit > 0;
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
        /** 最近崩溃（crash0 最新，最多 3 条，无崩溃为空串）；复制/导出与崩溃区共用。 */
        final List<String> crashes;

        Snapshot(long refreshedAt, String versionName, Map<String, RowData> rows,
                 boolean hasData, List<String> crashes) {
            this.refreshedAt = refreshedAt;
            this.versionName = versionName;
            this.rows = rows;
            this.hasData = hasData;
            this.crashes = crashes;
        }
    }
}
