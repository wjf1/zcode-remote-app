package com.zcode.remote.relay

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 会话桥层（PROTOCOL.md 第 5 节 zcode_type 路由）。
 * M1 先打通 bootstrap / workspace-list / 事件流展示；
 * rpc-frame 内层 conversation frame 解码在 M1 联调后补全。
 */
object BridgeFrames {
    fun bootstrapRequest(requestId: String): JsonObject = buildJsonObject {
        put("zcode_type", "bootstrap-request")
        put("requestId", requestId)
    }

    /**
     * 手机端视图状态上报（P1-2 控制权提示，PROTOCOL.md §5）。
     * PC 端 schema：`{zcode_type:"mobile-view-state-update",
     * viewState:{activeWorkspaceKey?, activeTaskId?, updatedAt}, deviceInfo?}`
     * ——PC 据此知道手机当前在看哪个工作区/会话（`Jo(h, viewState, deviceInfo)`）。
     */
    fun mobileViewStateUpdate(activeWorkspaceKey: String?, activeTaskId: String?): JsonObject =
        buildJsonObject {
            put("zcode_type", "mobile-view-state-update")
            put("viewState", buildJsonObject {
                activeWorkspaceKey?.takeIf { it.isNotBlank() }?.let { put("activeWorkspaceKey", it) }
                activeTaskId?.takeIf { it.isNotBlank() }?.let { put("activeTaskId", it) }
                put("updatedAt", System.currentTimeMillis())
            })
        }

