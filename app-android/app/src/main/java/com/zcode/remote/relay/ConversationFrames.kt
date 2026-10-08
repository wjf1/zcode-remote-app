package com.zcode.remote.relay

import com.zcode.remote.util.ZLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 会话流（conversation）帧解析与行模型。
 *
 * 规格见 research/CONVERSATION-PROTOCOL.md（真机实测确认）：
 * 204 → 逻辑帧信封 → topic 帧 → snapshot（全量，尾窗）/ deltas（增量）。
 */
object ConversationFrames {
    private const val TAG = "ConvFrames"

    private fun JsonObject.str(k: String): String? =
        this[k]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

    private fun JsonObject.int(k: String): Int? =
        this[k]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }

    private fun JsonObject.long(k: String): Long? =
        this[k]?.let { runCatching { it.jsonPrimitive.longOrNull }.getOrNull() }

    private fun JsonObject.obj(k: String): JsonObject? =
        this[k]?.let { runCatching { it.jsonObject }.getOrNull() }

    private fun JsonObject.bool(k: String): Boolean? =
        this[k]?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() }

    /** 逻辑帧信封（204 的 data），kind=complete 时 [frame] 非空。 */
    data class LogicalFrame(
        val wireVersion: Int?,
        val kind: String,             // complete | fragment
        val deliveryKind: String?,    // initial | online | recovery
        val logicalFrameId: String,
        val ordinal: Int?,
        val topic: String,
        val subscriptionId: String,
        val frame: TopicFrame?,
    )

    /** topic 帧：fromSeq/toSeq 单调推进；payload 是 snapshot 或 deltas。 */
    data class TopicFrame(
        val topic: String,
        val subscriptionId: String,
        val fromSeq: Long?,
        val toSeq: Long?,
        val payload: JsonObject,      // {kind:"snapshot"|"deltas", ...}
    ) {
        val payloadKind: String get() = payload.str("kind") ?: ""
    }

    fun parseLogicalFrame(data: JsonObject): LogicalFrame? {
        val kind = data.str("kind") ?: return null
        val topic = data.str("topic") ?: return null
        val subId = data.str("subscriptionId") ?: return null
        val frameObj = data.obj("frame")
        return LogicalFrame(
            wireVersion = data.int("wireVersion"),
            kind = kind,
            deliveryKind = data.str("deliveryKind"),
            logicalFrameId = data.str("logicalFrameId") ?: "",
            ordinal = data.int("logicalFrameOrdinal"),
            topic = topic,
            subscriptionId = subId,
            frame = frameObj?.let { parseTopicFrame(it) },
        )
    }

    private fun parseTopicFrame(o: JsonObject): TopicFrame? {
        val payload = o.obj("payload") ?: return null
        return TopicFrame(
            topic = o.str("topic") ?: "",
            subscriptionId = o.str("subscriptionId") ?: "",
            fromSeq = o.long("fromSeq"),
            toSeq = o.long("toSeq"),
            payload = payload,
        )
    }

    /** 快照顶层：取尾窗行 + 关键元信息。 */
    data class Snapshot(
        val sessionId: String?,
        val logEpoch: String?,
        val seq: Long?,
        val title: String?,
        val phase: String?,
        val rows: List<ConversationRow>,
        val totalCount: Int?,
        /** 待审批交互（permission），整组给出。 */
        val pendingInteractions: List<PendingApproval> = emptyList(),
        /** 待应答表单交互（pendingInteractions 里 kind=="userInput" 的条目）。 */
        val elicitations: List<PendingElicitation> = emptyList(),
        /** control 块（运行/停止状态由服务端算好下发）。 */
        val control: Control? = null,
        /** 快照 config 块的当前模型（PC 端实际在用的 model，如 "glm-4.5"）。 */
        val configModel: String? = null,
        /** 快照 config 块的当前供应商 providerId。 */
        val configProvider: String? = null,
        /** 当前状态版本号（CAS 命令 switchModelConfig 必须带上 baseRevision）。 */
        val revision: Long? = null,
        /** 会话级状态块（上下文容量 / 排队 / 后台任务 / 子智能体 / 目标 / 计划）。 */
        val sessionState: SessionState = SessionState(),
    )

    /**
     * control 块：官方 schema `canStop: boolean / stopState: idle|stoppable|stopping /
     * activeWorks[]`（host asar zod 实证）。停止按钮的显示条件就是 `canStop`；
     * `foregroundExecutionId` 是 activeWorks 里的前台执行 id，stop 命令带上可防误停。
     * `lastError`：会话级错误原因（host 侧 task meta 同名字段；桌面端在会话列表显示的就是它），
     * 文档样式中健康会话为 `null`（research/CONVERSATION-PROTOCOL.md:159）。
     */
    data class Control(
        val phase: String?,
        val canStop: Boolean?,
        val stopState: String?,
        val foregroundExecutionId: String?,
        val lastError: String? = null,
    )

    fun parseControl(o: JsonObject?): Control? {
        if (o == null) return null
        return Control(
            phase = o.str("phase"),
            canStop = o.bool("canStop"),
            stopState = o.str("stopState"),
            foregroundExecutionId = o["activeWorks"]?.let { runCatching { it.jsonArray }.getOrNull() }
                ?.firstNotNullOfOrNull { el ->
                    runCatching { el.jsonObject["foregroundExecutionId"]?.jsonPrimitive?.content }.getOrNull()
                },
            lastError = SessionItem.errorText(o["lastError"]),
        )
    }

    // ------------------------------------------------------------------
    // 会话级状态块：快照顶层 usage / queue / backgroundWorks / subagents / goal / plan
    //
    // 这些块与「行」是两套数据：行是历史，这些是**当前状态**（上下文占用、排队输入、
    // 后台任务、目标、计划）。桌面端把它们渲染在右侧状态面板与顶栏上下文指示器里。
    //
    // 形状依据：真机快照骨架（research/CONVERSATION-FRAME-SKELETON.txt:53-69，逐块 arity 实测）
    // + 桌面包解析代码（index-nOVzQNKW.js 的 `Fe`：usage.contextWindow → {used,size,cache,breakdown}）。
    // 取不到的块一律 null，面板据此隐藏对应分区。
    //
    // **不解析 availability 块**：它只服务于控制类命令的可用性门禁（fork/compact/queueEdit…），
    // 而状态面板是纯只读的、不新增任何控制命令，解析出来也无处可用。
    // ------------------------------------------------------------------

    /** 上下文来源明细（`usage.contextWindow.breakdown[]`）。 */
    data class ContextSource(val source: String, val chars: Long)

    /**
     * 上下文容量。`cache.hitRate` 缺失时为 null —— 桌面端只在命中率 ≥78% 时渲染该行
     * （阈值 `EZe = 0.78`），面板沿用同一门限。
     */
    data class ContextUsage(
        val usedTokens: Long?,
        val maxTokens: Long?,
        val cacheHitRate: Double?,
        val breakdown: List<ContextSource> = emptyList(),
    ) {
        /** 占用比 0..1；窗口未知或 ≤0 时为 null（面板据此不画进度条）。 */
        val ratio: Double?
            get() {
                val used = usedTokens ?: return null
                val max = maxTokens ?: return null
                if (max <= 0L) return null
                return (used.toDouble() / max.toDouble()).coerceIn(0.0, 1.0)
            }

        /** 桌面端门限：低于 0.78 整行不显示（不是「显示 0%」）。 */
        fun cacheHitRateIfNotable(): Double? = cacheHitRate?.takeIf { it >= CACHE_HIT_RATE_THRESHOLD }

        /**
         * 各来源按占比降序。占比基数是 breakdown 内 chars 之和（不是 usedTokens）——
         * 与桌面端 `AZe` 一致：chars 是唯一口径，usedTokens 是另一个量纲（含缓存）。
         */
        fun breakdownPercentages(): List<ContextBreakdownSlice> {
            val positive = breakdown.filter { it.chars > 0L }
            val total = positive.sumOf { it.chars }
            if (total <= 0L) return emptyList()
            return positive
                .sortedWith(compareByDescending<ContextSource> { it.chars }.thenBy { sourceRank(it.source) })
                .map { ContextBreakdownSlice(it, it.chars.toDouble() / total.toDouble()) }
        }

        companion object {
            fun from(o: JsonObject?): ContextUsage? {
                if (o == null) return null
                val usage = ContextUsage(
                    usedTokens = o.long("usedTokens"),
                    maxTokens = o.long("maxTokens"),
                    cacheHitRate = o.obj("cache")?.let { c ->
                        c["hitRate"]?.let { runCatching { it.jsonPrimitive.content.toDoubleOrNull() }.getOrNull() }
                    },
                    breakdown = o["breakdown"]?.let { runCatching { it.jsonArray }.getOrNull() }
                        ?.mapNotNull { el ->
                            runCatching { el.jsonObject }.getOrNull()?.let { b ->
                                val src = b.str("source")
                                val chars = b.long("chars")
                                if (src == null || chars == null) null else ContextSource(src, chars)
                            }
                        } ?: emptyList(),
                )
                // 全空（服务端还没算出来）不占分区
                return usage.takeIf { it.usedTokens != null || it.maxTokens != null || it.breakdown.isNotEmpty() }
            }
        }
    }

    /** 一条来源占比（面板直接渲染，避免 UI 层再算一次）。 */
    data class ContextBreakdownSlice(val source: ContextSource, val ratio: Double)

    /** 来源排序兜底：chars 相同时按桌面端固定序（`AI`），未知来源排最后。 */
    private val SOURCE_RANK = listOf(
        "messages", "system_prompt", "meta_user_context", "skills",
        "tool_prompt", "system_tool_schemas", "mcp_tool_schemas",
    )

    private fun sourceRank(source: String): Int =
        SOURCE_RANK.indexOf(source).let { if (it < 0) SOURCE_RANK.size else it }

    /** 桌面端缓存命中率显示门限（`EZe = 0.78`）。 */
    const val CACHE_HIT_RATE_THRESHOLD = 0.78

    /** 排队中的后续输入（`queue`）。只读展示「待发送 N 条」。 */
    data class QueueState(val itemCount: Int, val autoDrain: Boolean?) {
        companion object {
            fun from(o: JsonObject?): QueueState? {
                if (o == null) return null
                val count = o["items"]?.let { runCatching { it.jsonArray.size }.getOrNull() } ?: 0
                return QueueState(itemCount = count, autoDrain = o.bool("autoDrain"))
            }
        }
    }

    /** 一项后台工作（`backgroundWorks[]`，`kind ∈ {bash, subagent}`）。 */
    data class BackgroundWork(
        val kind: String?,
        val status: String?,
        val childSessionId: String?,
        val workId: String?,
    ) {
        val isRunning: Boolean get() = status == "running"
    }

    data class BackgroundWorksState(val works: List<BackgroundWork>) {
        /** 桌面端统计口径：只算 status=="running"（`DBe`）。 */
        val running: List<BackgroundWork> get() = works.filter { it.isRunning }
        val bashRunningCount: Int get() = running.count { it.kind == "bash" }

        companion object {
            fun from(el: JsonElement?): BackgroundWorksState? {
                val arr = el?.let { runCatching { it.jsonArray }.getOrNull() } ?: return null
                val works = arr.mapNotNull { item ->
                    runCatching { item.jsonObject }.getOrNull()?.let { w ->
                        BackgroundWork(
                            kind = w.str("kind"),
                            status = w.str("status"),
                            childSessionId = w.str("childSessionId"),
                            workId = w.str("workId"),
                        )
                    }
                }
                return BackgroundWorksState(works)
            }
        }
    }

    /**
     * 子智能体投影：`running` 是正在跑的子会话 id 列表，`endedTotal` 是历史累计结束数。
     * 桌面端「N 运行 / N 已结束」就是这两项。
     */
    data class SubagentsState(
        val runningChildSessionIds: List<String>,
        val endedTotal: Long?,
        val revision: Long?,
    ) {
        val runningCount: Int get() = runningChildSessionIds.size

        companion object {
            fun from(o: JsonObject?): SubagentsState? {
                if (o == null) return null
                return SubagentsState(
                    // running[] 的元素是对象（取 childSessionId）；顺带容错收字符串形态
                    runningChildSessionIds = o["running"]?.let { runCatching { it.jsonArray }.getOrNull() }
                        ?.mapNotNull { el ->
                            runCatching { el.jsonObject }.getOrNull()?.str("childSessionId")
                                ?: runCatching { el.jsonPrimitive.content }.getOrNull()
                        } ?: emptyList(),
                    endedTotal = o.long("endedTotal"),
                    revision = o.long("revision"),
                )
            }
        }
    }

    /**
     * 会话目标（`goal`）。空闲会话为 null。
     *
     * `status` 取值（桌面包序 `JW` 实证）：`active` / `verifying` / `notSatisfied` 三态属于
     * **仍在进行**，此时面板的秒数必须把 `(now - activeRunStartedAtMs)/1000` 叠加到
     * `timeUsedSeconds` 上，否则目标跑到一半时显示的耗时是冻结的（服务端只在里程碑打点落库）。
     */
    data class GoalState(
        val objective: String?,
        val summaryTitle: String?,
        val status: String?,
        val tokenBudget: Long?,
        val tokensUsed: Long?,
        val timeUsedSeconds: Long?,
        /** 本轮开始时刻（毫秒）。仅在 [isRunning] 为真时有意义。 */
        val activeRunStartedAtMs: Long? = null,
    ) {
        /** 是否仍在推进 —— 决定耗时要不要继续走秒。 */
        val isRunning: Boolean get() = status != null && status in RUNNING_STATUSES

        /** 展示用耗时（秒）：进行中叠加从本轮开始到现在的增量，已结束返回落库值。 */
        fun elapsedSeconds(nowMs: Long): Long {
            val base = (timeUsedSeconds ?: 0L).coerceAtLeast(0L)
            val started = activeRunStartedAtMs
            if (!isRunning || started == null || nowMs <= started) return base
            return base + (nowMs - started) / 1000L
        }

        companion object {
            /** 桌面端认定的「还在跑」状态集合。 */
            val RUNNING_STATUSES = setOf("active", "verifying", "notSatisfied")

            fun from(o: JsonObject?): GoalState? {
                if (o == null) return null
                val g = GoalState(
                    objective = o.str("objective"),
                    summaryTitle = o.str("summaryTitle"),
                    status = o.str("status"),
                    tokenBudget = o.long("tokenBudget"),
                    tokensUsed = o.long("tokensUsed"),
                    timeUsedSeconds = o.long("timeUsedSeconds"),
                    activeRunStartedAtMs = o.long("activeRunStartedAtMs"),
                )
                // 全空视为无目标：服务端下发空对象时不该顶出一个空分区
                return g.takeIf { it.objective != null || it.summaryTitle != null || it.status != null }
            }
        }
    }

    /**
     * 待办项（`plan.items[]`）。
     *
     * 桌面端渲染待办时逐项读 `{id, content, status}`（`e.id`/`e.content`/`e.status`，桌面包实证），
     * 所以这里按对象解析；同时容忍服务端下发裸字符串 —— 字符串项按「无状态」处理，
     * 这样的项既不算完成也不会被折叠丢弃。
     */
    data class PlanTodo(val id: String?, val content: String, val status: String?) {
        val isCompleted: Boolean get() = status == COMPLETED_STATUS

        companion object {
            const val COMPLETED_STATUS = "completed"

            fun from(el: JsonElement): PlanTodo? {
                runCatching { el.jsonObject }.getOrNull()?.let { o ->
                    val content = o.str("content") ?: o.str("text") ?: o.str("title")
                    if (content.isNullOrBlank()) return null
                    return PlanTodo(id = o.str("id"), content = content, status = o.str("status"))
                }
                val s = runCatching { el.jsonPrimitive.content }.getOrNull()
                return s?.takeIf { it.isNotBlank() }?.let { PlanTodo(id = null, content = it, status = null) }
            }
        }
    }

    /**
     * 会话待办（`plan`）。`items` 为空视为无计划（不占分区）。
     *
     * **这是桌面端「进程」分区的数据源。** 桌面端还有一个独立的「计划」分区
     * （`chat.statusPanel.sessionPlans`），其数据来自**另一条 RPC** `conversationPlansV4`
     * （桌面包实证 `transport.plans({sessionId})`），本 App 没有订阅它、也不新增查询命令，
     * 故面板不提供该分区 —— 不为了凑版式去开一条新 RPC。
     */
    data class PlanState(val items: List<PlanTodo>, val updatedAt: Long?) {
        val totalCount: Int get() = items.size
        val completedCount: Int get() = items.count { it.isCompleted }
        val allCompleted: Boolean get() = items.isNotEmpty() && completedCount == totalCount

        /**
         * 待办分区的展示切分：未完成项优先、已完成项垫底（桌面端「待处理 / 已完成」两组），
         * 超出上限的项折叠成计数 —— 面板要短，长计划不能把状态面板撑成一整屏。
         *
         * 规则（[limit] 为可见项上限，默认 6）：
         * - 未完成项先占位；未完成项本身就超限时，尾部未完成项计入 [PlanDisplay.foldedTail]
         * - 未完成项未占满时，用已完成项回填剩余位置
         * - 没展示到的已完成项计入 [PlanDisplay.foldedCompleted]
         */
        fun display(limit: Int = DISPLAY_LIMIT): PlanDisplay {
            val pending = items.filterNot { it.isCompleted }
            val completed = items.filter { it.isCompleted }
            val pendingShown = pending.take(limit)
            val rest = limit - pendingShown.size
            val completedShown = if (rest > 0) completed.take(rest) else emptyList()
            return PlanDisplay(
                visible = pendingShown + completedShown,
                foldedCompleted = completed.size - completedShown.size,
                foldedTail = pending.size - pendingShown.size,
            )
        }

        companion object {
            /** 桌面端待办分区的折叠阈值。 */
            const val DISPLAY_LIMIT = 6

            fun from(o: JsonObject?): PlanState? {
                if (o == null) return null
                val todos = o["items"]?.let { runCatching { it.jsonArray }.getOrNull() }
                    ?.mapNotNull { PlanTodo.from(it) } ?: emptyList()
                // 没有待办的「计划」不占分区位置
                return PlanState(items = todos, updatedAt = o.long("updatedAt")).takeIf { todos.isNotEmpty() }
            }
        }
    }

    /** [PlanState.display] 的结果：可见项 + 被折叠的已完成项数 + 被截掉的尾部未完成项数。 */
    data class PlanDisplay(
        val visible: List<PlanTodo>,
        val foldedCompleted: Int,
        val foldedTail: Int,
    )

    /**
     * 会话级状态的聚合视图。快照给全量、`state.updated` 的 patch 给增量，两者共用同一套解析。
     */
    data class SessionState(
        val usage: ContextUsage? = null,
        val queue: QueueState? = null,
        val backgroundWorks: BackgroundWorksState? = null,
        val subagents: SubagentsState? = null,
        val goal: GoalState? = null,
        val plan: PlanState? = null,
    ) {
        /** 是否有可展示内容 —— 面板与顶栏入口的显示条件（全空时不出现）。 */
        val hasContent: Boolean
            get() = usage != null || (queue?.itemCount ?: 0) > 0 ||
                backgroundWorks?.works?.isNotEmpty() == true || subagents != null || goal != null || plan != null

        /**
         * 增量合并：**patch 里出现的键整块替换，未出现的键原样保留**
         * （桌面端 `state.updated{patch}` 的语义 —— 只推变化的那几块）。
         * 键出现但值为 JSON null → 服务端在清除该块，置空。
         */
        fun merge(patch: JsonObject): SessionState = copy(
            usage = if (patch.containsKey("usage")) {
                patch.obj("usage")?.let { ContextUsage.from(it.obj("contextWindow")) }
            } else usage,
            queue = if (patch.containsKey("queue")) QueueState.from(patch.obj("queue")) else queue,
            backgroundWorks = if (patch.containsKey("backgroundWorks")) {
                BackgroundWorksState.from(patch["backgroundWorks"])
            } else backgroundWorks,
            subagents = if (patch.containsKey("subagents")) SubagentsState.from(patch.obj("subagents")) else subagents,
            goal = if (patch.containsKey("goal")) GoalState.from(patch.obj("goal")) else goal,
            plan = if (patch.containsKey("plan")) PlanState.from(patch.obj("plan")) else plan,
        )

        companion object {
            fun from(o: JsonObject?): SessionState = if (o == null) SessionState() else SessionState().merge(o)
        }
    }

    fun parseSnapshot(payload: JsonObject): Snapshot? {
        val snap = payload.obj("snapshot") ?: return null
        val rowsObj = snap.obj("rows")
        val control = parseControl(snap.obj("control"))
        // 快照 config 块：PC 端当前模型与供应商（真机帧实证每帧都带）
        val cfg = snap.obj("config")
        return Snapshot(
            sessionId = snap.str("sessionId"),
            logEpoch = snap.str("logEpoch"),
            seq = snap.long("seq"),
            // 标题防御性解包：host 曾把标题双重序列化成 {"title":"…"}（tasks-index 实锤），解出内层
            title = SessionItem.unwrapJsonTitle(snap.obj("meta")?.str("title")),
            phase = control?.phase,
            rows = rowsObj?.get("window")?.let { w ->
                runCatching { w.jsonArray }.getOrNull()
                    ?.mapNotNull { el -> runCatching { ConversationRow.from(el.jsonObject) }.getOrNull() }
            } ?: emptyList(),
            totalCount = rowsObj?.int("totalCount"),
            pendingInteractions = PendingApproval.parseFrom(snap, snap.str("sessionId")),
            elicitations = PendingElicitation.parseArray(
                runCatching { snap["pendingInteractions"]?.jsonArray }.getOrNull(),
                snap.str("sessionId")),
            control = control,
            configModel = cfg?.str("model"),
            configProvider = cfg?.str("provider"),
            revision = snap.long("revision") ?: snap.long("seq"),
            sessionState = SessionState.from(snap),
        )
    }

    /** 增量操作。 */
    sealed interface Delta {
        data class Upsert(val row: ConversationRow) : Delta
        data class RemoveFrom(val fromRowId: Int) : Delta
        data class AppendText(val rowId: Int, val path: String, val append: String) : Delta
        /** 会话状态补丁；`patch.pendingInteractions` 是**整组替换**（审批请求/消解都走这里）。 */
        data class StateUpdated(val patch: JsonObject?) : Delta
        data class Unknown(val op: String) : Delta
    }

    fun parseDeltas(payload: JsonObject): List<Delta> {
        val arr = payload["deltas"]?.let { runCatching { it.jsonArray }.getOrNull() } ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            when (val op = o.str("op")) {
                "row.appended", "row.upserted" ->
                    o.obj("row")?.let { r -> runCatching { ConversationRow.from(r) }.getOrNull() }?.let { Delta.Upsert(it) }
                "row.removed" -> o.int("fromRowId")?.let { Delta.RemoveFrom(it) }
                "row.delta" -> {
                    val rowId = o.int("rowId")
                    val append = o.str("append")
                    if (rowId != null && append != null) {
                        Delta.AppendText(rowId, o.str("path") ?: "text", append)
                    } else null
                }
                "state.updated" -> Delta.StateUpdated(o.obj("patch"))
                null -> null
                else -> {
                    ZLog.i(TAG, "未处理的 delta op=$op")
                    Delta.Unknown(op)
                }
            }
        }
    }
}

