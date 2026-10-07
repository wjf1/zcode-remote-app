package com.zcode.remote.relay

import android.util.Base64
import com.zcode.remote.util.ZLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * 会话流通道：握手 → 监听 → 订阅 → 应用快照/增量。
 *
 * 严格按 research/CONVERSATION-PROTOCOL.md §4 的官方序列（真机实测）：
 *   1) 102 zcode-agent onDynamicConversationFrame {workspacePath, workspaceIdentity?}   ← 裸对象参数
 *   2) 100 zcode-agent helloConversationV4            ()          → hello
 *   3) 100 zcode-agent initializeConversationV4       (clientHello)
 *   4) 100 zcode-agent subscribeConversationV4        ({...ws, sessionId})  → ack
 * 第 2、3 步不可省：跳过会得到 fault.connection.handshakeRequired。
 */
class ConversationChannel(private val rpc: RpcChannel) {

    sealed interface Status {
        data object Idle : Status
        data object Hello : Status
        data object Initialized : Status
        data class Live(val subscriptionId: String, val mode: String?, val logEpoch: String?) : Status

        /**
         * 失败态。`retryable` 标记「值得自动重订一次的瞬态失败」（目前仅握手超时），
         * 供 AppViewModel 决定是否退避重试；协议层硬性拒绝（如 handshakeRequired）不可重试。
         */
        data class Failed(val reason: String, val retryable: Boolean = false) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status

    /**
     * 快照已对齐信号（B-1）：权威快照 `replaceAll` 落地后置位。
     * 携带 seq/耗时而非一次性 Boolean，是为了让 C-3 的「细粒度同步态」在原形状上扩展，
     * 不必二次改动状态机。
     */
    data class SnapshotAligned(
        val sessionId: String,
        val rowCount: Int,
        val atElapsedMs: Long,
        val seq: Long,
    )

    private var alignedSeq = 0L
    private val _snapshotAligned = MutableStateFlow<SnapshotAligned?>(null)
    val snapshotAligned: StateFlow<SnapshotAligned?> = _snapshotAligned

    /** 会话标题、阶段等快照元信息（UI 可用）。 */
    private val _meta = MutableStateFlow(ConversationMeta())
    val meta: StateFlow<ConversationMeta> = _meta

    /** 当前会话的待审批项（服务端整组给出，手机侧不额外累积）。 */
    private val _interactions = MutableStateFlow<List<PendingApproval>>(emptyList())
    val interactions: StateFlow<List<PendingApproval>> = _interactions

    /** 当前会话的待应答表单交互（pendingInteractions 里 kind=="userInput"，整组替换）。 */
    private val _elicitations = MutableStateFlow<List<PendingElicitation>>(emptyList())
    val elicitations: StateFlow<List<PendingElicitation>> = _elicitations

    /** 行变更通知钩子（供 AppViewModel 监听并将最新消息行写入离线缓存）。 */
    var onRowsUpdated: ((sessionId: String, rows: List<ConversationRow>) -> Unit)? = null

    /**
     * 会话级错误原因钩子：快照 `control.lastError` 非空时回调（供 AppViewModel 把原因
     * 回填进首页会话列表——bootstrap 的 tasks[] 形状不含该字段，见 SessionItem 注释）。
     */
    var onSessionError: ((sessionId: String, error: String) -> Unit)? = null

    data class ConversationMeta(
        val title: String? = null,
        val phase: String? = null,
        val totalCount: Int? = null,
        val connectionId: String? = null,
        val logEpoch: String? = null,
        /** 服务端算好的可停标志（快照/增量 control.canStop），停止按钮的显示条件。 */
        val canStop: Boolean? = null,
        /** idle | stoppable | stopping（stopping 时按钮显示"停止中"并禁用）。 */
        val stopState: String? = null,
        /**
         * 会话级错误原因（快照 `control.lastError`，host 侧 task meta 的 lastError 同源；
         * 桌面端会话列表显示的就是它）。null = 无错误（或该会话已恢复）。
         * 首页列表对「已打开过的会话」也会用同源数据补齐（见 AppViewModel 的快照回填）。
         */
        val lastError: String? = null,
        /** 快照 config 带的 PC 端当前模型（新建会话弹窗展示与 provider 继承用）。 */
        val model: String? = null,
        val provider: String? = null,
        /** 当前状态版本号（CAS 命令 switchModelConfig 必须带上 baseRevision）。 */
        val revision: Long = 0L,
    )

    private val clientId = "android-${UUID.randomUUID()}"
    private var listenId: Int? = null
    private var subscriptionId: String? = null
    private var sessionId: String? = null

    /**
     * 握手代次（A-2）：每次 subscribe 递增。自动重订 / 切会话后，上一轮
     * hello→initialize→subscribe 的迟到应答由代次守卫丢弃，避免旧握手覆盖新会话状态
     * （沿用 RelayClient.generation 的既有写法，不另创机制）。
     */
    private var generation = 0

    /** 订阅目标（workspacePath/Identity）——发命令时要原样带上，服务端按它路由。 */
    private var subTarget: Map<String, Any>? = null

    /** 最近一次 control 里的前台执行 id，stop 命令作为 expectedForegroundExecutionId 带上（防误停）。 */
    private var foregroundExecutionId: String? = null

    /** 订阅指定会话，并把行写入 [store]。 */
    fun subscribe(workspacePath: String, workspaceIdentity: String?, session: String, store: RowStore) {
        generation += 1
        val gen = generation
        sessionId = session
        subscriptionId = null
        // 允许预加载离线缓存行：不强制即时 clear，待快照到达时由 replaceAll 平滑对齐权威状态
        _status.value = Status.Idle
        _interactions.value = emptyList()
        // 上一次订阅的对齐信号作废（避免新会话首帧就被旧信号驱动定位）
        _snapshotAligned.value = null

        val target = buildMap<String, Any> {
            put("workspacePath", workspacePath)
            workspaceIdentity?.takeIf { it.isNotBlank() }?.let { put("workspaceIdentity", it) }
        }
        subTarget = target

        // 1) 先挂监听：之后所有 204 都挂在 listenId 上。
        //    事件监听服务端只回 204、不回 201，故不注册应答回调（否则会留下永不触发的挂起项）。
        listenId = rpc.listen(RpcChannel.CHANNEL_AGENT, "onDynamicConversationFrame", target)

        // 2) hello → 拿 protocolVersion / connectionId
        //    A-2：三跳都必须带超时，否则单帧 201 丢失时连接健康也会永久停在「握手中…」。
        rpc.call(RpcChannel.CHANNEL_AGENT, "helloConversationV4",
            timeoutMs = HANDSHAKE_HELLO_TIMEOUT_MS) { reply ->
            if (isStaleReply(gen, generation)) return@call
            when (reply) {
                is RpcChannel.RpcReply.Err -> {
                    ZLog.w(TAG, "hello 失败: ${reply.message}")
                    _status.value = Status.Failed(
                        handshakeFailureReason("握手", reply.message),
                        retryable = isTransientHandshakeError(reply.message),
                    )
                }
                is RpcChannel.RpcReply.Ok -> {
                    val o = reply.data.asObj()
                    val pv = o?.get("protocolVersion")?.let {
                        runCatching { it.jsonPrimitive.content.toIntOrNull() }.getOrNull() } ?: 3
                    _meta.value = _meta.value.copy(
                        connectionId = o?.get("connectionId")?.let {
                            runCatching { it.jsonPrimitive.content }.getOrNull() })
                    _status.value = Status.Hello
                    handshake(gen, pv, target, session)
                }
            }
        }
    }

