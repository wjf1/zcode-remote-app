package com.zcode.remote.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zcode.remote.relay.FailureReason
import com.zcode.remote.relay.RelayState
import com.zcode.remote.relay.RpcChannel
import com.zcode.remote.relay.SessionItem
import com.zcode.remote.relay.TaskEvent
import com.zcode.remote.storage.PairedDevice
import com.zcode.remote.ui.theme.ZCodeTokens

/**
 * 现代高信息密度会话工作台 (Sessions Tab)
 * 对标官方 ZCode calm, dense, operational 规范，专注于工作区与会话列表管理。
 */
@Composable
fun HomeScreen(
    deviceName: String,
    state: RelayState,
    sessions: List<SessionItem>,
    events: List<TaskEvent> = emptyList(),
    bridgeState: RpcChannel.BridgeState,
    rpcEvents: List<String> = emptyList(),
    devices: List<PairedDevice> = emptyList(),
    activeSid: String? = null,
    sessionPending: Map<String, Int> = emptyMap(),
    query: String = "",
    onQueryChange: (String) -> Unit = {},
    subscribedSessionId: String? = null,
    desktopActiveTaskId: String? = null,
    onSessionClick: (SessionItem) -> Unit,
    onDisconnect: () -> Unit = {},
    onRescan: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
) {
    var filterOnlyRunning by remember { mutableStateOf(false) }
    var filterOnlyPending by remember { mutableStateOf(false) }

    val filteredSessions = remember(sessions, query, filterOnlyRunning, filterOnlyPending, sessionPending) {
        var list = sessions
        val q = query.trim()
        if (q.isNotEmpty()) {
            list = list.filter {
                it.title.contains(q, ignoreCase = true) ||
                        (it.workspacePath ?: "").contains(q, ignoreCase = true)
            }
        }
        if (filterOnlyRunning) {
            list = list.filter { it.isRunning }
        }
        if (filterOnlyPending) {
            list = list.filter { (sessionPending[it.taskId] ?: 0) > 0 }
        }
        list
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // 1. 顶栏：标题 + 状态小胶囊
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 10.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "ZCode 会话",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
                )
                Spacer(Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { onNavigateToSettings() }
                ) {
                    StatusDot(state)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "${deviceName.ifEmpty { "远程设备" }} · ${statusLabel(state)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 2. 控制权被接管横幅提示（KICKED）
        if (state is RelayState.Failed && state.reason == FailureReason.KICKED) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "控制权已被其它终端接管",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = "同一账号单时刻只允许一个终端在线。若要继续，请前往设置重新连接。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.9f)
                        )
                    }
                }
            }
        }

        // 3. 搜索与筛选工具条
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            placeholder = { Text("搜索会话标题或工作区路径…", style = MaterialTheme.typography.bodySmall) },
            singleLine = true,
            leadingIcon = {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp))
                    }
                }
            },
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
            )
        )

        // 快捷筛选 Chip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val runningCount = sessions.count { it.isRunning }
            val pendingCount = sessions.count { (sessionPending[it.taskId] ?: 0) > 0 }

            FilterChip(
                selected = !filterOnlyRunning && !filterOnlyPending,
                onClick = {
                    filterOnlyRunning = false
                    filterOnlyPending = false
                },
                label = { Text("全部 (${sessions.size})", style = MaterialTheme.typography.labelSmall) },
                shape = RoundedCornerShape(8.dp)
            )

            if (runningCount > 0) {
                FilterChip(
                    selected = filterOnlyRunning,
                    onClick = {
                        filterOnlyRunning = !filterOnlyRunning
                        if (filterOnlyRunning) filterOnlyPending = false
                    },
                    label = { Text("运行中 ($runningCount)", style = MaterialTheme.typography.labelSmall) },
                    shape = RoundedCornerShape(8.dp)
                )
            }

            if (pendingCount > 0) {
                FilterChip(
                    selected = filterOnlyPending,
                    onClick = {
                        filterOnlyPending = !filterOnlyPending
                        if (filterOnlyPending) filterOnlyRunning = false
                    },
                    label = { Text("待处理 ($pendingCount)", style = MaterialTheme.typography.labelSmall) },
                    shape = RoundedCornerShape(8.dp)
                )
            }
        }

        // 4. 会话列表
        if (sessions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 60.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = when (state) {
                        is RelayState.WaitingPeer -> "已连接中继，等待 PC 桌面端接入…\n请在 PC 打开「移动端远程控制」"
                        is RelayState.Paired -> "已与 PC 配对，正在拉取会话列表…"
                        else -> "正在建立安全连接…"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                if (filteredSessions.isEmpty()) {
                    item {
                        Text(
                            text = "没有找到匹配的会话",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    }
                }

                items(filteredSessions, key = { it.taskId }) { s ->
                    SessionItemCard(
                        s = s,
                        pending = sessionPending[s.taskId] ?: 0,
                        isActive = s.taskId == subscribedSessionId,
                        desktopActive = s.taskId == desktopActiveTaskId,
                        onClick = { onSessionClick(s) }
                    )
                }
            }
        }
    }
}

