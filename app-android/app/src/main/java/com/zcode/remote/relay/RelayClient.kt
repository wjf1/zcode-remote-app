package com.zcode.remote.relay

import com.zcode.remote.util.ZLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

enum class FailureReason { KICKED, AUTH_FAILED, DEVICE_OFFLINE, NETWORK, INTERNAL, PROTOCOL_MISMATCH }

class RelayClient(
    private val device: com.zcode.remote.storage.PairedDevice,
    private val appVersion: String = "0.1.0",
    /** 线路覆盖（设置里强制主线/备线/自建中继）；null=按配对二维码推断。 */
    private val relayWsUrlOverride: String? = null,
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var pumpJob: Job? = null

    /** 重连前的挂起点（B 组）：默认纯退避延迟；App 侧注入 [NetworkGate] 后无网时等待网络恢复。 */
    @Volatile
    var networkWait: suspend (Long) -> Unit = { delayMs -> delay(delayMs) }

    /**
     * 网络丢失立即响应（NetworkGate 常驻监控转发）：不等 OkHttp 的迟到失败回调
     * （真机实证其延迟到网络恢复才冒出），马上清 socket + 调度重连，attempt 归零
     * ——网络丢失不是对端拒绝，恢复后应立即重连而非空烧退避。
     */
    fun onNetworkLost() {
        if (manuallyClosed || terminalFailed) return
        ZLog.i(TAG, "net-watch: 网络丢失，立即断开 socket 并调度重连")
        stopHeartbeat()
        runCatching { socket?.close(1000, "net-lost") }
        socket = null
        reconnectAttempt = 0
        scheduleReconnect()
    }

    private val _state = MutableStateFlow<RelayState>(RelayState.Idle)
    val state: StateFlow<RelayState> = _state

    private val _inbound = MutableSharedFlow<JsonObject>(
        replay = 0, extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.SUSPEND)
    /** 内层 data.payload（zcode_type 路由后），UI/会话层订阅。 */
    val inbound: SharedFlow<JsonObject> = _inbound

    /**
     * 入站帧队列（B 组）：OkHttp 线程只入队，单泵协程顺序 emit —— 保证帧序，且突发时
     * 挂起而非丢弃（原 DROP_OLDEST 会在长流式输出时静默丢最旧帧，会话行悄悄错位）。
     */
    private val frameQueue = Channel<JsonObject>(Channel.UNLIMITED)

    init { startPump() }

    private fun startPump() {
        if (pumpJob?.isActive == true) return
        pumpJob = io.launch {
            for (payload in frameQueue) _inbound.emit(payload)
        }
    }

    private var socket: WebSocket? = null
    @Volatile private var manuallyClosed = false
    /** 终态失败（互踢/配对失效/协议不匹配）：不参与自动重连，等用户手动恢复。 */
    @Volatile private var terminalFailed = false
    @Volatile private var reconnectAttempt = 0

    fun connect() {
        manuallyClosed = false
        terminalFailed = false
        _state.value = RelayState.Connecting
        startPump()
        // 官方终端会追加 mid 参数（PROTOCOL.md 3 节）；主机在线时中继强制校验，缺失直接 AUTH_FAILED
        val wsUrl = relayWsUrlOverride ?: device.relayWsUrl
        val url = wsUrl +
            (device.deviceMid?.takeIf { it.isNotBlank() }?.let { "?mid=$it" } ?: "")
        // 中继校验 Origin 与线路一致性：从 ws 地址推导同源 https 地址（wss://host/ws → https://host）
        val origin = wsUrl.removePrefix("wss://").removePrefix("ws://").substringBefore('/')
            .let { host -> if (wsUrl.startsWith("wss://")) "https://$host" else "http://$host" }
        val request = Request.Builder()
            .url(url)
            .header("Origin", origin)
            .build()
        socket = client.newWebSocket(request, listener)
    }

    fun close() {
        manuallyClosed = true
        stopHeartbeat()
        reconnectJob?.cancel(); reconnectJob = null
        socket?.close(1000, "client-close")
        socket = null
        _state.value = RelayState.Idle
        // 实例废弃（AppViewModel 总是先 close 再 new）：连同泵协程一起收掉，frameQueue 残帧作废
        io.cancel()
        pumpJob = null
    }

    fun send(obj: JsonObject): Boolean {
        val s = RelayProtocol.json.encodeToString(JsonObject.serializer(), obj)
        ZLog.i(TAG, "ws send ${s.take(200)}")
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
            ZLog.i(TAG, "ws open http=${response.code}")
            _state.value = RelayState.Authenticating
            send(RelayProtocol.authInit(device.deviceSid, appVersion))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            ZLog.i(TAG, "ws recv $text")
            val frame = runCatching { RelayProtocol.parse(text) }.getOrNull() ?: return
            when (RelayProtocol.typeOf(frame)) {
                "auth_challenge" -> {
                    val nonce = RelayProtocol.nonceOf(frame) ?: return
                    val proof = RelayProtocol.calculateProof(
                        device.passHash, nonce, RelayProtocol.ROLE_TERMINAL, device.deviceSid)
                    // P0-C：proof 与 sid 是握手凭据，绝不能进 release 日志（ZLog release 静默 + R8 剥离）
                    ZLog.i(TAG, "auth proof computed sid=${device.deviceSid} hashLen=${device.passHash.length}")
                    send(RelayProtocol.authResponse(device.deviceSid, proof))
                }
                "auth_ack", "pair_status_ack" -> {
                    when (val st = RelayProtocol.pairStatusOf(frame)) {
                        "waiting" -> { _state.value = RelayState.WaitingPeer; startHeartbeat() }
                        "matched" -> { reconnectAttempt = 0; _state.value = RelayState.Paired; startHeartbeat() }
                        // 协议变更兜底（M3）：关键握手帧出现未知结构，别再静默忽略
                        else -> {
                            ZLog.w(TAG, "unknown pair_status=$st")
                            if (frame["type"]?.toString()?.contains("auth_ack") == true) {
                                fail(FailureReason.PROTOCOL_MISMATCH,
                                    "握手应答结构未知（pair_status=${st ?: "缺失"}），官方协议可能已升级，请更新 App")
                            }
                        }
                    }
                }
                "data" -> {
                    val payload = frame["payload"]?.let {
                        runCatching { it.jsonObject }.getOrNull()
                    } ?: return
                    frameQueue.trySend(payload)
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
            ZLog.w(TAG, "ws failure", t)
            if (manuallyClosed) return
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            ZLog.i(TAG, "ws closed code=$code")
            if (!manuallyClosed) scheduleReconnect()
        }
    }

    companion object { private const val TAG = "RelayClient" }

    private fun fail(reason: FailureReason, message: String?) {
        stopHeartbeat()
        // 终态失败不参与自动重连：重连会与占位的另一 terminal（官方 Web/桌面端面板内嵌页）
        // 形成 3s 级互踢死循环（2026-09-30 真机实测）。用户按横幅指引「断开→重新连接」恢复。
        if (reason == FailureReason.KICKED || reason == FailureReason.AUTH_FAILED ||
            reason == FailureReason.PROTOCOL_MISMATCH) {
            terminalFailed = true
            reconnectAttempt = 0
        }
        _state.value = RelayState.Failed(reason, message)
    }

    private fun startHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = io.launch {
            while (isActive && !manuallyClosed) {
                runCatching { send(RelayProtocol.heartbeat(device.deviceSid)) }
                delay(30_000)
            }
        }
    }

    private fun stopHeartbeat() { heartbeatJob?.cancel(); heartbeatJob = null }

    private fun scheduleReconnect() {
        stopHeartbeat()
        if (manuallyClosed || terminalFailed) return
        // onClosed/onFailure 可能双触发：已有一个重连调度在等网络时不再叠加
        //（否则第二次 cancel 掉第一次的挂起点，造成竞态）
        if (reconnectJob?.isActive == true) {
            ZLog.i(TAG, "reconnect: 已有调度在等待（attempt=$reconnectAttempt），跳过重复调度")
            return
        }
        val delayMs = 3000L * (1L shl minOf(reconnectAttempt, 4))  // 3s,6s,12s,24s,48s 封顶
        reconnectAttempt++
        reconnectJob = io.launch {
            // 挂起点：注入 NetworkGate 后，无网时等网络恢复即刻重连，而非空转到下一轮退避
            ZLog.i(TAG, "reconnect: 调度 attempt=$reconnectAttempt 退避 ${delayMs}ms")
            runCatching { networkWait(delayMs) }
                .onFailure { ZLog.w(TAG, "reconnect: networkWait 异常", it) }
            if (!manuallyClosed && !terminalFailed) {
                ZLog.i(TAG, "reconnect: 放行，执行 connect()")
                connect()
            } else {
                ZLog.i(TAG, "reconnect: 跳过重连（manuallyClosed=$manuallyClosed terminalFailed=$terminalFailed）")
            }
        }
    }
}