    /** 3) initializeConversationV4(clientHello) → 4) subscribeConversationV4 */
    private fun handshake(gen: Int, protocolVersion: Int, target: Map<String, Any>, session: String) {
        val clientHello = mapOf(
            "kind" to "clientHello",
            "protocolVersion" to protocolVersion,
            "clientId" to clientId,
            "clientKind" to "mobileApp",
            "appVersion" to APP_VERSION,
        )
        rpc.call(RpcChannel.CHANNEL_AGENT, "initializeConversationV4", listOf(clientHello),
            timeoutMs = HANDSHAKE_INIT_TIMEOUT_MS) { reply ->
            if (isStaleReply(gen, generation)) return@call
            if (reply is RpcChannel.RpcReply.Err) {
                ZLog.w(TAG, "initialize 失败: ${reply.message}")
                _status.value = Status.Failed(
                    handshakeFailureReason("初始化", reply.message),
                    retryable = isTransientHandshakeError(reply.message),
                )
                return@call
            }
            _status.value = Status.Initialized

            val args = HashMap<String, Any>(target)
            args["sessionId"] = session
            rpc.call(RpcChannel.CHANNEL_AGENT, "subscribeConversationV4", listOf(args),
                timeoutMs = HANDSHAKE_SUBSCRIBE_TIMEOUT_MS) { sub ->
                if (isStaleReply(gen, generation)) return@call
                when (sub) {
                    is RpcChannel.RpcReply.Err -> {
                        ZLog.w(TAG, "subscribe 失败: ${sub.message}")
                        _status.value = Status.Failed(
                            handshakeFailureReason("订阅", sub.message),
                            retryable = isTransientHandshakeError(sub.message),
                        )
                    }
                    is RpcChannel.RpcReply.Ok -> {
                        val ack = sub.data.asObj()?.get("ack")?.let {
                            runCatching { it.jsonObject }.getOrNull() }
                        val subId = ack?.get("subscriptionId")?.let {
                            runCatching { it.jsonPrimitive.content }.getOrNull() }
                        val mode = ack?.get("mode")?.let {
                            runCatching { it.jsonPrimitive.content }.getOrNull() }
                        val epoch = ack?.get("logEpoch")?.let {
                            runCatching { it.jsonPrimitive.content }.getOrNull() }
                        if (subId == null) {
                            _status.value = Status.Failed("subscribe ack 缺少 subscriptionId: ${sub.data}")
                        } else {
                            subscriptionId = subId
                            _meta.value = _meta.value.copy(logEpoch = epoch)
                            _status.value = Status.Live(subId, mode, epoch)
                            ZLog.i(TAG, "subscribed sub=$subId mode=$mode logEpoch=$epoch")
                        }
                    }
                }
            }
        }
    }

    /** 处理一条 204 事件（由 AppViewModel 转发）。 */
    fun onEvent(event: RpcChannel.RpcEvent, store: RowStore) {
        // 只关心本次监听注册的事件
        if (listenId == null || event.id != listenId) return
        val data = event.data.asObj() ?: return
        val lf = ConversationFrames.parseLogicalFrame(data)
        if (lf == null) {
            ZLog.w(TAG, "无法解析逻辑帧: ${data.toString().take(300)}")
            return
        }
        if (lf.kind == "fragment") {
            ZLog.w(TAG, "收到分片逻辑帧（未实现重组）frameId=${lf.logicalFrameId}")
            return
        }
        val frame = lf.frame ?: return
        // topic + subscriptionId 双重匹配，丢弃旧订阅的迟到帧
        if (frame.topic != "conversation/$sessionId") return
        if (subscriptionId != null && frame.subscriptionId != subscriptionId) return

        when (frame.payloadKind) {
            "snapshot" -> {
                val snap = ConversationFrames.parseSnapshot(frame.payload) ?: return
                store.replaceAll(snap.rows)
                sessionId?.let { sid ->
                    onRowsUpdated?.invoke(sid, snap.rows)
                    // B-1：权威快照落地即「已对齐」——UI 据此无条件定位到最新一行，
                    // 覆盖「缓存行数 == 快照行数」时 rows.size 不变、旧触发器完全不跑的新失效路径。
                    alignedSeq += 1
                    _snapshotAligned.value = SnapshotAligned(
                        sessionId = sid,
                        rowCount = snap.rows.size,
                        atElapsedMs = android.os.SystemClock.elapsedRealtime(),
                        seq = alignedSeq,
                    )
                }
                _interactions.value = snap.pendingInteractions
                _elicitations.value = snap.elicitations
                val control = snap.control
                control?.foregroundExecutionId?.let { foregroundExecutionId = it }
                // 会话级错误原因回填（仅在有错误时回调，避免快照风暴造成列表无谓重组）
                control?.lastError?.takeIf { it.isNotBlank() }?.let { err ->
                    sessionId?.let { sid -> onSessionError?.invoke(sid, err) }
                }
                _meta.value = _meta.value.copy(
                    title = snap.title ?: _meta.value.title,
                    phase = snap.phase,
                    totalCount = snap.totalCount,
                    logEpoch = snap.logEpoch ?: _meta.value.logEpoch,
                    canStop = control?.canStop ?: _meta.value.canStop,
                    stopState = control?.stopState ?: _meta.value.stopState,
                    // 快照是全量状态：control 在场时以新值为准（错误恢复后自然清空）
                    lastError = if (control != null) control.lastError else _meta.value.lastError,
                    model = snap.configModel ?: _meta.value.model,
                    provider = snap.configProvider ?: _meta.value.provider,
                    revision = snap.revision ?: _meta.value.revision,
                )
                ZLog.i(TAG, "snapshot: ${snap.rows.size} 行（总 ${snap.totalCount}）" +
                        "待审批=${snap.pendingInteractions.size} delivery=${lf.deliveryKind}" +
                        " canStop=${control?.canStop} stopState=${control?.stopState}")
            }
            "deltas" -> {
                val deltas = ConversationFrames.parseDeltas(frame.payload)
                for (d in deltas) {
                    when (d) {
                        is ConversationFrames.Delta.Upsert -> store.upsert(d.row)
                        is ConversationFrames.Delta.RemoveFrom -> store.removeFrom(d.fromRowId)
                        is ConversationFrames.Delta.AppendText -> store.appendText(d.rowId, d.path, d.append)
                        // 审批请求/消解走这里：patch 里的 pendingInteractions 是整组替换
                        is ConversationFrames.Delta.StateUpdated -> d.patch?.let { p ->
                            val next = PendingApproval.parseFrom(p, sessionId)
                            if (next != _interactions.value) {
                                _interactions.value = next
                                ZLog.i(TAG, "pendingInteractions → ${next.size} 条 " +
                                        next.joinToString(",") { "${it.toolName ?: "?"}#${it.interactionId.take(18)}" })
                            }
                            // elicitations 同组替换；仅当 patch 带该键时才更新（缺失=不涉及）
                            p["pendingInteractions"]?.let {
                                val nextE = PendingElicitation.parseArray(
                                    runCatching { it.jsonArray }.getOrNull(), sessionId)
                                if (nextE != _elicitations.value) {
                                    _elicitations.value = nextE
                                    ZLog.i(TAG, "elicitations → ${nextE.size} 条 " +
                                            nextE.joinToString(",") { "${it.toolName ?: "?"}#${it.interactionId.take(18)}" })
                                }
                            }
                            // control 增量：canStop/phase/stopState 运行中会变（停止按钮随之出现/消失）
                            p["control"].asObj()?.let { c ->
                                ConversationFrames.parseControl(c)?.let { ctl ->
                                    ctl.foregroundExecutionId?.let { foregroundExecutionId = it }
                                    _meta.value = _meta.value.copy(
                                        phase = ctl.phase ?: _meta.value.phase,
                                        canStop = ctl.canStop ?: _meta.value.canStop,
                                        stopState = ctl.stopState ?: _meta.value.stopState,
                                    )
                                    ZLog.i(TAG, "control 更新 phase=${ctl.phase} canStop=${ctl.canStop} stopState=${ctl.stopState}")
                                }
                            }
                            // revision 增量更新（用于 CAS 校验）
                            p["revision"]?.let { rEl ->
                                runCatching { rEl.jsonPrimitive.content.toLongOrNull() }.getOrNull()?.let { r ->
                                    _meta.value = _meta.value.copy(revision = r)
                                }
                            }
                            // config 增量更新（切换模型后广播）
                            p["config"].asObj()?.let { cfg ->
                                val newModel = cfg["model"].asStr()
                                val newProvider = cfg["provider"].asStr()
                                if (newModel != null || newProvider != null) {
                                    _meta.value = _meta.value.copy(
                                        model = newModel ?: _meta.value.model,
                                        provider = newProvider ?: _meta.value.provider,
                                    )
                                    ZLog.i(TAG, "config 增量更新 model=$newModel provider=$newProvider")
                                }
                            }
                        }
                        is ConversationFrames.Delta.Unknown ->
                            ZLog.i(TAG, "未处理 delta：${d.op}")
                    }
                }
                // deltas 为空是合法的（仅推 seq 的心跳），不记日志避免刷屏
            }
            else -> ZLog.i(TAG, "未知 payload kind=${frame.payloadKind}")
        }
    }

