package com.zcode.remote.relay

import com.zcode.remote.AppViewModel
import com.zcode.remote.storage.PairedDevice
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 协议边界纯函数单测（Sprint 6）。
 * QrParser 依赖 android.net.Uri（JVM 单测不可用），其「+ → %2B」坑的回归
 * 由真机验收覆盖（HANDOVER §5.7），此处不纳入。
 */
class PureFunctionsTest {

    // ---------- ConversationRow.from ----------

    @Test
    fun conversationRowParsesAllFields() {
        val o = buildJsonObject {
            put("rowId", 42)
            put("kind", "toolCall")
            put("turnId", "turn-1")
            put("text", JsonNull)
            put("state", "success")
            put("toolName", "Edit")
            put("toolCallId", "call_1")
            put("status", "success")
            put("inputText", "{\"old_string\":\"a\"}")
            put("createdAt", 1_700_000_000_000)
            put("output", buildJsonObject { put("text", "done") })
        }
        val row = ConversationRow.from(o)!!
        assertEquals(42, row.rowId)
        assertEquals("toolCall", row.kind)
        assertEquals("Edit", row.toolName)
        assertEquals("success", row.status)
        assertEquals("{\"old_string\":\"a\"}", row.inputText)
        assertEquals("done", row.outputText)
        assertEquals(1_700_000_000_000L, row.createdAt)
        // raw 保留整包（审批等结构化字段的取值来源）
        assertEquals(o, row.raw)
    }

    @Test
    fun conversationRowRequiresRowIdAndKind() {
        assertNull(ConversationRow.from(buildJsonObject { put("kind", "toolCall") }))
        assertNull(ConversationRow.from(buildJsonObject { put("rowId", 1) }))
    }

    // ---------- ApprovalOption.sortKey ----------

    private fun opt(kind: String?) = ApprovalOption("id", "标签", kind, null)

    @Test
    fun approvalSortKeyOrdering() {
        val ranked = listOf(
            opt("deny"), opt("rejectAlways"), opt("allowAlways"),
            opt("allowOnce"), opt(null), opt("escalate"),
        ).sortedBy { it.sortKey }
        // 单次批准(0) → 永久批准(1) → 拒绝/单次拒绝(2) → 永久拒绝(3) → 其他(4/5)
        // 「其他」内部按稳定排序保持原序（null 在前、escalate 在后）
        assertEquals(
            listOf("allowOnce", "allowAlways", "deny", "rejectAlways", null, "escalate"),
            ranked.map { it.kind },
        )
    }

    @Test
    fun approvalDecisionFallbacks() {
        // kind 缺失时按 decision 推断
        val allow = ApprovalOption("id", "", null, "allow")
        assertTrue(allow.isAllow)
        val deny = ApprovalOption("id", "", null, "deny")
        assertEquals(4, deny.sortKey)  // isDeny 兜底分支
        // label 为空时 displayLabel 回退 optionId
        assertEquals("id", ApprovalOption("id", "", null, null).displayLabel)
    }

    // ---------- WorkspaceConfigChannel.splitModelValue ----------

    @Test
    fun splitModelValueStripsProviderAndVariant() {
        assertEquals("glm" to "deepseek-v4.1", WorkspaceConfigChannel.splitModelValue("glm/deepseek-v4.1"))
        // $variant 剥离（带档位变体的模型值）
        assertEquals(
            "glm" to "deepseek-v4.1",
            WorkspaceConfigChannel.splitModelValue("glm/deepseek-v4.1\$xhigh"),
        )
        // 无斜杠：回退默认 provider "glm"（官方 pf() 兜底）
        assertEquals("glm" to "deepseek-v4.1", WorkspaceConfigChannel.splitModelValue("deepseek-v4.1"))
    }

    // ---------- AppViewModel.isNewerVersion（检查更新）----------

    @Test
    fun versionComparisonSemantics() {
        // 原 bug 场景：远端是旧版本，字符串不等判断会误报"有新版本"
        assertEquals(false, AppViewModel.isNewerVersion("0.5.0-beta5", "0.4.0-beta6"))
        assertEquals(false, AppViewModel.isNewerVersion("0.5.0-beta5", "0.5.0-beta5"))
        // 正常升级
        assertEquals(true, AppViewModel.isNewerVersion("0.5.0-beta5", "0.5.0-beta6"))
        assertEquals(true, AppViewModel.isNewerVersion("0.5.0-beta5", "0.6.0-beta1"))
        // 同核心版本：remote 正式版 > current 预发布；反之不然
        assertEquals(true, AppViewModel.isNewerVersion("0.5.0-beta5", "0.5.0"))
        assertEquals(false, AppViewModel.isNewerVersion("0.5.0", "0.5.0-beta5"))
        // prerelease 数字感知（字典序会把 beta10 排在 beta9 前）
        assertEquals(true, AppViewModel.isNewerVersion("0.5.0-beta9", "0.5.0-beta10"))
        assertEquals(false, AppViewModel.isNewerVersion("0.5.0-beta10", "0.5.0-beta9"))
    }

    // ---------- SessionFiles（剧本 B「最近文件」）----------

    private fun toolRow(rowId: Int, tool: String, input: String? = null) = ConversationRow(
        rowId = rowId, kind = "toolCall", toolName = tool, inputText = input,
    )

