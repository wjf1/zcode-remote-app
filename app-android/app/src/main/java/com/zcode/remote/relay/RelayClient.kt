package com.zcode.remote.relay

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * 中继连接状态机（PROTOCOL.md 第 3/4 节）。
 */
sealed interface RelayState {
    data object Idle : RelayState
    data object Connecting : RelayState
    data object Authenticating : RelayState
    data object WaitingPeer : RelayState   // pair_status=waiting：PC 端未开启远程控制
    data object Paired : RelayState        // pair_status=matched
    data class Failed(val reason: FailureReason, val message: String?) : RelayState
}

enum class FailureReason { KICKED, AUTH_FAILED, DEVICE_OFFLINE, NETWORK, INTERNAL }

class RelayClient(
    private val device: com.zcode.remote.storage.PairedDevice,
    private val appVersion: String = "0.1.0",
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow<RelayState>(RelayState.Idle)
    val state: StateFlow<RelayState> = _state

    private val _inbound = MutableSharedFlow<JsonObject>(
        extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** 内层 data.payload（zcode_type 路由后），UI/会话层订阅。 */
    val inbound: SharedFlow<JsonObject> = _inbound

    private var socket: WebSocket? = null
    @Volatile private var manuallyClosed = false
    @Volatile private var reconnectAttempt = 0
    private var heartbeatThread: Thread? = null

    fun connect() {
        manuallyClosed = false
        _state.value = RelayState.Connecting
        // 官方终端会追加 mid 参数（PROTOCOL.md 3 节）；主机在线时中继强制校验，缺失直接 AUTH_FAILED
        val url = device.relayWsUrl +
            (device.deviceMid?.takeIf { it.isNotBlank() }?.let { "?mid=$it" } ?: "")
        val request = Request.Builder()
            .url(url)
            .header("Origin", "https://zcode.z.ai")
            .build()
        socket = client.newWebSocket(request, listener)
    }

    fun close() {
        manuallyClosed = true
        stopHeartbeat()
        socket?.close(1000, "client-close")
        socket = null
        _state.value = RelayState.Idle
    }

    fun send(obj: JsonObject): Boolean {
        val s = RelayProtocol.json.encodeToString(JsonObject.serializer(), obj)
        android.util.Log.i(TAG, "ws send ${s.take(200)}")
        return socket?.send(s) ?: false
    }

    /** 业务负载必须包 data 信封并带 client_ts（官方 prepare() 实证）。 */
    fun sendPayload(payload: JsonObject): Boolean {
        val wrapped = kotlinx.serialization.json.buildJsonObject {
            put("type", "data")
            put("payload", payload)
            put("client_ts", System.currentTimeMillis())
        }
        return send(wrapped)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            android.util.Log.i(TAG, "ws open http=${response.code}")
            _state.value = RelayState.Authenticating
            send(RelayProtocol.authInit(device.deviceSid, appVersion))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            android.util.Log.i(TAG, "ws recv $text")
            val frame = runCatching { RelayProtocol.parse(text) }.getOrNull() ?: return
            when (RelayProtocol.typeOf(frame)) {
                "auth_challenge" -> {
                    val nonce = RelayProtocol.nonceOf(frame) ?: return
                    val proof = RelayProtocol.calculateProof(
                        device.passHash, nonce, RelayProtocol.ROLE_TERMINAL, device.deviceSid)
                    android.util.Log.i(TAG, "proof=$proof sid=${device.deviceSid} hashLen=${device.passHash.length}")
                    send(RelayProtocol.authResponse(device.deviceSid, proof))
                }
                "auth_ack", "pair_status_ack" -> {
                    when (RelayProtocol.pairStatusOf(frame)) {
                        "waiting" -> { _state.value = RelayState.WaitingPeer; startHeartbeat() }
                        "matched" -> { reconnectAttempt = 0; _state.value = RelayState.Paired; startHeartbeat() }
                    }
                }
                "data" -> {
                    val payload = frame["payload"]?.let {
                        runCatching { it.jsonObject }.getOrNull()
                    } ?: return
                    _inbound.tryEmit(payload)
                }
                "error" -> {
                    val err = RelayProtocol.RelayError.from(RelayProtocol.errorCodeOf(frame))
                    when (err) {
                        RelayProtocol.RelayError.KICKED ->
                            fail(FailureReason.KICKED, "会话已在别处打开（与官方 Web 版互踢）")
                        RelayProtocol.RelayError.AUTH_FAILED,
                        RelayProtocol.RelayError.WRONG_PARAM ->
                            fail(FailureReason.AUTH_FAILED, "配对失效，请重新扫码")
                        RelayProtocol.RelayError.DEVICE_OFFLINE -> {
                            _state.value = RelayState.WaitingPeer; scheduleReconnect()
                        }
                        else -> fail(FailureReason.INTERNAL, "中继内部错误")
                    }
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            android.util.Log.w(TAG, "ws failure", t)
            if (manuallyClosed) return
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            android.util.Log.i(TAG, "ws closed code=$code reason=$reason")
            if (!manuallyClosed) scheduleReconnect()
        }
    }

    companion object { private const val TAG = "RelayClient" }

    private fun fail(reason: FailureReason, message: String?) {
        stopHeartbeat()
        _state.value = RelayState.Failed(reason, message)
    }

    private fun startHeartbeat() {
        if (heartbeatThread?.isAlive == true) return
        heartbeatThread = Thread {
            while (!Thread.currentThread().isInterrupted && !manuallyClosed) {
                runCatching { send(RelayProtocol.heartbeat(device.deviceSid)) }
                try {
                    Thread.sleep(30_000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun stopHeartbeat() { heartbeatThread?.interrupt(); heartbeatThread = null }

    private fun scheduleReconnect() {
        stopHeartbeat()
        if (manuallyClosed) return
        val delayMs = (3000L * (1L shl minOf(reconnectAttempt, 4)))  // 3s,6s,12s,24s,48s 封顶
        reconnectAttempt++
        Thread { runCatching { Thread.sleep(delayMs) }; if (!manuallyClosed) connect() }
            .apply { isDaemon = true; start() }
    }
}