/**
 * 行携带的附件。仅在 `userInput` 行出现（服务端回显），形状与官方 web `attachmentRef` 同形：
 * `{ref, fileName, mime, bytes}` —— 依据 `PROTOCOL.md` §6.6 的实测结论，并由真机缓存里的
 * 真实行数据复核（样本见 `CHANGELOG.md` 的 userInput 附件渲染条目）。
 *
 * [ref] 是 host 侧暂存引用（`zcode-artifact://…`），**不是**工作区文件路径，
 * 故当前只用于展示，不可走 `file.readTextFile` 预览。
 */
@Serializable
data class RowAttachment(
    val ref: String? = null,
    val fileName: String? = null,
    val mime: String? = null,
    val bytes: Long? = null,
)

/**
 * 行属性的宽松取值：非字符串标量（数字/布尔）也能取到字面量，`JsonNull` 会抛异常、被静默吞掉。
 * 协议字段形态未穷举，一条畸形行不该让整帧解析失败。
 */
private fun JsonObject.rowStr(key: String): String? =
    this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

private fun JsonObject.rowLong(key: String): Long? =
    this[key]?.let { runCatching { it.jsonPrimitive.longOrNull }.getOrNull() }

/**
 * 会话行。角色由 [kind] 表达，没有统一的 role 字段。
 *
 * kind: userInput / assistantText / reasoning / toolCall / turnHeader / subagent / hookInvocation / timelineMarker
 */
