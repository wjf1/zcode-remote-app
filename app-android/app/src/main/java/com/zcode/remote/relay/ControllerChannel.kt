package com.zcode.remote.relay

import com.zcode.remote.storage.ProtocolStack
import com.zcode.remote.util.ZLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * V2 host 的 Controller 通道（PROTOCOL.md §11.6）：会话列表与工作区的**权威数据源**。
 *
 * 为什么需要：ZCode ≥3.16 之后 bootstrap `result` 只剩 `workspaces[] / mobileViewState /
 * desktopAppVersion / windowControlSessionId`，**不再下发 `tasks[]`**——会话列表要从
 * `controller/tasks-index` 订阅拿。桌面端 web bundle 同样只订阅这两个 topic。
 *
 * 分层：[ControllerReducer] 是无 IO 的纯状态机（解析 + 快照/增量应用 + 水位与失同步判定，
 * 全部由 JVM 单测覆盖）；本类只负责 listen/call 编排与重同步节流。
 *
 * 字段依据：桌面端 host 产物 zod strict schema（`chunk-BG4MS6RN.js` /
 * `chunk-RWMCBKS2.js`）+ PROTOCOL.md §11.6；真机落帧由 `tools/probe.py ctrl` 采集。
 */
class ControllerChannel(
    private val rpc: RpcChannel,
    private val nowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    private val reducer = ControllerReducer()

    val tasks: StateFlow<Map<String, ControllerTask>> get() = reducer.tasks

    val workspaces: StateFlow<List<ControllerWorkspace>> get() = reducer.workspaces

    val archivedCount: StateFlow<Int> get() = reducer.archivedCount

    private var listenId: Int? = null
    private val subscriptionByTopic = mutableMapOf<String, String>()
    private val resyncThrottle = ResyncThrottle()
    private var throttledTopics: Set<String> = emptySet()

    /** 已订阅 topic 的 subscriptionId（诊断/测试用）。 */
    fun subscriptionIdOf(topic: String): String? = subscriptionByTopic[topic]

    /**
     * 桥 Ready 后订阅。V2 host 对桥上 V4 RPC 强制连接级握手
     * （`fault.connection.handshakeRequired`），故先 hello → initialize，
     * 与 [SessionsIndexChannel] / [ConversationChannel] 同序列（服务端允许多次 initialize）。
     */
    fun subscribe(visibility: String = VISIBILITY_FOREGROUND) {
        if (listenId != null) return // 桥 Ready 会多次发射，避免重复 listen 泄漏
        rpc.call(RpcChannel.CHANNEL_AGENT, "helloConversationV4") { hello ->
            if (hello !is RpcChannel.RpcReply.Ok) {
                ZLog.w(TAG, "controller 前置握手 hello 失败: ${(hello as? RpcChannel.RpcReply.Err)?.message ?: "?"}")
                doSubscribe(visibility)
                return@call
            }
            val pv =
                (hello.data as? JsonObject)
                    ?.get("protocolVersion")
                    ?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() } ?: 3
            rpc.call(
                RpcChannel.CHANNEL_AGENT,
                "initializeConversationV4",
                listOf(
                    mapOf(
                        "kind" to "clientHello",
                        "protocolVersion" to pv,
                        "clientId" to clientId,
                        "clientKind" to "mobileApp",
                        "appVersion" to "1.0.0",
                    ),
                ),
            ) { init ->
                if (init !is RpcChannel.RpcReply.Ok) {
                    ZLog.w(TAG, "controller 前置握手 initialize 失败: ${(init as? RpcChannel.RpcReply.Err)?.message ?: "?"}")
                }
                doSubscribe(visibility)
            }
        }
    }

    private fun doSubscribe(visibility: String) {
        // 两个 topic 共用一个 listen，帧按 subscriptionId 分流（PROTOCOL.md §11.6 默认假设）
        listenId = rpc.listen(RpcChannel.CHANNEL_AGENT, ControllerChannel.EVENT_NAME, null)
        for (topic in CONTROLLER_TOPICS) {
            rpc.call(
                RpcChannel.CHANNEL_AGENT,
                "subscribeControllerV4",
                listOf(mapOf("topic" to topic, "visibility" to visibility)),
            ) { reply ->
                when (reply) {
                    is RpcChannel.RpcReply.Ok -> {
                        val sub = ControllerFrames.subscriptionIdOf(reply.data)
                        sub?.let { subscriptionByTopic[topic] = it }
                        ZLog.i(TAG, "controller subscribed topic=$topic sub=$sub")
                    }
                    is RpcChannel.RpcReply.Err ->
                        ZLog.w(TAG, "controller subscribe 失败 topic=$topic: ${reply.message}")
                }
            }
        }
    }

    /** RpcChannel.events 统一分发：只认本次 listen 注册的 id，再按 topic/subscriptionId 分流。 */
    fun onEvent(event: RpcChannel.RpcEvent) {
        if (listenId == null || event.id != listenId) return
        val data = event.data as? JsonObject ?: return
        val frame = ControllerFrames.parse(data) ?: return
        val expectedSub = subscriptionByTopic[frame.topic]
        if (expectedSub != null && frame.subscriptionId != expectedSub) return
        when (val outcome = reducer.apply(frame)) {
            is ReduceOutcome.Applied -> Unit
            is ReduceOutcome.Desync -> {
                ZLog.w(TAG, "controller 失同步 topic=${outcome.topic} reason=${outcome.reason} detail=${outcome.detail}")
                requestResync(outcome.topic)
            }
        }
    }

    /**
     * `resyncControllerV4({subscriptionId, base})`，节流同 [ConversationChannel]
     * （[ProtocolLimits.RESYNC_MIN_INTERVAL_MS]）。节流窗内被抑制的 topic 汇总，窗口过后的
     * 下一次触发一并补发（不至于永久丢失）。
     */
    private fun requestResync(topic: String) {
        val sub = subscriptionByTopic[topic]
        if (sub == null) {
            throttledTopics = throttledTopics + topic
            return
        }
        if (!resyncThrottle.shouldTrigger(nowMs())) {
            throttledTopics = throttledTopics + topic
            ZLog.i(TAG, "controller 重同步节流中（累计抑制 ${resyncThrottle.suppressed}）topic=$topic")
            return
        }
        val flush = throttledTopics + topic
        throttledTopics = emptySet()
        for (t in flush) {
            val s = subscriptionByTopic[t] ?: continue
            val wm = reducer.watermarkOf(t)
            rpc.call(
                RpcChannel.CHANNEL_AGENT,
                "resyncControllerV4",
                listOf(
                    mapOf(
                        "subscriptionId" to s,
                        "base" to
                            mapOf(
                                "logEpoch" to (wm?.logEpoch ?: ""),
                                "toSeq" to (wm?.toSeq ?: 0L),
                            ),
                    ),
                ),
            ) { reply ->
                if (reply is RpcChannel.RpcReply.Err) {
                    // 重同步失败退化为「退订 → 重订」
                    ZLog.w(TAG, "controller resync 失败 topic=$t（退订重订）: ${reply.message}")
                    resubscribe(t)
                } else {
                    ZLog.i(TAG, "controller resync 已请求 topic=$t")
                }
            }
        }
    }

    private fun resubscribe(topic: String) {
        val sub = subscriptionByTopic.remove(topic) ?: return
        rpc.call(RpcChannel.CHANNEL_AGENT, "unsubscribeControllerV4", listOf(mapOf("subscriptionId" to sub)))
        rpc.call(
            RpcChannel.CHANNEL_AGENT,
            "subscribeControllerV4",
            listOf(mapOf("topic" to topic, "visibility" to VISIBILITY_FOREGROUND)),
        ) { reply ->
            when (reply) {
                is RpcChannel.RpcReply.Ok -> {
                    ControllerFrames.subscriptionIdOf(reply.data)?.let { subscriptionByTopic[topic] = it }
                    ZLog.i(TAG, "controller 重订成功 topic=$topic")
                }
                is RpcChannel.RpcReply.Err ->
                    ZLog.w(TAG, "controller 重订失败 topic=$topic: ${reply.message}")
            }
        }
    }

    /** 断开前退订（失败只记日志，不阻塞调用方）。 */
    fun unsubscribe() {
        for ((topic, sub) in subscriptionByTopic) {
            rpc.call(RpcChannel.CHANNEL_AGENT, "unsubscribeControllerV4", listOf(mapOf("subscriptionId" to sub))) { r ->
                if (r is RpcChannel.RpcReply.Err) ZLog.w(TAG, "controller 退订失败 topic=$topic: ${r.message}")
            }
        }
        reset()
    }

    fun reset() {
        listenId = null
        subscriptionByTopic.clear()
        resyncThrottle.reset()
        throttledTopics = emptySet()
        reducer.reset()
    }

    companion object {
        private const val TAG = "ControllerCh"
        const val EVENT_NAME = "onDynamicControllerFrame"
        const val VISIBILITY_FOREGROUND = "foreground"

        /** Controller 通道的两个 topic（桌面端 zod 常量，§11.6）。 */
        val CONTROLLER_TOPICS = listOf(ControllerReducer.TOPIC_TASKS, ControllerReducer.TOPIC_WORKSPACES)

        private val clientId = "android-controller-${java.util.UUID.randomUUID()}"

        /**
         * 该设备是否由 Controller 通道接管会话列表。
         *
         * 判据 = 协议栈为 V2（[ProtocolStack]）+ bootstrap 未下发 `tasks[]`。
         * V2 host 的 bootstrap 恒定不含 tasks，两项同时成立才接管，避免误接管 V1。
         */
        fun shouldTakeOver(
            stack: ProtocolStack,
            bootstrapTaskCount: Int,
        ): Boolean = stack == ProtocolStack.V2 && bootstrapTaskCount == 0
    }
}

