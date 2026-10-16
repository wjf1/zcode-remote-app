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
    fun dispatch(
        interactionId: String,
        optionId: String,
    ): Boolean {
        val h = handler ?: return false
        h(interactionId, optionId)
        return true
    }
}

class ApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val interactionId = intent.getStringExtra(EXTRA_INTERACTION) ?: return
        val optionId = intent.getStringExtra(EXTRA_OPTION) ?: return
        val ok = ApprovalBridge.dispatch(interactionId, optionId)
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.cancel(ApprovalNotifier.notifId(interactionId))
        if (!ok) {
            // 连接不在（App 被杀/断线）：明确告知，避免用户以为已经批准了
            nm.notify(
                ApprovalNotifier.notifId(interactionId) + 1,
                Notification
                    .Builder(context, ApprovalNotifier.CH_APPROVALS)
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle(context.getString(R.string.notif_disconnected_title))
                    .setContentText(context.getString(R.string.notif_approval_not_sent))
                    .setAutoCancel(true)
                    .build(),
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

    fun notifId(interactionId: String): Int = ID_BASE + ((interactionId.hashCode() and 0x7fffffff) % ID_SPAN)

    /** 用当前 pending 集合对齐通知栏：新增/更新的发出去，已消解的撤掉。 */
    fun sync(
        context: Context,
        pending: List<PendingApproval>,
    ) {
        ensureChannel(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        val live =
            (
                pending.map { notifId(it.interactionId) } +
                    pending.map { notifId(it.interactionId) + 1 }
            ).toSet()

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

    private fun build(
        context: Context,
        a: PendingApproval,
    ): Notification {
        val title = context.getString(R.string.notif_approval_title, a.toolName ?: context.getString(R.string.notif_approval_default_tool))
        val body =
            listOfNotNull(a.summary?.take(120), a.detail?.take(160))
                .joinToString("\n")
                .ifBlank { context.getString(R.string.notif_waiting_decision) }

        val contentIntent =
            PendingIntent.getActivity(
                context,
                notifId(a.interactionId),
                Intent(context, MainActivity::class.java)
                    .putExtra("openSession", a.sessionId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val b =
            Notification
                .Builder(context, CH_APPROVALS)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(Notification.BigTextStyle().bigText(body))
                // setPriority/PRIORITY_HIGH 自 Android 8 起由通知渠道取代（本渠道为 IMPORTANCE_HIGH），
                // minSdk 31 下已是死代码；高优先级由渠道保证，移除以免误导。
                .setCategory(Notification.CATEGORY_SOCIAL)
                .setContentIntent(contentIntent)
                .setAutoCancel(false)
                .setOngoing(true)

        a.autoResolveAt?.let {
            b.setWhen(it)
            b.setSubText(
                context.getString(
                    R.string.notif_auto_resolve_subtext,
                    DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
                ),
            )
        }

        val opts = a.options.take(MAX_ACTIONS)
        if (opts.isEmpty()) {
            b.addAction(Notification.Action.Builder(null, context.getString(R.string.notif_open_app), contentIntent).build())
        } else {
            opts.forEachIndexed { i, opt ->
                b.addAction(
                    Notification.Action
                        .Builder(
                            null,
                            labelOf(context, opt),
                            actionIntent(context, a, opt, i),
                        ).build(),
                )
            }
        }
        return b.build()
    }

    private fun labelOf(
        context: android.content.Context,
        opt: ApprovalOption,
    ): String =
        when {
            opt.kind == "allowOnce" -> context.getString(R.string.pending_allow_once)
            opt.kind == "allowAlways" -> context.getString(R.string.pending_allow_always)
            opt.isDeny -> context.getString(R.string.pending_deny)
            opt.label.isNotBlank() -> opt.label.take(10)
            else -> context.getString(R.string.pending_option_default)
        }

    private fun actionIntent(
        context: Context,
        a: PendingApproval,
        opt: ApprovalOption,
        index: Int,
    ): PendingIntent {
        val intent =
            Intent(ApprovalReceiver.ACTION_RESOLVE)
                .setClass(context, ApprovalReceiver::class.java)
                .putExtra(ApprovalReceiver.EXTRA_INTERACTION, a.interactionId)
                .putExtra(ApprovalReceiver.EXTRA_OPTION, opt.optionId)
        return PendingIntent.getBroadcast(
            context,
            notifId(a.interactionId) + index,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH_APPROVALS) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CH_APPROVALS,
                    context.getString(R.string.notif_approval_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.notif_approval_channel_desc)
                    enableVibration(true)
                },
            )
        }
    }
}
