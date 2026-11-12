package com.zcode.remote.ui.screens

import com.zcode.remote.relay.ConversationRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 会话内查找纯函数（F1）：多字段检索 / 大小写不敏感 / 无效 query 短路。 */
class ConversationSearchPolicyTest {
    private fun row(
        rowId: Int,
        text: String? = null,
        inputText: String? = null,
        outputText: String? = null,
        summaryText: String? = null,
        errorText: String? = null,
    ): ConversationRow =
        ConversationRow(
            rowId = rowId,
            kind = "assistantText",
            text = text,
            inputText = inputText,
            outputText = outputText,
            summaryText = summaryText,
            errorText = errorText,
        )

    @Test
    fun `正文命中且大小写不敏感`() {
        val rows = listOf(row(1, text = "Hello World"), row(2, text = "nothing"))
        assertEquals(listOf(1), ConversationSearchPolicy.match(rows, "hello"))
        assertEquals(listOf(1), ConversationSearchPolicy.match(rows, "HELLO"))
    }

    @Test
    fun `工具输入与输出与摘要与错误原因都在检索面`() {
        val rows =
            listOf(
                row(1, inputText = "ls -la /path"),
                row(2, outputText = "build SUCCESSFUL"),
                row(3, summaryText = "编辑了 3 个文件"),
                row(4, errorText = "ENOENT: no such file"),
            )
        assertEquals(listOf(1), ConversationSearchPolicy.match(rows, "/path"))
        assertEquals(listOf(2), ConversationSearchPolicy.match(rows, "successful"))
        assertEquals(listOf(3), ConversationSearchPolicy.match(rows, "3 个文件"))
        assertEquals(listOf(4), ConversationSearchPolicy.match(rows, "enoent"))
    }

    @Test
    fun `无效query与空query返回空`() {
        val rows = listOf(row(1, text = "abc"))
        assertTrue(ConversationSearchPolicy.match(rows, "   ").isEmpty())
        assertTrue(ConversationSearchPolicy.match(rows, "").isEmpty())
        assertFalse(ConversationSearchPolicy.isQueryValid(" "))
    }

    @Test
    fun `无可检索文本的行不命中`() {
        val marker = ConversationRow(rowId = 9, kind = "timelineMarker")
        assertNull(ConversationSearchPolicy.searchableText(marker))
        assertTrue(ConversationSearchPolicy.match(listOf(marker), "a").isEmpty())
    }

    @Test
    fun `命中结果按时间序返回`() {
        val rows = listOf(row(3, text = "x a x"), row(1, text = "a"), row(2, text = "A b"))
        assertEquals("rowId 升序（会话时间序）", listOf(1, 2, 3), ConversationSearchPolicy.match(rows, "a"))
    }

    @Test
    fun `rowMatches 与 match 同口径`() {
        val row = row(1, text = "关键词")
        assertTrue(ConversationSearchPolicy.rowMatches(row, "关键词"))
        assertTrue(ConversationSearchPolicy.rowMatches(row, " 键词 "))
        assertFalse(ConversationSearchPolicy.rowMatches(row, "不存在"))
        assertFalse(ConversationSearchPolicy.rowMatches(row, ""))
    }
}
