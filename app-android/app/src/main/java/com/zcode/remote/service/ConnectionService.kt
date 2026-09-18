package com.zcode.remote.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

/**
 * M2 占位：中继长连接前台服务（HyperOS 保活与推送的载体）。
 * M1 阶段连接生命周期跟随 Activity；M2 迁移到此服务并接通知审批。
 */
class ConnectionService : Service() {

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "ZCode 连接", NotificationManager.IMPORTANCE_HIGH)
        )
        startForeground(NOTIFY_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val builder = if (android.os.Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder.setContentTitle("ZCode Remote")
            .setContentText("保持与 PC 的连接")
            .setSmallIcon(com.zcode.remote.R.drawable.ic_launcher)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "connection"
        private const val NOTIFY_ID = 1
        fun start(context: Context) =
            context.startForegroundService(Intent(context, ConnectionService::class.java))
        fun stop(context: Context) = context.stopService(Intent(context, ConnectionService::class.java))
    }
}
