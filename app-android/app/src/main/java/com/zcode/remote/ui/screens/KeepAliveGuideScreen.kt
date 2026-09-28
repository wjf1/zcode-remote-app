package com.zcode.remote.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 保活引导（M3）：HyperOS/MIUI 上保证审批通知可达的分步设置。
 * App 自身的保障是前台服务 + 高优先级通知；这一页解决厂商侧的拦截。
 */
@Composable
fun KeepAliveGuideScreen(onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row {
            TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) { Text("← 返回") }
        }
        Text("保活引导（小米 / HyperOS）", style = MaterialTheme.typography.titleLarge)
        Text(
            "审批通知的可靠性依赖以下系统授权。桌面端请求权限时，只有通知能及时送达，" +
                    "你才能在锁屏上直接批准/拒绝。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val steps = listOf(
            Step("1. 允许通知", "系统设置 → 通知与控制中心 → 应用通知 → ZCode Remote → 打开全部通知。" +
                    "审批类通知是高优先级，锁屏也会弹出。"),
            Step("2. 打开自启动", "系统设置 → 应用管理 → ZCode Remote → 自启动 → 打开。" +
                    "没有自启动权限，后台连接会被系统随时杀掉。"),
            Step("3. 省电策略设为无限制", "系统设置 → 应用管理 → ZCode Remote → 省电策略 → 无限制。" +
                    "「智能限制后台」会掐断长连接，导致审批收不到。"),
            Step("4. 锁定后台任务卡", "最近任务里下拉 ZCode Remote 卡片点锁头图标，防止一键清理误杀。"),
            Step("5. 常驻通知不要关", "App 的「连接保持中」前台通知是连接的命脉，关闭它等于断开远程控制。"),
            Step("6. （可选）锁屏显示", "通知管理 → 锁屏通知 → 显示，锁屏状态下不用解锁就能看到审批内容。"),
        )
        steps.forEach { s ->
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(s.title, style = MaterialTheme.typography.titleSmall)
                    Text(s.body, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text(
            "注：官方桌面端与本 App 同一时刻只能有一端在线（单终端槽位）；若桌面端 Web 版开着，App 会被踢下线。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private data class Step(val title: String, val body: String)
