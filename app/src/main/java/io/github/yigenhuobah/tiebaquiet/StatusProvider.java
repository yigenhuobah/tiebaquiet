package io.github.yigenhuobah.tiebaquiet;

import android.annotation.SuppressLint;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.util.Log;

/**
 * 目标进程（百度贴吧）→ 模块 App 的诊断状态通道。
 *
 * <p>方向不能反过来：remote prefs 在目标进程只读，所以“哪些钩子注册了 / 命中了多少次”
 * 只能由目标进程主动 call 进来。
 *
 * <p>安全边界：provider 必须 exported 才能被目标进程跨应用调用，因此每次 call 都校验
 * 调用方 UID 必须属于 {@code com.baidu.tieba}；key 只接受 {@link ReportProtocol} 白名单，
 * value 有长度上限。不存任何用户内容、账号、设备标识或业务数据。
 */
public final class StatusProvider extends ContentProvider {

    private static final String TAG = "TiebaQuiet";

    /** 与 manifest 的 ${applicationId}.status 一致，发布后不可改。 */
    public static final String AUTHORITY = "io.github.yigenhuobah.tiebaquiet.status";
    public static final String PREFS = "tq_report";

    public static final String PREFIX_REG = ReportProtocol.PREFIX_REG;
    public static final String PREFIX_HIT = ReportProtocol.PREFIX_HIT;
    public static final String PREFIX_SKIP = ReportProtocol.PREFIX_SKIP;

    /** 落盘时统一加 "v:" 前缀，避免与旧版本可能存在的同名键冲突。 */
    private static final String VALUE_PREFIX = "v:";

    private static final String TARGET_PACKAGE = ConfigStore.TARGET_PACKAGE;
    private static final int MAX_ITEMS = 800;

    @Override
    public boolean onCreate() {
        return true;
    }

    /**
     * synchronized：目标进程可能并发回传，hit 的 getLong→compare→putLong 必须串行，
     * 否则较大计数会被较小值覆盖。
     *
     * <p>写入用 {@code commit()} 同步落盘并检查返回值：call() 运行在 provider 的 binder
     * 线程，不阻塞主线程；只有提交成功才返回非 null（ok）。提交失败记录 warning 并返回
     * null，与“provider 不可达”保持同一失败语义，由 Report 侧退避重试，避免回传假成功。
     */
    @SuppressLint("ApplySharedPref") // binder 线程同步落盘，必须拿到真实写入结果。
    @Override
    public synchronized Bundle call(String method, String arg, Bundle extras) {
        Context context = getContext();
        if (context == null) {
            return null;
        }
        if (!"report".equals(method) || extras == null) {
            return null;
        }
        if (!isAllowedCaller(context, Binder.getCallingUid())) {
            return null;
        }
        String[] keys = extras.getStringArray("keys");
        String[] values = extras.getStringArray("values");
        if (keys == null || values == null || keys.length != values.length) {
            return null;
        }
        if (keys.length > MAX_ITEMS) {
            return null;
        }
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sp.edit();
        int accepted = 0;
        for (int i = 0; i < keys.length; i++) {
            String key = keys[i];
            String value = values[i];
            if (!ReportProtocol.validKey(key) || !ReportProtocol.isValidValue(value)) {
                continue;
            }
            if (key.startsWith(PREFIX_HIT)) {
                // 多进程各自回传，取历史最大值，避免重复累加或被低计数覆盖。
                long previous = sp.getLong(VALUE_PREFIX + key, 0L);
                long next;
                try {
                    next = Long.parseLong(value);
                } catch (NumberFormatException e) {
                    continue;
                }
                long merged = ReportProtocol.mergeHit(previous, next);
                if (merged > previous) {
                    editor.putLong(VALUE_PREFIX + key, merged);
                }
            } else {
                editor.putString(VALUE_PREFIX + key, value);
            }
            accepted++;
        }
        if (accepted > 0) {
            boolean committed;
            try {
                committed = editor.commit();
            } catch (Throwable t) {
                committed = false;
            }
            if (!committed) {
                Log.w(TAG, "report commit failed, accepted=" + accepted);
                return null;
            }
        }
        return new Bundle();
    }

    /** 读取一个回传字符串（reg/skip）；没有数据或读取失败返回 null。 */
    public static String reportString(Context context, String prefix, String key) {
        try {
            SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return sp.getString(VALUE_PREFIX + prefix + key, null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 读取命中计数；没有数据或读取失败返回 0。 */
    public static long reportHit(Context context, String key) {
        try {
            SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return sp.getLong(VALUE_PREFIX + PREFIX_HIT + key, 0L);
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * 统计“已启用且明确失败”的功能数，供异常横幅使用。
     *
     * <p>失败判定同时满足：用户配置为开启；目标进程有回传；reg 为空或 skip 非空。
     * 没有回传的功能不计入，避免刚装上或目标应用未启动时误报。只在主进程回传，
     * 子进程未装 hook 的 skip 不会被误算。
     */
    public static int failedFeatureCount(Context context) {
        if (context == null) {
            return 0;
        }
        ConfigStore store;
        try {
            store = new ConfigStore(context);
        } catch (Throwable t) {
            store = null;
        }
        return failedFeatureCount(context, store);
    }

    /**
     * 复用调用方已经拿到的 ConfigStore，避免在后台刷新时重复创建 store、
     * 重复走一遍 remote prefs binder。调用方必须保证在后台线程执行。
     */
    public static int failedFeatureCount(Context context, ConfigStore store) {
        if (context == null) {
            return 0;
        }
        int failed = 0;
        for (Feature feature : FeatureRegistry.all()) {
            if (feature.blocked()) {
                continue;
            }
            boolean enabled = store != null
                    ? store.get(feature.key, feature.defaultEnabled)
                    : feature.defaultEnabled;
            if (!enabled) {
                continue;
            }
            String reg = reportString(context, PREFIX_REG, feature.key);
            String skip = reportString(context, PREFIX_SKIP, feature.key);
            long hit = reportHit(context, feature.key);
            boolean reported = (reg != null && !reg.isEmpty())
                    || (skip != null && !skip.isEmpty())
                    || hit > 0;
            if (!reported) {
                continue;
            }
            if (reg == null || reg.isEmpty() || (skip != null && !skip.isEmpty())) {
                failed++;
            }
        }
        return failed;
    }

    /** 只放行百度贴吧 UID；模块自身与其他任意应用一律拒绝，不写盘也不抛异常。 */
    private static boolean isAllowedCaller(Context context, int uid) {
        try {
            String[] packages = context.getPackageManager().getPackagesForUid(uid);
            if (packages == null) {
                return false;
            }
            for (String pkg : packages) {
                if (TARGET_PACKAGE.equals(pkg)) {
                    return true;
                }
            }
        } catch (Throwable t) {
            return false;
        }
        return false;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
