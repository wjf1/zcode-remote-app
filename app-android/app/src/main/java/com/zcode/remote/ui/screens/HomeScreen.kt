package com.zcode.remote.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zcode.remote.relay.RelayState
import com.zcode.remote.relay.RpcChannel
import com.zcode.remote.relay.SessionItem
import com.zcode.remote.relay.TaskEvent

/** 主屏：连接状态 + PC 信息 + 会话列表（点击订阅）+ RPC 观测面板。 */
@Composable
fun HomeScreen(
    deviceName: String,
    state: RelayState,
    sessions: List<SessionItem>,
    events: List<TaskEvent>,
    bridgeState: RpcChannel.BridgeState,
    rpcEvents: List<String>,
    onSessionClick: (SessionItem) -> Unit,
    onDisconnect: () -> Unit,
    onRescan: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ZCode Remote", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onRescan) { Text("重新配对") }
        }

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(deviceName.ifEmpty { "已配对设备" }, style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = onDisconnect) { Text("断开") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(state)
                    Spacer(Modifier.width(8.dp))
                    Text(statusLabel(state), style = MaterialTheme.typography.bodyMedium)
                }
                Text("会话桥：" + bridgeLabel(bridgeState), style = MaterialTheme.typography.bodySmall)
                if (state is RelayState.Failed) {
                    Text(state.message ?: "", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("会话", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (sessions.isNotEmpty()) {
                Text("${sessions.count { it.isRunning }} 运行中 / ${sessions.size}",
                    style = MaterialTheme.typography.bodySmall)
            }
        }

        if (sessions.isEmpty()) {
            Text(
                when (state) {
                    is RelayState.WaitingPeer -> "已连上中继，等待 PC 端接入…请在 PC 打开「移动端远程控制」"
                    is RelayState.Paired -> "已与 PC 配对，正在拉取会话列表…"
                    else -> "连接中…"
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }

        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sessions) { s -> SessionCard(s) { onSessionClick(s) } }
            if (rpcEvents.isNotEmpty()) {
                item { Text("RPC 事件（${rpcEvents.size}）", style = MaterialTheme.typography.titleSmall) }
                items(rpcEvents) { raw -> RpcEventCard(raw) }
            }
            if (events.isNotEmpty()) {
                item { Text("任务事件", style = MaterialTheme.typography.titleSmall) }
                items(events) { ev -> EventCard(ev) }
            }
        }
    }
}

@Composable
private fun RpcEventCard(raw: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Text(raw, Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall, maxLines = 6,
            overflow = TextOverflow.Ellipsis)
    }
}

private fun bridgeLabel(b: RpcChannel.BridgeState) = when (b) {
    is RpcChannel.BridgeState.Closed -> "未开桥"
    is RpcChannel.BridgeState.Opening -> "开桥中…"
    is RpcChannel.BridgeState.Ready -> "已就绪"
    is RpcChannel.BridgeState.Failed -> "失败"
}

@Composable
private fun SessionCard(s: SessionItem, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = if (s.isRunning) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        else CardDefaults.cardColors(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            StatusDot(
                when {
                    s.needsApproval -> RelayState.Failed(com.zcode.remote.relay.FailureReason.INTERNAL, null)
                    s.isRunning -> RelayState.Paired
                    else -> RelayState.Idle
                }
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(s.title, style = MaterialTheme.typography.titleSmall, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(
                    listOfNotNull(s.workspaceLabel ?: s.workspacePath, s.provider, statusText(s.displayStatus))
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun EventCard(ev: TaskEvent) {
    val isApproval = ev.type == "permission_request" || ev.type == "elicitation_request"
    Card(
        colors = if (isApproval) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label(ev.type), style = MaterialTheme.typography.titleSmall,
                color = if (isApproval) MaterialTheme.colorScheme.primary else Color.Unspecified)
            ev.workspacePath?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
        }
    }
}

private fun statusText(s: String) = when (s) {
    "running" -> "运行中"; "streaming" -> "生成中"; "completed" -> "已完成"
    "error" -> "出错"; else -> s
}

private fun label(type: String) = when (type) {
    "created" -> "任务已创建"; "prompt_sent" -> "指令已发送"; "resumed" -> "任务已恢复"
    "streaming" -> "生成中"; "permission_request" -> "⏳ 等待审批"; "permission_resolved" -> "审批已处理"
    "elicitation_request" -> "⏳ 需要补充信息"; "elicitation_resolved" -> "补充已提交"
    "updated" -> "状态更新"; "completed" -> "✅ 已完成"; "error" -> "❌ 出错"; else -> type
}

@Composable
private fun StatusDot(state: RelayState) {
    val color = when (state) {
        is RelayState.Paired -> Color(0xFF4CAF50)
        is RelayState.WaitingPeer -> Color(0xFFFFA726)
        is RelayState.Failed -> Color(0xFFE53935)
        else -> Color.Gray
    }
    androidx.compose.foundation.Canvas(Modifier.size(10.dp)) { drawCircle(color) }
}

private fun statusLabel(state: RelayState) = when (state) {
    RelayState.Idle -> "未连接"
    RelayState.Connecting -> "连接中继…"
    RelayState.Authenticating -> "认证中…"
    RelayState.WaitingPeer -> "等待 PC 接入"
    RelayState.Paired -> "已配对"
    is RelayState.Failed -> "连接失败"
}