    fun reset() {
        // A-2：作废在途握手应答（迟到应答不得回写已重置的状态）
        generation += 1
        _snapshotAligned.value = null
        listenId = null
        subscriptionId = null
        sessionId = null
        subTarget = null
        foregroundExecutionId = null
        _interactions.value = emptyList()
        _elicitations.value = emptyList()
        _earlier.value = EarlierState()
        _status.value = Status.Idle
        _meta.value = ConversationMeta()
    }

    // ---------- 历史翻页 ----------

    /** 向上翻页状态（UI 据此显示"加载中/没有更早"并避免重复触发）。 */
    data class EarlierState(
        val loading: Boolean = false,
        val hasMore: Boolean = false,
        /** 是否已至少拉过一页（区分"从未拉过"与"拉完没更多"）。 */
        val pulled: Boolean = false,
    )

    private val _earlier = MutableStateFlow(EarlierState())
    val earlier: StateFlow<EarlierState> = _earlier

    /**
     * 向上拉一页更早历史（research/CONVERSATION-PROTOCOL.md §6.3）：
     * `conversationRowsRangeV4({workspacePath, sessionId, beforeRowId, limit})`，
     * 应答 `{rows, atSeq, atLogEpoch, hasMore}`。**atLogEpoch 必须等于当前快照的
     * logEpoch**，不等说明日志纪元已变（快照失效），整批丢弃。
     * 拉回的行按 rowId 升序前插进 [store]。
     */
    fun loadEarlier(store: RowStore, onResult: (Result<Int>) -> Unit) {
        val session = sessionId
        val target = subTarget
        val firstRowId = store.firstRowId()
        if (session == null || target == null || firstRowId == null) {
            onResult(Result.failure(IllegalStateException("未订阅会话或无历史行")))
            return
        }
        if (_earlier.value.loading) return
        _earlier.value = _earlier.value.copy(loading = true)
        val args = HashMap<String, Any>(target)
        args["sessionId"] = session
        args["beforeRowId"] = firstRowId
        args["limit"] = 60
        ZLog.i(TAG, "loadEarlier beforeRowId=$firstRowId session=$session")
        rpc.call(RpcChannel.CHANNEL_AGENT, "conversationRowsRangeV4", listOf(args)) { reply ->
            when (reply) {
                is RpcChannel.RpcReply.Err -> {
                    ZLog.w(TAG, "loadEarlier error: ${reply.message}")
                    _earlier.value = EarlierState(loading = false, hasMore = _earlier.value.hasMore, pulled = true)
                    onResult(Result.failure(IllegalStateException(reply.message)))
                }
                is RpcChannel.RpcReply.Ok -> {
                    val result: JsonObject? = reply.data?.let { el ->
                        runCatching { el.jsonObject }.getOrNull()
                    }
                    val atEpoch: String? = result?.get("atLogEpoch")?.let { e ->
                        runCatching { e.jsonPrimitive.content }.getOrNull()
                    }
                    val curEpoch = _meta.value.logEpoch
                    val rows: List<ConversationRow> = result?.get("rows")?.let { r ->
                        runCatching { r.jsonArray }.getOrNull()
                            ?.mapNotNull { el ->
                                runCatching { el.jsonObject }.getOrNull()
                                    ?.let { ConversationRow.from(it) }
                            }
                    } ?: emptyList()
                    if (atEpoch != null && curEpoch != null && atEpoch != curEpoch) {
                        ZLog.w(TAG, "loadEarlier logEpoch 不匹配（$atEpoch != $curEpoch），整批丢弃")
                        _earlier.value = EarlierState(loading = false, hasMore = false, pulled = true)
                        onResult(Result.failure(IllegalStateException("logEpoch 已变更")))
                        return@call
                    }
                    val hasMore: Boolean = result?.get("hasMore")?.let { e ->
                        val s = runCatching { e.jsonPrimitive.content }.getOrNull()
                        s?.toBooleanStrictOrNull() ?: (s == "true")
                    } ?: false
                    if (rows.isNotEmpty()) {
                        store.prepend(rows)
                        sessionId?.let { sid -> onRowsUpdated?.invoke(sid, store.snapshot()) }
                    }
                    _earlier.value = EarlierState(loading = false, hasMore = hasMore, pulled = true)
                    ZLog.i(TAG, "loadEarlier +${rows.size} 行 hasMore=$hasMore")
                    onResult(Result.success(rows.size))
                }
            }
        }
    }

    /**
     * 应答一次审批。
     *
     * 协议（host 源码与官方 web 双向印证）：
     *   `sendConversationCommandV4({workspacePath, workspaceIdentity?, envelope})`，
     *   envelope = `{commandId, clientId, sessionId, type:'resolveInteraction',
     *                payload:{interactionId, answer:{optionId}}, issuedAt}`。
     *
     * 三条硬约束：
     *   1. `clientId` 必须等于本次连接 `initializeConversationV4` 用的那个，
     *      否则 host 直接抛 `fault.command.clientMismatch`；
     *   2. `optionId` 原样透传，不猜序号（UI 显示序号是按 kind 排过序的位次）；
     *   3. 不做断线重放——官方把 resolveInteraction 归为 sensitive 命令，
     *      重连后要靠 `queryConversationCommandsV4` 回查，本 App 选择让用户重新点。
     */
    /**
     * 应答一条审批请求（permission interaction，官方 web 同构）。
     *
     * 关键修正：必须优先使用 approval.sessionId（审批实际所属的会话），
     * 绝不能盲目优先使用当前打开的 sessionId，避免在待办列表中跨会话应答时串台。
     */
    fun resolve(
        approval: PendingApproval,
        option: ApprovalOption,
        fallbackWorkspacePath: String? = null,
        onResult: (ResolveResult) -> Unit,
    ) {
        val session = approval.sessionId ?: sessionId
        val target = subTarget ?: fallbackWorkspacePath?.let { mapOf("workspacePath" to it) }
        if (session == null || target == null) {
            onResult(ResolveResult.Failed("no-session", "未定位到审批所属会话或工作区，无法应答"))
            return
        }
        if (option.optionId.isBlank()) {
            onResult(ResolveResult.Failed("no-option", "服务端未给出 optionId"))
            return
        }

        ZLog.i(TAG, "resolve interaction=${approval.interactionId} option=${option.optionId}" +
                " kind=${option.kind} session=$session ws=${target["workspacePath"]}")
        sendResolveInteraction(session, target,
            buildJsonObject {
                put("interactionId", approval.interactionId)
                put("answer", buildJsonObject { put("optionId", option.optionId) })
            }, onResult)
    }

