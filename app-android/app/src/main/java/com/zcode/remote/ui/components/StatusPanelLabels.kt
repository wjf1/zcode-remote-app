package com.zcode.remote.ui.components

import com.zcode.remote.R
import com.zcode.remote.relay.ConversationFrames.ContextUsage
import com.zcode.remote.relay.ConversationFrames.GoalState
import com.zcode.remote.relay.ConversationFrames.PlanState
import com.zcode.remote.relay.ConversationFrames.SessionState
import com.zcode.remote.relay.ConversationFrames.SubagentsState
import com.zcode.remote.util.Format
import com.zcode.remote.util.UiText
import com.zcode.remote.util.UiText.Companion.res
import kotlin.math.roundToInt

/**
 * 会话状态面板的**文案与可见性判定**，全部是纯函数（无 Compose、无 Android 依赖），
 * 与 [ToolKindLabels] 同一条理由：面板的「什么时候显示哪个分区」「数字怎么写」
 * 决定了它长得对不对，这些必须能写成单测，不能只靠截图人眼验收。
 *
 * 文案对齐桌面端 `chat.statusPanel.*` / `chat.contextUsage.*` 的 zh-CN 词条，
 * 保证手机与桌面读到同一个词。
 *
 * 国际化（第 2 批）：自然语言文案改由 [UiText] 承载（资源 id + 参数），消费点
 * （`SessionStatusPanel` 等 Composable）经 `asString()` 解析。**纯数字格式**
 * （[percent1] 的 `78.2%`、[planCounterLabel] 的 `3/7`）不是可翻译文案，保持 `String`。
 * 未知枚举值（来源 / 目标状态）走 [UiText.Raw] 原样回显，不硬塞成「其他」。
 */
object StatusPanelLabels {
    /**
     * 顶栏「状态」入口文案。**没有内容时返回 null**（入口整个不出现），
     * 与桌面端 `hasContent` 的隐藏条件一致。
     *
     * 已知上下文占比时把百分比挂在入口上（桌面端的上下文指示器也在顶栏）；
     * 占比未知就只显示「状态」——比显示一个假的 0% 诚实。
     */
    fun entryLabel(state: SessionState): UiText? {
        if (!state.hasContent) return null
        val ratio = state.usage?.ratio
        return if (ratio != null) res(R.string.status_entry_with_ratio, Format.percent(ratio)) else res(R.string.status_entry)
    }

    /** 输入栏上方的待发送队列条文案（`queue.items` 非空时才出现）。 */
    fun queueLabel(count: Int): UiText? = if (count > 0) res(R.string.status_queue_pending, count) else null

    /**
     * 面板内「排队输入」分区的整句文案（含是否自动排空的说明）。
     *
     * 国际化：原先由「`待发送 N 条` + 后缀片段」拼接，片段无法被翻译成通顺的其它语言，
     * 故改为三种完整句式各占一条资源。`autoDrain` 未知（null）时不加说明。
     */
    fun queueDrainLabel(
        count: Int,
        autoDrain: Boolean?,
    ): UiText =
        when (autoDrain) {
            true -> res(R.string.status_queue_pending_auto, count)
            false -> res(R.string.status_queue_pending_paused, count)
            null -> res(R.string.status_queue_pending, count)
        }

    /**
     * 百分比，**保留一位小数**（桌面端 `maximumFractionDigits: 1` → `78.2%`）。
     *
     * 刻意不复用 [Format.percent]：那个取整到 `78%`，用在上下文总占比上很合适，
     * 但缓存命中率恰恰卡在 78% 这个阈值附近，取整后 `78%` 与 `79%` 看起来毫无差别，
     * 会把「刚好压线」和「明显有富余」抹平。
     *
     * 返回纯数字格式（无自然语言），故不资源化。
     */
    fun percent1(ratio: Double): String {
        val clamped = ratio.coerceIn(0.0, 1.0)
        val tenths = (clamped * 1000).roundToInt()
        return if (tenths % 10 == 0) "${tenths / 10}%" else "${tenths / 10}.${tenths % 10}%"
    }

