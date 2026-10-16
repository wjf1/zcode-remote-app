package com.zcode.remote.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zcode.remote.R
import com.zcode.remote.relay.FailureReason
import com.zcode.remote.relay.RelayState
import com.zcode.remote.relay.SessionItem
import com.zcode.remote.ui.theme.ZCodeTokens
import com.zcode.remote.ui.theme.statusPendingTone
import com.zcode.remote.ui.theme.toolCallTrajectoryTone
import com.zcode.remote.ui.voice.VoiceInputButton
import com.zcode.remote.util.UiText
import com.zcode.remote.util.UiText.Companion.res
import com.zcode.remote.util.asString

/**
 * 现代高信息密度会话工作台 (Sessions Tab)
 * 对标官方 ZCode calm, dense, operational 规范，专注于工作区与会话列表管理。
 */
@Composable
fun HomeScreen(
    deviceName: String,
    state: RelayState,
    sessions: List<SessionItem>,
    sessionPending: Map<String, Int> = emptyMap(),
    query: String = "",
    onQueryChange: (String) -> Unit = {},
    subscribedSessionId: String? = null,
    desktopActiveTaskId: String? = null,
    onSessionClick: (SessionItem) -> Unit,
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
    /** 长按会话卡片触发删除（closeSession + deleteTask）。 */
    onSessionDelete: (SessionItem) -> Unit = {},
    onCreateSession: (
        prompt: String,
        modelConfig: com.zcode.remote.relay.WorkspaceConfigChannel.ModelOption?,
        execMode: String,
    ) -> Unit = { _, _, _ -> },
) {
    var filterOnlyRunning by remember { mutableStateOf(false) }
    var filterOnlyPending by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }

    val filteredSessions =
        remember(sessions, query, filterOnlyRunning, filterOnlyPending, sessionPending) {
            var list = sessions
            val q = query.trim()
            if (q.isNotEmpty()) {
                list =
                    list.filter {
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
        modifier =
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // 1. 顶栏：标题 + 状态小胶囊 + 新建会话按钮
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_title),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                )
                Spacer(Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.a11y_open_settings)) {
                                onNavigateToSettings()
                            },
                ) {
                    StatusDot(state, statusLabel(state).asString())
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text =
                            stringResource(
                                R.string.home_device_with_status,
                                deviceName.ifEmpty { stringResource(R.string.home_device_unnamed) },
                                statusLabel(state).asString(),
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Button(
                onClick = { showCreateDialog = true },
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier.height(36.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.home_new_session), style = MaterialTheme.typography.labelSmall)
            }
        }

        // 2. 控制权被接管横幅提示（KICKED）
        if (state is RelayState.Failed && state.reason == FailureReason.KICKED) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.home_kicked_title),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            text = stringResource(R.string.home_kicked_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.9f),
                        )
                    }
                }
            }
        }

        // 3. 搜索与筛选工具条
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
            placeholder = { Text(stringResource(R.string.home_search_placeholder), style = MaterialTheme.typography.bodySmall) },
            singleLine = true,
            leadingIcon = {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
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
            colors =
                OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                ),
        )

        // 快捷筛选 Chip
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val runningCount = sessions.count { it.isRunning }
            val pendingCount = sessions.count { (sessionPending[it.taskId] ?: 0) > 0 }

            FilterChip(
                selected = !filterOnlyRunning && !filterOnlyPending,
                onClick = {
                    filterOnlyRunning = false
                    filterOnlyPending = false
                },
                label = { Text(stringResource(R.string.home_filter_all, sessions.size), style = MaterialTheme.typography.labelSmall) },
                shape = RoundedCornerShape(8.dp),
            )

            if (runningCount > 0) {
                FilterChip(
                    selected = filterOnlyRunning,
                    onClick = {
                        filterOnlyRunning = !filterOnlyRunning
                        if (filterOnlyRunning) filterOnlyPending = false
                    },
                    label = { Text(stringResource(R.string.home_filter_running, runningCount), style = MaterialTheme.typography.labelSmall) },
                    shape = RoundedCornerShape(8.dp),
                )
            }

            if (pendingCount > 0) {
                FilterChip(
                    selected = filterOnlyPending,
                    onClick = {
                        filterOnlyPending = !filterOnlyPending
                        if (filterOnlyPending) filterOnlyRunning = false
                    },
                    label = { Text(stringResource(R.string.home_filter_pending, pendingCount), style = MaterialTheme.typography.labelSmall) },
                    shape = RoundedCornerShape(8.dp),
                )
            }
        }

        // 4. 会话列表
        if (sessions.isEmpty()) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(bottom = 60.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(horizontal = 24.dp),
                ) {
                    Text(
                        text = connectionEmptyStateText(state).asString(),
                        style = MaterialTheme.typography.bodyMedium,
                        color =
                            if (state is RelayState.Failed) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    // 终态失败（KICKED/AUTH_FAILED/PROTOCOL_MISMATCH/INVALID_ENDPOINT）不会自动重连，
                    // 给出「前往设置」引导，避免用户对着永不成功的「正在连接」干等。
                    if (isTerminalFailure(state)) {
                        TextButton(onClick = onNavigateToSettings) {
                            Text(stringResource(R.string.home_go_settings), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 80.dp),
            ) {
                if (filteredSessions.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.home_no_match),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    }
                }

                items(filteredSessions, key = { it.taskId }) { s ->
                    SessionItemCard(
                        s = s,
                        pending = sessionPending[s.taskId] ?: 0,
                        isActive = s.taskId == subscribedSessionId,
                        desktopActive = s.taskId == desktopActiveTaskId,
                        onClick = { onSessionClick(s) },
                        onDelete = { onSessionDelete(s) },
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
                onConfirm = { prompt, modelConfig, execMode ->
                    showCreateDialog = false
                    onCreateSession(prompt, modelConfig, execMode)
                },
            )
        }
    }
}

