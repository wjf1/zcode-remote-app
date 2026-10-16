package com.zcode.remote.ui.components

import com.zcode.remote.R
import com.zcode.remote.relay.ConversationFrames
import com.zcode.remote.relay.ConversationFrames.ContextUsage
import com.zcode.remote.relay.ConversationFrames.GoalState
import com.zcode.remote.relay.ConversationFrames.PlanState
import com.zcode.remote.relay.ConversationFrames.PlanTodo
import com.zcode.remote.relay.ConversationFrames.SubagentsState
import com.zcode.remote.util.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 会话状态面板文案与可见性判定的单测（i18n 第 2 批）。
 *
 * 此前 `StatusPanelLabels` **无测试覆盖**（只有消费侧的真机截图验收）；本轮资源化时补上——
 * 文案改由 [UiText]（资源 id + 参数）承载后，能直接断言「哪个 key、带什么参数」，
 * 且**不需要 Android 运行时**（这是 [UiText] 这层间接的核心价值，见其 KDoc）。
 *
 * 覆盖两块：① 各函数的资源 id 与参数；② 隐藏条件（null 语义：无内容/低命中率/无预算）。
 */
class StatusPanelLabelsTest {
    private fun usage(
        used: Long? = null,
        max: Long? = null,
        cacheRate: Double? = null,
    ) = ContextUsage(usedTokens = used, maxTokens = max, cacheHitRate = cacheRate)

    private fun goal(
        status: String? = "active",
        budget: Long? = null,
        used: Long? = null,
    ) = GoalState(
        objective = null,
        summaryTitle = null,
        status = status,
        tokenBudget = budget,
        tokensUsed = used,
        timeUsedSeconds = null,
    )

    private fun todo(
        content: String,
        completed: Boolean,
    ) = PlanTodo(id = content, content = content, status = if (completed) "completed" else "pending")

    // ---------- 可见性与 null 语义 ----------

    @Test
    fun entryLabelHiddenWhenNoContent() {
        // 无任何内容 → 入口整个不出现
        assertNull(StatusPanelLabels.entryLabel(ConversationFrames.SessionState()))
    }

    @Test
    fun cacheLineHiddenBelowThreshold() {
        // 低命中率是噪声，整行不渲染（与桌面 showBelowThreshold=false 一致）
        assertNull(StatusPanelLabels.cacheLine(usage(cacheRate = 0.5)))
    }

    @Test
    fun contextUsageNeedsBothNumbers() {
        // 缺任一数字都返回 null（宁可少一行也不显示 `12.3k / ?`）
        assertNull(StatusPanelLabels.contextUsageLine(usage(used = 100)))
        assertNull(StatusPanelLabels.contextUsageLine(usage(max = 200)))
    }

    @Test
    fun goalBudgetNullWithoutBudget() {
        assertNull(StatusPanelLabels.goalBudgetLine(goal(budget = null)))
    }

    @Test
    fun planCounterNullWhenEmpty() {
        assertNull(StatusPanelLabels.planCounterLabel(PlanState(items = emptyList(), updatedAt = null)))
    }

    @Test
    fun foldedLabelsNullAtZero() {
        assertNull(StatusPanelLabels.planFoldedCompletedLabel(0))
        assertNull(StatusPanelLabels.planFoldedTailLabel(0))
        assertNull(StatusPanelLabels.terminalsLabel(0))
    }

    @Test
    fun goalStatusLabelNullForNullStatus() {
        assertNull(StatusPanelLabels.goalStatusLabel(null))
    }

    // ---------- 资源 id 与参数 ----------

    @Test
    fun entryLabelUsesRatioVariantWhenKnown() {
        val state =
            ConversationFrames.SessionState(
                usage = usage(used = 100, max = 1000),
            )
        assertEquals(
            UiText.Res(R.string.status_entry_with_ratio, listOf("10%")),
            StatusPanelLabels.entryLabel(state),
        )
    }

    @Test
    fun queueLabelsCarryCount() {
        assertEquals(UiText.Res(R.string.status_queue_pending, listOf(3)), StatusPanelLabels.queueLabel(3))
        assertNull(StatusPanelLabels.queueLabel(0))
    }

    /** 队列整句三态：自动 / 暂停 / 未知，各占一条完整句式资源（不再拼接片段）。 */
    @Test
    fun queueDrainLabelThreeStates() {
        assertEquals(
            UiText.Res(R.string.status_queue_pending_auto, listOf(2)),
            StatusPanelLabels.queueDrainLabel(2, autoDrain = true),
        )
        assertEquals(
            UiText.Res(R.string.status_queue_pending_paused, listOf(2)),
            StatusPanelLabels.queueDrainLabel(2, autoDrain = false),
        )
        assertEquals(
            UiText.Res(R.string.status_queue_pending, listOf(2)),
            StatusPanelLabels.queueDrainLabel(2, autoDrain = null),
        )
    }

    /** 未知枚举值（来源 / 目标状态）必须以 [UiText.Raw] 原样回显，不得静默吞掉或塞进「其他」。 */
    @Test
    fun unknownEnumsFallBackToRaw() {
        assertEquals(UiText.Raw("brand_new_source"), StatusPanelLabels.contextSourceLabel("brand_new_source"))
        assertEquals(UiText.Raw("brandNewStatus"), StatusPanelLabels.goalStatusLabel("brandNewStatus"))
    }

    @Test
    fun knownContextSourcesMapToRes() {
        assertEquals(
            UiText.Res(R.string.status_context_source_messages),
            StatusPanelLabels.contextSourceLabel("messages"),
        )
        assertEquals(
            UiText.Res(R.string.status_context_source_mcp_tool_schemas),
            StatusPanelLabels.contextSourceLabel("mcp_tool_schemas"),
        )
    }

    @Test
    fun agentsLabelTwoVariantsByEndedCount() {
        // 显式 listOf<Any>：避免 `listOf(2, 3L)` 被推断成 List<Long> 而把 2 提升为 2L，
        // 与生产代码里 runningCount(Int) + endedTotal(Long) 的实际实参类型不一致。
        assertEquals(
            UiText.Res(R.string.status_agents_running_ended, listOf<Any>(2, 3L)),
            StatusPanelLabels.agentsLabel(
                SubagentsState(runningChildSessionIds = listOf("a", "b"), endedTotal = 3L, revision = 1L),
            ),
        )
        assertEquals(
            UiText.Res(R.string.status_agents_running, listOf<Any>(2)),
            StatusPanelLabels.agentsLabel(
                SubagentsState(runningChildSessionIds = listOf("a", "b"), endedTotal = 0L, revision = 1L),
            ),
        )
    }

    /** 待办计数（`3/7`）与百分比是纯数字格式，不资源化、不参与翻译。 */
    @Test
    fun numericFormatsStayPlainStrings() {
        val plan =
            PlanState(
                items = listOf(todo("a", true), todo("b", false), todo("c", false)),
                updatedAt = null,
            )
        assertEquals("1/3", StatusPanelLabels.planCounterLabel(plan))
    }

    @Test
    fun percent1KeepsOneDecimal() {
        assertEquals("78.2%", StatusPanelLabels.percent1(0.782))
        assertEquals("78%", StatusPanelLabels.percent1(0.78))
        assertEquals("100%", StatusPanelLabels.percent1(1.0))
    }
}