    /** 上下文来源的中文标签。未知来源原样回显（[UiText.Raw]），不硬塞成「其他」。 */
    fun contextSourceLabel(source: String): UiText =
        when (source) {
            "messages" -> res(R.string.status_context_source_messages)
            "system_prompt" -> res(R.string.status_context_source_system_prompt)
            "meta_user_context" -> res(R.string.status_context_source_other)
            "skills" -> res(R.string.status_context_source_skills)
            "tool_prompt" -> res(R.string.status_context_source_tool_prompt)
            "system_tool_schemas" -> res(R.string.status_context_source_system_tool_schemas)
            "mcp_tool_schemas" -> res(R.string.status_context_source_mcp_tool_schemas)
            else -> UiText.Raw(source)
        }

    /** `上下文 12.3k / 200k`。两个数字缺任一个都返回 null（宁可少一行也不显示 `12.3k / ?`）。 */
    fun contextUsageLine(usage: ContextUsage): UiText? {
        val used = usage.usedTokens ?: return null
        val max = usage.maxTokens ?: return null
        return res(R.string.status_context_usage, Format.compactNumber(used), Format.compactNumber(max))
    }

    /**
     * 缓存命中率行。**低于桌面阈值（78%）返回 null** —— 整行不渲染，
     * 与桌面 `showBelowThreshold=false` 的行为一致：低命中率是噪声，不值得占位。
     */
    fun cacheLine(usage: ContextUsage): UiText? {
        val rate = usage.cacheHitRateIfNotable() ?: return null
        return res(R.string.status_cache_hit, percent1(rate))
    }

    /** 目标状态的中文标签。未知状态原样回显（协议可能新增枚举，不要静默吞掉）。 */
    fun goalStatusLabel(status: String?): UiText? =
        when (status) {
            null -> null
            "active" -> res(R.string.status_goal_active)
            "verifying" -> res(R.string.status_goal_verifying)
            "notSatisfied" -> res(R.string.status_goal_not_satisfied)
            else -> UiText.Raw(status)
        }

    /**
     * 目标耗时：`已运行 3 分 20 秒`。无耗时时返回 null（消费点据 null 省略该段）。
     *
     * 进行中的目标要让秒数真的在走（[GoalState.elapsedSeconds] 叠加本轮已跑时长），
     * 所以这里必须由调用方把当前时刻传进来，函数本身不读时钟 —— 否则单测只能靠等一秒。
     */
    fun goalElapsedLabel(
        goal: GoalState,
        nowMs: Long,
    ): UiText? {
        val d = Format.duration(goal.elapsedSeconds(nowMs) * 1000L)
        return if (d.isEmpty()) null else res(R.string.status_goal_elapsed, d)
    }

    /** 目标 token 预算行：`1.2 万 / 5 万` 这类紧凑写法；没有预算则 null。 */
    fun goalBudgetLine(goal: GoalState): UiText? {
        val budget = goal.tokenBudget ?: return null
        val used = goal.tokensUsed
        return if (used != null) {
            res(R.string.status_goal_budget_used, Format.compactNumber(used), Format.compactNumber(budget))
        } else {
            res(R.string.status_goal_budget_only, Format.compactNumber(budget))
        }
    }

    /** 待办分区的计数（`3/7`），全空返回 null。纯数字格式，不资源化。 */
    fun planCounterLabel(plan: PlanState): String? = if (plan.totalCount == 0) null else "${plan.completedCount}/${plan.totalCount}"

    /** 被折叠的已完成项文案（桌面 `todoCompletedFold`：`已完成 {count} 项`）。 */
    fun planFoldedCompletedLabel(count: Int): UiText? = if (count > 0) res(R.string.status_plan_folded_completed, count) else null

    /** 被截掉的尾部未完成项文案（桌面 `todoLaterFold`：`后面 {count} 项`）。 */
    fun planFoldedTailLabel(count: Int): UiText? = if (count > 0) res(R.string.status_plan_folded_tail, count) else null

    /** 终端分区计数（桌面 `runningStatusValue`：`{count} 个后台运行`）。 */
    fun terminalsLabel(count: Int): UiText? = if (count > 0) res(R.string.status_terminals, count) else null

    /** 智能体分区计数：`2 运行 · 3 已结束`（桌面 `runningAgentsValue` + `endedAgents`）。 */
    fun agentsLabel(sub: SubagentsState): UiText {
        val ended = sub.endedTotal ?: 0L
        return if (ended > 0) {
            res(R.string.status_agents_running_ended, sub.runningCount, ended)
        } else {
            res(R.string.status_agents_running, sub.runningCount)
        }
    }
}
