package com.zcode.remote.relay

import android.util.Base64
import android.util.Log
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
        data class Failed(val reason: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status

    /** 会话标题、阶段等快照元信息（UI 可用）。 */
    private val _meta = MutableStateFlow(ConversationMeta())
    val meta: StateFlow<ConversationMeta> = _meta

    /** 当前会话的待审批项（服务端整组给出，手机侧不额外累积）。 */
    private val _interactions = MutableStateFlow<List<PendingApproval>>(emptyList())
    val interactions: StateFlow<List<PendingApproval>> = _interactions

    /** 当前会话的待应答表单交互（pendingInteractions 里 kind=="userInput"，整组替换）。 */
    private val _elicitations = MutableStateFlow<List<PendingElicitation>>(emptyList())
    val elicitations: StateFlow<List<PendingElicitation>> = _elicitations

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
    )

    private val clientId = "android-${UUID.randomUUID()}"
    private var listenId: Int? = null
    private var subscriptionId: String? = null
    private var sessionId: String? = null

    /** 订阅目标（workspacePath/Identity）——发命令时要原样带上，服务端按它路由。 */
    private var subTarget: Map<String, Any>? = null

    /** 最近一次 control 里的前台执行 id，stop 命令作为 expectedForegroundExecutionId 带上（防误停）。 */
    private var foregroundExecutionId: String? = null

    /** 订阅指定会话，并把行写入 [store]。 */
    fun subscribe(workspacePath: String, workspaceIdentity: String?, session: String, store: RowStore) {
        sessionId = session
        subscriptionId = null
        store.clear()
        _status.value = Status.Idle
        _interactions.value = emptyList()

        val target = buildMap<String, Any> {
            put("workspacePath", workspacePath)
            workspaceIdentity?.takeIf { it.isNotBlank() }?.let { put("workspaceIdentity", it) }
        }
        subTarget = target

        // 1) 先挂监听：之后所有 204 都挂在 listenId 上。
        //    事件监听服务端只回 204、不回 201，故不注册应答回调（否则会留下永不触发的挂起项）。
        listenId = rpc.listen(RpcChannel.CHANNEL_AGENT, "onDynamicConversationFrame", target)

        // 2) hello → 拿 protocolVersion / connectionId
        rpc.call(RpcChannel.CHANNEL_AGENT, "helloConversationV4") { reply ->
            when (reply) {
                is RpcChannel.RpcReply.Err ->
                    _status.value = Status.Failed("hello: ${reply.message}")
                is RpcChannel.RpcReply.Ok -> {
                    val o = reply.data.asObj()
                    val pv = o?.get("protocolVersion")?.let {
                        runCatching { it.jsonPrimitive.content.toIntOrNull() }.getOrNull() } ?: 3
                    _meta.value = _meta.value.copy(
                        connectionId = o?.get("connectionId")?.let {
                            runCatching { it.jsonPrimitive.content }.getOrNull() })
                    _status.value = Status.Hello
                    handshake(pv, target, session)
                }
            }
        }
    }

    /** 3) initializeConversationV4(clientHello) → 4) subscribeConversationV4 */
    private fun handshake(protocolVersion: Int, target: Map<String, Any>, session: String) {
        val clientHello = mapOf(
            "kind" to "clientHello",
            "protocolVersion" to protocolVersion,
            "clientId" to clientId,
            "clientKind" to "mobileApp",
            "appVersion" to APP_VERSION,
        )
        rpc.call(RpcChannel.CHANNEL_AGENT, "initializeConversationV4", listOf(clientHello)) { reply ->
            if (reply is RpcChannel.RpcReply.Err) {
                _status.value = Status.Failed("initialize: ${reply.message}")
                return@call
            }
            _status.value = Status.Initialized

            val args = HashMap<String, Any>(target)
            args["sessionId"] = session
            rpc.call(RpcChannel.CHANNEL_AGENT, "subscribeConversationV4", listOf(args)) { sub ->
                when (sub) {
                    is RpcChannel.RpcReply.Err ->
                        _status.value = Status.Failed("subscribe: ${sub.message}")
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
                            Log.i(TAG, "subscribed sub=$subId mode=$mode logEpoch=$epoch")
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
            Log.w(TAG, "无法解析逻辑帧: ${data.toString().take(300)}")
            return
        }
        if (lf.kind == "fragment") {
            Log.w(TAG, "收到分片逻辑帧（未实现重组）frameId=${lf.logicalFrameId}")
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
                _interactions.value = snap.pendingInteractions
                _elicitations.value = snap.elicitations
                val control = snap.control
                control?.foregroundExecutionId?.let { foregroundExecutionId = it }
                _meta.value = _meta.value.copy(
                    title = snap.title ?: _meta.value.title,
                    phase = snap.phase,
                    totalCount = snap.totalCount,
                    logEpoch = snap.logEpoch ?: _meta.value.logEpoch,
                    canStop = control?.canStop ?: _meta.value.canStop,
                    stopState = control?.stopState ?: _meta.value.stopState,
                )
                Log.i(TAG, "snapshot: ${snap.rows.size} 行（总 ${snap.totalCount}）" +
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
                                Log.i(TAG, "pendingInteractions → ${next.size} 条 " +
                                        next.joinToString(",") { "${it.toolName ?: "?"}#${it.interactionId.take(18)}" })
                            }
                            // elicitations 同组替换；仅当 patch 带该键时才更新（缺失=不涉及）
                            p["pendingInteractions"]?.let {
                                val nextE = PendingElicitation.parseArray(
                                    runCatching { it.jsonArray }.getOrNull(), sessionId)
                                if (nextE != _elicitations.value) {
                                    _elicitations.value = nextE
                                    Log.i(TAG, "elicitations → ${nextE.size} 条 " +
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
                                    Log.i(TAG, "control 更新 phase=${ctl.phase} canStop=${ctl.canStop} stopState=${ctl.stopState}")
                                }
                            }
                        }
                        is ConversationFrames.Delta.Unknown ->
                            Log.i(TAG, "未处理 delta：${d.op}")
                    }
                }
                // deltas 为空是合法的（仅推 seq 的心跳），不记日志避免刷屏
            }
            else -> Log.i(TAG, "未知 payload kind=${frame.payloadKind}")
        }
    }

    fun reset() {
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
        Log.i(TAG, "loadEarlier beforeRowId=$firstRowId session=$session")
        rpc.call(RpcChannel.CHANNEL_AGENT, "conversationRowsRangeV4", listOf(args)) { reply ->
            when (reply) {
                is RpcChannel.RpcReply.Err -> {
                    Log.w(TAG, "loadEarlier error: ${reply.message}")
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
                        Log.w(TAG, "loadEarlier logEpoch 不匹配（$atEpoch != $curEpoch），整批丢弃")
                        _earlier.value = EarlierState(loading = false, hasMore = false, pulled = true)
                        onResult(Result.failure(IllegalStateException("logEpoch 已变更")))
                        return@call
                    }
                    val hasMore: Boolean = result?.get("hasMore")?.let { e ->
                        val s = runCatching { e.jsonPrimitive.content }.getOrNull()
                        s?.toBooleanStrictOrNull() ?: (s == "true")
                    } ?: false
                    if (rows.isNotEmpty()) store.prepend(rows)
                    _earlier.value = EarlierState(loading = false, hasMore = hasMore, pulled = true)
                    Log.i(TAG, "loadEarlier +${rows.size} 行 hasMore=$hasMore")
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
    fun resolve(
        approval: PendingApproval,
        option: ApprovalOption,
        onResult: (ResolveResult) -> Unit,
    ) {
        val session = sessionId ?: approval.sessionId
        val target = subTarget
        if (session == null || target == null) {
            onResult(ResolveResult.Failed("no-session", "未订阅该会话，无法应答"))
            return
        }
        if (option.optionId.isBlank()) {
            onResult(ResolveResult.Failed("no-option", "服务端未给出 optionId"))
            return
        }

        Log.i(TAG, "resolve interaction=${approval.interactionId} option=${option.optionId}" +
                " kind=${option.kind} label=${option.label}")
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
     */
    fun resolveElicitation(
        el: PendingElicitation,
        answer: JsonObject,
        onResult: (ResolveResult) -> Unit,
    ) {
        val session = sessionId ?: el.sessionId
        val target = subTarget
        if (session == null || target == null) {
            onResult(ResolveResult.Failed("no-session", "未订阅该会话，无法应答"))
            return
        }
        Log.i(TAG, "resolveElicitation interaction=${el.interactionId} answer=$answer")
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
        rpc.call(RpcChannel.CHANNEL_AGENT, "sendConversationCommandV4", listOf(args)) { reply ->
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
     * 发送用户消息（HANDOVER §5.3 实测路径）：`zcode-agent` 通道 `sendPrompt`，
     * args = `{workspacePath, sessionId, inputId, content}`。
     * 消息进入会话队列（autoDrain），当前 turn 结束后自动新 turn 执行；
     * 成功后 userInput 行会由服务端推回会话流，无需本地 append。
     *
     * [attachments] 非空时带上 `attachments` 数组（元素 = [AttachmentRef.toWire]）。
     * host 侧 `createRemotePromptAttachmentSessionService`（asar/out/host/index.js）会包装
     * `sendPrompt` 并调 `materializePromptAttachments`；已上传的 ref 无 localPath，原样透传。
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
        val args = HashMap<String, Any>(target)
        args["sessionId"] = session
        args["inputId"] = "inp-${UUID.randomUUID()}"
        args["content"] = content
        if (attachments.isNotEmpty()) args["attachments"] = attachments.map { it.toWire() }
        Log.i(TAG, "sendPrompt session=$session chars=${content.length} attachments=${attachments.size}")
        rpc.call(RpcChannel.CHANNEL_AGENT, "sendPrompt", listOf(args)) { reply ->
            onResult(
                when (reply) {
                    is RpcChannel.RpcReply.Err -> Result.failure(IllegalStateException(reply.message))
                    is RpcChannel.RpcReply.Ok -> Result.success(Unit)
                }
            )
        }
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
    fun uploadAttachment(
        fileName: String,
        mime: String,
        data: ByteArray,
        onProgress: ((uploaded: Long, total: Long) -> Unit)? = null,
        onResult: (Result<AttachmentRef>) -> Unit,
    ) {
        val session = sessionId
        val target = subTarget
        if (session == null || target == null) {
            onResult(Result.failure(IllegalStateException("未订阅会话，无法上传附件")))
            return
        }
        if (data.isEmpty()) {
            onResult(Result.failure(IllegalArgumentException("附件内容为空")))
            return
        }
        if (data.size > MAX_ATTACHMENT_BYTES) {
            onResult(Result.failure(IllegalArgumentException("附件超过 20MiB 上限")))
            return
        }
        val totalChunks = (data.size + CHUNK_BYTES - 1) / CHUNK_BYTES
        if (totalChunks > MAX_CHUNKS) {
            onResult(Result.failure(IllegalArgumentException("附件分片数超过上限")))
            return
        }
        val total = data.size.toLong()
        val base = HashMap<String, Any>(target)
        base["sessionId"] = session
        base["uploadId"] = "upload-${UUID.randomUUID()}"

        fun abort() {
            rpc.call(RpcChannel.CHANNEL_AGENT, "attachmentAbortV4", listOf(HashMap(base))) {
                Log.i(TAG, "attachmentAbort sent uploadId=${base["uploadId"]}")
            }
        }

        fun fail(message: String) {
            Log.w(TAG, "uploadAttachment 失败：$message")
            abort()
            onResult(Result.failure(IllegalStateException(message)))
        }

        // 注意：Kotlin 局部函数只能引用「已声明」的同层函数，故按依赖顺序声明。
        fun commit() {
            rpc.call(RpcChannel.CHANNEL_AGENT, "attachmentCommitV4", listOf(HashMap(base))) { reply ->
                when (reply) {
                    is RpcChannel.RpcReply.Err -> fail("提交附件失败：${reply.message}")
                    is RpcChannel.RpcReply.Ok -> {
                        val ref = reply.data.asObj()?.get("ref").asStr()
                        if (ref.isNullOrBlank()) fail("提交附件未返回 ref：${reply.data}")
                        else {
                            Log.i(TAG, "附件已提交 ref=$ref file=$fileName bytes=${data.size}")
                            onResult(Result.success(AttachmentRef(ref, fileName, mime, total)))
                        }
                    }
                }
            }
        }

        fun uploadChunks(from: Int) {
            var index = from
            fun next() {
                if (index >= totalChunks) { commit(); return }
                val off = index * CHUNK_BYTES
                val end = minOf(off + CHUNK_BYTES, data.size)
                val args = HashMap(base)
                args["chunkIndex"] = index
                args["dataBase64"] = Base64.encodeToString(data.copyOfRange(off, end), Base64.NO_WRAP)
                val sent = index
                rpc.call(RpcChannel.CHANNEL_AGENT, "attachmentChunkV4", listOf(args)) { reply ->
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
        beginArgs["totalBytes"] = data.size
        beginArgs["totalChunks"] = totalChunks
        beginArgs["checksum"] = "sha256:" + sha256Hex(data)
        Log.i(TAG, "attachmentBegin file=$fileName mime=$mime bytes=${data.size} chunks=$totalChunks")
        rpc.call(RpcChannel.CHANNEL_AGENT, "attachmentBeginV4", listOf(beginArgs)) { reply ->
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
    }

    private fun sha256Hex(data: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }



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
        Log.i(TAG, "stop session=$session fg=${foregroundExecutionId ?: "-"}")
        rpc.call(RpcChannel.CHANNEL_AGENT, "sendConversationCommandV4", listOf(args)) { reply ->
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

        /** host 常量 attachmentMaxBytes = 20MiB、attachmentUploadMaxChunks = 64。 */
        private const val MAX_ATTACHMENT_BYTES = 20 * 1024 * 1024
        private const val MAX_CHUNKS = 64

        /** 服务端命令 ack 中算成功三种状态（duplicate/noop 表示已被他处消解）。 */
        private val SUCCESS_STATUSES = setOf("accepted", "duplicate", "noop")
    }
}