    /**
     * 应答一次表单交互（elicitation，kind=="userInput"，官方 web 同构）。
     *
     * answer 由调用方按形态构造（PROTOCOL.md §6.5）：
     *   带 questions 的表单 → `{action:"accept", content:{answer: 值}}`（多题 answer_0/1…）；
     *   拒绝 → `{action:"decline"}`；无 questions 的确认/文本 → `{optionId}` / `{freeText}`。
     * 与审批共用 resolveInteraction 管道与 ack 判据（sensitive，不做断线重放）。
     * 关键修正：优先使用 el.sessionId，避免跨会话应答串台。
     */
    fun resolveElicitation(
        el: PendingElicitation,
        answer: JsonObject,
        fallbackWorkspacePath: String? = null,
        onResult: (ResolveResult) -> Unit,
    ) {
        val session = el.sessionId ?: sessionId
        val target = subTarget ?: fallbackWorkspacePath?.let { mapOf("workspacePath" to it) }
        if (session == null || target == null) {
            onResult(ResolveResult.Failed("no-session", "未定位到表单所属会话或工作区，无法应答"))
            return
        }
        ZLog.i(TAG, "resolveElicitation interaction=${el.interactionId} session=$session ws=${target["workspacePath"]} answer=$answer")
        sendResolveInteraction(session, target,
            buildJsonObject {
                put("interactionId", el.interactionId)
                put("answer", answer)
            }, onResult)
    }

    /** resolveInteraction envelope 的公共出口（审批与表单同管道，clientId 硬约束同源）。 */
    private fun sendResolveInteraction(
        session: String,
        target: Map<String, Any>,
        payload: JsonObject,
        onResult: (ResolveResult) -> Unit,
    ) {
        val args = HashMap<String, Any>(target)
        args["envelope"] = mapOf(
            "commandId" to "cmd-${UUID.randomUUID()}",
            "clientId" to clientId,
            "sessionId" to session,
            "type" to "resolveInteraction",
            "payload" to payload,
            "issuedAt" to System.currentTimeMillis(),
        )
        rpc.call(RpcChannel.CHANNEL_AGENT, "sendConversationCommandV4", listOf(args),
            timeoutMs = SEND_ACK_TIMEOUT_MS) { reply ->
            onResult(
                when (reply) {
                    is RpcChannel.RpcReply.Err ->
                        ResolveResult.Failed("rpc-error", reply.message)
                    is RpcChannel.RpcReply.Ok -> parseCommandAck(reply.data)
                }
            )
        }
    }

    /** 命令应答判据：官方统一以 status ∈ {accepted, duplicate, noop} 视为成功。 */
    private fun parseCommandAck(data: JsonElement?): ResolveResult {
        val root = data.asObj()
        // 部分实现把 ack 再包一层，两种形态都接住
        val ack = root?.get("ack").asObj() ?: root
        if (ack == null) return ResolveResult.Failed("bad-ack", "应答格式不认识: $data")
        val status = ack["status"].asStr() ?: ""
        val reason = ack["reasonCode"].asStr()
        val message = ack["message"].asStr()
        return when {
            status in SUCCESS_STATUSES -> ResolveResult.Accepted(status, reason)
            else -> ResolveResult.Failed(reason ?: status.ifBlank { "unknown" },
                message ?: "服务端未接受（status=$status）")
        }
    }

    /**
     * 发送用户消息（官方 web 远程页同款路径，P1-3 发送侧实证 2026-09-30）：
     * `sendConversationCommandV4` envelope `type:'sendText'`，payload = `{text, attachments?}`，
     * 元素 = [AttachmentRef.toWire]（`{ref, fileName, mime, bytes}`）。
     *
     * 实证（tools/_p13_send_verify.py）：旧路径 RPC `sendPrompt` 的 args schema 只有
     * `{workspacePath, sessionId, inputId, content}`，多传的 attachments 被 zod strip——
     * 201 accepted 但附件不到模型侧；sendText envelope 附件随消息 materialize，
     * ack `result={type:"inputAccepted", delivery:"startNow"}`，userInput 行回显 attachments。
     * 成功后 userInput 行由服务端推回会话流，无需本地 append。
     */
    fun sendPrompt(
        content: String,
        attachments: List<AttachmentRef> = emptyList(),
        onResult: (Result<Unit>) -> Unit,
    ) {
        val session = sessionId
        val target = subTarget
        if (session == null || target == null) {
            onResult(Result.failure(IllegalStateException("未订阅会话，无法发送")))
            return
        }
        if (content.isBlank() && attachments.isEmpty()) {
            onResult(Result.failure(IllegalStateException("消息内容为空")))
            return
        }
        val payload = LinkedHashMap<String, Any>()
        payload["text"] = content
        if (attachments.isNotEmpty()) payload["attachments"] = attachments.map { it.toWire() }
        val args = HashMap<String, Any>(target)
        args["envelope"] = mapOf(
            "commandId" to "cmd-${UUID.randomUUID()}",
            "clientId" to clientId,
            "sessionId" to session,
            "type" to "sendText",
            "payload" to payload,
            "issuedAt" to System.currentTimeMillis(),
        )
        ZLog.i(TAG, "sendText session=$session chars=${content.length} attachments=${attachments.size}")
        rpc.call(RpcChannel.CHANNEL_AGENT, "sendConversationCommandV4", listOf(args),
            timeoutMs = SEND_ACK_TIMEOUT_MS) { reply ->
            onResult(
                when (reply) {
                    is RpcChannel.RpcReply.Err -> Result.failure(IllegalStateException(reply.message))
                    is RpcChannel.RpcReply.Ok -> when (val ack = parseCommandAck(reply.data)) {
                        is ResolveResult.Accepted -> Result.success(Unit)
                        is ResolveResult.Failed -> Result.failure(IllegalStateException(ack.message))
                    }
                }
            )
        }
    }

