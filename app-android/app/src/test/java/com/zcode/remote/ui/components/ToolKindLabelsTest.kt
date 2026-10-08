package com.zcode.remote.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阶段 A 设计基座的纯函数单测。
 *
 * 覆盖两块逻辑：
 * 1. [ToolKindLabels] 的 toolName→家族→中文文案映射（数值取自桌面端渲染器，改错会直接体现在界面上）；
 * 2. [isAllowedLinkScheme] 的链接白名单（安全边界，必须逐条钉死）。
 *
 * 不含 Compose UI 断言：项目当前没有 androidTest 基建，视觉部分靠真机验收（见 HANDOVER）。
 */
class ToolKindLabelsTest {

    // ---------- 家族精确匹配 ----------

    @Test
    fun exactTableCoversDesktopFamilies() {
        assertEquals(ToolFamily.FileRead, ToolKindLabels.familyFor("Read"))
        assertEquals(ToolFamily.FileWrite, ToolKindLabels.familyFor("Write"))
        assertEquals(ToolFamily.FileWrite, ToolKindLabels.familyFor("Edit"))
        assertEquals(ToolFamily.FileWrite, ToolKindLabels.familyFor("ApplyPatch"))
        assertEquals(ToolFamily.Shell, ToolKindLabels.familyFor("Bash"))
        assertEquals(ToolFamily.Search, ToolKindLabels.familyFor("Glob"))
        assertEquals(ToolFamily.Search, ToolKindLabels.familyFor("Grep"))
        assertEquals(ToolFamily.Search, ToolKindLabels.familyFor("WebFetch"))
        assertEquals(ToolFamily.Search, ToolKindLabels.familyFor("WebSearch"))
        assertEquals(ToolFamily.Search, ToolKindLabels.familyFor("web_search"))
        assertEquals(ToolFamily.Todo, ToolKindLabels.familyFor("TodoRead"))
        assertEquals(ToolFamily.Todo, ToolKindLabels.familyFor("TodoWrite"))
        assertEquals(ToolFamily.Goal, ToolKindLabels.familyFor("GoalRead"))
        assertEquals(ToolFamily.SessionContext, ToolKindLabels.familyFor("ReadSessionContext"))
        assertEquals(ToolFamily.AskUserQuestion, ToolKindLabels.familyFor("AskUserQuestion"))
        assertEquals(ToolFamily.Message, ToolKindLabels.familyFor("SendMessage"))
        assertEquals(ToolFamily.TaskControl, ToolKindLabels.familyFor("TaskOutput"))
        assertEquals(ToolFamily.TaskControl, ToolKindLabels.familyFor("TaskStop"))
        assertEquals(ToolFamily.Agent, ToolKindLabels.familyFor("Agent"))
        assertEquals(ToolFamily.Agent, ToolKindLabels.familyFor("Task"))
        assertEquals(ToolFamily.Skill, ToolKindLabels.familyFor("Skill"))
    }

    @Test
    fun exactMatchIsCaseInsensitive() {
        assertEquals(ToolFamily.FileRead, ToolKindLabels.familyFor("read"))
        assertEquals(ToolFamily.FileRead, ToolKindLabels.familyFor("READ"))
        assertEquals(ToolFamily.FileRead, ToolKindLabels.familyFor("  Read  "))
        assertEquals(ToolFamily.Shell, ToolKindLabels.familyFor("BASH"))
    }

    @Test
    fun nodeReplFamilyIsRecognized() {
        assertEquals(ToolFamily.NodeRepl, ToolKindLabels.familyFor("js"))
        assertEquals(ToolFamily.NodeRepl, ToolKindLabels.familyFor("js_reset"))
        assertEquals(ToolFamily.NodeRepl, ToolKindLabels.familyFor("js_add_node_module_dir"))
        // MCP 前缀形态：剥掉 mcp__<server>__ 后仍应命中
        assertEquals(ToolFamily.NodeRepl, ToolKindLabels.familyFor("mcp__node_repl__js"))
        assertEquals(ToolFamily.Skill, ToolKindLabels.familyFor("mcp__zcode__skill"))
    }

