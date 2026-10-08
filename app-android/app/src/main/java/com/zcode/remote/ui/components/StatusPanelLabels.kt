package com.zcode.remote.ui.components

import com.zcode.remote.relay.ConversationFrames.ContextUsage
import com.zcode.remote.relay.ConversationFrames.GoalState
import com.zcode.remote.relay.ConversationFrames.PlanState
import com.zcode.remote.relay.ConversationFrames.SessionState
import com.zcode.remote.relay.ConversationFrames.SubagentsState
import com.zcode.remote.util.Format
import kotlin.math.roundToInt

/**
 * 会话状态面板的**文案与可见性判定**，全部是纯函数（无 Compose、无 Android 依赖），
 * 与 [ToolKindLabels] 同一条理由：面板的「什么时候显示哪个分区」「数字怎么写」
 * 决定了它长得对不对，这些必须能写成单测，不能只靠截图人眼验收。
 *
 * 文案对齐桌面端 `chat.statusPanel.*` / `chat.contextUsage.*` 的 zh-CN 词条，
 * 保证手机与桌面读到同一个词。
 */
object StatusPanelLabels {

    /**
     * 顶栏「状态」入口文案。**没有内容时返回 null**（入口整个不出现），
     * 与桌面端 `hasContent` 的隐藏条件一致。
     *
     * 已知上下文占比时把百分比挂在入口上（桌面端的上下文指示器也在顶栏）；
     * 占比未知就只显示「状态」——比显示一个假的 0% 诚实。
     */
    fun entryLabel(state: SessionState): String? {
        if (!state.hasContent) return null
        val ratio = state.usage?.ratio
        return if (ratio != null) "状态 ${Format.percent(ratio)}" else "状态"
    }

    /** 输入栏上方的待发送队列条文案（`queue.items` 非空时才出现）。 */
    fun queueLabel(count: Int): String? = if (count > 0) "待发送 $count 条" else null

    /** 队列当前是否会自动排空（`autoDrain`），未知时不加这句。 */
    fun queueAutoDrainSuffix(autoDrain: Boolean?): String =
        when (autoDrain) {
            true -> " · 自动发送"
            false -> " · 暂停发送"
            null -> ""
        }

    /**
     * 百分比，**保留一位小数**（桌面端 `maximumFractionDigits: 1` → `78.2%`）。
     *
     * 刻意不复用 [Format.percent]：那个取整到 `78%`，用在上下文总占比上很合适，
     * 但缓存命中率恰恰卡在 78% 这个阈值附近，取整后 `78%` 与 `79%` 看起来毫无差别，
     * 会把「刚好压线」和「明显有富余」抹平。
     */
    fun percent1(ratio: Double): String {
        val clamped = ratio.coerceIn(0.0, 1.0)
        val tenths = (clamped * 1000).roundToInt()
        return if (tenths % 10 == 0) "${tenths / 10}%" else "${tenths / 10}.${tenths % 10}%"
    }

    /** 上下文来源的中文标签。未知来源原样回显，不硬塞成「其他」。 */
    fun contextSourceLabel(source: String): String = when (source) {
        "messages" -> "消息"
        "system_prompt" -> "系统提示词"
        "meta_user_context" -> "其他"
        "skills" -> "技能"
        "tool_prompt" -> "工具提示词"
        "system_tool_schemas" -> "系统工具"
        "mcp_tool_schemas" -> "MCP 工具"
        else -> source
    }

    /** `上下文 12.3k / 200k`。两个数字缺任一个都返回 null（宁可少一行也不显示 `12.3k / ?`）。 */
    fun contextUsageLine(usage: ContextUsage): String? {
        val used = usage.usedTokens ?: return null
        val max = usage.maxTokens ?: return null
        return "上下文 ${Format.compactNumber(used)} / ${Format.compactNumber(max)}"
    }

    /**
     * 缓存命中率行。**低于桌面阈值（78%）返回 null** —— 整行不渲染，
     * 与桌面 `showBelowThreshold=false` 的行为一致：低命中率是噪声，不值得占位。
     */
    fun cacheLine(usage: ContextUsage): String? {
        val rate = usage.cacheHitRateIfNotable() ?: return null
        return "缓存命中 ${percent1(rate)}"
    }

    /** 目标状态的中文标签。未知状态原样回显（协议可能新增枚举，不要静默吞掉）。 */
    fun goalStatusLabel(status: String?): String? = when (status) {
        null -> null
        "active" -> "进行中"
        "verifying" -> "校验中"
        "notSatisfied" -> "未达标"
        else -> status
    }

    /**
     * 目标耗时：`已运行 3 分 20 秒`。
     *
     * 进行中的目标要让秒数真的在走（[GoalState.elapsedSeconds] 叠加本轮已跑时长），
     * 所以这里必须由调用方把当前时刻传进来，函数本身不读时钟 —— 否则单测只能靠等一秒。
     */
    fun goalElapsedLabel(goal: GoalState, nowMs: Long): String {
        val d = Format.duration(goal.elapsedSeconds(nowMs) * 1000L)
        return if (d.isEmpty()) "" else "已运行 $d"
    }

    /** 目标 token 预算行：`1.2 万 / 5 万` 这类紧凑写法；没有预算则 null。 */
    fun goalBudgetLine(goal: GoalState): String? {
        val budget = goal.tokenBudget ?: return null
        val used = goal.tokensUsed
        return if (used != null) {
            "${Format.compactNumber(used)} / ${Format.compactNumber(budget)} tokens"
        } else {
            "预算 ${Format.compactNumber(budget)} tokens"
        }
    }

    /** 待办分区的计数（`3/7`），全空返回 null。 */
    fun planCounterLabel(plan: PlanState): String? =
        if (plan.totalCount == 0) null else "${plan.completedCount}/${plan.totalCount}"

    /** 被折叠的已完成项文案（桌面 `todoCompletedFold`：`已完成 {count} 项`）。 */
    fun planFoldedCompletedLabel(count: Int): String? =
        if (count > 0) "已完成 $count 项" else null

    /** 被截掉的尾部未完成项文案（桌面 `todoLaterFold`：`后面 {count} 项`）。 */
    fun planFoldedTailLabel(count: Int): String? =
        if (count > 0) "后面 $count 项" else null

    /** 终端分区计数（桌面 `runningStatusValue`：`{count} 个后台运行`）。 */
    fun terminalsLabel(count: Int): String? = if (count > 0) "$count 个后台运行" else null

    /** 智能体分区计数：`2 运行 · 3 已结束`（桌面 `runningAgentsValue` + `endedAgents`）。 */
    fun agentsLabel(sub: SubagentsState): String {
        val ended = sub.endedTotal ?: 0L
        return if (ended > 0) "${sub.runningCount} 运行 · $ended 已结束" else "${sub.runningCount} 运行"
    }
}
