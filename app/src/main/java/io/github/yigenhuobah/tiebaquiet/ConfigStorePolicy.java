package io.github.yigenhuobah.tiebaquiet;

/**
 * {@link ConfigStore} 的纯决策逻辑，不依赖 Android，便于 JVM 单元测试。
 *
 * <p>把“是否接受写入”“旧 group 是否兜底”“commit 结果如何判定”“store 是否复用”
 * 从 Android 胶水里抽出来，避免这些边界只能靠真机验证。
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
        return newGroupAvailable || legacyGroupAvailable;
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
        if (newGroupCommitted) {
            return true;
        }
        return legacyGroupCommitted && !newGroupHadValue;
    }

    /**
     * ConfigStore 复用：同一 service 实例内 remote prefs 不会变，可以复用；
     * service 绑定/断开/重连或尚无 store 时必须重建。
     */
    public static boolean reuseStore(boolean hasStore, Object cachedService, Object currentService) {
        return hasStore && cachedService == currentService;
    }
}
