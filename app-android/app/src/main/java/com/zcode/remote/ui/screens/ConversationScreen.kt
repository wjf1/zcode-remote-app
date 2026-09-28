package com.zcode.remote.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zcode.remote.relay.ApprovalOption
import com.zcode.remote.relay.ConversationChannel
import com.zcode.remote.relay.ConversationRow
import com.zcode.remote.relay.PendingApproval

/**
 * 会话内容页：把会话流（snapshot 尾窗 + 增量）渲染成对话。
 *
 * 行没有统一 role，按 kind 区分：userInput / assistantText / reasoning / toolCall / turnHeader。
 */
@Composable
fun ConversationScreen(
    title: String,
    status: ConversationChannel.Status,
    meta: ConversationChannel.ConversationMeta,
    rows: List<ConversationRow>,
    approvals: List<PendingApproval> = emptyList(),
    approvalFeedback: String? = null,
    earlier: ConversationChannel.EarlierState = ConversationChannel.EarlierState(),
    onResolve: (PendingApproval, ApprovalOption) -> Unit = { _, _ -> },
    onLoadEarlier: () -> Unit = {},
    onFeedbackSeen: () -> Unit = {},
    onBack: () -> Unit,
) {
    val listState = rememberLazyListState()

    // 新行到达时贴底（流式文本靠 RowStore 原地更新，size 不变时不滚动）；
    // 历史翻页前插时锚定原首行，避免视口跳变。
    var anchorRowId by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(rows.size) {
        val anchor = anchorRowId
        if (anchor != null) {
            val idx = rows.indexOfFirst { it.rowId == anchor }
            // LazyColumn 顶部有一个"加载更早"占位项，items 从 index 1 开始
            if (idx >= 0) listState.scrollToItem(idx + 1)
            anchorRowId = null
        } else {
            if (rows.isNotEmpty()) listState.animateScrollToItem(rows.lastIndex)
        }
    }

    // 滚到顶部附近：还有更早历史（或从未拉过）就自动拉一页
    val loadGate = rememberUpdatedState(Triple(rows.size, earlier.hasMore, earlier.loading to earlier.pulled))
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { idx ->
            val (size, hasMore, loadingPulled) = loadGate.value
            val (loading, pulled) = loadingPulled
            if (size > 0 && idx <= 2 && !loading && (hasMore || !pulled)) {
                anchorRowId = rows.firstOrNull()?.rowId
                onLoadEarlier()
            }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Column(Modifier.weight(1f)) {
                Text(title.ifBlank { "会话" }, style = MaterialTheme.typography.titleSmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        statusLabel(status),
                        meta.phase?.let { phaseLabel(it) },
                        meta.totalCount?.let { "共 $it 行" },
                        approvals.size.takeIf { it > 0 }?.let { "待审批 $it" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status is ConversationChannel.Status.Failed) MaterialTheme.colorScheme.error
                    else Color.Unspecified,
                )
            }
        }

        approvalFeedback?.let { msg ->
            LaunchedEffect(msg) { onFeedbackSeen() }
            Text(
                msg,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // 审批条置顶：待审批时正文可能被刷走，不能放在列表里
        approvals.forEach { ApprovalCard(it, onResolve) }

        if (rows.isEmpty()) {
            Text("等待会话内容…", style = MaterialTheme.typography.bodySmall)
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (rows.isNotEmpty()) {
                item(key = "earlier-head") {
                    Text(
                        when {
                            earlier.loading -> "加载更早…"
                            earlier.pulled && !earlier.hasMore -> "已到最早"
                            else -> "上滑加载更早"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
            items(rows, key = { it.rowId }) { row -> RowItem(row) }
        }
    }
}

/** 一条待审批：工具名 + 摘要 + 详情 + 按 kind 排好序的选项按钮。 */
@Composable
private fun ApprovalCard(
    a: PendingApproval,
    onResolve: (PendingApproval, ApprovalOption) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("⚠️ 需要审批 · ${a.toolName ?: "工具调用"}",
                style = MaterialTheme.typography.titleSmall)
            a.summary?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3,
                    overflow = TextOverflow.Ellipsis)
            }
            a.detail?.let {
                Text(it.take(600), style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace)
            }
            a.autoResolveAt?.let {
                Text("桌面端 ${(it - System.currentTimeMillis()).coerceAtLeast(0) / 1000}s 后自动决议",
                    style = MaterialTheme.typography.labelSmall)
            }
            if (a.options.isEmpty()) {
                Text("服务端没给选项，请到桌面端处理", style = MaterialTheme.typography.bodySmall)
            }
            a.options.forEach { opt ->
                Button(
                    onClick = { onResolve(a, opt) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (opt.isAllow) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(
                        when {
                            opt.kind == "allowOnce" -> "允许一次"
                            opt.kind == "allowAlways" -> "总是允许"
                            opt.isDeny -> "拒绝"
                            else -> opt.label.ifBlank { "选项" }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun RowItem(row: ConversationRow) = when (row.kind) {
    "userInput" -> Bubble(row.text.orEmpty(), isUser = true)
    "assistantText" -> Bubble(row.text.orEmpty(), isUser = false, streaming = row.isStreaming)
    "reasoning" -> ReasoningBlock(row)
    "toolCall" -> ToolCallCard(row)
    "turnHeader" -> TurnHeaderRow(row)
    else -> PlainRow(row)
}

@Composable
private fun Bubble(text: String, isUser: Boolean, streaming: Boolean = false) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            modifier = Modifier.widthIn(max = 300.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(Modifier.padding(10.dp)) {
                Text(text, style = MaterialTheme.typography.bodyMedium)
                if (streaming) {
                    Text("生成中…", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun ReasoningBlock(row: ConversationRow) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(Modifier.padding(8.dp)) {
            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                Text(if (expanded) "▾ 思考过程" else "▸ 思考过程", style = MaterialTheme.typography.labelMedium)
            }
            if (expanded) {
                Text(
                    row.text.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ToolCallCard(row: ConversationRow) {
    var expanded by remember { mutableStateOf(false) }
    val tone = when (row.status) {
        "success" -> Color(0xFF4CAF50)
        "error", "cancelled" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    Card(shape = RoundedCornerShape(8.dp)) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔧 ${row.toolName ?: "tool"}", style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f))
                Text(row.status ?: "", style = MaterialTheme.typography.labelSmall, color = tone)
            }
            // 摘要行：把输入压成一行看个大概
            val summary = row.inputText?.replace('\n', ' ')?.trim()
            if (!summary.isNullOrBlank()) {
                Text(summary, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                Text(if (expanded) "收起" else "详情", style = MaterialTheme.typography.labelSmall)
            }
            if (expanded) {
                CodeBlock("输入", row.inputText)
                CodeBlock("输出", row.outputText)
            }
        }
    }
}

@Composable
private fun CodeBlock(label: String, content: String?) {
    if (content.isNullOrBlank()) return
    Text(label, style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(top = 4.dp))
    Text(
        content.take(4000),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(top = 2.dp),
    )
}

@Composable
private fun TurnHeaderRow(row: ConversationRow) {
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    Text(
        listOfNotNull(
            row.state,
            row.createdAt?.let { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(it)) },
        ).joinToString(" · "),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PlainRow(row: ConversationRow) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(8.dp)) {
            Text("${row.kind} #${row.rowId}", style = MaterialTheme.typography.labelSmall)
            row.text?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 4,
                overflow = TextOverflow.Ellipsis) }
        }
    }
}

private fun statusLabel(s: ConversationChannel.Status) = when (s) {
    ConversationChannel.Status.Idle -> "未订阅"
    ConversationChannel.Status.Hello -> "握手中…"
    ConversationChannel.Status.Initialized -> "订阅中…"
    is ConversationChannel.Status.Live -> "已订阅"
    is ConversationChannel.Status.Failed -> "失败：${s.reason.take(80)}"
}

private fun phaseLabel(p: String) = when (p) {
    "completedInterrupted" -> "已结束"
    "idle" -> "空闲"
    "running" -> "运行中"
    else -> p
}
