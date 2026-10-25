package com.zcode.remote

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zcode.remote.notify.NotificationPermission
import com.zcode.remote.relay.ApprovalOption
import com.zcode.remote.relay.PendingApproval
import com.zcode.remote.relay.SessionItem
import com.zcode.remote.relay.approvalsForSession
import com.zcode.remote.relay.elicitationsForSession
import com.zcode.remote.storage.PairedDevice
import com.zcode.remote.storage.QrParseResult
import com.zcode.remote.storage.QrParser
import com.zcode.remote.ui.screens.*
import com.zcode.remote.ui.theme.ZCodeTheme
import com.zcode.remote.ui.theme.statusPendingTone
import com.zcode.remote.util.resolve

class MainActivity : ComponentActivity() {
    private var appVm: AppViewModel? = null

    /**
     * C-10：深链配对的待确认设备。此前协议链接（zcode://pair 等）被直接 `vm.pair()`——
     * 误点链接即静默替换当前设备连接，没有任何确认环节。
     */
    private var pendingPair by mutableStateOf<PairedDevice?>(null)

    /**
     * V2（ZCode ≥3.16）登录配对中的设备：新版二维码无 hash，凭据由登录链路下发
     * （PROTOCOL.md §11）。非空时渲染 [LoginWebViewDialog]。
     */
    private var pendingLogin by mutableStateOf<PairedDevice?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
        handleDeepLink(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: AppViewModel = viewModel()
            appVm = vm
            LaunchedEffect(Unit) {
                // 等首帧布局完成再处理深链/分享：冷启动 + 深链同时发生时（重装后 am start），
                // 立即弹出 Compose Dialog 会导致 Dialog window 内容整体不渲染（真机实证 2026-10-25：
                // 整窗纯背景色，连标题文字都不上屏）。
                androidx.compose.runtime.withFrameNanos { }
                handleShareIntent(intent)
                handleDeepLink(intent)
            }
            ZCodeTheme(
                forceDark =
                    when (vm.themeMode) {
                        "dark" -> true
                        "light" -> false
                        else -> null
                    },
            ) {
                Surface(Modifier.fillMaxSize()) {
                    var scanning by rememberSaveable { mutableStateOf(false) }
                    // 当前打开的会话：**存 taskId（可 saveable）** 而非整条 SessionItem ——
                    // 否则旋转屏/进程回收重建 Activity 时 opened 丢失，用户被从会话页踢回列表。
                    // 完整条目由 [openedItem] 缓存（新建会话回调早于列表刷新时仍需标题等元数据），
                    // 旋转重建后 openedItem 为空，则由 sessions 按 taskId 回查（离线缓存已秒开列表）。
                    var openedTaskId by rememberSaveable { mutableStateOf<String?>(null) }
                    var openedItem by remember { mutableStateOf<SessionItem?>(null) }
                    var showGuide by rememberSaveable { mutableStateOf(false) }
                    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

                    fun openSession(target: SessionItem) {
                        openedItem = target
                        openedTaskId = target.taskId
                    }

                    fun closeSession() {
                        openedItem = null
                        openedTaskId = null
                    }

                    // 配对后主动申请一次通知权限（Android 13+ 运行时权限，默认拒绝）。
                    // 审批通知是本 App 的招牌能力；不在运行时申请，装机后锁屏审批全部静默失效。
                    // 仅尝试一次（rememberSaveable 跨重建保持），被拒绝后由设置页的状态行兜底引导——
                    // 不重复弹框、不打断用户既有操作流。
                    var notifPermRequested by rememberSaveable { mutableStateOf(false) }
                    val notifPermLauncher =
                        androidx.activity.compose.rememberLauncherForActivityResult(
                            androidx.activity.result.contract.ActivityResultContracts
                                .RequestPermission(),
                        ) { /* 结果由设置页状态行在 ON_RESUME 时读取，这里不额外处理 */ }
                    LaunchedEffect(vm.device, notifPermRequested) {
                        if (vm.device != null && shouldRequestNotificationPermission() && !notifPermRequested) {
                            notifPermRequested = true
                            notifPermLauncher.launch(NotificationPermission.PERMISSION)
                        }
                    }

                    // 全局物理返回键拦截（防止直接退出应用）
                    BackHandler(enabled = openedTaskId != null || showGuide || scanning || pendingLogin != null) {
                        when {
                            pendingLogin != null -> pendingLogin = null
                            openedTaskId != null -> closeSession()
                            showGuide -> showGuide = false
                            scanning && vm.device != null -> scanning = false
                        }
                    }

                    // C-10：深链配对确认弹窗——协议链接不再静默替换当前设备连接
                    pendingPair?.let { dev ->
                        AlertDialog(
                            onDismissRequest = { pendingPair = null },
                            title = { Text("确认配对设备") },
                            text = {
                                Text(
                                    "链接中的设备：${dev.deviceName ?: dev.deviceSid}\n\n" +
                                        "确认后将连接到该设备，当前设备的连接会被替换。",
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    vm.pair(dev)
                                    pendingPair = null
                                    android.widget.Toast
                                        .makeText(
                                            this@MainActivity,
                                            "已通过链接配对：${dev.deviceName ?: dev.deviceSid}",
                                            android.widget.Toast.LENGTH_SHORT,
                                        ).show()
                                }) { Text("配对") }
                            },
                            dismissButton = {
                                TextButton(onClick = { pendingPair = null }) { Text("取消") }
                            },
                        )
                    }

                    // V2（≥3.16）登录配对：新版二维码无 hash，WebView 走官方登录链路补凭据
                    pendingLogin?.let { dev ->
                        LoginWebViewDialog(
                            device = dev,
                            onDismiss = { pendingLogin = null },
                            onLoggedIn = { completed ->
                                pendingLogin = null
                                vm.pair(completed)
                                android.widget.Toast
                                    .makeText(
                                        this@MainActivity,
                                        "登录完成，正在连接：${completed.deviceName ?: completed.deviceSid}",
                                        android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                            },
                        )
                    }

                    // 旋转/进程重建后 openedItem 为空：按 taskId 从列表回查（拿标题等元数据）
                    val target =
                        openedItem
                            ?: openedTaskId?.let { id -> vm.sessions.firstOrNull { it.taskId == id } }
                    when {
                        // 1. 保活指引全屏页
                        showGuide -> KeepAliveGuideScreen(onBack = { showGuide = false })

                        // 2. 扫码配对全屏页
                        vm.device == null || scanning ->
                            ScanScreen(
                                onPaired = {
                                    vm.pair(it)
                                    scanning = false
                                },
                                onNeedsLogin = {
                                    pendingLogin = it
                                    scanning = false
                                },
                            )

                        // 3. 沉浸式会话详情页
                        // B-1：用会话 id 作 key —— listState / 滚动锚点 / 贴底标记不跨会话复用，
                        // 否则「A 停在顶部 → 进 B」会沿用 A 的偏移量（进 B 不停在最新行）。
                        target != null ->
                            androidx.compose.runtime.key(target.taskId) {
                                ConversationScreen(
                                    ui =
                                        ConversationUiState(
                                            title = vm.conversationMeta.title ?: target.title,
                                            status = vm.conversationStatus,
                                            meta = vm.conversationMeta,
                                            rows = vm.rowsSnapshot,
                                            pendingUserMessages = vm.pendingUserMessages,
                                            // 跨会话隔离（2026-10-09 真机反馈）：待处理项合并了
                                            // 「任务事件流（全 workspace）」与「会话流（当前订阅）」两路，
                                            // 会话页只能渲染属于本会话的卡，否则 B 会话会弹出 A 会话的审批。
                                            approvals = approvalsForSession(vm.approvals, target.taskId),
                                            elicitations = elicitationsForSession(vm.elicitations, target.taskId),
                                            earlier = vm.earlier,
                                            sessionState = vm.sessionState,
                                            prompt = vm.promptDraft,
                                            sending = vm.sending,
                                            canStop = vm.conversationMeta.canStop == true,
                                            stopState = vm.conversationMeta.stopState,
                                            commandFeedback = vm.commandFeedback,
                                            feedbackIsFailure = vm.feedbackIsFailure,
                                            attachments = vm.attachments.toList(),
                                            attachUploadName = vm.attachUpload?.name,
                                            attachUploadPercent = vm.attachUpload?.percent ?: 0,
                                            attachFailed = vm.attachFailed,
                                            availableModels = vm.allAvailableModels,
                                            modelReasoningLevels = vm.modelReasoningLevels,
                                            currentSessionMode =
                                                vm.sessionModeOverride
                                                    ?: vm.sessionModeFromWorkspace,
                                            filePreview = vm.filePreview,
                                            snapshotAligned = vm.snapshotAligned,
                                            attachmentFeedback = vm.attachmentFeedback,
                                        ),
                                    actions =
                                        ConversationAction(
                                            onBack = { closeSession() },
                                            onCancelUpload = { vm.cancelAttachmentUpload() },
                                            onRetryUpload = { vm.retryAttachmentUpload() },
                                            onDismissUploadFailure = { vm.dismissAttachFailure() },
                                            onResolve = { a: PendingApproval, opt: ApprovalOption -> vm.resolve(a, opt) },
                                            onElicitationAccept = { el, answers -> vm.answerElicitation(el, answers) },
                                            onElicitationDecline = { vm.declineElicitation(it) },
                                            onElicitationFreeText = { el, text -> vm.answerElicitationFreeText(el, text) },
                                            onLoadEarlier = { vm.loadEarlier() },
                                            onPromptChange = { vm.updatePromptDraft(it) },
                                            onSend = { vm.sendPrompt() },
                                            onStop = { vm.stopSession() },
                                            onAttachmentPicked = { uri, name, mime, size -> vm.addAttachment(uri, name, mime, size) },
                                            onRemoveAttachment = { vm.removeAttachment(it) },
                                            onSwitchModel = { m -> vm.switchCurrentSessionModel(m) },
                                            onLoadModels = { vm.loadWorkspaceModels() },
                                            // ⚠️ 执行模式勿取 ConversationChannel.Status.Live.mode——那是订阅模式
                                            // （snapshot/live），与执行模式（plan/build/yolo）撞名（真机验收 2026-10-05 实证）
                                            onSwitchMode = { m ->
                                                vm.setSessionMode(m) { err ->
                                                    if (err != null) {
                                                        // C-2：exec mode 切换失败同样经错误映射，不直出裸英文
                                                        android.widget.Toast
                                                            .makeText(
                                                                this@MainActivity,
                                                                "切换失败：${com.zcode.remote.relay.UserFacingError.map(err)}",
                                                                android.widget.Toast.LENGTH_SHORT,
                                                            ).show()
                                                    }
                                                }
                                            },
                                            onPreviewFile = { vm.previewSessionFile(it) },
                                            onDismissFilePreview = { vm.dismissFilePreview() },
                                            onRetrySubscribe = { vm.retrySubscribe() },
                                        ),
                                )
                            }

                        // 4. 底部 3-Tab 主工作台框架
                        else -> {
                            val totalPending =
                                vm.approvals.size + vm.elicitations.size +
                                    vm.sessionPending.values.sum()

                            Scaffold(
                                bottomBar = {
                                    NavigationBar(
                                        modifier = Modifier.navigationBarsPadding(),
                                        containerColor = MaterialTheme.colorScheme.surface,
                                        tonalElevation = 4.dp,
                                    ) {
                                        // Tab 0: 会话列表
                                        NavigationBarItem(
                                            selected = selectedTab == 0,
                                            onClick = { selectedTab = 0 },
                                            icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "会话") },
                                            label = {
                                                Text(
                                                    "会话",
                                                    fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal,
                                                )
                                            },
                                        )

                                        // Tab 1: 待办与审批 (带Badge)
                                        NavigationBarItem(
                                            selected = selectedTab == 1,
                                            onClick = { selectedTab = 1 },
                                            icon = {
                                                BadgedBox(
                                                    badge = {
                                                        if (totalPending > 0) {
                                                            Badge(
                                                                containerColor = statusPendingTone(),
                                                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                                            ) {
                                                                Text(if (totalPending > 99) "99+" else "$totalPending")
                                                            }
                                                        }
                                                    },
                                                ) {
                                                    Icon(Icons.Default.Notifications, contentDescription = "待办与审批")
                                                }
                                            },
                                            label = {
                                                Text(
                                                    "待办",
                                                    fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal,
                                                )
                                            },
                                        )

                                        // Tab 2: 设置与设备
                                        NavigationBarItem(
                                            selected = selectedTab == 2,
                                            onClick = { selectedTab = 2 },
                                            icon = { Icon(Icons.Default.Settings, contentDescription = "设置") },
                                            label = {
                                                Text(
                                                    "设置",
                                                    fontWeight = if (selectedTab == 2) FontWeight.Bold else FontWeight.Normal,
                                                )
                                            },
                                        )
                                    }
                                },
                            ) { innerPadding ->
                                AnimatedContent(
                                    targetState = selectedTab,
                                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                                    label = "tab_switch",
                                    modifier = Modifier.padding(innerPadding),
                                ) { tab ->
                                    when (tab) {
                                        0 ->
                                            HomeScreen(
                                                deviceName = vm.device?.deviceName ?: "",
                                                state = vm.relayState,
                                                sessions = vm.sessions.toList(),
                                                sessionPending = vm.sessionPending,
                                                query = vm.sessionQuery,
                                                onQueryChange = { vm.updateSessionQuery(it) },
                                                subscribedSessionId = vm.subscribedSessionId,
                                                desktopActiveTaskId = vm.desktopActiveTaskId,
                                                onSessionClick = { s ->
                                                    vm.openSession(s)
                                                    openSession(s)
                                                },
                                                onNavigateToSettings = { selectedTab = 2 },
                                                modelState = vm.workspaceModelState,
                                                modelsLoading = vm.modelStateLoading,
                                                onLoadModels = { vm.loadWorkspaceModels() },
                                                currentModel = vm.conversationMeta.model,
                                                currentProvider = vm.conversationMeta.provider,
                                                availableModels = vm.allAvailableModels,
                                                modelReasoningLevels = vm.modelReasoningLevels,
                                                onSessionDelete = { vm.deleteSession(it) },
                                                onCreateSession = { prompt, modelOption, execMode ->
                                                    vm.createNewSession(
                                                        firstPrompt = prompt,
                                                        attachments = emptyList(),
                                                        modelOption = modelOption,
                                                        execMode = execMode,
                                                        onSuccess = { item -> openSession(item) },
                                                        onError = { err ->
                                                            android.widget.Toast
                                                                .makeText(
                                                                    this@MainActivity,
                                                                    err.resolve(this@MainActivity),
                                                                    android.widget.Toast.LENGTH_SHORT,
                                                                ).show()
                                                        },
                                                    )
                                                },
                                            )

                                        1 ->
                                            ApprovalsTab(
                                                approvals = vm.approvals,
                                                elicitations = vm.elicitations,
                                                sessions = vm.sessions.toList(),
                                                sessionPending = vm.sessionPending,
                                                subscribedSessionId = vm.subscribedSessionId,
                                                feedback = vm.commandFeedback,
                                                feedbackIsFailure = vm.feedbackIsFailure,
                                                onResolveApproval = { a, opt -> vm.resolve(a, opt) },
                                                onAcceptElicitation = { el, answers -> vm.answerElicitation(el, answers) },
                                                onDeclineElicitation = { vm.declineElicitation(it) },
                                                onFreeTextElicitation = { el, text -> vm.answerElicitationFreeText(el, text) },
                                                onOpenSession = { s ->
                                                    vm.openSession(s)
                                                    openSession(s)
                                                },
                                            )

                                        2 ->
                                            SettingsTab(
                                                deviceName = vm.device?.deviceName ?: "",
                                                state = vm.relayState,
                                                bridgeState = vm.bridgeState,
                                                devices = vm.devices,
                                                activeSid = vm.device?.deviceSid,
                                                endpointMode = vm.endpointMode,
                                                customRelayUrl = vm.customRelayUrl,
                                                themeMode = vm.themeMode,
                                                updateState = vm.updateState,
                                                hasGithubToken = vm.hasGithubToken,
                                                onSwitchDevice = { vm.switchDevice(it) },
                                                onRemoveDevice = { vm.removeDevice(it) },
                                                onDisconnect = { vm.disconnect() },
                                                onRescan = {
                                                    closeSession()
                                                    scanning = true
                                                },
                                                onEndpointChange = { m, u -> vm.setEndpoint(m, u) },
                                                onThemeChange = { m -> vm.setTheme(m) },
                                                onShowGuide = { showGuide = true },
                                                onSetGithubToken = { vm.updateGithubToken(it) },
                                                onCheckUpdate = { vm.checkForUpdate() },
                                            )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 是否应主动申请通知权限：仅 Android 13+ 且当前确实未授予时。
     * 抽成方法以便 Compose 条件保持简单（detekt 条件复杂度门禁），并集中判定口径。
     */
    private fun shouldRequestNotificationPermission(): Boolean =
        NotificationPermission.isRuntimeRequestRequired(android.os.Build.VERSION.SDK_INT) &&
            NotificationPermission.shouldPrompt(this)

    private fun handleShareIntent(intent: Intent?) {
        if (intent == null || intent.action != Intent.ACTION_SEND) return
        val vm = appVm ?: return
        val type = intent.type ?: return
        if (type.startsWith("text/")) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            if (text.isNotBlank()) {
                vm.updatePromptDraft(text)
                android.widget.Toast
                    .makeText(this, "已填充分享文本到输入草稿", android.widget.Toast.LENGTH_SHORT)
                    .show()
            }
        } else {
            val uri =
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                }
            if (uri != null) {
                inspectAttachment(this, uri)?.let { (name, mime, size) ->
                    vm.addAttachment(uri, name, mime, size)
                    android.widget.Toast
                        .makeText(this, "已添加分享附件: $name", android.widget.Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }
    }

    private fun handleDeepLink(intent: Intent?) {
        if (intent == null || intent.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        val rawUrl = uri.toString()
        when (val r = QrParser.parseResult(rawUrl)) {
            // C-10：不再直接配对——先弹确认弹窗（见 pendingPair 的渲染处）
            is QrParseResult.Ready -> pendingPair = r.device
            // V2 新版二维码无 hash：进入登录配对流程
            is QrParseResult.NeedsLogin -> pendingLogin = r.device
            null -> Unit
        }
    }
}
