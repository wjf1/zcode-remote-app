package com.zcode.remote.relay

/**
 * V4 已提交命令的客户端账本（G4，2026-11-14 语义对齐官方
 * `packages/ui/src/v4/pendingCommandRegistry.ts`）：
 *
 * - **只保存客户端恢复线索**：不参与会话投影、**绝不能据此自动重放**（官方头部注释原文）；
 *   重放安全性由桌面 CLI 的 CommandInbox 保证（CLI restart 后未入 transcript 的命令明确
 *   返回 `fault.command.inputDiscardedOnRestart`，绝不自动重放）；
 * - TTL 24h（官方 `PENDING_COMMAND_TTL_MS`）；按 issuedAt 升序出批（最旧优先）；
 * - 查询对账走 `queryConversationCommandsV4`（官方 commands/query：每批 ≤64、
 *   `result: CommandAck | "unknown"`；`fault.command.queryUnavailable` 表示「无法确认
 *   是否执行」而非「执行失败」，调用方不得据此做破坏性清理）；
 * - 敏感交互（resolveInteraction）不进账本：其 ack 判据由 [PendingRequests] 承接，
 *   官方同样把 sensitive 命令排除在重放/对账之外。
 */
internal class PendingCommandLedger(
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = TTL_MS,
) {
    /** 一条在途/未结算命令。digest 仅作展示线索（官方对敏感命令只存 digest 的同款思路）。 */
    data class Entry(
        val commandId: String,
        val sessionId: String?,
        val issuedAt: Long,
        val expiresAt: Long,
        val digest: String?,
    )

    private val entries = LinkedHashMap<String, Entry>()

    /** 记录（幂等：同 commandId 重复记录保留首条）。返回是否新记录。 */
    fun record(
        commandId: String,
        sessionId: String?,
        digest: String? = null,
    ): Boolean {
        if (commandId.isBlank() || entries.containsKey(commandId)) return false
        val now = nowMs()
        entries[commandId] = Entry(commandId, sessionId, now, now + ttlMs, digest)
        pruneLocked()
        return true
    }

    /** 结算（ack 到达/查询确认）。返回该命令是否存在过。 */
    fun settle(commandId: String): Boolean = entries.remove(commandId) != null

    /** 清除过期条目（超出 [ttlMs]）。返回清除数。 */
    fun prune(): Int {
        pruneLocked()
        return prunedCount
    }

    private var prunedCount = 0

    private fun pruneLocked() {
        val now = nowMs()
        val expired = entries.values.filter { it.expiresAt <= now }.map { it.commandId }
        expired.forEach { entries.remove(it) }
        prunedCount = expired.size
    }

    /** 未结算条目（按 issuedAt 升序、最旧优先；[max] 上限对齐官方 commands/query 每批 ≤64）。 */
    fun unsettledBatch(max: Int = MAX_BATCH): List<Entry> {
        pruneLocked()
        return entries.values.sortedBy { it.issuedAt }.take(max.coerceAtLeast(1))
    }

    /** 当前未结算数（诊断用）。 */
    fun size(): Int = entries.size

    companion object {
        /** 记录保留时长：24h（官方 `PENDING_COMMAND_TTL_MS = 24 * 60 * 60 * 1_000`）。 */
        const val TTL_MS: Long = 24L * 60 * 60 * 1_000

        /** 单次对账查询的命令数上限（官方 `commandsQueryParamsSchema` `.max(64)`）。 */
        const val MAX_BATCH = 64
    }
}
