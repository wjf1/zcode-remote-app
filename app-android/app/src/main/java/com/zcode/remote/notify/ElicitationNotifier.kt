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
import com.zcode.remote.relay.PendingElicitation

/**
 * 表单类交互（elicitation）的通知栏快捷应答（D-3）。
 *
 * 与审批通知分开两套：表单形态复杂，只有「能用一个按钮表达清楚」的才给快捷动作，
 * 其余（多题、多选、需自由文本）只提示「打开 App 处理」，避免在通知栏拼出会被服务端
 * 拒的答案。答案 JSON 由通知动作直接携带，接收器原样透传——这里不重复业务构造逻辑。
 */
object ElicitationBridge {
    /** 由 AppViewModel 挂上/摘下。返回 false = 当前无连接可应答。 */
    @Volatile
    var handler: ((interactionId: String, answerJson: String) -> Boolean)? = null

    fun dispatch(interactionId: String, answerJson: String): Boolean {
        val h = handler ?: return false
        return h(interactionId, answerJson)
    }
}

class ElicitationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val interactionId = intent.getStringExtra(EXTRA_INTERACTION) ?: return
        val nm = context.getSystemService(NotificationManager::class.java)

        val answerJson: String? = when (intent.action) {
            ACTION_REPLY_TEXT -> {
                val results = android.app.RemoteInput.getResultsFromIntent(intent)
                val text = results?.getCharSequence(KEY_TEXT_REPLY)?.toString()?.trim()
                if (text.isNullOrBlank()) null else """{"freeText":${ElicitationNotifier.quote(text)}}"""
            }
            else -> intent.getStringExtra(EXTRA_ANSWER)
        }

        if (answerJson == null) return
        val ok = ElicitationBridge.dispatch(interactionId, answerJson)
        nm.cancel(ElicitationNotifier.notifId(interactionId))
        if (!ok) {
            nm.notify(
                ElicitationNotifier.notifId(interactionId) + 1,
                Notification.Builder(context, ElicitationNotifier.CH_ELICITATIONS)
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle("ZCode Remote 未连接")
                    .setContentText("回复未发出去，打开 App 重新处理")
                    .setAutoCancel(true)
                    .build()
            )
        }
    }

    companion object {
        const val ACTION_RESOLVE = "com.zcode.remote.action.RESOLVE_ELICITATION"
        const val ACTION_REPLY_TEXT = "com.zcode.remote.action.REPLY_ELICITATION_TEXT"
        const val EXTRA_INTERACTION = "interactionId"
        const val EXTRA_ANSWER = "answerJson"
        const val KEY_TEXT_REPLY = "key_text_reply"
    }
}

object ElicitationNotifier {
    const val CH_ELICITATIONS = "elicitations"

    /** 与审批通知错开 ID 区间（审批 2000..202000），避免互相 cancel。 */
    private const val ID_BASE = 300_000
    private const val ID_SPAN = 100_000
    private const val MAX_ACTIONS = 3

    fun notifId(interactionId: String): Int =
        ID_BASE + ((interactionId.hashCode() and 0x7fffffff) % ID_SPAN)

    fun sync(context: Context, pending: List<PendingElicitation>) {
        ensureChannel(context)
        val nm = context.getSystemService(NotificationManager::class.java)
        val live = (pending.map { notifId(it.interactionId) } +
                pending.map { notifId(it.interactionId) + 1 }).toSet()
        nm.activeNotifications
            ?.asSequence()
            ?.filter { it.id in ID_BASE until (ID_BASE + ID_SPAN * 2) }
            ?.filter { it.id !in live }
            ?.forEach { nm.cancel(it.id) }
        for (el in pending) nm.notify(notifId(el.interactionId), build(context, el))
    }

    fun clearAll(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.activeNotifications
            ?.asSequence()
            ?.filter { it.id in ID_BASE until (ID_BASE + ID_SPAN * 2) }
            ?.forEach { nm.cancel(it.id) }
    }

