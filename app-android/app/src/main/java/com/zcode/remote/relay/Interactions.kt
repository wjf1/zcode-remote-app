package com.zcode.remote.relay

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 待审批交互（permission interaction）。
 *
 * 协议依据（逆向 + host 源码双向印证，详见 PROTOCOL.md §6.3）：
 *   - 请求出现在会话快照的 `pendingInteractions[]`，或 `state.updated` patch 里的同名字段
 *     （**整组替换**语义，不是逐条增删）。
 *   - 应答 = `sendConversationCommandV4` 发 `resolveInteraction` 命令，按 `interactionId` + `optionId` 定位。
 *   - `optionId` 是服务端生成的原文字符串，**只透传不解析**；UI 显示序号是按 kind 排序后的位次，
 *     与数组下标不一致，所以必须存原值。
 *   - 同一会话可能有多条并行 pending（subagent 场景），必须按 interactionId 精确匹配。
 */
data class ApprovalOption(
    val optionId: String,
    val label: String,
    /** allowOnce | allowAlways | deny | custom（服务端给的语义分类，优先于文本猜测）。 */
    val kind: String?,
    /** allow | deny | escalate | modify —— 仅用于渲染，不回传。 */
    val decision: String?,
) {
    val isAllow: Boolean
        get() = kind?.startsWith("allow") == true || decision == "allow"
    val isDeny: Boolean
        get() = kind == "deny" || kind?.startsWith("reject") == true || decision == "deny"

    /** 渲染顺序：单次批准 → 永久批准 → 拒绝 → 永久拒绝 → 其他。 */
    val sortKey: Int
        get() = when {
            kind == "allowOnce" -> 0
            kind == "allowAlways" -> 1
            kind == "rejectAlways" -> 3
            kind == "deny" || kind == "rejectOnce" -> 2
            isDeny -> 4
            else -> 5
        }

    val displayLabel: String get() = label.ifBlank { kind ?: optionId }
}

data class PendingApproval(
    val interactionId: String,
    val toolCallId: String?,
    val toolName: String?,
    val summary: String?,
    /** 压平后的可读详情（命令行 / URL / 路径），取不到为 null。 */
    val detail: String?,
    val options: List<ApprovalOption>,
    val anchorRowId: Int?,
    /** 桌面端自动决议倒计时的到期时刻（ms）。到点后手机再点会得到 noop。 */
    val autoResolveAt: Long?,
    val sessionId: String?,
) {
    companion object {
        /** 从一条 pendingInteraction 对象构造；不是 permission 类型或缺 interactionId 则返回 null。 */
        fun from(o: JsonObject, sessionId: String? = null): PendingApproval? {
            fun JsonElement?.asStr(): String? =
                this?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

            val interactionId = o["interactionId"].asStr() ?: return null
            val kind = o["kind"].asStr()
            // elicitation（表单类）也带 options，但应答形态不同，本功能只处理 permission
            if (kind != null && kind != "permission") return null

            val payload = runCatching { o["payload"]?.jsonObject }.getOrNull()
            val options = runCatching { payload?.get("options")?.jsonArray }.getOrNull()
                ?.mapNotNull { el ->
                    val ob = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
                    val oid = ob["optionId"].asStr() ?: return@mapNotNull null
                    val response = runCatching { ob["response"]?.jsonObject }.getOrNull()
                    ApprovalOption(
                        optionId = oid,
                        label = ob["label"].asStr() ?: "",
                        kind = ob["kind"].asStr(),
                        decision = response?.get("decision").asStr(),
                    )
                }
                ?.sortedBy { it.sortKey }
                ?: emptyList()

            val detail = payload?.get("detail").let { d ->
                runCatching { d?.jsonObject }.getOrNull()?.let { dt ->
                    // 官方 UI 的取值优先级：描述/原因类字段优先，其次工具入参主字段
                    listOf("description", "reason", "command", "url", "file_path", "path", "pattern")
                        .firstNotNullOfOrNull { k -> dt[k].asStr()?.takeIf { it.isNotBlank() } }
                } ?: d.asStr()
            }

            return PendingApproval(
                interactionId = interactionId,
                toolCallId = payload?.get("toolCallId").asStr(),
                toolName = payload?.get("toolName").asStr(),
                summary = payload?.get("summary").asStr(),
                detail = detail,
                options = options,
                anchorRowId = runCatching { o["anchorRowId"]?.jsonPrimitive?.content?.toIntOrNull() }.getOrNull(),
                autoResolveAt = runCatching {
                    o["autoResolution"]?.jsonObject?.get("deadlineAt")?.jsonPrimitive?.content?.toLongOrNull()
                }.getOrNull(),
                sessionId = sessionId,
            )
        }

        /**
         * 从任务事件流构造（桌面端 live 源码实证的审批推送路径，host `permissionRequestToStreamEvent`）：
         *   {type:"permission_request", taskId, requestId, description: reason||toolName,
         *    kind: toolName, title: toolName, options:[{optionId, kind, name?, response?}], raw}
         * 与 pendingInteractions 的差异：没有 interactionId/payload 包裹（requestId === 应答时的
         * interactionId，见 host respondPermission）；选项元素没有 label（显示名由 kind 生成，
         * custom 类用 name）。收不到这条事件 = 收不到审批（实测：会话帧里没有 pendingInteractions）。
         */
        fun fromTaskEvent(o: JsonObject, sessionId: String? = null): PendingApproval? {
            fun JsonElement?.asStr(): String? =
                this?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

            val requestId = o["requestId"].asStr() ?: return null
            val options = runCatching { o["options"]?.jsonArray }.getOrNull()
                ?.mapNotNull { el ->
                    val ob = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
                    val oid = ob["optionId"].asStr() ?: return@mapNotNull null
                    val response = runCatching { ob["response"]?.jsonObject }.getOrNull()
                    ApprovalOption(
                        optionId = oid,
                        label = ob["label"].asStr() ?: ob["name"].asStr() ?: "",
                        kind = ob["kind"].asStr(),
                        decision = response?.get("decision").asStr(),
                    )
                }?.sortedBy { it.sortKey } ?: emptyList()

            return PendingApproval(
                interactionId = requestId,
                toolCallId = null,
                toolName = o["title"].asStr() ?: o["kind"].asStr(),
                summary = o["description"].asStr(),
                detail = null,
                options = options,
                anchorRowId = null,
                autoResolveAt = runCatching {
                    o["raw"]?.jsonObject?.get("autoResolution")?.jsonObject?.get("deadlineAt")
                        ?.jsonPrimitive?.content?.toLongOrNull()
                }.getOrNull(),
                sessionId = sessionId,
            )
        }

        /** 解析整组 pendingInteractions；缺失/非数组一律视作"没有待审批"。 */
        fun parseArray(arr: JsonArray?, sessionId: String? = null): List<PendingApproval> =
            arr?.mapNotNull { el ->
                runCatching { from(el.jsonObject, sessionId) }.getOrNull()
            } ?: emptyList()

        fun parseFrom(obj: JsonObject?, sessionId: String? = null): List<PendingApproval> =
            parseArray(runCatching { obj?.get("pendingInteractions")?.jsonArray }.getOrNull(), sessionId)
    }
}