    @Test
    fun kindIsUsedWhenToolNameMissing() {
        assertEquals(ToolFamily.Shell, ToolKindLabels.familyFor(null, "Bash"))
        assertEquals(ToolFamily.Shell, ToolKindLabels.familyFor("", "Bash"))
        assertEquals(ToolFamily.Shell, ToolKindLabels.familyFor("   ", "Bash"))
    }

    // ---------- 家族正则兜底 ----------

    @Test
    fun fallbackRegexesCoverSnakeCaseTools() {
        assertEquals(ToolFamily.FileRead, ToolKindLabels.familyFor("read_file"))
        assertEquals(ToolFamily.FileWrite, ToolKindLabels.familyFor("write_file"))
        assertEquals(ToolFamily.FileWrite, ToolKindLabels.familyFor("multi_edit"))
        assertEquals(ToolFamily.Shell, ToolKindLabels.familyFor("run_command"))
        assertEquals(ToolFamily.Shell, ToolKindLabels.familyFor("terminal_exec"))
        assertEquals(ToolFamily.Search, ToolKindLabels.familyFor("list_files"))
        assertEquals(ToolFamily.Search, ToolKindLabels.familyFor("web_fetch"))
        assertEquals(ToolFamily.Explore, ToolKindLabels.familyFor("explore"))
        assertEquals(ToolFamily.Explore, ToolKindLabels.familyFor("inspect_code"))
    }

    @Test
    fun camelCaseToolNamesHitExactTable() {
        // 大小写不敏感查表直接覆盖驼峰写法
        assertEquals(ToolFamily.AskUserQuestion, ToolKindLabels.familyFor("askUserQuestion"))
        assertEquals(ToolFamily.SessionContext, ToolKindLabels.familyFor("readSessionContext"))
        assertEquals(ToolFamily.FileWrite, ToolKindLabels.familyFor("applyPatch"))
        assertEquals(ToolFamily.Todo, ToolKindLabels.familyFor("todoWrite"))
    }

    @Test
    fun separatorVariantsFallBackToSquashedLookup() {
        // 连字符写法先被归一化，正则都命中不了时再去掉分隔符查一次精确表
        assertEquals(ToolFamily.AskUserQuestion, ToolKindLabels.familyFor("ask-user-question"))
        assertEquals(ToolFamily.FileWrite, ToolKindLabels.familyFor("apply-patch"))
        // 拼接写法桌面端也认不出来，这里保持一致地判为 unknown
        assertEquals(ToolFamily.Unknown, ToolKindLabels.familyFor("readFile"))
    }

    @Test
    fun unknownToolsYieldUnknownFamily() {
        assertEquals(ToolFamily.Unknown, ToolKindLabels.familyFor(null))
        assertEquals(ToolFamily.Unknown, ToolKindLabels.familyFor(null, null))
        assertEquals(ToolFamily.Unknown, ToolKindLabels.familyFor(""))
        assertEquals(ToolFamily.Unknown, ToolKindLabels.familyFor("Frobnicate"))
    }

    // ---------- 类型标签 ----------

    @Test
    fun labelMatchesDesktopWording() {
        assertEquals("读取", ToolKindLabels.label(ToolFamily.FileRead))
        assertEquals("终端", ToolKindLabels.label(ToolFamily.Shell))
        assertEquals("搜索", ToolKindLabels.label(ToolFamily.Search))
        assertEquals("待办", ToolKindLabels.label(ToolFamily.Todo))
        assertEquals("查阅", ToolKindLabels.label(ToolFamily.Explore))
        assertEquals("子智能体", ToolKindLabels.label(ToolFamily.Agent))
        assertEquals("技能", ToolKindLabels.label(ToolFamily.Skill))
        assertEquals("Node.js", ToolKindLabels.label(ToolFamily.NodeRepl))
        assertEquals("工具", ToolKindLabels.label(ToolFamily.Unknown))
    }