    /**
     * 在当前工作区创建新会话（官方 V4 协议同款原生链路）：
     * `sendConversationCommandV4` envelope `type:'createSession'`，sessionId = null。
     * 可选携带 firstInput = { text, attachments? } 与 config = { provider, model, thought?, mode? }。
     * 服务端创建完成后返回 201 ack，其中 `result.sessionId` 为新分配的会话 ID。
     */
    fun createSession(
        workspacePath: String,
        workspaceIdentity: String? = null,
        firstInputText: String? = null,
        attachments: List<AttachmentRef> = emptyList(),
        modelConfig: ModelConfig? = null,
        onResult: (Result<String>) -> Unit,
    ) {
        val target = buildMap<String, Any> {
            put("workspacePath", workspacePath)
            workspaceIdentity?.takeIf { it.isNotBlank() }?.let { put("workspaceIdentity", it) }
        }
        val payload = LinkedHashMap<String, Any>()
        payload["workspaceId"] = workspacePath
        if (!firstInputText.isNullOrBlank() || attachments.isNotEmpty()) {
            val input = LinkedHashMap<String, Any>()
            input["text"] = firstInputText ?: ""
            if (attachments.isNotEmpty()) {
                input["attachments"] = attachments.map { it.toWire() }
            }
            payload["firstInput"] = input
        }
        // 显式模型选择（官方 Host `zo` schema）：provider + model + thought + mode
        if (modelConfig != null && modelConfig.modelId.isNotBlank()) {
            val cfg = LinkedHashMap<String, Any>()
            cfg["provider"] = modelConfig.providerId
            cfg["model"] = modelConfig.modelId
            // thought 必须落在该模型 optionSpecs.reasoningLevel.values 内，否则 registry 校验直接失败。
            // 未知档位时不下发，交由 PC 端按模型默认值决定。
            modelConfig.thought?.takeIf { it.isNotBlank() }?.let { cfg["thought"] = it }
            // P0-B：mode 兜底从 yolo 改为 build —— 免审批模式绝不能是默认值
            // （旧兜底会让任何未显式带 mode 的创建路径静默变成全自动放行）
            cfg["mode"] = modelConfig.mode ?: "build"
            payload["config"] = cfg
        }

        val args = HashMap<String, Any>(target)
        args["envelope"] = mapOf(
            "commandId" to "cmd-${UUID.randomUUID()}",
            "clientId" to clientId,
            "sessionId" to null,
            "type" to "createSession",
            "payload" to payload,
            "issuedAt" to System.currentTimeMillis(),
        )

        ZLog.i(TAG, "createSession workspace=$workspacePath hasFirstInput=${!firstInputText.isNullOrBlank()} " +
                "model=${modelConfig?.modelId ?: "inherit-default"} " +
                "thought=${modelConfig?.thought ?: "(未指定)"} mode=${modelConfig?.mode ?: "(未指定)"}")
        rpc.call(RpcChannel.CHANNEL_AGENT, "sendConversationCommandV4", listOf(args),
            timeoutMs = SEND_ACK_TIMEOUT_MS) { reply ->
            when (reply) {
                is RpcChannel.RpcReply.Err -> onResult(Result.failure(IllegalStateException(reply.message)))
                is RpcChannel.RpcReply.Ok -> {
                    val root = reply.data.asObj()
                    val ack = root?.get("ack").asObj() ?: root
                    val status = ack?.get("status").asStr() ?: ""
                    if (status in SUCCESS_STATUSES) {
                        val result = ack?.get("result").asObj()
                        val newSessionId = result?.get("sessionId").asStr()
                        if (!newSessionId.isNullOrBlank()) {
                            ZLog.i(TAG, "createSession success sessionId=$newSessionId")
                            onResult(Result.success(newSessionId))
                        } else {
                            onResult(Result.failure(IllegalStateException("服务端未返回新会话 ID: ${reply.data}")))
                        }
                    } else {
                        val msg = ack?.get("message").asStr() ?: "创建会话被拒绝 (status=$status)"
                        onResult(Result.failure(IllegalStateException(msg)))
                    }
                }
            }
        }
    }

    /**
     * 切换会话执行模式（P0-B；plan/build/yolo 等，协议合法值同 tools/setmode.py 的 VALID）。
     * 协议语义（HANDOVER §5.4）：**只对新的 agent turn 生效**，运行中 turn 的权限上下文
     * 不变 —— UI 必须如实提示，否则用户会误以为已经拦住了。
     */
    fun setMode(mode: String, onResult: (Result<Unit>) -> Unit) {
        val session = sessionId ?: run {
            onResult(Result.failure(IllegalStateException("会话尚未订阅")))
            return
        }
        val target = subTarget ?: run {
            onResult(Result.failure(IllegalStateException("缺少订阅目标（workspace）")))
            return
        }
        val arg = HashMap<String, Any>(target)
        arg["sessionId"] = session
        arg["mode"] = mode
        ZLog.i(TAG, "setMode session=$session mode=$mode")
        rpc.call(RpcChannel.CHANNEL_AGENT, "setMode", listOf(arg),
            timeoutMs = SEND_ACK_TIMEOUT_MS) { reply ->
            when (reply) {
                is RpcChannel.RpcReply.Err -> onResult(Result.failure(IllegalStateException(reply.message)))
                is RpcChannel.RpcReply.Ok -> onResult(Result.success(Unit))
            }
        }
    }

    /**
     * 读取工作区配置状态（官方桌面端模型下拉框同款数据源）：
     * `zcode-session::readWorkspaceState`，入参为 `[{workspacePath, preferWorkspaceDefaults:true}]`。
     * 返回当前模型、可用模型列表、思考等级与执行模式。
     */
    fun readWorkspaceState(
        workspacePath: String,
        workspaceIdentity: String? = null,
        onResult: (Result<WorkspaceState>) -> Unit,
    ) {
        val args = buildMap<String, Any> {
            put("workspacePath", workspacePath)
            workspaceIdentity?.takeIf { it.isNotBlank() }?.let { put("workspaceIdentity", it) }
            put("preferWorkspaceDefaults", true)
        }
        ZLog.i(TAG, "readWorkspaceState ws=$workspacePath")
        rpc.call(RpcChannel.CHANNEL_SESSION, "readWorkspaceState", listOf(args),
            timeoutMs = SEND_ACK_TIMEOUT_MS) { reply ->
            when (reply) {
                is RpcChannel.RpcReply.Err -> {
                    ZLog.w(TAG, "readWorkspaceState failed: ${reply.message}，回退 model-provider::getAllCached")
                    fetchModelsViaProviderCatalog(onResult)
                }
                is RpcChannel.RpcReply.Ok -> {
                    val o = reply.data.asObj()
                    val state = parseWorkspaceState(o)
                    if (state != null && state.available.isNotEmpty()) {
                        onResult(Result.success(state))
                    } else {
                        ZLog.w(TAG, "readWorkspaceState 返回空目录，回退 model-provider::getAllCached")
                        fetchModelsViaProviderCatalog(onResult)
                    }
                }
            }
        }
    }

    /**
     * 在已有会话中动态切换模型（官方 V4 switchModelConfig 原生命令）：
     * `sendConversationCommandV4` envelope `type:'switchModelConfig'`。
     * 携带 sessionId、baseRevision 与 payload = { provider, model, thought? }。
     * 服务端即时生效并广播 `state.updated` 帧，下一轮发送的消息立即以新模型执行。
     */
    fun switchModelConfig(
        provider: String,
        model: String,
        thought: String? = null,
        onResult: (Result<Unit>) -> Unit,
    ) {
        val session = sessionId
        val target = subTarget
        if (session == null || target == null) {
            onResult(Result.failure(IllegalStateException("未订阅会话，无法切换模型")))
            return
        }
        val payload = LinkedHashMap<String, Any>()
        payload["provider"] = provider
        payload["model"] = model
        thought?.let { payload["thought"] = it }

        val baseRev = _meta.value.revision
        val args = HashMap<String, Any>(target)
        args["envelope"] = mapOf(
            "commandId" to "cmd-${UUID.randomUUID()}",
            "clientId" to clientId,
            "sessionId" to session,
            "baseRevision" to baseRev,
            "type" to "switchModelConfig",
            "payload" to payload,
            "issuedAt" to System.currentTimeMillis(),
        )

        ZLog.i(TAG, "switchModelConfig session=$session model=$model provider=$provider baseRev=$baseRev")
        rpc.call(RpcChannel.CHANNEL_AGENT, "sendConversationCommandV4", listOf(args),
            timeoutMs = SEND_ACK_TIMEOUT_MS) { reply ->
            when (reply) {
                is RpcChannel.RpcReply.Err -> onResult(Result.failure(IllegalStateException(reply.message)))
                is RpcChannel.RpcReply.Ok -> {
                    val ack = parseCommandAck(reply.data)
                    when (ack) {
                        is ResolveResult.Accepted -> {
                            ZLog.i(TAG, "switchModelConfig success")
                            _meta.value = _meta.value.copy(model = model, provider = provider)
                            onResult(Result.success(Unit))
                        }
                        is ResolveResult.Failed -> onResult(Result.failure(IllegalStateException(ack.message)))
                    }
                }
            }
        }
    }

