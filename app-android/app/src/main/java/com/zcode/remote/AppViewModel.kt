package com.zcode.remote

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zcode.remote.relay.BridgeFrames
import com.zcode.remote.relay.ApprovalOption
import com.zcode.remote.relay.ConversationChannel
import com.zcode.remote.relay.ConversationRow
import com.zcode.remote.relay.PendingApproval
import com.zcode.remote.relay.PendingElicitation
import com.zcode.remote.relay.RelayClient
import com.zcode.remote.relay.RelayState
import com.zcode.remote.relay.SessionsIndexChannel
import com.zcode.remote.relay.WorkspaceConfigChannel
import com.zcode.remote.widget.PendingWidgetProvider
import com.zcode.remote.relay.RowStore
import com.zcode.remote.relay.RpcChannel
import com.zcode.remote.relay.SessionItem
import com.zcode.remote.relay.TaskEvent
import com.zcode.remote.relay.parseBootstrapSessions
import com.zcode.remote.notify.ApprovalBridge
import com.zcode.remote.notify.ApprovalNotifier
import com.zcode.remote.notify.ElicitationBridge
import com.zcode.remote.notify.ElicitationNotifier
import com.zcode.remote.storage.MultiDeviceStore
import com.zcode.remote.storage.PairedDevice
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
                val hasNew = remoteVer.isNotBlank() && remoteVer != curVer

                updateState = UpdateState.Success(
                    UpdateInfo(
                        tagName = tagName.ifBlank { "最新版" },
                        downloadUrl = downloadUrl,
                        body = releaseBody,
                        hasNew = hasNew,
                    )
                )
            }.onFailure { err ->
                Log.w(TAG, "checkForUpdate failed", err)
                updateState = UpdateState.Error("网络连接失败：${err.message ?: "未知错误"}")
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

    // ---- 权限审批 ----
    /** 当前会话的待审批项（会话流 pendingInteractions + 任务事件流两条来源合并）。 */
    var approvals by mutableStateOf<List<PendingApproval>>(emptyList())
        private set
    /** 任务事件流（桌面端实证的审批推送路径）来的待审批，按 interactionId 索引。主线程专用。 */
    private val taskApprovals = LinkedHashMap<String, PendingApproval>()
    /** 最近一次应答的反馈文案（UI 直接显示，用完置空）。 */
    var approvalFeedback by mutableStateOf<String?>(null)
        private set

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

    private var client: RelayClient? = null
    private var channel: RpcChannel? = null
    private var conversation: ConversationChannel? = null
    /** workspace 级会话索引（E-1）：服务端权威角标 pendingInteractionSummary。 */
    private var sessionsIndex: SessionsIndexChannel? = null
    private var workspaceConfig: WorkspaceConfigChannel? = null

    init {
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
        client?.close()
        channel?.reset()
        conversation?.reset()
        val c = RelayClient(dev, relayWsUrlOverride = relayOverride())
        val ch = RpcChannel(c)
        val conv = ConversationChannel(ch)
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
                    }
                    else -> Unit
                }
            }
        }

        viewModelScope.launch {
            ch.bridge.collect { st ->
                bridgeState = st
                Log.i(TAG, "bridge=$st")
                if (st is RpcChannel.BridgeState.Ready) {
                    // E-1：workspace 级 sessions-index 订阅（权威角标，一次订阅覆盖全部会话）
                    activeWorkspaceKey?.let { sidx.subscribe(it, null) }
                    // 从 PC 端 ~/.zcode/v2/provider_config.json 预加载已配置模型目录
                    loadModelsFromConfigFile()
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
                val text = ev.data.toString()
                // 每个 delta 一条，量大（HANDOVER 技术债）：降为 debug 级，不刷 info 日志
                Log.d(TAG, "rpc event id=${ev.id}: ${text.take(400)}")
                rpcEvents.add(0, text.take(4000))
                if (rpcEvents.size > 50) rpcEvents.removeAt(rpcEvents.lastIndex)
            }
        }

        viewModelScope.launch {
            conv.status.collect { st ->
                conversationStatus = st
                // 模型目录订阅需要会话握手完成（真机实证：bridge Ready 即订会报
                // fault.connection.handshakeRequired，Live 后才可用）
                if (st is ConversationChannel.Status.Live) {
                    activeWorkspaceKey?.let { wcfg.subscribe(it, null) }
                }
            }
        }
        viewModelScope.launch { conv.meta.collect { conversationMeta = it } }
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
        Log.i(TAG, "approvals → ${next.size} 条 " +
                next.joinToString(",") { "${it.toolName ?: "?"}#${it.interactionId.take(18)}" })
        runCatching { ApprovalNotifier.sync(getApplication(), next) }
            .onFailure { Log.w(TAG, "通知栏刷新失败", it) }
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
        Log.i(TAG, "elicitations → ${next.size} 条 " +
                next.joinToString(",") { "${it.toolName ?: "?"}#${it.interactionId.take(18)}" })
        runCatching { ElicitationNotifier.sync(getApplication(), next) }
            .onFailure { Log.w(TAG, "表单通知栏刷新失败", it) }
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
            .onFailure { Log.w(TAG, "widget 同步失败", it) }
    }

    /** 上报手机端视图状态（P1-2）：PC 据此在界面上指出"手机正在看这个会话"。 */
    private fun reportViewState() {
        val c = client ?: return
        val payload = BridgeFrames.mobileViewStateUpdate(activeWorkspaceKey, subscribedSessionId)
        c.sendPayload(payload)
        Log.i(TAG, "view-state → ws=$activeWorkspaceKey task=${subscribedSessionId?.take(20)}")
    }

    /** 通知栏按钮走这条路径：按 id 找回对象再应答。 */
    private fun resolveById(interactionId: String, optionId: String) {
        val a = approvals.firstOrNull { it.interactionId == interactionId }
        val opt = a?.options?.firstOrNull { it.optionId == optionId }
        if (a == null || opt == null) {
            approvalFeedback = "这条审批已经不在待处理列表里了（可能桌面端已处理）"
            return
        }
        resolve(a, opt)
    }

    /** 应答一次审批（乐观消除 + 会话隔离修复）。 */
    fun resolve(approval: PendingApproval, option: ApprovalOption) {
        val conv = conversation ?: run {
            approvalFeedback = "连接已断开，未发出"
            flash("连接已断开，未发出")
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
                val feedback = when (r) {
                    is ConversationChannel.ResolveResult.Accepted -> when (r.status) {
                        "accepted" -> if (option.isAllow) "已批准" else "已拒绝"
                        "duplicate" -> "已收到（重复提交，服务端只认第一次）"
                        else -> "服务端已消解（可能桌面端先处理了）"
                    }
                    is ConversationChannel.ResolveResult.Failed -> {
                        // 失败回滚：放回审批列表中
                        taskApprovals[interId] = approval
                        refreshApprovals()
                        "发送失败：${r.message}"
                    }
                }
                approvalFeedback = feedback
                flash(feedback)
                Log.i(TAG, "resolve result=$r interaction=$interId feedback=$feedback")
            }
        }
    }

    fun consumeApprovalFeedback() { approvalFeedback = null }

    // ---- 发送消息 / 停止（P0-1）----

    /** 会话页输入栏草稿（跨重组保持，发送成功才清空）。 */
    var promptDraft by mutableStateOf("")
        private set
    var sending by mutableStateOf(false)
        private set
    /** 发送/停止的操作反馈（与审批反馈同一展示位，几秒后自动清除）。 */
    var commandFeedback by mutableStateOf<String?>(null)
        private set

    private var feedbackJob: kotlinx.coroutines.Job? = null

    private fun flash(msg: String) {
        commandFeedback = msg
        feedbackJob?.cancel()
        // 失败类提示留 8s（4s 真机上易被错过，2026-09-30 验收发现）；成功提示仍 4s 免打扰
        val ms = if (msg.startsWith("发送失败") || msg.startsWith("应答失败") ||
            msg.startsWith("连接已断开")) 8_000L else 4_000L
        feedbackJob = viewModelScope.launch {
            kotlinx.coroutines.delay(ms)
            commandFeedback = null
        }
    }

    fun updatePromptDraft(v: String) { promptDraft = v }

    /** 发送输入栏消息：成功后服务端把 userInput 行推回会话流（无需本地 append）。 */
    fun sendPrompt() {
        val content = promptDraft.trim()
        val atts = attachments.toList()
        if ((content.isEmpty() && atts.isEmpty()) || sending) return
        val conv = conversation ?: run { flash("连接已断开，未发送"); return }
        sending = true
        conv.sendPrompt(content, atts) { r ->
            viewModelScope.launch {
                sending = false
                r.fold(
                    onSuccess = {
                        promptDraft = ""
                        attachments.clear()
                        Log.i(TAG, "sendPrompt ok session=$subscribedSessionId atts=${atts.size}")
                    },
                    onFailure = { flash("发送失败：${it.message}") },
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

    /**
     * 上传一个附件。走 conversation 通道的四步流程（begin/chunk/commit），
     * 成功后加入 [attachments]，UI 显示 chip，发送时随 sendPrompt 带走。
     * P2-3 流式改造：只持 uri，内容经 openStream 分片读（内存峰值一倍分片），
     * 不再整文件读进内存；选中后文件被移动/删除会在上传时报错提示。
     */
    fun addAttachment(uri: android.net.Uri, fileName: String, mime: String, size: Long) {
        val conv = conversation ?: run { flash("连接已断开，无法上传"); return }
        if (attachUpload != null) { flash("还有附件在上传中"); return }
        if (size > ConversationChannel.MAX_ATTACHMENT_BYTES) {
            flash("附件超过 20MiB 上限")
            return
        }
        attachUpload = AttachUpload(fileName, 0, size)
        val app = getApplication<android.app.Application>()
        conv.uploadAttachment(fileName, mime, size,
            openStream = {
                app.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("无法打开所选文件（可能已被移动或删除）")
            },
            onProgress = { up, total -> attachUpload = AttachUpload(fileName, up, total) },
        ) { r ->
            viewModelScope.launch {
                attachUpload = null
                r.fold(
                    onSuccess = {
                        attachments.add(it)
                        flash("已添加附件 ${it.fileName}")
                    },
                    onFailure = { flash("附件上传失败：${it.message}") },
                )
            }
        }
    }

    fun removeAttachment(ref: ConversationChannel.AttachmentRef) {
        attachments.remove(ref)
    }

    private fun clearAttachments() {
        attachments.clear()
        attachUpload = null
    }

    /** 停止当前运行（envelope `stop` 命令，官方 web 同款）。 */
    fun stopSession() {
        val conv = conversation ?: run { flash("连接已断开，未发送"); return }
        conv.stop { r ->
            viewModelScope.launch {
                flash(
                    when (r) {
                        is ConversationChannel.ResolveResult.Accepted -> "已请求停止"
                        is ConversationChannel.ResolveResult.Failed -> "停止失败：${r.message}"
                    }
                )
                Log.i(TAG, "stop result=$r")
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
        val conv = conversation ?: run { flash("连接已断开，未发送"); return }
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
        val conv = conversation ?: run { flash("连接已断开，未发送"); return }
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
        val conv = conversation ?: run { flash("连接已断开，未发送"); return }
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
        val conv = conversation ?: run { flash("连接已断开，未发送"); return }
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
                    flash("应答失败：${r.message}")
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
            flash(
                when (r) {
                    is ConversationChannel.ResolveResult.Accepted -> okText
                    is ConversationChannel.ResolveResult.Failed -> "应答失败：${r.message}"
                }
            )
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
        subscribedSessionId = s.taskId
        clearAttachments()   // 附件与会话绑定，切会话即清空
        Log.i(TAG, "subscribe conversation session=${s.taskId} ws=$ws")
        conv.subscribe(
            workspacePath = ws,
            workspaceIdentity = null,
            session = s.taskId,
            store = rowStore,
        )
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
                thought = "enabled",
                mode = "yolo",
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
                        Log.i(TAG, "createNewSession success: sid=$newSid ws=$ws " +
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
                        }
                        if (promptText.isNotEmpty()) {
                            promptDraft = promptText
                        }
                        openSession(item)
                        onSuccess(item)
                    },
                    onFailure = { err ->
                        Log.w(TAG, "createNewSession failed: ${err.message}")
                        onError(err.message ?: "创建会话失败")
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
                        Log.i(TAG, "readWorkspaceState 模型加载成功: ${list.size} 个模型")
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
            Log.i(TAG, "正在读取 PC 端模型配置: $realPath")
            ch.call(RpcChannel.CHANNEL_FILE, "readTextFile", listOf(mapOf("path" to realPath))) { r2 ->
                viewModelScope.launch {
                    when (r2) {
                        is RpcChannel.RpcReply.Err -> {
                            Log.w(TAG, "readTextFile 失败 ($realPath): ${r2.message}")
                        }
                        is RpcChannel.RpcReply.Ok -> {
                            val content = runCatching {
                                r2.data?.jsonObject?.get("content")?.jsonPrimitive?.content
                            }.getOrNull()
                            if (!content.isNullOrBlank()) {
                                val parsed = parseProviderConfigModels(content)
                                if (parsed.isNotEmpty()) {
                                    workspaceSessionModels = parsed
                                    Log.i(TAG, "从 provider_config.json 成功解析 ${parsed.size} 个可用模型: ${parsed.map { it.name }}")
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
                    Log.w(TAG, "system.info 失败: ${rSys.message}，回退 resolvePath")
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
                    Log.w(TAG, "resolvePath 兜底亦失败: ${r1.message}")
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
        }.onFailure { Log.w(TAG, "parseProviderConfigModels 解析异常", it) }
        return result
    }

    /**
     * 在已有会话中动态切换模型（官方 V4 switchModelConfig 原生信封链路）。
     * 成功后服务端广播 state.updated 增量帧，顶栏模型回显即时更新。
     */
    fun switchCurrentSessionModel(modelOption: WorkspaceConfigChannel.ModelOption) {
        val conv = conversation ?: run { flash("连接尚未就绪"); return }
        val (pid, mid) = WorkspaceConfigChannel.splitModelValue(modelOption.value)
        conv.switchModelConfig(provider = pid, model = mid) { result ->
            viewModelScope.launch {
                result.fold(
                    onSuccess = { flash("模型已切换为 $mid") },
                    onFailure = { flash("切换模型失败: ${it.message}") }
                )
            }
        }
    }

    /** 手动输入模型 ID 切换。 */
    fun switchCurrentSessionModelCustom(modelId: String, providerId: String? = null) {
        val conv = conversation ?: run { flash("连接尚未就绪"); return }
        val pid = providerId ?: conversationMeta.provider ?: "glm"
        conv.switchModelConfig(provider = pid, model = modelId.trim()) { result ->
            viewModelScope.launch {
                result.fold(
                    onSuccess = { flash("模型已切换为 $modelId") },
                    onFailure = { flash("切换模型失败: ${it.message}") }
                )
            }
        }
    }

    /** 向上拉一页更早历史（会话页滚到顶部时触发）。 */
    fun loadEarlier() {
        val conv = conversation ?: return
        conv.loadEarlier(rowStore) { r ->
            r.onFailure { Log.w(TAG, "loadEarlier: ${it.message}") }
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
    }

    companion object { private const val TAG = "AppViewModel" }
}
