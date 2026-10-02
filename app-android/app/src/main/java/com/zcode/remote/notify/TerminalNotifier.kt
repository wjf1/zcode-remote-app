package com.zcode.remote.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.zcode.remote.MainActivity
import com.zcode.remote.R

/**
 * 连接终态常驻通知（Sprint 2 / A 组第 3 项）。
 *
 * KICKED / AUTH_FAILED / PROTOCOL_MISMATCH 三种终态原来只在 App 内出横幅 —— 手机在兜里时
 * 用户会静默失去全部审批能力而毫不知情。现在发一条 ongoing 系统通知，点按打开 App 处理；
 * 连接回到任何非终态（含重连成功、主动断开）即撤除。
 */
object TerminalNotifier {
    private const val CHANNEL_ID = "terminal_state"
    private const val NOTIFY_ID = 2001

    fun show(context: Context, title: String, message: String?) {
        runCatching {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "连接终态提醒", NotificationManager.IMPORTANCE_HIGH)
            )
            val pi = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            nm.notify(
                NOTIFY_ID,
                Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle(title)
                    .setContentText(message ?: "连接已终止，打开 App 重新连接")
                    .setStyle(Notification.BigTextStyle().bigText(message ?: "连接已终止，打开 App 重新连接"))
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .build()
            )
        }
    }

    fun clear(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(NOTIFY_ID) }
    }
}
