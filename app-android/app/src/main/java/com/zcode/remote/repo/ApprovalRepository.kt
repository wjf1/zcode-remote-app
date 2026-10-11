package com.zcode.remote.repo

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zcode.remote.notify.ApprovalNotifier
import com.zcode.remote.notify.ElicitationNotifier
import com.zcode.remote.relay.ConversationChannel
import com.zcode.remote.relay.PendingApproval
import com.zcode.remote.relay.PendingElicitation
import com.zcode.remote.relay.UserFacingError
import com.zcode.remote.util.ZLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * 权限审批与表单交互（§11.7 结构性拆分：自 `AppViewModel` 原样搬出，只移动不改行为）。
 * 双来源合并（会话流 pendingInteractions + 任务事件流推送）→ 通知栏对齐 → 多会话看板计数。
 * 连接栈（conversation / activeWorkspaceKey / sessions 首项）为运行期代理，经 getter 注入。
 *
 * @param flash 反馈横幅回调：(文案, 是否失败语义)。
 * @param onPendingChanged 待处理集合变化后的看板/Widget 重算回调（NotificationCoordinator 职责）。
 */
class ApprovalRepository(
    private val scope: CoroutineScope,
    private val context: Context,
    private val conversation: () -> ConversationChannel?,
    private val activeWorkspaceKey: () -> String?,
    private val firstSessionWorkspace: () -> String?,
    private val flash: (String, Boolean) -> Unit,
    private val onPendingChanged: () -> Unit,
) {
    /** 当前会话的待审批项（会话流 pendingInteractions + 任务事件流两条来源合并）。 */
    var approvals by mutableStateOf<List<PendingApproval>>(emptyList())
        private set

    /** 任务事件流（桌面端实证的审批推送路径）来的待审批，按 interactionId 索引。主线程专用。 */
    internal val taskApprovals = LinkedHashMap<String, PendingApproval>()

    /** 当前会话的待应答表单（会话帧 userInput 条目 + 任务事件流 elicitation_request 合并）。 */
    var elicitations by mutableStateOf<List<PendingElicitation>>(emptyList())
        private set
    internal val taskElicitations = LinkedHashMap<String, PendingElicitation>()

    private companion object {
        private const val TAG = "ApprovalRepo"
    }


    /** 任务事件流 permission_request 建卡（collector 落地入口）。 */
    fun ingestTaskApproval(a: PendingApproval) {
        taskApprovals[a.interactionId] = a
        refreshApprovals()
    }

    /** 任务事件流 permission_resolved 按 requestId 撤卡。返回 true = 确有卡片被撤下。 */
    fun resolveTaskApproval(requestId: String): Boolean {
        if (taskApprovals.remove(requestId) != null) {
            refreshApprovals()
            return true
        }
        return false
    }

    /** 任务事件流 elicitation_request 建卡。 */
    fun ingestTaskElicitation(e: PendingElicitation) {
        taskElicitations[e.interactionId] = e
        refreshElicitations()
    }

    /** 任务事件流 elicitation_resolved 按 requestId 撤卡。返回 true = 确有卡片被撤下。 */
    fun resolveTaskElicitation(requestId: String): Boolean {
        if (taskElicitations.remove(requestId) != null) {
            refreshElicitations()
            return true
        }
        return false
    }

    /**
     * 合并两条审批来源（会话流 pendingInteractions + 任务事件流）并对齐通知栏：
     * 新审批弹通知，桌面端已处理/自动决议的自动撤下。
     */
    fun refreshApprovals() {
        val merged = LinkedHashMap<String, PendingApproval>()
        conversation()?.interactions?.value?.forEach { merged[it.interactionId] = it }
        taskApprovals.forEach { (k, v) -> merged[k] = v }
        val next = merged.values.toList()
        if (next == approvals) return
        approvals = next
        ZLog.i(
            TAG,
            "approvals → ${next.size} 条 " +
                next.joinToString(",") { "${it.toolName ?: "?"}#${it.interactionId.take(18)}" },
        )
        runCatching { ApprovalNotifier.sync(context, next) }
            .onFailure { ZLog.w(TAG, "通知栏刷新失败", it) }
        onPendingChanged()
    }

    /** 合并表单交互双来源（会话帧 userInput 条目 + 任务事件流），interactionId 去重。 */
    fun refreshElicitations() {
        val merged = LinkedHashMap<String, PendingElicitation>()
        conversation()?.elicitations?.value?.forEach { merged[it.interactionId] = it }
        taskElicitations.forEach { (k, v) -> merged.putIfAbsent(k, v) }
        val next = merged.values.toList()
        if (next == elicitations) return
        elicitations = next
        ZLog.i(
            TAG,
            "elicitations → ${next.size} 条 " +
                next.joinToString(",") { "${it.toolName ?: "?"}#${it.interactionId.take(18)}" },
        )
        runCatching { ElicitationNotifier.sync(context, next) }
            .onFailure { ZLog.w(TAG, "表单通知栏刷新失败", it) }
        onPendingChanged()
    }

    /** 通知栏按钮走这条路径：按 id 找回对象再应答。 */
    fun resolveById(
        interactionId: String,
        optionId: String,
    ) {
        val a = approvals.firstOrNull { it.interactionId == interactionId }
        val opt = a?.options?.firstOrNull { it.optionId == optionId }
        if (a == null || opt == null) {
            flash("这条审批已经不在待处理列表里了（可能桌面端已处理）", false)
            return
        }
        resolve(a, opt)
    }

    /** 应答一次审批（乐观消除 + 会话隔离修复）。 */
    fun resolve(
        approval: PendingApproval,
        option: com.zcode.remote.relay.ApprovalOption,
    ) {
        val conv =
            conversation() ?: run {
                flash("连接已断开，未发出", true)
                return
            }
        val ws = activeWorkspaceKey() ?: firstSessionWorkspace()
        val interId = approval.interactionId

        // 乐观消除：点击瞬间立即从本地列表移除，并刷新系统通知栏
        taskApprovals.remove(interId)
        approvals = approvals.filter { it.interactionId != interId }
        runCatching { ApprovalNotifier.sync(context, approvals) }
        onPendingChanged()

        conv.resolve(approval, option, fallbackWorkspacePath = ws) { r ->
            scope.launch {
                val (feedback, failure) =
                    when (r) {
                        is ConversationChannel.ResolveResult.Accepted ->
                            when (r.status) {
                                "accepted" -> (if (option.isAllow) "已批准" else "已拒绝") to false
                                "duplicate" -> "已收到（重复提交，服务端只认第一次）" to false
                                else -> "服务端已消解（可能桌面端先处理了）" to false
                            }
                        is ConversationChannel.ResolveResult.Failed -> {
                            // 失败回滚：放回审批列表中
                            taskApprovals[interId] = approval
                            refreshApprovals()
                            "发送失败：${UserFacingError.map(r.message)}" to true
                        }
                    }
                flash(feedback, failure)
                ZLog.i(TAG, "resolve result=$r interaction=$interId feedback=$feedback")
            }
        }
    }

    /**
     * 提交表单答案：[answers] 为 题号 → 该题答案值列表（含自定义文本）。
     * 官方 web rut 构造：单题 content.answer、多题 content.answer_N，
     * 同时附 answers:{问题文本: "合并答案"} 便于桌面端日志可读。
     */
    fun answerElicitation(
        el: PendingElicitation,
        answers: Map<Int, List<String>>,
    ) {
        val conv =
            conversation() ?: run {
                flash("连接已断开，未发送", true)
                return
            }
        val ws = activeWorkspaceKey() ?: firstSessionWorkspace()
        val interId = el.interactionId

        // 乐观消除：点击后立即从本地列表移除
        taskElicitations.remove(interId)
        elicitations = elicitations.filter { it.interactionId != interId }
        onPendingChanged()

        val content =
            buildJsonObject {
                val readable = LinkedHashMap<String, String>()
                answers.forEach { (idx, values) ->
                    if (values.isEmpty()) return@forEach
                    val q = el.questions.getOrNull(idx)
                    val jsonValues = buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }
                    if (el.questions.size == 1) {
                        put("answer", if (q?.multiSelect == true) jsonValues else JsonPrimitive(values.first()))
                    } else {
                        put("answer_$idx", if (q?.multiSelect == true) jsonValues else JsonPrimitive(values.first()))
                    }
                    q?.let { readable[it.question] = values.joinToString(", ") }
                }
                if (readable.isNotEmpty()) {
                    put("answers", buildJsonObject { readable.forEach { (k, v) -> put(k, JsonPrimitive(v)) } })
                }
            }
        val answer =
            buildJsonObject {
                put("action", "accept")
                put("content", content)
            }
        conv.resolveElicitation(el, answer, fallbackWorkspacePath = ws) { r ->
            handleElicitationResult(r, "已提交回答", el)
        }
    }

    /** 拒绝表单/计划。 */
    fun declineElicitation(el: PendingElicitation) {
        val conv =
            conversation() ?: run {
                flash("连接已断开，未发送", true)
                return
            }
        val ws = activeWorkspaceKey() ?: firstSessionWorkspace()
        val interId = el.interactionId

        taskElicitations.remove(interId)
        elicitations = elicitations.filter { it.interactionId != interId }
        onPendingChanged()

        conv.resolveElicitation(el, buildJsonObject { put("action", "decline") }, fallbackWorkspacePath = ws) { r ->
            handleElicitationResult(r, "已拒绝", el)
        }
    }

    /** 计划批准（plan_approval）。 */
    fun approveElicitationPlan(el: PendingElicitation) {
        val conv =
            conversation() ?: run {
                flash("连接已断开，未发送", true)
                return
            }
        val ws = activeWorkspaceKey() ?: firstSessionWorkspace()
        val interId = el.interactionId

        taskElicitations.remove(interId)
        elicitations = elicitations.filter { it.interactionId != interId }
        onPendingChanged()

        conv.resolveElicitation(el, buildJsonObject { put("action", "accept") }, fallbackWorkspacePath = ws) { r ->
            handleElicitationResult(r, "已批准计划", el)
        }
    }

    /** 自由文本应答（无 questions 的 userInput 条目，freeText=true）。 */
    fun answerElicitationFreeText(
        el: PendingElicitation,
        text: String,
    ) {
        val conv =
            conversation() ?: run {
                flash("连接已断开，未发送", true)
                return
            }
        val ws = activeWorkspaceKey() ?: firstSessionWorkspace()
        val interId = el.interactionId

        taskElicitations.remove(interId)
        elicitations = elicitations.filter { it.interactionId != interId }
        onPendingChanged()

        // 服务端 resolveInteraction.answer 的 action 为枚举 accept|decline|cancel：
        // 此前只发 {freeText} 缺 action，真机报 `invalid_value @ answer.action`（表单应答失败）。
        conv.resolveElicitation(
            el,
            buildJsonObject {
                put("action", "accept")
                put("freeText", text)
            },
            fallbackWorkspacePath = ws,
        ) { r ->
            handleElicitationResult(r, "已提交", el)
        }
    }

    private fun handleElicitationResult(
        r: ConversationChannel.ResolveResult,
        okText: String,
        el: PendingElicitation,
    ) {
        scope.launch {
            when (r) {
                is ConversationChannel.ResolveResult.Accepted -> flash(okText, false)
                is ConversationChannel.ResolveResult.Failed -> {
                    taskElicitations[el.interactionId] = el
                    refreshElicitations()
                    flash("应答失败：${UserFacingError.map(r.message)}", true)
                }
            }
        }
    }

    /**
     * 通知栏快捷应答（D-3）：携带预构造好的 answer JSON 直接提交。
     * 返回 false = 当前无连接（由 receiver 显示"没发出去"）。
     */
    fun resolveElicitationById(
        interactionId: String,
        answerJson: String,
    ): Boolean {
        val conv = conversation() ?: return false
        val el = elicitations.firstOrNull { it.interactionId == interactionId } ?: return false
        val answer =
            runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(answerJson).jsonObject
            }.getOrNull() ?: return false
        conv.resolveElicitation(el, answer) { r -> flashResolve(r, "已提交") }
        return true
    }

    private fun flashResolve(
        r: ConversationChannel.ResolveResult,
        okText: String,
    ) {
        scope.launch {
            when (r) {
                is ConversationChannel.ResolveResult.Accepted -> flash(okText, false)
                is ConversationChannel.ResolveResult.Failed ->
                    flash("应答失败：${UserFacingError.map(r.message)}", true)
            }
        }
    }

    /** 断开/卸载时清空（连接没了就没人能应答）。通知栏清理由调用方统一执行。 */
    fun clearAll() {
        taskApprovals.clear()
        taskElicitations.clear()
        approvals = emptyList()
        elicitations = emptyList()
    }
}