@Serializable
data class ConversationRow(
    val rowId: Int,
    val kind: String,
    val turnId: String? = null,
    val text: String? = null,
    val state: String? = null,
    val toolName: String? = null,
    val toolCallId: String? = null,
    val status: String? = null,
    val inputText: String? = null,
    val outputText: String? = null,
    val createdAt: Long? = null,
    /**
     * 流式摘要（wire 的 `summaryText`）。agent / 子智能体这类行只有摘要没有正文，
     * 折叠态就渲染它。**注意**：此前 `RowStore.appendText` 把 `summaryText` 当未知 path 丢弃，
     * 导致该字段永远停在首帧的值，已一并修复（见同文件下方的 `appendText`）。
     */
    val summaryText: String? = null,
    /** 行的来源标记（桌面端轮次头「轮次 · 来源 · 状态」里的来源）。 */
    val origin: String? = null,
    /** 轮次/行的起止时间与累计活跃时长（毫秒），用于「已工作 X分Y秒」。 */
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val activeMs: Long? = null,
    /** 行自身耗时（部分工具行直接下发）。 */
    val durationMs: Long? = null,
    /** 工具执行失败的错误文本（`output.error` / `error`），展开时按危险色渲染。 */
    val errorText: String? = null,
    /** 该行携带的附件（userInput 行回显）。默认空 = 无附件。 */
    val attachments: List<RowAttachment> = emptyList(),
    /**
     * 原始行对象。审批等结构化字段（选项数组、requestId 等）形态尚未穷举，
     * 这里保留整包避免二次改数据类——取值见 [com.zcode.remote.relay.PendingApproval]。
     */
    val raw: JsonObject? = null,
) {
    val isUser: Boolean get() = kind == "userInput"
    val isStreaming: Boolean get() = state == "streaming"

    companion object {
        fun from(o: JsonObject): ConversationRow? {
            val rowId = o["rowId"]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() } ?: return null
            val kind = o.rowStr("kind") ?: return null
            val output = o["output"]?.let { runCatching { it.jsonObject }.getOrNull() }
            return ConversationRow(
                rowId = rowId,
                kind = kind,
                turnId = o.rowStr("turnId"),
                text = o.rowStr("text"),
                state = o.rowStr("state"),
                toolName = o.rowStr("toolName"),
                toolCallId = o.rowStr("toolCallId"),
                status = o.rowStr("status"),
                inputText = o.rowStr("inputText"),
                outputText = output?.rowStr("text"),
                createdAt = o.rowLong("createdAt"),
                summaryText = o.rowStr("summaryText"),
                origin = o.rowStr("origin"),
                startedAt = o.rowLong("startedAt"),
                endedAt = o.rowLong("endedAt"),
                activeMs = o.rowLong("activeMs"),
                durationMs = o.rowLong("durationMs"),
                errorText = output?.rowStr("error") ?: o.rowStr("error"),
                attachments = parseAttachments(o),
                raw = o,
            )
        }

        /**
         * 解析 `attachments`（仅 userInput 行会出现）。
         * 形态未穷举且属协议逆向，故对非数组/非对象/字段缺失一律容错为空或跳过，
         * 保证一条畸形行不会让整帧解析失败。
         */
        private fun parseAttachments(o: JsonObject): List<RowAttachment> {
            val arr = o["attachments"]?.let { runCatching { it.jsonArray }.getOrNull() } ?: return emptyList()
            return arr.mapNotNull { item ->
                val ao = (item as? JsonObject) ?: return@mapNotNull null
                fun s(k: String) = ao[k]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
                val att = RowAttachment(
                    ref = s("ref"),
                    fileName = s("fileName"),
                    mime = s("mime"),
                    bytes = ao["bytes"]?.let { runCatching { it.jsonPrimitive.longOrNull }.getOrNull() },
                )
                // 三项标识全空视为垃圾元素，丢弃
                if (att.ref == null && att.fileName == null && att.mime == null) null else att
            }
        }
    }
}

