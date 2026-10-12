package com.zcode.remote.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zcode.remote.relay.ConversationRow
import com.zcode.remote.repo.ConversationRepository
import com.zcode.remote.ui.components.CollapsibleRow
import com.zcode.remote.ui.components.MarkdownView
import com.zcode.remote.ui.components.ShimmerText
import com.zcode.remote.ui.components.ToolBody
import com.zcode.remote.ui.components.ToolBodySection
import com.zcode.remote.ui.components.ToolFamily
import com.zcode.remote.ui.components.ToolKindLabels
import com.zcode.remote.ui.components.ToolStatus
import com.zcode.remote.ui.components.ToolSummaryBadge
import com.zcode.remote.ui.components.toolStatusTone
import com.zcode.remote.ui.theme.LocalZCodeDark
import com.zcode.remote.ui.theme.ZCodeDimens
import com.zcode.remote.ui.theme.ZCodeTokens
import com.zcode.remote.ui.theme.ZCodeType
import com.zcode.remote.util.Format
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/*
 * 会话时间线的行渲染器群（§11.7 结构性拆分：自 `ConversationScreen.kt` 原样搬出，
 * 只移动、不改行为）。桌面端也是一套渲染器按行类型分流，这里保持同名同序。
 */

/**
 * 行分发。
 *
 * [expanded] / [onToggleExpand] 由屏幕层传入而非行内 `remember`，原因见 `expandedRows` 处的说明
 * （LazyColumn 回收滚出视野的行，行内状态会丢）。只有三类可折叠行会用到，其余行忽略。
 */
@Composable
internal fun RowItem(
    row: ConversationRow,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
) = when (row.kind) {
    "userInput" -> UserBubble(row)
    "assistantText" -> AssistantText(row)
    "reasoning" -> ReasoningBlock(row, expanded, onToggleExpand)
    "toolCall" -> ToolCallRow(row, expanded, onToggleExpand)
    "turnHeader" -> TurnHeaderRow(row)
    "subagent" -> SubagentRow(row)
    "timelineMarker" -> TimelineMarkerRow(row)
    "hookInvocation" -> HookInvocationRow(row, expanded, onToggleExpand)
    else -> PlainRow(row)
}

/** 折叠状态在屏幕层的键：工具行用 `toolCallId`（跨重放稳定），其余行退化为 `kind:rowId`。 */
internal fun rowExpandKey(row: ConversationRow): String = row.toolCallId?.takeIf { it.isNotBlank() } ?: "${row.kind}:${row.rowId}"

/**
 * 用户消息：附件 chip（若有）在气泡上方，整体右对齐。
 *
 * 附件来自服务端在 userInput 行的回显（`{ref, fileName, mime, bytes}`），
 * 即「手机上选文件→发送」之后该消息在会话流里的呈现方式与官方客户端一致。
 * 历史消息仅展示：`ref` 是 host 侧暂存引用而非工作区路径，故不做点击预览（见 RowAttachment）。
 *
 * 气泡形态对齐桌面端：**中性半透明叠加底 + 1px 细边框**，右上角收成 2dp 小圆角指向发送者，
 * 宽度上限取屏宽的 84%（桌面端是气泡最大宽度按容器比例收缩，手机同理）。
 * 此前用的蓝底 primary 是 Material 默认聊天样式，与桌面端的中性叠加层差异很大。
 */
