package com.zcode.remote.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zcode.remote.BuildConfig
import com.zcode.remote.R
import com.zcode.remote.notify.NotificationPermission
import com.zcode.remote.relay.RelayState
import com.zcode.remote.relay.RpcChannel
import com.zcode.remote.relay.V2AccountApi
import com.zcode.remote.repo.UpdateRepository
import com.zcode.remote.storage.PairedDevice
import com.zcode.remote.ui.theme.ZCodeTokens
import com.zcode.remote.ui.theme.statusPendingTone
import com.zcode.remote.util.UiText
import com.zcode.remote.util.UiText.Companion.res
import com.zcode.remote.util.asString
import com.zcode.remote.util.resolve
import kotlinx.coroutines.launch

/**
 * 独立的系统与设备设置中心 (Settings Tab)
 * 从主屏彻底剥离所有配置项，按模块清晰组织：设备管理、通信线路、外观主题、系统保活、版本更新。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
    updateState: UpdateRepository.UpdateState,
    hasGithubToken: Boolean,
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
    // 走查 #10：不再用已存的 Token 预填输入框（原 `remember(githubToken){ mutableStateOf(githubToken) }`
    // 会在打开设置页时把明文 Token 铺在屏幕上）。改为空白起手，仅在用户主动输入时承载草稿。
    var tokenDraft by remember { mutableStateOf("") }
    var showDevGuide by remember { mutableStateOf(false) }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // 顶栏
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 12.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f),
            )
            FilledTonalButton(
                onClick = onRescan,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier.height(34.dp),
            ) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.settings_pair_new), style = MaterialTheme.typography.labelSmall)
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 80.dp),
        ) {
            // 1. 当前连接设备状态卡片
            item {
                SectionHeader(stringResource(R.string.settings_section_current_device))
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = CardDefaults.outlinedCardBorder(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier =
                                    Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.AccountBox,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = deviceName.ifEmpty { stringResource(R.string.common_unnamed_device) },
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                )
                                Spacer(Modifier.height(2.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    StatusDot(state)
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text =
                                            stringResource(
                                                R.string.settings_bridge_joined,
                                                statusLabel(state).asString(),
                                                bridgeLabel(bridgeState).asString(),
                                            ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            OutlinedButton(
                                onClick = onDisconnect,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                modifier = Modifier.height(32.dp),
                            ) {
                                Text(stringResource(R.string.settings_disconnect), style = MaterialTheme.typography.labelSmall)
                            }
                        }

                        if (state is RelayState.Failed) {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = state.message ?: stringResource(R.string.settings_connection_failed),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(8.dp),
                                )
                            }
                        }
                    }
                }
            }

            // 2.5 ZCode 账号设备（V2，≥3.16）：登录后可见账号绑定PC（PROTOCOL.md §11）
            item { AccountDevicesSection() }

            // 3. 多机管理列表
            val otherDevices = devices.filter { it.deviceSid != activeSid }
            if (otherDevices.isNotEmpty()) {
                item {
                    SectionHeader(stringResource(R.string.settings_section_other_devices, otherDevices.size))
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = CardDefaults.outlinedCardBorder(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            otherDevices.forEachIndexed { index, dev ->
                                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                Row(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = dev.deviceName ?: dev.deviceSid,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = "SID: ${dev.deviceSid.take(12)}…",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    TextButton(
                                        onClick = { onSwitchDevice(dev.deviceSid) },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                    ) {
                                        Text(stringResource(R.string.settings_switch_connection), style = MaterialTheme.typography.labelSmall)
                                    }
                                    IconButton(
                                        onClick = { pendingRemove = dev.deviceSid },
                                        modifier = Modifier.size(32.dp),
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            null,
                                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 3. 通信与外观配置
            item {
                SectionHeader(stringResource(R.string.settings_section_network))
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = CardDefaults.outlinedCardBorder(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        // 线路切换（FlowRow：窄屏下整颗 Chip 换行，文字不折断）
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.settings_relay_mode_label), style = MaterialTheme.typography.labelLarge)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                listOf(
                                    "auto" to stringResource(R.string.settings_relay_auto),
                                    "main" to stringResource(R.string.settings_relay_main),
                                    "backup" to stringResource(R.string.settings_relay_backup),
                                    "custom" to stringResource(R.string.settings_relay_custom),
                                ).forEach { (mode, label) ->
                                    FilterChip(
                                        selected = endpointMode == mode,
                                        onClick = {
                                            if (mode == "custom") {
                                                editingCustom = customRelayUrl
                                            } else {
                                                onEndpointChange(mode, null)
                                            }
                                        },
                                        label = {
                                            Text(
                                                label,
                                                style = MaterialTheme.typography.labelSmall,
                                                maxLines = 1,
                                            )
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                    )
                                }
                            }
                            // 自建中继地址卡片（与其它配置项统一的展示格式）
                            if (endpointMode == "custom") {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(10.dp),
                                    // 与本页其它卡片统一的描边（原借 ButtonDefaults.outlinedButtonBorder，已弃用）
                                    border = CardDefaults.outlinedCardBorder(),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = stringResource(R.string.settings_custom_relay_label),
                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Spacer(Modifier.height(2.dp))
                                            Text(
                                                text = customRelayUrl.ifBlank { "未设置 · 点击右侧「编辑」填写 wss:// 地址" },
                                                style =
                                                    MaterialTheme.typography.bodySmall.copy(
                                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                                        fontSize = 12.sp,
                                                    ),
                                                color =
                                                    if (customRelayUrl.isBlank()) {
                                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                                    } else {
                                                        MaterialTheme.colorScheme.primary
                                                    },
                                                maxLines = 1,
                                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                            )
                                        }
                                        TextButton(
                                            onClick = { editingCustom = customRelayUrl },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                            modifier = Modifier.height(30.dp),
                                        ) {
                                            Text(stringResource(R.string.settings_edit), style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        // 主题切换
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.settings_appearance), style = MaterialTheme.typography.labelLarge)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                listOf(
                                    "dark" to stringResource(R.string.settings_theme_dark),
                                    "system" to stringResource(R.string.settings_theme_system),
                                    "light" to stringResource(R.string.settings_theme_light),
                                ).forEach { (mode, label) ->
                                    FilterChip(
                                        selected = themeMode == mode,
                                        onClick = { onThemeChange(mode) },
                                        label = {
                                            Text(
                                                label,
                                                style = MaterialTheme.typography.labelSmall,
                                                maxLines = 1,
                                            )
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                    )
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        // 通知权限状态与申请（Android 13+ 运行时权限）。
                        // 若不在此申请，装机后审批通知会被系统静默丢弃（招牌功能直接失效）。
                        NotificationPermissionRow()

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        // 保活指引入口
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column {
                                Text(stringResource(R.string.settings_keepalive), style = MaterialTheme.typography.labelLarge)
                                Text(
                                    stringResource(R.string.settings_keepalive_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            OutlinedButton(
                                onClick = onShowGuide,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                modifier = Modifier.height(32.dp),
                            ) {
                                Text(stringResource(R.string.settings_view_guide), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            // 4. 诊断日志（§11.8 可观测性：本地脱敏记录 + 用户主动导出）
            item {
                SectionHeader(stringResource(R.string.settings_section_diagnostics))
                DiagnosticsSection()
            }

            // 5. 版本更新与维护
            item {
                SectionHeader(stringResource(R.string.settings_section_update))
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = CardDefaults.outlinedCardBorder(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column {
                                Text(
                                    text = "ZCode Remote",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                )
                                Text(
                                    text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Button(
                                onClick = onCheckUpdate,
                                enabled = updateState !is UpdateRepository.UpdateState.Checking,
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                                modifier = Modifier.height(34.dp),
                            ) {
                                if (updateState is UpdateRepository.UpdateState.Checking) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.settings_checking), style = MaterialTheme.typography.labelSmall)
                                } else {
                                    Text(stringResource(R.string.settings_check_update), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }

                        // 更新状态展示
                        when (updateState) {
                            is UpdateRepository.UpdateState.Success -> {
                                val info = updateState.info
                                if (info.hasNew) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(
                                                text = stringResource(R.string.settings_new_version, info.tagName),
                                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            )
                                            if (!info.body.isNullOrBlank()) {
                                                Text(
                                                    text = info.body.take(200),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                                    maxLines = 3,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                if (!info.downloadUrl.isNullOrBlank()) {
                                                    Button(
                                                        onClick = {
                                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.downloadUrl)))
                                                        },
                                                        modifier = Modifier.weight(1f).height(32.dp),
                                                        contentPadding = PaddingValues(0.dp),
                                                    ) {
                                                        Text(stringResource(R.string.settings_download_apk), style = MaterialTheme.typography.labelSmall)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    Text(
                                        text = stringResource(R.string.settings_latest_version, info.tagName),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = ZCodeTokens.StatusOnline,
                                    )
                                }
                            }
                            is UpdateRepository.UpdateState.Error -> {
                                Text(
                                    text = stringResource(R.string.settings_check_failed, updateState.message),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            else -> Unit
                        }

                        // 私有仓库 GitHub Token
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(stringResource(R.string.settings_github_token), style = MaterialTheme.typography.labelMedium)
                            TextButton(
                                onClick = { editingToken = !editingToken },
                                contentPadding = PaddingValues(0.dp),
                            ) {
                                Text(
                                    if (editingToken) {
                                        stringResource(R.string.common_collapse)
                                    } else if (hasGithubToken) {
                                        stringResource(R.string.settings_token_configured_short)
                                    } else {
                                        stringResource(R.string.settings_token_configure)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                        if (editingToken) {
                            OutlinedTextField(
                                value = tokenDraft,
                                onValueChange = { tokenDraft = it },
                                placeholder = {
                                    Text(
                                        ghTokenPlaceholder(hasGithubToken).asString(),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                },
                                singleLine = true,
                                // 走查 #10：Token 属敏感凭据，输入时以圆点掩码显示（不再明文铺屏）
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                trailingIcon = {
                                    TextButton(onClick = {
                                        val draft = tokenDraft.trim()
                                        onSetGithubToken(draft)
                                        tokenDraft = ""
                                        editingToken = false
                                        Toast.makeText(context, ghTokenSavedToast(draft).resolve(context), Toast.LENGTH_SHORT).show()
                                    }) { Text(stringResource(R.string.settings_save)) }
                                },
                            )
                        }

                        // 电脑端更新推送指南
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(stringResource(R.string.settings_pc_install_guide), style = MaterialTheme.typography.labelMedium)
                            TextButton(onClick = { showDevGuide = !showDevGuide }, contentPadding = PaddingValues(0.dp)) {
                                Text(
                                    if (showDevGuide) stringResource(R.string.common_collapse) else stringResource(R.string.common_view),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                        AnimatedVisibility(visible = showDevGuide) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_pc_install_body),
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(10.dp),
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
            title = { Text(stringResource(R.string.settings_remove_title)) },
            text = { Text(stringResource(R.string.settings_remove_body, name)) },
            confirmButton = {
                TextButton(onClick = {
                    onRemoveDevice(sid)
                    pendingRemove = null
                }) { Text(stringResource(R.string.settings_remove_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    // 自定义中继弹窗
    editingCustom?.let { current ->
        AlertDialog(
            onDismissRequest = { editingCustom = null },
            title = { Text(stringResource(R.string.settings_custom_relay_dialog_title)) },
            text = {
                OutlinedTextField(
                    value = current,
                    onValueChange = { editingCustom = it },
                    placeholder = { Text(stringResource(R.string.settings_custom_relay_hint)) },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onEndpointChange("custom", editingCustom)
                    editingCustom = null
                }) { Text(stringResource(R.string.settings_save_reconnect)) }
            },
            dismissButton = {
                TextButton(onClick = { editingCustom = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

/**
 * 通知权限状态行（Android 13+ POST_NOTIFICATIONS）。
 *
 * 未授予时展示醒目提示与「去开启」按钮：先尝试运行时申请（首次），
 * 若已被永久拒绝（`shouldShowRequestPermissionRationale` 为 false 且再次申请无效）
 * 则直接跳系统通知设置页——两条路径都不打断用户当前操作，只补上缺失的能力。
 *
 * 状态在 `ON_RESUME` 时刷新：用户从系统设置页返回后本行即时更新，无需手动刷新。
 */
