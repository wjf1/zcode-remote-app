package com.zcode.remote.relay

import com.zcode.remote.ui.components.ToolDiff
import com.zcode.remote.ui.components.ToolDiffParser

/**
 * 单轮 Turn 变更聚合（Sprint 3 第三步 · Turn Diff Summary）：
 *
 * 手机上审查 Agent 工作时，单独看每个工具行较为零碎；将一轮 Turn 内的所有
 * Edit / Write 聚合为一个变更清单（修改文件数、总增删行、各文件完整 Diff），
 * 呈现对齐 GitHub PR "Files changed" 的一站式 Review 体验。
 * 纯客户端解析，零新协议、零额外 RPC。
 */
data class TurnDiffSummary(
    val turnKey: String,
    val diffs: List<ToolDiff>,
    /** 触发此轮总结的最后一个写工具 rowId（用于在会话列表中定位并挂载卡片）。 */
    val lastRowId: Int,
) {
    val fileCount: Int get() = diffs.mapNotNull { it.filePath }.distinct().size.coerceAtLeast(1)
    val totalAdded: Int get() = diffs.sumOf { it.added }
    val totalRemoved: Int get() = diffs.sumOf { it.removed }
}

object TurnChanges {
    /**
     * 从会话行中抽取各 Turn 的文件写变更聚合。
     * 返回：以该 Turn 最后一个写操作的 rowId 为键的 Map，便于在 ConversationScreen 中精准挂载渲染。
     */
    fun aggregate(rows: List<ConversationRow>): Map<Int, TurnDiffSummary> {
        val result = mutableMapOf<Int, TurnDiffSummary>()
        if (rows.isEmpty()) return result

        var currentTurnKey = "turn_0"
        var turnSeq = 0
        val turnGroups = LinkedHashMap<String, MutableList<ConversationRow>>()

        for (row in rows) {
            if (row.kind == "userInput") {
                turnSeq++
                currentTurnKey = row.turnId ?: "turn_$turnSeq"
            } else if (!row.turnId.isNullOrBlank()) {
                currentTurnKey = row.turnId
            }
            turnGroups.getOrPut(currentTurnKey) { mutableListOf() }.add(row)
        }

        for ((turnKey, group) in turnGroups) {
            val diffs = mutableListOf<ToolDiff>()
            var lastWriteRowId: Int? = null

            for (row in group) {
                val diff = ToolDiffParser.parse(row)
                if (diff != null && diff.lines.isNotEmpty()) {
                    diffs += diff
                    lastWriteRowId = row.rowId
                }
            }

            if (diffs.isNotEmpty() && lastWriteRowId != null) {
                result[lastWriteRowId] = TurnDiffSummary(
                    turnKey = turnKey,
                    diffs = diffs,
                    lastRowId = lastWriteRowId,
                )
            }
        }

        return result
    }
}
