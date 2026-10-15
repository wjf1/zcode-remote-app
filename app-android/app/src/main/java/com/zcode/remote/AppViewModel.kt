package com.zcode.remote

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zcode.remote.notify.ApprovalBridge
import com.zcode.remote.notify.ApprovalNotifier
import com.zcode.remote.notify.ElicitationBridge
import com.zcode.remote.notify.ElicitationNotifier
import com.zcode.remote.notify.TerminalNotifier
import com.zcode.remote.relay.ApprovalOption
import com.zcode.remote.relay.ConversationChannel
import com.zcode.remote.relay.ConversationFrames
import com.zcode.remote.relay.ConversationRow
import com.zcode.remote.relay.FailureReason
import com.zcode.remote.relay.PendingApproval
import com.zcode.remote.relay.PendingElicitation
import com.zcode.remote.relay.RelayClient
import com.zcode.remote.relay.RelayEndpointValidator
import com.zcode.remote.relay.RelayState
import com.zcode.remote.relay.RpcChannel
import com.zcode.remote.relay.SessionItem
import com.zcode.remote.relay.SessionsIndexChannel
import com.zcode.remote.relay.TaskEvent
import com.zcode.remote.relay.UserFacingError
import com.zcode.remote.relay.WorkspaceConfigChannel
import com.zcode.remote.repo.ApprovalRepository
import com.zcode.remote.repo.AttachmentUploader
import com.zcode.remote.repo.ConversationRepository
import com.zcode.remote.repo.ModelConfigRepository
import com.zcode.remote.repo.NotificationCoordinator
import com.zcode.remote.repo.UpdateRepository
import com.zcode.remote.service.ConnectionCoordinator
import com.zcode.remote.service.ConnectionScope
import com.zcode.remote.service.ConnectionService
import com.zcode.remote.service.RemoteConnectionManager
import com.zcode.remote.storage.MultiDeviceStore
import com.zcode.remote.storage.PairedDevice
import com.zcode.remote.storage.SessionCacheStore
import com.zcode.remote.storage.SettingsStore
import com.zcode.remote.util.LogRedactor
import com.zcode.remote.util.ZLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 应用状态中枢：配对 → 中继连接 → bootstrap（会话列表）→ 开桥 → RPC 会话流。
 * 协议见 PROTOCOL.md 与 research/FRAME-CODEC.md。
 */