    /**
     * 回退路径（官方移动端 useModelProviders 同款）：
     * `model-provider::getAllCached()`（无参），把每个供应商下的 models 展平为模型目录。
     * 此路径读不到"当前选中模型"，current 恒为 null（UI 显示"跟随 PC 默认"）。
     */
    private fun fetchModelsViaProviderCatalog(onResult: (Result<WorkspaceState>) -> Unit) {
        rpc.call(RpcChannel.CHANNEL_MODEL_PROVIDER, "getAllCached", listOf<Any>()) { reply ->
            when (reply) {
                is RpcChannel.RpcReply.Err ->
                    onResult(Result.failure(IllegalStateException("模型目录读取失败：${reply.message}")))
                is RpcChannel.RpcReply.Ok -> {
                    val available = mutableListOf<ModelConfig>()
                    reply.data.asArray()?.forEach { el ->
                        val provider = el.asObj() ?: return@forEach
                        val providerId = provider["id"].asStr() ?: return@forEach
                        val providerName = provider["name"].asStr()
                        provider["models"].asArray()?.forEach { mEl ->
                            val m = mEl.asObj() ?: return@forEach
                            val modelId = m["id"].asStr() ?: return@forEach
                            available.add(
                                ModelConfig(
                                    providerId = providerId,
                                    modelId = modelId,
                                    label = m["name"].asStr() ?: modelId,
                                    providerLabel = providerName,
                                )
                            )
                        }
                    }
                    ZLog.i(TAG, "providerCatalog 回退成功：${available.size} 个模型")
                    onResult(Result.success(WorkspaceState(current = null, available = available)))
                }
            }
        }
    }

    /** 解析 PC 端返回的 readWorkspaceState 应答，兼容字段缺失（全部回退默认）。 */
    private fun parseWorkspaceState(root: JsonObject?): WorkspaceState? {
        if (root == null) return null
        val settings = root["settings"].asObj()
        val model = settings?.get("model").asObj()

        // 当前选中模型（settings.model.current 优先，回落 lastUsed）
        val currentObj = model?.get("current").asObj() ?: model?.get("lastUsed").asObj()
        val current = currentObj?.let {
            ModelConfig(
                providerId = it["providerId"].asStr() ?: "",
                modelId = it["modelId"].asStr() ?: "",
                label = it["label"].asStr(),
            )
        }

        // 可用模型列表（settings.model.available / modelCatalog.available）
        val available = mutableListOf<ModelConfig>()
        val availArr = model?.get("available").asArray()
            ?: root["modelCatalog"].asObj()?.get("available").asArray()
        availArr?.forEach { el ->
            val item = el.asObj() ?: return@forEach
            val ref = item["ref"].asObj()
            val providerId = ref?.get("providerId").asStr()
                ?: item["modelProviderId"].asStr()
                ?: item["providerId"].asStr()
                ?: ""
            val modelId = ref?.get("modelId").asStr()
                ?: item["modelId"].asStr()
                ?: item["value"].asStr()
                ?: ""
            if (providerId.isNotBlank() && modelId.isNotBlank()) {
                available.add(
                    ModelConfig(
                        providerId = providerId,
                        modelId = modelId,
                        label = item["label"].asStr() ?: modelId,
                        providerLabel = item["providerLabel"].asStr()
                            ?: item["modelProviderName"].asStr(),
                        thoughtLevels = item["reasoning"].asObj()?.get("levels").asArray()
                            ?.mapNotNull { it.asStr() },
                    )
                )
            }
        }

        // 执行模式
        val modeObj = settings?.get("mode").asObj()
        val currentMode = modeObj?.get("current").asStr()
        val availableModes = modeObj?.get("available").asArray()?.mapNotNull { it.asStr() }

        // 思考等级
        val thoughtObj = settings?.get("thoughtLevel").asObj()
        val currentThought = thoughtObj?.get("current").asStr()

        return WorkspaceState(
            current = current,
            available = available,
            currentMode = currentMode,
            availableModes = availableModes,
            currentThought = currentThought,
        )
    }

    // ---------- 附件上传（P1-3，协议实证见 PROTOCOL.md §6.6）----------

    /**
     * 上传一个附件到该会话（官方 web `TTe` 同构的四步流程）：
     *   attachmentBeginV4 → attachmentChunkV4 × N → attachmentCommitV4
     * 失败时 fire-and-forget 打一发 attachmentAbortV4 回收服务端暂存。
     *
     * - chunk 大小 = [CHUNK_BYTES]（与官方客户端一致 384KiB；host 上限 512KiB）
     * - checksum = `"sha256:" + sha256(data).hex`（小写）
     * - 全程不需要 connectionId（host 从 workspace/session 上下文解析；实机验证）
     */
    /**
     * 上传一个附件（官方 web `TTe` 同构的四步流程）：
     *   attachmentBeginV4 → attachmentChunkV4 × N → attachmentCommitV4
     * 失败时 fire-and-forget 打一发 attachmentAbortV4 回收服务端暂存。
     *
     * - chunk 大小 = [CHUNK_BYTES]（与官方客户端一致 384KiB；host 上限 512KiB）
     * - checksum = `"sha256:" + sha256(bytes).hex`（小写）
     * - 全程不需要 connectionId（host 从 workspace/session 上下文解析；实机验证）
     *
     * P2-3 流式改造：内容经 [openStream] 分片读取（可重入，SAF uri 可重复开流），
     * 内存峰值 = 一倍分片 + 64KiB hash 缓冲，不再整文件读进内存。
     * 流程 = 第一遍流式算 sha256 → begin → 单次开流顺序读满分片逐片上传。
     */
    /**
     * 上传句柄（A-1）：调用方（AppViewModel）在切会话 / 清空附件时据此终止在途上传，
     * 避免上传完成后把文件回写进另一个会话的附件条。`abort()` 幂等，未开始的上传返回空实现。
     */
    interface UploadHandle { fun abort() }

    private val NOOP_UPLOAD_HANDLE = object : UploadHandle { override fun abort() = Unit }