    @Test
    fun fileWriteLabelDistinguishesVerb() {
        assertEquals("写入", ToolKindLabels.label(ToolFamily.FileWrite, "Write"))
        assertEquals("编辑", ToolKindLabels.label(ToolFamily.FileWrite, "Edit"))
        assertEquals("编辑", ToolKindLabels.label(ToolFamily.FileWrite, "ApplyPatch"))
        assertEquals("删除", ToolKindLabels.label(ToolFamily.FileWrite, "delete_file"))
        // 无 toolName 时退化为「写入」，不能崩
        assertEquals("写入", ToolKindLabels.label(ToolFamily.FileWrite))
    }

    @Test
    fun runningLabelUsesProgressWording() {
        assertEquals("正在读取", ToolKindLabels.runningLabel(ToolFamily.FileRead))
        assertEquals("正在执行", ToolKindLabels.runningLabel(ToolFamily.Shell))
        assertEquals("正在搜索", ToolKindLabels.runningLabel(ToolFamily.Search))
        assertEquals("正在更新待办", ToolKindLabels.runningLabel(ToolFamily.Todo))
        assertEquals("正在运行技能", ToolKindLabels.runningLabel(ToolFamily.Skill))
        assertEquals("正在写入", ToolKindLabels.runningLabel(ToolFamily.FileWrite, "Write"))
        assertEquals("正在编辑", ToolKindLabels.runningLabel(ToolFamily.FileWrite, "Edit"))
        // 未定义运行态措辞的家族沿用静态标签
        assertEquals("子智能体", ToolKindLabels.runningLabel(ToolFamily.Agent))
    }

    // ---------- 状态文案 ----------

    @Test
    fun statusNormalizesBothDesktopAndWireNames() {
        assertEquals(ToolStatus.Pending, ToolKindLabels.normalizeStatus("pending"))
        assertEquals(ToolStatus.Pending, ToolKindLabels.normalizeStatus("inputStreaming"))
        assertEquals(ToolStatus.Pending, ToolKindLabels.normalizeStatus("input-streaming"))
        assertEquals(ToolStatus.Pending, ToolKindLabels.normalizeStatus("pendingApproval"))
        assertEquals(ToolStatus.Running, ToolKindLabels.normalizeStatus("running"))
        assertEquals(ToolStatus.Running, ToolKindLabels.normalizeStatus("in_progress"))
        assertEquals(ToolStatus.Running, ToolKindLabels.normalizeStatus("input-available"))
        assertEquals(ToolStatus.Completed, ToolKindLabels.normalizeStatus("success"))
        assertEquals(ToolStatus.Completed, ToolKindLabels.normalizeStatus("completed"))
        assertEquals(ToolStatus.Completed, ToolKindLabels.normalizeStatus("output-available"))
        assertEquals(ToolStatus.Failed, ToolKindLabels.normalizeStatus("error"))
        assertEquals(ToolStatus.Failed, ToolKindLabels.normalizeStatus("output-error"))
        assertEquals(ToolStatus.Denied, ToolKindLabels.normalizeStatus("output-denied"))
        assertEquals(ToolStatus.Stopped, ToolKindLabels.normalizeStatus("cancelled"))
        assertEquals(ToolStatus.Stopped, ToolKindLabels.normalizeStatus("stopped"))
        assertEquals(ToolStatus.Unknown, ToolKindLabels.normalizeStatus(null))
    }

    @Test
    fun statusLabelsAreTheSixDesktopStrings() {
        assertEquals("等待中", ToolKindLabels.statusLabel("input-streaming"))
        assertEquals("执行中", ToolKindLabels.statusLabel("running"))
        assertEquals("已执行", ToolKindLabels.statusLabel("output-available"))
        assertEquals("执行失败", ToolKindLabels.statusLabel("output-error"))
        assertEquals("已拒绝", ToolKindLabels.statusLabel("output-denied"))
        assertEquals("已停止", ToolKindLabels.statusLabel("stopped"))
        // 未知状态不渲染状态字，而不是显示一个占位符
        assertNull(ToolKindLabels.statusLabel("something-else"))
        assertNull(ToolKindLabels.statusLabel(null))
    }

