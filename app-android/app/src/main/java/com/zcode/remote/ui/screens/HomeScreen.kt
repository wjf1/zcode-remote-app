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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
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
import com.zcode.remote.ui.voice.VoiceInputButton

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
    /** 工作区模型目录（新建会话弹窗的模型选择数据源，可为空=加载中/不可用）。 */
    modelState: com.zcode.remote.relay.WorkspaceConfigChannel.WorkspaceState? = null,
    modelsLoading: Boolean = false,
    onLoadModels: () -> Unit = {},
    /** PC 端当前会话快照里的模型（弹窗展示与 provider 继承用，可为空）。 */
    currentModel: String? = null,
    currentProvider: String? = null,
    availableModels: List<com.zcode.remote.relay.WorkspaceConfigChannel.ModelOption> = emptyList(),
    /** 各模型合法思考档位：key = "providerId/modelId"。 */
    modelReasoningLevels: Map<String, List<String>> = emptyMap(),
    onCreateSession: (prompt: String, modelConfig: com.zcode.remote.relay.WorkspaceConfigChannel.ModelOption?) -> Unit = { _, _ -> },
) {
    var filterOnlyRunning by remember { mutableStateOf(false) }
    var filterOnlyPending by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }

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
        // 1. 顶栏：标题 + 状态小胶囊 + 新建会话按钮
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
            Button(
                onClick = { showCreateDialog = true },
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier.height(36.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("新建会话", style = MaterialTheme.typography.labelSmall)
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

        // 新建会话弹窗
        if (showCreateDialog) {
            CreateSessionDialog(
                modelState = modelState,
                availableModels = availableModels,
                modelsLoading = modelsLoading,
                onLoadModels = onLoadModels,
                currentModel = currentModel,
                currentProvider = currentProvider,
                modelReasoningLevels = modelReasoningLevels,
                onDismiss = { showCreateDialog = false },
                onConfirm = { prompt, modelConfig ->
                    showCreateDialog = false
                    onCreateSession(prompt, modelConfig)
                }
            )
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

/** 自动挑选的思考档位：优先首个非 disabled（保持推理开启），全为 disabled 时取第一个。 */
private fun autoThought(levels: List<String>): String? =
    levels.firstOrNull { !it.equals("disabled", ignoreCase = true) } ?: levels.firstOrNull()

/** 思考档位的中文展示名（未知档位原样回显）。 */
private fun thoughtLabel(level: String): String = when (level.lowercase()) {
    "disabled", "off", "none" -> "关闭"
    "low", "minimal" -> "低"
    "medium", "enabled" -> "中"
    "high" -> "高"
    "max", "xhigh" -> "最高"
    else -> level
}

/**
 * 新建会话弹窗
 * 支持输入首条任务指令、语音输入填充与可选的模型选择（数据源 = PC 端工作区模型目录）。
 */
@Composable
private fun CreateSessionDialog(
    modelState: com.zcode.remote.relay.WorkspaceConfigChannel.WorkspaceState?,
    availableModels: List<com.zcode.remote.relay.WorkspaceConfigChannel.ModelOption> = emptyList(),
    modelsLoading: Boolean,
    onLoadModels: () -> Unit,
    currentModel: String?,
    currentProvider: String?,
    /** 各模型合法思考档位：key = "providerId/modelId"。 */
    modelReasoningLevels: Map<String, List<String>> = emptyMap(),
    onDismiss: () -> Unit,
    onConfirm: (prompt: String, modelConfig: com.zcode.remote.relay.WorkspaceConfigChannel.ModelOption?) -> Unit,
) {
    var prompt by remember { mutableStateOf("") }
    var voiceListening by remember { mutableStateOf(false) }
    var voiceLive by remember { mutableStateOf<String?>(null) }
    var customModelId by remember { mutableStateOf("") }

    // 模型选择：null = 跟随 PC 端默认；选中 = 显式下发 config
    var selectedModel by remember { mutableStateOf<com.zcode.remote.relay.WorkspaceConfigChannel.ModelOption?>(null) }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    // 思考档位：null = 自动（取首个非 disabled 档位）
    var selectedThought by remember { mutableStateOf<String?>(null) }

    /** 该模型可供选择的思考档位（空 = 该模型无推理档位，不展示选择器）。 */
    fun levelsOf(opt: com.zcode.remote.relay.WorkspaceConfigChannel.ModelOption?): List<String> {
        if (opt == null) return emptyList()
        val pid = opt.providerId ?: return emptyList()
        return modelReasoningLevels["$pid/${opt.modelId()}"] ?: emptyList()
    }

    val modelsList = if (availableModels.isNotEmpty()) availableModels else (modelState?.models ?: emptyList())
    val hasCatalog = modelsList.isNotEmpty()
    // 自定义输入优先；否则用下拉选中项
    val effectiveSelection: com.zcode.remote.relay.WorkspaceConfigChannel.ModelOption? = when {
        customModelId.isNotBlank() -> com.zcode.remote.relay.WorkspaceConfigChannel.ModelOption(
            value = "${currentProvider ?: "glm"}/${customModelId.trim()}",
            name = customModelId.trim(),
            providerId = currentProvider,
            providerName = null,
        )
        else -> selectedModel
    }
    // 把用户选定的思考档位附着到模型选项上（未选则交给上层自动挑选）
    val finalSelection = effectiveSelection?.copy(thought = selectedThought)
    val availableThoughtLevels = levelsOf(effectiveSelection)

    // 切换模型后重置档位选择，避免把上一个模型的档位带到新模型
    LaunchedEffect(effectiveSelection?.value) { selectedThought = null }

    // 弹窗打开时拉一次模型目录
    LaunchedEffect(Unit) { onLoadModels() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "发起新会话",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "在当前活动工作区创建一个全新的 AI Agent 编程会话。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (voiceListening || !voiceLive.isNullOrBlank()) {
                    Text(
                        text = "🎤 ${voiceLive ?: "正在聆听…"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    placeholder = {
                        Text(
                            "输入给 Agent 的第一条任务指令（例如：检查并修复登录组件的单测）…",
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp, max = 180.dp),
                    shape = RoundedCornerShape(10.dp)
                )

                // ---- 模型选择器 ----
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "模型",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Box {
                        OutlinedButton(
                            onClick = { modelMenuExpanded = true },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.fillMaxWidth().height(40.dp)
                        ) {
                            Icon(
                                Icons.Filled.Build,
                                contentDescription = null,
                                tint = ZCodeTokens.ToolCallTrajectoryDark,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = effectiveSelection?.name
                                    ?: currentModel?.let { "默认（${it}）" }
                                    ?: "默认（跟随 PC 端）",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text("▾", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        DropdownMenu(
                            expanded = modelMenuExpanded,
                            onDismissRequest = { modelMenuExpanded = false }
                        ) {
                            when {
                                modelsLoading -> {
                                    DropdownMenuItem(
                                        text = { Text("加载模型目录中…", style = MaterialTheme.typography.bodySmall) },
                                        onClick = {},
                                        enabled = false,
                                    )
                                }
                                !hasCatalog -> {
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(
                                                    "远程模型目录暂不可用",
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                                Text(
                                                    "可在下方输入模型 ID 手动指定（留空跟随 PC 默认）",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        },
                                        onClick = {},
                                        enabled = false,
                                    )
                                }
                                else -> {
                                    // 1. 跟随默认
                                    DropdownMenuItem(
                                        text = { Text("跟随 PC 端默认", style = MaterialTheme.typography.bodySmall) },
                                        leadingIcon = {
                                            if (selectedModel == null && customModelId.isBlank()) {
                                                Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                            }
                                        },
                                        onClick = {
                                            selectedModel = null
                                            customModelId = ""
                                            modelMenuExpanded = false
                                        }
                                    )
                                    // 2. PC 端当前模型（从会话快照 config 快捷置顶）
                                    currentModel?.let { cur ->
                                        DropdownMenuItem(
                                            text = { Text("PC 当前 · $cur", style = MaterialTheme.typography.bodySmall) },
                                            leadingIcon = {
                                                if (selectedModel?.name == cur) {
                                                    Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                                }
                                            },
                                            onClick = {
                                                selectedModel = com.zcode.remote.relay.WorkspaceConfigChannel.ModelOption(
                                                    value = "${currentProvider ?: "glm"}/$cur",
                                                    name = cur,
                                                    providerId = currentProvider,
                                                    providerName = null,
                                                )
                                                customModelId = ""
                                                modelMenuExpanded = false
                                            }
                                        )
                                    }
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                                    // 3. 全部可用模型（综合从 PC 端读到的模型列表）
                                    modelsList.forEach { m ->
                                        val isSelected = selectedModel?.value == m.value
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text(m.name, style = MaterialTheme.typography.bodySmall)
                                                    m.providerName?.let {
                                                        Text(
                                                            it,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }
                                            },
                                            leadingIcon = {
                                                if (isSelected) {
                                                    Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                                }
                                            },
                                            onClick = {
                                                selectedModel = m
                                                customModelId = ""
                                                modelMenuExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // ---- 思考档位选择器（仅当所选模型有推理档位时展示）----
                if (availableThoughtLevels.size > 1) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "思考档位",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            availableThoughtLevels.forEach { lv ->
                                val picked = (selectedThought ?: autoThought(availableThoughtLevels)) == lv
                                FilterChip(
                                    selected = picked,
                                    onClick = { selectedThought = lv },
                                    label = {
                                        Text(
                                            text = thoughtLabel(lv),
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    },
                                    shape = RoundedCornerShape(8.dp)
                                )
                            }
                        }
                        Text(
                            text = "档位越高推理越深入，但耗时与消耗也更大。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    VoiceInputButton(
                        onFinalText = { text ->
                            prompt = if (prompt.isBlank()) text else "$prompt $text"
                        },
                        onStateChange = { listening, live ->
                            voiceListening = listening
                            voiceLive = live
                        }
                    )
                }

                // 自定义模型 ID（目录不可用或需临时指定时使用；留空 = 按下拉选择/默认）
                OutlinedTextField(
                    value = customModelId,
                    onValueChange = {
                        customModelId = it
                        selectedThought = null   // 手填模型 ID 时档位交回自动挑选
                    },
                    placeholder = {
                        Text(
                            "可选：手动指定模型 ID（如 glm-4.5 / deepseek-r1）",
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(prompt.trim(), finalSelection) },
                enabled = prompt.isNotBlank(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("立即创建")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