/** task（会话）条目：`controller/tasks-index` 快照与 `task.upserted` 的元素。 */
data class ControllerTask(
    val taskId: String,
    val workspacePath: String? = null,
    val workspaceIdentity: String? = null,
    val remoteSessionId: String? = null,
    val title: String? = null,
    /** task meta 的 `status`（idle/running/…）。 */
    val status: String? = null,
    /** `liveStatus`：会话列表的实时状态（idle/running/waiting/completed/error）。 */
    val liveStatus: String? = null,
    val updatedAt: Long? = null,
    val createdAt: Long? = null,
    val provider: String? = null,
    /** 归档标志在 `membership.archived`（**不是**顶层）——桌面端 schema 实证。 */
    val archived: Boolean = false,
    val pinned: Boolean = false,
    val sourceAvailability: String? = null,
    val lastError: String? = null,
)

/** 工作区事实（`controller/workspaces`）。 */
data class ControllerWorkspace(
    val workspacePath: String,
    val workspaceIdentity: String? = null,
    val remoteSessionId: String? = null,
    val sourceAvailability: String? = null,
    val connectionState: String? = null,
) {
    val online: Boolean get() = sourceAvailability == "online"
}

/** Controller 帧的解析结果（裸帧形态，§11.6）。 */
internal data class ControllerFrame(
    val topic: String,
    val subscriptionId: String,
    val logEpoch: String,
    val fromSeq: Long?,
    val toSeq: Long?,
    val payloadKind: String,
    val payload: JsonObject,
)

