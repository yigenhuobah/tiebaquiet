package io.github.yigenhuobah.tiebaquiet;

import io.github.yigenhuobah.core.ConfigPolicy;

/**
 * {@link ConfigStore} 的纯决策逻辑适配器。
 *
 * <p>判定规则（remote 可用性、新/旧 group 的 commit 兜底、store 复用）全部由
 * core 的 {@link ConfigPolicy} 承担；本类只保留模块既有的静态方法名，避免
 * ConfigStore 调用点改动。Android 胶水层不参与判定，因此这些边界仍可由
 * 纯 JVM 单测锁死（见 ConfigStorePolicyTest）。
 */
public final class ConfigStorePolicy {

    private ConfigStorePolicy() {
    }

    /**
     * 写入口的即时决策：新 group 或旧 group 至少一个 remote 可用才接受。
     *
     * <p>两个都不可用时必须返回 false，让 UI 回滚；不能像 {@code apply()} 那样
     * 静默成功，否则设置页显示已保存、贴吧进程却读不到。
     */
    public static boolean canWrite(boolean newGroupAvailable, boolean legacyGroupAvailable) {
        return ConfigPolicy.canWrite(newGroupAvailable, legacyGroupAvailable);
    }

    /**
     * 一次写入的完整判定：目标进程按“新 key 优先、旧 key 兜底”解析配置，因此
     * 新 group commit 成功即成功；新 group 未写成功时，只有它此前没有该 key
     * （旧 group 的新值不会被遮蔽）且旧 group commit 成功，目标进程才读得到新值。
     *
     * @param newGroupCommitted    新 group 本次 commit 是否成功
     * @param legacyGroupCommitted 旧 group 本次 commit 是否成功
     * @param newGroupHadValue     本次写入前新 group 是否已持有该 key；commit 失败
     *                             不会改动已落盘内容，故在 commit 前用
     *                             {@code newPrefs.contains(key)} 记录即可
     */
    public static boolean committed(boolean newGroupCommitted, boolean legacyGroupCommitted,
                                    boolean newGroupHadValue) {
        return ConfigPolicy.committed(newGroupCommitted, legacyGroupCommitted, newGroupHadValue);
    }

    /**
     * ConfigStore 复用：同一 service 实例内 remote prefs 不会变，可以复用；
     * service 绑定/断开/重连或尚无 store 时必须重建。
     */
    public static boolean reuseStore(boolean hasStore, Object cachedService, Object currentService) {
        return ConfigPolicy.reuseStore(hasStore, cachedService, currentService);
    }
}
