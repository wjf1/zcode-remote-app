package com.zcode.remote.relay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * C-5④ 解析缓存单测：会话页派生（SessionFiles / TurnChanges）在流式期
 * 必须只解析「新增或变化」的行（O(增量)），未变化的行复用缓存结果。
 *
 * 断言口径用各自缓存的 `parseCount`（真实解析发生次数）——
 * 只看结果值无法区分「又全量解析了一遍但结果相同」，计数才钉得住「增量」。
 */
class ParseCacheTest {

    private fun tool(rowId: Int, name: String, input: String) =
        ConversationRow(rowId = rowId, kind = "toolCall", toolName = name, inputText = input)

    // ---------- SessionFiles.PathCache ----------

    @Test
    fun pathCache_parsesOnlyNewRowsOnAppend() {
        val cache = SessionFiles.PathCache()
        val old = tool(1, "Edit", """{"file_path":"a.kt","old_string":"x","new_string":"y"}""")
        SessionFiles.extract(listOf(old), cache)
        assertEquals(1, cache.parseCount)

        // 追加新行后重跑（流式期每个 token 都会发生）——旧行必须命中缓存
        val new = tool(2, "Write", """{"file_path":"b.kt","content":"c"}""")
        val files = SessionFiles.extract(listOf(old, new), cache)
        assertEquals("只应解析新增的那一行", 2, cache.parseCount)
        assertEquals(listOf("b.kt", "a.kt"), files.map { it.path })
    }

    @Test
    fun pathCache_reparsesWhenRowContentChanges() {
        val cache = SessionFiles.PathCache()
        val v1 = tool(1, "Edit", """{"file_path":"a.kt"}""")
        SessionFiles.extract(listOf(v1), cache)

        // 同 rowId 的新内容（RowStore 的流式更新是替换行对象）→ 指纹变化 → 重新解析
        val v2 = tool(1, "Edit", """{"file_path":"changed.kt"}""")
        val files = SessionFiles.extract(listOf(v2), cache)
        assertEquals("changed.kt", files.single().path)
        assertEquals(2, cache.parseCount)
    }

    @Test
    fun pathCache_equivalentToUncached() {
        val rows = listOf(
            tool(1, "Edit", """{"file_path":"a.kt"}"""),
            tool(2, "Bash", """{"command":"ls"}"""),
            tool(3, "Read", """{"file_path":"b.kt"}"""),
        )
        val cache = SessionFiles.PathCache()
        assertEquals(SessionFiles.extract(rows), SessionFiles.extract(rows, cache))
        // 非路径工具行（Bash）在缓存之前就被过滤，不产生解析
        assertEquals(2, cache.parseCount)
    }

    // ---------- TurnChanges.DiffCache ----------

    @Test
    fun diffCache_parsesOnlyNewRowsOnAppend() {
        val cache = TurnChanges.DiffCache()
        val first = tool(1, "Edit", """{"file_path":"a.kt","old_string":"val a = 1","new_string":"val a = 2"}""")
        TurnChanges.aggregate(listOf(first), cache)
        assertEquals(1, cache.parseCount)

        val second = tool(2, "Write", """{"file_path":"b.kt","content":"hello"}""")
        TurnChanges.aggregate(listOf(first, second), cache)
        assertEquals("只应解析新增的那一行", 2, cache.parseCount)
    }

    @Test
    fun diffCache_reparsesWhenRowContentChanges() {
        val cache = TurnChanges.DiffCache()
        val v1 = tool(1, "Edit", """{"file_path":"a.kt","old_string":"a","new_string":"b"}""")
        TurnChanges.aggregate(listOf(v1), cache)

        val v2 = tool(1, "Edit", """{"file_path":"a.kt","old_string":"a","new_string":"c"}""")
        TurnChanges.aggregate(listOf(v2), cache)
        assertEquals(2, cache.parseCount)
    }

    @Test
    fun diffCache_equivalentToUncached() {
        val rows = listOf(
            tool(1, "Edit", """{"file_path":"a.kt","old_string":"a","new_string":"b"}"""),
            tool(2, "Bash", """{"command":"ls"}"""),
            tool(3, "Write", """{"file_path":"b.kt","content":"hello"}"""),
        )
        // ToolDiff 是普通 class（引用相等），不能整体比较对象——按值指纹比较
        fun fingerprint(summaries: Map<Int, TurnDiffSummary>) = summaries.mapValues { (_, s) ->
            listOf(s.turnKey, s.lastRowId, s.fileCount, s.totalAdded, s.totalRemoved) to
                s.diffs.map { listOf(it.filePath, it.added, it.removed) }
        }
        val cache = TurnChanges.DiffCache()
        assertEquals(fingerprint(TurnChanges.aggregate(rows)), fingerprint(TurnChanges.aggregate(rows, cache)))
        // 三个工具行各经历一次缓存未命中（Bash 行的 parse 立即返回 null，
        // 计数含义是「缓存未命中次数」——与 SessionFiles 的入口过滤位置不同，此处为 3）
        assertEquals(3, cache.parseCount)
    }
}