@Composable
private fun NotificationPermissionRow() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(NotificationPermission.isGranted(context)) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    val launcher =
        androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts
                .RequestPermission(),
        ) { result ->
            granted = result
            if (!result) {
                // 用户刚拒绝（或此前已永久拒绝）：引导去系统设置手动开启
                runCatching { context.startActivity(NotificationPermission.settingsIntent(context)) }
            }
        }

    // 从系统设置返回 / 首次进入时刷新状态（不申请，只读）
    DisposableEffect(lifecycleOwner) {
        val observer =
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                    granted = NotificationPermission.isGranted(context)
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_notif_permission), style = MaterialTheme.typography.labelLarge)
            Text(
                text =
                    if (granted) {
                        stringResource(R.string.settings_notif_granted)
                    } else {
                        stringResource(R.string.settings_notif_denied)
                    },
                style = MaterialTheme.typography.bodySmall,
                color = if (granted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        }
        if (!granted) {
            OutlinedButton(
                onClick = {
                    if (NotificationPermission.isRuntimeRequestRequired(android.os.Build.VERSION.SDK_INT)) {
                        // Android 13+：走运行时申请。首次弹系统框；已被永久拒绝时会立刻
                        // 回调 false，由 launcher 回调转去系统设置页（见上方）。
                        launcher.launch(NotificationPermission.PERMISSION)
                    } else {
                        // 低版本无该运行时权限：只能是系统通知总开关被关，直接跳设置。
                        runCatching { context.startActivity(NotificationPermission.settingsIntent(context)) }
                    }
                },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier.height(32.dp),
            ) {
                Text(stringResource(R.string.settings_notif_grant), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun StatusDot(state: RelayState) {
    val color =
        when (state) {
            is RelayState.Paired -> ZCodeTokens.StatusOnline
            is RelayState.WaitingPeer -> statusPendingTone()
            is RelayState.Failed -> ZCodeTokens.StatusError
            else -> ZCodeTokens.StatusOffline
        }
    Canvas(Modifier.size(8.dp)) { drawCircle(color) }
}

private fun statusLabel(state: RelayState): UiText =
    when (state) {
        RelayState.Idle -> res(R.string.settings_status_idle)
        RelayState.Connecting -> res(R.string.settings_status_connecting)
        RelayState.Authenticating -> res(R.string.settings_status_authenticating)
        RelayState.WaitingPeer -> res(R.string.settings_status_waiting_peer)
        RelayState.Paired -> res(R.string.settings_status_paired)
        is RelayState.Failed -> res(R.string.settings_status_failed)
    }

private fun bridgeLabel(b: RpcChannel.BridgeState): UiText =
    when (b) {
        is RpcChannel.BridgeState.Closed -> res(R.string.bridge_state_closed)
        is RpcChannel.BridgeState.Opening -> res(R.string.bridge_state_opening)
        is RpcChannel.BridgeState.Ready -> res(R.string.bridge_state_ready)
        is RpcChannel.BridgeState.Failed -> res(R.string.bridge_state_failed)
    }

/** 走查 #10：Token 输入框占位文案（抽成纯函数，避免给 SettingsTab 增加圈复杂度）。 */
internal fun ghTokenPlaceholder(configured: Boolean): UiText =
    if (configured) res(R.string.gh_token_placeholder_configured) else res(R.string.gh_token_placeholder_empty)

/** 走查 #10：保存 Token 的反馈文案——空草稿即清除。 */
internal fun ghTokenSavedToast(draft: String): UiText = if (draft.isEmpty()) res(R.string.gh_token_cleared) else res(R.string.gh_token_saved)

/**
 * ZCode 账号设备（V2，ZCode ≥3.16）：显示登录态（JWT 是否就绪）与账号绑定的 PC 列表。
 * 数据源 `GET /api/v1/remote/device`（PROTOCOL.md §11）。自包含拉取（TokenStore + V2AccountApi），
 * 不侵入 SettingsTab 调用链。未登录时折叠为一行提示。
 */
@Composable
private fun AccountDevicesSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var jwt by remember { mutableStateOf<String?>(null) }
    var devices by remember { mutableStateOf<List<V2AccountApi.RemoteDevice>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        jwt =
            com.zcode.remote.storage
                .TokenStore(context)
                .readZcodeJwt()
    }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "ZCode 账号（新版协议）",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (jwt != null) {
                    TextButton(
                        onClick = {
                            if (loading) return@TextButton
                            loading = true
                            error = null
                            scope.launch {
                                V2AccountApi
                                    .listDevices(jwt!!)
                                    .onSuccess { devices = it }
                                    .onFailure { error = it.message }
                                loading = false
                            }
                        },
                        enabled = !loading,
                    ) { Text(if (loading) "加载中…" else "刷新设备列表") }
                }
            }
            if (jwt == null) {
                Text(
                    text = "未登录：使用 3.16+ 新版二维码配对时会在登录流程中自动获取账号凭据。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                AccountDevicesBody(devices, error)
            }
        }
    }
}

/** 设备列表状态区（从 [AccountDevicesSection] 抽出以控制圈复杂度）。 */
@Composable
private fun AccountDevicesBody(
    devices: List<V2AccountApi.RemoteDevice>?,
    error: String?,
) {
    val list = devices
    when {
        error != null ->
            Text(
                "获取失败：$error",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        list == null ->
            Text(
                "已登录（凭据已加密存储）。点击「刷新设备列表」查看账号绑定的电脑。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        list.isEmpty() ->
            Text("账号下暂无绑定设备", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else ->
            list.forEach { d -> AccountDeviceRow(d) }
    }
}

/** 单台绑定设备行。 */
@Composable
private fun AccountDeviceRow(d: V2AccountApi.RemoteDevice) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Column(Modifier.weight(1f)) {
            Text(
                d.meta?.name ?: d.deviceSid.take(16),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${d.meta?.platform ?: "?"} · ${d.meta?.version ?: "?"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            if (d.online) "在线" else "离线",
            style = MaterialTheme.typography.labelMedium,
            color = if (d.online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