    fun zcodeTypeOf(payload: JsonObject): String? =
        payload["zcode_type"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

    fun requestIdOf(payload: JsonObject): String? =
        payload["requestId"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
}

/** 会话（任务）条目：来自 bootstrap-response.result.tasks（PROTOCOL.md 5/6.2 节，实测确认）。 */
@Serializable
data class SessionItem(
    val taskId: String,
    val title: String,
    val displayStatus: String,
    val workspacePath: String?,
    val workspaceLabel: String?,
    val provider: String?,
    val updatedAt: Long?,
    val archived: Boolean,
    /**
     * 会话级错误原因（host 的 task meta 里有 `lastError:{code,detail,message?}`，桌面端据此显示）。
     * ⚠️ `PROTOCOL.md` 记录的 bootstrap `tasks[]` 形状**不含**该字段（文档样本取自健康会话），
     * 故此字段是**宽容解析**：负载里带了就显示，没带就是 null（此时列表只能显示「异常」二字，
     * 原因需进会话页看快照 `control.lastError`，那里是协议保证存在的）。
     */
    val lastError: String? = null,
) {
    val isRunning: Boolean get() = displayStatus == "running" || displayStatus == "streaming"
    val needsApproval: Boolean
        get() = displayStatus == "permission_request" || displayStatus == "waiting_approval"

    companion object {
        fun from(obj: JsonObject): SessionItem? {
            fun str(k: String) = obj[k]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            val taskId = str("taskId") ?: return null
            return SessionItem(
                taskId = taskId,
                title = unwrapJsonTitle(str("title")) ?: taskId,
                displayStatus = str("displayStatus") ?: "unknown",
                workspacePath = str("workspacePath"),
                workspaceLabel = str("workspaceLabel"),
                provider = str("provider"),
                updatedAt = str("updatedAt")?.toLongOrNull(),
                archived = str("archived") == "true",
                lastError = errorText(obj["lastError"]),
            )
        }

        /**
         * 防御性解包：host 曾把会话标题存成 `{"title":"…"}`（双重序列化，桌面端数据库实锤，
         * 见 tasks-index 里 sess_98e11ba2）。这种形态的标题不可能是本意，解出内层；其余原样返回。
         */
        fun unwrapJsonTitle(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val o = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return raw
            val inner = o["title"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            return inner?.takeIf { it.isNotBlank() } ?: raw
        }

        /**
         * 错误原因归一：host 的 `lastError` 可能是对象（取 `message`，缺了退 `detail`）
         * 或已是字符串。都取不到（含空对象/未知形状）返回 null —— 宁可不显示也不把
         * 原始 JSON 灌进 UI。
         */
        fun errorText(el: JsonElement?): String? {
            if (el == null || el is JsonNull) return null
            return runCatching {
                when (el) {
                    is JsonObject ->
                        (el["message"] ?: el["detail"])?.let {
                            runCatching { it.jsonPrimitive.content }.getOrNull()
                        }
                    else -> el.jsonPrimitive.content
                }
            }.getOrNull()?.takeIf { it.isNotBlank() }
        }
    }
}

/** bootstrap-response → 会话列表（过滤已归档）。 */
fun parseBootstrapSessions(payload: JsonObject): List<SessionItem> {
    val result = payload["result"]?.let { runCatching { it.jsonObject }.getOrNull() } ?: return emptyList()
    val tasks = result["tasks"]?.let { runCatching { it.jsonArray }.getOrNull() } ?: return emptyList()
    return tasks.mapNotNull { el -> runCatching { SessionItem.from(el.jsonObject) }.getOrNull() }
        .filter { !it.archived }
}

/**
 * 任务事件 → 首页列表 `displayStatus` 映射（`null` = 该事件不足以判定状态，保持原值）。
 *
 * 背景：列表的 `displayStatus` 只在 bootstrap（配对成功时）取一次快照，全仓没有任何刷新入口；
 * 任务事件虽是 host 推的实时流（PROTOCOL.md §6.2），此前只用来建/撤审批卡，从不回写列表——
 * 于是「进会话跑一轮 → 退回主界面」看到的仍是旧状态（多为「已完成」），「运行中」不增加。
 */
fun displayStatusForTaskEvent(type: String): String? = when (type) {
    // 轮次进行中：发过 prompt、正在流式输出、已恢复、或有待应答的审批/表单
    "prompt_sent", "streaming", "resumed",
    "permission_request", "elicitation_request" -> "running"
    "completed" -> "completed"
    "error" -> "error"
    // created / updated / *_resolved：止于「不足以判定」，宁可不动状态也不误判
    else -> null
}

/**
 * 会话快照 `phase` → 首页列表 `displayStatus`。
 *
 * **只认「运行中」**：终态交给任务事件（`completed`/`error`）判定。host 的持久化 task_status 与
 * 实时相位本就可能打架（beta16 实测过 `task_status=error` 而实时相位已恢复的反例），
 * 这里单方向补「正在跑」，不做双向推断。
 */
fun displayStatusForPhase(phase: String?): String? =
    if (phase == "running" || phase == "streaming") "running" else null

/** 任务事件（PROTOCOL.md 6.2：11 种事件类型 + workspacePath/taskId/updatedAt）。 */
data class TaskEvent(
    val type: String,
    val workspacePath: String?,
    val taskId: String?,
    val updatedAt: Long?,
    val raw: JsonObject,
) {
    companion object {
        val TYPES = setOf(
            "created", "prompt_sent", "resumed", "streaming",
            "permission_request", "permission_resolved",
            "elicitation_request", "elicitation_resolved",
            "updated", "completed", "error",
        )

        fun from(payload: JsonObject): TaskEvent? {
            val type = payload["type"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() } ?: return null
            if (type !in TYPES) return null
            return TaskEvent(
                type = type,
                workspacePath = payload["workspacePath"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() },
                taskId = payload["taskId"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() },
                updatedAt = payload["updatedAt"]?.let { runCatching { it.jsonPrimitive.content.toLongOrNull() }.getOrNull() },
                raw = payload,
            )
        }
    }
}

/** 权限审批选项归类（PROTOCOL.md 6.3，手机端字符串集 1:1 还原）。 */
object ApprovalClassifier {
    enum class Kind { ALLOW_ONCE, ALLOW_ALWAYS, REJECT_ONCE, REJECT_ALWAYS, CUSTOM }

    private val allowOnce = setOf("allow", "allow once", "approve")
    private val allowAlways = setOf("always allow", "allow always", "approve always")
    private val rejectOnce = setOf("deny", "deny once", "reject", "reject once")
    private val rejectAlways = setOf("always deny", "deny always", "always reject", "always reject")

    fun classify(optionText: String): Kind {
        val t = optionText.trim().lowercase()
        val allow = t.contains("allow") || t.contains("approve")
        val reject = t.contains("reject") || t.contains("deny")
        val always = t.contains("always")
        return when {
            allow && always -> Kind.ALLOW_ALWAYS
            allow -> Kind.ALLOW_ONCE
            reject && always -> Kind.REJECT_ALWAYS
            reject -> Kind.REJECT_ONCE
            else -> Kind.CUSTOM
        }
    }

    /** 供审批条渲染排序：批准/永久批准/拒绝/永久拒绝/其他。 */
    fun sortKey(k: Kind): Int = when (k) {
        Kind.ALLOW_ONCE -> 0; Kind.ALLOW_ALWAYS -> 1
        Kind.REJECT_ONCE -> 2; Kind.REJECT_ALWAYS -> 3; Kind.CUSTOM -> 4
    }
}
