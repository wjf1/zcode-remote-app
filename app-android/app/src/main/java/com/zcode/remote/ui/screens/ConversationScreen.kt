package com.zcode.remote.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zcode.remote.AppViewModel
import com.zcode.remote.relay.ApprovalOption
import com.zcode.remote.relay.ConversationChannel
import com.zcode.remote.relay.ConversationRow
import com.zcode.remote.relay.PendingApproval
import com.zcode.remote.relay.PendingElicitation
import com.zcode.remote.ui.components.MarkdownView
import com.zcode.remote.ui.theme.ZCodeTokens
import com.zcode.remote.ui.voice.VoiceInputButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 沉浸式会话详情与对话控制台
 * 对标官方 ZCode 桌面端排版与交互规范，支持流式渲染、官方同款思考过程与工具调用。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    title: String,
    status: ConversationChannel.Status,
    meta: ConversationChannel.ConversationMeta,
    rows: List<ConversationRow>,
    approvals: List<PendingApproval> = emptyList(),
    elicitations: List<PendingElicitation> = emptyList(),
    approvalFeedback: String? = null,
    earlier: ConversationChannel.EarlierState = ConversationChannel.EarlierState(),
    prompt: String = "",
    sending: Boolean = false,
    canStop: Boolean = false,
    stopState: String? = null,
    commandFeedback: String? = null,
    attachments: List<ConversationChannel.AttachmentRef> = emptyList(),
    attachUploadName: String? = null,
    attachUploadPercent: Int = 0,
    onResolve: (PendingApproval, ApprovalOption) -> Unit = { _, _ -> },
    onElicitationAccept: (PendingElicitation, Map<Int, List<String>>) -> Unit = { _, _ -> },
    onElicitationDecline: (PendingElicitation) -> Unit = {},
    onElicitationFreeText: (PendingElicitation, String) -> Unit = { _, _ -> },
    onLoadEarlier: () -> Unit = {},
    onFeedbackSeen: () -> Unit = {},
    onPromptChange: (String) -> Unit = {},
    onSend: () -> Unit = {},
    onStop: () -> Unit = {},
    onAttachmentPicked: (uri: android.net.Uri, name: String, mime: String, size: Long) -> Unit = { _, _, _, _ -> },
    onRemoveAttachment: (ConversationChannel.AttachmentRef) -> Unit = {},
    availableModels: List<com.zcode.remote.relay.WorkspaceConfigChannel.ModelOption> = emptyList(),
    /** 各模型合法思考档位：key = "providerId/modelId"（会话内切模型时用于二级档位菜单）。 */
    modelReasoningLevels: Map<String, List<String>> = emptyMap(),
    onSwitchModel: (com.zcode.remote.relay.WorkspaceConfigChannel.ModelOption) -> Unit = {},
    onSwitchModelCustom: (String) -> Unit = {},
    /** 进入会话页时触发一次模型目录刷新（PC 端配置变更准实时同步）。 */
    onLoadModels: () -> Unit = {},
    /** 当前会话执行模式（P0-B：订阅 ack / 快照；用户切换后的乐观值由上层合并后传入）。 */
    currentSessionMode: String? = null,
    /** 切换执行模式（协议语义：仅对下一轮 agent turn 生效）。 */
    onSwitchMode: (String) -> Unit = {},
    /** 剧本 B「最近文件」：本会话文件预览状态与操作（AppViewModel 持有状态）。 */
    filePreview: AppViewModel.FilePreview? = null,
    onPreviewFile: (String) -> Unit = {},
    onDismissFilePreview: () -> Unit = {},
    /** B-1：权威快照是否已落地（含 seq，用于「缓存行数 == 快照行数」时仍能定位最新行）。 */
    snapshotAligned: ConversationChannel.SnapshotAligned? = null,
    /** B-3：附件上传成/败信号（tick 变化触发一次触觉）。 */
    attachmentFeedback: AppViewModel.AttachmentFeedback? = null,
    /** A-2：握手失败后用户手动重试订阅。 */
    onRetrySubscribe: () -> Unit = {},
    onBack: () -> Unit,
) {
    val listState = rememberLazyListState()
    val headerCount = if (rows.isEmpty()) 0 else 1
    // 剧本 B「最近文件」：从会话行派生文件清单（纯客户端零协议）
    val sessionFiles = remember(rows) { com.zcode.remote.relay.SessionFiles.extract(rows) }
    // Sprint 3 第三步：从会话行按 Turn 聚合文件写操作 Diff
    val turnSummaries = remember(rows) { com.zcode.remote.relay.TurnChanges.aggregate(rows) }
    var showFiles by remember { mutableStateOf(false) }

    // 进入会话页即刷新一次模型目录（PC 端新增/删除模型后回到 APP 即可看到最新列表）
    LaunchedEffect(Unit) { onLoadModels() }

    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()

    // B-3：附件上传成/败触觉。上传完成是 VM 侧异步驱动的，Compose 没有点击上下文，
    // 故由 attachmentFeedback.tick 变化触发一次（沿用本文件既有的 LocalHapticFeedback 路径）。
    LaunchedEffect(attachmentFeedback?.tick) {
        val fb = attachmentFeedback ?: return@LaunchedEffect
        haptic.performHapticFeedback(
            if (fb.ok) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove
        )
    }
    // 智能贴底状态：用户是否在列表底部附近（距离末尾 <= 2 项，或无法向前滚动）
    val isNearBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val total = layoutInfo.totalItemsCount
            if (total <= 1) true
            else {
                val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                lastVisible >= total - 2 || !listState.canScrollForward
            }
        }
    }

    var anchorRowId by remember { mutableStateOf<Int?>(null) }
    // B-1：本会话是否已执行过「进会话定位」（外层 key(taskId) 重建后自动归零）
    var pinnedThisSession by remember { mutableStateOf(false) }
    // B-1：用户是否已**主动**上翻离开底部（查阅历史）。程序化滚动不产生
    // DragInteraction，故这是区分「用户上翻」与「刚进入时列表停在顶部」的唯一可靠信号。
    // 不用「离底部多少项」判断——真机实测证明那样会在滚动动画与新增行竞态时卡在半路。
    var userScrolledAway by remember { mutableStateOf(false) }

    // B-1：显式「回到底部」跳转进行中。跳转期间必须**禁止翻页触发**，否则：
    // 在顶部点跳转 → 翻页同时触发并把锚点记为「列表首行」→ 加载更早历史后锚点恢复
    // 把视口从底部拽回中段，显式跳转被覆盖（真机实测 + 诊断日志，2026-10-07）。
    var jumpingToBottom by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { i ->
            if (i is androidx.compose.foundation.interaction.DragInteraction.Start) userScrolledAway = true
        }
    }
    // 用户自己拖回底部后，恢复自动跟随
    LaunchedEffect(isNearBottom) { if (isNearBottom) userScrolledAway = false }

    // B-1 决策输入：翻页/锚点恢复进行中时，任何自动定位都必须让位，
    // 否则刚加载进来的更早历史会被立刻拉回底部（把翻页体验改坏）。
    val paginationInFlight = earlier.loading || anchorRowId != null

    // 1) 进会话定位：有缓存（首帧即有行）与无缓存（等缓存/快照填行）两条时序都覆盖。
    //    声明在翻页效应之前，保证同一帧内先定位再评估翻页条件。
    LaunchedEffect(rows.isNotEmpty()) {
        if (ConversationScrollPolicy.shouldPinToLatest(
                ConversationScrollPolicy.Trigger.Enter, rows.size, paginationInFlight,
                pinnedThisSession, userScrolledAway,
            )
        ) {
            listState.scrollToItem(rows.lastIndex + headerCount)
            pinnedThisSession = true
        }
    }

    // 2) 快照对齐定位：覆盖「缓存行数 == 快照行数」这条 rows.size 不变、
    //    旧 LaunchedEffect(rows.size) 完全不触发的新失效路径。
    LaunchedEffect(snapshotAligned?.seq) {
        if (snapshotAligned == null) return@LaunchedEffect
        if (ConversationScrollPolicy.shouldPinToLatest(
                ConversationScrollPolicy.Trigger.SnapshotAligned, rows.size, paginationInFlight,
                pinnedThisSession, userScrolledAway,
            )
        ) {
            listState.scrollToItem(rows.lastIndex + headerCount)
            pinnedThisSession = true
        }
    }

    // 3) 流式增量跟随：以「末行 rowId + 文本长度」为键 —— 流式 appendText 只替换单行、
    //    size 不变，故不能再用 rows.size 作触发键。只要用户未主动上翻就持续跟随。
    val lastRowKey = rows.lastOrNull()?.let { it.rowId to (it.text?.length ?: -1) }
    LaunchedEffect(lastRowKey) {
        if (lastRowKey == null) return@LaunchedEffect
        if (ConversationScrollPolicy.shouldPinToLatest(
                ConversationScrollPolicy.Trigger.Delta, rows.size, paginationInFlight,
                pinnedThisSession, userScrolledAway,
            ) && rows.isNotEmpty()
        ) {
            listState.animateScrollToItem(rows.lastIndex + headerCount)
        }
    }

    // B-1 诊断：把滚动决策的全部输入打到 debug 日志（release 被 R8 的 -assumenosideeffects 剥离）。
    // 仅在状态跃迁时输出，不是每 delta 一行。B-1 的失效只能真机复现，这条是唯一的现场观测手段。
    LaunchedEffect(rows.size, paginationInFlight, pinnedThisSession, userScrolledAway, isNearBottom, jumpingToBottom) {
        com.zcode.remote.util.ZLog.d(
            "ConvScreen",
            "scroll决策 rows=${rows.size} pagination=$paginationInFlight pinned=$pinnedThisSession " +
                "away=$userScrolledAway nearBottom=$isNearBottom jumping=$jumpingToBottom",
        )
    }

    // 4) 翻页锚点恢复：独立于自动贴底，两类滚动互不打断。
    LaunchedEffect(rows.size) {
        val anchor = anchorRowId ?: return@LaunchedEffect
        val idx = rows.indexOfFirst { it.rowId == anchor }
        if (idx >= 0) listState.scrollToItem(idx + headerCount)
        anchorRowId = null
    }

    val loadGate = rememberUpdatedState(Triple(rows.size, earlier.hasMore, earlier.loading to earlier.pulled))
    val jumping = rememberUpdatedState(jumpingToBottom)
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { idx ->
            val (size, hasMore, loadingPulled) = loadGate.value
            val (loading, pulled) = loadingPulled
            // jumping.value：显式跳转期间不翻页，避免锚点恢复覆盖跳转目标
            if (size > 0 && idx <= 2 && !loading && !jumping.value && (hasMore || !pulled)) {
                anchorRowId = rows.firstOrNull()?.rowId
                onLoadEarlier()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 1. 顶栏：标准返回导航键 + 标题 + 紧凑元数据
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回列表",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title.ifBlank { "会话控制台" },
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = listOfNotNull(
                        statusLabel(status),
                        meta.phase?.let { phaseLabel(it) },
                        meta.totalCount?.let { "共 $it 行" },
                        approvals.size.takeIf { it > 0 }?.let { "待审批 $it" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (status is ConversationChannel.Status.Failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // A-2：握手失败后的显式重试入口。挂在顶栏而不是空态里——有离线缓存时
            // rows 非空、走不到空态，只有这里能稳定触达（自动重订已用尽时才需要它）。
            if (status is ConversationChannel.Status.Failed) {
                Surface(
                    onClick = onRetrySubscribe,
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.height(28.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp)
                    ) {
                        Text(
                            text = "重试",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
            }

            // 顶栏右侧：最近文件入口（剧本 B）——显示本会话涉及的文件数，点开列表面板
            if (sessionFiles.isNotEmpty()) {
                Surface(
                    onClick = { showFiles = true },
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.height(28.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    ) {
                        Text(
                            text = "📁 ${sessionFiles.size}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold, fontSize = 11.sp
                            ),
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
            }

            // 顶栏右侧：执行模式胶囊（P0-B）—— 当前模式常驻可见，yolo 红底警示；点击弹出切换菜单
            ModeChip(
                currentMode = currentSessionMode,
                onSwitchMode = onSwitchMode,
            )

            // 顶栏右侧：模型切换胶囊（两级菜单：先选模型 → 再选思考档位）
            Box {
                var menuExpanded by remember { mutableStateOf(false) }
                // 当前处于档位选择阶段的模型（null = 正在选模型）
                var levelStageModel by remember { mutableStateOf<com.zcode.remote.relay.WorkspaceConfigChannel.ModelOption?>(null) }

                fun levelsOf(m: com.zcode.remote.relay.WorkspaceConfigChannel.ModelOption?): List<String> {
                    if (m == null) return emptyList()
                    val pid = m.providerId ?: return emptyList()
                    return modelReasoningLevels["$pid/${m.modelId()}"] ?: emptyList()
                }

                Surface(
                    onClick = { levelStageModel = null; menuExpanded = true },
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.height(28.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    ) {
                        Text(
                            text = meta.model?.substringAfterLast('/') ?: "模型",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 11.sp
                            ),
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.width(2.dp))
                        Text("▾", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false; levelStageModel = null }
                ) {
                    val stage = levelStageModel
                    if (stage == null) {
                        // ---- 阶段一：选模型 ----
                        if (availableModels.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("未读到备选模型目录", style = MaterialTheme.typography.bodySmall) },
                                onClick = {},
                                enabled = false
                            )
                        } else {
                            availableModels.forEach { m ->
                                val isSelected = m.name == meta.model || m.modelId() == meta.model
                                val levels = levelsOf(m)
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(m.name, style = MaterialTheme.typography.bodySmall)
                                            m.providerName?.let {
                                                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    },
                                    leadingIcon = {
                                        if (isSelected) {
                                            Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                        }
                                    },
                                    trailingIcon = {
                                        if (levels.isNotEmpty()) {
                                            Text("▸", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    },
                                    onClick = {
                                        if (levels.isEmpty()) {
                                            // 无推理档位的模型：直接切换
                                            menuExpanded = false
                                            onSwitchModel(m.copy(thought = null))
                                        } else {
                                            // 有档位：进入第二阶段
                                            levelStageModel = m
                                        }
                                    }
                                )
                            }
                        }
                    } else {
                        // ---- 阶段二：选思考档位 ----
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "← ${stage.name.substringAfterLast('/')}",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            },
                            onClick = { levelStageModel = null }
                        )
                        HorizontalDivider()
                        levelsOf(stage).forEach { lv ->
                            DropdownMenuItem(
                                text = { Text(thoughtLabel(lv), style = MaterialTheme.typography.bodySmall) },
                                onClick = {
                                    menuExpanded = false
                                    levelStageModel = null
                                    onSwitchModel(stage.copy(thought = lv))
                                }
                            )
                        }
                    }
                }
            }
        }

        // 反馈横幅提示
        approvalFeedback?.let { msg ->
            LaunchedEffect(msg) { onFeedbackSeen() }
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = msg,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }

        commandFeedback?.let { msg ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = msg,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }

        // 浮动审批提醒卡片（待决议置顶）
        approvals.forEach { ApprovalCard(it, onResolve) }

        // 表单交互卡片（AskUserQuestion / 计划确认）
        elicitations.forEach { el ->
            ElicitationCard(
                el = el,
                onAccept = { answers -> onElicitationAccept(el, answers) },
                onDecline = { onElicitationDecline(el) },
                onFreeText = { text -> onElicitationFreeText(el, text) },
            )
        }

        if (rows.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Text(
                        text = if (meta.phase == "draft") "新会话已就绪" else "等待会话同步…",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (meta.phase == "draft")
                            "在下方输入指令或语音输入，即可发送给 PC 端 Agent 开始探索与编码。"
                        else
                            "正在与桌面端 Agent 同步会话历史流…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            // 2. 消息流列表（外层 Box 承载悬浮「回到底部」提示胶囊）
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "earlier-head") {
                        Text(
                            text = when {
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
                        RowItem(row)
                        turnSummaries[row.rowId]?.let { summary ->
                            com.zcode.remote.ui.components.TurnChangesCard(
                                summary = summary,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    }
                }

                // 悬浮「回到底部」胶囊按钮（仅在用户主动上翻查阅历史且列表有内容时展示）
                androidx.compose.animation.AnimatedVisibility(
                    visible = !isNearBottom && rows.isNotEmpty(),
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 10.dp, end = 6.dp)
                ) {
                    Surface(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            // B-1：显式回到最新 = 解除「已上翻」意图锁，此后增量持续跟随
                            userScrolledAway = false
                            jumpingToBottom = true
                            coroutineScope.launch {
                                try {
                                    listState.animateScrollToItem(rows.lastIndex + headerCount)
                                } finally {
                                    // 丢弃跳转期间翻页逻辑可能设上的锚点（否则它随后会把视口拽回中段）
                                    anchorRowId = null
                                    jumpingToBottom = false
                                }
                            }
                        },
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        shadowElevation = 4.dp
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "回到底部",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "回到底部",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }
        }

        // 3. 附件条
        val context = LocalContext.current
        val filePicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            uri?.let { u ->
                inspectAttachment(context, u)?.let { (name, mime, size) ->
                    onAttachmentPicked(u, name, mime, size)
                }
            }
        }
        AttachmentBar(
            attachments = attachments,
            uploadName = attachUploadName,
            uploadPercent = attachUploadPercent,
            onPick = { filePicker.launch(arrayOf("*/*")) },
            onRemove = onRemoveAttachment,
        )

        // 3.5 常用快捷指令胶囊（减少软键盘输入成本）
        ActionChipsBar(
            onSelectChip = { chip ->
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                val current = prompt.trim()
                val next = if (current.isEmpty()) chip else "$current $chip"
                onPromptChange(next)
            },
            modifier = Modifier.padding(vertical = 2.dp)
        )

        // 4. 底部现代化输入栏
        InputBar(
            prompt = prompt,
            sending = sending,
            canStop = canStop,
            stopping = stopState == "stopping",
            canSend = prompt.isNotBlank() || attachments.isNotEmpty(),
            onPromptChange = onPromptChange,
            onSend = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onSend()
            },
            onStop = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onStop()
            },
            onPick = { filePicker.launch(arrayOf("*/*")) },
        )

        // 5. 「最近文件」面板（剧本 B）：列表 → 点选 → readTextFile 预览
        if (showFiles) {
            ModalBottomSheet(onDismissRequest = { showFiles = false }) {
                SessionFilesPanel(
                    files = sessionFiles,
                    preview = filePreview,
                    onPreview = onPreviewFile,
                    onDismissPreview = onDismissFilePreview,
                    onClose = { showFiles = false },
                )
            }
        }
    }
}

/**
 * 常用快捷指令胶囊栏（减少移动端软键盘输入成本）
 */
@Composable
private fun ActionChipsBar(
    onSelectChip: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chips = listOf(
        "继续",
        "运行测试验证",
        "修复该问题",
        "检查 Git 状态",
        "整理并提交"
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        chips.forEach { chipText ->
            Surface(
                onClick = { onSelectChip(chipText) },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                modifier = Modifier.height(26.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp)
                ) {
                    Text(
                        text = chipText,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * 顶栏执行模式胶囊（P0-B）：当前模式常驻可见，yolo 红底警示；点击弹出切换菜单。
 * 协议语义（HANDOVER §5.4）：setMode 只对下一轮 agent turn 生效，菜单内如实提示。
 */
@Composable
private fun ModeChip(
    currentMode: String?,
    onSwitchMode: (String) -> Unit,
) {
    val mode = currentMode ?: "build"
    Box {
        var menuExpanded by remember { mutableStateOf(false) }
        Surface(
            onClick = { menuExpanded = true },
            shape = RoundedCornerShape(14.dp),
            color = if (mode == "yolo") MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.height(28.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp)
            ) {
                Text(
                    text = if (mode == "yolo") "⚠ $mode" else mode,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp
                    ),
                    color = if (mode == "yolo") MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.tertiary,
                    maxLines = 1
                )
                Spacer(Modifier.width(2.dp))
                Text("▾", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            listOf("plan", "build", "yolo").forEach { m ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(modeDisplayName(m), style = MaterialTheme.typography.bodySmall)
                            if (m == "yolo") {
                                Text(
                                    "免审批：所有工具调用自动放行",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    },
                    leadingIcon = {
                        if (m == mode) {
                            Icon(
                                Icons.Default.Check, null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    },
                    onClick = {
                        menuExpanded = false
                        if (m != mode) onSwitchMode(m)
                    }
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
            DropdownMenuItem(
                text = {
                    Text(
                        "切换仅对下一轮对话生效；运行中的轮次权限不变",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                onClick = {},
                enabled = false
            )
        }
    }
}

private fun modeDisplayName(m: String): String = when (m) {
    "plan" -> "规划 plan（只读规划，不改文件）"
    "build" -> "构建 build（工具调用需审批）"
    "yolo" -> "全自动 yolo（免审批）"
    else -> m
}

/**
 * 「最近文件」面板（Sprint 3 第二步·剧本 B）：本会话涉及的文件列表 → 点选 →
 * `file.readTextFile` 预览（~ 路径经 resolvePath 展开）。只读，无任何写路径（§8 红线）。
 */
@Composable
private fun SessionFilesPanel(
    files: List<com.zcode.remote.relay.SessionFiles.Entry>,
    preview: AppViewModel.FilePreview?,
    onPreview: (String) -> Unit,
    onDismissPreview: () -> Unit,
    onClose: () -> Unit,
) {
    var selected by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(max = 540.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            if (selected != null) {
                TextButton(onClick = { onDismissPreview(); selected = null }) { Text("← 文件列表") }
            } else {
                Text(
                    "本会话涉及的文件",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.weight(1f)
                )
            }
            TextButton(onClick = { onDismissPreview(); onClose() }) { Text("关闭") }
        }

        when {
            selected == null -> {
                if (files.isEmpty()) {
                    Text(
                        "本会话暂无文件操作记录（Edit / Write / Read 等工具调用后会出现在这里）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                } else {
                    LazyColumn {
                        items(files.size) { idx ->
                            val f = files[idx]
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selected = f.path; onPreview(f.path) }
                                    .padding(vertical = 8.dp, horizontal = 4.dp)
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        f.path.substringAfterLast('/').substringAfterLast('\\'),
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        f.path,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace, fontSize = 10.sp
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    f.toolName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }

            else -> when (val p = preview) {
                is AppViewModel.FilePreview.Loading -> Row(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalArrangement = Arrangement.Center
                ) { CircularProgressIndicator(Modifier.size(28.dp)) }

                is AppViewModel.FilePreview.Loaded -> Column {
                    Text(
                        p.path,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace, fontSize = 10.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val scroll = rememberScrollState()
                        Text(
                            p.content.take(20_000),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp
                            ),
                            modifier = Modifier.padding(8.dp).horizontalScroll(scroll).heightIn(max = 380.dp)
                        )
                    }
                }

                is AppViewModel.FilePreview.Failed -> Text(
                    "读取失败：${p.message}\n\n$p.path",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 16.dp)
                )

                null -> Unit
            }
        }
    }
}

internal fun inspectAttachment(context: Context, uri: Uri): Triple<String, String, Long>? {
    var name = "attachment"
    var size = -1L
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
            if (c.moveToFirst()) {
                if (nameIdx >= 0) c.getString(nameIdx)?.let { name = it }
                if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
            }
        }
    }
    val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
    if (size < 0) {
        size = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                var total = 0L
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    total += n
                }
                total
            }
        }.getOrNull() ?: -1L
        if (size < 0) return null
    }
    return Triple(name, mime, size)
}

@Composable
private fun AttachmentBar(
    attachments: List<ConversationChannel.AttachmentRef>,
    uploadName: String?,
    uploadPercent: Int,
    onPick: () -> Unit,
    onRemove: (ConversationChannel.AttachmentRef) -> Unit,
) {
    if (attachments.isEmpty() && uploadName == null) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        uploadName?.let { name ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "上传中: $name ($uploadPercent%)",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                LinearProgressIndicator(
                    progress = { uploadPercent / 100f },
                    modifier = Modifier.width(80.dp),
                )
            }
        }
        attachments.forEach { a ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Share, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(
                        text = a.fileName,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = humanBytes(a.bytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    IconButton(
                        onClick = { onRemove(a) },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(Icons.Default.Close, null, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}

private fun humanBytes(n: Long): String = when {
    n < 1024 -> "${n}B"
    n < 1024 * 1024 -> "%.1fKB".format(n / 1024.0)
    else -> "%.1fMB".format(n / 1024.0 / 1024.0)
}

/**
 * 现代悬浮胶囊输入条
 */
@Composable
private fun InputBar(
    prompt: String,
    sending: Boolean,
    canStop: Boolean,
    stopping: Boolean,
    canSend: Boolean,
    onPromptChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onPick: () -> Unit,
) {
    var voiceListening by remember { mutableStateOf(false) }
    var voiceLive by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val voiceLine = if (voiceListening) "🎤 正在聆听: ${voiceLive ?: "…"}" else voiceLive
        voiceLine?.let { line ->
            LaunchedEffect(line) {
                if (line.startsWith("⚠️")) { delay(4000); voiceLive = null }
            }
            Text(
                text = line,
                style = MaterialTheme.typography.labelSmall,
                color = if (line.startsWith("⚠️")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            )
        }

        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            border = CardDefaults.outlinedCardBorder(),
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                // 附件选择
                IconButton(
                    onClick = onPick,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "添加附件",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 语音输入
                VoiceInputButton(
                    onFinalText = { text ->
                        onPromptChange(if (prompt.isBlank()) text else "$prompt $text")
                    },
                    onStateChange = { listening, live ->
                        voiceListening = listening
                        voiceLive = live
                    },
                )

                Spacer(Modifier.width(4.dp))

                // 文本输入
                OutlinedTextField(
                    value = prompt,
                    onValueChange = onPromptChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("发送指令到 PC 端 Agent…", style = MaterialTheme.typography.bodySmall) },
                    maxLines = 4,
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent
                    )
                )

                Spacer(Modifier.width(4.dp))

                // 发送 / 停止 变形按钮
                if (canSend) {
                    FilledIconButton(
                        onClick = onSend,
                        enabled = !sending,
                        modifier = Modifier.size(36.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        if (sending) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        } else {
                            Icon(Icons.Default.Send, contentDescription = "发送", modifier = Modifier.size(18.dp))
                        }
                    }
                } else if (canStop) {
                    FilledIconButton(
                        onClick = onStop,
                        enabled = !stopping,
                        modifier = Modifier.size(36.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "中断", modifier = Modifier.size(18.dp))
                    }
                } else {
                    IconButton(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = "发送",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ElicitationCard(
    el: PendingElicitation,
    onAccept: (Map<Int, List<String>>) -> Unit,
    onDecline: () -> Unit,
    onFreeText: (String) -> Unit,
) {
    val selected = remember(el.interactionId) { mutableStateMapOf<Int, MutableList<String>>() }
    var freeTextDraft by remember(el.interactionId) { mutableStateOf("") }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f))
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = when {
                    el.isPlanApproval -> "实施方案待批准"
                    el.questions.isNotEmpty() -> "提问 · 需要你的回答"
                    else -> "确认请求"
                },
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.tertiary
            )

            el.plan?.let {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = it.take(2000),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                        maxLines = 10,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            if (el.isPlanApproval) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onAccept(emptyMap()) }) { Text("批准方案") }
                    OutlinedButton(onClick = onDecline) { Text("拒绝") }
                }
                return@Column
            }

            el.questions.forEachIndexed { qIdx, q ->
                Text(q.question.ifBlank { q.header ?: "" }, style = MaterialTheme.typography.bodyMedium)
                q.options.forEach { opt ->
                    val chosen = opt.value in (selected[qIdx] ?: emptyList())
                    OutlinedButton(
                        onClick = {
                            val cur = selected.getOrPut(qIdx) { mutableListOf() }
                            if (q.multiSelect) {
                                if (chosen) cur.remove(opt.value) else cur.add(opt.value)
                                selected[qIdx] = cur
                            } else {
                                selected[qIdx] = mutableListOf(opt.value)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = if (chosen) ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        ) else ButtonDefaults.outlinedButtonColors()
                    ) {
                        Text(opt.label, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (el.freeText) {
                OutlinedTextField(
                    value = freeTextDraft,
                    onValueChange = { freeTextDraft = it },
                    placeholder = { Text("输入自定义回答…", style = MaterialTheme.typography.bodySmall) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    maxLines = 3
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val answers = selected.mapValues { (_, list) -> list.toList() }.toMutableMap()
                        if (el.freeText && freeTextDraft.isNotBlank()) {
                            val cur = answers.getOrPut(0) { emptyList() }
                            answers[0] = cur + freeTextDraft.trim()
                        }
                        onAccept(answers.filter { it.value.isNotEmpty() })
                    },
                    enabled = selected.isNotEmpty() || freeTextDraft.isNotBlank()
                ) { Text("提交回答") }
                OutlinedButton(onClick = onDecline) { Text("拒绝") }
            }
        }
    }
}

@Composable
private fun ApprovalCard(
    a: PendingApproval,
    onResolve: (PendingApproval, ApprovalOption) -> Unit,
) {
    // B-3：会话内审批按钮与 ApprovalsTab 保持一致，点击给一次触觉反馈
    val haptic = LocalHapticFeedback.current
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(ZCodeTokens.StatusPending.copy(alpha = 0.6f))
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "权限审批 · ${a.toolName ?: "Command"}",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = ZCodeTokens.StatusPending
                )
                Spacer(Modifier.weight(1f))
                a.autoResolveAt?.let {
                    val sec = ((it - System.currentTimeMillis()).coerceAtLeast(0) / 1000).toInt()
                    Text("${sec}s 自动决议", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            a.summary?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            a.detail?.let {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(it.take(500), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp), modifier = Modifier.padding(6.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                a.options.forEach { opt ->
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onResolve(a, opt)
                        },
                        modifier = Modifier.weight(1f).height(34.dp),
                        contentPadding = PaddingValues(0.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (opt.isAllow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(
                            when {
                                opt.kind == "allowOnce" -> "允许一次"
                                opt.kind == "allowAlways" -> "总是允许"
                                opt.isDeny -> "拒绝"
                                else -> opt.label.ifBlank { "选项" }
                            },
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RowItem(row: ConversationRow) = when (row.kind) {
    "userInput" -> UserBubble(row)
    "assistantText" -> Bubble(row.text.orEmpty(), isUser = false, streaming = row.isStreaming)
    "reasoning" -> ReasoningBlock(row)
    "toolCall" -> ToolCallCard(row)
    "turnHeader" -> TurnHeaderRow(row)
    else -> PlainRow(row)
}

/**
 * 用户消息：附件 chip（若有）在气泡上方，整体右对齐。
 *
 * 附件来自服务端在 userInput 行的回显（`{ref, fileName, mime, bytes}`），
 * 即「手机上选文件→发送」之后该消息在会话流里的呈现方式与官方客户端一致。
 * 历史消息仅展示：`ref` 是 host 侧暂存引用而非工作区路径，故不做点击预览（见 [RowAttachment]）。
 */
@Composable
private fun UserBubble(row: ConversationRow) {
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
                        // 与输入栏附件 chip 保持同一图标（本工程只有 material-icons-core，
                        // 无 Description / AttachFile 等扩展图标；C-3 已登记统一替换该图标）
                        imageVector = Icons.Default.Share,
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
        Bubble(row.text.orEmpty(), isUser = true)
    }
}

@Composable
private fun Bubble(text: String, isUser: Boolean, streaming: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (isUser) {
            Surface(
                modifier = Modifier.widthIn(max = 310.dp),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
                color = MaterialTheme.colorScheme.primary,
                shadowElevation = 1.dp
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onPrimary),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        } else {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = CardDefaults.outlinedCardBorder()
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    MarkdownView(
                        markdown = text,
                        modifier = Modifier.fillMaxWidth(),
                        textColor = MaterialTheme.colorScheme.onSurface
                    )
                    if (streaming) {
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "正在生成…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 官方 ZCode 风格深度思考折叠胶囊 (ReasoningBlock)
 */
@Composable
private fun ReasoningBlock(row: ConversationRow) {
    var expanded by remember { mutableStateOf(false) }
    val content = row.text.orEmpty().trim()
    if (content.isEmpty()) return
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                // 紫色思考轨迹圆点
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(ZCodeTokens.ReasoningTrajectoryDark)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "思考过程 (${content.length} 字符)",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                    color = ZCodeTokens.ReasoningTrajectoryDark,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    modifier = Modifier.height(26.dp)
                ) {
                    Text(if (expanded) "收起" else "展开", style = MaterialTheme.typography.labelSmall)
                }
                if (expanded) {
                    TextButton(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("reasoning", content))
                            Toast.makeText(context, "思考过程已复制", Toast.LENGTH_SHORT).show()
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        modifier = Modifier.height(26.dp)
                    ) {
                        Text("复制", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, start = 4.dp)
            ) {
                // 左侧微弱竖向引导线（官方同款）
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(ZCodeTokens.ReasoningTrajectoryDark.copy(alpha = 0.35f))
                )
                Spacer(Modifier.width(8.dp))
                MarkdownView(
                    markdown = content,
                    modifier = Modifier.weight(1f),
                    textColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 官方 ZCode 风格紧凑工具调用卡片 (ToolCallBlock)
 */
@Composable
private fun ToolCallCard(row: ConversationRow) {
    val tool = row.toolName?.trim().orEmpty()
    val input = row.inputText?.trim().orEmpty()
    val output = row.outputText?.trim().orEmpty()
    if (tool.isEmpty() && input.isEmpty() && output.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }
    // Sprint 3：写类工具（Edit/Write/MultiEdit）从 inputText 解析红绿 diff —— 纯客户端，零 RPC
    val toolDiff = remember(row.rowId, input) {
        runCatching { com.zcode.remote.ui.components.ToolDiffParser.parse(row.toolName, input) }.getOrNull()
    }
    val tone = when (row.status) {
        "success" -> ZCodeTokens.StatusOnline
        "error", "cancelled" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }

    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder(),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    color = ZCodeTokens.ToolCallTrajectoryDark.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = tool.ifEmpty { "Command" },
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = ZCodeTokens.ToolCallTrajectoryDark,
                            fontWeight = FontWeight.SemiBold
                        ),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = when (row.status) {
                        "success" -> "✓ 成功"
                        "error" -> "✗ 失败"
                        "running" -> "执行中…"
                        "cancelled" -> "已取消"
                        else -> row.status ?: ""
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = tone,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text(if (expanded) "收起" else "详情", style = MaterialTheme.typography.labelSmall)
                }
            }

            val summary = input.replace('\n', ' ').trim()
            if (!expanded && toolDiff != null) {
                // 折叠态：写类工具直接显示 文件 + 增删统计，一眼判断这次改动是否越界
                Text(
                    text = buildString {
                        toolDiff.filePath?.let { append("📄 $it  ") }
                        append("+${toolDiff.added} −${toolDiff.removed}")
                    },
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            } else if (!expanded && summary.isNotEmpty()) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(top = 6.dp)) {
                    if (toolDiff != null) {
                        com.zcode.remote.ui.components.DiffBlock(toolDiff)
                        Text(
                            "原始输入",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                    CodeBlock("输入", input)
                    CodeBlock("输出", output)
                }
            }
        }
    }
}

@Composable
private fun CodeBlock(label: String, content: String?) {
    if (content.isNullOrBlank()) return
    Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = content.take(4000),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, lineHeight = 16.sp),
            modifier = Modifier.padding(8.dp),
        )
    }
}

@Composable
private fun TurnHeaderRow(row: ConversationRow) {
    HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
    Text(
        text = listOfNotNull(
            row.state?.let { phaseLabel(it) },
            row.createdAt?.let { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(it)) },
        ).joinToString(" · "),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PlainRow(row: ConversationRow) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(Modifier.padding(8.dp)) {
            Text("${row.kind} #${row.rowId}", style = MaterialTheme.typography.labelSmall)
            row.text?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis) }
        }
    }
}

private fun statusLabel(s: ConversationChannel.Status) = when (s) {
    ConversationChannel.Status.Idle -> "未订阅"
    ConversationChannel.Status.Hello -> "握手中…"
    ConversationChannel.Status.Initialized -> "订阅中…"
    is ConversationChannel.Status.Live -> "已连接"
    is ConversationChannel.Status.Failed -> "异常：${s.reason.take(50)}"
}

private fun phaseLabel(p: String) = when (p) {
    "completedSuccess" -> "已完成"
    "completedInterrupted" -> "已中断"
    "completedError" -> "执行出错"
    "idle" -> "空闲"
    "running" -> "运行中"
    "waitingUserInput" -> "等待输入"
    "aborted" -> "已中止"
    else -> p
}