/** 一帧作用到状态机后的结果。 */
internal sealed interface ReduceOutcome {
    data object Applied : ReduceOutcome

    /** 失同步（logEpoch 变化 / seq 缺口）：调用方应发起重同步。 */
    data class Desync(
        val topic: String,
        val reason: String,
        val detail: String,
    ) : ReduceOutcome
}

/** 同步水位：logEpoch + 已消费到的 toSeq。 */
internal data class ControllerWatermark(
    val logEpoch: String,
    val toSeq: Long,
)

/** 帧解析（纯函数）。 */
internal object ControllerFrames {
    /**
     * 裸帧形态：`{topic, subscriptionId, logEpoch, fromSeq, toSeq, payload:{kind, snapshot|deltas}}`
     * （桌面端 host 直接 `onFrame`，**没有** conversation 的 204 逻辑帧信封）。
     * 同时容忍 conversation 风格信封（以防服务端统一封装）。
     */
    fun parse(data: JsonObject): ControllerFrame? {
        parseBare(data)?.let { return it }
        val lf = ConversationFrames.parseLogicalFrame(data) ?: return null
        val tf = lf.frame ?: return null
        val payload = tf.payload
        return ControllerFrame(
            topic = tf.topic.ifBlank { lf.topic },
            subscriptionId = tf.subscriptionId.ifBlank { lf.subscriptionId },
            logEpoch = str(payload, "logEpoch") ?: "",
            fromSeq = tf.fromSeq,
            toSeq = tf.toSeq,
            payloadKind = tf.payloadKind,
            payload = payload,
        )
    }