    /**
     * 上传一个附件（四步流程 begin/chunk/commit，协议见 PROTOCOL.md §6.6）。
     * 返回值用于终止在途上传（A-1）。
     */
    fun uploadAttachment(
        fileName: String,
        mime: String,
        totalBytes: Long,
        openStream: () -> java.io.InputStream,
        onProgress: ((uploaded: Long, total: Long) -> Unit)? = null,
        onResult: (Result<AttachmentRef>) -> Unit,
    ): UploadHandle {
        val session = sessionId
        val target = subTarget
        if (session == null || target == null) {
            onResult(Result.failure(IllegalStateException("未订阅会话，无法上传附件")))
            return NOOP_UPLOAD_HANDLE
        }
        if (totalBytes <= 0L) {
            onResult(Result.failure(IllegalArgumentException("附件内容为空")))
            return NOOP_UPLOAD_HANDLE
        }
        if (totalBytes > MAX_ATTACHMENT_BYTES) {
            onResult(Result.failure(IllegalArgumentException("附件超过 20MiB 上限")))
            return NOOP_UPLOAD_HANDLE
        }
        val totalChunks = ((totalBytes + CHUNK_BYTES - 1) / CHUNK_BYTES).toInt()
        if (totalChunks > MAX_CHUNKS) {
            onResult(Result.failure(IllegalArgumentException("附件分片数超过上限")))
            return NOOP_UPLOAD_HANDLE
        }
        val total = totalBytes
        val base = HashMap<String, Any>(target)
        base["sessionId"] = session
        base["uploadId"] = "upload-${UUID.randomUUID()}"

        // A-1：外部可终止标记。置位后一切在途回调（begin/chunk/commit 的应答）与后续分片
        // 全部短路，且不再回写 onResult——否则切会话后旧上传仍会把结果投给新会话。
        // 线程不变量：RpcChannel 的应答经 viewModelScope(Main.immediate) 分发、超时经 mainHandler，
        // 故本标记与调用方 clearAttachments() 同在主线程，无需 @Volatile（若日后应答改到 IO 线程须加）。
        var aborted = false
        val handle = object : UploadHandle {
            override fun abort() {
                if (aborted) return
                aborted = true
                val abortedId = base["uploadId"]
                rpc.call(RpcChannel.CHANNEL_AGENT, "attachmentAbortV4", listOf(HashMap(base))) {
                    ZLog.i(TAG, "attachmentAbort sent uploadId=$abortedId")
                }
            }
        }

        fun fail(message: String) {
            if (aborted) return
            ZLog.w(TAG, "uploadAttachment 失败：$message")
            handle.abort()
            onResult(Result.failure(IllegalStateException(message)))
        }

        // 第一遍：流式计算整文件 sha256（begin 的 args 需要 checksum 先行）
        val digest = runCatching {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            openStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }.getOrElse {
            if (!aborted) onResult(Result.failure(IllegalStateException("读取附件失败：${it.message}")))
            return handle
        }
        if (aborted) return handle

        // 注意：Kotlin 局部函数只能引用「已声明」的同层函数，故按依赖顺序声明。
        fun commit() {
            if (aborted) return
            rpc.call(RpcChannel.CHANNEL_AGENT, "attachmentCommitV4", listOf(HashMap(base))) { reply ->
                if (aborted) return@call
                when (reply) {
                    is RpcChannel.RpcReply.Err -> fail("提交附件失败：${reply.message}")
                    is RpcChannel.RpcReply.Ok -> {
                        val ref = reply.data.asObj()?.get("ref").asStr()
                        if (ref.isNullOrBlank()) fail("提交附件未返回 ref：${reply.data}")
                        else {
                            ZLog.i(TAG, "附件已提交 ref=$ref file=$fileName bytes=$total")
                            onResult(Result.success(AttachmentRef(ref, fileName, mime, total)))
                        }
                    }
                }
            }
        }

        fun uploadChunks(from: Int) {
            // 单次开流顺序读满分片（续传 from>0 时 skip 前部，仅重试场景出现）
            var index = from
            var input: java.io.InputStream? = null
            val buf = ByteArray(CHUNK_BYTES)
            fun closeQuietly() { runCatching { input?.close() } }
            fun next() {
                // A-1：被外部终止后不再继续读流/发分片
                if (aborted) { closeQuietly(); return }
                if (index >= totalChunks) { closeQuietly(); commit(); return }
                if (input == null) {
                    input = try {
                        openStream().also { s ->
                            var toSkip = index.toLong() * CHUNK_BYTES
                            while (toSkip > 0) {
                                val n = s.skip(toSkip)
                                if (n <= 0) break
                                toSkip -= n
                            }
                        }
                    } catch (e: Exception) {
                        fail("读取附件失败：${e.message}"); return
                    }
                }
                var filled = 0
                while (filled < CHUNK_BYTES) {
                    val n = try {
                        input!!.read(buf, filled, CHUNK_BYTES - filled)
                    } catch (e: Exception) {
                        closeQuietly(); fail("读取附件分片失败：${e.message}"); return
                    }
                    if (n <= 0) break
                    filled += n
                }
                if (filled == 0) {
                    closeQuietly(); fail("附件内容比声明大小短"); return
                }
                val args = HashMap(base)
                args["chunkIndex"] = index
                args["dataBase64"] = Base64.encodeToString(buf.copyOf(filled), Base64.NO_WRAP)
                val sent = index
                rpc.call(RpcChannel.CHANNEL_AGENT, "attachmentChunkV4", listOf(args)) { reply ->
                    if (aborted) return@call
                    when (reply) {
                        is RpcChannel.RpcReply.Err -> fail("分片 $sent 上传失败：${reply.message}")
                        is RpcChannel.RpcReply.Ok -> {
                            val ack = reply.data.asObj()?.get("nextChunkIndex").asInt()
                            if (ack != sent + 1) {
                                fail("服务端分片进度异常（期望 ${sent + 1}，收到 $ack）")
                                return@call
                            }
                            index = sent + 1
                            onProgress?.invoke(minOf(index.toLong() * CHUNK_BYTES, total), total)
                            next()
                        }
                    }
                }
            }
            next()
        }

        val beginArgs = HashMap(base)
        beginArgs["fileName"] = fileName
        beginArgs["mime"] = mime
        beginArgs["totalBytes"] = totalBytes
        beginArgs["totalChunks"] = totalChunks
        beginArgs["checksum"] = "sha256:" + digest
        ZLog.i(TAG, "attachmentBegin file=$fileName mime=$mime bytes=$total chunks=$totalChunks")
        rpc.call(RpcChannel.CHANNEL_AGENT, "attachmentBeginV4", listOf(beginArgs)) { reply ->
            if (aborted) return@call
            when (reply) {
                is RpcChannel.RpcReply.Err -> onResult(
                    Result.failure(IllegalStateException("附件上传初始化失败：${reply.message}")))
                is RpcChannel.RpcReply.Ok -> {
                    val o = reply.data.asObj()
                    val ref = o?.get("ref").asStr()
                    // 幂等：服务端可能对该 uploadId 已有 committed 内容（重试场景）
                    if (o?.get("state").asStr() == "committed" && !ref.isNullOrBlank()) {
                        onResult(Result.success(AttachmentRef(ref, fileName, mime, total)))
                        return@call
                    }
                    val next = o?.get("nextChunkIndex").asInt() ?: 0
                    if (next > totalChunks) { fail("服务端返回了非法进度 $next"); return@call }
                    uploadChunks(next)
                }
            }
        }
        return handle
    }




    /**
     * 停止当前运行（官方 web 版同款命令，host asar 实证）：
     * `sendConversationCommandV4` envelope `type:'stop'`，
     * payload `{expectedForegroundExecutionId?}`——control.activeWorks 里
     * 有前台执行 id 就带上（官方行为），否则发空 payload。
     * ack 判据与 resolveInteraction 相同（status accepted/duplicate/noop）。
     */
    fun stop(onResult: (ResolveResult) -> Unit) {
        val session = sessionId
        val target = subTarget
        if (session == null || target == null) {
            onResult(ResolveResult.Failed("no-session", "未订阅该会话，无法停止"))
            return
        }
        val args = HashMap<String, Any>(target)
        args["envelope"] = mapOf(
            "commandId" to "cmd-${UUID.randomUUID()}",
            "clientId" to clientId,
            "sessionId" to session,
            "type" to "stop",
            "payload" to (foregroundExecutionId
                ?.let { mapOf("expectedForegroundExecutionId" to it) }
                ?: emptyMap()),
            "issuedAt" to System.currentTimeMillis(),
        )
        ZLog.i(TAG, "stop session=$session fg=${foregroundExecutionId ?: "-"}")
        rpc.call(RpcChannel.CHANNEL_AGENT, "sendConversationCommandV4", listOf(args),
            timeoutMs = SEND_ACK_TIMEOUT_MS) { reply ->
            onResult(
                when (reply) {
                    is RpcChannel.RpcReply.Err ->
                        ResolveResult.Failed("rpc-error", reply.message)
                    is RpcChannel.RpcReply.Ok -> parseCommandAck(reply.data)
                }
            )
        }
    }

