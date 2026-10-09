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

/**
 * 待应答的表单类交互（elicitation，P1-1）。
 *
 * 协议依据（2026-09-29 host asar + 官方 web bundle + 真实帧三重实证，详见 PROTOCOL.md §6.5）：
 *   - 会话帧 `pendingInteractions[]` 里 kind 为 **"userInput"** 的条目（不是 "elicitation"），
 *     `payload.questions[] = {question, header, options:[{value,label,description}], multiSelect}`，
 *     `payload.freeText` 表示允许自由文本；plan 场景 `schema.interaction=="plan_approval"`。
 *   - 应答 = 同一个 `resolveInteraction` envelope，answer 键按形态选择：
 *     带 questions 的表单 → `{action:"accept", content:{answer: 值}}`（单题）/ `{content:{answer_0:…, answers:{问题:答案}}}`（多题）；
 *     拒绝 → `{action:"decline"}`；普通确认/文本（无 questions）→ `{optionId}` / `{freeText}`。
 *     （官方 web：onRespond → `T(interactionId, {action:n, ...content})`；content 构造函数 rut。）
 */
data class ElicitationOption(
    val value: String,
    val label: String,
    val description: String?,
)

data class ElicitationQuestion(
    val question: String,
    val header: String?,
    val options: List<ElicitationOption>,
    val multiSelect: Boolean,
)

