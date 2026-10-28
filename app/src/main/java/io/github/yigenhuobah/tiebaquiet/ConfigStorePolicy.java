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
     * 一次写入的最终结果：新 group 或旧 group 任一 commit 成功，目标进程都能读到
     * （目标侧同样按“新 key 优先、旧 key 兜底”解析）。
     */
    public static boolean committed(boolean newGroupCommitted, boolean legacyGroupCommitted) {
        return newGroupCommitted || legacyGroupCommitted;
    }

    /**
     * ConfigStore 复用：同一 service 实例内 remote prefs 不会变，可以复用；
     * service 绑定/断开/重连或尚无 store 时必须重建。
     */
    public static boolean reuseStore(boolean hasStore, Object cachedService, Object currentService) {
        return hasStore && cachedService == currentService;
    }
}