@Composable
private fun UserBubble(row: ConversationRow) {
    val dark = LocalZCodeDark.current
    val bubbleBg = if (dark) ZCodeTokens.OverlaySurfaceHoverDark else ZCodeTokens.OverlaySurfaceHoverLight
    val bubbleBorder = if (dark) ZCodeTokens.BorderSubtleDark else ZCodeTokens.BorderSubtleLight
    val maxWidth = (LocalConfiguration.current.screenWidthDp * ZCodeDimens.BUBBLE_WIDTH_FRACTION).dp

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
    ) {
        row.attachments.forEach { a ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.padding(bottom = 4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Icon(
                        // C-3：与输入栏附件 chip 同一图标（material-icons-extended 的 AttachFile）
                        imageVector = Icons.Default.AttachFile,
                        contentDescription = "附件",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = a.fileName ?: "附件",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 220.dp),
                    )
                    a.bytes?.takeIf { it > 0 }?.let { size ->
                        Text(
                            text = humanBytes(size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Surface(
            modifier = Modifier.widthIn(max = maxWidth),
            shape =
                RoundedCornerShape(
                    topStart = ZCodeDimens.RadiusXl,
                    topEnd = ZCodeDimens.RadiusTail,
                    bottomStart = ZCodeDimens.RadiusXl,
                    bottomEnd = ZCodeDimens.RadiusXl,
                ),
            color = bubbleBg,
            border = BorderStroke(1.dp, bubbleBorder),
        ) {
            Text(
                text = row.text.orEmpty(),
                style =
                    MaterialTheme.typography.bodyMedium.copy(
                        fontSize = ZCodeType.Base,
                        lineHeight = ZCodeType.BodyLineHeight,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * 本地 pending 回显气泡（C-6）：消息发出瞬间即在流末尾显示，半透明 + 「发送中…」表示
 * 尚未被服务端回显确认；服务端 userInput 行到达后由 VM 移除本气泡（见
 * AppViewModel.pendingUserMessages）。形态与 [UserBubble] 一致，仅降透明度并加状态行。
 */
@Composable
internal fun PendingUserBubble(p: ConversationRepository.PendingUserMessage) {
    val dark = LocalZCodeDark.current
    val bubbleBg = if (dark) ZCodeTokens.OverlaySurfaceHoverDark else ZCodeTokens.OverlaySurfaceHoverLight
    val bubbleBorder = if (dark) ZCodeTokens.BorderSubtleDark else ZCodeTokens.BorderSubtleLight
    val maxWidth = (LocalConfiguration.current.screenWidthDp * ZCodeDimens.BUBBLE_WIDTH_FRACTION).dp

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
    ) {
        p.attachmentNames.forEach { name ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.padding(bottom = 4.dp),
            ) {
                Text(
                    text = "附件 · $name",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        Surface(
            modifier = Modifier.widthIn(max = maxWidth),
            shape =
                RoundedCornerShape(
                    topStart = ZCodeDimens.RadiusXl,
                    topEnd = ZCodeDimens.RadiusTail,
                    bottomStart = ZCodeDimens.RadiusXl,
                    bottomEnd = ZCodeDimens.RadiusXl,
                ),
            color = bubbleBg.copy(alpha = 0.6f),
            border = BorderStroke(1.dp, bubbleBorder),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    text = p.text,
                    style =
                        MaterialTheme.typography.bodyMedium.copy(
                            fontSize = ZCodeType.Base,
                            lineHeight = ZCodeType.BodyLineHeight,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                        ),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "发送中…",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = ZCodeType.Sm),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 助手正文。桌面端**没有卡片外壳**：正文直接铺满整列，靠段间距与上一行区分。
 * 此前套了一层 `Surface`（圆角 + 描边），把大段 Markdown 关进卡片里，与桌面端差距最明显的一处。
 */
@Composable
private fun AssistantText(row: ConversationRow) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                // 与上一行的呼吸感：桌面端是 gap-5（20px），这里补在行内，
                // LazyColumn 的 spacedBy(8dp) 只负责行间基础间距。
                .padding(top = ZCodeDimens.GapTurn),
    ) {
        MarkdownView(
            markdown = row.text.orEmpty(),
            modifier = Modifier.fillMaxWidth(),
            textColor = MaterialTheme.colorScheme.onSurface,
        )
        if (row.isStreaming) {
            Spacer(Modifier.height(ZCodeDimens.GapInline))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier =
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                )
                Spacer(Modifier.width(6.dp))
                ShimmerText(
                    text = "正在生成…",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = ZCodeType.Sm),
                )
            }
        }
    }
}

/**
 * 思考过程。对齐桌面端为**无外框的内联折叠行**：紫色圆点 + 「思考 · 持续了 N 秒」+ 折叠箭头，
 * 展开后是左侧一条中性竖线 + 缩进正文，高度封顶后内部滚动。
 *
 * 此前是一个带背景色的圆角胶囊 + 「展开/收起」文字按钮，属于自造样式（桌面上并不存在）。
 * 复制按钮保留（桌面端是 hover 才出现的图标按钮，手机没有 hover，收进行尾以免丢失功能）。
 */
@Composable
private fun ReasoningBlock(
    row: ConversationRow,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
) {
    val content = row.text.orEmpty().trim()
    if (content.isEmpty()) return
    val dark = LocalZCodeDark.current
    val tone = if (dark) ZCodeTokens.ReasoningTrajectoryDark else ZCodeTokens.ReasoningTrajectoryLight
    val context = LocalContext.current
    val worked = Format.duration(row.durationMs ?: row.activeMs)

    CollapsibleRow(
        expanded = expanded,
        onToggle = onToggleExpand,
        leading = {
            Box(
                modifier =
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(tone),
            )
        },
        label = {
            Text(
                text = if (worked.isEmpty()) "思考" else "思考 · 持续了 $worked",
                style =
                    MaterialTheme.typography.labelSmall.copy(
                        fontSize = ZCodeType.Sm,
                        fontWeight = ZCodeType.Medium,
                    ),
                color = tone,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        primary = {
            if (row.isStreaming) {
                ShimmerText(
                    text = "正在思考",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = ZCodeType.Sm),
                    baseColor = tone.copy(alpha = 0.6f),
                    highlightColor = tone,
                )
            } else if (!expanded) {
                // C-4：折叠态给出内容摘要（原折叠态零摘要，只有「思考 · 持续了 N 秒」，
                // 用户无法判断这段思考值不值得展开）。单行截断，与工具行折叠态同款。
                val summary = Format.singleLine(content, limit = 120)
                if (!summary.isNullOrBlank()) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = ZCodeType.Caption),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
        },
        trailing = {
            TextButton(
                onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("reasoning", content))
                    Toast.makeText(context, "思考过程已复制", Toast.LENGTH_SHORT).show()
                },
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                modifier = Modifier.height(24.dp),
            ) {
                Text("复制", style = MaterialTheme.typography.labelSmall.copy(fontSize = ZCodeType.Sm))
            }
            Text(
                text = "${content.length} 字符",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = ZCodeType.Sm),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        chevronColor = tone,
    ) {
        ToolBody {
            MarkdownView(
                markdown = content,
                modifier = Modifier.fillMaxWidth(),
                textColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 工具调用行。对齐桌面端为**无外框的内联折叠行**：家族图标 + 中文类型标签 + 主文案 +
 * 尾部增删徽标 + 状态字。
 *
 * 此前是带描边的圆角卡片 + 琥珀色工具名徽章 —— 卡片外壳在桌面上并不存在，
 * 且每个工具一次卡片会让长会话变成「一摞卡片」，视觉噪声远高于桌面端。
 */
@Composable
private fun ToolCallRow(
    row: ConversationRow,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
) {
    val input = row.inputText?.trim().orEmpty()
    val output = row.outputText?.trim().orEmpty()
    val errorText = row.errorText?.trim().orEmpty()
    val tool = row.toolName?.trim().orEmpty()
    if (tool.isEmpty() && input.isEmpty() && output.isEmpty()) return

    val family = ToolKindLabels.familyFor(row.toolName, row.kind)
    val status = ToolKindLabels.normalizeStatus(row.status)
    val label =
        if (status == ToolStatus.Running) {
            ToolKindLabels.runningLabel(family, row.toolName)
        } else {
            ToolKindLabels.label(family, row.toolName)
        }
    val statusText = ToolKindLabels.statusLabel(status)
    val tone = toolStatusTone(status)
    val primary =
        if (family == ToolFamily.FileWrite) {
            ToolKindLabels.filePathBasename(input)
                ?: ToolKindLabels.primaryText(row.toolName, row.kind, null, input)
        } else {
            ToolKindLabels.primaryText(row.toolName, row.kind, null, input)
        }
    // Sprint 3：写类工具（Edit/Write/MultiEdit）从 inputText 解析红绿 diff —— 纯客户端，零 RPC
    val toolDiff =
        remember(row.rowId, input) {
            runCatching {
                com.zcode.remote.ui.components.ToolDiffParser
                    .parse(row.toolName, input)
            }.getOrNull()
        }

    CollapsibleRow(
        expanded = expanded,
        onToggle = onToggleExpand,
        leading = { ToolFamilyIcon(family = family, tint = tone) },
        label = {
            Text(
                text = label,
                style =
                    MaterialTheme.typography.labelSmall.copy(
                        fontSize = ZCodeType.Sm,
                        fontWeight = ZCodeType.SemiBold,
                    ),
                color = tone,
                maxLines = 1,
            )
        },
        primary = {
            val text = primary
            if (!expanded && !text.isNullOrBlank()) {
                Text(
                    text = text,
                    style =
                        MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.5.sp,
                        ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
        },
        trailing = {
            if (!expanded && toolDiff != null) {
                if (toolDiff.added > 0) ToolSummaryBadge("+${toolDiff.added}", ZCodeTokens.StatusOnline)
                if (toolDiff.removed > 0) ToolSummaryBadge("−${toolDiff.removed}", ZCodeTokens.StatusError)
            }
            statusText?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = ZCodeType.Sm),
                    color = tone,
                    maxLines = 1,
                )
            }
        },
        chevronColor = tone,
    ) {
        ToolBody {
            toolDiff?.let {
                com.zcode.remote.ui.components
                    .DiffBlock(it)
            }
            ToolBodySection(title = "Parameters", content = prettyJson(input))
            if (errorText.isNotEmpty()) {
                ToolBodySection(
                    title = "Error",
                    content = errorText,
                    titleColor = ZCodeTokens.StatusError,
                    background = ZCodeTokens.StatusError.copy(alpha = 0.10f),
                    mono = false,
                )
            }
            ToolBodySection(title = "Result", content = output)
        }
    }
}

/**
 * 工具家族图标。桌面端用 lucide 图标集，App 只有 `material-icons-core` 的内置图标
 * （全量清单：AccountBox … Warning 共 45 个），形态无法与 lucide 一一对应，
 * 故按语义就近映射；家族主色一致，形状差异不会造成误读。
 */
@Composable
private fun ToolFamilyIcon(
    family: ToolFamily,
    tint: Color,
) {
    val icon =
        when (family) {
            ToolFamily.FileRead -> Icons.Default.Info
            ToolFamily.FileWrite -> Icons.Default.Edit
            ToolFamily.Shell -> Icons.Default.PlayArrow
            ToolFamily.Search -> Icons.Default.Search
            ToolFamily.Todo -> Icons.Default.List
            ToolFamily.Goal -> Icons.Default.Star
            ToolFamily.Explore -> Icons.Default.Place
            ToolFamily.SessionContext -> Icons.Default.Refresh
            ToolFamily.AskUserQuestion -> Icons.Default.Face
            ToolFamily.Message -> Icons.Default.Email
            ToolFamily.TaskControl -> Icons.Default.Settings
            ToolFamily.NodeRepl -> Icons.Default.Build
            ToolFamily.Agent -> Icons.Default.Person
            ToolFamily.Skill -> Icons.Default.Favorite
            ToolFamily.Unknown -> Icons.Default.MoreVert
        }
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(ZCodeDimens.IconSm),
    )
}

private val prettyJsonPrinter =
    Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

/**
 * 工具入参缩进美化。服务端下发的 input 是紧凑 JSON，展开后不缩进基本没法读。
 * 解析失败（例如某些工具直接下发非 JSON 文本）时原样返回 —— 绝不因为「美化失败」把内容吞掉。
 */
private fun prettyJson(raw: String): String {
    if (raw.isBlank()) return raw
    return runCatching {
        prettyJsonPrinter.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(raw))
    }.getOrNull() ?: raw
}

/**
 * 轮次头。桌面端是一行极淡的分隔线 + 「轮次 · 来源 · 状态」，右侧或次行给出耗时。
 *
 * 这里不使用 wall-clock 时间戳：会话行的 wire 协议只下发 `startedAt` / `endedAt` /
 * `activeMs` 三个相对量，没有「行创建时刻」的绝对时间；早期版本用 `SimpleDateFormat`
 * 格式化 `createdAt`，显示的其实是首帧到达时刻，与桌面端语义不符，已移除。
 */
@Composable
private fun TurnHeaderRow(row: ConversationRow) {
    val dark = LocalZCodeDark.current
    // 优先用服务端累计的活跃时长；缺失时用起止时间兜底（含等待用户输入的空档）
    val durationMs =
        row.activeMs
            ?: if (row.startedAt != null && row.endedAt != null) row.endedAt - row.startedAt else null
    val meta =
        listOfNotNull(
            "轮次",
            row.origin?.trim()?.takeIf { it.isNotEmpty() },
            row.state?.let { phaseLabel(it) },
        ).joinToString(" · ")

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = ZCodeDimens.GapTurn, bottom = ZCodeDimens.GapInline),
    ) {
        HorizontalDivider(
            color = if (dark) ZCodeTokens.BorderSubtleDark else ZCodeTokens.BorderSubtleLight,
        )
        Spacer(Modifier.height(ZCodeDimens.GapInline))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = ZCodeType.Sm),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Format.workedDuration(durationMs).takeIf { it.isNotEmpty() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = ZCodeType.Sm),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 子智能体行。桌面端压缩成单行：`{类型} · {状态} — {摘要}`，
 * 正文本身由子智能体的独立会话承载，聚合视图不重复渲染。
 */
@Composable
private fun SubagentRow(row: ConversationRow) {
    val tone = if (LocalZCodeDark.current) ZCodeTokens.AssistantTrajectoryDark else ZCodeTokens.AssistantTrajectoryLight
    val type = row.origin?.trim()?.takeIf { it.isNotEmpty() } ?: "子智能体"
    val status = ToolKindLabels.statusLabel(row.status)
    val summary = Format.singleLine(row.summaryText ?: row.text, limit = 160)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Person,
            contentDescription = null,
            tint = tone,
            modifier = Modifier.size(ZCodeDimens.IconSm),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = type,
            style =
                MaterialTheme.typography.labelSmall.copy(
                    fontSize = ZCodeType.Sm,
                    fontWeight = ZCodeType.SemiBold,
                ),
            color = tone,
            maxLines = 1,
        )
        status?.let {
            Text(
                text = " · $it",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = ZCodeType.Sm),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        summary?.takeIf { it.isNotEmpty() }?.let {
            Text(
                text = " — $it",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = ZCodeType.Caption),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 时间线标记（会话被压缩 / 分叉 / 恢复之类的分隔点）：居中标签 + 两侧 1px 细线。 */
@Composable
private fun TimelineMarkerRow(row: ConversationRow) {
    val dark = LocalZCodeDark.current
    val line = if (dark) ZCodeTokens.BorderSubtleDark else ZCodeTokens.BorderSubtleLight
    val label =
        Format
            .singleLine(row.summaryText ?: row.text, limit = 60)
            ?.takeIf { it.isNotEmpty() }
            ?: return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = ZCodeDimens.PadBlock),
    ) {
        HorizontalDivider(Modifier.weight(1f), color = line)
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = ZCodeType.Sm),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = ZCodeDimens.GapInline),
        )
        HorizontalDivider(Modifier.weight(1f), color = line)
    }
}

/** 钩子调用行（PreToolUse / PostToolUse 等）：形态与工具行一致，走同一条折叠行。 */
@Composable
private fun HookInvocationRow(
    row: ConversationRow,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
) {
    val hook = row.toolName?.trim().takeIf { !it.isNullOrEmpty() } ?: "钩子"
    val detail =
        row.outputText
            ?.trim()
            .orEmpty()
            .ifEmpty { row.inputText?.trim().orEmpty() }
    val failed =
        row.status?.lowercase()?.contains("error") == true ||
            row.status?.lowercase()?.contains("fail") == true
    val tone = if (failed) ZCodeTokens.StatusError else ZCodeTokens.StatusOffline

    CollapsibleRow(
        expanded = expanded,
        onToggle = onToggleExpand,
        leading = { Icon(Icons.Default.Build, contentDescription = null, tint = tone, modifier = Modifier.size(ZCodeDimens.IconSm)) },
        label = {
            Text(
                text = hook,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = ZCodeType.Sm, fontWeight = ZCodeType.SemiBold),
                color = tone,
                maxLines = 1,
            )
        },
        primary = {
            if (!expanded) {
                Text(
                    text = Format.singleLine(detail, limit = 120).orEmpty(),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = ZCodeType.Caption),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
        },
        chevronColor = tone,
    ) {
        ToolBody {
            ToolBodySection(title = "Input", content = prettyJson(row.inputText?.trim().orEmpty()))
            ToolBodySection(title = "Output", content = row.outputText?.trim().orEmpty())
        }
    }
}

@Composable
private fun PlainRow(row: ConversationRow) {
    Text(
        text = "${row.kind} #${row.rowId}",
        style = MaterialTheme.typography.labelSmall.copy(fontSize = ZCodeType.Sm),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    )
    row.text?.takeIf { it.isNotBlank() }?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = ZCodeType.Caption),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
        )
    }
}

