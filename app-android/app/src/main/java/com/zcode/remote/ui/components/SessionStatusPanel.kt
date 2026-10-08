package com.zcode.remote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zcode.remote.relay.ConversationFrames
import com.zcode.remote.ui.theme.LocalZCodeDark
import com.zcode.remote.ui.theme.ZCodeDimens
import com.zcode.remote.ui.theme.ZCodeTokens
import com.zcode.remote.ui.theme.ZCodeType
import kotlinx.coroutines.delay

/**
 * 会话状态面板（底部弹层内容）—— 桌面端右侧状态面板的手机版。
 *
 * **纯只读**：只呈现快照里已有的会话级状态，不承载任何控制命令
 * （停止后台任务 / 编辑队列 / 暂停目标 / 分叉 / 压缩都不做）。
 * 数据全部来自 [com.zcode.remote.relay.ConversationChannel.sessionState]，
 * 该状态流的快照与增量两条路径在 C1 已接通。
 *
 * 分区与桌面端 `chat.statusPanel.*` 的对应关系：
 * - 上下文 → 桌面顶栏的上下文指示器（不在右面板，但它是最常看的一块，放最上面）
 * - 目标 → `goal`；进程 → `todo`（数据源 `snapshot.plan.items`）
 * - 终端 → `terminals`；智能体 → `agents`
 * - **不提供**「计划」与「环境/Git」两个分区，理由见下面对应 [SessionStatusSection] 的注释。
 */
@Composable
fun SessionStatusPanel(
    state: ConversationFrames.SessionState,
    modifier: Modifier = Modifier,
    onClose: () -> Unit,
) {
    val dark = LocalZCodeDark.current
    val borderColor = if (dark) ZCodeTokens.BorderSubtleDark else ZCodeTokens.BorderSubtleLight

    Column(modifier = modifier.fillMaxWidth()) {
        // 标题行
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = ZCodeDimens.PadInline, end = 4.dp),
        ) {
            Text(
                text = "会话状态",
                style = MaterialTheme.typography.titleSmall.copy(
                    fontSize = ZCodeType.Lg,
                    fontWeight = ZCodeType.SemiBold,
                ),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Close, contentDescription = "关闭", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // 分区清单（无数据的分区整个不出现）。
        //
        // 滚动只在这一层：`CollapsibleRow` 的展开体一律传 `bodyMaxHeight = null`，
        // 让所有分区共用这一个滚动宿主。否则每个分区自带一层 verticalScroll，
        // 同一方向嵌套滚动会触发 Compose 运行时告警，手指也分不清滚的是哪一层。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ZCodeDimens.PadInline)
                .padding(bottom = ZCodeDimens.PadWide),
            verticalArrangement = Arrangement.spacedBy(ZCodeDimens.GapTight),
        ) {
            val usage = state.usage
            if (usage != null) {
                ContextSection(usage)
                HorizontalDivider(color = borderColor)
            }

            val goal = state.goal
            if (goal != null) {
                GoalSection(goal)
                HorizontalDivider(color = borderColor)
            }

            val plan = state.plan
            if (plan != null) {
                PlanSection(plan)
                HorizontalDivider(color = borderColor)
            }

            val works = state.backgroundWorks
            if (works != null && works.bashRunningCount > 0) {
                TerminalsSection(works)
                HorizontalDivider(color = borderColor)
            }

            val subagents = state.subagents
            if (subagents != null && (subagents.runningCount > 0 || (subagents.endedTotal ?: 0L) > 0L)) {
                AgentsSection(subagents)
                HorizontalDivider(color = borderColor)
            }

            val queue = state.queue
            if (queue != null && queue.itemCount > 0) {
                QueueSection(queue)
                HorizontalDivider(color = borderColor)
            }

            // 兜底提示：理论上 hasContent 为真才会打开面板，走到这里说明快照里的块
            // 全是本轮不渲染的类型。宁可说清楚，也不要给一个空白面板。
            if (!hasAnyRenderedSection(state)) {
                Text(
                    text = "当前会话没有可展示的状态信息",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = ZCodeDimens.PadBlock),
                )
            }
        }
    }
}

/** 面板里真正会渲染出内容的条件，与 [SessionStatusPanel] 内部各分区判断保持一致。 */
private fun hasAnyRenderedSection(state: ConversationFrames.SessionState): Boolean =
    state.usage != null || state.goal != null || state.plan != null ||
        (state.backgroundWorks?.bashRunningCount ?: 0) > 0 ||
        state.subagents?.let { it.runningCount > 0 || (it.endedTotal ?: 0L) > 0L } == true ||
        (state.queue?.itemCount ?: 0) > 0

