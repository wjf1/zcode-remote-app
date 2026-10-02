package com.zcode.remote.relay

import com.zcode.remote.ui.components.DiffType
import com.zcode.remote.ui.components.ToolDiffParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sprint 3 diff 解析与 Myers 算法单测（纯 JVM）。
 * 覆盖：写类工具识别、Edit/MultiEdit/Write 三种输入形态、diff 行序列正确性、
 * 空端边界、回溯正确性（交换型修改）、超限降级、unified 文本格式。
 */
class ToolDiffTest {

    // ---------- parser：工具识别与输入形态 ----------

    @Test
    fun parsesEditToolOldNew() {
        val input = """{"file_path":"src/a.kt","old_string":"val a = 1\nval b = 2","new_string":"val a = 1\nval b = 3"}"""
        val diff = ToolDiffParser.parse("Edit", input)!!
        assertEquals("src/a.kt", diff.filePath)
        // 期望：CONTEXT(a) DEL(b=2) ADD(b=3)
        val types = diff.lines.map { it.type }
        assertEquals(listOf(DiffType.CONTEXT, DiffType.DEL, DiffType.ADD), types)
        assertEquals(1, diff.added)
        assertEquals(1, diff.removed)
        assertEquals(1, diff.lines[0].oldNo)
        assertEquals(2, diff.lines[2].newNo)
    }

    @Test
    fun parsesMultiEditEditsArrayWithSeparator() {
        val input = """
            {"file_path":"f.txt","edits":[
                {"old_string":"one","new_string":"ONE"},
                {"old_string":"two","new_string":"TWO"}
            ]}
        """.trimIndent()
        val diff = ToolDiffParser.parse("MultiEdit", input)!!
        // 两段编辑之间应有 "⋯" 分隔行
        assertTrue(diff.lines.any { it.type == DiffType.CONTEXT && it.text == "⋯" })
        assertTrue(diff.lines.count { it.type == DiffType.DEL } >= 2)
    }

    @Test
    fun parsesWriteAsPureAdditions() {
        val input = """{"file_path":"new.md","content":"# Title\nbody"}"""
        val diff = ToolDiffParser.parse("Write", input)!!
        assertEquals(2, diff.added)
        assertEquals(0, diff.removed)
        assertTrue(diff.lines.all { it.type == DiffType.ADD })
        assertEquals(1, diff.lines[0].newNo)
        assertNull(diff.lines[0].oldNo)
    }

    @Test
    fun rejectsNonWriteToolsAndMalformedJson() {
        assertNull(ToolDiffParser.parse("Bash", """{"command":"ls"}"""))
        assertNull(ToolDiffParser.parse("Read", """{"file_path":"x","old_string":"a","new_string":"b"}"""))
        assertNull(ToolDiffParser.parse("Edit", "not json at all"))
        assertNull(ToolDiffParser.parse("Edit", """{"file_path":"x"}"""))
        assertNull(ToolDiffParser.parse(null, """{"old_string":"a"}"""))
        // old == new：无实际变更
        assertNull(ToolDiffParser.parse("Edit", """{"file_path":"x","old_string":"same","new_string":"same"}"""))
    }

    // ---------- diffLines：Myers 算法 ----------

    private fun diff(old: String, new: String) =
        ToolDiffParser.diffLines(old.lines(), new.lines())

    @Test
    fun identicalInputYieldsAllContext() {
        val out = diff("a\nb\nc", "a\nb\nc")
        assertTrue(out.all { it.type == DiffType.CONTEXT })
        assertEquals(3, out.size)
    }

    @Test
    fun emptyOldYieldsAllAdditions() {
        val out = diff("", "x\ny")
        assertEquals(listOf(DiffType.ADD, DiffType.ADD), out.map { it.type })
    }

    @Test
    fun emptyNewYieldsAllDeletions() {
        // 注意：不用 "".lines()（它是 [""] 单空行而非空列表），显式表达「新文件为空」
        val out = ToolDiffParser.diffLines(listOf("x", "y"), emptyList())
        assertEquals(listOf(DiffType.DEL, DiffType.DEL), out.map { it.type })
    }

    @Test
    fun kotlinEmptyStringLinesTrapIsNormalized() {
        // Kotlin "".lines() == [""]：Write 全新文件场景（old_string 为空）应视为空列表 → 全 ADD
        val out = ToolDiffParser.diffLines("".lines(), "x\ny".lines())
        assertEquals(listOf(DiffType.ADD, DiffType.ADD), out.map { it.type })
    }

    @Test
    fun middleEditKeepsContextAround() {
        val out = diff("A\nB\nC", "A\nX\nC")
        assertEquals("A", out[0].text)
        assertEquals(DiffType.CONTEXT, out[0].type)
        assertEquals(DiffType.DEL, out[1].type)
        assertEquals("B", out[1].text)
        assertEquals(DiffType.ADD, out[2].type)
        assertEquals("X", out[2].text)
        assertEquals(DiffType.CONTEXT, out[3].type)
        assertEquals("C", out[3].text)
    }

    @Test
    fun swappedLinesBacktracksCorrectly() {
        // 交换型修改是 Myers 回溯的经典坑：ab → ba 的最优脚本是 1 删 + 1 增（保留一行作为上下文）
        val out = diff("a\nb", "b\na")
        assertEquals(1, out.count { it.type == DiffType.ADD })
        assertEquals(1, out.count { it.type == DiffType.DEL })
        // 重建校验（本质正确性）：删掉的 + 保留的 = 旧文本；保留的 + 新增的 = 新文本
        val rebuiltOld = out.mapNotNull { if (it.type != DiffType.ADD) it.text else null }
        val rebuiltNew = out.mapNotNull { if (it.type != DiffType.DEL) it.text else null }
        assertEquals(listOf("a", "b"), rebuiltOld)
        assertEquals(listOf("b", "a"), rebuiltNew)
    }

    @Test
    fun rebuildOldAndNewFromDiff() {
        val old = "line1\nline2\nline3\nline4\nline5"
        val new = "line1\nline2 edited\nline3\nline4\nline5 plus"
        val out = diff(old, new)
        val rebuiltOld = out.mapNotNull { if (it.type != DiffType.ADD) it.text else null }
        val rebuiltNew = out.mapNotNull { if (it.type != DiffType.DEL) it.text else null }
        assertEquals(listOf("line1", "line2", "line3", "line4", "line5"), rebuiltOld)
        assertEquals(listOf("line1", "line2 edited", "line3", "line4", "line5 plus"), rebuiltNew)
    }

    @Test
    fun oversizedInputFallsBackToFullReplace() {
        val old = (1..2500).joinToString("\n") { "old $it" }
        val new = (1..2500).joinToString("\n") { "new $it" }
        val out = diff(old, new)
        // 超过 4000 行合计 → 降级：全 DEL 后全 ADD，不做精细对齐
        assertEquals(2500, out.count { it.type == DiffType.DEL })
        assertEquals(2500, out.count { it.type == DiffType.ADD })
        assertEquals(DiffType.DEL, out.first().type)
    }

    @Test
    fun unifiedTextFormatAndTruncation() {
        val diff = ToolDiffParser.parse("Edit", """{"file_path":"a.txt","old_string":"x","new_string":"y"}""")!!
        val text = diff.toUnifiedText()
        assertTrue(text.startsWith("--- a/a.txt\n+++ b/a.txt\n"))
        assertTrue(text.contains("\n-x"))
        assertTrue(text.contains("\n+y"))
    }
}
