package com.zcode.remote.relay

/**
 * 会话未读存储（G6a，2026-11-14 对齐官方「未读清除 CAS」语义，
 * `docs/web-remote-control/mobile-task-home-design.md`）：
 *
 * - 值从布尔升级为 **unreadAt 时间戳**（官方快照字段 `unreadAt` 的语义）；
 * - 清除走 **compare-and-clear**（官方 `expectedUnreadAt` 原子清除，
 *   落点参考 `webRemoteControlTaskRead.ts`）：清除请求必须携带「发起清除时看到的
 *   unreadAt」，不匹配（期间有新未读写入）则**拒绝清除**——迟到的旧点击不得覆盖新未读。
 *
 * 纯函数、无 IO，JVM 单测直驱；AppViewModel 当前单线程使用（标记/清除同在主线程），
 * CAS 在单线程下等价于直接清除——本层把官方语义就位，未来接入服务端未读快照
 * （多端并发清除）时无需改调用方形状。
 */
object UnreadStore {
    /** 标记未读（已在 map 中的任务**保留更早时间戳**——未读起点语义，重复标记不刷新）。 */
    fun mark(
        map: Map<String, Long>,
        taskId: String,
        atMs: Long,
    ): Map<String, Long> {
        if (taskId.isBlank()) return map
        if (map.containsKey(taskId)) return map
        return map + (taskId to atMs)
    }

    /**
     * CAS 清除：仅当 [taskId] 当前值等于 [expectedUnreadAt] 时移除；不匹配（含已不存在）
     * 原样返回。[expectedUnreadAt] ≤ 0 视为「无条件清除」（调用方无旧值可带的兼容路径）。
     */
    fun compareAndClear(
        map: Map<String, Long>,
        taskId: String,
        expectedUnreadAt: Long,
    ): Map<String, Long> {
        val current = map[taskId] ?: return map
        if (expectedUnreadAt > 0 && current != expectedUnreadAt) return map
        return map - taskId
    }
}
