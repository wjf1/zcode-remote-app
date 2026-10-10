package com.zcode.remote.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话页待处理项的**跨会话隔离**回归网（2026-10-09 真机反馈「在其他会话也会弹审批」）。
 *
 * 待处理项有两路来源：会话流（只含当前订阅会话）与**任务事件流**（覆盖整个 workspace、
 * 不限当前订阅会话）。两路合并成全局列表后，会话页只能渲染属于当前会话的条目，
 * 否则在 B 会话页面上会弹出 A 会话的审批/提问。
 *
 * 关键边界是 `sessionId == null`：解析时拿不到归属时**按可见处理**（fail-open），
 * 宁可多显示一条，也不能把当前会话的卡隐藏掉 —— 这条如果写成 false，会把
 * 「会话流快照没带 sessionId」的正常条目一并藏掉（比原缺陷更难排查）。
 */
class PendingScopeTest {

    private fun approval(id: String, sessionId: String?) = PendingApproval(
        interactionId = id,
        toolCallId = null,
        toolName = null,
        summary = null,
        detail = null,
        options = emptyList(),
        anchorRowId = null,
        autoResolveAt = null,
        sessionId = sessionId,
    )

    private fun elicitation(id: String, sessionId: String?) = PendingElicitation(
        interactionId = id,
        toolName = null,
        prompt = null,
        questions = emptyList(),
        freeText = false,
        plan = null,
        autoResolveAt = null,
        sessionId = sessionId,
    )

    @Test
    fun approvalsKeepOwnSessionAndDropOthers() {
        val list = listOf(
            approval("a-own", "sessA"),
            approval("a-other", "sessB"),
            approval("a-anon", null),
        )
        val visible = approvalsForSession(list, "sessA")
        assertEquals("只应看到本会话与归属未知的条目", listOf("a-own", "a-anon"), visible.map { it.interactionId })
    }

    @Test
    fun approvalsKeepUnknownSessionIdVisible() {
        // fail-open：归属未知 ≠ 属于别的会话。写死成 false 会隐藏当前会话的正常卡。
        val visible = approvalsForSession(listOf(approval("a-anon", null)), "sessA")
        assertEquals(1, visible.size)
    }

    @Test
    fun approvalsAreEmptyWhenNoSessionSelected() {
        // 未选中会话（target 为空）时不应放行带归属的条目；归属未知的仍可见（调用点此时也不渲染会话页）
        val visible = approvalsForSession(listOf(approval("a", "sessA"), approval("anon", null)), null)
        assertEquals(listOf("anon"), visible.map { it.interactionId })
    }

    @Test
    fun elicitationsKeepOwnSessionAndDropOthers() {
        val list = listOf(
            elicitation("e-own", "sessA"),
            elicitation("e-other", "sessB"),
            elicitation("e-anon", null),
        )
        val visible = elicitationsForSession(list, "sessA")
        assertEquals(listOf("e-own", "e-anon"), visible.map { it.interactionId })
    }

    @Test
    fun emptyInputStaysEmpty() {
        assertTrue(approvalsForSession(emptyList(), "sessA").isEmpty())
        assertTrue(elicitationsForSession(emptyList(), "sessA").isEmpty())
    }
}