/**
 * 官方 ZCode 风格精细化会话卡片（长按呼出删除确认）
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SessionItemCard(
    s: SessionItem,
    pending: Int,
    isActive: Boolean,
    desktopActive: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit = {},
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // 无障碍（走查 #12）：卡片语义文案在 Composable 层拼好（semantics 块内不能调 stringResource）
    val a11yCardLabel =
        stringResource(
            R.string.a11y_session_card,
            s.title.ifBlank { stringResource(R.string.common_unnamed_session) },
            statusText(s.displayStatus).asString(),
        ) + "，" + stringResource(R.string.a11y_session_card_long_press_hint)

    Card(
        shape = RoundedCornerShape(12.dp),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    when {
                        pending > 0 -> MaterialTheme.colorScheme.surface
                        isActive -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                        s.isRunning -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        else -> MaterialTheme.colorScheme.surface
                    },
            ),
        border =
            if (pending > 0) {
                CardDefaults.outlinedCardBorder().copy(
                    brush =
                        androidx.compose.ui.graphics
                            .SolidColor(statusPendingTone().copy(alpha = 0.6f)),
                )
            } else if (isActive) {
                CardDefaults.outlinedCardBorder().copy(
                    brush =
                        androidx.compose.ui.graphics
                            .SolidColor(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                )
            } else {
                CardDefaults.outlinedCardBorder()
            },
        modifier =
            Modifier
                .fillMaxWidth()
                // 无障碍（走查 #12）：自定义点击行补语义——TalkBack 需知道它是按钮、
                // 读得出「会话 + 标题 + 状态」，并被告知有长按删除（否则功能不可发现）。
                .semantics(mergeDescendants = true) {
                    contentDescription = a11yCardLabel
                }.combinedClickable(
                    onClick = onClick,
                    onLongClick = { showDeleteConfirm = true },
                ),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // 状态指示圆点
            Box(
                modifier =
                    Modifier
                        .padding(top = 4.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                pending > 0 -> statusPendingTone()
                                s.isRunning -> ZCodeTokens.StatusOnline
                                else -> ZCodeTokens.StatusOffline
                            },
                        ),
            )

            Spacer(Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                // 标题行与徽标
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = s.title.ifBlank { stringResource(R.string.common_unnamed_session) },
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )

                    if (isActive) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(4.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.home_badge_active),
                                style =
                                    MaterialTheme.typography.labelSmall.copy(
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        fontSize = 10.sp,
                                    ),
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                            )
                        }
                    }

                    if (desktopActive && !isActive) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(4.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.home_badge_desktop_focus),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                // 工作区路径与模型元数据
                Text(
                    text =
                        listOfNotNull(
                            s.workspaceLabel ?: s.workspacePath?.substringAfterLast('/'),
                            s.provider,
                            statusText(s.displayStatus).asString(),
                        ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                // 异常原因（host 的 task meta.lastError / 会话快照 control.lastError 回填）。
                // 数据源说明：bootstrap 的 tasks[] 形状不含该字段（PROTOCOL.md 实测样本），
                // 故「没打开过的会话」可能只有「异常」二字——原因在会话页的快照里一定有。
                val errorReason = s.lastError?.takeIf { it.isNotBlank() }
                if (s.displayStatus == "error" && errorReason != null) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = errorReason.lineSequence().lastOrNull { it.isNotBlank() } ?: errorReason,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // 待处理提醒
                if (pending > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.home_pending_hint, pending),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = statusPendingTone(),
                    )
                }
            }
        }
    }

    // 长按删除确认弹窗
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.home_delete_title)) },
            text = {
                Text(stringResource(R.string.home_delete_body, s.title.ifBlank { stringResource(R.string.common_unnamed_session) }))
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete()
                }) { Text(stringResource(R.string.home_delete_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

private fun statusText(s: String): UiText =
    when (s) {
        "running" -> res(R.string.home_session_status_running)
        "streaming" -> res(R.string.home_session_status_streaming)
        "completed" -> res(R.string.home_session_status_completed)
        "error" -> res(R.string.home_session_status_error)
        else -> UiText.Raw(s)
    }

@Composable
private fun StatusDot(
    state: RelayState,
    a11yStatusText: String,
) {
    val color =
        when (state) {
            is RelayState.Paired -> ZCodeTokens.StatusOnline
            is RelayState.WaitingPeer -> statusPendingTone()
            is RelayState.Failed -> ZCodeTokens.StatusError
            else -> ZCodeTokens.StatusOffline
        }
    // 无障碍（走查 #12）：这是纯视觉圆点，TalkBack 读不到颜色——补语义标签。
    // clearAndSetSemantics 避免它与紧邻的状态文案各读一遍（两处合成一句）。
    Canvas(
        Modifier
            .size(8.dp)
            .clearAndSetSemantics {
                contentDescription = a11yStatusText
            },
    ) { drawCircle(color) }
}

private fun statusLabel(state: RelayState): UiText =
    when (state) {
        RelayState.Idle -> res(R.string.relay_status_idle)
        RelayState.Connecting -> res(R.string.relay_status_connecting)
        RelayState.Authenticating -> res(R.string.relay_status_authenticating)
        RelayState.WaitingPeer -> res(R.string.relay_status_waiting_peer)
        RelayState.Paired -> res(R.string.relay_status_paired)
        is RelayState.Failed -> res(R.string.relay_status_failed)
    }

/** 自动挑选的思考档位：优先首个非 disabled（保持推理开启），全为 disabled 时取第一个。 */
private fun autoThought(levels: List<String>): String? = levels.firstOrNull { !it.equals("disabled", ignoreCase = true) } ?: levels.firstOrNull()

