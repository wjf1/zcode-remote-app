package com.zcode.remote.relay

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.zip.CRC32

/**
 * 会话桥 + RPC 通道（规格见 research/FRAME-CODEC.md、research/CONVERSATION-PROTOCOL.md）。
 *
 * 流程：workspace-bridge-open → workspace-bridge-ready → 发 rpc-frame（Vql 编码）→ 收 204 EventFire。
 * M1 简化：仅单分片（消息 < 1MiB envelope 预算），分片/重传待后续。
 */
class RpcChannel(private val relay: RelayClient) {

    sealed interface BridgeState {
        data object Closed : BridgeState
        data object Opening : BridgeState
        data class Ready(val bridgeSessionId: String) : BridgeState
        data class Failed(val reason: String) : BridgeState
    }

    /** Promise 调用的结果：202/203 不再伪装成 null，便于调用方区分「失败」与「成功但无数据」。 */
    sealed interface RpcReply {
        data class Ok(val data: JsonElement?) : RpcReply
        data class Err(val message: String, val raw: JsonElement?) : RpcReply
    }

    /** 204 EventFire：带请求 id（事件监听的应答都挂在该监听的 id 上）。 */
    data class RpcEvent(val id: Int?, val data: JsonElement)

    private val _events = MutableSharedFlow<RpcEvent>(
        extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<RpcEvent> = _events

    private val _bridge = MutableStateFlow<BridgeState>(BridgeState.Closed)
    val bridge: StateFlow<BridgeState> = _bridge

    /** 最近一次 ready 回包（含 bridge.* 身份与 host 能力）。 */
    var readyPayload: JsonObject? = null
        private set

    /** 桥代次（identity 三元组之一，PC 端做全等校验，必须回填到 rpc-frame）。 */
    private var bridgeGeneration: Int? = null
    private var recoveryId: String? = null

    private var nextRequestId = 0
    private var nextPhysicalSeq = 1
    private var nextMessageSeq = 1
    private var lastAckedMessageSeq = 0

    private val pendingResponses = mutableMapOf<Int, (RpcReply) -> Unit>()

    val bridgeIdentity: JsonObject?
        get() {
            val r = readyPayload ?: return null
            return r["bridge"]?.let { runCatching { it.jsonObject }.getOrNull() }
        }

    // ---------- 开桥 ----------

    fun openBridge(workspaceKey: String, taskId: String? = null) {
        val sid = "bridge-${UUID.randomUUID()}"
        _bridge.value = BridgeState.Opening
        val payload = buildJsonObject {
            put("zcode_type", "workspace-bridge-open")
            put("requestId", "workspace-bridge-${UUID.randomUUID()}")
            put("bridgeSessionId", sid)
            put("bridgeGeneration", 1)
            put("workspaceKey", workspaceKey)
            taskId?.let { put("taskId", it) }
        }
        Log.i(TAG, "bridge-open ws=$workspaceKey sid=$sid")
        relay.sendPayload(payload)
    }

    /** 由 AppViewModel 在收到 workspace-bridge-* 帧时调用。 */
    fun onBridgeFrame(payload: JsonObject) {
        when (BridgeFrames.zcodeTypeOf(payload)) {
            "workspace-bridge-ready" -> {
                val sid = payload["bridgeSessionId"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
                readyPayload = payload
                // 身份三元组以此为准（PC 端 enforces 全等匹配）
                bridgeGeneration = payload["bridgeGeneration"]?.let {
                    runCatching { it.jsonPrimitive.content.toIntOrNull() }.getOrNull() }
                recoveryId = payload["recoveryId"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
                Log.i(TAG, "bridge-ready sid=$sid gen=$bridgeGeneration payload=${payload.toString().take(500)}")
                if (sid != null) _bridge.value = BridgeState.Ready(sid)
            }
            "workspace-bridge-error", "bridge-degraded" -> {
                Log.w(TAG, "bridge error: ${payload.toString().take(300)}")
                _bridge.value = BridgeState.Failed(payload.toString().take(200))
            }
        }
    }

    // ---------- RPC ----------

    /**
     * 发一次 Promise 调用：serialize([100,id,channel,method]) ++ serialize(args)
     *
     * args 是**参数数组**（例：单参数调用传 `listOf(mapOf(...))`）。
     */
    fun call(
        channelName: String,
        method: String,
        args: List<Any?> = emptyList(),
        onResponse: ((RpcReply) -> Unit)? = null,
    ): Int? {
        val bridge = _bridge.value
        if (bridge !is BridgeState.Ready) {
            Log.w(TAG, "call($method) skipped: bridge not ready (${bridge})")
            onResponse?.invoke(RpcReply.Err("bridge not ready", null))
            return null
        }
        val id = nextRequestId++
        if (onResponse != null) pendingResponses[id] = onResponse
        val msg = Vql.serialize(listOf(TYPE_PROMISE, id, channelName, method), args)
        Log.i(TAG, "rpc call channel=$channelName method=$method id=$id bytes=${msg.size}")
        sendMessage(msg, bridge.bridgeSessionId)
        return id
    }

    /**
     * 注册事件监听：serialize([102,id,channel,event]) ++ serialize(arg)
     *
     * ⚠️ 与 [call] 不同，事件监听的参数段是**单个裸值**（不是数组）——
     * 见 toService 代理里 `isDynamicEvent(name) ? listener => listen(name, listener)` 分支。
     * 之后服务端用 204 持续推送，事件 id 即本次请求的 id。
     */
    fun listen(
        channelName: String,
        event: String,
        arg: Any?,
        onResponse: ((RpcReply) -> Unit)? = null,
    ): Int? {
        val bridge = _bridge.value
        if (bridge !is BridgeState.Ready) {
            Log.w(TAG, "listen($event) skipped: bridge not ready (${bridge})")
            onResponse?.invoke(RpcReply.Err("bridge not ready", null))
            return null
        }
        val id = nextRequestId++
        if (onResponse != null) pendingResponses[id] = onResponse
        val msg = Vql.serialize(listOf(TYPE_EVENT_LISTEN, id, channelName, event), arg)
        Log.i(TAG, "rpc listen channel=$channelName event=$event id=$id bytes=${msg.size}")
        sendMessage(msg, bridge.bridgeSessionId)
        return id
    }

    private fun sendMessage(message: ByteArray, bridgeSessionId: String) {
        val bytes = message
        val crc = CRC32().apply { update(bytes) }.value
        val checksum = buildJsonObject {
            put("algorithm", "crc32")
            put("value", String.format("%08x", crc))
        }
        val seq = nextPhysicalSeq++
        val messageSeq = nextMessageSeq++
        val frame = buildJsonObject {
            put("zcode_type", "rpc-frame")
            put("bridgeSessionId", bridgeSessionId)
            bridgeGeneration?.let { put("bridgeGeneration", it) }
            recoveryId?.let { put("recoveryId", it) }
            put("seq", seq)
            put("messageSeq", messageSeq)
            put("fragmentIndex", 0)
            put("fragmentCount", 1)
            put("messageBytes", bytes.size)
            put("checksum", checksum)
            put("dataBase64", Base64.encodeToString(bytes, Base64.NO_WRAP))
        }
        relay.sendPayload(frame)
    }

    /** 收到 rpc-frame（单分片）→ 解码 → 路由。 */
    fun onRpcFrame(payload: JsonObject) {
        val b64 = payload["dataBase64"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() } ?: return
        val bytes = runCatching { Base64.decode(b64, Base64.NO_WRAP) }.getOrNull() ?: return
        val messageSeq = payload["messageSeq"]?.let {
            runCatching { it.jsonPrimitive.content.toIntOrNull() }.getOrNull()
        }
        messageSeq?.let { ack(it, (bridge.value as? BridgeState.Ready)?.bridgeSessionId) }

        var type = -1
        var id: Int? = null
        var data: Any? = null
        try {
            val head = Vql.deserialize(bytes)
            val kind = head.value as? List<*>
            if (kind == null) {
                Log.w(TAG, "rpc frame head not array: ${head.value?.toString()?.take(120)}")
                return
            }
            type = (kind.getOrNull(0) as? Int) ?: -1
            id = kind.getOrNull(1) as? Int
            data = Vql.deserialize(bytes, head.nextOffset).value
        } catch (t: Throwable) {
            Log.w(TAG, "rpc decode failed", t)
            return
        }

        Log.i(TAG, "rpc recv type=$type id=$id data=${data?.toString()?.take(400)}")
        when (type) {
            TYPE_SUCCESS -> {
                val cb = id?.let { pendingResponses.remove(it) }
                cb?.invoke(RpcReply.Ok(toJsonElement(data)))
            }
            TYPE_ERROR, TYPE_ERROR_OBJ -> {
                val el = toJsonElement(data)
                val msg = el.let { runCatching { it.jsonObject["message"]?.jsonPrimitive?.content }.getOrNull() }
                    ?: el.toString().take(300)
                Log.w(TAG, "rpc error id=$id: $msg")
                val cb = id?.let { pendingResponses.remove(it) }
                cb?.invoke(RpcReply.Err(msg, el))
            }
            TYPE_EVENT_FIRE -> _events.tryEmit(RpcEvent(id, toJsonElement(data)))
            else -> Unit
        }
    }

    private fun toJsonElement(v: Any?): JsonElement = when (v) {
        null -> kotlinx.serialization.json.JsonNull
        is JsonElement -> v
        is String -> kotlinx.serialization.json.JsonPrimitive(v)
        is Int -> kotlinx.serialization.json.JsonPrimitive(v)
        is List<*> -> kotlinx.serialization.json.JsonArray(v.map { toJsonElement(it) })
        else -> kotlinx.serialization.json.JsonPrimitive(v.toString())
    }

    /**
     * 回 ack。**必须带齐身份三元组**：PC 端做全等比较（含 undefined），
     * 只要帧里出现任一身份键就要求三个全等，否则整帧被静默丢弃、服务端会持续重放。
     * 曾因漏 bridgeGeneration 导致 ack 全部失效（见 CONVERSATION-PROTOCOL.md §8）。
     */
    private fun ack(messageSeq: Int, bridgeSessionId: String?) {
        if (messageSeq <= lastAckedMessageSeq || bridgeSessionId == null) return
        lastAckedMessageSeq = messageSeq
        relay.sendPayload(buildJsonObject {
            put("zcode_type", "rpc-frame-ack")
            put("bridgeSessionId", bridgeSessionId)
            bridgeGeneration?.let { put("bridgeGeneration", it) }
            recoveryId?.let { put("recoveryId", it) }
            put("ackMessageSeq", messageSeq)
        })
    }

    fun reset() {
        _bridge.value = BridgeState.Closed
        readyPayload = null
        nextRequestId = 0
        nextPhysicalSeq = 1
        nextMessageSeq = 1
        lastAckedMessageSeq = 0
        pendingResponses.clear()
    }

    companion object {
        private const val TAG = "RpcChannel"
        const val TYPE_PROMISE = 100
        const val TYPE_PROMISE_CANCEL = 101
        const val TYPE_EVENT_LISTEN = 102
        const val TYPE_EVENT_DISPOSE = 103
        const val TYPE_SUCCESS = 201
        const val TYPE_ERROR = 202
        const val TYPE_ERROR_OBJ = 203
        const val TYPE_EVENT_FIRE = 204

        /** 通道名（asar/out/host/chunk-RWMCBKS2.js 枚举）。 */
        const val CHANNEL_AGENT = "zcode-agent"      // ← 会话/对话方法都在这个通道上
        const val CHANNEL_SESSION = "zcode-session"  // 仅 createSession 等会话管理方法
        const val CHANNEL_TASK = "zcode-task"
        const val CHANNEL_WORKSPACE = "workspace"
    }
}
