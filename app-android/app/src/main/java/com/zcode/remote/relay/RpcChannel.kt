package com.zcode.remote.relay

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import com.zcode.remote.util.LogRedactor
import com.zcode.remote.util.ZLog
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 会话桥 + RPC 通道（规格见 research/FRAME-CODEC.md、research/CONVERSATION-PROTOCOL.md）。
 *
 * 流程：workspace-bridge-open → workspace-bridge-ready → 发 rpc-frame（Vql 编码）→ 收 204 EventFire。
 *
 * §11.5.2（beta33）：请求 key 绑 `(桥代次, requestId)`、入站身份三元组校验、单/多分片统一
 * 校验（声明长度 + 整条消息 CRC32）、**完整解码成功后才 ack**、在途分片与队列有界、
 * 出站超限本地拒绝（物理分片待真实样本，见 ProtocolLimits / 任务书 §11.5.2）。
 */
class RpcChannel(
    private val relay: RelayClient,
) {
    sealed interface BridgeState {
        data object Closed : BridgeState

        data object Opening : BridgeState

        data class Ready(
            val bridgeSessionId: String,
        ) : BridgeState

        data class Failed(
            val reason: String,
        ) : BridgeState
    }

    /** Promise 调用的结果：202/203 不再伪装成 null，便于调用方区分「失败」与「成功但无数据」。 */
    sealed interface RpcReply {
        data class Ok(
            val data: JsonElement?,
        ) : RpcReply

        data class Err(
            val message: String,
            val raw: JsonElement?,
        ) : RpcReply
    }

    /**
     * 桥看门狗到点后的决策（见 companion 的 `watchdogDecision`）。
     * 定义在类体内（与 [RpcReply] 同款）——最初放在 companion 内时，测试源集引用
     * `RpcChannel.WatchdogDecision` 报 Unresolved reference（Kotlin 2.0.20 实编译复现一次），
     * 移到类体即通过；**勿轻易挪回 companion**。
     */
    sealed interface WatchdogDecision {
        /** 什么都不做：令牌已过期（被更新的一次 open 取代）或桥已就绪。 */
        data object Noop : WatchdogDecision

        /** 未用尽：带上重开次数继续 `openBridge`。 */
        data class Retry(
            val nextAttempt: Int,
        ) : WatchdogDecision

        /** 重开次数用尽：置 `BridgeState.Failed`，把「静默卡死」变成「可见失败」。 */
        data object Fail : WatchdogDecision
    }

    /** 204 EventFire：带请求 id（事件监听的应答都挂在该监听的 id 上）。 */
    data class RpcEvent(
        val id: Int?,
        val data: JsonElement,
    )

    // A-4：SUSPEND + 单泵 —— 慢消费时由发射端（onRpcFrame，跑在入站单泵协程里）背压挂起，
    // 而非 DROP_OLDEST 丢帧：丢帧 = seq 缺口 = 会话静默停在旧状态。
    // 容量收敛到集中预算 ProtocolLimits.RPC_EVENT_BUFFER_CAPACITY（§11.3.3）。
    private val _events =
        MutableSharedFlow<RpcEvent>(
            extraBufferCapacity = ProtocolLimits.RPC_EVENT_BUFFER_CAPACITY,
            onBufferOverflow = BufferOverflow.SUSPEND,
        )

    /**
     * A-4：事件流 seq 缺口回调（缺口帧已 ack、服务端不重发，会话将静默停在旧状态）。
     * 连接层不自知会话语义，由上层挂「重订阅」（服务端重放权威快照）实现自愈。
     * 在入站泵协程上回调；实现方须可跨线程调用。
     */
    var onSequenceGap: (() -> Unit)? = null

    /**
     * §11.5.2：协议违例回调（分片顺序/CRC/长度/超限/解码失败等固定错误码）。
     * 与 [onSequenceGap] 同一语义——上层须进入重同步（重订阅权威快照）；实现方自行节流。
     * 身份不匹配（旧桥迟到帧）**不**走此回调：那是丢弃即可的正常竞态，不构成失同步。
     */
    var onProtocolViolation: ((ProtocolViolation) -> Unit)? = null
    val events: SharedFlow<RpcEvent> = _events

    private val _bridge = MutableStateFlow<BridgeState>(BridgeState.Closed)
    val bridge: StateFlow<BridgeState> = _bridge

    /** 最近一次 ready 回包（含 bridge.* 身份与 host 能力）。 */
    var readyPayload: JsonObject? = null
        private set

    /** 桥代次（identity 三元组之一，PC 端做全等校验，必须回填到 rpc-frame）。 */
    private var bridgeGeneration: Int? = null
    private var recoveryId: String? = null

    /**
     * §11.5.2 请求位点代次：桥就绪/通道复位时递增。
     * 挂起请求以 `(bridgeEpoch, requestId)` 为 key —— 旧连接的迟到应答在结构上无法串单。
     */
    private var bridgeEpoch = 0

    /** 请求 id 由 [AtomicInteger] 生成（call/listen 可能来自任意线程）；保持非负（线格式为 uint32）。 */
    private val nextRequestId = AtomicInteger(0)
    private var nextPhysicalSeq = 1
    private var nextMessageSeq = 1
    private var lastAckedMessageSeq = 0

    // WS 线程收响应、主线程调度超时，两侧都会 remove——必须并发安全；key 含代次。
    private val pendingResponses = PendingRequests<(RpcReply) -> Unit>()

    /** 需要超时兜底的请求 key（同 PendingRequests.key）-> 超时任务。 */
    private val timeoutTasks = ConcurrentHashMap<Long, Runnable>()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** §11.5.2：入站分片组装器（纯逻辑，校验/去重/超时/容量全在其内）。 */
    private val assembler = RpcFrameAssembler()

    /**
     * 桥重建 / 通道复位时，旧桥上的挂起请求永远等不到应答（新桥 ack 序列空间不同），
     * 必须逐个以错误收场，调用方（发送/审批/表单）才能复位 UI 状态。
     * 修的就是真机验收发现的「发送中」永久卡死（2026-09-30，HANDOVER §6.0）。
     */
    private fun failPending(reason: String) {
        cancelAllTimeouts()
        for ((id, cb) in pendingResponses.drainAll()) {
            ZLog.w(TAG, "failPending id=$id reason=$reason")
            runCatching { cb(RpcReply.Err(reason, null)) }
        }
    }

    private fun cancelTimeout(key: Long) {
        timeoutTasks.remove(key)?.let { mainHandler.removeCallbacks(it) }
    }

    private fun cancelAllTimeouts() {
        timeoutTasks.values.forEach { mainHandler.removeCallbacks(it) }
        timeoutTasks.clear()
    }

    /** 桥握手看门狗令牌（仅 main 线程读写）：只有最新一次 openBridge 的看门狗有效。 */
    private var bridgeOpenToken = 0

    /** 当前桥身份（无桥时字段为 null；[identityMismatch] 对「无桥 + 带身份键的帧」判为不一致）。 */
    private fun currentIdentity(): FrameIdentity =
        FrameIdentity(
            sessionId = (_bridge.value as? BridgeState.Ready)?.bridgeSessionId,
            generation = bridgeGeneration?.toString(),
            recoveryId = recoveryId,
        )

    /** 固定错误码 + 计数落日志（只记码/序号/长度，绝不记 payload），并按需上抛重同步。 */
    private fun reject(
        violation: ProtocolViolation,
        messageSeq: Int?,
        detail: String,
        resync: Boolean = true,
    ) {
        ZLog.w(TAG, "rpc frame rejected code=${violation.code} seq=${messageSeq ?: "-"} $detail")
        if (resync) onProtocolViolation?.invoke(violation)
    }

    val bridgeIdentity: JsonObject?
        get() {
            val r = readyPayload ?: return null
            return r["bridge"]?.let { runCatching { it.jsonObject }.getOrNull() }
        }

    // ---------- 开桥 ----------

    fun openBridge(
        workspaceKey: String,
        taskId: String? = null,
        attempt: Int = 1,
    ) {
        // 令牌：连续多次 openBridge（如 workspace-list 帧连发）时，只有最后一次的看门狗有效，
        // 避免多个看门狗互相触发重开形成放大风暴。openBridge 与看门狗同在 main 线程。
        bridgeOpenToken += 1
        val token = bridgeOpenToken
        val sid = "bridge-${UUID.randomUUID()}"
        _bridge.value = BridgeState.Opening
        val payload =
            buildJsonObject {
                put("zcode_type", "workspace-bridge-open")
                put("requestId", "workspace-bridge-${UUID.randomUUID()}")
                put("bridgeSessionId", sid)
                put("bridgeGeneration", 1)
                put("workspaceKey", workspaceKey)
                taskId?.let { put("taskId", it) }
            }
        ZLog.i(TAG, "bridge-open ws=$workspaceKey sid=$sid attempt=$attempt")
        relay.sendPayload(payload)
        scheduleBridgeWatchdog(workspaceKey, taskId, attempt, token)
    }

    /**
     * 桥握手看门狗（真机反馈修复，2026-10-07）。
     *
     * 缺陷：`openBridge` 只把状态置为 [BridgeState.Opening]，**没有任何超时或重开机制**
     * （仅在收到新的 workspace-list 帧时才可能重开）。若 `workspace-bridge-ready` 帧丢失，
     * 桥会**永久**停在 `Opening`，此时 [call]/[listen] 一律立刻回 `bridge not ready` ——
     * 表现为「中继/设置显示已连接，会话页却一直报错且永不恢复」。
     *
     * 这里做有限次重开（间隔 [BRIDGE_OPEN_TIMEOUT_MS]，上限 [MAX_BRIDGE_OPEN_ATTEMPTS] 次），
     * 用尽则显式置 [BridgeState.Failed]，把「静默卡死」变成「可见失败」。
     */
    private fun scheduleBridgeWatchdog(
        wsKey: String,
        taskId: String?,
        attempt: Int,
        token: Int,
    ) {
        mainHandler.postDelayed({
            // 决策抽成纯函数（watchdogDecision）以便 JVM 单测钉死「重开用尽 → 可见失败」——
            // 该路径在真机上**无法构造**（需人为丢弃 workspace-bridge-ready，App 外部制造不了，
            // 见 HANDOVER beta15 记录），此前仅有代码推理覆盖。
            when (
                val d =
                    watchdogDecision(
                        token = token,
                        currentToken = bridgeOpenToken,
                        bridgeReady = _bridge.value is BridgeState.Ready,
                        attempt = attempt,
                    )
            ) {
                is WatchdogDecision.Noop -> Unit
                is WatchdogDecision.Fail -> {
                    ZLog.w(TAG, "bridge 握手重开已用尽（$attempt 次），置失败态（state=${_bridge.value}）")
                    _bridge.value = BridgeState.Failed("工作区桥未就绪（已重开 $attempt 次）")
                }
                is WatchdogDecision.Retry -> {
                    ZLog.w(TAG, "bridge ${BRIDGE_OPEN_TIMEOUT_MS}ms 未就绪，重开 attempt=${d.nextAttempt} ws=$wsKey")
                    openBridge(wsKey, taskId, d.nextAttempt)
                }
            }
        }, BRIDGE_OPEN_TIMEOUT_MS)
    }

    /** 由 AppViewModel 在收到 workspace-bridge-* 帧时调用。 */
    fun onBridgeFrame(payload: JsonObject) {
        when (BridgeFrames.zcodeTypeOf(payload)) {
            "workspace-bridge-ready" -> {
                val sid = payload["bridgeSessionId"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
                readyPayload = payload
                // 身份三元组以此为准（PC 端 enforces 全等匹配）
                bridgeGeneration =
                    payload["bridgeGeneration"]?.let {
                        runCatching { it.jsonPrimitive.content.toIntOrNull() }.getOrNull()
                    }
                recoveryId = payload["recoveryId"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
                ZLog.i(
                    TAG,
                    "bridge-ready sid=${LogRedactor.maskId(
                        sid,
                    )} gen=$bridgeGeneration payload=${LogRedactor.payloadLabel(payload.toString())}",
                )
                // §11.5.2：新桥 = 新 seq 空间。官方每条桥各自维护一套 seq（两侧从 1 起、
                // 组装器 expectedPhysicalSeq 默认 1，见 research/FRAME-CODEC.md §6）——
                // 若沿用旧 seq，PC 侧组装器会判 physicalGap（终态）；重置使两侧同起点。
                bridgeEpoch += 1
                nextPhysicalSeq = 1
                nextMessageSeq = 1
                lastAckedMessageSeq = 0
                assembler.clear()
                if (sid != null) {
                    // 新桥 ack 序列空间全新：旧桥挂起请求立即失败收场，防止调用方状态卡死
                    failPending("bridge re-established")
                    _bridge.value = BridgeState.Ready(sid)
                }
            }
            "workspace-bridge-error", "bridge-degraded" -> {
                ZLog.w(TAG, "bridge error payload=${LogRedactor.payloadLabel(payload.toString())}")
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
        timeoutMs: Long? = null,
        onResponse: ((RpcReply) -> Unit)? = null,
    ): Int? {
        val bridge = _bridge.value
        if (bridge !is BridgeState.Ready) {
            ZLog.w(TAG, "call($method) skipped: bridge not ready ($bridge)")
            onResponse?.invoke(RpcReply.Err("bridge not ready", null))
            return null
        }
        val epoch = bridgeEpoch
        val id = nextRequestId.getAndIncrement() and ID_MASK
        if (onResponse != null) {
            pendingResponses.register(epoch, id, onResponse)
            if (timeoutMs != null && timeoutMs > 0) {
                // 超时兜底：服务端不回/桥半死时，调用方不能永久挂起。
                // remove 原子性保证与 WS 线程的应答分发不会双触发。
                val key = PendingRequests.key(epoch, id)
                val task =
                    Runnable {
                        val cb = pendingResponses.complete(epoch, id) ?: return@Runnable
                        ZLog.w(TAG, "rpc timeout id=$id method=$method after=${timeoutMs}ms")
                        cb(RpcReply.Err("timeout after ${timeoutMs}ms", null))
                    }
                timeoutTasks[key] = task
                mainHandler.postDelayed(task, timeoutMs)
            }
        }
        val msg = Vql.serialize(listOf(TYPE_PROMISE, id, channelName, method), args)
        ZLog.i(TAG, "rpc call channel=$channelName method=$method id=$id bytes=${msg.size}")
        if (!sendMessage(msg, bridge.bridgeSessionId)) {
            // 出站超限（>16MiB 或信封 >1MiB）本地即拒：以可见失败收场，不留「已发送却等不到应答」的挂起
            pendingResponses.complete(epoch, id)?.let { it(RpcReply.Err("消息过大，已被本地拒绝发送", null)) }
            cancelTimeout(PendingRequests.key(epoch, id))
            return null
        }
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
            ZLog.w(TAG, "listen($event) skipped: bridge not ready ($bridge)")
            onResponse?.invoke(RpcReply.Err("bridge not ready", null))
            return null
        }
        val epoch = bridgeEpoch
        val id = nextRequestId.getAndIncrement() and ID_MASK
        if (onResponse != null) pendingResponses.register(epoch, id, onResponse)
        val msg = Vql.serialize(listOf(TYPE_EVENT_LISTEN, id, channelName, event), arg)
        ZLog.i(TAG, "rpc listen channel=$channelName event=$event id=$id bytes=${msg.size}")
        if (!sendMessage(msg, bridge.bridgeSessionId)) {
            pendingResponses.complete(epoch, id)?.let { it(RpcReply.Err("消息过大，已被本地拒绝发送", null)) }
            return null
        }
        return id
    }

    /**
     * A-3 前置：[encodeEventDispose] 的发送入口（fire-and-forget，与 [listen] 同构——服务端
     * 对 103 的应答形态未知，不注册回调；发送失败不影响主流程）。返回 false = 桥未就绪。
     */
    fun disposeEvent(listenId: Int): Boolean {
        val bridge = _bridge.value
        if (bridge !is BridgeState.Ready) {
            ZLog.w(TAG, "disposeEvent($listenId) skipped: bridge not ready")
            return false
        }
        val msg = encodeEventDispose(listenId)
        ZLog.i(TAG, "rpc dispose listenId=$listenId bytes=${msg.size}")
        sendMessage(msg, bridge.bridgeSessionId)
        return true
    }

    /**
     * 组 rpc-frame 并发出。返回 false = **本地预算拒绝**（未发送）：
     * ① 报文 > 16 MiB（官方 `messageTooLarge`）；② `data` 信封 > 1 MiB（官方 `envelopeTooLarge`，
     * 官方发送端在此按 ≤1 MiB 物理分片；App 侧物理分片待真实样本，先显式拒绝而非发超限帧）。
     */
    private fun sendMessage(
        message: ByteArray,
        bridgeSessionId: String,
    ): Boolean {
        val bytes = message
        if (bytes.size > ProtocolLimits.MAX_MESSAGE_BYTES) {
            ZLog.w(TAG, "outbound refused code=${ProtocolViolation.OUTBOUND_TOO_LARGE.code} bytes=${bytes.size}")
            return false
        }
        val checksum =
            buildJsonObject {
                put("algorithm", "crc32")
                put("value", crc32Hex(bytes))
            }
        val seq = nextPhysicalSeq++
        val messageSeq = nextMessageSeq++
        val frame =
            buildJsonObject {
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
        // 快速路径：base64 膨胀 4/3 后仍远离 1 MiB 的报文无需序列化整帧做预算判定
        // （700_000 × 4/3 ≈ 933 KB + 信封余量 < 1 MiB，数学上必然合规）
        if (bytes.size <= ENVELOPE_SAFE_MESSAGE_BYTES) {
            relay.sendPayload(frame)
            return true
        }
        // 官方 envelopeTooLarge 的同一口径：data 信封（含 client_ts）UTF-8 字节 ≤ 1 MiB
        val envelopeBytes = relay.envelopeBytes(frame)
        if (envelopeBytes > ProtocolLimits.MAX_PHYSICAL_FRAME_BYTES) {
            ZLog.w(
                TAG,
                "outbound refused code=${ProtocolViolation.OUTBOUND_TOO_LARGE.code} envelope=$envelopeBytes",
            )
            return false
        }
        relay.sendPayload(frame)
        return true
    }

    /**
     * 收到 rpc-frame → 身份校验 → 分片组装（统一校验）→ **完整解码成功后** ack → 路由。
     *
     * §11.5.2 的判定全部落在纯逻辑里（[RpcFrameAssembler] / [ProtocolLimits]），本函数只做
     * 与 Android 相关的取字段、base64 与日志：
     * - 身份三元组与当前桥不符（旧桥迟到帧）→ 丢弃且不回 ack（官方非终态，不触发重同步）；
     * - 已 ack 的 messageSeq 重放（服务端累计 ack 语义下的重传）→ 幂等忽略；
     * - 分片顺序/CRC/声明长度/容量违例 → 固定错误码 + 重同步（**不 ack**，服务端可重放）；
     * - 解码失败的帧**绝不 ack**（旧实现在 ack 之后才解码，等于对无法处理的帧确认收货）。
     */
    suspend fun onRpcFrame(payload: JsonObject) {
        val identity = frameIdentityOf(payload)
        validateIdentityLengths(identity)?.let {
            return reject(it, null, "identity field too long")
        }
        if (identityMismatch(identity, currentIdentity())) {
            // 旧桥/旧连接的迟到帧：结构上就是不该处理的帧，丢弃即可（不 ack、不重同步）
            return reject(ProtocolViolation.IDENTITY_MISMATCH, null, "identity mismatch", resync = false)
        }
        val b64 =
            payload["dataBase64"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
                ?: return reject(ProtocolViolation.METADATA, null, "missing dataBase64")
        if (b64.length > ProtocolLimits.MAX_PHYSICAL_FRAME_BYTES) {
            return reject(ProtocolViolation.FRAME_BYTES, null, "b64Chars=${b64.length}")
        }
        // 官方 isCanonicalBase64 的第一道：形状（廉价，纯逻辑）非法即拒，不进解码器
        if (!isCanonicalBase64Shape(b64)) {
            return reject(ProtocolViolation.INVALID_BASE64, null, "non-canonical base64 shape")
        }
        val slice = runCatching { Base64.decode(b64, Base64.NO_WRAP) }.getOrNull()
        if (slice == null || slice.isEmpty()) return reject(ProtocolViolation.INVALID_BASE64, null, "decode failed")
        // 第二道：解码-重编码往返全等（官方 $u(n)===t 的同构判定）
        if (Base64.encodeToString(slice, Base64.NO_WRAP) != b64) {
            return reject(ProtocolViolation.INVALID_BASE64, null, "non-canonical base64 roundtrip")
        }
        val metadata =
            rpcFrameMetadataOf(payload)
                ?: return reject(ProtocolViolation.METADATA, null, "metadata parse failed")
        // 信封字节按重序列化长度判定（解析后的 JsonObject 已无原始字节；量级与官方口径一致）。
        // 快速路径：dataBase64 是帧内绝对主字段（其余字段合计仅数百字节），其字符数 ≤ 阈值时
        // envelope 必然 < 1 MiB —— 流式期每帧免一次整帧重序列化（与出站快速路径同一阈值口径）。
        if (b64.length > ENVELOPE_SAFE_MESSAGE_BYTES) {
            val envelopeBytes = payload.toString().toByteArray(Charsets.UTF_8).size
            if (envelopeBytes > ProtocolLimits.MAX_PHYSICAL_FRAME_BYTES) {
                return reject(ProtocolViolation.FRAME_BYTES, metadata.messageSeq, "envelope=$envelopeBytes")
            }
        }
        // 已 ack 的 messageSeq 重放：累计 ack 覆盖，幂等忽略（官方 kind:"duplicate" 同一语义）
        if (metadata.messageSeq <= lastAckedMessageSeq) {
            ZLog.d(TAG, "rpc frame replay ignored seq=${metadata.messageSeq} acked=$lastAckedMessageSeq")
            return
        }

        val nowMs = SystemClock.elapsedRealtime()
        for (v in assembler.sweepExpired(nowMs)) {
            ZLog.w(TAG, "assembly evicted code=${v.code} seq=${metadata.messageSeq} inFlight=${assembler.inFlightBytes}")
            onProtocolViolation?.invoke(v)
        }
        when (val outcome = assembler.accept(slice, metadata, nowMs)) {
            is RpcFrameAssembler.Outcome.Buffered -> return
            is RpcFrameAssembler.Outcome.Rejected ->
                return reject(outcome.violation, metadata.messageSeq, "assembly rejected")
            is RpcFrameAssembler.Outcome.Complete -> {
                val decoded =
                    decodeMessage(outcome.bytes)
                        ?: return reject(ProtocolViolation.DECODE_FAILED, outcome.messageSeq, "vql decode failed")
                // A-4：事件流缺口 = 中间有帧被丢（ack 已发、服务端不重发）→ 会话会静默停在旧状态。
                // 唯一自愈路径是**重订阅**（服务端重放权威快照）；连接层不自知会话语义，经回调交上层。
                if (lastAckedMessageSeq > 0 && hasSeqGap(outcome.messageSeq, lastAckedMessageSeq)) {
                    ZLog.w(TAG, "事件流缺口检测: seq=${outcome.messageSeq} lastAcked=$lastAckedMessageSeq（中间有帧未收到）→ 触发会话重订阅")
                    onSequenceGap?.invoke()
                }
                ack(outcome.messageSeq, (_bridge.value as? BridgeState.Ready)?.bridgeSessionId)
                dispatch(decoded)
            }
        }
    }

    /** 解码结果（VQL head + payload 都解析成功才算成功；null = 解码失败，不得 ack）。 */
    private class DecodedMessage(
        val type: Int,
        val id: Int?,
        val data: Any?,
    )

    /** 解码一条完整消息（VQL head + payload）。返回 null = 解码失败。 */
    private fun decodeMessage(bytes: ByteArray): DecodedMessage? =
        try {
            val head = Vql.deserialize(bytes)
            val kind = head.value as? List<*>
            if (kind == null) {
                ZLog.w(TAG, "rpc frame head not array type=${head.value?.let { it::class.simpleName }}")
                null
            } else {
                val type = kind.getOrNull(0) as? Int
                if (type == null) {
                    ZLog.w(TAG, "rpc frame head missing type")
                    null
                } else {
                    DecodedMessage(type, kind.getOrNull(1) as? Int, Vql.deserialize(bytes, head.nextOffset).value)
                }
            }
        } catch (t: Throwable) {
            ZLog.w(TAG, "rpc decode failed", t)
            null
        }

    /** 完整解码成功后按类型路由（201/202/203 对应请求位点；204 进事件流）。 */
    private suspend fun dispatch(m: DecodedMessage) {
        val type = m.type
        val id = m.id
        val data = m.data
        // P0 日志止血：不再打印 data 正文（旧实现 take(6000)，workspace-config 等帧含全文）
        ZLog.i(TAG, "rpc recv type=$type id=$id data=${LogRedactor.payloadLabel(data?.toString())}")
        val epoch = bridgeEpoch
        when (type) {
            TYPE_SUCCESS -> {
                val cb = id?.let { pendingResponses.complete(epoch, it) }
                id?.let { cancelTimeout(PendingRequests.key(epoch, it)) }
                cb?.invoke(RpcReply.Ok(toJsonElement(data)))
            }
            TYPE_ERROR, TYPE_ERROR_OBJ -> {
                val el = toJsonElement(data)
                val msg =
                    el.let { runCatching { it.jsonObject["message"]?.jsonPrimitive?.content }.getOrNull() }
                        ?: el.toString().take(300)
                ZLog.w(TAG, "rpc error id=$id msg=${LogRedactor.payloadLabel(msg)}")
                val cb = id?.let { pendingResponses.complete(epoch, it) }
                id?.let { cancelTimeout(PendingRequests.key(epoch, it)) }
                cb?.invoke(RpcReply.Err(msg, el))
            }
            TYPE_EVENT_FIRE -> _events.emit(RpcEvent(id, toJsonElement(data)))
            else -> Unit
        }
    }

    private fun toJsonElement(v: Any?): JsonElement =
        when (v) {
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
    private fun ack(
        messageSeq: Int,
        bridgeSessionId: String?,
    ) {
        if (messageSeq <= lastAckedMessageSeq || bridgeSessionId == null) return
        lastAckedMessageSeq = messageSeq
        relay.sendPayload(
            buildJsonObject {
                put("zcode_type", "rpc-frame-ack")
                put("bridgeSessionId", bridgeSessionId)
                bridgeGeneration?.let { put("bridgeGeneration", it) }
                recoveryId?.let { put("recoveryId", it) }
                put("ackMessageSeq", messageSeq)
            },
        )
    }

    /**
     * 通道复位（relay 重新配对/换设备时调用）：§11.5.2 要求清理 **pending、看门狗、ACK、
     * 序列与分片状态**——旧实现漏了看门狗令牌与身份字段（复位后旧看门狗仍可能用旧 wsKey
     * 重开桥、且出站帧会带上旧桥身份）。
     */
    fun reset() {
        // 令牌失效：在途看门狗到点后判定 Noop，不再用旧 wsKey 重开桥
        bridgeOpenToken += 1
        _bridge.value = BridgeState.Closed
        readyPayload = null
        bridgeGeneration = null
        recoveryId = null
        // 代次递增：旧代次的迟到应答在 key 上即不匹配，结构上不可能串单
        bridgeEpoch += 1
        nextRequestId.set(0)
        nextPhysicalSeq = 1
        nextMessageSeq = 1
        lastAckedMessageSeq = 0
        assembler.clear()
        failPending("channel reset")
    }

    companion object {
        private const val TAG = "RpcChannel"

        /**
         * 桥握手看门狗参数（2026-10-07 真机反馈修复）。
         * 真机实测 `workspace-bridge-ready` 正常在 1s 内到达，故 6s 判定为「丢帧/未处理」足够保守；
         * 最多重开 3 次（约 18s），用尽后置失败态而非静默卡在 Opening。
         */
        private const val BRIDGE_OPEN_TIMEOUT_MS = 6_000L
        private const val MAX_BRIDGE_OPEN_ATTEMPTS = 3

        /** 请求 id 掩码：线格式为 uint32，保持非负且不溢出（回绕后复用旧 id 由桥代次隔离）。 */
        private const val ID_MASK = 0x7fffffff

        /**
         * 出站信封预算快速路径阈值（字节）：≤ 此值的报文经 base64（4/3）+ JSON 信封后
         * 必然 < 1 MiB（[ProtocolLimits.MAX_PHYSICAL_FRAME_BYTES]），无需序列化整帧判定。
         */
        private const val ENVELOPE_SAFE_MESSAGE_BYTES = 700_000

        const val TYPE_PROMISE = 100
        const val TYPE_PROMISE_CANCEL = 101
        const val TYPE_EVENT_LISTEN = 102
        const val TYPE_EVENT_DISPOSE = 103
        const val TYPE_SUCCESS = 201
        const val TYPE_ERROR = 202
        const val TYPE_ERROR_OBJ = 203
        const val TYPE_EVENT_FIRE = 204

        /**
         * A-3：103 EventDispose 帧编码（纯函数，单测见 `PureFunctionsTest`）。
         *
         * **字段规格 = 官方 web bundle 静态实证 + 动态实测双确认（2026-10-09）**：
         * 官方 `sendCancelOrDispose` 为 `zu(n,[e,t]); zu(n,void 0)`——头部数组 `[103, id]`
         * 加一个 undefined 参数段（与 102 listen 的两段式同构）；
         * `tools/probe.py dispose` 实测：dispose 前 12s 收 8 帧事件、发帧后 12s 归零
         * （期间会话持续产生事件），服务端静默接受无错误应答。
         */
        internal fun encodeEventDispose(requestId: Int): ByteArray = Vql.serialize(listOf(TYPE_EVENT_DISPOSE, requestId), null)

        /**
         * A-4 前置：事件流缺口判定（纯函数，单测见 `PureFunctionsTest`）。
         *
         * 事件流帧的 `messageSeq` 单调递增（WS 帧严格有序，服务端不重发已 ack 的帧）。
         * 收到的 seq 与已确认水位 [lastAckedSeq] 之间「跳号」即说明中间有帧丢失——
         * 表现为会话静默停在旧状态（ack 已发出、服务端不再重放）。
         *
         * 四类输入：连续（incoming == last+1）→ false；跳号（> last+1）→ **true**；
         * 重复（==）→ false；回退（<，迟到旧帧）→ false。
         */
        internal fun hasSeqGap(
            incomingSeq: Int,
            lastAckedSeq: Int,
        ): Boolean = incomingSeq > lastAckedSeq + 1

        /**
         * 桥看门狗到点时的决策（纯函数，单测见 `PureFunctionsTest.watchdog*`）。
         *
         * 历史背景：beta15 的「桥重开 3 次用尽 → 可见失败」在真机上**无法构造**
         * （需人为丢弃 `workspace-bridge-ready`，App 外部制造不了），当时只有代码推理覆盖；
         * 抽成纯函数后该路径由单测钉死（token 过期 / 已就绪 / 未用尽重开 / 用尽置失败）。
         */
        internal fun watchdogDecision(
            token: Int,
            currentToken: Int,
            bridgeReady: Boolean,
            attempt: Int,
            maxAttempts: Int = MAX_BRIDGE_OPEN_ATTEMPTS,
        ): WatchdogDecision =
            when {
                token != currentToken -> WatchdogDecision.Noop
                bridgeReady -> WatchdogDecision.Noop
                attempt >= maxAttempts -> WatchdogDecision.Fail
                else -> WatchdogDecision.Retry(attempt + 1)
            }

        /** 通道名（asar/out/host/chunk-RWMCBKS2.js 枚举）。 */
        const val CHANNEL_AGENT = "zcode-agent" // ← 会话/对话方法都在这个通道上
        const val CHANNEL_SESSION = "zcode-session" // 仅 createSession 等会话管理方法
        const val CHANNEL_TASK = "zcode-task"
        const val CHANNEL_WORKSPACE = "workspace"
        const val CHANNEL_MODEL_PROVIDER = "model-provider" // 模型供应商目录（getAllCached/getAll）
        const val CHANNEL_FILE = "file" // 文件系统服务（resolvePath/readTextFile）
        const val CHANNEL_SYSTEM = "system" // 系统服务（info 返回 homedir/platform）
        const val CHANNEL_MODEL_SELECTION = "model-selection" // 模型注册表视图（getView 含各模型合法思考档位）
    }
}
