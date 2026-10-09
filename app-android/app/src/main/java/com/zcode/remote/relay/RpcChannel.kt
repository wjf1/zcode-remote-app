package com.zcode.remote.relay

import android.os.Handler
import android.os.Looper
import android.util.Base64
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

    // WS 线程收响应、主线程调度超时，两侧都会 remove——必须并发安全。
    private val pendingResponses = ConcurrentHashMap<Int, (RpcReply) -> Unit>()

    /** 需要超时兜底的请求 id -> 超时任务（call 侧传入 timeoutMs 时登记）。 */
    private val timeoutTasks = ConcurrentHashMap<Int, Runnable>()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 桥握手看门狗令牌（仅 main 线程读写）：只有最新一次 openBridge 的看门狗有效。 */
    private var bridgeOpenToken = 0

    /**
     * 桥重建 / 通道复位时，旧桥上的挂起请求永远等不到应答（新桥 ack 序列空间不同），
     * 必须逐个以错误收场，调用方（发送/审批/表单）才能复位 UI 状态。
     * 修的就是真机验收发现的「发送中」永久卡死（2026-09-30，HANDOVER §6.0）。
     */
    private fun failPending(reason: String) {
        val stale = pendingResponses.keys.toList()
        for (id in stale) {
            val cb = pendingResponses.remove(id) ?: continue
            cancelTimeout(id)
            ZLog.w(TAG, "failPending id=$id reason=$reason")
            runCatching { cb(RpcReply.Err(reason, null)) }
        }
    }

    private fun cancelTimeout(id: Int) {
        timeoutTasks.remove(id)?.let { mainHandler.removeCallbacks(it) }
    }

    /** 接收分片缓冲：messageSeq -> (fragmentIndex -> 分片字节)。收齐即拼装校验。 */
    private val fragmentBuffers = HashMap<Int, MutableMap<Int, ByteArray>>()

    val bridgeIdentity: JsonObject?
        get() {
            val r = readyPayload ?: return null
            return r["bridge"]?.let { runCatching { it.jsonObject }.getOrNull() }
        }

    // ---------- 开桥 ----------

    fun openBridge(workspaceKey: String, taskId: String? = null, attempt: Int = 1) {
        // 令牌：连续多次 openBridge（如 workspace-list 帧连发）时，只有最后一次的看门狗有效，
        // 避免多个看门狗互相触发重开形成放大风暴。openBridge 与看门狗同在 main 线程。
        bridgeOpenToken += 1
        val token = bridgeOpenToken
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
    private fun scheduleBridgeWatchdog(wsKey: String, taskId: String?, attempt: Int, token: Int) {
        mainHandler.postDelayed({
            if (token != bridgeOpenToken) return@postDelayed              // 已被更新的一次 open 取代
            if (_bridge.value is BridgeState.Ready) return@postDelayed     // 已就绪，看门狗自动失效
            if (attempt >= MAX_BRIDGE_OPEN_ATTEMPTS) {
                ZLog.w(TAG, "bridge 握手重开已用尽（$attempt 次），置失败态（state=${_bridge.value}）")
                _bridge.value = BridgeState.Failed("工作区桥未就绪（已重开 $attempt 次）")
                return@postDelayed
            }
            ZLog.w(TAG, "bridge ${BRIDGE_OPEN_TIMEOUT_MS}ms 未就绪，重开 attempt=${attempt + 1} ws=$wsKey")
            openBridge(wsKey, taskId, attempt + 1)
        }, BRIDGE_OPEN_TIMEOUT_MS)
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
                ZLog.i(TAG, "bridge-ready sid=$sid gen=$bridgeGeneration payload=${payload.toString().take(500)}")
                if (sid != null) {
                    // 新桥 ack 序列空间全新：旧桥挂起请求立即失败收场，防止调用方状态卡死
                    failPending("bridge re-established")
                    _bridge.value = BridgeState.Ready(sid)
                }
            }
            "workspace-bridge-error", "bridge-degraded" -> {
                ZLog.w(TAG, "bridge error: ${payload.toString().take(300)}")
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
            ZLog.w(TAG, "call($method) skipped: bridge not ready (${bridge})")
            onResponse?.invoke(RpcReply.Err("bridge not ready", null))
            return null
        }
        val id = nextRequestId++
        if (onResponse != null) {
            pendingResponses[id] = onResponse
            if (timeoutMs != null && timeoutMs > 0) {
                // 超时兜底：服务端不回/桥半死时，调用方不能永久挂起。
                // remove 原子性保证与 WS 线程的应答分发不会双触发。
                val task = Runnable {
                    val cb = pendingResponses.remove(id) ?: return@Runnable
                    ZLog.w(TAG, "rpc timeout id=$id method=$method after=${timeoutMs}ms")
                    cb(RpcReply.Err("timeout after ${timeoutMs}ms", null))
                }
                timeoutTasks[id] = task
                mainHandler.postDelayed(task, timeoutMs)
            }
        }
        val msg = Vql.serialize(listOf(TYPE_PROMISE, id, channelName, method), args)
        ZLog.i(TAG, "rpc call channel=$channelName method=$method id=$id bytes=${msg.size}")
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
            ZLog.w(TAG, "listen($event) skipped: bridge not ready (${bridge})")
            onResponse?.invoke(RpcReply.Err("bridge not ready", null))
            return null
        }
        val id = nextRequestId++
        if (onResponse != null) pendingResponses[id] = onResponse
        val msg = Vql.serialize(listOf(TYPE_EVENT_LISTEN, id, channelName, event), arg)
        ZLog.i(TAG, "rpc listen channel=$channelName event=$event id=$id bytes=${msg.size}")
        sendMessage(msg, bridge.bridgeSessionId)
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

    /**
     * 收到 rpc-frame → 按 fragmentIndex/fragmentCount 重组 → CRC 校验 → 解码 → 路由。
     *
     * 单分片（fragmentCount<=1）直通；多分片按 messageSeq 缓冲、收齐后按 index 升序拼接，
     * checksum/messageBytes 校验通过才解码与 ack（不通过则不 ack，服务端会按 messageSeq 重放）。
     */
    fun onRpcFrame(payload: JsonObject) {
        val b64 = payload["dataBase64"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() } ?: return
        val bytes = runCatching { Base64.decode(b64, Base64.NO_WRAP) }.getOrNull() ?: return
        val messageSeq = payload["messageSeq"]?.let {
            runCatching { it.jsonPrimitive.content.toIntOrNull() }.getOrNull()
        }
        val fragIndex = payload["fragmentIndex"]?.let {
            runCatching { it.jsonPrimitive.content.toIntOrNull() }.getOrNull() } ?: 0
        val fragCount = payload["fragmentCount"]?.let {
            runCatching { it.jsonPrimitive.content.toIntOrNull() }.getOrNull() } ?: 1
        val bridgeSessionId = (bridge.value as? BridgeState.Ready)?.bridgeSessionId

        val complete: ByteArray = if (fragCount <= 1) bytes else {
            val buf = fragmentBuffers.getOrPut(messageSeq ?: -1) { HashMap() }
            buf[fragIndex] = bytes
            if (buf.size < fragCount) {
                ZLog.d(TAG, "fragment buffered seq=$messageSeq ${buf.size}/$fragCount")
                trimFragmentBuffers()
                return
            }
            val joined = ByteArray(buf.entries.sortedBy { it.key }.sumOf { it.value.size })
            var off = 0
            for ((_, part) in buf.entries.sortedBy { it.key }) {
                part.copyInto(joined, off); off += part.size
            }
            fragmentBuffers.remove(messageSeq ?: -1)
            val expectCrc = payload["checksum"]?.let { runCatching { it.jsonObject["value"]?.jsonPrimitive?.content }.getOrNull() }
            val actualCrc = String.format("%08x", CRC32().apply { update(joined) }.value)
            if (expectCrc != null && !expectCrc.equals(actualCrc, ignoreCase = true)) {
                ZLog.w(TAG, "fragment checksum mismatch seq=$messageSeq expect=$expectCrc actual=$actualCrc")
                return
            }
            val messageBytes = payload["messageBytes"]?.let {
                runCatching { it.jsonPrimitive.content.toIntOrNull() }.getOrNull() }
            if (messageBytes != null && messageBytes != joined.size) {
                ZLog.w(TAG, "fragment size mismatch seq=$messageSeq expect=$messageBytes actual=${joined.size}")
                return
            }
            joined
        }
        messageSeq?.let { seq ->
            // A-4 前置：事件流缺口检测（纯函数判定 + WARN 日志）。
            // 本轮只落地「检测与可观测」——「触发重订阅」与「buffer 策略改 SUSPEND/单泵」
            // 属高危改动（任务书自标），须独立 commit + 独立真机回归，见 HANDOVER §6.1。
            if (lastAckedMessageSeq > 0 && hasSeqGap(seq, lastAckedMessageSeq)) {
                ZLog.w(TAG, "事件流缺口检测: seq=$seq lastAcked=$lastAckedMessageSeq（中间有帧未收到）")
            }
            ack(seq, bridgeSessionId)
        }
        handleMessage(complete)
    }

    /** 分片缓冲上限（防泄漏）：只留最近几条未完成消息的碎片。 */
    private fun trimFragmentBuffers() {
        while (fragmentBuffers.size > 8) {
            val oldest = fragmentBuffers.keys.minOrNull() ?: return
            fragmentBuffers.remove(oldest)
        }
    }

    /** 解码一条完整消息（VQL head + payload）并按类型路由。 */
    private fun handleMessage(bytes: ByteArray) {
        var type = -1
        var id: Int? = null
        var data: Any? = null
        try {
            val head = Vql.deserialize(bytes)
            val kind = head.value as? List<*>
            if (kind == null) {
                ZLog.w(TAG, "rpc frame head not array: ${head.value?.toString()?.take(120)}")
                return
            }
            type = (kind.getOrNull(0) as? Int) ?: -1
            id = kind.getOrNull(1) as? Int
            data = Vql.deserialize(bytes, head.nextOffset).value
        } catch (t: Throwable) {
            ZLog.w(TAG, "rpc decode failed", t)
            return
        }

        // 调试期加长到 6000：workspace-config snapshot 帧较大，400 字符看不出 configOptions 结构
        ZLog.i(TAG, "rpc recv type=$type id=$id data=${data?.toString()?.take(6000)}")
        when (type) {
            TYPE_SUCCESS -> {
                val cb = id?.let { pendingResponses.remove(it) }
                id?.let { cancelTimeout(it) }
                cb?.invoke(RpcReply.Ok(toJsonElement(data)))
            }
            TYPE_ERROR, TYPE_ERROR_OBJ -> {
                val el = toJsonElement(data)
                val msg = el.let { runCatching { it.jsonObject["message"]?.jsonPrimitive?.content }.getOrNull() }
                    ?: el.toString().take(300)
                ZLog.w(TAG, "rpc error id=$id: $msg")
                val cb = id?.let { pendingResponses.remove(it) }
                id?.let { cancelTimeout(it) }
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
        failPending("channel reset")
        fragmentBuffers.clear()
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

        const val TYPE_PROMISE = 100
        const val TYPE_PROMISE_CANCEL = 101
        const val TYPE_EVENT_LISTEN = 102
        const val TYPE_EVENT_DISPOSE = 103
        const val TYPE_SUCCESS = 201
        const val TYPE_ERROR = 202
        const val TYPE_ERROR_OBJ = 203
        const val TYPE_EVENT_FIRE = 204

        /**
         * A-3 前置：103 EventDispose 帧编码（纯函数，单测见 `PureFunctionsTest`）。
         *
         * ⚠️ **形态为最简假设**：协议文档（`CONVERSATION-PROTOCOL.md` §2 帧码表）只有
         * 「103 | EventDispose | 移除事件监听」一行，**payload 字段规格未实测**。本函数按
         * 「仅回带原监听请求 id」编码 `[103, id]`；若真机 / `tools/probe.py` 实测要求
         * 追加 channel/event 参数段，改这里一处即可（对应单测同步改）。
         */
        internal fun encodeEventDispose(requestId: Int): ByteArray =
            Vql.serialize(listOf(TYPE_EVENT_DISPOSE, requestId))

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
        internal fun hasSeqGap(incomingSeq: Int, lastAckedSeq: Int): Boolean =
            incomingSeq > lastAckedSeq + 1

        /** 通道名（asar/out/host/chunk-RWMCBKS2.js 枚举）。 */
        const val CHANNEL_AGENT = "zcode-agent"      // ← 会话/对话方法都在这个通道上
        const val CHANNEL_SESSION = "zcode-session"  // 仅 createSession 等会话管理方法
        const val CHANNEL_TASK = "zcode-task"
        const val CHANNEL_WORKSPACE = "workspace"
        const val CHANNEL_MODEL_PROVIDER = "model-provider"  // 模型供应商目录（getAllCached/getAll）
        const val CHANNEL_FILE = "file"                      // 文件系统服务（resolvePath/readTextFile）
        const val CHANNEL_SYSTEM = "system"                  // 系统服务（info 返回 homedir/platform）
        const val CHANNEL_MODEL_SELECTION = "model-selection" // 模型注册表视图（getView 含各模型合法思考档位）
    }
}