    @Test
    fun extractPathFromWriteLikeTools() {
        assertEquals(
            "F:/x/a.kt",
            SessionFiles.extractPath("""{"file_path":"F:/x/a.kt","old_string":"a","new_string":"b"}"""),
        )
        assertEquals("~/.zcode/v2/c.json", SessionFiles.extractPath("""{"path":"~/.zcode/v2/c.json"}"""))
        assertNull(SessionFiles.extractPath("""{"command":"ls"}"""))          // Bash 无路径字段
        assertNull(SessionFiles.extractPath("not json"))                       // 非 JSON
        assertNull(SessionFiles.extractPath(null))
    }

    @Test
    fun extractDedupesByPathWithLatestFirst() {
        val rows = listOf(
            toolRow(1, "Read", """{"file_path":"F:/x/one.kt"}"""),
            toolRow(2, "Bash", """{"command":"echo hi"}"""),                   // 应跳过
            toolRow(3, "Write", """{"file_path":"F:/x/two.kt"}"""),
            toolRow(4, "Edit", """{"file_path":"F:/x/one.kt"}"""),             // one.kt 再次出现 → 置顶
        )
        val files = SessionFiles.extract(rows)
        assertEquals(listOf("F:/x/one.kt", "F:/x/two.kt"), files.map { it.path })
        assertEquals(4, files[0].rowId)                                        // 保留最近操作行
        assertEquals(2, files.size)
    }

    // ---------- PairedDevice.relayWsUrl ----------

    @Test
    fun relayWsUrlByRemoteHost() {
        val zai = PairedDevice("sid", "hash", null, null, "https://zcode.z.ai/remote/v4")
        assertEquals("wss://zcode.z.ai/ws", zai.relayWsUrl)
        val backup = PairedDevice("sid", "hash", null, null, "https://zcode.chatglm.site/remote/v4")
        assertEquals("wss://zcode.chatglm.site/ws", backup.relayWsUrl)
    }

    // ---------- TurnChanges.aggregate ----------

    @Test
    fun turnChangesAggregatesWriteToolsInSameTurn() {
        val rows = listOf(
            toolRow(1, "userInput", null).copy(kind = "userInput", turnId = "turn_1"),
            toolRow(2, "Edit", """{"file_path":"F:/x/a.kt","old_string":"foo","new_string":"bar"}""").copy(turnId = "turn_1"),
            toolRow(3, "Bash", """{"command":"ls"}""").copy(turnId = "turn_1"),
            toolRow(4, "Write", """{"file_path":"F:/x/b.txt","content":"hello\nworld"}""").copy(turnId = "turn_1"),
            toolRow(5, "userInput", null).copy(kind = "userInput", turnId = "turn_2"),
            toolRow(6, "Bash", """{"command":"git status"}""").copy(turnId = "turn_2"),
        )
        val summaries = TurnChanges.aggregate(rows)
        // 只有 turn_1 包含写工具，且挂载在最后一个写操作 rowId=4 上
        assertEquals(1, summaries.size)
        val s = summaries[4]!!
        assertEquals("turn_1", s.turnKey)
        assertEquals(4, s.lastRowId)
        assertEquals(2, s.fileCount)
        assertEquals(2, s.diffs.size)
        assertTrue(s.totalAdded > 0)
        assertTrue(s.totalRemoved > 0)
    }

    // ---------- SessionItem 序列化与离线缓存 ----------

    @Test
    fun sessionItemSerializationRoundtrip() {
        val original = listOf(
            SessionItem(
                taskId = "sess_001",
                title = "测试会话1",
                displayStatus = "running",
                workspacePath = "F:/project",
                workspaceLabel = "project",
                provider = "glm",
                updatedAt = 1_700_000_000_000L,
                archived = false
            ),
            SessionItem(
                taskId = "sess_002",
                title = "测试会话2",
                displayStatus = "idle",
                workspacePath = null,
                workspaceLabel = null,
                provider = null,
                updatedAt = null,
                archived = true
            )
        )
        val jsonStr = kotlinx.serialization.json.Json.encodeToString(original)
        val decoded = kotlinx.serialization.json.Json.decodeFromString<List<SessionItem>>(jsonStr)
        assertEquals(original, decoded)
        assertEquals(2, decoded.size)
        assertEquals("sess_001", decoded[0].taskId)
        assertEquals(true, decoded[0].isRunning)
        assertEquals(false, decoded[0].archived)
        assertEquals(true, decoded[1].archived)
    }

    // ---------- ConversationRow 离线消息行序列化与 RowStore.snapshot ----------

    @Test
    fun conversationRowSerializationAndRowStoreSnapshot() {
        val rows = listOf(
            ConversationRow(
                rowId = 1,
                kind = "userInput",
                turnId = "t1",
                text = "hello agent",
                createdAt = 1_700_000_000_100L
            ),
            ConversationRow(
                rowId = 2,
                kind = "toolCall",
                toolName = "Edit",
                inputText = """{"file_path":"F:/a.txt","old_string":"foo","new_string":"bar"}""",
                status = "success"
            )
        )
        val jsonStr = kotlinx.serialization.json.Json.encodeToString(rows)
        val decoded = kotlinx.serialization.json.Json.decodeFromString<List<ConversationRow>>(jsonStr)
        assertEquals(rows, decoded)
        assertEquals(2, decoded.size)
        assertEquals("hello agent", decoded[0].text)
        assertEquals("Edit", decoded[1].toolName)

        val storeList = mutableListOf<ConversationRow>()
        val store = RowStore(storeList)
        store.replaceAll(decoded)
        val snapshot = store.snapshot()
        assertEquals(2, snapshot.size)
        assertEquals(decoded, snapshot)
    }
}