/**
 * 行集合：按 rowId 排序维护，供 Compose 直接渲染。
 *
 * [out] 传 `mutableStateListOf<ConversationRow>()` 即可让 UI 自动刷新。
 */
class RowStore(private val out: MutableList<ConversationRow>) {
    private val index = HashMap<Int, Int>()   // rowId -> 在 out 中的下标

    fun clear() {
        index.clear()
        out.clear()
    }

    fun snapshot(): List<ConversationRow> = ArrayList(out)

    /** 当前窗口最早一行（向上翻页的 beforeRowId）。 */
    fun firstRowId(): Int? = out.firstOrNull()?.rowId

    /**
     * 历史翻页前插：把拉回的更早行（按 rowId 升序）插到头部，跳过已有的 rowId。
     */
    fun prepend(rows: List<ConversationRow>) {
        val fresh = rows.filter { it.rowId !in index }.sortedBy { it.rowId }
        if (fresh.isEmpty()) return
        out.addAll(0, fresh)
        reindex()
    }

    /** 快照全量替换（按 rowId 升序）。 */
    fun replaceAll(rows: List<ConversationRow>) {
        index.clear()
        out.clear()
        rows.sortedBy { it.rowId }.forEach { r ->
            index[r.rowId] = out.size
            out.add(r)
        }
    }