/**
 * 分区骨架：一个可折叠的标题行 + 展开体。
 *
 * 复用 [CollapsibleRow]（B3/B4/B6 已用它统一了行形态），保证状态面板的分区头
 * 与会话流里的折叠行是同一套观感；chevron 同样常显（手机没有 hover）。
 */
@Composable
private fun SessionStatusSection(
    title: String,
    icon: ImageVector,
    summary: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    body: @Composable () -> Unit,
) {
    CollapsibleRow(
        expanded = expanded,
        onToggle = onToggle,
        leading = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(ZCodeDimens.IconSm),
            )
        },
        label = {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = ZCodeType.Base),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        // 撑开中间，把 summary 与 chevron 推到行尾。`label` 槽位不是 RowScope（拿不到 weight），
        // 按 CollapsibleRow 的约定把 weight 放在 `primary`（RowScope）里。
        primary = { Spacer(Modifier.weight(1f)) },
        trailing = {
            if (summary != null) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = ZCodeType.Sm,
                        fontWeight = ZCodeType.Medium,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        },
        bodyMaxHeight = null,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZCodeDimens.GapTight)) {
            Spacer(Modifier.height(ZCodeDimens.GapTight))
            body()
        }
    }
}

// ---------------------------------------------------------------------------
// 上下文
// ---------------------------------------------------------------------------

@Composable
private fun ContextSection(usage: ConversationFrames.ContextUsage) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    SessionStatusSection(
        title = "上下文容量",
        icon = Icons.Default.Info,
        summary = null,
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        // 占比条 + 数字：数字放正文而不是标题右侧，是因为「上下文 12.3k / 200k」
        // 比「上下文容量」更有信息量，值得占一行；标题右侧留给内容更短的分区。
        val ratio = usage.ratio
        if (ratio != null) {
            LinearProgressIndicator(
                progress = { ratio.toFloat() },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = if (ratio >= 0.9) ZCodeTokens.StatusError else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            )
            Text(
                text = StatusPanelLabels.contextUsageLine(usage) ?: "",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = ZCodeType.Sm),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        StatusPanelLabels.cacheLine(usage)?.let { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = ZCodeType.Sm),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val slices = usage.breakdownPercentages()
        if (slices.isNotEmpty()) {
            Spacer(Modifier.height(ZCodeDimens.GapTight))
            Text(
                text = "上下文来源",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = ZCodeType.Sm,
                    fontWeight = ZCodeType.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            slices.forEach { slice ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ZCodeDimens.GapTight),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = StatusPanelLabels.contextSourceLabel(slice.source.source),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = ZCodeType.Sm),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = StatusPanelLabels.percent1(slice.ratio),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = ZCodeType.Sm),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 目标
// ---------------------------------------------------------------------------

@Composable
private fun GoalSection(goal: ConversationFrames.GoalState) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    // 只有进行中的目标才需要每秒刷新（否则白跑一个 1s 定时器）
    val now = rememberTickingNow(enabled = goal.isRunning)
    SessionStatusSection(
        title = "目标",
        icon = Icons.Default.Star,
        summary = StatusPanelLabels.goalStatusLabel(goal.status),
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        val text = goal.objective ?: goal.summaryTitle
        if (text != null) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = ZCodeType.Caption),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        val elapsed = StatusPanelLabels.goalElapsedLabel(goal, now)
        val budget = StatusPanelLabels.goalBudgetLine(goal)
        val meta = listOfNotNull(elapsed.ifEmpty { null }, budget).joinToString(" · ")
        if (meta.isNotEmpty()) {
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = ZCodeType.Sm),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 进程（待办）
// ---------------------------------------------------------------------------

@Composable
private fun PlanSection(plan: ConversationFrames.PlanState) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    SessionStatusSection(
        title = "进程",
        icon = Icons.Default.List,
        summary = StatusPanelLabels.planCounterLabel(plan),
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        // 面板要短：默认按桌面阈值（6 项）截断，未完成优先、已完成垫底。
        // 「展开全部」用 `limit = Int.MAX_VALUE` —— take 不会溢出，且复用同一套排序逻辑，
        // 不另写一条「全量」分支（两条分支迟早会走偏）。
        val display = plan.display(limit = if (showAll) Int.MAX_VALUE else ConversationFrames.PlanState.DISPLAY_LIMIT)
        display.visible.forEach { todo -> PlanTodoLine(todo) }

        StatusPanelLabels.planFoldedCompletedLabel(display.foldedCompleted)?.let { label ->
            FoldHint(text = label, onClick = { showAll = true })
        }
        StatusPanelLabels.planFoldedTailLabel(display.foldedTail)?.let { label ->
            FoldHint(text = label, onClick = { showAll = true })
        }
        if (showAll) {
            FoldHint(text = "收起", onClick = { showAll = false })
        }
    }
}

@Composable
private fun PlanTodoLine(todo: ConversationFrames.PlanTodo) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(ZCodeDimens.GapTight),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = if (todo.isCompleted) "●" else "○",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = ZCodeType.Sm),
            color = if (todo.isCompleted) ZCodeTokens.StatusOnline else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = todo.content,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = ZCodeType.Caption),
            color = if (todo.isCompleted) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f),
        )
    }
}

