package com.zcode.remote.repo

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zcode.remote.widget.PendingWidgetProvider
import com.zcode.remote.relay.BridgeFrames
import com.zcode.remote.relay.FailureReason
import com.zcode.remote.relay.RelayClient
import com.zcode.remote.relay.RelayState
import com.zcode.remote.relay.SessionsIndexChannel
import com.zcode.remote.util.LogRedactor
import com.zcode.remote.util.ZLog

/**
 * 多会话看板 / Widget / 视图上报（§11.7 结构性拆分：自 `AppViewModel` 原样搬出，只移动不改行为）。
 * 任务书 §11.7 的 NotificationCoordinator 职责：待处理计数聚合（P1-2 / E-1 权威角标优先 +
 * 事件流回退）、桌面 Widget 推送（P2-3）、「手机正在看哪个会话」上报（P1-2）与连接终态文案。
 * 连接栈与审批域均为运行期代理，经 getter / 注入引用。
 */
class NotificationCoordinator(
    private val context: Context,
    private val client: () -> RelayClient?,
    private val sessionsIndex: () -> SessionsIndexChannel?,
    private val relayState: () -> RelayState,
    private val activeWorkspaceKey: () -> String?,
    private val subscribedSessionId: () -> String?,
    private val approvalsRepo: ApprovalRepository,
) {
    private val TAG = "NotifyCoord"

    /**
     * 每个会话的待处理条数（taskId → 审批 + 表单）。来源＝任务事件流（覆盖所有会话，
     * 不限于当前订阅的那个），订阅中的会话以会话流数据优先（更实时）。
     */
    var sessionPending by mutableStateOf<Map<String, Int>>(emptyMap())
        private set
    /** 桌面端当前活动会话（workspace-list 的 activeTaskId，用于「PC 正在看哪个」提示）。 */
    var desktopActiveTaskId by mutableStateOf<String?>(null)
        private set

    /**
     * 多会话看板（P1-2 / E-1）：汇总每个会话的待处理条数。
     * 权威来源＝sessions-index 订阅（服务端 pendingInteractionSummary，覆盖全部会话、消解即清零）；
     * 事件流推导作为回退（仅补权威索引里没有的会话，如订阅失败/未就绪）；
     * 当前订阅会话以会话流明细数覆盖（最实时、含会话帧路径条目），明细为 0 时仍信权威。
     */
    fun recomputeSessionPending() {
        val counts = HashMap<String, Int>()
        val authoritative = sessionsIndex()?.summaries?.value ?: emptyMap()
        if (authoritative.isNotEmpty()) {
            authoritative.forEach { (sid, s) -> if (s.total > 0) counts[sid] = s.total }
            approvalsRepo.taskApprovals.values.forEach { a ->
                a.sessionId?.let { sid -> if (sid !in authoritative) counts[sid] = (counts[sid] ?: 0) + 1 }
            }
            approvalsRepo.taskElicitations.values.forEach { e ->
                e.sessionId?.let { sid -> if (sid !in authoritative) counts[sid] = (counts[sid] ?: 0) + 1 }
            }
        } else {
            approvalsRepo.taskApprovals.values.forEach { a -> a.sessionId?.let { counts[it] = (counts[it] ?: 0) + 1 } }
            approvalsRepo.taskElicitations.values.forEach { e -> e.sessionId?.let { counts[it] = (counts[it] ?: 0) + 1 } }
        }
        subscribedSessionId()?.let { sid ->
            val n = approvalsRepo.approvals.size + approvalsRepo.elicitations.size
            when {
                n > 0 -> counts[sid] = n
                (authoritative[sid]?.total ?: 0) <= 0 -> counts.remove(sid)
            }
        }
        if (counts != sessionPending) sessionPending = counts
        syncWidget(counts.values.sum())
    }

    /** 桌面 Widget（P2-3）状态推送：计数或连接变化都走这里。 */
    fun syncWidget(pendingTotal: Int = sessionPending.values.sum()) {
        val connected = relayState() is RelayState.Paired
        runCatching { PendingWidgetProvider.sync(context, pendingTotal, connected) }
            .onFailure { ZLog.w(TAG, "widget 同步失败", it) }
    }

    /** 上报手机端视图状态（P1-2）：PC 据此在界面上指出"手机正在看这个会话"。 */
    fun reportViewState() {
        val c = client() ?: return
        val payload = BridgeFrames.mobileViewStateUpdate(activeWorkspaceKey(), subscribedSessionId())
        c.sendPayload(payload)
        ZLog.i(TAG, "view-state → ws=${LogRedactor.pathLabel(activeWorkspaceKey())} task=${LogRedactor.maskId(subscribedSessionId())}")
    }

    /** 断开/清理时清空看板计数（disconnect 复位）。 */
    fun clearPending() {
        sessionPending = emptyMap()
    }

    companion object {
        /** 连接终态通知的短标题（Sprint 2 / A 组）。 */
        internal fun terminalTitle(reason: FailureReason): String = when (reason) {
            FailureReason.KICKED -> "控制权已在别处接管"
            FailureReason.AUTH_FAILED -> "配对已失效，请重新扫码"
            FailureReason.PROTOCOL_MISMATCH -> "中继协议可能已升级"
            FailureReason.INVALID_ENDPOINT -> "中继地址不安全，已拒绝连接"
            else -> "连接已终止"
        }
    }
}
