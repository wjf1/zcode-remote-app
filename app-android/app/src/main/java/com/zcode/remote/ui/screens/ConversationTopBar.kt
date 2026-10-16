package com.zcode.remote.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zcode.remote.R
import com.zcode.remote.relay.ConversationChannel
import com.zcode.remote.relay.WorkspaceConfigChannel
import com.zcode.remote.util.UiText
import com.zcode.remote.util.UiText.Companion.res
import com.zcode.remote.util.asString

/**
 * 会话页顶栏（§11.7 结构性拆分：自 `ConversationScreen.kt` 原样搬出，只移动不改行为）。
 * 标准返回导航 + 标题/元数据 + 失败重试胶囊 + 溢出菜单（文件/状态）+ 执行模式胶囊 +
 * 模型两级菜单。弹窗开关由 Route 层持有，经 [onOpenFiles] / [onOpenStatus] 打开。
 */

@Composable
internal fun ConversationTopBar(
    title: String,
    status: ConversationChannel.Status,
    meta: ConversationChannel.ConversationMeta,
    approvalsCount: Int,
    sessionFileCount: Int,
    statusEntryLabel: UiText?,
    currentSessionMode: String?,
    availableModels: List<WorkspaceConfigChannel.ModelOption>,
    modelReasoningLevels: Map<String, List<String>>,
    onBack: () -> Unit,
    onRetrySubscribe: () -> Unit,
    onSwitchMode: (String) -> Unit,
    onSwitchModel: (WorkspaceConfigChannel.ModelOption) -> Unit,
    onOpenFiles: () -> Unit,
    onOpenStatus: () -> Unit,
) {
    // 1. 顶栏：标准返回导航键 + 标题 + 紧凑元数据
    // C-14：testTag 供仪器化布局断言锁定顶栏高度（防「行高被内容换行撑爆」回归）。
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("conv-topbar"),
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回列表",
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title.ifBlank { "会话控制台" },
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text =
                    listOfNotNull(
                        statusLabel(status),
                        meta.phase?.let { phaseLabel(it) },
                        meta.totalCount?.let { "共 $it 行" },
                        approvalsCount.takeIf { it > 0 }?.let { "待审批 $it" },
                    ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color =
                    if (status is ConversationChannel.Status.Failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                // C-14：右侧胶囊（文件/状态/模式/模型）占满行宽时，本列会被压到 ~0 宽度；
                // 该文本此前无 maxLines 兜底，会在零宽下逐字符换行成十几行，把整条顶栏
                // Row 撑到近千像素（顶部布局错乱的直接成因）。此处硬锁单行。
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // A-2：握手失败后的显式重试入口。挂在顶栏而不是空态里——有离线缓存时
        // rows 非空、走不到空态，只有这里能稳定触达（自动重订已用尽时才需要它）。
        if (status is ConversationChannel.Status.Failed) {
            Surface(
                onClick = onRetrySubscribe,
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.height(28.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp),
                ) {
                    Text(
                        text = "重试",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
        }

        // 顶栏右侧：溢出菜单（C-14 空间预算）——把「文件数」「状态」从顶栏直排收进
        // 「⋮」二级菜单。二者作为定宽胶囊会把中部标题列挤到约 16px（标题/元数据不可见，
        // 真机实测），故改为二级入口，把宽度让给标题。仅在确有条目时出现。
        val overflowEntries =
            buildList {
                if (sessionFileCount > 0) add("files" to "📁 文件（$sessionFileCount）")
                statusEntryLabel?.let { add("status" to it.asString()) }
            }
        if (overflowEntries.isNotEmpty()) {
            Box {
                var overflow by remember { mutableStateOf(false) }
                IconButton(onClick = { overflow = true }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "更多",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                    overflowEntries.forEach { (kind, label) ->
                        DropdownMenuItem(
                            text = { Text(label, style = MaterialTheme.typography.bodySmall) },
                            onClick = {
                                overflow = false
                                if (kind == "files") onOpenFiles() else onOpenStatus()
                            },
                        )
                    }
                }
            }
        }

        // 顶栏右侧：执行模式胶囊（P0-B）—— 当前模式常驻可见，yolo 红底警示；点击弹出切换菜单
        ModeChip(
            currentMode = currentSessionMode,
            onSwitchMode = onSwitchMode,
        )

        // 顶栏右侧：模型切换胶囊（两级菜单：先选模型 → 再选思考档位）
        Box {
            var menuExpanded by remember { mutableStateOf(false) }
            // 当前处于档位选择阶段的模型（null = 正在选模型）
            var levelStageModel by remember { mutableStateOf<WorkspaceConfigChannel.ModelOption?>(null) }

            fun levelsOf(m: WorkspaceConfigChannel.ModelOption?): List<String> {
                if (m == null) return emptyList()
                val pid = m.providerId ?: return emptyList()
                return modelReasoningLevels["$pid/${m.modelId()}"] ?: emptyList()
            }

            Surface(
                onClick = {
                    levelStageModel = null
                    menuExpanded = true
                },
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.height(28.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    Text(
                        text = meta.model?.substringAfterLast('/') ?: "模型",
                        style =
                            MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 11.sp,
                            ),
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(2.dp))
                    Text("▾", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = {
                    menuExpanded = false
                    levelStageModel = null
                },
            ) {
                val stage = levelStageModel
                if (stage == null) {
                    // ---- 阶段一：选模型 ----
                    if (availableModels.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("未读到备选模型目录", style = MaterialTheme.typography.bodySmall) },
                            onClick = {},
                            enabled = false,
                        )
                    } else {
                        availableModels.forEach { m ->
                            val isSelected = m.name == meta.model || m.modelId() == meta.model
                            val levels = levelsOf(m)
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(m.name, style = MaterialTheme.typography.bodySmall)
                                        m.providerName?.let {
                                            Text(
                                                it,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                },
                                leadingIcon = {
                                    if (isSelected) {
                                        Icon(
                                            Icons.Default.Check,
                                            null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                },
                                trailingIcon = {
                                    if (levels.isNotEmpty()) {
                                        Text("▸", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                onClick = {
                                    if (levels.isEmpty()) {
                                        // 无推理档位的模型：直接切换
                                        menuExpanded = false
                                        onSwitchModel(m.copy(thought = null))
                                    } else {
                                        // 有档位：进入第二阶段
                                        levelStageModel = m
                                    }
                                },
                            )
                        }
                    }
                } else {
                    // ---- 阶段二：选思考档位 ----
                    DropdownMenuItem(
                        text = {
                            Text(
                                "← ${stage.name.substringAfterLast('/')}",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                        onClick = { levelStageModel = null },
                    )
                    HorizontalDivider()
                    levelsOf(stage).forEach { lv ->
                        DropdownMenuItem(
                            text = { Text(thoughtLabel(lv), style = MaterialTheme.typography.bodySmall) },
                            onClick = {
                                menuExpanded = false
                                levelStageModel = null
                                onSwitchModel(stage.copy(thought = lv))
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 顶栏执行模式胶囊（P0-B）：当前模式常驻可见，yolo 红底警示；点击弹出切换菜单。
 * 协议语义（HANDOVER §5.4）：setMode 只对下一轮 agent turn 生效，菜单内如实提示。
 */
@Composable
private fun ModeChip(
    currentMode: String?,
    onSwitchMode: (String) -> Unit,
) {
    val mode = currentMode ?: "build"
    Box {
        var menuExpanded by remember { mutableStateOf(false) }
        Surface(
            onClick = { menuExpanded = true },
            shape = RoundedCornerShape(14.dp),
            color =
                if (mode == "yolo") {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                },
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.height(28.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Text(
                    text = if (mode == "yolo") "⚠ $mode" else mode,
                    style =
                        MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.sp,
                        ),
                    color =
                        if (mode == "yolo") {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.tertiary
                        },
                    maxLines = 1,
                )
                Spacer(Modifier.width(2.dp))
                Text("▾", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            listOf("plan", "build", "yolo").forEach { m ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(modeDisplayName(m), style = MaterialTheme.typography.bodySmall)
                            if (m == "yolo") {
                                Text(
                                    "免审批：所有工具调用自动放行",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    },
                    leadingIcon = {
                        if (m == mode) {
                            Icon(
                                Icons.Default.Check,
                                null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    },
                    onClick = {
                        menuExpanded = false
                        if (m != mode) onSwitchMode(m)
                    },
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
            DropdownMenuItem(
                text = {
                    Text(
                        "切换仅对下一轮对话生效；运行中的轮次权限不变",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = {},
                enabled = false,
            )
        }
    }
}

private fun modeDisplayName(m: String): String =
    when (m) {
        "plan" -> "规划 plan（只读规划，不改文件）"
        "build" -> "构建 build（工具调用需审批）"
        "yolo" -> "全自动 yolo（免审批）"
        else -> m
    }

internal fun statusLabel(s: ConversationChannel.Status): UiText =
    when (s) {
        ConversationChannel.Status.Idle -> res(R.string.conv_status_idle)
        ConversationChannel.Status.Hello -> res(R.string.conv_status_hello)
        ConversationChannel.Status.Initialized -> res(R.string.conv_status_initialized)
        is ConversationChannel.Status.Live -> res(R.string.conv_status_live)
        // C-2：底层 reason 可能是通道未就绪 / 超时（英文自造短语）这类串，先经 UserFacingError 映射；
        // 截断到 50 字由顶栏 `maxLines=1 + Ellipsis` 兜底，此处保留原文（避免在纯函数层做字符截断
        // 把已解析的中文串切坏——截断交给 Text 渲染层）。
        is ConversationChannel.Status.Failed ->
            res(
                R.string.conv_status_failed,
                com.zcode.remote.relay.UserFacingError
                    .map(s.reason),
            )
    }

internal fun phaseLabel(p: String) =
    when (p) {
        "completedSuccess" -> "已完成"
        "completedInterrupted" -> "已中断"
        "completedError" -> "执行出错"
        "idle" -> "空闲"
        "running" -> "运行中"
        "waitingUserInput" -> "等待输入"
        "aborted" -> "已中止"
        // host 对失败轮次直接送 phase="error"（非 completedError），此前落到 else 显示英文原文，
        // 与列表里同一状态的「异常」标签不一致。
        "error" -> "异常"
        else -> p
    }