/** 折叠提示行（`已完成 4 项` / `后面 2 项` / `收起`），点击展开或收起。 */
@Composable
private fun FoldHint(text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = androidx.compose.ui.graphics.Color.Transparent,
        shape = RoundedCornerShape(ZCodeDimens.RadiusSm),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = ZCodeType.Sm),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(vertical = 2.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// 终端 / 智能体 / 排队
// ---------------------------------------------------------------------------

@Composable
private fun TerminalsSection(works: ConversationFrames.BackgroundWorksState) {
    // 默认折叠：终端是「想知道有没有、不常想知道具体是什么」的信息
    var expanded by rememberSaveable { mutableStateOf(false) }
    SessionStatusSection(
        title = "终端",
        icon = Icons.Default.PlayArrow,
        summary = StatusPanelLabels.terminalsLabel(works.bashRunningCount),
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        works.running.filter { it.kind == "bash" }.forEach { work ->
            // 快照里后台任务只有 workId，没有命令行文本，因此只能给出标识
            Text(
                text = work.workId ?: work.childSessionId ?: "后台命令",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = ZCodeType.Sm),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AgentsSection(subagents: ConversationFrames.SubagentsState) {
    // 默认折叠：子智能体动辄一串 id，平时只需要计数
    var expanded by rememberSaveable { mutableStateOf(false) }
    SessionStatusSection(
        title = "智能体",
        icon = Icons.Default.Person,
        summary = StatusPanelLabels.agentsLabel(subagents),
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        if (subagents.runningChildSessionIds.isEmpty()) {
            Text(
                text = "当前没有运行中的子智能体",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = ZCodeType.Sm),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            subagents.runningChildSessionIds.forEach { id ->
                Text(
                    text = id,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = ZCodeType.Sm),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun QueueSection(queue: ConversationFrames.QueueState) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    SessionStatusSection(
        title = "排队输入",
        icon = Icons.Default.Send,
        summary = "${queue.itemCount} 条",
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        Text(
            text = "待发送 ${queue.itemCount} 条" + StatusPanelLabels.queueAutoDrainSuffix(queue.autoDrain),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = ZCodeType.Sm),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------------------
// 每秒走时的时钟
// ---------------------------------------------------------------------------

/**
 * 按秒递增的「当前时刻」。仅在 [enabled] 为真时投递定时器 —— 目标结束后
 * 继续每秒重组一次面板是纯粹的浪费（面板自身没有别的随时间变化的内容）。
 */
@Composable
private fun rememberTickingNow(enabled: Boolean): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(enabled) {
        if (!enabled) {
            now = System.currentTimeMillis()
            return@LaunchedEffect
        }
        while (true) {
            now = System.currentTimeMillis()
            delay(1000L)
        }
    }
    return now
}

/**
 * 输入栏上方的待发送队列条（只读）。
 *
 * 桌面端在输入框附近提示排队的输入；这里只做**告知**，不提供「立即发送 / 取消排队」
 * ——那属于队列控制命令，超出本轮「纯只读面板」的边界。
 */
@Composable
fun QueuePendingStrip(itemCount: Int, modifier: Modifier = Modifier) {
    val label = StatusPanelLabels.queueLabel(itemCount) ?: return
    Surface(
        shape = RoundedCornerShape(ZCodeDimens.RadiusMd),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZCodeDimens.GapTight),
            modifier = Modifier.padding(horizontal = ZCodeDimens.PadBlock, vertical = 6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(ZCodeTokens.StatusPending, shape = RoundedCornerShape(3.dp)),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = ZCodeType.Sm,
                    fontWeight = FontWeight.Medium,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}