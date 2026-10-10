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
     * C-5④：按行缓存的 diff 解析（增量解析）。
     *
     * `ToolDiffParser.parse` 是逐行 JSON 解析 + Myers diff（O(m×n)），而会话页的
     * Turn 聚合在流式期每个 token 重算一次——没有缓存就是全量重解析。缓存以
     * rowId 为键、以「toolName + inputText 的 hash/长度」为内容指纹：流式更新
     * 替换为新的行对象（同 rowId 新内容）时指纹变化即重新解析，否则复用。
     *
     * 生命周期由调用方（会话页 `remember`）持有，随会话页销毁——切会话即重置。
     */
    class DiffCache {
        private class Cached(
            val nameHash: Int, val nameLen: Int,
            val textHash: Int, val textLen: Int,
            val diff: ToolDiff?,
        )

        private val byRowId = HashMap<Int, Cached>()

        /** 实际发生的解析次数（单测断言「增量」用，也可作诊断指标）。 */
        var parseCount = 0
            private set

        fun diffFor(row: ConversationRow): ToolDiff? {
            val name = row.toolName
            val text = row.inputText
            val nameHash = name?.hashCode() ?: 0
            val nameLen = name?.length ?: -1
            val textHash = text?.hashCode() ?: 0
            val textLen = text?.length ?: -1
            byRowId[row.rowId]?.let { hit ->
                if (hit.nameHash == nameHash && hit.nameLen == nameLen &&
                    hit.textHash == textHash && hit.textLen == textLen
                ) {
                    return hit.diff
                }
            }
            val diff = ToolDiffParser.parse(row)
            byRowId[row.rowId] = Cached(nameHash, nameLen, textHash, textLen, diff)
            parseCount++
            return diff
        }
    }

    /**
     * 从会话行中抽取各 Turn 的文件写变更聚合。
     * 返回：以该 Turn 最后一个写操作的 rowId 为键的 Map，便于在 ConversationScreen 中精准挂载渲染。
     */
    fun aggregate(rows: List<ConversationRow>, cache: DiffCache? = null): Map<Int, TurnDiffSummary> {
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
                val diff = if (cache != null) cache.diffFor(row) else ToolDiffParser.parse(row)
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
