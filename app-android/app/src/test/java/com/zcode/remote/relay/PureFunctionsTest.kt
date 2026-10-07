package com.zcode.remote.relay

import com.zcode.remote.AppViewModel
import com.zcode.remote.storage.PairedDevice
import com.zcode.remote.ui.screens.ConversationScrollPolicy
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ---------- userInput 行的附件回显（会话流附件 chip 渲染的数据来源）----------

    @Test
    fun conversationRowParsesAttachments() {
        // 形状取自真机缓存里的真实样本（见 CHANGELOG：master-plan-v1.1.md / 39733 bytes）
        val o = buildJsonObject {
            put("rowId", 282)
            put("kind", "userInput")
            put("origin", "realUser")
            put("text", "针对 https://github.com/wjf1/zcode-dotfiles 的优化提升方案")
            putJsonArray("attachments") {
                addJsonObject {
                    put("ref", "zcode-artifact://sess_x/tool-result-y")
                    put("fileName", "master-plan-v1.1.md")
                    put("mime", "text/plain")
                    put("bytes", 39_733)
                }
            }
        }
        val row = ConversationRow.from(o)!!
        assertEquals(1, row.attachments.size)
        assertEquals("master-plan-v1.1.md", row.attachments[0].fileName)
        assertEquals("text/plain", row.attachments[0].mime)
        assertEquals(39_733L, row.attachments[0].bytes)
        assertEquals("zcode-artifact://sess_x/tool-result-y", row.attachments[0].ref)
    }

    @Test
    fun conversationRowAttachmentParsingIsFaultTolerant() {
        // 无附件字段 → 空列表（绝大多数行）
        val plain = ConversationRow.from(buildJsonObject { put("rowId", 1); put("kind", "userInput") })!!
        assertTrue(plain.attachments.isEmpty())
        // attachments 不是数组 → 容错为空，不抛异常
        val notArray = ConversationRow.from(
            buildJsonObject { put("rowId", 2); put("kind", "userInput"); put("attachments", "oops") },
        )!!
        assertTrue(notArray.attachments.isEmpty())
        // 数组里混入垃圾元素 → 跳过垃圾、保留合法项
        val mixed = ConversationRow.from(
            buildJsonObject {
                put("rowId", 3); put("kind", "userInput")
                putJsonArray("attachments") {
                    addJsonObject { put("fileName", "ok.txt") }
                    addJsonObject { /* 全空，应丢弃 */ }
                }
            },
        )!!
        assertEquals(1, mixed.attachments.size)
        assertEquals("ok.txt", mixed.attachments[0].fileName)
    }

    // ---------- 会话异常原因与标题解包（用户反馈：APP 只显示「异常」没有原因）----------

    @Test
    fun errorTextPrefersMessageThenDetail() {
        // 形状取自桌面端 tasks-index 的 task meta.lastError 实锤样本
        val obj = buildJsonObject {
            put("code", "3009")
            put("detail", "Turn execution failed\nprovider=… reason=rate_limited status=429 retryable=false")
            put("message", "模型并发超限，稍后重试")
        }
        assertEquals("模型并发超限，稍后重试", SessionItem.errorText(obj))
        // 没有 message 时退 detail（保留换行的原文）
        val detailOnly = buildJsonObject {
            put("code", "3009")
            put("detail", "Turn execution failed\nreason=rate_limited")
        }
        assertEquals("Turn execution failed\nreason=rate_limited", SessionItem.errorText(detailOnly))
        // 已是字符串 / null / 空对象
        assertEquals("直接字符串", SessionItem.errorText(kotlinx.serialization.json.JsonPrimitive("直接字符串")))
        assertNull(SessionItem.errorText(JsonNull))
        assertNull(SessionItem.errorText(buildJsonObject { }))
    }

    @Test
    fun unwrapJsonTitleHandlesDoubleSerializedTitle() {
        // host 曾把标题双重序列化成 {"title":"…"}（tasks-index 里 sess_98e11ba2 实锤）
        assertEquals(
            "分析 ZCode STREAM_IDLE_TIMEOUT 报错原因",
            SessionItem.unwrapJsonTitle("""{"title":"分析 ZCode STREAM_IDLE_TIMEOUT 报错原因"}"""),
        )
        // 正常标题原样返回
        assertEquals("普通标题", SessionItem.unwrapJsonTitle("普通标题"))
        // 非 JSON / 非 title 键的对象 → 原样返回，不抛异常
        assertEquals("not json", SessionItem.unwrapJsonTitle("not json"))
        assertEquals("""{"a":1}""", SessionItem.unwrapJsonTitle("""{"a":1}"""))
        assertNull(SessionItem.unwrapJsonTitle(null))
        assertNull(SessionItem.unwrapJsonTitle(""))
    }

    @Test
    fun sessionItemParsesLastErrorAndUnwrapsTitle() {
        val o = buildJsonObject {
            put("taskId", "sess_x")
            put("title", """{"title":"真标题"}""")
            put("displayStatus", "error")
            put("lastError", buildJsonObject { put("code", "3009"); put("detail", "reason=rate_limited") })
        }
        val s = SessionItem.from(o)!!
        assertEquals("真标题", s.title)
        assertEquals("error", s.displayStatus)
        assertEquals("reason=rate_limited", s.lastError)
        // 无 lastError 字段（PROTOCOL.md 记录的 bootstrap 形状）→ null，列表退回只显示「异常」
        val plain = SessionItem.from(buildJsonObject {
            put("taskId", "sess_y"); put("title", "t"); put("displayStatus", "error")
        })!!
        assertNull(plain.lastError)
    }

    @Test
    fun parseControlReadsLastError() {
        // 形状见 research/CONVERSATION-PROTOCOL.md:159（健康会话 lastError:null）
        assertNull(ConversationFrames.parseControl(buildJsonObject {
            put("phase", "completedSuccess"); put("canStop", false); put("stopState", "idle")
            put("lastError", JsonNull)
        })?.lastError)
        assertEquals(
            "reason=rate_limited",
            ConversationFrames.parseControl(buildJsonObject {
                put("phase", "completedError")
                put("lastError", buildJsonObject { put("detail", "reason=rate_limited") })
            })?.lastError,
        )
        // control 缺失 → 整体为 null
        assertNull(ConversationFrames.parseControl(null))
    }

    // ---------- A-1：上传回调的会话归属判定 ----------

    @Test
    fun uploadBelongsToSession() {
        // 同会话：允许回写
        assertTrue(AppViewModel.uploadBelongsTo("sess_A", "sess_A"))
        // 跨会话：必须丢弃（否则 A 的在途上传会把文件注入 B 的附件条）
        assertFalse(AppViewModel.uploadBelongsTo("sess_A", "sess_B"))
        // 发起时未订阅 / 当前已断开：一律丢弃
        assertFalse(AppViewModel.uploadBelongsTo(null, "sess_A"))
        assertFalse(AppViewModel.uploadBelongsTo("sess_A", null))
        assertFalse(AppViewModel.uploadBelongsTo(null, null))
    }

    // ---------- B-3：反馈横幅时长档位（显式类型，不再靠字符串前缀猜）----------

    @Test
    fun flashDurationByKind() {
        assertEquals(8_000L, AppViewModel.flashDurationMs(AppViewModel.FlashKind.Failure))
        assertEquals(4_000L, AppViewModel.flashDurationMs(AppViewModel.FlashKind.Info))
    }

    // ---------- A-2：握手失败文案与迟到应答判定 ----------

    @Test
    fun handshakeFailureReasonIsReadableChinese() {
        val timeout = ConversationChannel.handshakeFailureReason("握手", "timeout after 8000ms")
        assertEquals("握手超时，请检查网络后重试", timeout)
        // 裸英文串不得透出到 UI（任务书 §7 异常的 grep 断言）
        assertFalse(timeout.contains("timeout after"))
        assertEquals(
            "通道尚未就绪，请稍后重试",
            ConversationChannel.handshakeFailureReason("订阅", "bridge not ready"),
        )
        // 非超时类失败保留服务端原文，便于定位
        assertTrue(
            ConversationChannel.handshakeFailureReason("订阅", "fault.connection.handshakeRequired")
                .contains("handshakeRequired"),
        )
    }

    @Test
    fun isTimeoutErrorOnlyMatchesTimeout() {
        assertTrue(ConversationChannel.isTimeoutError("timeout after 8000ms"))
        assertFalse(ConversationChannel.isTimeoutError("bridge not ready"))
        assertFalse(ConversationChannel.isTimeoutError("fault.connection.handshakeRequired"))
    }

    @Test
    fun transientHandshakeErrorCoversBridgeNotReady() {
        // 超时（A-2 场景）
        assertTrue(ConversationChannel.isTransientHandshakeError("timeout after 4000ms"))
        // 桥未就绪：真机反馈的「中继已连接、会话却报 hello: bridge not ready 且永不恢复」根因
        assertTrue(ConversationChannel.isTransientHandshakeError("bridge not ready"))
        // 桥重建导致旧请求作废
        assertTrue(ConversationChannel.isTransientHandshakeError("bridge re-established"))
        // 协议层硬性拒绝：重试无意义，不得被判为瞬态
        assertFalse(ConversationChannel.isTransientHandshakeError("fault.connection.handshakeRequired"))
        assertFalse(ConversationChannel.isTransientHandshakeError("subscribe ack 缺少 subscriptionId"))
    }

    // ---------- 重连退避（网络恢复须能重置，见 NetworkGate / RelayClient.onNetworkAvailable）----------

    @Test
    fun reconnectBackoffIsExponentialAndCapped() {
        assertEquals(3_000L, reconnectBackoffMs(0))
        assertEquals(6_000L, reconnectBackoffMs(1))
        assertEquals(12_000L, reconnectBackoffMs(2))
        assertEquals(24_000L, reconnectBackoffMs(3))
        assertEquals(48_000L, reconnectBackoffMs(4))
        // 封顶：更大的 attempt 不再增长（真机实测旧实现会烧到 48s 后白等一整轮）
        assertEquals(48_000L, reconnectBackoffMs(5))
        assertEquals(48_000L, reconnectBackoffMs(50))
    }

    @Test
    fun staleReplyDetection() {
        // 同代次：本轮握手的应答，正常处理
        assertFalse(ConversationChannel.isStaleReply(3, 3))
        // 旧代次：切会话 / 自动重订后的迟到应答，必须丢弃
        assertTrue(ConversationChannel.isStaleReply(2, 3))
    }

    // ---------- B-1：进会话定位 / 对齐 / 增量跟随的条件判定 ----------

    private fun pin(
        trigger: ConversationScrollPolicy.Trigger,
        rowCount: Int = 10,
        paginationInFlight: Boolean = false,
        alreadyPinned: Boolean = false,
        userScrolledAway: Boolean = false,
    ) = ConversationScrollPolicy.shouldPinToLatest(
        trigger, rowCount, paginationInFlight, alreadyPinned, userScrolledAway,
    )

    @Test
    fun scrollPolicyEnterPinsOnce() {
        // 进会话（有缓存 / 无缓存的区别只体现在首帧行数，判定一致）：必须定位
        assertTrue(pin(ConversationScrollPolicy.Trigger.Enter))
        // 已定位过不重复（否则每次重组都强拉回底部）
        assertFalse(pin(ConversationScrollPolicy.Trigger.Enter, alreadyPinned = true))
        // 尚无行时无目标可滚
        assertFalse(pin(ConversationScrollPolicy.Trigger.Enter, rowCount = 0))
    }

    @Test
    fun scrollPolicySnapshotAlignedAlwaysPins() {
        // 「缓存行数 == 快照行数」时 rowCount 不变，仍必须定位 —— 旧 rows.size 触发器的失效路径
        assertTrue(pin(ConversationScrollPolicy.Trigger.SnapshotAligned, alreadyPinned = true))
    }

    @Test
    fun scrollPolicyDeltaFollowsWhileNotScrolledAway() {
        // 意图锁未置位（用户想待在底部）→ 持续跟随
        assertTrue(pin(ConversationScrollPolicy.Trigger.Delta, alreadyPinned = true))
        // 尚未定位过 → 交给 Enter 处理，增量不抢跑（避免进会话瞬间重复滚动）
        assertFalse(pin(ConversationScrollPolicy.Trigger.Delta, alreadyPinned = false))
        // 关键回归：跟随判定**不得**再掺入「离底部多远」的几何条件 ——
        // 那会导致「点回到底部」的滚动动画与新增行竞态时视口卡在半路（真机实测，2026-10-07）
        assertTrue(pin(ConversationScrollPolicy.Trigger.Delta, alreadyPinned = true))
    }

    @Test
    fun scrollPolicyBlocksDuringPagination() {
        // 翻页 / 锚点恢复进行中：任何触发都不得强拉回底部
        for (t in ConversationScrollPolicy.Trigger.values()) {
            assertFalse("trigger=$t 在翻页中不应滚动", pin(t, paginationInFlight = true))
        }
    }

    @Test
    fun scrollPolicyRespectsUserScrollUp() {
        // 用户主动上翻查阅历史：所有触发都不打断（只有拖回底部或点「回到底部」才解除）
        for (t in ConversationScrollPolicy.Trigger.values()) {
            assertFalse("trigger=$t 在用户上翻时不应强拉", pin(t, alreadyPinned = true, userScrolledAway = true))
        }
    }
}
