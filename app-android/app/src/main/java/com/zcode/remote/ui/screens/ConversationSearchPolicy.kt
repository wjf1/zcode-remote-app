package com.zcode.remote.ui.screens

import com.zcode.remote.relay.ConversationRow

/**
 * 会话内查找（纯函数，JVM 单测覆盖——判定逻辑与 Compose 解耦，理由同 [ConversationScrollPolicy]）。
 *
 * 检索覆盖「可读正文」的多个字段：`text`（助手/思考正文）、`inputText`（工具调用输入）、
 * `outputText`（工具调用输出）、`summaryText`（行摘要）、`errorText`（错误原因）。
 * 匹配规则：query trim 后非空才执行；**不区分大小写**（中英混排场景下用户预期）；
 * 命中结果按 rowId 升序（即会话时间序）返回。
 */
object ConversationSearchPolicy {
    /** 提取一行的可检索正文（null = 该行无可检索文本，如 turnHeader/timelineMarker）。 */
    fun searchableText(row: ConversationRow): String? =
        listOfNotNull(row.text, row.inputText, row.outputText, row.summaryText, row.errorText)
            .firstOrNull { it.isNotBlank() }

    /** query 是否有效（trim 非空）。 */
    fun isQueryValid(query: String): Boolean = query.trim().isNotEmpty()

    /**
     * 在 [rows] 中查找 [query]（不区分大小写），返回命中的 rowId 列表（时间序）。
     * query 无效返回空列表。
     */
    fun match(
        rows: List<ConversationRow>,
        query: String,
    ): List<Int> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val needle = q.lowercase()
        // rowId 升序（会话时间序）：输入 rows 的存储顺序不保证旧→新，「下一个/上一个」导航
        // 依赖稳定时间序（单测「命中结果按时间序返回」钉住）。
        return rows
            .filter { row ->
                searchableText(row)?.lowercase()?.contains(needle) == true
            }.map { it.rowId }
            .sorted()
    }

    /**
     * 命中行是否包含 [query]（用于 UI 校验某个 rowId 在 query 变化后是否仍命中）。
     * 与 [match] 同口径。
     */
    fun rowMatches(
        row: ConversationRow,
        query: String,
    ): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return false
        return searchableText(row)?.lowercase()?.contains(q.lowercase()) == true
    }
}