    private fun build(context: Context, el: PendingElicitation): Notification {
        val title = when {
            el.isPlanApproval -> "计划待批准"
            el.questions.isNotEmpty() -> "需要你的回答：${el.toolName ?: "表单"}"
            else -> "需要你补充信息"
        }
        val body = listOfNotNull(
            el.prompt?.take(120),
            el.questions.firstOrNull()?.question?.take(140),
            el.plan?.take(120),
        ).joinToString("\n").ifBlank { "桌面端在等你的输入" }

        val contentIntent = PendingIntent.getActivity(
            context, notifId(el.interactionId),
            Intent(context, MainActivity::class.java).putExtra("openSession", el.sessionId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val b = Notification.Builder(context, CH_ELICITATIONS)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setPriority(Notification.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_SOCIAL)
            .setContentIntent(contentIntent)
            .setAutoCancel(false)
            .setOngoing(true)

        el.autoResolveAt?.let {
            b.setWhen(it)
            b.setSubText("桌面端 ${DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)} 自动决议")
        }

        // 只对「答案能被一个动作完整表达」的形态给快捷按钮，其余引导进 App
        // 自由文本 / 开放式提问支持通知栏 RemoteInput 直接内联回复（Sprint 4）
        if (el.freeText || el.questions.isEmpty()) {
            val remoteInput = android.app.RemoteInput.Builder(ElicitationReceiver.KEY_TEXT_REPLY)
                .setLabel("输入回复内容…")
                .build()
            val replyIntent = Intent(ElicitationReceiver.ACTION_REPLY_TEXT)
                .setClass(context, ElicitationReceiver::class.java)
                .putExtra(ElicitationReceiver.EXTRA_INTERACTION, el.interactionId)
            val replyPendingIntent = PendingIntent.getBroadcast(
                context,
                notifId(el.interactionId) + 99,
                replyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            val replyAction = Notification.Action.Builder(
                null, "直接回复", replyPendingIntent
            ).addRemoteInput(remoteInput).build()
            b.addAction(replyAction)
        }

        val quick = quickActions(el)
        if (quick.isEmpty()) {
            if (!el.freeText && el.questions.isNotEmpty()) {
                b.addAction(Notification.Action.Builder(null, "打开 App 处理", contentIntent).build())
            }
        } else {
            quick.forEachIndexed { i, (label, answerJson) ->
                b.addAction(
                    Notification.Action.Builder(
                        null, label, actionIntent(context, el, answerJson, i)
                    ).build()
                )
            }
        }
        return b.build()
    }

    /** 返回 (按钮文案, 答案 JSON) 列表；空 = 不给快捷动作。 */
    private fun quickActions(el: PendingElicitation): List<Pair<String, String>> {
        if (el.isPlanApproval) {
            return listOf("批准计划" to """{"action":"accept"}""", "拒绝" to """{"action":"decline"}""")
        }
        // 单题、单选、选项数不超过按钮额度：每个选项给一个按钮
        val q = el.questions.singleOrNull()
        if (q != null && !q.multiSelect && q.options.size in 1..MAX_ACTIONS && !el.freeText) {
            return q.options.take(MAX_ACTIONS).map { opt ->
                opt.label.ifBlank { opt.value }.take(12) to
                        """{"action":"accept","content":{"answer":${quote(opt.value)}}}"""
            }
        }
        return emptyList()
    }

    fun quote(v: String): String =
        "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun actionIntent(
        context: Context, el: PendingElicitation, answerJson: String, index: Int,
    ): PendingIntent {
        val intent = Intent(ElicitationReceiver.ACTION_RESOLVE)
            .setClass(context, ElicitationReceiver::class.java)
            .putExtra(ElicitationReceiver.EXTRA_INTERACTION, el.interactionId)
            .putExtra(ElicitationReceiver.EXTRA_ANSWER, answerJson)
        return PendingIntent.getBroadcast(
            context, notifId(el.interactionId) + index, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH_ELICITATIONS) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CH_ELICITATIONS, "表单交互", NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "桌面端询问（AskUserQuestion / 计划批准）时的快捷回答入口"
                    enableVibration(true)
                }
            )
        }
    }
}