    private fun parseBare(data: JsonObject): ControllerFrame? {
        val topic = str(data, "topic") ?: return null
        val sub = str(data, "subscriptionId") ?: return null
        val payload = data["payload"] as? JsonObject ?: return null
        return ControllerFrame(
            topic = topic,
            subscriptionId = sub,
            logEpoch = str(data, "logEpoch") ?: "",
            fromSeq = lng(data, "fromSeq"),
            toSeq = lng(data, "toSeq"),
            payloadKind = str(payload, "kind") ?: "",
            payload = payload,
        )
    }

    /** `subscribeControllerV4` 的 ack：`{ack:{subscriptionId}}`（宽松兼容顶层）。 */
    fun subscriptionIdOf(root: JsonElement?): String? {
        val obj = root as? JsonObject ?: return null
        (obj["ack"] as? JsonObject)?.let { ack -> str(ack, "subscriptionId")?.let { return it } }
        return str(obj, "subscriptionId")
    }

    fun str(
        o: JsonObject,
        k: String,
    ): String? = o[k]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

    fun lng(
        o: JsonObject,
        k: String,
    ): Long? = o[k]?.let { runCatching { it.jsonPrimitive.longOrNull }.getOrNull() }

    fun bool(
        o: JsonObject,
        k: String,
    ): Boolean? = o[k]?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() }
}

/**
 * Controller 状态机（无 IO）：快照全量替换、增量覆盖/删除、水位与失同步判定。
 *
 * 失同步判据（两者任一成立）：
 * - `logEpoch` 变化（服务端日志重置/PC 重启）—— 必须整份重取；
 * - `fromSeq > 上次 toSeq`（缺口）—— 中间帧已丢，同样整份重取。
 * 与 [SessionsIndexChannel] 的差异是这里要主动 resync（那边只依赖下一次快照）。
 *
 * [log] 是可注入的日志器：状态机本身是纯逻辑，JVM 单测跑在无 android.util.Log 的环境里，
 * 不注入时用 [ZLog]（生产路径）。
 */