/**
 * 官方 ZCode 风格精细化会话卡片
 */
@Composable
private fun SessionItemCard(
    s: SessionItem,
    pending: Int,
    isActive: Boolean,
    desktopActive: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                pending > 0 -> MaterialTheme.colorScheme.surface
                isActive -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                s.isRunning -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                else -> MaterialTheme.colorScheme.surface
            }
        ),
        border = if (pending > 0) {
            CardDefaults.outlinedCardBorder().copy(
                brush = androidx.compose.ui.graphics.SolidColor(ZCodeTokens.StatusPending.copy(alpha = 0.6f))
            )
        } else if (isActive) {
            CardDefaults.outlinedCardBorder().copy(
                brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
            )
        } else {
            CardDefaults.outlinedCardBorder()
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            // 状态指示圆点
            Box(
                modifier = Modifier
                    .padding(top = 4.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            pending > 0 -> ZCodeTokens.StatusPending
                            s.isRunning -> ZCodeTokens.StatusOnline
                            else -> ZCodeTokens.StatusOffline
                        }
                    )
            )

            Spacer(Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                // 标题行与徽标
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = s.title.ifBlank { "未命名会话" },
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    if (isActive) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "正在查看",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    fontSize = 10.sp
                                ),
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }

                    if (desktopActive && !isActive) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "PC焦点",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                // 工作区路径与模型元数据
                Text(
                    text = listOfNotNull(
                        s.workspaceLabel ?: s.workspacePath?.substringAfterLast('/'),
                        s.provider,
                        statusText(s.displayStatus)
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // 待处理提醒
                if (pending > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "⏳ $pending 项待决议 (请前往「待办」处理)",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = ZCodeTokens.StatusPending
                    )
                }
            }
        }
    }
}

private fun statusText(s: String) = when (s) {
    "running" -> "运行中"
    "streaming" -> "生成中"
    "completed" -> "已完成"
    "error" -> "异常"
    else -> s
}

@Composable
private fun StatusDot(state: RelayState) {
    val color = when (state) {
        is RelayState.Paired -> ZCodeTokens.StatusOnline
        is RelayState.WaitingPeer -> ZCodeTokens.StatusPending
        is RelayState.Failed -> ZCodeTokens.StatusError
        else -> ZCodeTokens.StatusOffline
    }
    Canvas(Modifier.size(8.dp)) { drawCircle(color) }
}

private fun statusLabel(state: RelayState) = when (state) {
    RelayState.Idle -> "未连接"
    RelayState.Connecting -> "连接中"
    RelayState.Authenticating -> "握手中"
    RelayState.WaitingPeer -> "等待接入"
    RelayState.Paired -> "就绪"
    is RelayState.Failed -> "异常"
}
