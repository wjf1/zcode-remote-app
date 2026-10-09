package com.zcode.remote

import android.app.Application
import android.net.ConnectivityManager
import com.zcode.remote.util.ZLog
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
import com.zcode.remote.relay.RelayState
import com.zcode.remote.relay.SessionsIndexChannel
import com.zcode.remote.relay.WorkspaceConfigChannel
import com.zcode.remote.service.ConnectionScope
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
        SettingsStore.ENDPOINT_CUSTOM -> customRelayUrl.takeIf { it.startsWith("ws", ignoreCase = true) }
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

    // ---- 软件版本与在线更新（设置面板）----
    data class UpdateInfo(
        val tagName: String,
        val downloadUrl: String?,
        val body: String?,
        val hasNew: Boolean,
    )

    sealed interface UpdateState {
        data object Idle : UpdateState
        data object Checking : UpdateState
        data class Success(val info: UpdateInfo) : UpdateState
        data class Error(val message: String) : UpdateState
    }

    var updateState by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set
    var githubToken by mutableStateOf(settings.githubToken)
        private set

    fun updateGithubToken(token: String) {
        settings.githubToken = token
        githubToken = token
    }

    fun checkForUpdate() {
        if (updateState is UpdateState.Checking) return
        updateState = UpdateState.Checking
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val reqBuilder = okhttp3.Request.Builder()
                    .url("https://api.github.com/repos/wjf1/zcode-remote-app/releases/latest")
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "ZCodeRemote-Android")

                val token = settings.githubToken.trim()
                if (token.isNotEmpty()) {
                    reqBuilder.header("Authorization", "Bearer $token")
                }

                val response = client.newCall(reqBuilder.build()).execute()
                val bodyStr = response.body?.string()
                if (!response.isSuccessful || bodyStr == null) {
                    val code = response.code
                    val errMsg = when (code) {
                        404, 401 -> if (token.isEmpty()) "私有仓库需配置 GitHub Token 后拉取更新" else "未找到 Release 或 Token 无权限 ($code)"
                        else -> "检查更新失败: HTTP $code"
                    }
                    updateState = UpdateState.Error(errMsg)
                    return@launch
                }

                val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                val root = json.parseToJsonElement(bodyStr).jsonObject
                val tagName = root["tag_name"]?.jsonPrimitive?.content ?: ""
                val releaseBody = root["body"]?.jsonPrimitive?.content ?: ""
                val assets = root["assets"]?.jsonArray ?: kotlinx.serialization.json.JsonArray(emptyList())
                val downloadUrl = assets.firstNotNullOfOrNull { el ->
                    val asset = el.jsonObject
                    val name = asset["name"]?.jsonPrimitive?.content.orEmpty()
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        asset["browser_download_url"]?.jsonPrimitive?.content
                    } else null
                }

                val curVer = BuildConfig.VERSION_NAME.removePrefix("v").trim()
                val remoteVer = tagName.removePrefix("v").trim()
                val hasNew = remoteVer.isNotBlank() && isNewerVersion(curVer, remoteVer)

                updateState = UpdateState.Success(
                    UpdateInfo(
                        tagName = tagName.ifBlank { "最新版" },
                        downloadUrl = downloadUrl,
                        body = releaseBody,
                        hasNew = hasNew,
                    )
                )
            }.onFailure { err ->
                ZLog.w(TAG, "checkForUpdate failed", err)
                updateState = UpdateState.Error("网络连接失败：${UserFacingError.map(err.message ?: "未知错误")}")
            }
        }
    }
    var relayState by mutableStateOf<RelayState>(RelayState.Idle)
        private set

    val events = mutableStateListOf<TaskEvent>()
    val sessions = mutableStateListOf<SessionItem>()

    // ---- 会话桥 / RPC ----
    var bridgeState by mutableStateOf<RpcChannel.BridgeState>(RpcChannel.BridgeState.Closed)
        private set
    var activeWorkspaceKey by mutableStateOf<String?>(null)
        private set
    var subscribedSessionId by mutableStateOf<String?>(null)
        private set

    /** 最近一次订阅的会话条目（A-2 自动重订需要原始 workspacePath/sessionId）。 */
    private var subscribedSession: SessionItem? = null

    /**
     * 握手自动重订计数（A-2）：每次新会话订阅归零；`Status.Live` 到达也归零。
     * 上限 1 次——再失败即置 Failed 并把重试权交回用户（UI 重试入口）。
     */
    private var handshakeRetryCount = 0
    private var handshakeRetryJob: kotlinx.coroutines.Job? = null
    /** 最近收到的 RPC 事件（原始 JSON，供 M2 阶段观测/渲染）。 */
    val rpcEvents = mutableStateListOf<String>()

    // ---- 会话流 ----
    /** 当前会话的行（已按 rowId 排序，UI 直接渲染）。 */
    val rows = mutableStateListOf<ConversationRow>()
    private val rowStore = RowStore(rows)
    var conversationStatus by mutableStateOf<ConversationChannel.Status>(ConversationChannel.Status.Idle)
        private set
    var conversationMeta by mutableStateOf(ConversationChannel.ConversationMeta())
        private set
    /** 向上翻页状态（加载中/hasMore/pulled）。 */
    var earlier by mutableStateOf(ConversationChannel.EarlierState())
        private set

    /**
     * 当前会话的权威快照是否已落地（B-1）。UI 据此在「缓存行数 == 快照行数」
     * （rows.size 不变、旧触发器完全不跑）时也能定位到最新一行。
     */
    var snapshotAligned by mutableStateOf<ConversationChannel.SnapshotAligned?>(null)
        private set

    /**
     * 当前会话的会话级状态（上下文用量 / 目标 / 待办 / 后台任务 / 子智能体 / 排队输入）。
     * 面板与顶栏「状态」入口都读它；全空时 [ConversationFrames.SessionState.hasContent]
     * 为 false，入口不出现。
     */
    var sessionState by mutableStateOf(ConversationFrames.SessionState())
        private set

    // ---- 权限审批 ----
    /** 当前会话的待审批项（会话流 pendingInteractions + 任务事件流两条来源合并）。 */
    var approvals by mutableStateOf<List<PendingApproval>>(emptyList())
        private set
    /** 任务事件流（桌面端实证的审批推送路径）来的待审批，按 interactionId 索引。主线程专用。 */
    private val taskApprovals = LinkedHashMap<String, PendingApproval>()

    // ---- 表单交互（elicitation，P1-1）----
    /** 当前会话的待应答表单（会话帧 userInput 条目 + 任务事件流 elicitation_request 合并）。 */
    var elicitations by mutableStateOf<List<PendingElicitation>>(emptyList())
        private set
    private val taskElicitations = LinkedHashMap<String, PendingElicitation>()

    // ---- 多会话看板（P1-2）----
    /**
     * 每个会话的待处理条数（taskId → 审批 + 表单）。来源＝任务事件流（覆盖所有会话，
     * 不限于当前订阅的那个），订阅中的会话以会话流数据优先（更实时）。
     */
    var sessionPending by mutableStateOf<Map<String, Int>>(emptyMap())
        private set
    /** 桌面端当前活动会话（workspace-list 的 activeTaskId，用于「PC 正在看哪个」提示）。 */
    var desktopActiveTaskId by mutableStateOf<String?>(null)
        private set

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

        viewModelScope.launch {
            c.state.collect { st ->
                relayState = st
                // Sprint 2 / A 组第 3 项：终态失败发常驻系统通知 —— 手机在兜里时不能
                // 静默失去全部审批能力（KICKED/配对失效/协议升级用户都必须立刻知道）。
                val terminal = (st as? RelayState.Failed)?.takeIf {
                    it.reason == FailureReason.KICKED || it.reason == FailureReason.AUTH_FAILED ||
                        it.reason == FailureReason.PROTOCOL_MISMATCH
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
                                PendingApproval.fromTaskEvent(ev.raw, ev.taskId)?.let {
                                    taskApprovals[it.interactionId] = it
                                    refreshApprovals()
                                }
                            "permission_resolved" ->
                                ev.raw["requestId"]?.let {
                                    runCatching { it.jsonPrimitive.content }.getOrNull()
                                }?.let {
                                    if (taskApprovals.remove(it) != null) refreshApprovals()
                                }
                            "elicitation_request" ->
                                PendingElicitation.fromTaskEvent(ev.raw, ev.taskId)?.let {
                                    taskElicitations[it.interactionId] = it
                                    refreshElicitations()
                                }
                            "elicitation_resolved" ->
                                ev.raw["requestId"]?.let {
                                    runCatching { it.jsonPrimitive.content }.getOrNull()
                                }?.let {
                                    if (taskElicitations.remove(it) != null) refreshElicitations()
                                }
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

        viewModelScope.launch {
            ch.bridge.collect { st ->
                bridgeState = st
                ZLog.i(TAG, "bridge=$st")
                if (st is RpcChannel.BridgeState.Ready) {
                    // E-1：workspace 级 sessions-index 订阅（权威角标，一次订阅覆盖全部会话）
                    activeWorkspaceKey?.let { sidx.subscribe(it, null) }
                    // 从 PC 端 ~/.zcode/v2/provider_config.json 预加载已配置模型目录
                    loadModelsFromConfigFile()
                    // 模型注册表：各模型的合法思考档位（createSession/switchModelConfig 必需）
                    loadModelReasoningLevels()
                    val target = sessions.firstOrNull { it.isRunning } ?: sessions.firstOrNull()
                    target?.let { subscribeConversation(it) }
                }
            }
        }

        viewModelScope.launch {
            ch.events.collect { ev ->
                // 会话帧优先交给会话层解析；sessions-index 帧交给 E-1 权威角标通道
                conv.onEvent(ev, rowStore)
                sidx.onEvent(ev)
                wcfg.onEvent(ev)
                // C-5①：观测面板只在 debug 构建保留——release 不再为**每个 delta** 做一次
                // `ev.data.toString()`（全量 JSON 序列化，主线程 collector 内）与 4000 字符拷贝。
                // ZLog.d 本身在 release 已被 R8 剥离（proguard -assumenosideeffects），但实参求值
                // 不会——这才是真凶（任务书 §5 C-5① 纠正点：v1 误判为「ZLog 未剥离」）。
                if (BuildConfig.DEBUG) {
                    val text = ev.data.toString()
                    ZLog.d(TAG, "rpc event id=${ev.id}: ${text.take(400)}")
                    rpcEvents.add(0, text.take(4000))
                    if (rpcEvents.size > 50) rpcEvents.removeAt(rpcEvents.lastIndex)
                }
            }
        }

        viewModelScope.launch {
            conv.status.collect { st ->
                conversationStatus = st
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
        viewModelScope.launch { conv.meta.collect { conversationMeta = it } }
        viewModelScope.launch { conv.snapshotAligned.collect { snapshotAligned = it } }
        viewModelScope.launch { conv.sessionState.collect { sessionState = it } }
        viewModelScope.launch { conv.earlier.collect { earlier = it } }
        viewModelScope.launch {
            conv.interactions.collect { refreshApprovals() }
        }
        viewModelScope.launch {
            conv.elicitations.collect { refreshElicitations() }
        }
        viewModelScope.launch {
            sidx.summaries.collect { recomputeSessionPending() }
        }
        // 通知按钮 → AppViewModel 应答（连接只活在这里，所以桥必须在连接建立时挂上）
        ApprovalBridge.handler = { interactionId, optionId -> resolveById(interactionId, optionId) }
        ElicitationBridge.handler = { interactionId, answerJson ->
            resolveElicitationById(interactionId, answerJson)
        }
    }

    /**
     * 合并两条审批来源（会话流 pendingInteractions + 任务事件流）并对齐通知栏：
     * 新审批弹通知，桌面端已处理/自动决议的自动撤下。
     */
    private fun refreshApprovals() {
        val merged = LinkedHashMap<String, PendingApproval>()
        conversation?.interactions?.value?.forEach { merged[it.interactionId] = it }
        taskApprovals.forEach { (k, v) -> merged[k] = v }
        val next = merged.values.toList()
        if (next == approvals) return
        approvals = next
        ZLog.i(TAG, "approvals → ${next.size} 条 " +
                next.joinToString(",") { "${it.toolName ?: "?"}#${it.interactionId.take(18)}" })
        runCatching { ApprovalNotifier.sync(getApplication(), next) }
            .onFailure { ZLog.w(TAG, "通知栏刷新失败", it) }
        recomputeSessionPending()
    }

    /** 合并表单交互双来源（会话帧 userInput 条目 + 任务事件流），interactionId 去重。 */
    private fun refreshElicitations() {
        val merged = LinkedHashMap<String, PendingElicitation>()
        conversation?.elicitations?.value?.forEach { merged[it.interactionId] = it }
        taskElicitations.forEach { (k, v) -> merged.putIfAbsent(k, v) }
        val next = merged.values.toList()
        if (next == elicitations) return
        elicitations = next
        ZLog.i(TAG, "elicitations → ${next.size} 条 " +
                next.joinToString(",") { "${it.toolName ?: "?"}#${it.interactionId.take(18)}" })
        runCatching { ElicitationNotifier.sync(getApplication(), next) }
            .onFailure { ZLog.w(TAG, "表单通知栏刷新失败", it) }
        recomputeSessionPending()
    }

    /**
     * 多会话看板（P1-2 / E-1）：汇总每个会话的待处理条数。
     * 权威来源＝sessions-index 订阅（服务端 pendingInteractionSummary，覆盖全部会话、消解即清零）；
     * 事件流推导作为回退（仅补权威索引里没有的会话，如订阅失败/未就绪）；
     * 当前订阅会话以会话流明细数覆盖（最实时、含会话帧路径条目），明细为 0 时仍信权威。
     */
    private fun recomputeSessionPending() {
        val counts = HashMap<String, Int>()
        val authoritative = sessionsIndex?.summaries?.value ?: emptyMap()
        if (authoritative.isNotEmpty()) {
            authoritative.forEach { (sid, s) -> if (s.total > 0) counts[sid] = s.total }
            taskApprovals.values.forEach { a ->
                a.sessionId?.let { sid -> if (sid !in authoritative) counts[sid] = (counts[sid] ?: 0) + 1 }
            }
            taskElicitations.values.forEach { e ->
                e.sessionId?.let { sid -> if (sid !in authoritative) counts[sid] = (counts[sid] ?: 0) + 1 }
            }
        } else {
            taskApprovals.values.forEach { a -> a.sessionId?.let { counts[it] = (counts[it] ?: 0) + 1 } }
            taskElicitations.values.forEach { e -> e.sessionId?.let { counts[it] = (counts[it] ?: 0) + 1 } }
        }
        subscribedSessionId?.let { sid ->
            val n = approvals.size + elicitations.size
            when {
                n > 0 -> counts[sid] = n
                (authoritative[sid]?.total ?: 0) <= 0 -> counts.remove(sid)
            }
        }
        if (counts != sessionPending) sessionPending = counts
        syncWidget(counts.values.sum())
    }

    /** 桌面 Widget（P2-3）状态推送：计数或连接变化都走这里。 */
    private fun syncWidget(pendingTotal: Int = sessionPending.values.sum()) {
        val connected = relayState is RelayState.Paired
        runCatching { PendingWidgetProvider.sync(getApplication(), pendingTotal, connected) }
            .onFailure { ZLog.w(TAG, "widget 同步失败", it) }
    }

    /** 上报手机端视图状态（P1-2）：PC 据此在界面上指出"手机正在看这个会话"。 */
    private fun reportViewState() {
        val c = client ?: return
        val payload = BridgeFrames.mobileViewStateUpdate(activeWorkspaceKey, subscribedSessionId)
        c.sendPayload(payload)
        ZLog.i(TAG, "view-state → ws=$activeWorkspaceKey task=${subscribedSessionId?.take(20)}")
    }

    /** 通知栏按钮走这条路径：按 id 找回对象再应答。 */
    private fun resolveById(interactionId: String, optionId: String) {
        val a = approvals.firstOrNull { it.interactionId == interactionId }
        val opt = a?.options?.firstOrNull { it.optionId == optionId }
        if (a == null || opt == null) {
            flash("这条审批已经不在待处理列表里了（可能桌面端已处理）")
            return
        }
        resolve(a, opt)
    }

    /** 应答一次审批（乐观消除 + 会话隔离修复）。 */
    fun resolve(approval: PendingApproval, option: ApprovalOption) {
        val conv = conversation ?: run {
            flash("连接已断开，未发出", FlashKind.Failure)
            return
        }
        val ws = activeWorkspaceKey ?: sessions.firstOrNull()?.workspacePath
        val interId = approval.interactionId

        // 乐观消除：点击瞬间立即从本地列表移除，并刷新系统通知栏
        taskApprovals.remove(interId)
        approvals = approvals.filter { it.interactionId != interId }
        runCatching { ApprovalNotifier.sync(getApplication(), approvals) }
        recomputeSessionPending()

        conv.resolve(approval, option, fallbackWorkspacePath = ws) { r ->
            viewModelScope.launch {
                val (feedback, kind) = when (r) {
                    is ConversationChannel.ResolveResult.Accepted -> when (r.status) {
                        "accepted" -> (if (option.isAllow) "已批准" else "已拒绝") to FlashKind.Info
                        "duplicate" -> "已收到（重复提交，服务端只认第一次）" to FlashKind.Info
                        else -> "服务端已消解（可能桌面端先处理了）" to FlashKind.Info
                    }
                    is ConversationChannel.ResolveResult.Failed -> {
                        // 失败回滚：放回审批列表中
                        taskApprovals[interId] = approval
                        refreshApprovals()
                        "发送失败：${UserFacingError.map(r.message)}" to FlashKind.Failure
                    }
                }
                flash(feedback, kind)
                ZLog.i(TAG, "resolve result=$r interaction=$interId feedback=$feedback")
            }
        }
    }

    // ---- 发送消息 / 停止（P0-1）----

    /** 会话页输入栏草稿（跨重组保持，发送成功才清空）。 */
    var promptDraft by mutableStateOf("")
        private set

    /**
     * 本地回显的待确认消息（C-6）：发送瞬间插入一条 pending 气泡，解决「按了没反应」的空窗期——
     * sendText ack 之后服务端要等 turn 排队才回显 userInput 行（快则几百毫秒、慢则数分钟）。
     *
     * 移除时机：服务端回显同文本的 userInput 行（[ConversationChannel.onUserInputEcho]）→ 移除；
     * 发送失败 → 立即撤回（错误横幅已说明原因）；切会话 / 断开连接 → 清空（气泡与会话绑定）。
     */
    data class PendingUserMessage(val id: Long, val text: String, val attachmentNames: List<String>)

    var pendingUserMessages by mutableStateOf<List<PendingUserMessage>>(emptyList())
        private set
    private var pendingSeq = 0L

    /** 服务端回显到达时按文本匹配移除（多条 pending 时只移除最先插入的那条）。 */
    private fun consumePendingEcho(echoText: String?) {
        pendingUserMessages = consumePendingByEcho(pendingUserMessages, echoText)
    }
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
        pendingSeq += 1
        val pending = PendingUserMessage(pendingSeq, content, atts.mapNotNull { it.fileName })
        pendingUserMessages = pendingUserMessages + pending
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
                        pendingUserMessages = pendingUserMessages.filterNot { it.id == pending.id }
                        flash("发送失败：${UserFacingError.map(it.message)}", FlashKind.Failure)
                    },
                )
            }
        }
    }

    // ---- 附件上传（P1-3，协议见 PROTOCOL.md §6.6）----

    /** 已上传完成、随下一条消息发出去的附件。 */
    val attachments = mutableStateListOf<ConversationChannel.AttachmentRef>()

    /** 上传中的附件（UI 显示进度；同一时刻只允许一个）。 */
    var attachUpload by mutableStateOf<AttachUpload?>(null)
        private set

    data class AttachUpload(val name: String, val uploaded: Long, val total: Long) {
        val percent: Int get() = if (total <= 0) 0 else ((uploaded * 100) / total).toInt()
    }

    /** 在途上传句柄（A-1）：切会话 / 清理附件时用于终止，防止跨会话回写。 */
    private var attachUploadHandle: ConversationChannel.UploadHandle? = null

    /**
     * C-1：上传失败态（可重试）。记录重试所需的全部信息——**特别是 uploadId**：
     * 重试必须复用同一 id 才能命中服务端幂等分支（`state=="committed"` 直接返回 ref，
     * 已上传分片不重传）；原实现 `ConversationChannel` 每次随机生成 id，幂等分支永不命中。
     */
    data class FailedUpload(
        val uri: android.net.Uri,
        val fileName: String,
        val mime: String,
        val size: Long,
        val uploadId: String,
        val message: String,
    )

    var attachFailed by mutableStateOf<FailedUpload?>(null)
        private set

    private fun newUploadId(): String = "upload-${java.util.UUID.randomUUID()}"

    /** C-1：取消在途上传（用户主动放弃，不记失败态）。 */
    fun cancelAttachmentUpload() {
        attachUploadHandle?.abort()
        attachUploadHandle = null
        attachUpload = null
    }

    /** C-1：失败后重试——复用原 uploadId（同一文件不重传已提交的分片）。 */
    fun retryAttachmentUpload() {
        val f = attachFailed ?: return
        attachFailed = null
        startUpload(f.uri, f.fileName, f.mime, f.size, f.uploadId)
    }

    /** C-1：放弃失败的上传（清掉失败态）。 */
    fun dismissAttachFailure() { attachFailed = null }

    /**
     * 附件上传成/败信号（B-3）：tick 每次自增，UI 侧以 `LaunchedEffect(tick)` 消费一次触觉。
     * 上传完成是异步的（由 VM 驱动），Compose 侧拿不到点击上下文，故需要一条信号桥。
     */
    data class AttachmentFeedback(val ok: Boolean, val tick: Long)

    var attachmentFeedback by mutableStateOf<AttachmentFeedback?>(null)
        private set
    private var attachmentFeedbackSeq = 0L

    private fun signalAttachmentFeedback(ok: Boolean) {
        attachmentFeedbackSeq += 1
        attachmentFeedback = AttachmentFeedback(ok, attachmentFeedbackSeq)
    }

    /**
     * 上传一个附件。走 conversation 通道的四步流程（begin/chunk/commit），
     * 成功后加入 [attachments]，UI 显示 chip，发送时随 sendPrompt 带走。
     * P2-3 流式改造：只持 uri，内容经 openStream 分片读（内存峰值一倍分片），
     * 不再整文件读进内存；选中后文件被移动/删除会在上传时报错提示。
     *
     * A-1：上传发起时把「发起会话」捕获进闭包，所有回调先校验会话归属——否则在途上传
     * 完成后会把文件塞进用户已经切过去的另一个会话的附件条（数据正确性 + 隐私风险）。
     *
     * C-1：失败进 [attachFailed] 失败态（UI 提供重试/取消）；重选同一文件复用 uploadId。
     */
    fun addAttachment(uri: android.net.Uri, fileName: String, mime: String, size: Long) {
        val prev = attachFailed
        val id = uploadIdForAttempt(
            prev?.fileName, prev?.size ?: -1L, prev?.uploadId, fileName, size, newUploadId())
        attachFailed = null
        startUpload(uri, fileName, mime, size, id)
    }

    private fun startUpload(uri: android.net.Uri, fileName: String, mime: String, size: Long, uploadId: String) {
        val conv = conversation ?: run { flash("连接已断开，无法上传", FlashKind.Failure); return }
        if (attachUpload != null) { flash("还有附件在上传中"); return }
        if (size > ConversationChannel.MAX_ATTACHMENT_BYTES) {
            flash("附件超过 20MiB 上限")
            return
        }
        val originSession = subscribedSessionId
        attachUpload = AttachUpload(fileName, 0, size)
        val app = getApplication<android.app.Application>()
        attachUploadHandle = conv.uploadAttachment(uploadId, fileName, mime, size,
            openStream = {
                app.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("无法打开所选文件（可能已被移动或删除）")
            },
            onProgress = { up, total ->
                // 切走会话后旧上传的进度不得再点亮新会话的进度条
                if (uploadBelongsTo(originSession, subscribedSessionId)) {
                    attachUpload = AttachUpload(fileName, up, total)
                }
            },
        ) { r ->
            viewModelScope.launch {
                if (!uploadBelongsTo(originSession, subscribedSessionId)) {
                    ZLog.i(TAG, "丢弃跨会话上传回调 origin=$originSession current=$subscribedSessionId")
                    return@launch
                }
                attachUpload = null
                r.fold(
                    onSuccess = {
                        attachments.add(it)
                        attachFailed = null
                        signalAttachmentFeedback(ok = true)
                        flash("已添加附件 ${it.fileName}")
                    },
                    onFailure = {
                        signalAttachmentFeedback(ok = false)
                        // C-1：记录失败态供「重试/取消」；重试复用同一 uploadId
                        attachFailed = FailedUpload(uri, fileName, mime, size, uploadId, it.message ?: "上传失败")
                        flash("附件上传失败：${UserFacingError.map(it.message)}", FlashKind.Failure)
                    },
                )
            }
        }
    }

    fun removeAttachment(ref: ConversationChannel.AttachmentRef) {
        attachments.remove(ref)
    }

    private fun clearAttachments() {
        // A-1：切会话/清理时终止在途上传，避免其回调在别的会话里落地
        attachUploadHandle?.abort()
        attachUploadHandle = null
        attachments.clear()
        attachUpload = null
        attachFailed = null   // C-1：失败态同样与会话绑定
    }

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
     * 提交表单答案：[answers] 为 题号 → 该题答案值列表（含自定义文本）。
     * 官方 web rut 构造：单题 content.answer、多题 content.answer_N，
     * 同时附 answers:{问题文本: "合并答案"} 便于桌面端日志可读。
     */
    fun answerElicitation(el: PendingElicitation, answers: Map<Int, List<String>>) {
        val conv = conversation ?: run { flash("连接已断开，未发送", FlashKind.Failure); return }
        val ws = activeWorkspaceKey ?: sessions.firstOrNull()?.workspacePath
        val interId = el.interactionId

        // 乐观消除：点击后立即从本地列表移除
        taskElicitations.remove(interId)
        elicitations = elicitations.filter { it.interactionId != interId }
        recomputeSessionPending()

        val content = buildJsonObject {
            val readable = LinkedHashMap<String, String>()
            answers.forEach { (idx, values) ->
                if (values.isEmpty()) return@forEach
                val q = el.questions.getOrNull(idx)
                val jsonValues = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }
                if (el.questions.size == 1) {
                    put("answer", if (q?.multiSelect == true) jsonValues else JsonPrimitive(values.first()))
                } else {
                    put("answer_$idx", if (q?.multiSelect == true) jsonValues else JsonPrimitive(values.first()))
                }
                q?.let { readable[it.question] = values.joinToString(", ") }
            }
            if (readable.isNotEmpty()) {
                put("answers", buildJsonObject { readable.forEach { (k, v) -> put(k, JsonPrimitive(v)) } })
            }
        }
        val answer = buildJsonObject {
            put("action", "accept")
            put("content", content)
        }
        conv.resolveElicitation(el, answer, fallbackWorkspacePath = ws) { r ->
            handleElicitationResult(r, "已提交回答", el)
        }
    }

    /** 拒绝表单/计划。 */
    fun declineElicitation(el: PendingElicitation) {
        val conv = conversation ?: run { flash("连接已断开，未发送", FlashKind.Failure); return }
        val ws = activeWorkspaceKey ?: sessions.firstOrNull()?.workspacePath
        val interId = el.interactionId

        taskElicitations.remove(interId)
        elicitations = elicitations.filter { it.interactionId != interId }
        recomputeSessionPending()

        conv.resolveElicitation(el, buildJsonObject { put("action", "decline") }, fallbackWorkspacePath = ws) { r ->
            handleElicitationResult(r, "已拒绝", el)
        }
    }

    /** 计划批准（plan_approval）。 */
    fun approveElicitationPlan(el: PendingElicitation) {
        val conv = conversation ?: run { flash("连接已断开，未发送", FlashKind.Failure); return }
        val ws = activeWorkspaceKey ?: sessions.firstOrNull()?.workspacePath
        val interId = el.interactionId

        taskElicitations.remove(interId)
        elicitations = elicitations.filter { it.interactionId != interId }
        recomputeSessionPending()

        conv.resolveElicitation(el, buildJsonObject { put("action", "accept") }, fallbackWorkspacePath = ws) { r ->
            handleElicitationResult(r, "已批准计划", el)
        }
    }

    /** 自由文本应答（无 questions 的 userInput 条目，freeText=true）。 */
    fun answerElicitationFreeText(el: PendingElicitation, text: String) {
        val conv = conversation ?: run { flash("连接已断开，未发送", FlashKind.Failure); return }
        val ws = activeWorkspaceKey ?: sessions.firstOrNull()?.workspacePath
        val interId = el.interactionId

        taskElicitations.remove(interId)
        elicitations = elicitations.filter { it.interactionId != interId }
        recomputeSessionPending()

        conv.resolveElicitation(el, buildJsonObject { put("freeText", text) }, fallbackWorkspacePath = ws) { r ->
            handleElicitationResult(r, "已提交", el)
        }
    }

    private fun handleElicitationResult(r: ConversationChannel.ResolveResult, okText: String, el: PendingElicitation) {
        viewModelScope.launch {
            when (r) {
                is ConversationChannel.ResolveResult.Accepted -> flash(okText)
                is ConversationChannel.ResolveResult.Failed -> {
                    taskElicitations[el.interactionId] = el
                    refreshElicitations()
                    flash("应答失败：${UserFacingError.map(r.message)}", FlashKind.Failure)
                }
            }
        }
    }

    /**
     * 通知栏快捷应答（D-3）：携带预构造好的 answer JSON 直接提交。
     * 返回 false = 当前无连接（由 receiver 显示"没发出去"）。
     */
    private fun resolveElicitationById(interactionId: String, answerJson: String): Boolean {
        val conv = conversation ?: return false
        val el = elicitations.firstOrNull { it.interactionId == interactionId } ?: return false
        val answer = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(answerJson).jsonObject
        }.getOrNull() ?: return false
        conv.resolveElicitation(el, answer) { r -> flashResolve(r, "已提交") }
        return true
    }

    private fun flashResolve(r: ConversationChannel.ResolveResult, okText: String) {
        viewModelScope.launch {
            when (r) {
                is ConversationChannel.ResolveResult.Accepted -> flash(okText)
                is ConversationChannel.ResolveResult.Failed ->
                    flash("应答失败：${UserFacingError.map(r.message)}", FlashKind.Failure)
            }
        }
    }

    /**
     * 订阅某会话的流式内容。
     *
     * 走 zcode-agent 通道的官方序列：listen → hello → initialize → subscribe
     * （见 research/CONVERSATION-PROTOCOL.md §4）。
     */
    private fun subscribeConversation(s: SessionItem) {
        val conv = conversation ?: return
        val ws = s.workspacePath ?: activeWorkspaceKey ?: return
        subscribedSession = s
        subscribedSessionId = s.taskId
        // A-2：新会话订阅重置自动重订预算
        handshakeRetryJob?.cancel(); handshakeRetryJob = null
        handshakeRetryCount = 0
        clearAttachments()   // 附件与会话绑定，切会话即清空
        pendingUserMessages = emptyList()   // C-6：本地回显气泡同样与会话绑定

        // Sprint 5 离线缓存秒开：点击会话卡片首帧立即同步呈现历史消息行
        rowStore.clear()
        val cached = SessionCacheStore.loadRows(getApplication(), s.taskId)
        if (cached.isNotEmpty()) {
            rowStore.replaceAll(cached)
            ZLog.i(TAG, "离线缓存秒开: 首帧加载 ${cached.size} 行历史消息 session=${s.taskId.take(24)}")
        }

        ZLog.i(TAG, "subscribe conversation session=${s.taskId} ws=$ws")
        conv.subscribe(
            workspacePath = ws,
            workspaceIdentity = null,
            session = s.taskId,
            store = rowStore,
        )
    }

    /**
     * A-2：握手超时等瞬态失败后的自动重订（退避 1s → 2s，上限 1 次）。
     *
     * 只重发订阅，不重载离线缓存、不清附件——那些是「进入会话」的一次性副作用。
     * 再失败则停在 Failed，重试权交回用户（见 [retrySubscribe]）。
     */
    private fun scheduleHandshakeRetry() {
        val s = subscribedSession ?: return
        if (handshakeRetryCount >= MAX_HANDSHAKE_RETRIES) {
            ZLog.w(TAG, "握手自动重订已用尽（$handshakeRetryCount 次），停在失败态等用户重试")
            return
        }
        handshakeRetryCount += 1
        val delayMs = if (handshakeRetryCount == 1) 1_000L else 2_000L
        ZLog.i(TAG, "握手失败，${delayMs}ms 后自动重订（第 $handshakeRetryCount 次）")
        handshakeRetryJob?.cancel()
        handshakeRetryJob = viewModelScope.launch {
            kotlinx.coroutines.delay(delayMs)
            // 期间用户可能已切走会话，此时重订无意义
            if (subscribedSessionId != s.taskId) return@launch
            val conv = conversation ?: return@launch
            val ws = s.workspacePath ?: activeWorkspaceKey ?: return@launch
            ZLog.i(TAG, "自动重订 session=${s.taskId}")
            conv.subscribe(ws, null, s.taskId, rowStore)
        }
    }

    /** 用户手动重试订阅（A-2 的 UI 重试入口）：重置重订预算后重新发起订阅。 */
    fun retrySubscribe() {
        val s = subscribedSession ?: return
        handshakeRetryJob?.cancel(); handshakeRetryJob = null
        handshakeRetryCount = 0
        val conv = conversation ?: run {
            flash("连接已断开，无法重试", FlashKind.Failure)
            return
        }
        val ws = s.workspacePath ?: activeWorkspaceKey ?: run {
            flash("未定位到工作区，无法重试", FlashKind.Failure)
            return
        }
        ZLog.i(TAG, "用户重试订阅 session=${s.taskId}")
        conv.subscribe(ws, null, s.taskId, rowStore)
    }

    /** 手动订阅指定会话（UI 点击会话卡片时调用）。 */
    fun openSession(s: SessionItem) {
        subscribeConversation(s)
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
                        ZLog.i(TAG, "createNewSession success: sid=$newSid ws=$ws " +
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

    // ---- 模型目录（新建会话弹窗与会话内切模型的数据源）----

    /** 工作区模型状态（workspace-config 订阅推送，snapshot/deltas 双路更新）。 */
    val workspaceModelState: WorkspaceConfigChannel.WorkspaceState?
        get() = workspaceConfig?.state?.value

    /** 从 zcode-session::readWorkspaceState 读取的模型列表。 */
    var workspaceSessionModels by mutableStateOf<List<WorkspaceConfigChannel.ModelOption>>(emptyList())
        private set

    /** 综合可用模型列表（优先配置订阅，回退 session 目录）。 */
    val allAvailableModels: List<WorkspaceConfigChannel.ModelOption>
        get() {
            val fromConfig = workspaceConfig?.state?.value?.models ?: emptyList()
            if (fromConfig.isNotEmpty()) return fromConfig
            if (workspaceSessionModels.isNotEmpty()) return workspaceSessionModels
            return emptyList()
        }

    var modelStateLoading by mutableStateOf(false)
        private set

    /**
     * 各模型合法的思考档位：key = "providerId/modelId" → 合法档位值列表。
     * 来源 = PC 端 model-selection::getView（registry 视图，含 model.config.optionSpecs.reasoningLevel.values）。
     * 官方 registry 对推理模型强制校验 reasoningLevel：值不在合法列表里就直接报
     * "Reasoning level is required"（或 not supported）导致会话失败，所以必须按模型真实档位下发。
     */
    var modelReasoningLevels by mutableStateOf<Map<String, List<String>>>(emptyMap())
        private set

    /**
     * 取该模型应当下发的思考档位；无合法档位信息时返回 null（不下发，交由 PC 默认）。
     * 挑选策略：优先启用推理（首个非 disabled 档位），全为 disabled 时才用 disabled。
     * 例如 deepseek [disabled,low,high,max] → "low"；gemini [disabled,enabled] → "enabled"。
     */
    private fun pickReasoningLevel(providerId: String, modelId: String): String? {
        val levels = modelReasoningLevels["$providerId/$modelId"] ?: return null
        return levels.firstOrNull { !it.equals("disabled", ignoreCase = true) }
            ?: levels.firstOrNull()
    }

    /**
     * 拉取 PC 端模型注册表视图（model-selection::getView），提取每个模型的合法思考档位。
     * 非推理模型不会出现在结果里；拉取失败静默降级为不下发 thought。
     */
    fun loadModelReasoningLevels() {
        val ch = channel ?: return
        ch.call(RpcChannel.CHANNEL_MODEL_SELECTION, "getView", listOf<Any>()) { reply ->
            when (reply) {
                is RpcChannel.RpcReply.Err ->
                    ZLog.w(TAG, "model-selection.getView 失败: ${reply.message}")
                is RpcChannel.RpcReply.Ok -> {
                    val raw = reply.data?.toString() ?: ""
                    ZLog.i(TAG, "model-selection.getView 原始响应(截断): ${raw.take(3000)}")
                    val parsed = parseReasoningLevels(reply.data)
                    if (parsed.isNotEmpty()) {
                        modelReasoningLevels = parsed
                        ZLog.i(TAG, "模型思考档位加载成功: " +
                                parsed.entries.joinToString(", ") { "${it.key}->${it.value}" })
                    }
                }
            }
        }
    }

    /**
     * 从 getView 响应中按多种可能的嵌套形态提取 models[].config.optionSpecs.reasoningLevel.values。
     * 兼容：根即视图 / 包在 view|snapshot 里；model 标识在 modelId / ref.modelId / config.modelId。
     */
    private fun parseReasoningLevels(data: kotlinx.serialization.json.JsonElement?): Map<String, List<String>> {
        val out = LinkedHashMap<String, List<String>>()
        fun objOf(e: kotlinx.serialization.json.JsonElement?) =
            runCatching { e?.jsonObject }.getOrNull()

        val root = objOf(data) ?: return out
        // 视图可能被包一层
        val view = objOf(root["view"]) ?: objOf(root["snapshot"]) ?: objOf(root["registry"]) ?: root
        val providers = runCatching { view["providers"]?.jsonArray }.getOrNull() ?: return out

        for (pEl in providers) {
            val p = objOf(pEl) ?: continue
            val pid = runCatching { p["providerId"]?.jsonPrimitive?.content }.getOrNull() ?: continue
            val models = runCatching { p["models"]?.jsonArray }.getOrNull() ?: continue
            for (mEl in models) {
                val m = objOf(mEl) ?: continue
                val cfg = objOf(m["config"])
                val mid = runCatching { m["modelId"]?.jsonPrimitive?.content }.getOrNull()
                    ?: runCatching { objOf(m["ref"])?.get("modelId")?.jsonPrimitive?.content }.getOrNull()
                    ?: runCatching { cfg?.get("modelId")?.jsonPrimitive?.content }.getOrNull()
                    ?: continue
                val levels = runCatching {
                    objOf(cfg?.get("optionSpecs"))
                        ?.get("reasoningLevel")?.jsonObject
                        ?.get("values")?.jsonArray
                        ?.mapNotNull { it.jsonPrimitive.content }
                }.getOrNull()
                if (!levels.isNullOrEmpty()) out["$pid/$mid"] = levels
            }
        }
        return out
    }

    /**
     * 触发一次模型目录刷新：三路并发探测（provider_config 配置文件直读 + workspace-config resync + readWorkspaceState），
     * 只要有一路返回模型数据即可填充到 UI。
     */
    fun loadWorkspaceModels() {
        val ws = activeWorkspaceKey ?: sessions.firstOrNull()?.workspacePath ?: return
        modelStateLoading = true
        // 1) 最稳定的直接链路：通过 file 服务的 resolvePath/readTextFile 读取 ~/.zcode/v2/provider_config.json
        loadModelsFromConfigFile {
            modelStateLoading = false
        }
        // 2) 远程会话目录链路（若支持）
        conversation?.readWorkspaceState(ws) { res ->
            viewModelScope.launch {
                res.onSuccess { state ->
                    // 执行模式的权威来源（P0-B）：settings.mode.current——
                    // 勿用订阅 ack 的 mode（那是订阅模式 snapshot/live，与执行模式撞名）
                    state.currentMode?.takeIf { it.isNotBlank() }?.let {
                        sessionModeFromWorkspace = it
                    }
                    val list = state.available.map {
                        WorkspaceConfigChannel.ModelOption(
                            value = "${it.providerId}/${it.modelId}",
                            name = it.displayName(),
                            providerId = it.providerId,
                            providerName = it.providerLabel,
                        )
                    }
                    if (list.isNotEmpty()) {
                        workspaceSessionModels = list
                        ZLog.i(TAG, "readWorkspaceState 模型加载成功: ${list.size} 个模型")
                    }
                }
            }
        }
        // 3) 配置流 resync
        workspaceConfig?.resync(ws)
    }

    /**
     * 从 PC 端 ~/.zcode/v2/provider_config.json 配置文件中直接拉取用户已配置的模型目录。
     * （先通过 system::info 获取真实的 homedir，再通过 file::readTextFile 读取配置文件，100% 绕过远程 RPC 限制）。
     */
    fun loadModelsFromConfigFile(onDone: () -> Unit = {}) {
        val ch = channel ?: run { onDone(); return }

        fun readConfigByPath(realPath: String) {
            ZLog.i(TAG, "正在读取 PC 端模型配置: $realPath")
            ch.call(RpcChannel.CHANNEL_FILE, "readTextFile", listOf(mapOf("path" to realPath))) { r2 ->
                viewModelScope.launch {
                    when (r2) {
                        is RpcChannel.RpcReply.Err -> {
                            ZLog.w(TAG, "readTextFile 失败 ($realPath): ${r2.message}")
                        }
                        is RpcChannel.RpcReply.Ok -> {
                            val content = runCatching {
                                r2.data?.jsonObject?.get("content")?.jsonPrimitive?.content
                            }.getOrNull()
                            if (!content.isNullOrBlank()) {
                                val parsed = parseProviderConfigModels(content)
                                if (parsed.isNotEmpty()) {
                                    workspaceSessionModels = parsed
                                    ZLog.i(TAG, "从 provider_config.json 成功解析 ${parsed.size} 个可用模型: ${parsed.map { it.name }}")
                                }
                            }
                        }
                    }
                    onDone()
                }
            }
        }

        // 1) 优先调用 system::info 获取桌面系统真实 homedir
        ch.call(RpcChannel.CHANNEL_SYSTEM, "info", listOf<Any>()) { rSys ->
            when (rSys) {
                is RpcChannel.RpcReply.Ok -> {
                    val homedir = runCatching {
                        rSys.data?.jsonObject?.get("homedir")?.jsonPrimitive?.content
                    }.getOrNull()
                    if (!homedir.isNullOrBlank()) {
                        val fullPath = "${homedir.trimEnd('\\', '/')}/.zcode/v2/provider_config.json"
                        readConfigByPath(fullPath)
                        return@call
                    }
                    // homedir 缺失则回退 resolvePath
                    fallbackResolvePath(ch, ::readConfigByPath, onDone)
                }
                is RpcChannel.RpcReply.Err -> {
                    ZLog.w(TAG, "system.info 失败: ${rSys.message}，回退 resolvePath")
                    fallbackResolvePath(ch, ::readConfigByPath, onDone)
                }
            }
        }
    }

    private fun fallbackResolvePath(ch: RpcChannel, onPath: (String) -> Unit, onDone: () -> Unit) {
        val pathArg = mapOf("path" to "~/.zcode/v2/provider_config.json")
        ch.call(RpcChannel.CHANNEL_FILE, "resolvePath", listOf(pathArg)) { r1 ->
            when (r1) {
                is RpcChannel.RpcReply.Err -> {
                    ZLog.w(TAG, "resolvePath 兜底亦失败: ${r1.message}")
                    onDone()
                }
                is RpcChannel.RpcReply.Ok -> {
                    val realPath = r1.data?.let {
                        runCatching { it.jsonPrimitive.content }.getOrNull()
                    } ?: runCatching {
                        r1.data?.jsonObject?.get("path")?.jsonPrimitive?.content
                    }.getOrNull() ?: "~/.zcode/v2/provider_config.json"
                    onPath(realPath)
                }
            }
        }
    }

    /** 解析 provider_config.json 结构中的全部模型。 */
    private fun parseProviderConfigModels(jsonStr: String): List<WorkspaceConfigChannel.ModelOption> {
        val result = mutableListOf<WorkspaceConfigChannel.ModelOption>()
        runCatching {
            val root = Json.parseToJsonElement(jsonStr).jsonObject
            val config = root["config"]?.jsonObject
            val rules = config?.get("providerConfigRules")?.jsonObject?.get("providerRules")?.jsonArray
            rules?.forEach { rEl ->
                val r = rEl.jsonObject
                val pid = r["providerId"]?.jsonPrimitive?.content ?: ""
                val pname = r["providerName"]?.jsonPrimitive?.content
                val cfg = r["config"]?.jsonObject
                val modelIds = (cfg?.get("personalModelIds")?.jsonArray ?: cfg?.get("modelOrder")?.jsonArray)
                    ?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
                    ?: emptyList()
                for (mid in modelIds) {
                    result.add(
                        WorkspaceConfigChannel.ModelOption(
                            value = "$pid/$mid",
                            name = mid,
                            providerId = pid,
                            providerName = pname,
                        )
                    )
                }
            }
        }.onFailure { ZLog.w(TAG, "parseProviderConfigModels 解析异常", it) }
        return result
    }

    /**
     * 在已有会话中动态切换模型（官方 V4 switchModelConfig 原生信封链路）。
     * 成功后服务端广播 state.updated 增量帧，顶栏模型回显即时更新。
     */
    fun switchCurrentSessionModel(modelOption: WorkspaceConfigChannel.ModelOption) {
        val conv = conversation ?: run { flash("连接尚未就绪", FlashKind.Failure); return }
        val (pid, mid) = WorkspaceConfigChannel.splitModelValue(modelOption.value)
        // 用户选了档位就用选的，否则按该模型自动挑一个合法档位，避免 registry 校验失败
        val thought = modelOption.thought ?: pickReasoningLevel(pid, mid)
        conv.switchModelConfig(provider = pid, model = mid, thought = thought) { result ->
            viewModelScope.launch {
                result.fold(
                    onSuccess = { flash("模型已切换为 $mid" + (thought?.let { " · $it" } ?: "")) },
                    onFailure = { flash("切换模型失败: ${UserFacingError.map(it.message)}", FlashKind.Failure) }
                )
            }
        }
    }

    /** 手动输入模型 ID 切换。 */
    fun switchCurrentSessionModelCustom(modelId: String, providerId: String? = null) {
        val conv = conversation ?: run { flash("连接尚未就绪", FlashKind.Failure); return }
        val pid = providerId ?: conversationMeta.provider ?: "glm"
        conv.switchModelConfig(provider = pid, model = modelId.trim(), thought = pickReasoningLevel(pid, modelId.trim())) { result ->
            viewModelScope.launch {
                result.fold(
                    onSuccess = { flash("模型已切换为 $modelId") },
                    onFailure = { flash("切换模型失败: ${UserFacingError.map(it.message)}", FlashKind.Failure) }
                )
            }
        }
    }

    /** 向上拉一页更早历史（会话页滚到顶部时触发）。 */
    fun loadEarlier() {
        val conv = conversation ?: return
        conv.loadEarlier(rowStore) { r ->
            r.onFailure { ZLog.w(TAG, "loadEarlier: ${it.message}") }
        }
    }

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
                        if (subscribedSessionId == item.taskId) {
                            subscribedSessionId = null
                            subscribedSession = null
                            handshakeRetryCount = 0
                            rowStore.clear()
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
        subscribedSession = null
        // 连接没了就没人能应答：摘掉桥并撤掉通知，避免用户点了个"假批准"
        ApprovalBridge.handler = null
        ElicitationBridge.handler = null
        runCatching { ApprovalNotifier.clearAll(getApplication()) }
        runCatching { ElicitationNotifier.clearAll(getApplication()) }
        taskApprovals.clear()
        taskElicitations.clear()
        approvals = emptyList()
        elicitations = emptyList()
        sessionPending = emptyMap()
        syncWidget(0)
        clearAttachments()
        promptDraft = ""
        // C-6：连接没了，本地回显气泡无从确认，清掉避免误导
        pendingUserMessages = emptyList()
    }

    fun forget() {
        disconnect()
        multiStore.clear()
        device = null
        events.clear()
        sessions.clear()
        rpcEvents.clear()
        rows.clear()
        subscribedSessionId = null
        subscribedSession = null
    }

    /** 连接终态通知的短标题（Sprint 2 / A 组）。 */
    private fun terminalTitle(reason: FailureReason): String = when (reason) {
        FailureReason.KICKED -> "控制权已在别处接管"
        FailureReason.AUTH_FAILED -> "配对已失效，请重新扫码"
        FailureReason.PROTOCOL_MISMATCH -> "中继协议可能已升级"
        else -> "连接已终止"
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

        /**
         * 上传回调的会话归属判定（A-1）。仅当发起上传时的会话仍是当前打开的会话时才允许
         * 回写 VM 状态；任一侧为空（未订阅 / 已断开）一律判「不属于」并丢弃回调。
         */
        internal fun uploadBelongsTo(originSession: String?, currentSession: String?): Boolean =
            originSession != null && originSession == currentSession

        /**
         * C-1：上传 id 的复用判定（纯函数，参数化以避免测试依赖 android.net.Uri）。
         * 名字与大小都相同视为同一附件 → 复用原 id 命中服务端幂等（已 committed 直接返回 ref）；
         * 换了文件必须换新 id，否则服务端会把旧文件内容当作新文件提交（数据正确性）。
         * previous* 传 null 表示没有可复用的失败态。
         */
        internal fun uploadIdForAttempt(
            previousFileName: String?,
            previousSize: Long,
            previousUploadId: String?,
            fileName: String,
            size: Long,
            freshId: String,
        ): String =
            if (previousFileName != null && previousUploadId != null &&
                previousFileName == fileName && previousSize == size
            ) previousUploadId else freshId

        /**
         * 服务端回显到达时的 pending 匹配移除（C-6 纯函数）：按文本 trim 相等匹配，
         * 只移除最先插入的一条；不匹配 / 空文本时原样返回
         * （回显可能是桌面端发的消息，不能误删本机的 pending 气泡）。
         */
        internal fun consumePendingByEcho(
            pending: List<PendingUserMessage>,
            echoText: String?,
        ): List<PendingUserMessage> {
            val text = echoText?.trim().orEmpty()
            if (text.isEmpty() || pending.isEmpty()) return pending
            val idx = pending.indexOfFirst { it.text.trim() == text }
            if (idx < 0) return pending
            return pending.filterIndexed { i, _ -> i != idx }
        }

        /** 反馈横幅驻留时长（B-3）：失败 8s（4s 真机上易被错过），成功/提示 4s。 */
        internal fun flashDurationMs(kind: FlashKind): Long =
            if (kind == FlashKind.Failure) 8_000L else 4_000L

        /** 握手自动重订次数上限（A-2）：1 次。再失败即交回用户手动重试。 */
        private const val MAX_HANDSHAKE_RETRIES = 1

        /**
         * 语义化版本比较（检查更新用，2026-10-05 修）：原实现用字符串不等判断——
         * 远端旧版本（0.4.0 < 0.5.0-beta5）会误报"有新版本"。规则按 semver 直觉：
         * 数字段逐位比较；核心版本相等时，remote 无 prerelease 且 current 有 → newer；
         * prerelease 之间按「字母前缀 + 数字」比较（beta10 > beta9，字典序会错）。
         */
        internal fun isNewerVersion(current: String, remote: String): Boolean {
            fun coreOf(v: String): List<Int> =
                v.substringBefore('-').split('.').map { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
            fun preOf(v: String): String = v.substringAfter('-', "")
            fun preKey(p: String): Pair<String, Int> {
                val digits = p.takeLastWhile { it.isDigit() }
                return p.dropLast(digits.length) to (digits.toIntOrNull() ?: 0)
            }
            val cCore = coreOf(current)
            val rCore = coreOf(remote)
            for (i in 0 until maxOf(cCore.size, rCore.size)) {
                val c = cCore.getOrElse(i) { 0 }
                val r = rCore.getOrElse(i) { 0 }
                if (r != c) return r > c
            }
            val cPre = preOf(current)
            val rPre = preOf(remote)
            return when {
                rPre.isEmpty() && cPre.isNotEmpty() -> true   // 同核心版本，remote 是正式版、current 是预发布
                rPre.isNotEmpty() && cPre.isEmpty() -> false
                rPre.isNotEmpty() && cPre.isNotEmpty() -> {
                    val rk = preKey(rPre)
                    val ck = preKey(cPre)
                    if (rk.first != ck.first) rk.first > ck.first else rk.second > ck.second
                }
                else -> false
            }
        }
    }
}