data class PendingElicitation(
    val interactionId: String,
    val toolName: String?,
    /** 桌面端给的摘要（如 "Tool AskUserQuestion requires user interaction"），仅兜底显示。 */
    val prompt: String?,
    val questions: List<ElicitationQuestion>,
    val freeText: Boolean,
    /** plan_approval 场景的计划文本（schema.interaction=="plan_approval"）。 */
    val plan: String?,
    val autoResolveAt: Long?,
    val sessionId: String?,
) {
    val isPlanApproval: Boolean get() = plan != null

    companion object {
        /** 从一条 pendingInteraction（kind=="userInput"）构造；其他 kind 返回 null。 */
        fun from(o: JsonObject, sessionId: String? = null): PendingElicitation? {
            fun JsonElement?.asStr(): String? =
                this?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

            val interactionId = o["interactionId"].asStr() ?: return null
            val kind = o["kind"].asStr()
            if (kind != "userInput") return null
            val payload = runCatching { o["payload"]?.jsonObject }.getOrNull() ?: return null

            val questions = runCatching { payload["questions"]?.jsonArray }.getOrNull()
                ?.mapNotNull { el ->
                    val q = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
                    val opts = runCatching { q["options"]?.jsonArray }.getOrNull()
                        ?.mapNotNull { oe ->
                            val ob = runCatching { oe.jsonObject }.getOrNull() ?: return@mapNotNull null
                            ElicitationOption(
                                value = ob["value"].asStr() ?: ob["label"].asStr() ?: return@mapNotNull null,
                                label = ob["label"].asStr() ?: ob["value"].asStr() ?: "",
                                description = ob["description"].asStr(),
                            )
                        } ?: emptyList()
                    if (opts.isEmpty()) return@mapNotNull null
                    ElicitationQuestion(
                        question = q["question"].asStr() ?: "",
                        header = q["header"].asStr(),
                        options = opts,
                        multiSelect = q["multiSelect"].asStr() == "true",
                    )
                } ?: emptyList()

            val schema = runCatching { payload["schema"]?.jsonObject }.getOrNull()
            val plan = schema?.get("plan").asStr()?.takeIf { it.isNotBlank() }

            if (questions.isEmpty() && plan == null && payload["freeText"].asStr() != "true") return null

            return PendingElicitation(
                interactionId = interactionId,
                toolName = payload["toolName"].asStr(),
                prompt = payload["prompt"].asStr(),
                questions = questions,
                freeText = payload["freeText"].asStr() == "true",
                plan = plan,
                autoResolveAt = runCatching {
                    o["autoResolution"]?.jsonObject?.get("deadlineAt")?.jsonPrimitive?.content?.toLongOrNull()
                }.getOrNull(),
                sessionId = sessionId,
            )
        }

        /**
         * 从任务事件流构造（host `userInputRequestToElicitationStreamEvent` 实证）：
         * `{type:"elicitation_request", taskId, requestId, message, header,
         *   options:[{value,label,description}], multiSelect?, questions?, schema?}`。
         */
        fun fromTaskEvent(o: JsonObject, sessionId: String? = null): PendingElicitation? {
            fun JsonElement?.asStr(): String? =
                this?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

            val requestId = o["requestId"].asStr() ?: return null
            val questions = runCatching { o["questions"]?.jsonArray }.getOrNull()
                ?.mapNotNull { el ->
                    val q = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
                    val opts = runCatching { q["options"]?.jsonArray }.getOrNull()
                        ?.mapNotNull { oe ->
                            val ob = runCatching { oe.jsonObject }.getOrNull() ?: return@mapNotNull null
                            ElicitationOption(
                                value = ob["value"].asStr() ?: ob["label"].asStr() ?: return@mapNotNull null,
                                label = ob["label"].asStr() ?: ob["value"].asStr() ?: "",
                                description = ob["description"].asStr(),
                            )
                        } ?: emptyList()
                    if (opts.isEmpty()) return@mapNotNull null
                    ElicitationQuestion(
                        question = q["question"].asStr() ?: "",
                        header = q["header"].asStr(),
                        options = opts,
                        multiSelect = q["multiSelect"].asStr() == "true",
                    )
                } ?: emptyList()

            val schema = runCatching { o["schema"]?.jsonObject }.getOrNull()
            val plan = schema?.get("plan").asStr()?.takeIf { it.isNotBlank() }
            val options = runCatching { o["options"]?.jsonArray }.getOrNull()
                ?.mapNotNull { oe ->
                    val ob = runCatching { oe.jsonObject }.getOrNull() ?: return@mapNotNull null
                    val v = ob["value"].asStr() ?: return@mapNotNull null
                    ElicitationOption(value = v, label = ob["label"].asStr() ?: v, description = ob["description"].asStr())
                } ?: emptyList()
            val mergedQuestions = if (questions.isNotEmpty()) questions
            else {
                val single = ElicitationQuestion(
                    question = o["message"].asStr() ?: "",
                    header = o["header"].asStr(),
                    options = options,
                    multiSelect = o["multiSelect"].asStr() == "true",
                )
                listOfNotNull(single.takeIf { it.options.isNotEmpty() || plan != null })
            }

            if (mergedQuestions.isEmpty() && plan == null) return null
            return PendingElicitation(
                interactionId = requestId,
                toolName = schema?.get("toolName").asStr(),
                prompt = o["message"].asStr(),
                questions = mergedQuestions,
                freeText = false,
                plan = plan,
                autoResolveAt = null,
                sessionId = sessionId,
            )
        }

        /** 解析整组 pendingInteractions 里的 userInput 条目。 */
        fun parseArray(arr: JsonArray?, sessionId: String? = null): List<PendingElicitation> =
            arr?.mapNotNull { el ->
                runCatching { from(el.jsonObject, sessionId) }.getOrNull()
            } ?: emptyList()
    }
}

/**
 * 会话页只渲染「属于当前会话」的待处理项（审批）。
 *
 * 背景（2026-10-09 真机反馈「在其他会话也会弹审批」）：待处理项有两路来源 ——
 * 会话流（只含当前订阅会话）与**任务事件流（覆盖整个 workspace，不限当前订阅会话）**，
 * 两路合并后是一个全局列表。会话页此前把整份全局列表直接铺在输入栏上方，
 * 于是一条属于 A 会话的审批会出现在 B 会话的页面上。
 *
 * `sessionId == null`（解析时拿不到归属）按**可见**处理：宁可多显示一条，
 * 也不能把当前会话的卡隐藏掉（未知归属 ≠ 属于别的会话）。「待办」页仍用全局列表并按会话分组。
 */
internal fun approvalsForSession(
    list: List<PendingApproval>,
    sessionId: String?,
): List<PendingApproval> = list.filter { it.sessionId == null || it.sessionId == sessionId }

/** 同 [approvalsForSession]，用于表单/计划类交互。 */
internal fun elicitationsForSession(
    list: List<PendingElicitation>,
    sessionId: String?,
): List<PendingElicitation> = list.filter { it.sessionId == null || it.sessionId == sessionId }
