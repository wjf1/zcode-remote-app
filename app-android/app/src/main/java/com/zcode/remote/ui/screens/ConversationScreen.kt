package com.zcode.remote.ui.screens

import androidx.compose.foundation.layout.*
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
import com.zcode.remote.relay.ConversationChannel
import com.zcode.remote.relay.ConversationRow

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
    onBack: () -> Unit,
) {
    val listState = rememberLazyListState()

    // 新行到达时贴底（流式文本靠 RowStore 原地更新，size 不变时不滚动）
    LaunchedEffect(rows.size) {
        if (rows.isNotEmpty()) listState.animateScrollToItem(rows.lastIndex)
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status is ConversationChannel.Status.Failed) MaterialTheme.colorScheme.error
                    else Color.Unspecified,
                )
            }
        }

        if (rows.isEmpty()) {
            Text("等待会话内容…", style = MaterialTheme.typography.bodySmall)
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(rows, key = { it.rowId }) { row -> RowItem(row) }
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