    fun upsert(row: ConversationRow) {
        val pos = index[row.rowId]
        if (pos != null) {
            out[pos] = row
        } else {
            val at = out.indexOfFirst { it.rowId > row.rowId }.let { if (it < 0) out.size else it }
            out.add(at, row)
            for (i in at until out.size) index[out[i].rowId] = i
        }
    }

    /** row.removed：删掉 rowId >= fromRowId 的所有行。 */
    fun removeFrom(fromRowId: Int) {
        var changed = false
        for (i in out.indices.reversed()) {
            if (out[i].rowId >= fromRowId) {
                index.remove(out[i].rowId)
                out.removeAt(i)
                changed = true
            }
        }
        if (changed) reindex()
    }

    /** row.delta：把增量片段累加到指定行的 text/inputText/output.text 上。 */
    fun appendText(rowId: Int, path: String, append: String) {
        val pos = index[rowId]
        if (pos == null) {
            // 理论上先有 row.appended；缺失时兜底建行，避免丢内容
            ZLog.w("RowStore", "appendText 找不到 rowId=$rowId，兜底新建")
            upsert(ConversationRow(rowId = rowId, kind = "streaming", text = append))
            return
        }
        val cur = out[pos]
        val next = when (path) {
            "text" -> cur.copy(text = (cur.text ?: "") + append)
            "inputText" -> cur.copy(inputText = (cur.inputText ?: "") + append)
            "output.text" -> cur.copy(outputText = (cur.outputText ?: "") + append)
            // 曾经这里是 `-> cur`（空操作），于是 agent / 子智能体行的摘要只在首帧出现，
            // 后续增量被静默丢弃，界面表现为「摘要永远停在半句」。
            "summaryText" -> cur.copy(summaryText = (cur.summaryText ?: "") + append)
            else -> {
                ZLog.i("RowStore", "未知 delta path=$path")
                cur
            }
        }
        out[pos] = next
    }

    private fun reindex() {
        index.clear()
        out.forEachIndexed { i, r -> index[r.rowId] = i }
    }
}
