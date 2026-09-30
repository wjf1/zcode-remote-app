package com.zcode.remote.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.zcode.remote.MainActivity
import com.zcode.remote.R

/**
 * 桌面 Widget（P2-3 按需池）：待处理角标 + 一键进 App。
 *
 * 纯本地 UI，零协议改动：数据是 App 内已汇总的待处理总数（审批 + 表单交互，
 * E-1 权威角标），由 [sync] 在计数/连接状态变化时从 App 内主动推送——
 * updatePeriodMillis=0 不轮询；App 未运行时显示最后一次状态（与官方同类做法一致）。
 *
 * RemoteViews 而非 Glance：内容只有三行文本，不值得为此引一套新依赖。
 */
class PendingWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        // App 侧没有最新数据（刚重启/未连接）：渲染未连接态；真实状态由 App 推送覆盖
        render(context, mgr, ids, pendingTotal = 0, connected = false)
    }

    companion object {
        /** App 内计数或连接状态变化时调用；桌面上没放 widget 时为 no-op。 */
        fun sync(context: Context, pendingTotal: Int, connected: Boolean) {
            val mgr = AppWidgetManager.getInstance(context) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(context, PendingWidgetProvider::class.java))
            if (ids.isEmpty()) return
            render(context, mgr, ids, pendingTotal, connected)
        }

        private fun render(
            context: Context,
            mgr: AppWidgetManager,
            ids: IntArray,
            pendingTotal: Int,
            connected: Boolean,
        ) {
            val launch = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val value = when {
                !connected -> "—"
                else -> pendingTotal.toString()
            }
            val hint = when {
                !connected -> "未连接 · 点按打开 App"
                pendingTotal > 0 -> "项待处理 · 点按处理"
                else -> "运行正常"
            }
            val views = RemoteViews(context.packageName, R.layout.widget_pending).apply {
                setTextViewText(R.id.widget_value, value)
                setTextViewText(R.id.widget_hint, hint)
                setOnClickPendingIntent(R.id.widget_root, launch)
            }
            mgr.updateAppWidget(ids, views)
        }
    }
}
