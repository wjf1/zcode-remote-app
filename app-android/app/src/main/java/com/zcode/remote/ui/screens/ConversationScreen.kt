package com.zcode.remote.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zcode.remote.ui.components.QueuePendingStrip
import com.zcode.remote.ui.components.SessionStatusPanel
import com.zcode.remote.ui.components.StatusPanelLabels
import kotlinx.coroutines.launch

/**
 * 沉浸式会话详情与对话控制台
 * 对标官方 ZCode 桌面端排版与交互规范，支持流式渲染、官方同款思考过程与工具调用。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    ui: ConversationUiState,
    actions: ConversationAction,
) = with(ui) {
    with(actions) {
        val listState = rememberLazyListState()
        val headerCount = if (rows.isEmpty()) 0 else 1
        // 剧本 B「最近文件」：从会话行派生文件清单（纯客户端零协议）
        // C-5④：解析缓存跨 rows 版本存活——流式期只解析新增/变化的行（增量），
        // 未变化的行复用上次结果；缓存随本 composable 销毁（切会话即重置）。
        val filesParseCache =
            remember {
                com.zcode.remote.relay.SessionFiles
                    .PathCache()
            }
        val turnDiffCache =
            remember {
                com.zcode.remote.relay.TurnChanges
                    .DiffCache()
            }
        val sessionFiles =
            remember(rows) {
                com.zcode.remote.relay.SessionFiles
                    .extract(rows, filesParseCache)
            }
        // Sprint 3 第三步：从会话行按 Turn 聚合文件写操作 Diff
        val turnSummaries =
            remember(rows) {
                com.zcode.remote.relay.TurnChanges
                    .aggregate(rows, turnDiffCache)
            }
        var showFiles by remember { mutableStateOf(false) }
        // C3：会话状态面板弹层
        var showStatus by remember { mutableStateOf(false) }

        // 折叠态提升到屏幕层：LazyColumn 会回收滚出视野的行，行内 remember 随之销毁，
        // 表现为「滚上去再滚回来，刚展开的详情自己合上了」。键优先取 toolCallId（工具行唯一且跨重放稳定），
        // 无则退化为 `kind:rowId`。用 List 而不是 Set，是因为 rememberSaveable 要把值写进 Bundle，
        // 具体集合类型（Set 的实现类）跨进程恢复时不保证可反序列化，List 则一定有 ArrayList 兜底。
        var expandedRows by rememberSaveable { mutableStateOf(listOf<String>()) }
        val toggleExpanded: (String) -> Unit = { key ->
            expandedRows = if (key in expandedRows) expandedRows - key else expandedRows + key
        }

        // 进入会话页即刷新一次模型目录（PC 端新增/删除模型后回到 APP 即可看到最新列表）
        LaunchedEffect(Unit) { onLoadModels() }

        val haptic = LocalHapticFeedback.current
        val coroutineScope = rememberCoroutineScope()

        // B-3：附件上传成/败触觉。上传完成是 VM 侧异步驱动的，Compose 没有点击上下文，
        // 故由 attachmentFeedback.tick 变化触发一次（沿用本文件既有的 LocalHapticFeedback 路径）。
        LaunchedEffect(attachmentFeedback?.tick) {
            val fb = attachmentFeedback ?: return@LaunchedEffect
            haptic.performHapticFeedback(
                if (fb.ok) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove,
            )
        }
        // 智能贴底状态：用户是否在列表底部附近（距离末尾 <= 2 项，或无法向前滚动）
        val isNearBottom by remember {
            derivedStateOf {
                val layoutInfo = listState.layoutInfo
                val total = layoutInfo.totalItemsCount
                if (total <= 1) {
                    true
                } else {
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
                    ConversationScrollPolicy.Trigger.Enter,
                    rows.size,
                    paginationInFlight,
                    pinnedThisSession,
                    userScrolledAway,
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
                    ConversationScrollPolicy.Trigger.SnapshotAligned,
                    rows.size,
                    paginationInFlight,
                    pinnedThisSession,
                    userScrolledAway,
                )
            ) {
                listState.scrollToItem(rows.lastIndex + headerCount)
                pinnedThisSession = true
            }
        }

        // 3) 流式增量跟随：以「末行 rowId + 文本长度」为键 —— 流式 appendText 只替换单行、
        //    size 不变，故不能再用 rows.size 作触发键。只要用户未主动上翻就持续跟随。
        //    C-6：本地 pending 气泡优先参与（发送瞬间它才是视觉末行，不跟随就会出现在屏幕外）。
        val lastRowKey =
            pendingUserMessages.lastOrNull()?.let { -1 to it.text.length }
                ?: rows.lastOrNull()?.let { it.rowId to (it.text?.length ?: -1) }
        LaunchedEffect(lastRowKey) {
            if (lastRowKey == null) return@LaunchedEffect
            if (ConversationScrollPolicy.shouldPinToLatest(
                    ConversationScrollPolicy.Trigger.Delta,
                    rows.size,
                    paginationInFlight,
                    pinnedThisSession,
                    userScrolledAway,
                ) &&
                (rows.isNotEmpty() || pendingUserMessages.isNotEmpty())
            ) {
                listState.animateScrollToItem(rows.lastIndex + headerCount + pendingUserMessages.size)
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
            modifier =
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 1. 顶栏：标准返回导航键 + 标题 + 紧凑元数据（§11.7 拆分：槽位实现见 ConversationTopBar.kt）
            ConversationTopBar(
                title = title,
                status = status,
                meta = meta,
                approvalsCount = approvals.size,
                sessionFileCount = sessionFiles.size,
                statusEntryLabel = StatusPanelLabels.entryLabel(sessionState),
                currentSessionMode = currentSessionMode,
                availableModels = availableModels,
                modelReasoningLevels = modelReasoningLevels,
                onBack = onBack,
                onRetrySubscribe = onRetrySubscribe,
                onSwitchMode = onSwitchMode,
                onSwitchModel = onSwitchModel,
                onOpenFiles = { showFiles = true },
                onOpenStatus = { showStatus = true },
            )

            // 统一反馈横幅（C-11：审批反馈与操作反馈合并为单队列，配色按失败/提示语义区分，
            // 由 VM 的 flash 统一消退；原两条并排横幅 + 审批 Tab 文案滞留的缺陷一并消除）
            commandFeedback?.let { msg ->
                val failure = feedbackIsFailure
                Surface(
                    color =
                        if (failure) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.labelMedium,
                        color =
                            if (failure) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            },
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }

            // 会话级错误原因（快照 control.lastError）：桌面端列表会显示原因，App 端此前只有「异常」二字。
            // 放在反馈横幅之后、审批卡片之前——进入会话第一眼就能看到为什么出错。
            meta.lastError?.let { reason ->
                val context = LocalContext.current
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = reason,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(6.dp))
                        // 原文可复制（报障 / 排查用），沿用本文件「思考过程已复制」的既有模式
                        TextButton(
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("lastError", reason))
                                Toast.makeText(context, "错误原因已复制", Toast.LENGTH_SHORT).show()
                            },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                            modifier = Modifier.height(24.dp),
                        ) {
                            Text("复制", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
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
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .size(54.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Send,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                        Text(
                            text = if (meta.phase == "draft") "新会话已就绪" else "等待会话同步…",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text =
                                if (meta.phase == "draft") {
                                    "在下方输入指令或语音输入，即可发送给 PC 端 Agent 开始探索与编码。"
                                } else {
                                    "正在与桌面端 Agent 同步会话历史流…"
                                },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            } else {
                // 2. 消息流列表（外层 Box 承载悬浮「回到底部」提示胶囊；实现见 ConversationTimeline.kt）
                ConversationTimelineArea(
                    modifier = Modifier.weight(1f),
                    listState = listState,
                    rows = rows,
                    pendingUserMessages = pendingUserMessages,
                    earlier = earlier,
                    turnSummaries = turnSummaries,
                    expandedRows = expandedRows,
                    toggleExpanded = toggleExpanded,
                    isNearBottom = isNearBottom,
                    onJumpToBottom = {
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
                )
            }

            // 3. 附件条
            val context = LocalContext.current
            val filePicker =
                rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument(),
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
                failedUpload = attachFailed,
                onCancelUpload = onCancelUpload,
                onRetryUpload = onRetryUpload,
                onDismissUploadFailure = onDismissUploadFailure,
            )

            // 3.5 常用快捷指令胶囊（减少软键盘输入成本）
            ActionChipsBar(
                onSelectChip = { chip ->
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    val current = prompt.trim()
                    val next = if (current.isEmpty()) chip else "$current $chip"
                    onPromptChange(next)
                },
                modifier = Modifier.padding(vertical = 2.dp),
            )

            // C3：待发送队列条（只读）—— 仅在有排队输入时占位，纯告知不做控制
            QueuePendingStrip(
                itemCount = sessionState.queue?.itemCount ?: 0,
                modifier = Modifier.padding(vertical = 2.dp),
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

            // 6. 会话状态面板（C3）：纯只读，分区无数据自动隐藏
            if (showStatus) {
                ModalBottomSheet(onDismissRequest = { showStatus = false }) {
                    SessionStatusPanel(
                        state = sessionState,
                        onClose = { showStatus = false },
                    )
                }
            }
        }
    }
}