    private fun JsonElement?.asObj(): JsonObject? =
        this?.let { runCatching { it.jsonObject }.getOrNull() }

    private fun JsonElement?.asStr(): String? =
        this?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

    private fun JsonElement?.asInt(): Int? =
        this?.let { runCatching { it.jsonPrimitive.content.toIntOrNull() }.getOrNull() }

    private fun JsonElement?.asArray(): List<JsonElement>? =
        this?.let { runCatching { it.jsonArray }.getOrNull() }

    /** 工作区模型状态（readWorkspaceState 解析结果）。 */
    data class ModelConfig(
        val providerId: String,
        val modelId: String,
        val label: String? = null,
        val providerLabel: String? = null,
        val thought: String? = null,
        val mode: String? = null,
        val thoughtLevels: List<String>? = null,
    ) {
        /** 显示名：优先 label，其次 "providerLabel / modelId"，最后 modelId。 */
        fun displayName(): String = when {
            !label.isNullOrBlank() -> label
            !providerLabel.isNullOrBlank() -> "$providerLabel · ${modelId.substringAfterLast('/')}"
            else -> modelId.substringAfterLast('/')
        }
    }

    data class WorkspaceState(
        /** 当前选中的模型（未读到时为 null，UI 显示"默认（跟随 PC 端）"）。 */
        val current: ModelConfig?,
        /** 可用模型目录。 */
        val available: List<ModelConfig>,
        /** 当前执行模式（yolo/build/plan）。 */
        val currentMode: String? = null,
        val availableModes: List<String>? = null,
        /** 当前思考等级。 */
        val currentThought: String? = null,
    )

    /**
     * 已上传附件的引用（sendPrompt `attachments` 数组元素）。
     * 字段与官方 web 的 `attachmentRef` 一致（`{ref, fileName, mime, bytes}`，
     * 见 research/index-nOVzQNKW.js），host 侧对无 localPath 的元素原样透传。
     */
    data class AttachmentRef(
        val ref: String,
        val fileName: String,
        val mime: String,
        val bytes: Long,
    ) {
        fun toWire(): Map<String, Any> = mapOf(
            "ref" to ref,
            "fileName" to fileName,
            "mime" to mime,
            "bytes" to bytes,
        )
    }

    /** 审批应答结果。 */
    sealed interface ResolveResult {
        /** status: accepted / duplicate / noop（后两枚说明已被桌面端或重复请求消解）。 */
        data class Accepted(val status: String, val reasonCode: String?) : ResolveResult
        data class Failed(val code: String, val message: String) : ResolveResult
    }

    companion object {
        private const val TAG = "ConvChannel"
        private const val APP_VERSION = "1.0.0"

        /** 附件分片大小：与官方 web 客户端一致（host 上限 attachmentChunkMaxBytes=512KiB）。 */
        private const val CHUNK_BYTES = 384 * 1024

        /**
         * 发送/应答/停止类 envelope RPC 的 ack 超时。服务端正常应答在秒级；
         * 桥半死（断线瞬间）永远等不到 ack，必须兜底让调用方复位 UI 状态
         * （真机验收发现「发送中」永久卡死，2026-09-30）。
         */
        private const val SEND_ACK_TIMEOUT_MS = 15_000L

        /**
         * A-2：握手三跳的超时阈值。
         *
         * **依据（T0 真机实测，2026-10-07，小米 15 Pro / Android 17，6 次冷启动样本）**：
         * hello 132–211ms（median 140）、initialize 127–198ms（median 145）、
         * subscribe 127–186ms（median 139）、ack→快照 99–141ms，整链路 512–740ms。
         *
         * 取值 = 实测最大值的约 **19 倍余量**：容得下弱网/拥塞下 10 倍级的 RTT 恶化，
         * 又能把真实卡死稳稳压在验收要求的「10s 内给出可读失败」以内（首跳 4s 即报）。
         * 统计口径与原始样本见 `体验提升任务书-v2.md` §9 与 `_tmp/t0_samples.txt`。
         */
        private const val HANDSHAKE_HELLO_TIMEOUT_MS = 4_000L
        private const val HANDSHAKE_INIT_TIMEOUT_MS = 4_000L
        private const val HANDSHAKE_SUBSCRIBE_TIMEOUT_MS = 5_000L

        /** host 常量 attachmentMaxBytes = 20MiB、attachmentUploadMaxChunks = 64。 */
        /** 与 host 常量 attachmentMaxBytes 一致（UI 选附件时同值校验）。 */
        internal const val MAX_ATTACHMENT_BYTES = 20 * 1024 * 1024
        private const val MAX_CHUNKS = 64

        /** 服务端命令 ack 中算成功三种状态（duplicate/noop 表示已被他处消解）。 */
        private val SUCCESS_STATUSES = setOf("accepted", "duplicate", "noop")

        /**
         * A-2：底层英文错误 → 用户可读中文。
         * UI 直接展示该串，因此**不得**把 `timeout after 8000ms` / `bridge not ready`
         * 这类裸英文原样透出（见任务书 §7 异常的 grep 断言）。
         */
        internal fun handshakeFailureReason(stage: String, message: String): String = when {
            // 注意：CJK 字符是合法标识符字符，`$stage超时` 会被解析成变量名 `stage超时`，必须用 ${stage}
            isTimeoutError(message) -> "${stage}超时，请检查网络后重试"
            message.contains("bridge not ready") -> "通道尚未就绪，请稍后重试"
            else -> "${stage}失败：$message"
        }

        /** 超时判定：只用于把 timeout 映射成「超时」措辞。 */
        internal fun isTimeoutError(message: String): Boolean = message.startsWith("timeout")

        /**
         * 瞬态握手失败判定：是否值得自动重订一次。
         *
         * - `timeout…`：单帧 201 丢失（A-2 的场景）；
         * - `bridge not ready`：工作区桥尚未就绪或正在重建 —— `RpcChannel` 在 `Opening` 期间
         *   对任何 call 都立即回此错，属**瞬态**；
         * - `bridge re-established`：桥重建导致旧请求作废。
         *
         * 真机反馈（2026-10-07）：旧判定只认 timeout，于是「中继显示已连接、会话却报
         * `hello: bridge not ready`」时**永不自动恢复**，用户只能手动点重试或切会话。
         * 本函数正是覆盖该情形。
         */
        internal fun isTransientHandshakeError(message: String): Boolean =
            isTimeoutError(message) ||
                message.contains("bridge not ready") ||
                message.contains("bridge re-established")

        /**
         * A-2：迟到应答判定。切会话 / 自动重订后，上一轮握手（replyGeneration 已落后
         * 于当前代次）的回调必须被丢弃，否则旧握手会覆盖新会话的状态。
         */
        internal fun isStaleReply(replyGeneration: Int, currentGeneration: Int): Boolean =
            replyGeneration != currentGeneration
    }
}
