package com.zcode.remote.relay

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

    fun zcodeTypeOf(payload: JsonObject): String? =
        payload["zcode_type"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

    fun requestIdOf(payload: JsonObject): String? =
        payload["requestId"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
}

/** 会话（任务）条目：来自 bootstrap-response.result.tasks（PROTOCOL.md 5/6.2 节，实测确认）。 */
data class SessionItem(
    val taskId: String,
    val title: String,
    val displayStatus: String,
    val workspacePath: String?,
    val workspaceLabel: String?,
    val provider: String?,
    val updatedAt: Long?,
    val archived: Boolean,
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
                title = str("title") ?: taskId,
                displayStatus = str("displayStatus") ?: "unknown",
                workspacePath = str("workspacePath"),
                workspaceLabel = str("workspaceLabel"),
                provider = str("provider"),
                updatedAt = str("updatedAt")?.toLongOrNull(),
                archived = str("archived") == "true",
            )
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
