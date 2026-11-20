package com.kazumi.port.ui.navigation

/**
 * 大体积路由参数的进程内中转表。
 *
 * 背景（真实崩溃取证）：2026-11-19 00:17:22，App 切后台瞬间崩溃：
 * ```
 * FATAL EXCEPTION: main
 * java.lang.RuntimeException: android.os.TransactionTooLargeException: data parcel size 613760 bytes
 *   at android.app.ActivityThread$StopInfo.run → IActivityManager.activityStopped
 * ```
 * 原因：Navigation 的路由参数（`roadsJson` = 全部线路 × 全部剧集 URL）与详情页
 * `rememberSaveable` 里的整部番 JSON 都会被系统写进 Activity 的 saved state，
 * 切后台/被系统回收时统一走 Binder 事务，超过 1MB 缓冲（实际可用约 500~600KB）
 * 直接抛 TransactionTooLargeException，主线程崩溃。
 *
 * 对策：超过阈值的大字符串不放进路由/保存状态，改为存进本表（进程内内存），
 * 路由里只带一个短 token（`mem:<id>`）。页面侧用 [resolve] 还原。
 *
 * 生命周期：进程内有效；进程被杀即失效（此时页面本来也会重建，需重新拉取，
 * 属于可接受的退化——换取"绝不再因为参数过大而崩溃"）。
 */
object MemoryRouteArgs {

    /** 触发中转的阈值：超过 4KB 就不再进入路由串/保存状态 */
    const val THRESHOLD = 4 * 1024

    /** 最多保留多少条，防止长期使用无限增长（按插入顺序淘汰最旧的） */
    private const val MAX_ENTRIES = 16

    private val store = object : LinkedHashMap<String, String>(MAX_ENTRIES, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean =
            size > MAX_ENTRIES
    }

    private var nextId = 1L

    /**
     * 存入一条大字符串并返回可直接放进路由的短 token。
     * 未超过阈值时原样返回，避免无谓的内存占用与迁移成本。
     */
    @Synchronized
    fun put(value: String): String {
        if (value.length <= THRESHOLD) return value
        val id = "mem:${nextId++}"
        store[id] = value
        return id
    }

    /**
     * 还原真实内容：token 命中则返回大字符串，否则原样返回（普通短参数/空串）。
     */
    @Synchronized
    fun resolve(value: String): String {
        if (value.startsWith("mem:")) {
            return store[value] ?: ""
        }
        return value
    }

    /** 清理（退出前台/低内存时可调用，当前未强制使用） */
    @Synchronized
    fun clear() {
        store.clear()
    }
}
