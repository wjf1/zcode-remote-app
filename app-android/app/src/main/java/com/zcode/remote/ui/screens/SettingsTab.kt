package com.zcode.remote.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zcode.remote.AppViewModel
import com.zcode.remote.BuildConfig
import com.zcode.remote.relay.FailureReason
import com.zcode.remote.relay.RelayState
import com.zcode.remote.relay.RpcChannel
import com.zcode.remote.storage.PairedDevice
import com.zcode.remote.ui.theme.ZCodeTokens

/**
 * 独立的系统与设备设置中心 (Settings Tab)
 * 从主屏彻底剥离所有配置项，按模块清晰组织：设备管理、通信线路、外观主题、系统保活、版本更新。
 */
@Composable
fun SettingsTab(
    deviceName: String,
    state: RelayState,
    bridgeState: RpcChannel.BridgeState,
    devices: List<PairedDevice>,
    activeSid: String?,
    endpointMode: String,
    customRelayUrl: String,
    themeMode: String,
    updateState: AppViewModel.UpdateState,
    githubToken: String,
    onSwitchDevice: (String) -> Unit,
    onRemoveDevice: (String) -> Unit,
    onDisconnect: () -> Unit,
    onRescan: () -> Unit,
    onEndpointChange: (String, String?) -> Unit,
    onThemeChange: (String) -> Unit,
    onShowGuide: () -> Unit,
    onSetGithubToken: (String) -> Unit,
    onCheckUpdate: () -> Unit,
) {
    val context = LocalContext.current
    var pendingRemove by remember { mutableStateOf<String?>(null) }
    var editingCustom by remember { mutableStateOf<String?>(null) }
    var editingToken by remember { mutableStateOf(false) }
    var tokenDraft by remember(githubToken) { mutableStateOf(githubToken) }
    var showDevGuide by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // 顶栏
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 12.dp)
        ) {
            Text(
                text = "设置与设备",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f)
            )
            FilledTonalButton(
                onClick = onRescan,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier.height(34.dp)
            ) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("配对新设备", style = MaterialTheme.typography.labelSmall)
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            // 1. 当前连接设备状态卡片
            item {
                SectionHeader("当前连接设备")
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = CardDefaults.outlinedCardBorder(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.AccountBox, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = deviceName.ifEmpty { "未命名设备" },
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                                Spacer(Modifier.height(2.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    StatusDot(state)
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "${statusLabel(state)} · 会话桥 ${bridgeLabel(bridgeState)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            OutlinedButton(
                                onClick = onDisconnect,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("断开", style = MaterialTheme.typography.labelSmall)
                            }
                        }

                        if (state is RelayState.Failed) {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = state.message ?: "连接失败",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 2. 多机管理列表
            val otherDevices = devices.filter { it.deviceSid != activeSid }
            if (otherDevices.isNotEmpty()) {
                item {
                    SectionHeader("已配对的其他设备 (${otherDevices.size})")
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = CardDefaults.outlinedCardBorder(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            otherDevices.forEachIndexed { index, dev ->
                                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = dev.deviceName ?: dev.deviceSid,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "SID: ${dev.deviceSid.take(12)}…",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    TextButton(
                                        onClick = { onSwitchDevice(dev.deviceSid) },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                    ) {
                                        Text("切换连接", style = MaterialTheme.typography.labelSmall)
                                    }
                                    IconButton(
                                        onClick = { pendingRemove = dev.deviceSid },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 3. 通信与外观配置
            item {
                SectionHeader("网络与个性化")
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = CardDefaults.outlinedCardBorder(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // 线路切换
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("中继线路模式", style = MaterialTheme.typography.labelLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(
                                    "auto" to "自动判定",
                                    "main" to "官方主线",
                                    "backup" to "官方备线",
                                    "custom" to "自建中继",
                                ).forEach { (mode, label) ->
                                    FilterChip(
                                        selected = endpointMode == mode,
                                        onClick = {
                                            if (mode == "custom") editingCustom = customRelayUrl
                                            else onEndpointChange(mode, null)
                                        },
                                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                }
                            }
                            // 自建中继地址卡片（与其它配置项统一的展示格式）
                            if (endpointMode == "custom") {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(10.dp),
                                    border = ButtonDefaults.outlinedButtonBorder,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "自定义中继地址",
                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Spacer(Modifier.height(2.dp))
                                            Text(
                                                text = customRelayUrl.ifBlank { "未设置 · 点击右侧「编辑」填写 wss:// 地址" },
                                                style = MaterialTheme.typography.bodySmall.copy(
                                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                                    fontSize = 12.sp
                                                ),
                                                color = if (customRelayUrl.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                                else MaterialTheme.colorScheme.primary,
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                            )
                                        }
                                        TextButton(
                                            onClick = { editingCustom = customRelayUrl },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                            modifier = Modifier.height(30.dp)
                                        ) {
                                            Text("编辑", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        // 主题切换
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("界面外观", style = MaterialTheme.typography.labelLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(
                                    "dark" to "深色模式",
                                    "system" to "跟随系统",
                                    "light" to "浅色模式"
                                ).forEach { (mode, label) ->
                                    FilterChip(
                                        selected = themeMode == mode,
                                        onClick = { onThemeChange(mode) },
                                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        // 保活指引入口
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("后台保活引导", style = MaterialTheme.typography.labelLarge)
                                Text("确保小米 / HyperOS 锁屏能收到审批推送", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            OutlinedButton(
                                onClick = onShowGuide,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("查看教程", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            // 4. 版本更新与维护
            item {
                SectionHeader("软件版本与更新")
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = CardDefaults.outlinedCardBorder(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = "ZCode Remote",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = "版本 v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Button(
                                onClick = onCheckUpdate,
                                enabled = updateState !is AppViewModel.UpdateState.Checking,
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                if (updateState is AppViewModel.UpdateState.Checking) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("检查中…", style = MaterialTheme.typography.labelSmall)
                                } else {
                                    Text("检查更新", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }

                        // 更新状态展示
                        when (updateState) {
                            is AppViewModel.UpdateState.Success -> {
                                val info = updateState.info
                                if (info.hasNew) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(
                                                text = "发现新版本：${info.tagName}",
                                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                            if (!info.body.isNullOrBlank()) {
                                                Text(
                                                    text = info.body.take(200),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                                    maxLines = 3,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                if (!info.downloadUrl.isNullOrBlank()) {
                                                    Button(
                                                        onClick = {
                                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.downloadUrl)))
                                                        },
                                                        modifier = Modifier.weight(1f).height(32.dp),
                                                        contentPadding = PaddingValues(0.dp)
                                                    ) {
                                                        Text("下载安装包", style = MaterialTheme.typography.labelSmall)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    Text(
                                        text = "已是最新版本 (${info.tagName})",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = ZCodeTokens.StatusOnline
                                    )
                                }
                            }
                            is AppViewModel.UpdateState.Error -> {
                                Text(
                                    text = "检查失败: ${updateState.message}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            else -> Unit
                        }

                        // 私有仓库 GitHub Token
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("GitHub 访问 Token", style = MaterialTheme.typography.labelMedium)
                            TextButton(
                                onClick = { editingToken = !editingToken },
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text(if (editingToken) "收起" else if (githubToken.isNotBlank()) "已配置" else "配置", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (editingToken) {
                            OutlinedTextField(
                                value = tokenDraft,
                                onValueChange = { tokenDraft = it },
                                placeholder = { Text("ghp_... 只读权限即可", style = MaterialTheme.typography.bodySmall) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                trailingIcon = {
                                    TextButton(onClick = {
                                        onSetGithubToken(tokenDraft.trim())
                                        editingToken = false
                                        Toast.makeText(context, "Token 已保存", Toast.LENGTH_SHORT).show()
                                    }) { Text("保存") }
                                }
                            )
                        }

                        // 电脑端更新推送指南
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("电脑一键覆盖安装指南", style = MaterialTheme.typography.labelMedium)
                            TextButton(onClick = { showDevGuide = !showDevGuide }, contentPadding = PaddingValues(0.dp)) {
                                Text(if (showDevGuide) "收起" else "查看", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        AnimatedVisibility(visible = showDevGuide) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "手机连电脑后，在仓库根目录运行：\n./build.sh install\n即可一键编译并覆盖推送到手机。",
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 移除设备二次确认弹窗
    pendingRemove?.let { sid ->
        val name = devices.firstOrNull { it.deviceSid == sid }?.deviceName ?: sid.take(12)
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("移除设备凭据") },
            text = { Text("确定移除「$name」？凭据删除后需重新在 PC 扫码才能连接。") },
            confirmButton = {
                TextButton(onClick = {
                    onRemoveDevice(sid)
                    pendingRemove = null
                }) { Text("确认移除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) { Text("取消") }
            }
        )
    }

    // 自定义中继弹窗
    editingCustom?.let { current ->
        AlertDialog(
            onDismissRequest = { editingCustom = null },
            title = { Text("自定义 WebSocket 中继") },
            text = {
                OutlinedTextField(
                    value = current,
                    onValueChange = { editingCustom = it },
                    placeholder = { Text("wss://host/ws") },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onEndpointChange("custom", editingCustom)
                    editingCustom = null
                }) { Text("保存并重连") }
            },
            dismissButton = {
                TextButton(onClick = { editingCustom = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

@Composable
private fun StatusDot(state: RelayState) {
    val color = when (state) {
        is RelayState.Paired -> ZCodeTokens.StatusOnline
        is RelayState.WaitingPeer -> ZCodeTokens.StatusPending
        is RelayState.Failed -> ZCodeTokens.StatusError
        else -> ZCodeTokens.StatusOffline
    }
    Canvas(Modifier.size(8.dp)) { drawCircle(color) }
}

private fun statusLabel(state: RelayState) = when (state) {
    RelayState.Idle -> "未连接"
    RelayState.Connecting -> "正在连接中继…"
    RelayState.Authenticating -> "鉴权握手中…"
    RelayState.WaitingPeer -> "等待桌面端接入"
    RelayState.Paired -> "已就绪连接"
    is RelayState.Failed -> "连接异常"
}

private fun bridgeLabel(b: RpcChannel.BridgeState) = when (b) {
    is RpcChannel.BridgeState.Closed -> "未开启"
    is RpcChannel.BridgeState.Opening -> "开启中…"
    is RpcChannel.BridgeState.Ready -> "就绪"
    is RpcChannel.BridgeState.Failed -> "失败"
}