/** 思考档位的中文展示名（未知档位原样回显）。会话页二级菜单复用。 */
internal fun thoughtLabel(level: String): UiText =
    when (level.lowercase()) {
        "disabled", "off", "none" -> res(R.string.thought_off)
        "low", "minimal" -> res(R.string.thought_low)
        "medium", "enabled" -> res(R.string.thought_medium)
        "high" -> res(R.string.thought_high)
        "max", "xhigh" -> res(R.string.thought_max)
        else -> UiText.Raw(level)
    }

/** P0-B：执行模式全集（协议合法值见 tools/setmode.py 的 VALID）。 */
private val EXEC_MODES = listOf("plan", "build", "yolo")

internal fun execModeLabel(m: String): UiText =
    when (m) {
        "plan" -> res(R.string.mode_plan_short)
        "build" -> res(R.string.mode_build_short)
        "yolo" -> res(R.string.mode_yolo_short)
        else -> UiText.Raw(m)
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
    onConfirm: (prompt: String, modelConfig: com.zcode.remote.relay.WorkspaceConfigChannel.ModelOption?, execMode: String) -> Unit,
) {
    var prompt by remember { mutableStateOf("") }
    var voiceListening by remember { mutableStateOf(false) }
    var voiceLive by remember { mutableStateOf<String?>(null) }

    // 执行模式（P0-B）：默认 build；yolo 免审批，须醒目警示
    var selectedExecMode by remember { mutableStateOf("build") }

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
    val effectiveSelection = selectedModel
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
                text = stringResource(R.string.create_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.create_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (voiceListening || !voiceLive.isNullOrBlank()) {
                    Text(
                        text = stringResource(R.string.create_voice_live, voiceLive ?: stringResource(R.string.create_listening)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    placeholder = {
                        Text(
                            stringResource(R.string.create_prompt_placeholder),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 100.dp, max = 180.dp),
                    shape = RoundedCornerShape(10.dp),
                )

                // ---- 模型选择器 ----
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.create_model_label),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Box {
                        OutlinedButton(
                            onClick = { modelMenuExpanded = true },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.fillMaxWidth().height(40.dp),
                        ) {
                            Icon(
                                Icons.Filled.Build,
                                contentDescription = null,
                                tint = toolCallTrajectoryTone(),
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text =
                                    effectiveSelection?.name
                                        ?: currentModel?.let { stringResource(R.string.create_model_default_named, it) }
                                        ?: stringResource(R.string.create_model_default_follow),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text("▾", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        DropdownMenu(
                            expanded = modelMenuExpanded,
                            onDismissRequest = { modelMenuExpanded = false },
                        ) {
                            when {
                                modelsLoading -> {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.create_models_loading), style = MaterialTheme.typography.bodySmall) },
                                        onClick = {},
                                        enabled = false,
                                    )
                                }
                                !hasCatalog -> {
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(
                                                    stringResource(R.string.create_models_unavailable),
                                                    style = MaterialTheme.typography.bodySmall,
                                                )
                                                Text(
                                                    stringResource(R.string.create_models_unavailable_hint),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                        text = { Text(stringResource(R.string.create_model_follow_pc), style = MaterialTheme.typography.bodySmall) },
                                        leadingIcon = {
                                            if (selectedModel == null) {
                                                Icon(
                                                    Icons.Filled.Check,
                                                    null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            }
                                        },
                                        onClick = {
                                            selectedModel = null
                                            modelMenuExpanded = false
                                        },
                                    )
                                    // 2. PC 端当前模型（从会话快照 config 快捷置顶）
                                    currentModel?.let { cur ->
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.create_model_pc_current, cur), style = MaterialTheme.typography.bodySmall) },
                                            leadingIcon = {
                                                if (selectedModel?.name == cur) {
                                                    Icon(
                                                        Icons.Filled.Check,
                                                        null,
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(16.dp),
                                                    )
                                                }
                                            },
                                            onClick = {
                                                selectedModel =
                                                    com.zcode.remote.relay.WorkspaceConfigChannel.ModelOption(
                                                        value = "${currentProvider ?: "glm"}/$cur",
                                                        name = cur,
                                                        providerId = currentProvider,
                                                        providerName = null,
                                                    )
                                                modelMenuExpanded = false
                                            },
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
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        )
                                                    }
                                                }
                                            },
                                            leadingIcon = {
                                                if (isSelected) {
                                                    Icon(
                                                        Icons.Filled.Check,
                                                        null,
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(16.dp),
                                                    )
                                                }
                                            },
                                            onClick = {
                                                selectedModel = m
                                                modelMenuExpanded = false
                                            },
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
                            text = stringResource(R.string.create_thought_label),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            availableThoughtLevels.forEach { lv ->
                                val picked = (selectedThought ?: autoThought(availableThoughtLevels)) == lv
                                FilterChip(
                                    selected = picked,
                                    onClick = { selectedThought = lv },
                                    label = {
                                        Text(
                                            text = thoughtLabel(lv).asString(),
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                )
                            }
                        }
                        Text(
                            text = stringResource(R.string.create_thought_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // ---- 执行模式选择器（P0-B）：默认 build；选 yolo 给醒目风险文案 ----
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.create_mode_label),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        EXEC_MODES.forEach { m ->
                            FilterChip(
                                selected = selectedExecMode == m,
                                onClick = { selectedExecMode = m },
                                label = {
                                    Text(
                                        text = execModeLabel(m).asString(),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                },
                                shape = RoundedCornerShape(8.dp),
                            )
                        }
                    }
                    when (selectedExecMode) {
                        "yolo" ->
                            Text(
                                text = stringResource(R.string.create_yolo_warning),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        else ->
                            Text(
                                text = stringResource(R.string.create_mode_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    VoiceInputButton(
                        onFinalText = { text ->
                            prompt = if (prompt.isBlank()) text else "$prompt $text"
                        },
                        onStateChange = { listening, live ->
                            voiceListening = listening
                            voiceLive = live
                        },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(prompt.trim(), finalSelection, selectedExecMode) },
                enabled = prompt.isNotBlank(),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(stringResource(R.string.create_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}
