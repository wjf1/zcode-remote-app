package com.zcode.remote.relay

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    data class ConversationMeta(
        val title: String? = null,
        val phase: String? = null,
        val totalCount: Int? = null,
        val connectionId: String? = null,
        val logEpoch: String? = null,
    )

    private val clientId = "android-${UUID.randomUUID()}"
    private var listenId: Int? = null
    private var subscriptionId: String? = null
    private var sessionId: String? = null

    /** 订阅目标（workspacePath/Identity）——发命令时要原样带上，服务端按它路由。 */
    private var subTarget: Map<String, Any>? = null

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
                _meta.value = _meta.value.copy(
                    title = snap.title ?: _meta.value.title,
                    phase = snap.phase,
                    totalCount = snap.totalCount,
                )
                Log.i(TAG, "snapshot: ${snap.rows.size} 行（总 ${snap.totalCount}）" +
                        "待审批=${snap.pendingInteractions.size} delivery=${lf.deliveryKind}")
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
        _interactions.value = emptyList()
        _status.value = Status.Idle
        _meta.value = ConversationMeta()
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

        val args = HashMap<String, Any>(target)
        args["envelope"] = mapOf(
            "commandId" to "cmd-${UUID.randomUUID()}",
            "clientId" to clientId,
            "sessionId" to session,
            "type" to "resolveInteraction",
            "payload" to mapOf(
                "interactionId" to approval.interactionId,
                "answer" to mapOf("optionId" to option.optionId),
            ),
            "issuedAt" to System.currentTimeMillis(),
        )
        Log.i(TAG, "resolve interaction=${approval.interactionId} option=${option.optionId}" +
                " kind=${option.kind} label=${option.label}")
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

    private fun JsonElement?.asObj(): JsonObject? =
        this?.let { runCatching { it.jsonObject }.getOrNull() }

    private fun JsonElement?.asStr(): String? =
        this?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

    /** 审批应答结果。 */
    sealed interface ResolveResult {
        /** status: accepted / duplicate / noop（后两枚说明已被桌面端或重复请求消解）。 */
        data class Accepted(val status: String, val reasonCode: String?) : ResolveResult
        data class Failed(val code: String, val message: String) : ResolveResult
    }

    companion object {
        private const val TAG = "ConvChannel"
        private const val APP_VERSION = "1.0.0"

        /** 服务端命令 ack 中算成功三种状态（duplicate/noop 表示已被他处消解）。 */
        private val SUCCESS_STATUSES = setOf("accepted", "duplicate", "noop")
    }
}
