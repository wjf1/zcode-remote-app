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

    private var client: RelayClient? = null
    private var channel: RpcChannel? = null
    private var conversation: ConversationChannel? = null

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
        client = c
        channel = ch
        conversation = conv
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
                    val target = sessions.firstOrNull { it.isRunning } ?: sessions.firstOrNull()
                    target?.let { subscribeConversation(it) }
                }
            }
        }

        viewModelScope.launch {
            ch.events.collect { ev ->
                // 会话帧优先交给会话层解析
                conv.onEvent(ev, rowStore)
                val text = ev.data.toString()
                // 每个 delta 一条，量大（HANDOVER 技术债）：降为 debug 级，不刷 info 日志
                Log.d(TAG, "rpc event id=${ev.id}: ${text.take(400)}")
                rpcEvents.add(0, text.take(4000))
                if (rpcEvents.size > 50) rpcEvents.removeAt(rpcEvents.lastIndex)
            }
        }

        viewModelScope.launch { conv.status.collect { conversationStatus = it } }
        viewModelScope.launch { conv.meta.collect { conversationMeta = it } }
        viewModelScope.launch { conv.earlier.collect { earlier = it } }
        viewModelScope.launch {
            conv.interactions.collect { refreshApprovals() }
        }
        viewModelScope.launch {
            conv.elicitations.collect { refreshElicitations() }
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
     * 多会话看板（P1-2）：汇总每个会话的待处理条数。
     * 来源＝任务事件流（含所有会话的 permission_request/elicitation_request，带 taskId）；
     * 当前订阅的会话改用会话流数据覆盖（更实时、且含会话帧路径的条目）。
     */
    private fun recomputeSessionPending() {
        val counts = HashMap<String, Int>()
        taskApprovals.values.forEach { a -> a.sessionId?.let { counts[it] = (counts[it] ?: 0) + 1 } }
        taskElicitations.values.forEach { e -> e.sessionId?.let { counts[it] = (counts[it] ?: 0) + 1 } }
        subscribedSessionId?.let { sid ->
            val n = approvals.size + elicitations.size
            if (n > 0) counts[sid] = n else counts.remove(sid)
        }
        if (counts != sessionPending) sessionPending = counts
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

    /** 应答一次审批。 */
    fun resolve(approval: PendingApproval, option: ApprovalOption) {
        val conv = conversation ?: run {
            approvalFeedback = "连接已断开，未发出"
            return
        }
        conv.resolve(approval, option) { r ->
            viewModelScope.launch {
                approvalFeedback = when (r) {
                    is ConversationChannel.ResolveResult.Accepted -> when (r.status) {
                        "accepted" -> if (option.isAllow) "已批准" else "已拒绝"
                        "duplicate" -> "已收到（重复提交，服务端只认第一次）"
                        else -> "服务端已消解（可能桌面端先处理了）"
                    }
                    is ConversationChannel.ResolveResult.Failed -> "发送失败：${r.message}"
                }
                Log.i(TAG, "resolve result=$r interaction=${approval.interactionId}")
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
        feedbackJob = viewModelScope.launch {
            kotlinx.coroutines.delay(4000)
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
     */
    fun addAttachment(fileName: String, mime: String, data: ByteArray) {
        val conv = conversation ?: run { flash("连接已断开，无法上传"); return }
        if (attachUpload != null) { flash("还有附件在上传中"); return }
        attachUpload = AttachUpload(fileName, 0, data.size.toLong())
        conv.uploadAttachment(fileName, mime, data,
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
        conv.resolveElicitation(el, answer) { r -> flashResolve(r, "已提交") }
    }

    /** 拒绝表单/计划。 */
    fun declineElicitation(el: PendingElicitation) {
        val conv = conversation ?: run { flash("连接已断开，未发送"); return }
        conv.resolveElicitation(el, buildJsonObject { put("action", "decline") }) { r ->
            flashResolve(r, "已拒绝")
        }
    }

    /** 计划批准（plan_approval）。 */
    fun approveElicitationPlan(el: PendingElicitation) {
        val conv = conversation ?: run { flash("连接已断开，未发送"); return }
        conv.resolveElicitation(el, buildJsonObject { put("action", "accept") }) { r ->
            flashResolve(r, "已批准计划")
        }
    }

    /** 自由文本应答（无 questions 的 userInput 条目，freeText=true）。 */
    fun answerElicitationFreeText(el: PendingElicitation, text: String) {
        val conv = conversation ?: run { flash("连接已断开，未发送"); return }
        conv.resolveElicitation(el, buildJsonObject { put("freeText", text) }) { r ->
            flashResolve(r, "已提交")
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
        client = null
        channel = null
        conversation = null
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
