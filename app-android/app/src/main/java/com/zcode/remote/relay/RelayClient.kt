package com.zcode.remote.relay

import com.zcode.remote.BuildConfig
import com.zcode.remote.storage.ProtocolStack
import com.zcode.remote.util.HttpClientProvider
import com.zcode.remote.util.LogRedactor
import com.zcode.remote.util.ZLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
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

    data object WaitingPeer : RelayState // pair_status=waiting：PC 端未开启远程控制

    data object Paired : RelayState // pair_status=matched

    data class Failed(
        val reason: FailureReason,
        val message: String?,
    ) : RelayState
}

enum class FailureReason { KICKED, AUTH_FAILED, DEVICE_OFFLINE, NETWORK, INTERNAL, PROTOCOL_MISMATCH, INVALID_ENDPOINT }

/**
 * 重连退避（纯函数，便于 JVM 单测）：3s 起指数翻倍，48s 封顶。
 *
 * ⚠️ 退避**只应在「网络可用但对端拒绝/超时」时累积**。网络整体不可用时应由
 * [NetworkGate] 挂起等待（不烧退避）、恢复后由 [RelayClient.onNetworkAvailable] 重置归零
 * ——否则断网期会把退避烧到 48s 封顶，恢复后还要白等一轮（2026-10-07 真机实测 ~47s）。
 */
internal fun reconnectBackoffMs(attempt: Int): Long = 3000L * (1L shl minOf(attempt, 4))

/**
 * 退避加抖动（纯函数）：把基准值按 [factor] 缩放到 [0.5, 1.0] 区间。
 *
 * 动机：多台设备/多个实例在同一次断网后按**同一个确定值**重连（3s/6s/…/48s），
 * 会形成同时到点的「重连雷群」压垮中继。抖动只缩短不延长（上限仍是 [reconnectBackoffMs]），
 * 因此不违反「封顶 48s」的既有承诺，仅把同时到点摊开。
 */
internal fun jitteredBackoffMs(
    attempt: Int,
    factor: Double,
): Long {
    val base = reconnectBackoffMs(attempt)
    val clamped = factor.coerceIn(0.0, 1.0)
    return (base * (0.5 + 0.5 * clamped)).toLong()
}

