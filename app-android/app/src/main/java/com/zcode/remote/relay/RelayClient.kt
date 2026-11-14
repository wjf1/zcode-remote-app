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

enum class FailureReason { KICKED, AUTH_FAILED, DEVICE_OFFLINE, NETWORK, INTERNAL, PROTOCOL_MISMATCH, INVALID_ENDPOINT, DESKTOP_DISCONNECTED }

/**
 * 心跳间隔（纯函数，JVM 单测直驱；官方 `heartbeatTiming.ts` `getWebRemoteControlHeartbeatDelayMs`
 * 等价实现）：基准 5s、jitter 1s，在 `[base-jitter, base+jitter]` 均匀抽样、**每轮重抽**
 * （官方注释：jitter 必须小于基准间隔，避免把合法心跳变成 0ms 风暴）。
 */
internal fun heartbeatIntervalMs(
    random: Double = Math.random(),
    baseMs: Long = RelayClient.HEARTBEAT_INTERVAL_MS,
    jitterMs: Long = RelayClient.HEARTBEAT_JITTER_MS,
): Long {
    val base = if (baseMs > 0) baseMs else RelayClient.HEARTBEAT_INTERVAL_MS
    val jitter =
        when {
            jitterMs <= 0 -> 0L
            jitterMs >= base -> (base - 1).coerceAtLeast(0L)
            else -> jitterMs
        }
    val lo = maxOf(1L, base - jitter)
    val hi = base + jitter
    val r = if (random.isFinite()) random.coerceIn(0.0, 0.999999) else 0.0
    return lo + (r * (hi - lo + 1)).toLong()
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

    /**
     * 重连/退避/KICKED 策略（P2①②，2026-11-14 对齐官方 `WebRemoteControlV2RetryPolicy`，
     * 语义详见 [V2RetryPolicy]）。
     */
    private val retryPolicy = V2RetryPolicy()

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
        // 网络丢失不是对端拒绝：清退避（恢复后尽快重连），重连调度走 nextDelay（首次打散语义保留）
        retryPolicy.reset()
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
        retryPolicy.reset()
        // 掐断可能正在 sleep 的退避等待（最多 10s）：否则要白等到退避到期才重连。
        // 被取消的调度由 scheduleReconnect 内的 `if (!isActive) return@launch` 保证不再 connect。
        reconnectJob?.cancel()
        reconnectJob = null
        connect(resetRetryPolicy = false)
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

    /**
     * DEVICE_OFFLINE 有界宽限（官方 2026-10-27「旧 SID 自动接续」配套，`recoverConnection(15_000)`）：
     * 首次进入 WaitingPeer 起算 15s，窗口内保持既有退避重试（桌面可能只是重启间隙）；
     * 超时升级为 [FailureReason.DESKTOP_DISCONNECTED]（可接续终态）交上层按 MID 自动接续。
     * 重连再次遇 DEVICE_OFFLINE **不重置**起点——否则「退避重连→归零」会让总窗口永不到期。
     */
    @Volatile private var offlineGraceStartMs = 0L
    private var offlineGraceJob: Job? = null

    @Volatile private var manuallyClosed = false

    /** 终态失败（配对失效/协议不匹配/端点不安全/桌面端离线/60s 内二次互踢）：不参与自动重连，等用户手动恢复。 */
    @Volatile private var terminalFailed = false

    /**
     * 连接代次（2026-10-05 竞态修复）：旧 socket 的 onFailure/onClosed 可能迟到到新连接
     * 建立之后才触发（真机实证 OkHttp 失败回调可延迟数秒冒出），会穿过防重入守卫错误调度
     * 重连、断掉健康连接。每次 connect() 递增代次，所有回调与重连调度携带并校验。
     */
    @Volatile private var generation = 0

    /**
     * 显式连接入口。用户动作（扫码/手动重连/设置页）与自动恢复（网络恢复/重连调度）共用；
     * [resetRetryPolicy] 仅用户/网络恢复路径为 true（清退避、不打散），重连调度内部路径
     * 必须传 false——否则会把「连接建立后的首次打散」标志清掉，打散永不生效。
     */
    fun connect(resetRetryPolicy: Boolean = true) {
        manuallyClosed = false
        terminalFailed = false
        if (resetRetryPolicy) retryPolicy.reset()
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
                                // 官方语义（retry.ts.acknowledged）：连接建立清退避 + 标记下一次
                                // 重连打散；**不动 KICKED 窗口**（lastKickAtMs 只在 kickedDelay 推进）
                                retryPolicy.acknowledged()
                                cancelOfflineGrace()
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
                            RelayProtocol.RelayError.KICKED -> handleKicked()
                            RelayProtocol.RelayError.AUTH_FAILED,
                            RelayProtocol.RelayError.WRONG_PARAM,
                            ->
                                fail(FailureReason.AUTH_FAILED, "配对失效，请重新扫码")
                            RelayProtocol.RelayError.DEVICE_OFFLINE -> {
                                enterOfflineGrace()
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

        /** 心跳基准间隔 5s（官方 `WEB_REMOTE_CONTROL_V2_HEARTBEAT_INTERVAL_MS`，2026-11-14 源码实证）。 */
        internal const val HEARTBEAT_INTERVAL_MS: Long = 5_000L

        /** 心跳 jitter 1s（官方 `WEB_REMOTE_CONTROL_V2_HEARTBEAT_JITTER_MS`；实际抖动按 [heartbeatIntervalMs] 每轮重抽）。 */
        internal const val HEARTBEAT_JITTER_MS: Long = 1_000L

        /**
         * T11 心跳单向失活 / ACK watchdog 阈值：**15s**（官方
         * `WEB_REMOTE_CONTROL_V2_HEARTBEAT_ACK_TIMEOUT_MS = 15_000`，`heartbeatTiming.ts`
         * 源码实证）。勘误：旧值 30s 与「官方 ack 超时 30s」的旧注释均系 v3.11 时代 bundle
         * 逆向误记——官方源码明确为 15s（3 个心跳周期无任何 ack，属确定性失活）。
         * V1 链路官方已删、无独立现值，与 V2 同口径（同一中继、行为一致性优先）。
         */
        internal const val HEARTBEAT_STALE_MS: Long = 15_000L

        /**
         * DEVICE_OFFLINE 宽限窗口：15s，对齐官方 `desktopOfflineGraceMs ?? 15_000`
         * （官方仓库 webRemoteControlTransport.ts）。期间桌面重启回来则平滑恢复；
         * 超时升级 [FailureReason.DESKTOP_DISCONNECTED]，由上层按 MID 自动接续新 SID。
         */
        internal const val DEVICE_OFFLINE_GRACE_MS: Long = 15_000L

        /**
         * 终态失败集合（不参与自动重连）：互踢 / 配对失效 / 协议不匹配 / 端点不安全 / 桌面端离线。
         * 与 `ui/screens/HomeConnectionInfo.isTerminalFailure` 必须保持一致（后者决定「前往设置」引导）。
         */
        internal val TERMINAL_FAILURES =
            setOf(
                FailureReason.KICKED,
                FailureReason.AUTH_FAILED,
                FailureReason.PROTOCOL_MISMATCH,
                FailureReason.INVALID_ENDPOINT,
                FailureReason.DESKTOP_DISCONNECTED,
            )
    }

    private fun fail(
        reason: FailureReason,
        message: String?,
    ) {
        stopHeartbeat()
        cancelOfflineGrace()
        // 终态失败不参与自动重连：重连会与占位的另一 terminal（官方 Web/桌面端面板内嵌页）
        // 形成 3s 级互踢死循环（2026-09-30 真机实测）。用户按横幅指引「断开→重新连接」恢复。
        // DESKTOP_DISCONNECTED 同为终态：宽限期内调度的退避重连到点会查 terminalFailed，
        // 不列入会穿透终态、形成 15s 周期的「超时→重连→再超时」循环；自动恢复交上层按 MID 接续。
        if (reason in TERMINAL_FAILURES) {
            terminalFailed = true
            retryPolicy.reset()
        }
        _state.value = RelayState.Failed(reason, message)
    }

    /**
     * DEVICE_OFFLINE → 有界宽限（详见 [offlineGraceStartMs] 注释）。
     * 宽限内重连成功配对即取消归零；超时且仍停在 WaitingPeer 才升级终态。
     *
     * ⚠️ 这里**不能**调 `startHeartbeat()`：DEVICE_OFFLINE 意味着服务端已判定桌面端离线，
     * 随后的心跳收不到任何 ack，会被 T11 单向失活逻辑在 30s 判成 `NETWORK`（非终态、无限重连），
     * 抢在 15s 宽限之前把状态改掉——接续路径就永远等不到 DESKTOP_DISCONNECTED。
     */
    private fun enterOfflineGrace() {
        // 已是终态就不再受理：宽限超时后 terminalFailed 置真，理论上不会再走到这里
        // （终态时 socket 已不再收帧），但显式挡一层，防止万一的状态回退把终态覆盖回 WaitingPeer。
        if (terminalFailed || manuallyClosed) return
        _state.value = RelayState.WaitingPeer
        if (offlineGraceStartMs == 0L) {
            offlineGraceStartMs = System.currentTimeMillis()
            offlineGraceJob =
                io.launch {
                    delay(DEVICE_OFFLINE_GRACE_MS)
                    if (_state.value is RelayState.WaitingPeer && !manuallyClosed) {
                        ZLog.w(TAG, "DEVICE_OFFLINE 宽限 ${DEVICE_OFFLINE_GRACE_MS / 1000}s 超时，升级为可接续终态")
                        // 先摘掉自己的引用再进 fail()：否则 fail() 内的 cancelOfflineGrace()
                        // 会 cancel 掉当前协程（虽然赋值是同步的、本次能跑完，但引用会残留成
                        // 「已取消的 job」，后续 cancel 语义与排查都易误读）。
                        offlineGraceJob = null
                        offlineGraceStartMs = 0L
                        fail(FailureReason.DESKTOP_DISCONNECTED, "桌面端长时间离线：等待自动接续或手动重连")
                    }
                }
        }
    }

    private fun cancelOfflineGrace() {
        offlineGraceJob?.cancel()
        offlineGraceJob = null
        offlineGraceStartMs = 0L
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
                    // P2③ 心跳口径对齐官方 heartbeatTiming.ts：基准 5s、jitter 1s、每轮重抽
                    // （官方手机 Web 端同款；V1 链路官方已删，统一采用 V2 口径）。旧值 10s 固定
                    // 间隔 + 30s 失活系 v3.11 时代 bundle 逆向结论，以官方源码为准（15s watchdog）。
                    delay(heartbeatIntervalMs())
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

    /**
     * KICKED 处理（P2②，对齐官方 `V2RetryPolicy.kickedDelay`）：**首次 KICKED 不再直接终态**
     * ——官方已知限制：KICKED 无法区分「同角色替换」与「relay owner 丢失」，后者可自愈
     * （2~5s 随机重连一次）。60s 内第二次 = 真互踢（另一 terminal 确实在用），才进
     * [FailureReason.KICKED] 终态（原「KICKED 即终态」防的是 3s 级互踢死循环，官方 60s
     * 窗口是同一目标的更精细解法：自愈机会 + 死循环护栏并存）。
     */
    private fun handleKicked() {
        val delayMs = retryPolicy.kickedDelay()
        if (delayMs == null) {
            fail(
                FailureReason.KICKED,
                "60 秒内两次被接管：会话确实已在别处打开（与官方 Web 版互踢），请稍后重新连接",
            )
            return
        }
        ZLog.w(TAG, "kicked: 首次接管（可能为 relay 状态误报），${delayMs}ms 后重连自愈；60s 内再次接管才判终态")
        stopHeartbeat()
        cancelOfflineGrace()
        // 作废既有重连调度与 socket：KICKED 的 2~5s 自愈延迟优先于任何普通退避
        // （迟到 onClosed 的 scheduleReconnect 由「已有调度」守卫挡住）
        reconnectJob?.cancel()
        reconnectJob = null
        runCatching { socket?.close(1000, "kicked") }
        socket = null
        _state.value = RelayState.Connecting
        scheduleReconnect(delayMs)
    }

    private fun scheduleReconnect(fixedDelayMs: Long? = null) {
        stopHeartbeat()
        if (manuallyClosed || terminalFailed) return
        // onClosed/onFailure 可能双触发：已有一个重连调度在等网络时不再叠加
        // （否则第二次 cancel 掉第一次的挂起点，造成竞态）
        if (reconnectJob?.isActive == true) {
            ZLog.i(TAG, "reconnect: 已有调度在等待，跳过重复调度")
            return
        }
        // P2①② 打散/退避口径对齐官方（V2RetryPolicy）：连接建立后的首次重连打散 0~1.5s，
        // 之后 500ms×2ⁿ ±20% 封顶 10s；KICKED 自愈路径传固定 2~5s
        val delayMs = fixedDelayMs ?: retryPolicy.nextDelay()
        reconnectJob =
            io.launch {
                val myGen = generation
                // 挂起点：注入 NetworkGate 后，无网时等网络恢复即刻重连，而非空转到下一轮退避
                ZLog.i(TAG, "reconnect: 调度退避 ${delayMs}ms")
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
                    // 重连调度内部路径：不 reset 策略（否则清掉「首次打散」标志，打散永不生效）
                    connect(resetRetryPolicy = false)
                } else {
                    ZLog.i(TAG, "reconnect: 跳过重连（manuallyClosed=$manuallyClosed terminalFailed=$terminalFailed）")
                }
            }
    }
}