/**
 * 消息流列表容器（§11.7 结构性拆分：自 `ConversationScreen.kt` 原样搬出，只移动不改行为）。
 * 外层 Box 承载悬浮「回到底部」提示胶囊；列表状态、滚动意图锁与跳转执行仍在 Route 层
 * （会话级副作用），经 [onJumpToBottom] 回调触发。
 */
@Composable
internal fun ConversationTimelineArea(
    modifier: Modifier = Modifier,
    listState: androidx.compose.foundation.lazy.LazyListState,
    rows: List<ConversationRow>,
    pendingUserMessages: List<ConversationRepository.PendingUserMessage>,
    earlier: com.zcode.remote.relay.ConversationChannel.EarlierState,
    turnSummaries: Map<Int, com.zcode.remote.relay.TurnDiffSummary>,
    expandedRows: List<String>,
    toggleExpanded: (String) -> Unit,
    isNearBottom: Boolean,
    onJumpToBottom: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    Box(modifier = modifier.fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "earlier-head") {
                Text(
                    text =
                        when {
                            earlier.loading -> "加载更早历史…"
                            earlier.pulled && !earlier.hasMore -> "已到会话开头"
                            else -> "上滑加载更早历史"
                        },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            items(rows, key = { it.rowId }) { row ->
                val expandKey = rowExpandKey(row)
                RowItem(
                    row = row,
                    expanded = expandKey in expandedRows,
                    onToggleExpand = { toggleExpanded(expandKey) },
                )
                turnSummaries[row.rowId]?.let { summary ->
                    com.zcode.remote.ui.components.TurnChangesCard(
                        summary = summary,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
            // C-6：本地 pending 回显气泡（服务端 userInput 行到达后由 VM 移除）
            items(pendingUserMessages, key = { "pending-${it.id}" }) { p ->
                PendingUserBubble(p)
            }
        }

        // 悬浮「回到底部」胶囊按钮（仅在用户主动上翻查阅历史且列表有内容时展示）
        androidx.compose.animation.AnimatedVisibility(
            visible = !isNearBottom && rows.isNotEmpty(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 10.dp, end = 6.dp),
        ) {
            Surface(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onJumpToBottom()
                },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shadowElevation = 4.dp,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "回到底部",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "回到底部",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}