class RelayClient(
    private val device: com.zcode.remote.storage.PairedDevice,
    private val appVersion: String = "0.1.0",
    /** 线路覆盖（设置里强制主线/备线/自建中继）；null=按配对二维码推断。 */
    private val relayWsUrlOverride: String? = null,
    /**
     * V2 直连凭据源（PROTOCOL.md §11）：返回登录 JWT，null = 未登录。
     * V2 配对（无 passHash）必须注入，否则连接将失败并给出登录指引。
     */
    private val jwtProvider: (suspend () -> String?)? = null,
) {
    /** V2 配对：凭据走「JWT → relay token → ws2?token=」链路（无 challenge/proof）。 */
    private val v2Mode: Boolean = device.protocolStack == ProtocolStack.V2
    private val client =
        HttpClientProvider
            .newBuilder()
            .pingInterval(20, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .build()

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var pumpJob: Job? = null

    /** T11 心跳单向失活：首次「发出但尚未被 ack 覆盖」的心跳时刻；0 = 无待确认心跳。 */
    @Volatile
    private var heartbeatSentAtMs: Long = 0L

    /** 最近一次成功发出心跳的时刻（诊断用，不参与失活判定）。 */
    @Volatile
    private var lastHeartbeatSentAtMs: Long = 0L

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

    /**
     * 网络恢复立即重连（NetworkGate 常驻监控转发）。
     *
     * 2026-10-07 真机实测缺陷：断网期开烧的退避可达 48s 封顶，恢复后仍要把这轮退避烧完才重连
     * （实测恢复耗时 ~47s，而基线为 `<9s`）。这里在**网络恢复的瞬间**重置退避、取消已排队的
     * 退避等待并立刻重连——不再依赖「退避正好到期」。
     */
    fun onNetworkAvailable() {
        if (manuallyClosed || terminalFailed) return
        // 只在「WS 确实活着」的状态下不打断健康连接：Paired / WaitingPeer 都有 live socket。
        // ⚠️ 不能把 Connecting 当作健康：socket 失败时 onFailure 只调度重连、**不改 _state**，
        // 故 Connecting 会残留下来。若据此提前 return，「网络恢复即刻重连」就永远不生效
        // ——真机实测（2026-10-07）：断网 150s 时恢复仍要等满 48s 退避，正是此处被挡。
        when (_state.value) {
            RelayState.Paired, RelayState.WaitingPeer -> return
            else -> Unit
        }
        ZLog.i(TAG, "net-watch: 网络恢复，重置退避并立即重连")
        reconnectAttempt = 0
        // 掐断可能正在 sleep 的退避等待（最多 48s）：否则要白等到退避到期才重连。
        // 被取消的调度由 scheduleReconnect 内的 `if (!isActive) return@launch` 保证不再 connect。
        reconnectJob?.cancel()
        reconnectJob = null
        connect()
    }

    private val _state = MutableStateFlow<RelayState>(RelayState.Idle)
    val state: StateFlow<RelayState> = _state

    private val _inbound =
        MutableSharedFlow<JsonObject>(
            replay = 0,
            extraBufferCapacity = ProtocolLimits.RPC_EVENT_BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.SUSPEND,
        )

    /** 内层 data.payload（zcode_type 路由后），UI/会话层订阅。 */
    val inbound: SharedFlow<JsonObject> = _inbound

    /**
     * 入站帧队列（B 组）：OkHttp 线程只入队，单泵协程顺序 emit —— 保证帧序，且突发时
     * 挂起而非丢弃（原 DROP_OLDEST 会在长流式输出时静默丢最旧帧，会话行悄悄错位）。
     *
     * §11.3.3：容量有界（[ProtocolLimits.INBOUND_QUEUE_CAPACITY]）。溢出时**不静默**——
     * 计数 + [onInboundOverflow] 上抛（调用方进入重同步；被丢弃的帧未 ack，服务端可重放）。
     */
    private val frameQueue = Channel<JsonObject>(ProtocolLimits.INBOUND_QUEUE_CAPACITY)

    /** 入站队列溢出回调（掉帧 = 失同步信号，必须可见）。 */
    var onInboundOverflow: (() -> Unit)? = null

    /** 入站队列记账（§11.3.3：容量/峰值/准入/丢弃；纯逻辑类，压测与单测直接驱动）。 */
    internal val inboundAdmission = InboundAdmission()

    /** 累计因队列溢出丢弃的帧数（观测用，只记计数）。 */
    @Volatile
    var inboundDropped: Int = 0
        private set

    init {
        startPump()
    }

    private fun startPump() {
        if (pumpJob?.isActive == true) return
        pumpJob =
            io.launch {
                for (payload in frameQueue) {
                    // 消费一帧再投递：入站深度记账与 Channel 容量同侧（见 InboundAdmission）
                    inboundAdmission.onConsumed()
                    _inbound.emit(payload)
                }
            }
    }

    private var socket: WebSocket? = null

    @Volatile private var manuallyClosed = false

    /** 终态失败（互踢/配对失效/协议不匹配）：不参与自动重连，等用户手动恢复。 */
    @Volatile private var terminalFailed = false

    @Volatile private var reconnectAttempt = 0

    /**
     * 连接代次（2026-10-05 竞态修复）：旧 socket 的 onFailure/onClosed 可能迟到到新连接
     * 建立之后才触发（真机实证 OkHttp 失败回调可延迟数秒冒出），会穿过防重入守卫错误调度
     * 重连、断掉健康连接。每次 connect() 递增代次，所有回调与重连调度携带并校验。
     */
    @Volatile private var generation = 0

    fun connect() {
        manuallyClosed = false
        terminalFailed = false
        _state.value = RelayState.Connecting
        startPump()
        // 显式 connect 即作废一切挂起的重连调度与旧 socket（其迟到回调由代次守卫拦截）
        reconnectJob?.cancel()
        reconnectJob = null
        generation += 1
        val myGen = generation
        runCatching { socket?.cancel() }
        io.launch {
            // V2：先换 relay token（suspend HTTP），再开 WS；期间连接可能被显式作废
            val v2Token = if (v2Mode) acquireV2Token(myGen) ?: return@launch else null
            openSocket(myGen, v2Token)
        }
    }

    /**
     * V2 凭据获取：JWT → relay token。取不到（未登录/网络失败/连接已作废）返回 null
     * 并在仍为本代次时置失败态。
     *
     * 失败分两层（T10）：
     * - **401/403 / 业务码拒绝** → [FailureReason.AUTH_FAILED]（终态、不自动重连，指引重新登录）。
     *   此前一律报 NETWORK，界面会无限重连转圈而用户不知道要重新登录。
     * - **其余（DNS/超时/连接重置）** → [FailureReason.NETWORK]（可重连）。
     */
    private suspend fun acquireV2Token(myGen: Int): String? {
        val jwt =
            jwtProvider?.invoke().also {
                if (it.isNullOrBlank()) ZLog.w(TAG, "v2: 无登录 JWT — 无法换取 relay token")
            }
        if (jwt.isNullOrBlank()) {
            if (myGen == generation && !manuallyClosed) {
                fail(
                    FailureReason.AUTH_FAILED,
                    "尚未登录 ZCode 账号：请在「设置 → ZCode 账号」完成登录后重试",
                )
            }
            return null
        }
        val tok = V2AccountApi.fetchRelayTokenSingleFlight(jwt, "terminal")
        val token =
            tok.getOrNull().also {
                if (it == null) ZLog.w(TAG, "v2: relay token 换取失败 ${tok.exceptionOrNull()?.message}")
            }
        if (token == null) {
            if (myGen == generation && !manuallyClosed) {
                reportTokenFailure(tok.exceptionOrNull())
            }
            return null
        }
        if (myGen != generation || manuallyClosed) return null
        return token
    }

    /** 取 token 失败时按「鉴权 vs 网络」分类上报（抽成独立函数以压住 detekt 圈复杂度）。 */
    private fun reportTokenFailure(err: Throwable?) {
        if (isAuthFailure(err)) {
            fail(
                FailureReason.AUTH_FAILED,
                "登录态已失效：请在「设置 → ZCode 账号」重新登录后重试",
            )
        } else {
            fail(FailureReason.NETWORK, "获取中继令牌失败：${err?.message ?: "网络异常"}")
        }
    }

    /**
     * 取 token 失败是否属于「鉴权问题」（重试无意义）而非「网络问题」（值得重试）。
     * 纯函数，便于 JVM 单测钉住分类口径。
     */
    internal fun isAuthFailure(err: Throwable?): Boolean =
        when (err) {
            null -> false
            is V2AccountApi.TokenAuthException -> true
            // 业务码拒绝（code != 0）由 check() 抛 IllegalStateException，文案含 "api code="；
            // 服务端用它表示「该 JWT 无权换 token」，与网络无关。
            is IllegalStateException -> err.message?.contains("api code=") == true
            else -> false
        }

    /**
     * 建立 WS（V1/V2 汇合点）。[v2Token] 非空 = V2 token 直连（ws2 端点，auth_init 无 role）。
     */
    private fun openSocket(
        myGen: Int,
        v2Token: String?,
    ) {
        // 官方终端会追加 mid 参数（PROTOCOL.md 3 节）；主机在线时中继强制校验，缺失直接 AUTH_FAILED
        val rawWsUrl =
            if (v2Token != null) {
                // V2：端点固定为 origin 下的 /api/v1/remote/ws2（桌面端代码实证；V1 的 /ws 不认 token）
                RelayProtocol.v2RelayWsUrl(relayWsUrlOverride ?: device.remoteUrl)
            } else {
                relayWsUrlOverride ?: device.relayWsUrl
            }
        // P0 传输止血（任务书 §11.3.1）：Release 只接受绝对 wss://；ws:// 仅 debug 放行。
        // 校验失败一律拒绝连接，绝不降级重试（旧实现 relayOverride 用 startsWith("ws") 放过了明文）。
        val wsUrl =
            when (val v = RelayEndpointValidator.validate(rawWsUrl, allowInsecure = BuildConfig.DEBUG)) {
                is RelayEndpointValidator.Result.Ok -> v.url
                is RelayEndpointValidator.Result.Rejected -> {
                    // 只记事件码与原因枚举，不记原始 URL（避免二次泄漏）
                    ZLog.e(TAG, "relay endpoint rejected reason=${v.reason}")
                    fail(FailureReason.INVALID_ENDPOINT, v.message)
                    return
                }
            }
        val url =
            wsUrl +
                (if (v2Token != null) "?token=$v2Token" else "") +
                (device.deviceMid?.takeIf { it.isNotBlank() }?.let { "${if (v2Token != null) "&" else "?"}mid=$it" } ?: "")
        // 中继校验 Origin 与线路一致性：从 ws 地址推导同源 https 地址（wss://host/ws → https://host）
        val secure = wsUrl.startsWith("wss://", ignoreCase = true)
        val host = wsUrl.substringAfter("//").substringBefore('/')
        val origin = if (secure) "https://$host" else "http://$host"
        val request =
            Request
                .Builder()
                .url(url)
                .header("Origin", origin)
                .build()
        socket = client.newWebSocket(request, makeListener(myGen))
    }

    fun close() {
        manuallyClosed = true
        stopHeartbeat()
        reconnectJob?.cancel()
        reconnectJob = null
        socket?.close(1000, "client-close")
        socket = null
        _state.value = RelayState.Idle
        // 实例废弃（AppViewModel 总是先 close 再 new）：连同泵协程一起收掉，frameQueue 残帧作废
        io.cancel()
        pumpJob = null
    }

    fun send(obj: JsonObject): Boolean {
        val s = RelayProtocol.json.encodeToString(JsonObject.serializer(), obj)
        // P0 日志止血：只记字节数，不记帧内容（含会话正文/凭据）
        ZLog.i(TAG, "ws send ${LogRedactor.payloadLabel(s)}")
        return socket?.send(s) ?: false
    }

    /** 业务负载必须包 data 信封并带 client_ts（官方 prepare() 实证）。 */
    fun sendPayload(payload: JsonObject): Boolean = send(wrap(payload))

    /** 包裹 data 信封（与 [sendPayload] 同一构造，供出站预算判定的字节口径复用）。 */
    private fun wrap(payload: JsonObject): JsonObject =
        kotlinx.serialization.json.buildJsonObject {
            put("type", "data")
            put("payload", payload)
            put("client_ts", System.currentTimeMillis())
        }

    /**
     * 出站信封 UTF-8 字节数（官方 `envelopeTooLarge` 判定口径：`{type,payload,client_ts}` 整体）。
     * 用于 [RpcChannel] 在发送前拒绝超 1 MiB 的帧（物理分片未实现，见任务书 §11.5.2）。
     */
    fun envelopeBytes(payload: JsonObject): Int =
        RelayProtocol.json
            .encodeToString(JsonObject.serializer(), wrap(payload))
            .toByteArray(Charsets.UTF_8)
            .size

    /** 每次连接独立 listener：回调首行校验代次，旧 socket 的迟到回调一律丢弃。 */
    private fun makeListener(gen: Int): WebSocketListener =
        object : WebSocketListener() {
            private fun stale(): Boolean = gen != generation

            override fun onOpen(
                webSocket: WebSocket,
                response: Response,
            ) {
                if (stale()) return
                ZLog.i(TAG, "ws open http=${response.code} v2=$v2Mode")
                _state.value = RelayState.Authenticating
                val init =
                    if (v2Mode) {
                        RelayProtocol.authInitV2(device.deviceSid, appVersion)
                    } else {
                        RelayProtocol.authInit(device.deviceSid, appVersion)
                    }
                send(init)
            }

            override fun onMessage(
                webSocket: WebSocket,
                text: String,
            ) {
                if (stale()) return
                // P0 日志止血：完整入站帧含全部会话正文；只记字节数
                ZLog.i(TAG, "ws recv ${LogRedactor.payloadLabel(text)}")
                val frame = runCatching { RelayProtocol.parse(text) }.getOrNull() ?: return
                when (RelayProtocol.typeOf(frame)) {
                    "auth_challenge" -> {
                        if (v2Mode) {
                            // V2（ws2 + token）设计上无 challenge；收到即协议异常（端点/版本错乱）
                            ZLog.w(TAG, "v2: unexpected auth_challenge — protocol mismatch")
                            fail(
                                FailureReason.PROTOCOL_MISMATCH,
                                "V2 直连收到 challenge（协议异常）：请更新 App 或反馈",
                            )
                            return
                        }
                        val hash = device.passHash?.takeIf { it.isNotBlank() }
                        if (hash == null) {
                            ZLog.w(TAG, "auth_challenge but no passHash (V2 pairing) — login flow required")
                            fail(
                                FailureReason.AUTH_FAILED,
                                "新版协议配对缺少凭据：请在会话页重新进入配对，完成账号登录后重试",
                            )
                            return
                        }
                        val nonce = RelayProtocol.nonceOf(frame) ?: return
                        val proof =
                            RelayProtocol.calculateProof(
                                hash,
                                nonce,
                                RelayProtocol.ROLE_TERMINAL,
                                device.deviceSid,
                            )
                        // P0-C：proof 与 sid 是握手凭据，绝不能进日志（这里只记 SID 长度，连前缀都不留）
                        ZLog.i(TAG, "auth proof computed sid=${LogRedactor.maskId(device.deviceSid)} hashLen=${hash.length}")
                        send(RelayProtocol.authResponse(device.deviceSid, proof))
                    }
                    "auth_ack", "pair_status_ack" -> {
                        // T11：任何 ack 都证明链路双向可用，清空单向失活计时
                        markHeartbeatAcked()
                        when (val st = RelayProtocol.pairStatusOf(frame)) {
                            "waiting" -> {
                                _state.value = RelayState.WaitingPeer
                                startHeartbeat()
                            }
                            "matched" -> {
                                reconnectAttempt = 0
                                _state.value = RelayState.Paired
                                startHeartbeat()
                            }
                            // 协议变更兜底（M3）：关键握手帧出现未知结构，别再静默忽略
                            else -> {
                                ZLog.w(TAG, "unknown pair_status=$st")
                                if (frame["type"]?.toString()?.contains("auth_ack") == true) {
                                    fail(
                                        FailureReason.PROTOCOL_MISMATCH,
                                        "握手应答结构未知（pair_status=${st ?: "缺失"}），官方协议可能已升级，请更新 App",
                                    )
                                }
                            }
                        }
                    }
                    "data" -> {
                        val payload =
                            frame["payload"]?.let {
                                runCatching { it.jsonObject }.getOrNull()
                            } ?: return
                        // §11.3.3：有界队列 + 溢出可见（掉帧不再静默；未 ack 的帧服务端可重放）
                        if (frameQueue.trySend(payload).isSuccess) {
                            inboundAdmission.onAdmitted()
                        } else {
                            inboundAdmission.onDropped()
                            inboundDropped = inboundAdmission.dropped
                            ZLog.w(
                                TAG,
                                "inbound queue overflow dropped=$inboundDropped " +
                                    "cap=${ProtocolLimits.INBOUND_QUEUE_CAPACITY} peak=${inboundAdmission.peakDepth}",
                            )
                            onInboundOverflow?.invoke()
                        }
                    }
                    "error" -> {
                        val err = RelayProtocol.RelayError.from(RelayProtocol.errorCodeOf(frame))
                        when (err) {
                            RelayProtocol.RelayError.KICKED ->
                                fail(FailureReason.KICKED, "会话已在别处打开（与官方 Web 版互踢）")
                            RelayProtocol.RelayError.AUTH_FAILED,
                            RelayProtocol.RelayError.WRONG_PARAM,
                            ->
                                fail(FailureReason.AUTH_FAILED, "配对失效，请重新扫码")
                            RelayProtocol.RelayError.DEVICE_OFFLINE -> {
                                _state.value = RelayState.WaitingPeer
                                scheduleReconnect()
                            }
                            else -> fail(FailureReason.INTERNAL, "中继内部错误")
                        }
                    }
                }
            }

            override fun onFailure(
                webSocket: WebSocket,
                t: Throwable,
                response: Response?,
            ) {
                if (stale()) return
                // P0 日志止血：不传原始 Throwable（其 message 常含 URL/路径），只记异常类名
                ZLog.w(TAG, "ws failure ${LogRedactor.exceptionLabel(t)}")
                if (manuallyClosed) return
                scheduleReconnect()
            }

            override fun onClosed(
                webSocket: WebSocket,
                code: Int,
                reason: String,
            ) {
                if (stale()) return
                ZLog.i(TAG, "ws closed code=$code")
                if (!manuallyClosed) scheduleReconnect()
            }
        }

    companion object {
        private const val TAG = "RelayClient"

        /**
         * T11 心跳单向失活阈值：30s。
         *
         * 取 30s 而非 10s（一个心跳周期）：官方 ack 超时口径即为 30s（PROTOCOL.md §4.1），
         * 与之一致的目的是**不抢在服务端判定之前误判**——偶发一次丢包不该立刻断连。
         * 30s = 3 个心跳周期未回，属确定性失活。
         */
        internal const val HEARTBEAT_STALE_MS: Long = 30_000L
    }

    private fun fail(
        reason: FailureReason,
        message: String?,
    ) {
        stopHeartbeat()
        // 终态失败不参与自动重连：重连会与占位的另一 terminal（官方 Web/桌面端面板内嵌页）
        // 形成 3s 级互踢死循环（2026-09-30 真机实测）。用户按横幅指引「断开→重新连接」恢复。
        if (reason == FailureReason.KICKED ||
            reason == FailureReason.AUTH_FAILED ||
            reason == FailureReason.PROTOCOL_MISMATCH ||
            reason == FailureReason.INVALID_ENDPOINT
        ) {
            terminalFailed = true
            reconnectAttempt = 0
        }
        _state.value = RelayState.Failed(reason, message)
    }

    private fun startHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        // T11：新一轮心跳从零计时——上一条连接的「未 ack」残留不得继承（否则新连接会秒判失活）
        heartbeatSentAtMs = 0L
        heartbeatJob =
            io.launch {
                while (isActive && !manuallyClosed) {
                    // V2 帧为无 device_sid 的 pair_status_query（strictObject 拒多余字段）
                    val sentAtMs = System.currentTimeMillis()
                    val sent =
                        runCatching {
                            send(if (v2Mode) RelayProtocol.heartbeatV2() else RelayProtocol.heartbeat(device.deviceSid))
                        }.isSuccess
                    if (sent) markHeartbeatSent(sentAtMs)
                    // T11：发出后一直收不到 ack = 单向失活 → 主动判网络失败并重连
                    if (isHeartbeatStale(heartbeatSentAtMs, System.currentTimeMillis())) {
                        ZLog.w(
                            TAG,
                            "heartbeat 单向失活：已发心跳但 ${HEARTBEAT_STALE_MS}ms 内无任何 ack — 判定连接失效并重连",
                        )
                        fail(FailureReason.NETWORK, "连接已失去响应，正在重连…")
                        return@launch
                    }
                    // 官方两侧一致为 10s（PROTOCOL.md §4.1）；ack 超时 30s 由服务端判定，
                    // 旧实现固定 30s 虽在窗口内可用，但会拉长失活检测，对齐官方取值
                    delay(10_000)
                }
            }
    }

    /**
     * T11：心跳「单向失活」检测。
     *
     * 场景：TCP/WS 仍连着（写 `send` 不报错），但**收不到任何 ack**——典型是中间盒静默丢包、
     * 或对端进程已死而 socket 未及时 FIN。此时 `_state` 会一直停在 `Paired`，用户看到
     * 「已连接」却什么都收不到，且不会自动重连（业务帧无超时兜底）。
     *
     * 判据（纯函数 [isHeartbeatStale]）：最近一次心跳发出后超过 [HEARTBEAT_STALE_MS]
     * **仍未收到任何 pair_status_ack**，且期间确实发出过心跳（[heartbeatSentAtMs] != 0）。
     * 命中即 `fail(NETWORK)`——`NETWORK` 不是终态，会自动进入指数退避重连，符合「单向失活值得重试」。
     *
     * 注意只在「发了但没回」时判：单纯没发（如刚连上）不判。
     */
    private fun markHeartbeatSent(atMs: Long) {
        lastHeartbeatSentAtMs = atMs
        if (heartbeatSentAtMs == 0L) heartbeatSentAtMs = atMs
    }

    /** 收到任意 ack：单向失活计时清零（连续发心跳期间只要有一帧回来就不算失活）。 */
    private fun markHeartbeatAcked() {
        heartbeatSentAtMs = 0L
    }

    /**
     * 心跳是否已单向失活（纯函数，JVM 可测）。
     *
     * @param sentAtMs 本轮「尚未被 ack 覆盖」的首次心跳发出时刻；0 = 无待确认心跳
     * @param nowMs    当前时刻
     * @param staleMs  失活阈值（默认 [HEARTBEAT_STALE_MS]）
     */
    internal fun isHeartbeatStale(
        sentAtMs: Long,
        nowMs: Long,
        staleMs: Long = HEARTBEAT_STALE_MS,
    ): Boolean = sentAtMs > 0L && nowMs - sentAtMs >= staleMs

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun scheduleReconnect() {
        stopHeartbeat()
        if (manuallyClosed || terminalFailed) return
        // onClosed/onFailure 可能双触发：已有一个重连调度在等网络时不再叠加
        // （否则第二次 cancel 掉第一次的挂起点，造成竞态）
        if (reconnectJob?.isActive == true) {
            ZLog.i(TAG, "reconnect: 已有调度在等待（attempt=$reconnectAttempt），跳过重复调度")
            return
        }
        // 3s,6s,12s,24s,48s 封顶 + 抖动（只缩短不延长）：避免多端同时到点的重连雷群
        val delayMs = jitteredBackoffMs(reconnectAttempt, Math.random())
        reconnectAttempt++
        reconnectJob =
            io.launch {
                val myGen = generation
                // 挂起点：注入 NetworkGate 后，无网时等网络恢复即刻重连，而非空转到下一轮退避
                ZLog.i(TAG, "reconnect: 调度 attempt=$reconnectAttempt 退避 ${delayMs}ms")
                runCatching { networkWait(delayMs) }
                    .onFailure { ZLog.w(TAG, "reconnect: networkWait 异常", it) }
                // ⚠️ 必须显式判 isActive：上面的 runCatching 会把取消异常一并吞掉，此后协程虽已取消
                // 仍会继续执行到 connect() —— 而 onNetworkAvailable 已在取消后自行重连，重复 connect
                // 会造成双连接/代次错乱（真机修复 2026-10-07）。
                if (!isActive) {
                    ZLog.i(TAG, "reconnect: 本次调度已被取消（网络恢复已直接重连），不再 connect")
                    return@launch
                }
                if (myGen != generation) {
                    ZLog.i(TAG, "reconnect: 代次已变（期间发生显式 connect），作废本次调度")
                    return@launch
                }
                if (!manuallyClosed && !terminalFailed) {
                    ZLog.i(TAG, "reconnect: 放行，执行 connect()")
                    connect()
                } else {
                    ZLog.i(TAG, "reconnect: 跳过重连（manuallyClosed=$manuallyClosed terminalFailed=$terminalFailed）")
                }
            }
    }
}
