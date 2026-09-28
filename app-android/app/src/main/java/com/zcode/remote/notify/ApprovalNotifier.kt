package com.zcode.remote.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import com.zcode.remote.MainActivity
import com.zcode.remote.R
import com.zcode.remote.relay.ApprovalOption
import com.zcode.remote.relay.PendingApproval

/**
 * 审批通知栏：把服务端推来的 pendingInteractions 逐条变成带「批准/拒绝」按钮的系统通知。
 *
 * 通知动作 → [ApprovalReceiver] → [ApprovalBridge] → AppViewModel 发 resolveInteraction。
 * 中继连接活在 AppViewModel 里，所以桥为空时（连接已断/页面已销毁）动作明确提示"没发出去"，
 * 不做任何自动重放。
 */
object ApprovalBridge {
    /** 由 AppViewModel 在连接建立时注册、断开时清空。 */
    @Volatile
    var handler: ((interactionId: String, optionId: String) -> Unit)? = null

    /** 返回 false 表示当前无连接可应答。 */
    fun dispatch(interactionId: String, optionId: String): Boolean {
        val h = handler ?: return false
        h(interactionId, optionId)
        return true
    }
}

class ApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val interactionId = intent.getStringExtra(EXTRA_INTERACTION) ?: return
        val optionId = intent.getStringExtra(EXTRA_OPTION) ?: return
        val ok = ApprovalBridge.dispatch(interactionId, optionId)
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.cancel(ApprovalNotifier.notifId(interactionId))
        if (!ok) {
            // 连接不在（App 被杀/断线）：明确告知，避免用户以为已经批准了
            nm.notify(
                ApprovalNotifier.notifId(interactionId) + 1,
                Notification.Builder(context, ApprovalNotifier.CH_APPROVALS)
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle("ZCode Remote 未连接")
                    .setContentText("这条审批没发出去，打开 App 重新处理")
                    .setAutoCancel(true)
                    .build()
            )
        }
    }

    companion object {
        const val ACTION_RESOLVE = "com.zcode.remote.action.RESOLVE_APPROVAL"
        const val EXTRA_INTERACTION = "interactionId"
        const val EXTRA_OPTION = "optionId"
    }
}

object ApprovalNotifier {
    const val CH_APPROVALS = "approvals"

    /** 每条审批最多渲染 3 个按钮（系统通知动作上限）。 */
    private const val MAX_ACTIONS = 3
    private const val ID_BASE = 2000
    private const val ID_SPAN = 100_000

    fun notifId(interactionId: String): Int =
        ID_BASE + ((interactionId.hashCode() and 0x7fffffff) % ID_SPAN)

    /** 用当前 pending 集合对齐通知栏：新增/更新的发出去，已消解的撤掉。 */
    fun sync(context: Context, pending: List<PendingApproval>) {
        ensureChannel(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        val live = (pending.map { notifId(it.interactionId) } +
                pending.map { notifId(it.interactionId) + 1 }).toSet()

        nm.activeNotifications
            ?.asSequence()
            ?.filter { it.id in ID_BASE until (ID_BASE + ID_SPAN * 2) }
            ?.filter { it.id !in live }
            ?.forEach { nm.cancel(it.id) }

        for (a in pending) nm.notify(notifId(a.interactionId), build(context, a))
    }

    fun clearAll(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.activeNotifications
            ?.asSequence()
            ?.filter { it.id in ID_BASE until (ID_BASE + ID_SPAN * 2) }
            ?.forEach { nm.cancel(it.id) }
    }

    private fun build(context: Context, a: PendingApproval): Notification {
        val title = "需要审批：${a.toolName ?: "工具调用"}"
        val body = listOfNotNull(a.summary?.take(120), a.detail?.take(160))
            .joinToString("\n")
            .ifBlank { "桌面端在等你决定" }

        val contentIntent = PendingIntent.getActivity(
            context, notifId(a.interactionId),
            Intent(context, MainActivity::class.java)
                .putExtra("openSession", a.sessionId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val b = Notification.Builder(context, CH_APPROVALS)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setPriority(Notification.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_SOCIAL)
            .setContentIntent(contentIntent)
            .setAutoCancel(false)
            .setOngoing(true)

        a.autoResolveAt?.let {
            b.setWhen(it)
            b.setSubText("桌面端 ${DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)} 自动决议")
        }

        val opts = a.options.take(MAX_ACTIONS)
        if (opts.isEmpty()) {
            b.addAction(Notification.Action.Builder(null, "打开 App 处理", contentIntent).build())
        } else {
            opts.forEachIndexed { i, opt ->
                b.addAction(
                    Notification.Action.Builder(
                        null, labelOf(opt), actionIntent(context, a, opt, i)
                    ).build()
                )
            }
        }
        return b.build()
    }

    private fun labelOf(opt: ApprovalOption): String = when {
        opt.kind == "allowOnce" -> "允许一次"
        opt.kind == "allowAlways" -> "总是允许"
        opt.isDeny -> "拒绝"
        opt.label.isNotBlank() -> opt.label.take(10)
        else -> "选项"
    }

    private fun actionIntent(
        context: Context, a: PendingApproval, opt: ApprovalOption, index: Int,
    ): PendingIntent {
        val intent = Intent(ApprovalReceiver.ACTION_RESOLVE)
            .setClass(context, ApprovalReceiver::class.java)
            .putExtra(ApprovalReceiver.EXTRA_INTERACTION, a.interactionId)
            .putExtra(ApprovalReceiver.EXTRA_OPTION, opt.optionId)
        return PendingIntent.getBroadcast(
            context, notifId(a.interactionId) + index, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH_APPROVALS) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CH_APPROVALS, "权限审批", NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "桌面端 Agent 请求权限时的批准/拒绝入口"
                    enableVibration(true)
                }
            )
        }
    }
}
