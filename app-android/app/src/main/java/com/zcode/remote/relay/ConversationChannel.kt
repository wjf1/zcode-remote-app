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

    /** 订阅指定会话，并把行写入 [store]。 */
    fun subscribe(workspacePath: String, workspaceIdentity: String?, session: String, store: RowStore) {
        sessionId = session
        subscriptionId = null
        store.clear()
        _status.value = Status.Idle

        val target = buildMap<String, Any> {
            put("workspacePath", workspacePath)
            workspaceIdentity?.takeIf { it.isNotBlank() }?.let { put("workspaceIdentity", it) }
        }

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
                _meta.value = _meta.value.copy(
                    title = snap.title ?: _meta.value.title,
                    phase = snap.phase,
                    totalCount = snap.totalCount,
                )
                Log.i(TAG, "snapshot: ${snap.rows.size} 行（总 ${snap.totalCount}）delivery=${lf.deliveryKind}")
            }
            "deltas" -> {
                val deltas = ConversationFrames.parseDeltas(frame.payload)
                for (d in deltas) {
                    when (d) {
                        is ConversationFrames.Delta.Upsert -> store.upsert(d.row)
                        is ConversationFrames.Delta.RemoveFrom -> store.removeFrom(d.fromRowId)
                        is ConversationFrames.Delta.AppendText -> store.appendText(d.rowId, d.path, d.append)
                        ConversationFrames.Delta.StateUpdated -> Unit
                        is ConversationFrames.Delta.Unknown -> Unit
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
        _status.value = Status.Idle
        _meta.value = ConversationMeta()
    }

    private fun JsonElement?.asObj(): JsonObject? =
        this?.let { runCatching { it.jsonObject }.getOrNull() }

    companion object {
        private const val TAG = "ConvChannel"
        private const val APP_VERSION = "1.0.0"
    }
}
