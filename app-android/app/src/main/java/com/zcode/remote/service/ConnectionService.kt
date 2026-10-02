package com.zcode.remote.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * 中继长连接前台服务（Sprint 1 / P0-A，M2 占位至此真正启用）。
 *
 * 职责 = 进程保活：持有一条 LOW 重要性的常驻通知，让系统不把承载连接的进程当缓存回收
 * （此前连接生命周期跟随 Activity，进程一旦被回收，锁屏审批即全部失联）。
 *
 * 类型用 specialUse：Android 15 对 dataSync 型前台服务有「24 小时内累计 6 小时」的硬超时
 * （超时回调 onTimeout 后须数秒内 stopSelf，否则崩溃），撑不过一个通宵；specialUse 不受此限，
 * 侧载 APK 亦不经 Play 政策审查（HANDOVER §1：纯自用不分发）。
 *
 * 连接的建立与编排仍在 AppViewModel（经 [ConnectionScope]）——服务不自己开连接；
 * 系统重启本服务（START_STICKY）后，由用户下次打开 App 时经 VM 重连。
 */
class ConnectionService : Service() {

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "ZCode 连接", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    /** Android 15 前台服务超时兜底（specialUse 正常不触发；触发则主动让位，避免被系统强杀崩溃）。 */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    private fun startForegroundCompat() {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFY_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFY_ID, notif)
        }
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ZCode Remote")
            .setContentText("保持与 PC 的连接")
            .setSmallIcon(com.zcode.remote.R.drawable.ic_launcher)
            .setOngoing(true)
            .build()

    companion object {
        private const val CHANNEL_ID = "connection"
        private const val NOTIFY_ID = 1
        fun start(context: Context) =
            context.startForegroundService(Intent(context, ConnectionService::class.java))
        fun stop(context: Context) = context.stopService(Intent(context, ConnectionService::class.java))
    }
}
