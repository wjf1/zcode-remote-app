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

    /**
     * C-5④：按行缓存的路径抽取（增量解析）。
     *
     * 会话页对 rows 的派生每次重算都全量遍历——流式期 rows 每个 token 变化一次，
     * 没有缓存就是 O(n) 行反复 JSON 解析。缓存以 rowId 为键、以「inputText 的
     * hash + 长度」为内容指纹：流式更新会替换为新的行对象（同 rowId 新内容），
     * 指纹不同即重新解析；未变化的行直接复用，解析量降为 O(增量)。
     *
     * 生命周期由调用方（会话页 `remember`）持有，随会话页销毁——切会话即重置。
     */
    class PathCache {
        private class Cached(val textHash: Int, val textLen: Int, val path: String?)

        private val byRowId = HashMap<Int, Cached>()

        /** 实际发生的解析次数（单测断言「增量」用，也可作诊断指标）。 */
        var parseCount = 0
            private set

        fun pathFor(row: ConversationRow): String? {
            val text = row.inputText
            val hash = text?.hashCode() ?: 0
            val len = text?.length ?: -1
            byRowId[row.rowId]?.let { hit ->
                if (hit.textHash == hash && hit.textLen == len) return hit.path
            }
            val path = extractPath(text)
            byRowId[row.rowId] = Cached(hash, len, path)
            parseCount++
            return path
        }
    }

    /** 从会话行抽取文件清单：按路径去重、最近操作者排前。 */
    fun extract(rows: List<ConversationRow>, cache: PathCache? = null): List<Entry> {
        val byPath = LinkedHashMap<String, Entry>()
        for (row in rows) {
            val name = row.toolName?.lowercase()?.substringAfterLast('.') ?: continue
            if (PATH_TOOLS.none { name.contains(it) }) continue
            val path = if (cache != null) cache.pathFor(row) else extractPath(row.inputText)
            if (path == null) continue
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