internal class ControllerReducer(
    private val log: (String, String) -> Unit = { tag, msg -> ZLog.i(tag, msg) },
) {
    private val _tasks = MutableStateFlow<Map<String, ControllerTask>>(emptyMap())
    val tasks: StateFlow<Map<String, ControllerTask>> = _tasks

    private val _workspaces = MutableStateFlow<List<ControllerWorkspace>>(emptyList())
    val workspaces: StateFlow<List<ControllerWorkspace>> = _workspaces

    private val _archivedCount = MutableStateFlow(0)
    val archivedCount: StateFlow<Int> = _archivedCount

    private val watermarks = mutableMapOf<String, ControllerWatermark>()

    fun watermarkOf(topic: String): ControllerWatermark? = watermarks[topic]

    fun apply(frame: ControllerFrame): ReduceOutcome {
        when (frame.payloadKind) {
            "snapshot" -> applySnapshot(frame)
            "deltas" -> applyDeltas(frame)
            else -> log(TAG, "controller 未知 payload kind=${frame.payloadKind}（忽略）")
        }
        return checkSync(frame)
    }

    private fun applySnapshot(frame: ControllerFrame) {
        val snap = frame.payload["snapshot"] as? JsonObject ?: return
        when (frame.topic) {
            TOPIC_TASKS -> {
                val arr = snap["tasks"] as? JsonArray ?: return
                val next = HashMap<String, ControllerTask>(arr.size)
                for (el in arr) {
                    val o = el as? JsonObject ?: continue
                    parseTask(o)?.let { next[it.taskId] = it }
                }
                _tasks.value = next
                _archivedCount.value = next.values.count { it.archived }
                log(TAG, "controller snapshot: ${next.size} 任务（归档 ${_archivedCount.value}）")
            }
            TOPIC_WORKSPACES -> {
                val arr = snap["workspaces"] as? JsonArray ?: return
                _workspaces.value = arr.mapNotNull { (it as? JsonObject)?.let(::parseWorkspace) }
                log(TAG, "controller snapshot: ${_workspaces.value.size} 工作区")
            }
        }
    }

    private fun applyDeltas(frame: ControllerFrame) {
        val deltas = frame.payload["deltas"] as? JsonArray ?: return
        when (frame.topic) {
            TOPIC_TASKS -> applyTaskDeltas(deltas)
            TOPIC_WORKSPACES -> applyWorkspaceDeltas(deltas)
        }
    }

    /**
     * 应用任务增量。只在真正命中一条 op 时才复制 Map：空帧或纯未知 op 不产生新实例，
     * 避免无谓的 UI 重组。
     */
    private fun applyTaskDeltas(deltas: JsonArray) {
        var next: Map<String, ControllerTask>? = null
        for (d in deltas) {
            val o = d as? JsonObject ?: continue
            val applied = applySingleTaskOp(o, next ?: _tasks.value)
            if (applied != null) next = applied
        }
        if (next != null) commitTasks(next)
    }

    /** 单条任务 op 返回新 Map；未命中返回 null（调用方保留原值）。 */
    private fun applySingleTaskOp(
        op: JsonObject,
        cur: Map<String, ControllerTask>,
    ): Map<String, ControllerTask>? =
        when (val name = ControllerFrames.str(op, "op")) {
            "task.upserted" -> (op["task"] as? JsonObject)?.let(::parseTask)?.let { cur + (it.taskId to it) }
            // task.removed 的载荷是 Address（{workspacePath, taskId, …}），不是完整 task
            "task.removed" -> taskIdOfRemoved(op)?.let { cur - it }
            null -> null
            else -> {
                log(TAG, "controller unknown task op=$name (ignored)")
                null
            }
        }

    private fun taskIdOfRemoved(op: JsonObject): String? = (op["address"] as? JsonObject)?.let { ControllerFrames.str(it, "taskId") }

    private fun commitTasks(next: Map<String, ControllerTask>) {
        _tasks.value = next
        _archivedCount.value = next.values.count { t -> t.archived }
    }

    private fun applyWorkspaceDeltas(deltas: JsonArray) {
        var next: List<ControllerWorkspace>? = null
        for (d in deltas) {
            val o = d as? JsonObject ?: continue
            val applied = applySingleWorkspaceOp(o, next ?: _workspaces.value)
            if (applied != null) next = applied
        }
        if (next != null) _workspaces.value = next
    }

    private fun applySingleWorkspaceOp(
        op: JsonObject,
        cur: List<ControllerWorkspace>,
    ): List<ControllerWorkspace>? =
        when (ControllerFrames.str(op, "op")) {
            "workspace.upserted" -> (op["workspace"] as? JsonObject)?.let(::parseWorkspace)?.let { upsertWorkspace(cur, it) }
            "workspace.removed" ->
                ControllerFrames.str(op, "workspacePath")?.let { path -> cur.filterNot { it.workspacePath == path } }
            else -> null
        }

    private fun upsertWorkspace(
        cur: List<ControllerWorkspace>,
        ws: ControllerWorkspace,
    ): List<ControllerWorkspace> {
        val idx = cur.indexOfFirst { it.workspacePath == ws.workspacePath }
        return if (idx >= 0) cur.toMutableList().also { it[idx] = ws } else cur + ws
    }

    private fun parseTask(o: JsonObject): ControllerTask? {
        val address = o["address"] as? JsonObject
        val meta = o["meta"] as? JsonObject
        val membership = o["membership"] as? JsonObject
        // taskId 以 address 为准（strict schema）；顶层兜底防未来形态变化丢条目
        val taskId = taskIdOf(address, o) ?: return null
        return ControllerTask(
            taskId = taskId,
            workspacePath = workspacePathOf(address, meta),
            workspaceIdentity = addrStr(address, "workspaceIdentity"),
            remoteSessionId = addrStr(address, "remoteSessionId"),
            title = metaStr(meta, "title")?.let { SessionItem.unwrapJsonTitle(it) },
            status = metaStr(meta, "status"),
            liveStatus = ControllerFrames.str(o, "liveStatus"),
            updatedAt = meta?.let { ControllerFrames.lng(it, "updatedAt") },
            createdAt = meta?.let { ControllerFrames.lng(it, "createdAt") },
            provider = metaStr(meta, "provider"),
            archived = membership?.let { ControllerFrames.bool(it, "archived") } ?: false,
            pinned = membership?.let { ControllerFrames.bool(it, "pinned") } ?: false,
            sourceAvailability = ControllerFrames.str(o, "sourceAvailability"),
            lastError = meta?.let { SessionItem.errorText(it["lastError"]) },
        )
    }

    private fun taskIdOf(
        address: JsonObject?,
        o: JsonObject,
    ): String? = addrStr(address, "taskId") ?: ControllerFrames.str(o, "taskId")

    private fun workspacePathOf(
        address: JsonObject?,
        meta: JsonObject?,
    ): String? = addrStr(address, "workspacePath") ?: metaStr(meta, "workspacePath")

    private fun addrStr(
        o: JsonObject?,
        k: String,
    ): String? = o?.let { ControllerFrames.str(it, k) }

    private fun metaStr(
        o: JsonObject?,
        k: String,
    ): String? = o?.let { ControllerFrames.str(it, k) }

    private fun parseWorkspace(o: JsonObject): ControllerWorkspace? {
        val path = ControllerFrames.str(o, "workspacePath") ?: return null
        return ControllerWorkspace(
            workspacePath = path,
            workspaceIdentity = ControllerFrames.str(o, "workspaceIdentity"),
            remoteSessionId = ControllerFrames.str(o, "remoteSessionId"),
            sourceAvailability = ControllerFrames.str(o, "sourceAvailability"),
            connectionState = ControllerFrames.str(o, "connectionState"),
        )
    }

    private fun checkSync(frame: ControllerFrame): ReduceOutcome {
        val prev = watermarks[frame.topic]
        val toSeq = frame.toSeq ?: prev?.toSeq ?: 0L
        watermarks[frame.topic] = ControllerWatermark(logEpoch = frame.logEpoch, toSeq = toSeq)
        if (prev == null) return ReduceOutcome.Applied
        val epochChanged =
            prev.logEpoch.isNotEmpty() && frame.logEpoch.isNotEmpty() && prev.logEpoch != frame.logEpoch
        val fromSeq = frame.fromSeq
        val seqGap = fromSeq != null && prev.toSeq > 0L && fromSeq > prev.toSeq
        return when {
            epochChanged ->
                ReduceOutcome.Desync(
                    frame.topic,
                    "logEpoch",
                    "prev=${prev.logEpoch} now=${frame.logEpoch}",
                )
            seqGap ->
                ReduceOutcome.Desync(
                    frame.topic,
                    "seq-gap",
                    "prevToSeq=${prev.toSeq} fromSeq=$fromSeq",
                )
            else -> ReduceOutcome.Applied
        }
    }

    fun reset() {
        watermarks.clear()
        _tasks.value = emptyMap()
        _workspaces.value = emptyList()
        _archivedCount.value = 0
    }

    companion object {
        private const val TAG = "ControllerReducer"
        const val TOPIC_TASKS = "controller/tasks-index"
        const val TOPIC_WORKSPACES = "controller/workspaces"

        /** task → [SessionItem]（纯函数）：archived 直取 membership，title 缺省退 taskId。 */
        fun toSessionItem(task: ControllerTask): SessionItem =
            SessionItem(
                taskId = task.taskId,
                title = task.title?.takeIf { it.isNotBlank() } ?: task.taskId,
                displayStatus = task.liveStatus ?: task.status ?: "unknown",
                workspacePath = task.workspacePath,
                workspaceLabel = null,
                provider = task.provider,
                updatedAt = task.updatedAt,
                archived = task.archived,
                lastError = task.lastError,
            )

        /**
         * 列表视图：过滤归档、按 updatedAt 降序（缺值垫底，同值按 taskId 稳定排序），
         * 与 V1 bootstrap 的归档过滤 + 列表排序语义一致。
         */
        fun visibleSessions(tasks: Map<String, ControllerTask>): List<SessionItem> =
            tasks.values
                .filterNot { it.archived }
                .map(::toSessionItem)
                .sortedWith(
                    compareByDescending<SessionItem> { it.updatedAt ?: Long.MIN_VALUE }
                        .thenBy { it.taskId },
                )
    }
}
