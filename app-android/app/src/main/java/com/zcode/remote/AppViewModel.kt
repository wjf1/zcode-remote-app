package com.zcode.remote

import android.app.Application
import android.net.ConnectivityManager
import com.zcode.remote.util.LogRedactor
import com.zcode.remote.util.ZLog
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zcode.remote.relay.BridgeFrames
import com.zcode.remote.BuildConfig
import com.zcode.remote.relay.ApprovalOption
import com.zcode.remote.relay.ConversationChannel
import com.zcode.remote.relay.ConversationFrames
import com.zcode.remote.relay.ConversationRow
import com.zcode.remote.relay.FailureReason
import com.zcode.remote.relay.NetworkGate
import com.zcode.remote.relay.PendingApproval
import com.zcode.remote.relay.PendingElicitation
import com.zcode.remote.relay.RelayClient
import com.zcode.remote.relay.RelayEndpointValidator
import com.zcode.remote.relay.RelayState
import com.zcode.remote.relay.SessionsIndexChannel
import com.zcode.remote.relay.WorkspaceConfigChannel
import com.zcode.remote.service.ConnectionScope
import com.zcode.remote.service.RemoteConnectionManager
import com.zcode.remote.service.ConnectionService
import com.zcode.remote.widget.PendingWidgetProvider
import com.zcode.remote.relay.RowStore
import com.zcode.remote.relay.RpcChannel
import com.zcode.remote.relay.SessionItem
import com.zcode.remote.relay.TaskEvent
import com.zcode.remote.relay.UserFacingError
import com.zcode.remote.relay.displayStatusForPhase
import com.zcode.remote.relay.displayStatusForTaskEvent
import com.zcode.remote.relay.parseBootstrapSessions
import com.zcode.remote.notify.ApprovalBridge
import com.zcode.remote.notify.ApprovalNotifier
import com.zcode.remote.notify.ElicitationBridge
import com.zcode.remote.notify.ElicitationNotifier
import com.zcode.remote.notify.TerminalNotifier
import com.zcode.remote.storage.MultiDeviceStore
import com.zcode.remote.storage.PairedDevice
import com.zcode.remote.storage.SessionCacheStore
import com.zcode.remote.repo.ApprovalRepository
import com.zcode.remote.repo.AttachmentUploader
import com.zcode.remote.repo.ConversationRepository
import com.zcode.remote.repo.ModelConfigRepository
import com.zcode.remote.repo.NotificationCoordinator
import com.zcode.remote.repo.UpdateRepository
import com.zcode.remote.storage.SettingsStore
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 应用状态中枢：配对 → 中继连接 → bootstrap（会话列表）→ 开桥 → RPC 会话流。
 * 协议见 PROTOCOL.md 与 research/FRAME-CODEC.md。
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val multiStore = MultiDeviceStore(app)
    private val settings = SettingsStore(app)

    // ---- §11.7 结构性拆分：职责域 repository（实现见 repo/，此处装配并转发）----
    private val updateRepo = UpdateRepository(viewModelScope, settings)
    private val uploader = AttachmentUploader(
        viewModelScope, app,
        conversation = { conversation },
        subscribedSessionId = { subscribedSessionId },
        flash = { msg, failure -> if (failure) flash(msg, FlashKind.Failure) else flash(msg) },
    )
    private val modelRepo = ModelConfigRepository(
        viewModelScope,
        channel = { channel },
        conversation = { conversation },
        workspaceConfig = { workspaceConfig },
        activeWorkspaceKey = { activeWorkspaceKey },
        firstSessionWorkspace = { sessions.firstOrNull()?.workspacePath },
        onWorkspaceMode = { sessionModeFromWorkspace = it },
        flash = { msg, failure -> if (failure) flash(msg, FlashKind.Failure) else flash(msg) },
    )

    private val convRepo = ConversationRepository(
        viewModelScope, app,
        conversation = { conversation },
        activeWorkspaceKey = { activeWorkspaceKey },
        flash = { msg, failure -> if (failure) flash(msg, FlashKind.Failure) else flash(msg) },
        onNewSubscription = {
            handshakeRetryJob?.cancel(); handshakeRetryJob = null
            handshakeRetryCount = 0
        },
        clearAttachments = { uploader.clearAttachments() },
    )
    private val approvalRepo = ApprovalRepository(
        viewModelScope, app,
        conversation = { conversation },
        activeWorkspaceKey = { activeWorkspaceKey },
        firstSessionWorkspace = { sessions.firstOrNull()?.workspacePath },
        flash = { msg, failure -> if (failure) flash(msg, FlashKind.Failure) else flash(msg) },
        onPendingChanged = { recomputeSessionPending() },
    )

    private val notifyCoordinator: NotificationCoordinator = NotificationCoordinator(
        app,
        client = { client },
        sessionsIndex = { sessionsIndex },
        relayState = { relayState },
        activeWorkspaceKey = { activeWorkspaceKey },
        subscribedSessionId = { subscribedSessionId },
        approvalsRepo = approvalRepo,
    )

    /** 活跃设备（当前连接的这台）。 */
    var device by mutableStateOf(multiStore.load().let { s ->
        s.devices.firstOrNull { it.deviceSid == s.activeSid } ?: s.devices.firstOrNull()
    })
        private set
    /** 已配对的全部设备（M3 多机管理）。 */
    var devices by mutableStateOf(multiStore.load().devices)
        private set

    // ---- 设置 ----
    var endpointMode by mutableStateOf(settings.endpointMode)
        private set
    var customRelayUrl by mutableStateOf(settings.customRelayUrl)
        private set
    var themeMode by mutableStateOf(settings.themeMode)
        private set

    /** 设置里的线路覆盖；auto 返回 null（按配对二维码推断）。 */
    private fun relayOverride(): String? = when (endpointMode) {
        SettingsStore.ENDPOINT_MAIN -> "wss://zcode.z.ai/ws"
        SettingsStore.ENDPOINT_BACKUP -> "wss://zcode.chatglm.site/ws"
        // 自建中继：必须过统一端点校验（Release 仅 wss://）。旧实现 startsWith("ws") 会
        // 放过 ws:// 明文中继；校验失败返回 null，回退到配对推断的官方 wss 端点。
        SettingsStore.ENDPOINT_CUSTOM ->
            customRelayUrl.takeIf {
                RelayEndpointValidator.isAcceptable(it, allowInsecure = BuildConfig.DEBUG)
            }
        else -> null
    }

    fun setEndpoint(mode: String, customUrl: String? = null) {
        settings.endpointMode = mode
        endpointMode = mode
        customUrl?.let { settings.customRelayUrl = it; customRelayUrl = it }
        if (device != null) connect()   // 线路变更立即重连生效
    }

    fun setTheme(mode: String) {
        settings.themeMode = mode
        themeMode = mode
    }

    // ---- 软件版本与在线更新（§11.7 拆分：实现在 repo/UpdateRepository，此处转发保持签名不变）----
    val updateState: UpdateRepository.UpdateState get() = updateRepo.updateState
    val githubToken: String get() = updateRepo.githubToken
    fun updateGithubToken(token: String) = updateRepo.updateGithubToken(token)
    fun checkForUpdate() = updateRepo.checkForUpdate()
    var relayState by mutableStateOf<RelayState>(RelayState.Idle)
        private set

    val events = mutableStateListOf<TaskEvent>()
    val sessions = mutableStateListOf<SessionItem>()

    // ---- 会话桥 / RPC ----
    var bridgeState by mutableStateOf<RpcChannel.BridgeState>(RpcChannel.BridgeState.Closed)
        private set
    var activeWorkspaceKey by mutableStateOf<String?>(null)
        private set

    /**
     * 握手自动重订计数（A-2）：每次新会话订阅归零；`Status.Live` 到达也归零。
     * 上限 1 次——再失败即置 Failed 并把重试权交回用户（UI 重试入口）。
     */
    private var handshakeRetryCount = 0
    private var handshakeRetryJob: kotlinx.coroutines.Job? = null
    /**
     * 桥自动重开计数与任务（2026-10-10 真机故障修复）：桥失败后此前无自愈路径，
     * 详见 scheduleBridgeReopen。桥就绪即归零。
     */
    private var bridgeReopenCount = 0
    private var bridgeReopenJob: kotlinx.coroutines.Job? = null
    /** 最近收到的 RPC 事件（原始 JSON，供 M2 阶段观测/渲染）。 */
    val rpcEvents = mutableStateListOf<String>()
    // ---- 会话流（§11.7 拆分：实现在 repo/ConversationRepository，此处转发保持签名不变）----
    /** 当前订阅中的会话 id（看板/调度/删除复位共用）。 */
    val subscribedSessionId: String? get() = convRepo.subscribedSessionId
    /** 当前会话的行（已按 rowId 排序，UI 直接渲染）。 */
    val rows get() = convRepo.rows
    /** C-5③：会话行的派生快照——仅在行内容变化时产生新实例。 */
    val rowsSnapshot: List<ConversationRow> get() = convRepo.rowsSnapshot
    val conversationStatus: ConversationChannel.Status get() = convRepo.conversationStatus
    val conversationMeta: ConversationChannel.ConversationMeta get() = convRepo.conversationMeta
    /** 向上翻页状态（加载中/hasMore/pulled）。 */
    val earlier: ConversationChannel.EarlierState get() = convRepo.earlier
    /** B-1：权威快照是否已落地（含 seq）。 */
    val snapshotAligned: ConversationChannel.SnapshotAligned? get() = convRepo.snapshotAligned
    /** C3：会话级状态（上下文用量 / 目标 / 待办 / 后台任务 / 子智能体 / 排队输入）。 */
    val sessionState: ConversationFrames.SessionState get() = convRepo.sessionState
    /** C-6：本地 pending 回显气泡。 */
    val pendingUserMessages: List<ConversationRepository.PendingUserMessage> get() = convRepo.pendingUserMessages
    fun retrySubscribe() = convRepo.retrySubscribe()
    fun loadEarlier() = convRepo.loadEarlier()
    private fun consumePendingEcho(echoText: String?) = convRepo.consumePendingEcho(echoText)

    // ---- 权限审批与表单交互（§11.7 拆分：实现在 repo/ApprovalRepository，此处转发保持签名不变）----
    /** 当前会话的待审批项（会话流 pendingInteractions + 任务事件流两条来源合并）。 */
    val approvals: List<PendingApproval> get() = approvalRepo.approvals
    /** 当前会话的待应答表单（会话帧 userInput 条目 + 任务事件流 elicitation_request 合并）。 */
    val elicitations: List<PendingElicitation> get() = approvalRepo.elicitations
    fun resolveById(interactionId: String, optionId: String) = approvalRepo.resolveById(interactionId, optionId)
    fun resolve(approval: PendingApproval, option: ApprovalOption) = approvalRepo.resolve(approval, option)
    fun answerElicitation(el: PendingElicitation, answers: Map<Int, List<String>>) = approvalRepo.answerElicitation(el, answers)
    fun declineElicitation(el: PendingElicitation) = approvalRepo.declineElicitation(el)
    fun approveElicitationPlan(el: PendingElicitation) = approvalRepo.approveElicitationPlan(el)
    fun answerElicitationFreeText(el: PendingElicitation, text: String) = approvalRepo.answerElicitationFreeText(el, text)
    private fun refreshApprovals() = approvalRepo.refreshApprovals()
    private fun refreshElicitations() = approvalRepo.refreshElicitations()
    private fun resolveElicitationById(interactionId: String, answerJson: String): Boolean =
        approvalRepo.resolveElicitationById(interactionId, answerJson)

    // ---- 多会话看板 / Widget / 视图上报（§11.7 拆分：实现在 repo/NotificationCoordinator，此处转发）----
    /** 每个会话的待处理条数（taskId → 审批 + 表单）。 */
    val sessionPending: Map<String, Int> get() = notifyCoordinator.sessionPending
    /** 桌面端当前活动会话（workspace-list 的 activeTaskId，用于「PC 正在看哪个」提示）。 */
    val desktopActiveTaskId: String? get() = notifyCoordinator.desktopActiveTaskId
    private fun recomputeSessionPending() = notifyCoordinator.recomputeSessionPending()
    private fun syncWidget(pendingTotal: Int = notifyCoordinator.sessionPending.values.sum()) =
        notifyCoordinator.syncWidget(pendingTotal)
    private fun reportViewState() = notifyCoordinator.reportViewState()
    private fun terminalTitle(reason: FailureReason): String = NotificationCoordinator.terminalTitle(reason)

    /** 首页会话搜索关键字（P2-3：按标题/工作区过滤，忽略大小写）。 */
    var sessionQuery by mutableStateOf("")
        private set

    fun updateSessionQuery(q: String) { sessionQuery = q }

    // ---- 连接栈（Sprint 1 / P0-A）：实际持有方是进程级 ConnectionScope（由前台服务
    // ConnectionService 保护进程），下面是读写代理 —— ViewModel 重建/销毁不影响连接本体，
    // 既有引用点全部无需改动。
    // ---- 执行模式（P0-B）----
    // ⚠️ 相关 mutableState 必须声明在 init{connect()} 之前：connect 的状态收集会立即写它，
    // Kotlin 属性按声明顺序初始化，放类尾会在首次 emit 时 NPE（真机验收 2026-10-05 实证）。
    /** 用户在会话页手动切换后的执行模式乐观值（优先级最高）。 */
    var sessionModeOverride by mutableStateOf<String?>(null)
        private set
    /** 工作区当前执行模式（readWorkspaceState 的 settings.mode.current；null=未读到，UI 兜底 build）。 */
    var sessionModeFromWorkspace by mutableStateOf<String?>(null)
        private set

    private var client: RelayClient?
        get() = ConnectionScope.client
        set(value) { ConnectionScope.client = value }

    // ---- 会话文件预览（Sprint 3 第二步·剧本 B「最近文件」）----
    // 纯派生状态（点击触发，不在 init 路径），但统一放顶部区避免初始化顺序坑。
    sealed interface FilePreview {
        data object Loading : FilePreview
        data class Loaded(val path: String, val content: String) : FilePreview
        data class Failed(val path: String, val message: String) : FilePreview
    }

    var filePreview by mutableStateOf<FilePreview?>(null)
        private set

    /** 预览一个文件：~ 开头先经 resolvePath 展开（provider_config 链路同款），再 readTextFile。 */
    fun previewSessionFile(path: String) {
        filePreview = FilePreview.Loading
        readRemoteTextFile(path) { r ->
            viewModelScope.launch {
                filePreview = r.fold(
                    onSuccess = { FilePreview.Loaded(path, it) },
                    onFailure = { FilePreview.Failed(path, UserFacingError.map(it.message)) },
                )
            }
        }
    }

    fun dismissFilePreview() { filePreview = null }

    private fun readRemoteTextFile(path: String, onResult: (Result<String>) -> Unit) {
        val ch = channel ?: run { onResult(Result.failure(IllegalStateException("连接尚未就绪"))); return }
        fun doRead(p: String) {
            ch.call(RpcChannel.CHANNEL_FILE, "readTextFile", listOf(mapOf("path" to p))) { r ->
                viewModelScope.launch {
                    when (r) {
                        is RpcChannel.RpcReply.Err ->
                            onResult(Result.failure(IllegalStateException(r.message)))
                        is RpcChannel.RpcReply.Ok -> {
                            val content = runCatching {
                                r.data?.jsonObject?.get("content")?.jsonPrimitive?.content
                            }.getOrNull()
                            if (content != null) onResult(Result.success(content))
                            else onResult(Result.failure(IllegalStateException("响应缺少 content 字段")))
                        }
                    }
                }
            }
        }
        if (path.startsWith("~")) {
            ch.call(RpcChannel.CHANNEL_FILE, "resolvePath", listOf(mapOf("path" to path))) { r1 ->
                viewModelScope.launch {
                    val real = when (r1) {
                        is RpcChannel.RpcReply.Ok ->
                            runCatching { r1.data?.jsonPrimitive?.content }.getOrNull()
                                ?: runCatching { r1.data?.jsonObject?.get("path")?.jsonPrimitive?.content }.getOrNull()
                                ?: path
                        else -> path
                    }
                    doRead(real)
                }
            }
        } else doRead(path)
    }
    private var channel: RpcChannel?
        get() = ConnectionScope.channel
        set(value) { ConnectionScope.channel = value }
    private var conversation: ConversationChannel?
        get() = ConnectionScope.conversation
        set(value) { ConnectionScope.conversation = value }
    /** workspace 级会话索引（E-1）：服务端权威角标 pendingInteractionSummary。 */
    private var sessionsIndex: SessionsIndexChannel?
        get() = ConnectionScope.sessionsIndex
        set(value) { ConnectionScope.sessionsIndex = value }
    private var workspaceConfig: WorkspaceConfigChannel?
        get() = ConnectionScope.workspaceConfig
        set(value) { ConnectionScope.workspaceConfig = value }

    init {
        // Sprint 5 离线缓存秒开：冷启动时立即同步读取本地持久化会话列表
        val cached = SessionCacheStore.load(app)
        if (cached.isNotEmpty()) {
            sessions.addAll(cached)
        }
        if (device != null) connect()
    }

    fun pair(newDevice: PairedDevice) {
        val snap = multiStore.upsertActive(newDevice)
        devices = snap.devices
        device = newDevice
        connect()
    }

    /** 切换到另一台已配对设备（断开当前连接，连接新设备）。 */
    fun switchDevice(sid: String) {
        if (sid == device?.deviceSid) return
        val target = devices.firstOrNull { it.deviceSid == sid } ?: return
        multiStore.setActive(sid)
        device = target
        connect()
    }

    /** 移除一台设备；若移除的是活跃设备则落到下一台（无设备则断开）。 */
    fun removeDevice(sid: String) {
        val snap = multiStore.remove(sid)
        // §11.6：删设备即清其关联离线缓存（会话列表 + 全部行缓存）——
        // 缓存按会话 id 落盘、不分设备，保守起见随删设备整体清空，避免残留上一设备的会话正文。
        SessionCacheStore.clearAll(getApplication())
        devices = snap.devices
        if (device?.deviceSid == sid) {
            device = snap.devices.firstOrNull { it.deviceSid == snap.activeSid }
                ?: snap.devices.firstOrNull()
            if (device != null) connect() else disconnect()
        }
    }

    fun connect() {
        val dev = device ?: return
        ConnectionScope.manuallyDisconnected = false
        // P0-A：前台服务保住进程 —— App 在后台/锁屏时连接与审批通知才可达（招牌功能的性命）
        ConnectionService.start(getApplication())
        // §11.4 阶段②：重建连接栈前先取消旧 collector —— 否则重连会叠加旧流，
        // 旧流继续写旧实例（重复订阅 / 重复审批卡 / 重复通知）。
        RemoteConnectionManager.cancelCollectors()
        client?.close()
        channel?.reset()
        conversation?.reset()
        val c = RelayClient(
            dev,
            // B 组：自报真实版本号（原默认 0.1.0，上游按 app_version 做能力协商时是哑雷）
            appVersion = BuildConfig.VERSION_NAME,
            relayWsUrlOverride = relayOverride(),
        )
        // B 组：网络感知重连 —— 常驻监控：断网立即断 socket 调度重连（attempt 归零），
        // 恢复即刻放行（不等 OkHttp 迟到的失败回调，真机实证其延迟到恢复才冒出）
        val cm = getApplication<Application>().getSystemService(ConnectivityManager::class.java)
        val gate = NetworkGate(cm)
        gate.onLost = c::onNetworkLost
        // 网络恢复：重置退避 + 取消已排队的退避等待并立即重连（否则恢复要白等一轮退避，实测 ~47s）
        gate.onAvailable = c::onNetworkAvailable
        c.networkWait = gate::waitBeforeReconnect
        gate.startWatch()
        val ch = RpcChannel(c)
        // A-4：事件流 seq 缺口 → 会话重订阅（缺口帧已 ack、服务端不重发，重取权威快照是唯一自愈路径）。
        ch.onSequenceGap = { retrySubscribe() }
        val conv = ConversationChannel(ch)
        conv.onRowsUpdated = { sid, updatedRows ->
            viewModelScope.launch {
                SessionCacheStore.saveRows(getApplication(), sid, updatedRows)
            }
        }
        // C-6：服务端回显 userInput 行 → 移除对应的本地 pending 回显气泡
        conv.onUserInputEcho = { row -> consumePendingEcho(row.text) }
        // 会话级错误原因回填首页列表：bootstrap 的 tasks[] 形状不含 lastError（PROTOCOL.md 实测样本），
        // 只有打开过的会话能从快照 control.lastError 拿到原因——聊胜于无，完整原因在会话页横幅。
        conv.onSessionError = { sid, err ->
            val idx = sessions.indexOfFirst { it.taskId == sid }
            if (idx >= 0 && sessions[idx].lastError != err) {
                sessions[idx] = sessions[idx].copy(lastError = err)
            }
        }
        // 会话相位回填首页列表：列表的 displayStatus 只在配对成功时的 bootstrap 取一次快照，
        // 全仓没有刷新入口——「会话页明明在跑、退回主界面却没有运行中的会话」就是这一半缺口。
        conv.onSessionPhase = { sid, phase ->
            val mapped = displayStatusForPhase(phase)
            if (mapped != null) {
                val idx = sessions.indexOfFirst { it.taskId == sid }
                if (idx >= 0 && sessions[idx].displayStatus != mapped) {
                    sessions[idx] = sessions[idx].copy(displayStatus = mapped)
                }
            }
        }
        val sidx = SessionsIndexChannel(ch)
        val wcfg = WorkspaceConfigChannel(ch)
        client = c
        channel = ch
        conversation = conv
        sessionsIndex = sidx
        workspaceConfig = wcfg
        c.connect()

        // §11.4 阶段③：relay 状态流迁入进程级作用域 —— 终态通知（KICKED/配对失效等）是
        // 锁屏可达性本体，VM 被清后也必须能发；引导触发与 Widget 刷新随流同迁。
        RemoteConnectionManager.launchCollector {
            c.state.collect { st ->
                relayState = st
                // Sprint 2 / A 组第 3 项：终态失败发常驻系统通知 —— 手机在兜里时不能
                // 静默失去全部审批能力（KICKED/配对失效/协议升级用户都必须立刻知道）。
                val terminal = (st as? RelayState.Failed)?.takeIf {
                    it.reason == FailureReason.KICKED || it.reason == FailureReason.AUTH_FAILED ||
                        it.reason == FailureReason.PROTOCOL_MISMATCH ||
                        it.reason == FailureReason.INVALID_ENDPOINT
                }
                if (terminal != null) {
                    TerminalNotifier.show(getApplication(), terminalTitle(terminal.reason), terminal.message)
                } else {
                    TerminalNotifier.clear(getApplication())
                }
                if (st is RelayState.Paired) {
                    ch.reset()
                    c.sendPayload(BridgeFrames.bootstrapRequest("boot-${System.currentTimeMillis()}"))
                    // 接管后立即上报视图状态（P1-2）：PC 端知道手机在看哪个工作区/会话
                    reportViewState()
                }
                // 连接状态变化即刷新桌面 Widget（含断线 → 未连接态）
                syncWidget()
            }
        }

        viewModelScope.launch {
            c.inbound.collect { payload ->
                when (BridgeFrames.zcodeTypeOf(payload)) {
                    "bootstrap-response" -> {
                        val list = parseBootstrapSessions(payload)
                        if (list.isNotEmpty()) {
                            sessions.clear(); sessions.addAll(list)
                            viewModelScope.launch { SessionCacheStore.save(getApplication(), list) }
                        }
                        // 取活动工作区 → 开桥（会话流前置条件）
                        val wsKey = activeWorkspaceKeyOf(payload)
                        if (wsKey != null) {
                            activeWorkspaceKey = wsKey
                            if (ch.bridge.value !is RpcChannel.BridgeState.Ready) ch.openBridge(wsKey)
                        }
                    }
                    "workspace-bridge-ready",
                    "workspace-bridge-error",
                    "bridge-degraded" -> ch.onBridgeFrame(payload)

                    "rpc-frame" -> ch.onRpcFrame(payload)
                    "rpc-frame-ack" -> Unit

                    "workspace-list-response", "workspace-list-updated" -> Unit
                    null -> TaskEvent.from(payload)?.let { ev ->
                        events.add(0, ev)
                        if (events.size > 200) events.removeAt(events.lastIndex)
                        // 审批推送走任务事件流（实测会话帧里没有 pendingInteractions）：
                        // permission_request 建卡，permission_resolved 按 requestId 撤卡。
                        when (ev.type) {
                            "permission_request" ->
                                PendingApproval.fromTaskEvent(ev.raw, ev.taskId)?.let { approvalRepo.ingestTaskApproval(it) }
                            "permission_resolved" ->
                                ev.raw["requestId"]?.let {
                                    runCatching { it.jsonPrimitive.content }.getOrNull()
                                }?.let { approvalRepo.resolveTaskApproval(it) }
                            "elicitation_request" ->
                                PendingElicitation.fromTaskEvent(ev.raw, ev.taskId)?.let { approvalRepo.ingestTaskElicitation(it) }
                            "elicitation_resolved" ->
                                ev.raw["requestId"]?.let {
                                    runCatching { it.jsonPrimitive.content }.getOrNull()
                                }?.let { approvalRepo.resolveTaskElicitation(it) }
                        }
                        // 列表状态实时化：任务事件是 host 推的实时流（PROTOCOL.md §6.2），而列表的
                        // displayStatus 只在 bootstrap 取过一次快照——不回写就会出现「会话页在跑、
                        // 退回主界面仍显示已完成」。映射规则见 displayStatusForTaskEvent。
                        ev.taskId?.let { tid ->
                            val mapped = displayStatusForTaskEvent(ev.type)
                            if (mapped != null) {
                                val idx = sessions.indexOfFirst { it.taskId == tid }
                                if (idx >= 0 && sessions[idx].displayStatus != mapped) {
                                    sessions[idx] = sessions[idx].copy(displayStatus = mapped)
                                }
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }

        // §11.4 阶段③：桥状态流迁入进程级作用域 —— 桥降级后的自动重开是连接韧性本体。
        RemoteConnectionManager.launchCollector {
            ch.bridge.collect { st ->
                bridgeState = st
                ZLog.i(TAG, "bridge=$st")
                if (st is RpcChannel.BridgeState.Ready) {
                    // 桥就绪：自动重开预算归零（下次失败可再走完整退避序列）
                    bridgeReopenCount = 0
                    bridgeReopenJob?.cancel(); bridgeReopenJob = null
                    // E-1：workspace 级 sessions-index 订阅（权威角标，一次订阅覆盖全部会话）
                    activeWorkspaceKey?.let { sidx.subscribe(it, null) }
                    // 从 PC 端 ~/.zcode/v2/provider_config.json 预加载已配置模型目录
                    loadModelsFromConfigFile()
                    // 模型注册表：各模型的合法思考档位（createSession/switchModelConfig 必需）
                    loadModelReasoningLevels()
                    val target = sessions.firstOrNull { it.isRunning } ?: sessions.firstOrNull()
                    target?.let { convRepo.subscribeConversation(it) }
                }
                // 桥失败（服务端降级 `bridge-degraded` 或看门狗用尽）→ 自动重开（2026-10-10 真机故障修复）
                if (st is RpcChannel.BridgeState.Failed) scheduleBridgeReopen()
            }
        }

        // §11.4 阶段②：首条迁入进程级作用域的事件流 —— rpc 事件是会话流 / 会话角标 / 配置
        // 三通道的唯一入口，此前跑在 viewModelScope：Activity 销毁、VM 被清后这条流一断，
        // 锁屏审批、离线缓存、会话角标全部停摆（连接对象虽存活、事件却断流）。
        // 逻辑原样搬迁，仅换作用域；其余流在后续提交逐条迁移（每条独立 commit 可回退）。
        RemoteConnectionManager.launchCollector {
            ch.events.collect { ev ->
                // 会话帧优先交给会话层解析；sessions-index 帧交给 E-1 权威角标通道
                conv.onEvent(ev, convRepo.rowStore)
                sidx.onEvent(ev)
                wcfg.onEvent(ev)
                // C-5①：观测面板只在 debug 构建保留——release 不再为**每个 delta** 做一次
                // `ev.data.toString()`（全量 JSON 序列化，主线程 collector 内）与 4000 字符拷贝。
                // ZLog.d 本身在 release 已被 R8 剥离（proguard -assumenosideeffects），但实参求值
                // 不会——这才是真凶（任务书 §5 C-5① 纠正点：v1 误判为「ZLog 未剥离」）。
                if (BuildConfig.DEBUG) {
                    val text = ev.data.toString()
                    // P0 日志止血：logcat 里也不落会话正文（canary 验收要求 debug 日志同样零命中）
                    ZLog.d(TAG, "rpc event id=${ev.id} data=${LogRedactor.payloadLabel(text)}")
                    rpcEvents.add(0, text.take(4000))
                    if (rpcEvents.size > 50) rpcEvents.removeAt(rpcEvents.lastIndex)
                }
            }
        }

        // §11.4 阶段③：会话状态流迁入进程级作用域 —— 握手瞬态失败的自动重订（A-2）是连接韧性本体。
        RemoteConnectionManager.launchCollector {
            conv.status.collect { st ->
                convRepo.conversationStatus = st
                // 换会话/重订阅时清掉上一个会话的模式乐观覆盖，回到订阅 ack 的真实值
                if (st is ConversationChannel.Status.Idle) sessionModeOverride = null
                // 模型目录订阅需要会话握手完成（真机实证：bridge Ready 即订会报
                // fault.connection.handshakeRequired，Live 后才可用）
                if (st is ConversationChannel.Status.Live) {
                    handshakeRetryCount = 0
                    activeWorkspaceKey?.let { wcfg.subscribe(it, null) }
                }
                // A-2：握手瞬态失败（超时）→ 退避自动重订一次，避免永久停在「握手中…」
                if (st is ConversationChannel.Status.Failed && st.retryable) {
                    scheduleHandshakeRetry()
                }
            }
        }
        viewModelScope.launch { conv.meta.collect { convRepo.conversationMeta = it } }
        viewModelScope.launch { conv.snapshotAligned.collect { convRepo.snapshotAligned = it } }
        viewModelScope.launch { conv.sessionState.collect { convRepo.sessionState = it } }
        viewModelScope.launch { conv.earlier.collect { convRepo.earlier = it } }
        // §11.4 阶段②（续）：审批/表单「通知链路」迁入进程级作用域 —— 通知是锁屏可达性的本体，
        // 必须在 VM 被清后仍能发出。refreshApprovals/refreshElicitations 内部无 viewModelScope
        // 依赖（getApplication 在 VM 被清后仍可用），故原样换作用域。
        RemoteConnectionManager.launchCollector {
            conv.interactions.collect { refreshApprovals() }
        }
        RemoteConnectionManager.launchCollector {
            conv.elicitations.collect { refreshElicitations() }
        }
        RemoteConnectionManager.launchCollector {
            sidx.summaries.collect { recomputeSessionPending() }
        }
        // 通知按钮 → AppViewModel 应答（连接只活在这里，所以桥必须在连接建立时挂上）
        ApprovalBridge.handler = { interactionId, optionId -> resolveById(interactionId, optionId) }
        ElicitationBridge.handler = { interactionId, answerJson ->
            resolveElicitationById(interactionId, answerJson)
        }
    }





    // ---- 发送消息 / 停止（P0-1）----

    /** 会话页输入栏草稿（跨重组保持，发送成功才清空）。 */
    var promptDraft by mutableStateOf("")
        private set

    var sending by mutableStateOf(false)
        private set
    /**
     * 统一的反馈横幅（C-11）：审批应答反馈与操作反馈共用这一条状态，
     * 由 [flash] 统一管理显示与自动消退。原 approvalFeedback 与 commandFeedback 双轨并存，
     * 且审批 Tab 从不消费 approvalFeedback（`consumeApprovalFeedback` 只在会话页被调），
     * 表现为审批 Tab 文案永久滞留——合并为单队列后该缺陷消失。
     */
    var commandFeedback by mutableStateOf<String?>(null)
        private set
    /** 当前反馈是否为失败语义（UI 据此决定横幅配色；与 [flashDurationMs] 的时长档位同源）。 */
    var feedbackIsFailure by mutableStateOf(false)
        private set

    private var feedbackJob: kotlinx.coroutines.Job? = null

    /**
     * 反馈语义（B-3）：驻留时长由显式类型参数决定，取代原先按字符串前缀猜的白名单
     * （旧写法每新增一档失败文案都得改白名单，且「附件上传失败」一直错落在 4s 档）。
     */
    enum class FlashKind { Info, Failure }

    private fun flash(msg: String, kind: FlashKind = FlashKind.Info) {
        commandFeedback = msg
        feedbackIsFailure = kind == FlashKind.Failure
        feedbackJob?.cancel()
        // 失败类提示留 8s（4s 真机上易被错过，2026-09-30 验收发现）；成功提示仍 4s 免打扰
        val ms = flashDurationMs(kind)
        feedbackJob = viewModelScope.launch {
            kotlinx.coroutines.delay(ms)
            commandFeedback = null
        }
    }

    fun updatePromptDraft(v: String) { promptDraft = v }

    /** 发送输入栏消息：成功后服务端把 userInput 行推回会话流（本地只留 pending 回显气泡）。 */
    fun sendPrompt() {
        val content = promptDraft.trim()
        val atts = attachments.toList()
        if ((content.isEmpty() && atts.isEmpty()) || sending) return
        val conv = conversation ?: run { flash("连接已断开，未发送", FlashKind.Failure); return }
        sending = true
        // C-6：发送瞬间先插一条本地回显气泡（发送失败时撤回，服务端回显到达时移除）
        val pending = convRepo.addPendingMessage(content, atts.mapNotNull { it.fileName })
        conv.sendPrompt(content, atts) { r ->
            viewModelScope.launch {
                sending = false
                r.fold(
                    onSuccess = {
                        promptDraft = ""
                        attachments.clear()
                        ZLog.i(TAG, "sendPrompt ok session=$subscribedSessionId atts=${atts.size}")
                    },
                    onFailure = {
                        // 撤回本地气泡：错误横幅已说明原因，不留下只在手机上存在的「幽灵消息」
                        convRepo.removePendingMessage(pending.id)
                        flash("发送失败：${UserFacingError.map(it.message)}", FlashKind.Failure)
                    },
                )
            }
        }
    }

    // ---- 附件上传（§11.7 拆分：实现在 repo/AttachmentUploader，此处转发保持签名不变）----
    /** 已上传完成、随下一条消息发出去的附件。 */
    val attachments get() = uploader.attachments
    val attachUpload: AttachmentUploader.AttachUpload? get() = uploader.attachUpload
    /** C-1：上传失败态（UI 显示重试/取消）。 */
    val attachFailed: AttachmentUploader.FailedUpload? get() = uploader.attachFailed
    /** B-3：附件上传成/败信号（tick 变化触发一次触觉）。 */
    val attachmentFeedback: AttachmentUploader.AttachmentFeedback? get() = uploader.attachmentFeedback
    fun addAttachment(uri: android.net.Uri, fileName: String, mime: String, size: Long) = uploader.addAttachment(uri, fileName, mime, size)
    fun removeAttachment(ref: ConversationChannel.AttachmentRef) = uploader.removeAttachment(ref)
    fun cancelAttachmentUpload() = uploader.cancelAttachmentUpload()
    fun retryAttachmentUpload() = uploader.retryAttachmentUpload()
    fun dismissAttachFailure() = uploader.dismissAttachFailure()
    private fun clearAttachments() = uploader.clearAttachments()

    /** 停止当前运行（envelope `stop` 命令，官方 web 同款）。 */
    fun stopSession() {
        val conv = conversation ?: run { flash("连接已断开，未发送", FlashKind.Failure); return }
        conv.stop { r ->
            viewModelScope.launch {
                flash(
                    when (r) {
                        is ConversationChannel.ResolveResult.Accepted -> "已请求停止"
                        is ConversationChannel.ResolveResult.Failed -> "停止失败：${UserFacingError.map(r.message)}"
                    },
                    when (r) {
                        is ConversationChannel.ResolveResult.Accepted -> FlashKind.Info
                        is ConversationChannel.ResolveResult.Failed -> FlashKind.Failure
                    },
                )
                ZLog.i(TAG, "stop result=$r")
            }
        }
    }

    // ---- 表单交互应答（P1-1，构造规则见 PROTOCOL.md §6.5）----


    /**
     * A-2：握手超时等瞬态失败后的自动重订（退避 1s → 2s，上限 1 次）。
     *
     * 只重发订阅，不重载离线缓存、不清附件——那些是「进入会话」的一次性副作用。
     * 再失败则停在 Failed，重试权交回用户（见 [retrySubscribe]）。
     */
    private fun scheduleHandshakeRetry() {
        val s = convRepo.subscribedSession ?: return
        if (handshakeRetryCount >= MAX_HANDSHAKE_RETRIES) {
            ZLog.w(TAG, "握手自动重订已用尽（$handshakeRetryCount 次），停在失败态等用户重试")
            return
        }
        handshakeRetryCount += 1
        val delayMs = if (handshakeRetryCount == 1) 1_000L else 2_000L
        ZLog.i(TAG, "握手失败，${delayMs}ms 后自动重订（第 $handshakeRetryCount 次）")
        handshakeRetryJob?.cancel()
        // §11.4 阶段③：重试调度改挂进程级作用域 —— VM 被清后自动重订仍要生效（连接韧性）。
        handshakeRetryJob = RemoteConnectionManager.scope.launch {
            kotlinx.coroutines.delay(delayMs)
            // 期间用户可能已切走会话，此时重订无意义
            if (convRepo.subscribedSessionId != s.taskId) return@launch
            val conv = conversation ?: return@launch
            val ws = s.workspacePath ?: activeWorkspaceKey ?: return@launch
            ZLog.i(TAG, "自动重订 session=${s.taskId}")
            conv.subscribe(ws, null, s.taskId, convRepo.rowStore)
        }
    }

    /**
     * 桥失败后的自动重开（2026-10-10 真机故障修复）。
     *
     * 背景：`bridge-degraded`（服务端判定 `rpc-transport-fault` 主动降级）与桥看门狗用尽都会置
     * `BridgeState.Failed`，但此前**没有任何自愈路径**——只有 relay 重新 Paired 或用户手动操作
     * 才会再次 `openBridge`。真机实测（2026-10-10）表现为「手机显示异常、桌面端仍显示已连接」
     * 的长窗口不可用（本次约 2 分钟，靠上层重连撞回来；期间 App 不 ack 服务端帧，
     * 反而触发服务端重放与降级，形成负反馈）。
     *
     * 策略：退避 1s/2s/4s、上限 3 次（[bridgeReopenDelayMs]）；桥就绪即归零；用尽后停失败态
     * 交由上层兜底，不无限重试（避免把「服务端持续降级」放大成重开风暴）。
     */
    private fun scheduleBridgeReopen() {
        val wsKey = activeWorkspaceKey ?: return
        val delayMs = bridgeReopenDelayMs(bridgeReopenCount + 1)
        if (delayMs == null) {
            ZLog.w(TAG, "桥自动重开已用尽（$bridgeReopenCount 次），停在失败态等待上层重连")
            return
        }
        bridgeReopenCount += 1
        bridgeReopenJob?.cancel()
        bridgeReopenJob = RemoteConnectionManager.scope.launch {
            kotlinx.coroutines.delay(delayMs)
            if (bridgeState is RpcChannel.BridgeState.Ready) return@launch  // 期间已恢复（如 relay 重连撞回来）
            ZLog.i(TAG, "桥失败，${delayMs}ms 后自动重开（第 $bridgeReopenCount 次）ws=${LogRedactor.pathLabel(wsKey)}")
            channel?.openBridge(wsKey)
        }
    }

    /** 手动订阅指定会话（UI 点击会话卡片时调用）。 */
    fun openSession(s: SessionItem) {
        convRepo.subscribeConversation(s)
        reportViewState()   // P1-2：切换会话即上报，PC 端可提示"手机正在看这个会话"
    }

    /**
     * 创建全新会话（官方 V4 createSession 原生信封链路）。
     * 成功后自动切换订阅并上报 PC 端视图状态，同时回调通知 UI 打开该会话。
     * modelOption 来自 workspace-config 订阅的模型目录；null = 跟随 PC 端默认。
     */
    fun createNewSession(
        firstPrompt: String,
        attachments: List<ConversationChannel.AttachmentRef> = emptyList(),
        modelOption: WorkspaceConfigChannel.ModelOption? = null,
        /** 执行模式（P0-B）：plan/build/yolo，默认 build —— 旧版写死 yolo 让手机建的会话全部免审批。 */
        execMode: String = "build",
        onSuccess: (SessionItem) -> Unit,
        onError: (String) -> Unit,
    ) {
        val conv = conversation ?: run {
            onError("连接尚未就绪，无法创建会话")
            return
        }
        val ws = activeWorkspaceKey ?: sessions.firstOrNull()?.workspacePath ?: run {
            onError("未定位到活动工作区，请在 PC 端打开一个工作区后再试")
            return
        }

        // 把 "providerId/modelId" 选项值拆成 createSession config 需要的 {provider, model}
        val modelConfig = modelOption?.let {
            val (pid, mid) = WorkspaceConfigChannel.splitModelValue(it.value)
            ConversationChannel.ModelConfig(
                providerId = pid,
                modelId = mid,
                // 思考档位必须用该模型自己的合法值（gemini=enabled，deepseek 可能是 low/high/max）；
                // 官方 registry 校验不通过会直接抛出 "Reasoning level is required" 让会话失败。
                // 用户在弹窗里显式选了档位则用选中的，否则自动挑一个合法档位；都没有时不下发。
                thought = modelOption.thought ?: pickReasoningLevel(pid, mid),
                // P0-B：执行模式来自新建会话弹窗选择（默认 build），不再写死 yolo
                mode = execMode,
            )
        }
        val promptText = firstPrompt.trim()

        conv.createSession(
            workspacePath = ws,
            workspaceIdentity = null,
            firstInputText = promptText.ifEmpty { null },
            attachments = attachments,
            modelConfig = modelConfig,
        ) { result ->
            viewModelScope.launch {
                result.fold(
                    onSuccess = { newSid ->
                        ZLog.i(TAG, "createNewSession success: sid=${LogRedactor.maskId(newSid)} ws=${LogRedactor.pathLabel(ws)} " +
                                "model=${modelConfig?.modelId ?: "inherit-default"}")
                        val item = SessionItem(
                            taskId = newSid,
                            title = promptText.ifEmpty { "新会话" },
                            displayStatus = "running",
                            workspacePath = ws,
                            workspaceLabel = ws.substringAfterLast('/'),
                            provider = modelConfig?.providerId,
                            updatedAt = System.currentTimeMillis(),
                            archived = false,
                        )
                        // 若列表里尚未有该会话，前插到首位
                        if (sessions.none { it.taskId == newSid }) {
                            sessions.add(0, item)
                            viewModelScope.launch { SessionCacheStore.save(getApplication(), sessions.toList()) }
                        }
                        // 首轮指令已随 createSession 的 firstInput 发出，输入框保持空白
                        promptDraft = ""
                        clearAttachments()
                        openSession(item)
                        onSuccess(item)
                    },
                    onFailure = { err ->
                        ZLog.w(TAG, "createNewSession failed: ${err.message}")
                        onError(UserFacingError.map(err.message ?: "创建会话失败"))
                    }
                )
            }
        }
    }

    // ---- 模型目录（§11.7 拆分：实现在 repo/ModelConfigRepository，此处转发保持签名不变）----
    val workspaceModelState: WorkspaceConfigChannel.WorkspaceState? get() = modelRepo.workspaceModelState
    val workspaceSessionModels: List<WorkspaceConfigChannel.ModelOption> get() = modelRepo.workspaceSessionModels
    val allAvailableModels: List<WorkspaceConfigChannel.ModelOption> get() = modelRepo.allAvailableModels
    val modelStateLoading: Boolean get() = modelRepo.modelStateLoading
    val modelReasoningLevels: Map<String, List<String>> get() = modelRepo.modelReasoningLevels
    fun loadModelReasoningLevels() = modelRepo.loadModelReasoningLevels()
    fun loadWorkspaceModels() = modelRepo.loadWorkspaceModels()
    fun loadModelsFromConfigFile(onDone: () -> Unit = {}) = modelRepo.loadModelsFromConfigFile(onDone)
    fun switchCurrentSessionModel(modelOption: WorkspaceConfigChannel.ModelOption) = modelRepo.switchCurrentSessionModel(modelOption)
    /** 取该模型应当下发的思考档位（无合法档位信息时返回 null）。实现在 ModelConfigRepository。 */
    private fun pickReasoningLevel(providerId: String, modelId: String): String? = modelRepo.pickReasoningLevel(providerId, modelId)

    /**
     * 删除会话（官方移动端同款链路）：
     * 1) 若会话在运行，先走 `zcode-session::closeSession` 结束（fire-and-forget，失败不阻断）；
     * 2) 再走 `zcode-task::deleteTask` 软删除（task index 写入 deleted=1，bootstrap/列表不再下发）。
     * 成功后从本地会话列表移除；若删除的是当前订阅中的会话，同步复位订阅状态。
     */
    fun deleteSession(item: SessionItem, onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        val ch = channel ?: run { onResult(false, "连接尚未就绪"); return }
        val ws = item.workspacePath ?: activeWorkspaceKey ?: run { onResult(false, "未定位到工作区"); return }
        val target = buildMap<String, Any> {
            put("workspacePath", ws)
            put("taskId", item.taskId)
        }

        // 1) 先尝试结束运行中的会话（失败不阻断删除）
        ch.call(RpcChannel.CHANNEL_SESSION, "closeSession",
            listOf(mapOf("workspacePath" to ws, "sessionId" to item.taskId))) { r ->
            ZLog.i(TAG, "closeSession(${item.taskId.take(20)}…) → ${r::class.simpleName}")
        }

        // 2) 软删除任务
        ch.call(RpcChannel.CHANNEL_TASK, "deleteTask", listOf(target)) { reply ->
            viewModelScope.launch {
                when (reply) {
                    is RpcChannel.RpcReply.Ok -> {
                        ZLog.i(TAG, "deleteTask 成功: ${item.taskId.take(20)}…")
                        // 本地列表同步移除并写回持久化缓存；C-7：同步清理该会话的行缓存文件
                        sessions.removeAll { it.taskId == item.taskId }
                        SessionCacheStore.deleteRows(getApplication(), item.taskId)
                        viewModelScope.launch { SessionCacheStore.save(getApplication(), sessions.toList()) }
                        // 若删除的是当前订阅中的会话，复位订阅与草稿状态
                        if (convRepo.subscribedSessionId == item.taskId) {
                            convRepo.subscribedSessionId = null
                            convRepo.subscribedSession = null
                            handshakeRetryCount = 0
                            convRepo.rowStore.clear()
                            promptDraft = ""
                            attachments.clear()
                            conversation?.reset()
                        }
                        recomputeSessionPending()
                        syncWidget()
                        flash("会话已删除")
                        onResult(true, "会话已删除")
                    }
                    is RpcChannel.RpcReply.Err -> {
                        ZLog.w(TAG, "deleteTask 失败: ${reply.message}")
                        flash("删除会话失败: ${UserFacingError.map(reply.message)}", FlashKind.Failure)
                        onResult(false, reply.message)
                    }
                }
            }
        }
    }

    /** 从 bootstrap-response 取当前活动工作区 key。 */
    private fun activeWorkspaceKeyOf(payload: JsonObject): String? {
        val result = payload["result"]?.let { runCatching { it.jsonObject }.getOrNull() } ?: return null
        val ivs = result["initialViewState"]?.let { runCatching { it.jsonObject }.getOrNull() }
        ivs?.get("activeWorkspaceKey")?.let {
            runCatching { it.jsonPrimitive.content }.getOrNull()?.let { k -> if (k.isNotBlank()) return k }
        }
        // 回退：取第一个会话的 workspacePath
        val tasks = result["tasks"]?.let { runCatching { it.jsonArray }.getOrNull() } ?: return null
        return tasks.firstOrNull()?.let {
            runCatching { it.jsonObject["workspacePath"]?.jsonPrimitive?.content }.getOrNull()
        }
    }

    fun disconnect() {
        ConnectionScope.manuallyDisconnected = true
        ConnectionService.stop(getApplication())
        TerminalNotifier.clear(getApplication())
        client?.close()
        channel?.reset()
        conversation?.reset()
        sessionsIndex?.reset()
        workspaceConfig?.reset()
        client = null
        channel = null
        conversation = null
        sessionsIndex = null
        workspaceConfig = null
        // A-2：连接没了，作废在途的握手自动重订与重试上下文
        handshakeRetryJob?.cancel(); handshakeRetryJob = null
        handshakeRetryCount = 0
        convRepo.subscribedSession = null
        // 连接没了就没人能应答：摘掉桥并撤掉通知，避免用户点了个"假批准"
        ApprovalBridge.handler = null
        ElicitationBridge.handler = null
        runCatching { ApprovalNotifier.clearAll(getApplication()) }
        runCatching { ElicitationNotifier.clearAll(getApplication()) }
        approvalRepo.clearAll()
        notifyCoordinator.clearPending()
        syncWidget(0)
        clearAttachments()
        promptDraft = ""
        // C-6：连接没了，本地回显气泡无从确认，清掉避免误导
        convRepo.clearPendingMessages()
    }

    fun forget() {
        disconnect()
        multiStore.clear()
        device = null
        events.clear()
        sessions.clear()
        rpcEvents.clear()
        convRepo.rows.clear()
        convRepo.subscribedSessionId = null
        convRepo.subscribedSession = null
    }


    // ---- 执行模式（P0-B）----

    /**
     * 切换当前会话执行模式（plan/build/yolo）。协议语义（§5.4）：仅对下一轮 agent turn 生效。
     * [onResult] 参数为 null 表示成功，否则为错误信息。
     */
    fun setSessionMode(mode: String, onResult: (String?) -> Unit) {
        val conv = conversation ?: run { onResult("连接尚未就绪"); return }
        conv.setMode(mode) { r ->
            viewModelScope.launch {
                r.fold(
                    onSuccess = {
                        ZLog.i(TAG, "setMode success mode=$mode")
                        sessionModeOverride = mode
                        onResult(null)
                    },
                    onFailure = { err ->
                        ZLog.w(TAG, "setMode failed: ${err.message}")
                        onResult(err.message ?: "切换失败")
                    },
                )
            }
        }
    }

    /**
     * Sprint 1 / P0-A：连接栈归进程级 ConnectionScope 所有，ViewModel 销毁**不关连接** ——
     * 划掉界面后连接与通知仍存活；下次进入 App 时本类重建并经 init{connect()} 归位。
     * （禁止在此 close：那会把 P0-A 退回「连接随 Activity 生灭」。）
     */
    override fun onCleared() { super.onCleared() }

    companion object {
        private const val TAG = "AppViewModel"


        /** 反馈横幅驻留时长（B-3）：失败 8s（4s 真机上易被错过），成功/提示 4s。 */
        internal fun flashDurationMs(kind: FlashKind): Long =
            if (kind == FlashKind.Failure) 8_000L else 4_000L

        /**
         * 桥自动重开的退避延迟（纯函数，单测覆盖）：第 1/2/3 次 → 1s/2s/4s；
         * 超出上限（默认 3）返回 null = 不再重开（停失败态，等 relay 重连或用户操作兜底）。
         */
        internal fun bridgeReopenDelayMs(nextAttempt: Int, maxAttempts: Int = 3): Long? =
            if (nextAttempt in 1..maxAttempts) 1_000L shl (nextAttempt - 1) else null

        /** 握手自动重订次数上限（A-2）：1 次。再失败即交回用户手动重试。 */
        private const val MAX_HANDSHAKE_RETRIES = 1

    }
}
