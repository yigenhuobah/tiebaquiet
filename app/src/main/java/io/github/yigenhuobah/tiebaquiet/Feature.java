package io.github.yigenhuobah.tiebaquiet;

import android.content.SharedPreferences;
import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * 功能清单：统一编号、remote prefs 键、文案、默认值、分组与安装器。
 *
 * <p>{@code code} 是统一功能编号（AD/UI/PV/EN/EX-NN），只用于展示、日志与文档；
 * {@code key} 是 remote prefs 键，发布后不可修改。默认值遵循统一策略：
 * AD 广告净化、UI 界面精简默认开；PV 隐私保护、EN 功能增强、EX 实验功能默认关。
 *
 * <p>安装器只在目标进程执行；模块 App 只读取本类的元数据，不调用 hook 相关方法。
 */
public final class Feature {

    public static final String TAG = "TiebaQuiet";

    /** 分组顺序固定 AD → UI → PV → EN → EX；没有功能的分组不显示。 */
    public enum Group { AD, UI, PV, EN, EX }

    /** 一个功能对应一个安装器；返回 true 表示至少安装成功一个 hook。 */
    public interface Installer {
        boolean install(HookEnv env);
    }

    /** after 钩子的结果变换器；异常时由调用方回退原结果。 */
    public interface Transformer {
        Object apply(Object result);
    }

    /** 统一功能编号，格式 AD/UI/PV/EN/EX-NN。 */
    public final String code;
    /** remote prefs 键，发布后不可改。 */
    public final String key;
    public final int titleRes;
    public final int summaryRes;
    /** 编译期内置默认值：remote prefs 读不到时使用。 */
    public final boolean defaultEnabled;
    public final Group group;
    /** blocked 原因；null 表示功能可用。 */
    public final String blockedReason;

    private final Installer installer;

    public Feature(String code, String key, int titleRes, int summaryRes,
                   boolean defaultEnabled, Group group, String blockedReason,
                   Installer installer) {
        this.code = code;
        this.key = key;
        this.titleRes = titleRes;
        this.summaryRes = summaryRes;
        this.defaultEnabled = defaultEnabled;
        this.group = group;
        this.blockedReason = blockedReason;
        this.installer = installer;
    }

    public Feature(String code, String key, int titleRes, int summaryRes,
                   boolean defaultEnabled, Group group, Installer installer) {
        this(code, key, titleRes, summaryRes, defaultEnabled, group, null, installer);
    }

    public boolean blocked() {
        return blockedReason != null;
    }

    public boolean install(HookEnv env) {
        return installer.install(env);
    }

    /**
     * 读取开关：新 group {@code config} 优先，旧 group {@code features} 兜底，
     * 两者都缺失才使用编译期默认值。读失败不能拖垮整个 hook 安装。
     */
    public boolean isEnabled(HookEnv env) {
        if (blocked()) {
            return false;
        }
        try {
            Boolean newValue = null;
            SharedPreferences prefs = env.prefs;
            if (prefs != null && prefs.contains(key)) {
                newValue = prefs.getBoolean(key, defaultEnabled);
            }
            Boolean legacyValue = null;
            SharedPreferences legacyPrefs = env.legacyPrefs;
            if (legacyPrefs != null && legacyPrefs.contains(key)) {
                legacyValue = legacyPrefs.getBoolean(key, defaultEnabled);
            }
            return ConfigMigration.resolve(newValue, legacyValue, defaultEnabled);
        } catch (Throwable t) {
            env.module.log(Log.WARN, TAG, "read config failed: " + key, t);
            return defaultEnabled;
        }
    }

    /**
     * 安装一个 hook。method 为 null 时只记 skip；安装异常同样只记日志并返回 false。
     * 命中次数由包装层统一累加，日志前缀使用功能编号。
     */
    public boolean hook(HookEnv env, Method method, String label,
                        XposedInterface.Hooker hooker) {
        if (method == null) {
            HookState.markNotFound(this, "锚点缺失 " + label);
            env.module.log(Log.INFO, TAG, code + " skip: anchor missing " + label);
            return false;
        }
        try {
            env.module.hook(method)
                    .setId(code + "#" + label)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(wrapHit(env, label, hooker));
            HookState.installed(this, label);
            env.module.log(Log.INFO, TAG, code + " registered " + label);
            return true;
        } catch (Throwable t) {
            env.module.log(Log.ERROR, TAG, code + " hook failed " + label, t);
            HookState.markNotFound(this, "hook 失败 " + label);
            return false;
        }
    }

    /** 常量替换：不调用原方法。 */
    public boolean replace(HookEnv env, Method method, String label, final Object value) {
        return hook(env, method, label, new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) {
                return value;
            }
        });
    }

    /** 不调用原方法，返回 null。 */
    public boolean skip(HookEnv env, Method method, String label) {
        return replace(env, method, label, null);
    }

    /** 调用原方法后变换结果；变换异常时回退原结果，proceed 只发生一次。 */
    public boolean after(HookEnv env, Method method, String label,
                         final Transformer transform) {
        return hook(env, method, label, new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object result = chain.proceed();
                try {
                    return transform.apply(result);
                } catch (Throwable t) {
                    env.module.log(Log.WARN, TAG, code + " transform failed " + label, t);
                    return result;
                }
            }
        });
    }

    /** 供没有 hook 可装的安装器（例如签到命令通道）记录锚点缺失。 */
    public void markSkip(String reason) {
        HookState.markNotFound(this, reason);
    }

    private XposedInterface.Hooker wrapHit(final HookEnv env, final String label,
                                           final XposedInterface.Hooker delegate) {
        return new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                long count = HookState.hit(Feature.this);
                // 前 5 次与每第 100 次写日志，避免高频 hook 刷爆 logcat。
                if (count <= 5 || count % 100 == 0) {
                    env.module.log(Log.INFO, TAG, code + " hit " + label + " #" + count);
                }
                return delegate.intercept(chain);
            }
        };
    }
}