    // ---------- 主文案 ----------

    @Test
    fun fileReadPrimaryTextShowsBasename() {
        assertEquals("c.kt", ToolKindLabels.primaryText("Read", inputJson = """{"file_path":"/a/b/c.kt"}"""))
        assertEquals("y.md", ToolKindLabels.primaryText("Read", inputJson = """{"filePath":"C:\\x\\y.md"}"""))
        // input 缺失时退回 title
        assertEquals("foo.txt", ToolKindLabels.primaryText("Read", title = "foo.txt"))
    }

    @Test
    fun searchPrimaryTextMatchesDesktopWording() {
        assertEquals("查找 TODO", ToolKindLabels.primaryText("Grep", inputJson = """{"pattern":"TODO"}"""))
        assertEquals("查找 aaa", ToolKindLabels.primaryText("Grep", inputJson = """{"query":"aaa"}"""))
        assertEquals("列出 /src", ToolKindLabels.primaryText("Glob", inputJson = """{"cwd":"/src"}"""))
    }

    @Test
    fun shellPrimaryTextExtractsCommand() {
        assertEquals("echo hi", ToolKindLabels.primaryText("Bash", inputJson = """{"command":"echo hi"}"""))
        // 数组形态取 -lc 之后的一项（桌面端 QW 的规则）
        assertEquals(
            "ls -la",
            ToolKindLabels.primaryText("Bash", inputJson = """{"command":["bash","-lc","ls -la"]}"""),
        )
        // 没有 -lc 时整体空格拼接
        assertEquals(
            "git status",
            ToolKindLabels.primaryText("Bash", inputJson = """{"command":["git","status"]}"""),
        )
        // 换行与缩进压成单行
        assertEquals("a b", ToolKindLabels.primaryText("Bash", inputJson = """{"command":"a\n   b"}"""))
        // 完全没有 command 字段时退回 title
        assertEquals("my title", ToolKindLabels.primaryText("Bash", title = "my title", inputJson = "{}"))
    }

    @Test
    fun primaryTextToleratesMalformedInput() {
        // inputJson 不是合法 JSON 对象时静默降级，不抛异常、也不硬塞一段乱码当主文案
        assertNull(ToolKindLabels.primaryText("Read", inputJson = "not json"))
        assertNull(ToolKindLabels.primaryText(null, inputJson = null))
    }

    @Test
    fun basenameHandlesBothSeparators() {
        assertEquals("c.kt", ToolKindLabels.basename("/a/b/c.kt"))
        assertEquals("c.kt", ToolKindLabels.basename("C:\\a\\b\\c.kt"))
        assertEquals("c.kt", ToolKindLabels.basename("c.kt"))
    }

    // ---------- 链接白名单（安全边界） ----------

    @Test
    fun linkWhitelistAllowsHttpAndHttpsOnly() {
        assertTrue(isAllowedLinkScheme("https://example.com/a"))
        assertTrue(isAllowedLinkScheme("http://example.com"))
        assertTrue(isAllowedLinkScheme("HTTPS://EXAMPLE.COM"))
    }

    @Test
    fun linkWhitelistBlocksDangerousSchemes() {
        assertFalse(isAllowedLinkScheme("javascript:alert(1)"))
        assertFalse(isAllowedLinkScheme("file:///etc/passwd"))
        assertFalse(isAllowedLinkScheme("content://com.example/secret"))
        assertFalse(isAllowedLinkScheme("intent://scan/#Intent;scheme=zxing;end"))
        assertFalse(isAllowedLinkScheme("data:text/html,<script>1</script>"))
        assertFalse(isAllowedLinkScheme("tel:+10086"))
        assertFalse(isAllowedLinkScheme("no-scheme-here"))
        assertFalse(isAllowedLinkScheme(""))
    }
}
