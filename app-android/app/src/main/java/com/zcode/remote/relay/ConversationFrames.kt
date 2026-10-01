package com.zcode.remote.relay

import android.util.Log
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
    )

    /**
     * control 块：官方 schema `canStop: boolean / stopState: idle|stoppable|stopping /
     * activeWorks[]`（host asar zod 实证）。停止按钮的显示条件就是 `canStop`；
     * `foregroundExecutionId` 是 activeWorks 里的前台执行 id，stop 命令带上可防误停。
     */
    data class Control(
        val phase: String?,
        val canStop: Boolean?,
        val stopState: String?,
        val foregroundExecutionId: String?,
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
        )
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
            title = snap.obj("meta")?.str("title"),
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
                    Log.i(TAG, "未处理的 delta op=$op")
                    Delta.Unknown(op)
                }
            }
        }
    }
}

/**
 * 会话行。角色由 [kind] 表达，没有统一的 role 字段。
 *
 * kind: userInput / assistantText / reasoning / toolCall / turnHeader / subagent / hookInvocation / timelineMarker
 */
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
     * 原始行对象。审批等结构化字段（选项数组、requestId 等）形态尚未穷举，
     * 这里保留整包避免二次改数据类——取值见 [com.zcode.remote.relay.PendingApproval]。
     */
    val raw: JsonObject? = null,
) {
    val isUser: Boolean get() = kind == "userInput"
    val isStreaming: Boolean get() = state == "streaming"

    companion object {
        fun from(o: JsonObject): ConversationRow? {
            fun str(k: String) = o[k]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            val rowId = o["rowId"]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() } ?: return null
            val kind = str("kind") ?: return null
            return ConversationRow(
                rowId = rowId,
                kind = kind,
                turnId = str("turnId"),
                text = str("text"),
                state = str("state"),
                toolName = str("toolName"),
                toolCallId = str("toolCallId"),
                status = str("status"),
                inputText = str("inputText"),
                outputText = o["output"]?.let { runCatching { it.jsonObject["text"]?.jsonPrimitive?.content }.getOrNull() },
                createdAt = o["createdAt"]?.let { runCatching { it.jsonPrimitive.longOrNull }.getOrNull() },
                raw = o,
            )
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
            Log.w("RowStore", "appendText 找不到 rowId=$rowId，兜底新建")
            upsert(ConversationRow(rowId = rowId, kind = "streaming", text = append))
            return
        }
        val cur = out[pos]
        val next = when (path) {
            "text" -> cur.copy(text = (cur.text ?: "") + append)
            "inputText" -> cur.copy(inputText = (cur.inputText ?: "") + append)
            "output.text" -> cur.copy(outputText = (cur.outputText ?: "") + append)
            "summaryText" -> cur
            else -> {
                Log.i("RowStore", "未知 delta path=$path")
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
