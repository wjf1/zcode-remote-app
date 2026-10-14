package com.zcode.remote.notify

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 通知权限状态判定（Android 13+ 的 POST_NOTIFICATIONS）。
 *
 * 背景：审批通知是本 App 的招牌能力（锁屏直接批准/拒绝），而 Android 13 起
 * 通知权限为**运行时权限且默认拒绝**——不在运行时申请，装机后审批通知全部静默丢弃，
 * 用户只会看到「会话有审批」却收不到任何提醒。此前 Manifest 已声明该权限，但全工程
 * 没有任何 `RequestPermission` 调用它（仅相机/麦克风有）。
 *
 * 本对象把「要不要申请 / 要不要引导」的判定收敛为纯逻辑，便于 JVM 单测钉死边界；
 * 实际申请与跳转系统设置由 UI 层（[com.zcode.remote.ui.screens.SettingsTab] 与
 * MainActivity 的首启引导）发起。
 */
object NotificationPermission {
    /** 运行时申请所需的权限名（仅 Android 13+ 需要；低版本恒视为已授予）。 */
    const val PERMISSION: String = Manifest.permission.POST_NOTIFICATIONS

    /** 低于 Android 13 无需运行时申请（POST_NOTIFICATIONS 不存在）。 */
    fun isRuntimeRequestRequired(sdkInt: Int): Boolean = sdkInt >= Build.VERSION_CODES.TIRAMISU

    /**
     * 当前是否已授予通知权限（含「系统级通知总开关被关」的判定）。
     *
     * 判定口径与业务语义对齐：只要系统不会真的弹出通知，就返回 false——
     * 这样 UI 的引导入口在「权限已授予但用户在系统设置里关掉了开关」时同样出现。
     */
    fun isGranted(context: Context): Boolean {
        // ① 运行时权限（Android 13+）
        if (isRuntimeRequestRequired(Build.VERSION.SDK_INT) &&
            ContextCompat.checkSelfPermission(context, PERMISSION) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        // ② 系统通知总开关（低版本也要判：用户在系统设置里关掉通知同样收不到）
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /**
     * 是否应向用户展示「去开启通知」的引导条。
     *
     * 与 [isGranted] 同义，但单独命名以便调用点表达意图（未来若需要区分
     * 「首次可选」与「用户明确拒绝后不再打扰」可在此加判定，调用点无需改动）。
     */
    fun shouldPrompt(context: Context): Boolean = !isGranted(context)

    /** 跳转本应用的系统通知设置页 Intent（供 UI 层 `startActivity` 使用）。 */
    fun settingsIntent(context: Context): android.content.Intent =
        android.content
            .Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)

    /** 系统是否具备通知能力（某些设备/极度精简系统上 NotificationManager 可能为空）。 */
    fun managerOf(context: Context): NotificationManager? = context.getSystemService(NotificationManager::class.java)
}