class AppViewModel(
    app: Application,
) : AndroidViewModel(app),
    ConnectionCoordinator.ConnectionHost {
    private val multiStore = MultiDeviceStore(app)
    private val settings = SettingsStore(app)

    // ---- §11.7 结构性拆分：职责域 repository（实现见 repo/，此处装配并转发）----
    private val updateRepo = UpdateRepository(viewModelScope, settings)
    private val uploader =
        AttachmentUploader(
            viewModelScope,
            app,
            conversation = { conversation },
            subscribedSessionId = { subscribedSessionId },
            flash = { msg, failure -> if (failure) flash(msg, FlashKind.Failure) else flash(msg) },
        )
    private val modelRepo =
        ModelConfigRepository(
            viewModelScope,
            channel = { channel },
            conversation = { conversation },
            workspaceConfig = { workspaceConfig },
            activeWorkspaceKey = { activeWorkspaceKey },
            firstSessionWorkspace = { sessions.firstOrNull()?.workspacePath },
            onWorkspaceMode = { sessionModeFromWorkspace = it },
            flash = { msg, failure -> if (failure) flash(msg, FlashKind.Failure) else flash(msg) },
        )

    private val convRepo =
        ConversationRepository(
            viewModelScope,
            app,
            conversation = { conversation },
            activeWorkspaceKey = { activeWorkspaceKey },
            flash = { msg, failure -> if (failure) flash(msg, FlashKind.Failure) else flash(msg) },
            onNewSubscription = { connectionCoordinator.resetHandshakeRetry() },
            clearAttachments = { uploader.clearAttachments() },
        )
    private val approvalRepo =
        ApprovalRepository(
            viewModelScope,
            app,
            conversation = { conversation },
            activeWorkspaceKey = { activeWorkspaceKey },
            firstSessionWorkspace = { sessions.firstOrNull()?.workspacePath },
            flash = { msg, failure -> if (failure) flash(msg, FlashKind.Failure) else flash(msg) },
            onPendingChanged = { recomputeSessionPending() },
        )

    private val notifyCoordinator: NotificationCoordinator =
        NotificationCoordinator(
            app,
            client = { client },
            sessionsIndex = { sessionsIndex },
            relayState = { relayState },
            activeWorkspaceKey = { activeWorkspaceKey },
            subscribedSessionId = { subscribedSessionId },
            approvalsRepo = approvalRepo,
        )
    private val connectionCoordinator: ConnectionCoordinator =
        ConnectionCoordinator(
            host = this,
            convRepo = convRepo,
            approvalRepo = approvalRepo,
            modelRepo = modelRepo,
            notifyCoordinator = notifyCoordinator,
        )

    // ---- §11.7 拆分：ConnectionHost 实现（connect 编排对 VM 的全部触碰点）----
    override val hostContext: Context get() = getApplication()
    override val hostScope: CoroutineScope get() = viewModelScope
    override val hostDevice: PairedDevice? get() = device

    override fun hostRelayOverride(): String? = relayOverride()

    override var hostRelayState: RelayState
        get() = relayState
        set(value) {
            relayState = value
        }
    override val hostEvents: MutableList<TaskEvent> get() = events
    override val hostSessions: MutableList<SessionItem> get() = sessions
    override var hostBridgeState: RpcChannel.BridgeState
        get() = bridgeState
        set(value) {
            bridgeState = value
        }
    override var hostActiveWorkspaceKey: String?
        get() = activeWorkspaceKey
        set(value) {
            activeWorkspaceKey = value
        }
    override val hostRpcEvents: MutableList<String> get() = rpcEvents
    override var hostSessionModeOverride: String?
        get() = sessionModeOverride
        set(value) {
            sessionModeOverride = value
        }

    override fun hostRetrySubscribe() = retrySubscribe()

    /** 活跃设备（当前连接的这台）。 */
    var device by mutableStateOf(
        multiStore.load().let { s ->
            s.devices.firstOrNull { it.deviceSid == s.activeSid } ?: s.devices.firstOrNull()
        },
    )
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
    private fun relayOverride(): String? =
        when (endpointMode) {
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

    fun setEndpoint(
        mode: String,
        customUrl: String? = null,
    ) {
        settings.endpointMode = mode
        endpointMode = mode
        customUrl?.let {
            settings.customRelayUrl = it
            customRelayUrl = it
        }
        if (device != null) connect() // 线路变更立即重连生效
    }

    fun setTheme(mode: String) {
        settings.themeMode = mode
        themeMode = mode
    }

    // ---- 软件版本与在线更新（§11.7 拆分：实现在 repo/UpdateRepository，此处转发保持签名不变）----
    val updateState: UpdateRepository.UpdateState get() = updateRepo.updateState

    /** 只暴露「是否已配置」——明文 Token 不进 UI 状态（走查 #10）。 */
    val hasGithubToken: Boolean get() = updateRepo.hasGithubToken

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

    fun resolveById(
        interactionId: String,
        optionId: String,
    ) = approvalRepo.resolveById(interactionId, optionId)

    fun resolve(
        approval: PendingApproval,
        option: ApprovalOption,
    ) = approvalRepo.resolve(approval, option)

    fun answerElicitation(
        el: PendingElicitation,
        answers: Map<Int, List<String>>,
    ) = approvalRepo.answerElicitation(el, answers)

    fun declineElicitation(el: PendingElicitation) = approvalRepo.declineElicitation(el)

    fun approveElicitationPlan(el: PendingElicitation) = approvalRepo.approveElicitationPlan(el)

    fun answerElicitationFreeText(
        el: PendingElicitation,
        text: String,
    ) = approvalRepo.answerElicitationFreeText(el, text)

    private fun refreshApprovals() = approvalRepo.refreshApprovals()

    private fun refreshElicitations() = approvalRepo.refreshElicitations()

    private fun resolveElicitationById(
        interactionId: String,
        answerJson: String,
    ): Boolean = approvalRepo.resolveElicitationById(interactionId, answerJson)

    // ---- 多会话看板 / Widget / 视图上报（§11.7 拆分：实现在 repo/NotificationCoordinator，此处转发）----

    /** 每个会话的待处理条数（taskId → 审批 + 表单）。 */
    val sessionPending: Map<String, Int> get() = notifyCoordinator.sessionPending

    /** 桌面端当前活动会话（workspace-list 的 activeTaskId，用于「PC 正在看哪个」提示）。 */
    val desktopActiveTaskId: String? get() = notifyCoordinator.desktopActiveTaskId

    private fun recomputeSessionPending() = notifyCoordinator.recomputeSessionPending()

    private fun syncWidget(pendingTotal: Int = notifyCoordinator.sessionPending.values.sum()) = notifyCoordinator.syncWidget(pendingTotal)

    private fun reportViewState() = notifyCoordinator.reportViewState()

    private fun terminalTitle(reason: FailureReason): String = NotificationCoordinator.terminalTitle(reason)

    /** 首页会话搜索关键字（P2-3：按标题/工作区过滤，忽略大小写）。 */
    var sessionQuery by mutableStateOf("")
        private set

    fun updateSessionQuery(q: String) {
        sessionQuery = q
    }

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
        set(value) {
            ConnectionScope.client = value
        }

    // ---- 会话文件预览（Sprint 3 第二步·剧本 B「最近文件」）----
    // 纯派生状态（点击触发，不在 init 路径），但统一放顶部区避免初始化顺序坑。
    sealed interface FilePreview {
        data object Loading : FilePreview

        data class Loaded(
            val path: String,
            val content: String,
        ) : FilePreview

        data class Failed(
            val path: String,
            val message: String,
        ) : FilePreview
    }

    var filePreview by mutableStateOf<FilePreview?>(null)
        private set

    /** 预览一个文件：~ 开头先经 resolvePath 展开（provider_config 链路同款），再 readTextFile。 */
    fun previewSessionFile(path: String) {
        filePreview = FilePreview.Loading
        readRemoteTextFile(path) { r ->
            viewModelScope.launch {
                filePreview =
                    r.fold(
                        onSuccess = { FilePreview.Loaded(path, it) },
                        onFailure = { FilePreview.Failed(path, UserFacingError.map(it.message)) },
                    )
            }
        }
    }

    fun dismissFilePreview() {
        filePreview = null
    }

    private fun readRemoteTextFile(
        path: String,
        onResult: (Result<String>) -> Unit,
    ) {
        val ch =
            channel ?: run {
                onResult(Result.failure(IllegalStateException("连接尚未就绪")))
                return
            }

        fun doRead(p: String) {
            ch.call(RpcChannel.CHANNEL_FILE, "readTextFile", listOf(mapOf("path" to p))) { r ->
                viewModelScope.launch {
                    when (r) {
                        is RpcChannel.RpcReply.Err ->
                            onResult(Result.failure(IllegalStateException(r.message)))
                        is RpcChannel.RpcReply.Ok -> {
                            val content =
                                runCatching {
                                    r.data
                                        ?.jsonObject
                                        ?.get("content")
                                        ?.jsonPrimitive
                                        ?.content
                                }.getOrNull()
                            if (content != null) {
                                onResult(Result.success(content))
                            } else {
                                onResult(Result.failure(IllegalStateException("响应缺少 content 字段")))
                            }
                        }
                    }
                }
            }
        }
        if (path.startsWith("~")) {
            ch.call(RpcChannel.CHANNEL_FILE, "resolvePath", listOf(mapOf("path" to path))) { r1 ->
                viewModelScope.launch {
                    val real =
                        when (r1) {
                            is RpcChannel.RpcReply.Ok ->
                                runCatching { r1.data?.jsonPrimitive?.content }.getOrNull()
                                    ?: runCatching {
                                        r1.data
                                            ?.jsonObject
                                            ?.get("path")
                                            ?.jsonPrimitive
                                            ?.content
                                    }.getOrNull()
                                    ?: path
                            else -> path
                        }
                    doRead(real)
                }
            }
        } else {
            doRead(path)
        }
    }

    private var channel: RpcChannel?
        get() = ConnectionScope.channel
        set(value) {
            ConnectionScope.channel = value
        }
    private var conversation: ConversationChannel?
        get() = ConnectionScope.conversation
        set(value) {
            ConnectionScope.conversation = value
        }

    /** workspace 级会话索引（E-1）：服务端权威角标 pendingInteractionSummary。 */
    private var sessionsIndex: SessionsIndexChannel?
        get() = ConnectionScope.sessionsIndex
        set(value) {
            ConnectionScope.sessionsIndex = value
        }
    private var workspaceConfig: WorkspaceConfigChannel?
        get() = ConnectionScope.workspaceConfig
        set(value) {
            ConnectionScope.workspaceConfig = value
        }

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

    fun connect() = connectionCoordinator.connect()

    // ---- 发送消息 / 停止（P0-1）----

    /**
     * 会话页输入栏草稿（跨重组保持，发送成功才清空）。
     * §11.7 拆分：草稿存储已移入 [ConversationRepository]（按 taskId 隔离，防串会话），
     * 此处转发仅保持 UI 调用签名不变。
     */
    val promptDraft: String get() = convRepo.promptDraft

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

    private fun flash(
        msg: String,
        kind: FlashKind = FlashKind.Info,
    ) {
        commandFeedback = msg
        feedbackIsFailure = kind == FlashKind.Failure
        feedbackJob?.cancel()
        // 失败类提示留 8s（4s 真机上易被错过，2026-09-30 验收发现）；成功提示仍 4s 免打扰
        val ms = flashDurationMs(kind)
        feedbackJob =
            viewModelScope.launch {
                kotlinx.coroutines.delay(ms)
                commandFeedback = null
            }
    }

    fun updatePromptDraft(v: String) {
        convRepo.updatePromptDraft(v)
    }

    /** 发送输入栏消息：成功后服务端把 userInput 行推回会话流（本地只留 pending 回显气泡）。 */
    fun sendPrompt() {
        val content = promptDraft.trim()
        val atts = attachments.toList()
        if ((content.isEmpty() && atts.isEmpty()) || sending) return
        val conv =
            conversation ?: run {
                flash("连接已断开，未发送", FlashKind.Failure)
                return
            }
        sending = true
        // C-6：发送瞬间先插一条本地回显气泡（发送失败时撤回，服务端回显到达时移除）
        val pending = convRepo.addPendingMessage(content, atts.mapNotNull { it.fileName })
        conv.sendPrompt(content, atts) { r ->
            viewModelScope.launch {
                sending = false
                r.fold(
                    onSuccess = {
                        convRepo.clearPromptDraft()
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

    fun addAttachment(
        uri: android.net.Uri,
        fileName: String,
        mime: String,
        size: Long,
    ) = uploader.addAttachment(uri, fileName, mime, size)

    fun removeAttachment(ref: ConversationChannel.AttachmentRef) = uploader.removeAttachment(ref)

    fun cancelAttachmentUpload() = uploader.cancelAttachmentUpload()

    fun retryAttachmentUpload() = uploader.retryAttachmentUpload()

    fun dismissAttachFailure() = uploader.dismissAttachFailure()

    private fun clearAttachments() = uploader.clearAttachments()

    /** 停止当前运行（envelope `stop` 命令，官方 web 同款）。 */
    fun stopSession() {
        val conv =
            conversation ?: run {
                flash("连接已断开，未发送", FlashKind.Failure)
                return
            }
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

    /** 手动订阅指定会话（UI 点击会话卡片时调用）。 */
    fun openSession(s: SessionItem) {
        convRepo.subscribeConversation(s)
        reportViewState() // P1-2：切换会话即上报，PC 端可提示"手机正在看这个会话"
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
        val conv =
            conversation ?: run {
                onError("连接尚未就绪，无法创建会话")
                return
            }
        val ws =
            activeWorkspaceKey ?: sessions.firstOrNull()?.workspacePath ?: run {
                onError("未定位到活动工作区，请在 PC 端打开一个工作区后再试")
                return
            }

        // 把 "providerId/modelId" 选项值拆成 createSession config 需要的 {provider, model}
        val modelConfig =
            modelOption?.let {
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
                        ZLog.i(
                            TAG,
                            "createNewSession success: sid=${LogRedactor.maskId(newSid)} ws=${LogRedactor.pathLabel(ws)} " +
                                "model=${modelConfig?.modelId ?: "inherit-default"}",
                        )
                        val item =
                            SessionItem(
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
                        convRepo.clearPromptDraft()
                        clearAttachments()
                        openSession(item)
                        onSuccess(item)
                    },
                    onFailure = { err ->
                        ZLog.w(TAG, "createNewSession failed: ${err.message}")
                        onError(UserFacingError.map(err.message ?: "创建会话失败"))
                    },
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
    private fun pickReasoningLevel(
        providerId: String,
        modelId: String,
    ): String? = modelRepo.pickReasoningLevel(providerId, modelId)

    /**
     * 删除会话（官方移动端同款链路）：
     * 1) 若会话在运行，先走 `zcode-session::closeSession` 结束（fire-and-forget，失败不阻断）；
     * 2) 再走 `zcode-task::deleteTask` 软删除（task index 写入 deleted=1，bootstrap/列表不再下发）。
     * 成功后从本地会话列表移除；若删除的是当前订阅中的会话，同步复位订阅状态。
     */
    fun deleteSession(
        item: SessionItem,
        onResult: (Boolean, String) -> Unit = { _, _ -> },
    ) {
        val ch =
            channel ?: run {
                onResult(false, "连接尚未就绪")
                return
            }
        val ws =
            item.workspacePath ?: activeWorkspaceKey ?: run {
                onResult(false, "未定位到工作区")
                return
            }
        val target =
            buildMap<String, Any> {
                put("workspacePath", ws)
                put("taskId", item.taskId)
            }

        // 1) 先尝试结束运行中的会话（失败不阻断删除）
        ch.call(
            RpcChannel.CHANNEL_SESSION,
            "closeSession",
            listOf(mapOf("workspacePath" to ws, "sessionId" to item.taskId)),
        ) { r ->
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
                            connectionCoordinator.resetHandshakeRetry()
                            convRepo.rowStore.clear()
                            convRepo.clearPromptDraft()
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
        // 注销网络回调：断开后不再需要感知网络变化，且下次 connect() 会新建闸门（防累积）
        RemoteConnectionManager.releaseGate()
        client = null
        channel = null
        conversation = null
        sessionsIndex = null
        workspaceConfig = null
        // A-2：连接没了，作废在途的握手自动重订与桥重开调度（含预算归零）
        connectionCoordinator.cancelResilienceJobs()
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
        // 草稿按会话隔离，断开时全部清空（凭据/会话上下文都不在了）
        convRepo.clearAllDrafts()
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
        convRepo.clearAllDrafts()
    }

    // ---- 执行模式（P0-B）----

    /**
     * 切换当前会话执行模式（plan/build/yolo）。协议语义（§5.4）：仅对下一轮 agent turn 生效。
     * [onResult] 参数为 null 表示成功，否则为错误信息。
     */
    fun setSessionMode(
        mode: String,
        onResult: (String?) -> Unit,
    ) {
        val conv =
            conversation ?: run {
                onResult("连接尚未就绪")
                return
            }
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
    override fun onCleared() {
        super.onCleared()
    }

    companion object {
        private const val TAG = "AppViewModel"

        /** 反馈横幅驻留时长（B-3）：失败 8s（4s 真机上易被错过），成功/提示 4s。 */
        internal fun flashDurationMs(kind: FlashKind): Long = if (kind == FlashKind.Failure) 8_000L else 4_000L
    }
}
