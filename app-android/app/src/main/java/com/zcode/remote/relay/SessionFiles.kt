package com.zcode.remote.relay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 会话文件索引（Sprint 3 第二步·剧本 B「最近文件」，纯客户端零协议）：
 * 从会话流的工具调用行抽取涉及文件的路径 —— 你在手机上想看的，基本就是
 * "Agent 刚才改了什么"，这比通用目录浏览器更贴场景，且不依赖目录列举 RPC。
 */
object SessionFiles {
    /** 带文件路径参数的工具（小写包含匹配）。Bash 的命令文本不做路径抽取（噪音大）。 */
    private val PATH_TOOLS = listOf("edit", "write", "read", "notebook")

    private val json = Json

    data class Entry(val path: String, val toolName: String, val rowId: Int)

    /** 从会话行抽取文件清单：按路径去重、最近操作者排前。 */
    fun extract(rows: List<ConversationRow>): List<Entry> {
        val byPath = LinkedHashMap<String, Entry>()
        for (row in rows) {
            val name = row.toolName?.lowercase()?.substringAfterLast('.') ?: continue
            if (PATH_TOOLS.none { name.contains(it) }) continue
            val path = extractPath(row.inputText) ?: continue
            byPath.remove(path)                       // remove+put 使该路径移到末尾（最新）
            byPath[path] = Entry(path, row.toolName ?: "?", row.rowId)
        }
        return byPath.values.reversed()
    }

    /** 从工具调用的 inputText JSON 抽文件路径字段。 */
    fun extractPath(inputText: String?): String? {
        val raw = inputText?.trim()?.takeIf { it.startsWith("{") } ?: return null
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        return listOf("file_path", "filePath", "path", "notebook_path")
            .firstNotNullOfOrNull { k ->
                obj[k]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            }
            ?.takeIf { it.isNotBlank() && it.length <= 500 }
    }
}
