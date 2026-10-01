package com.zcode.remote.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zcode.remote.relay.ApprovalOption
import com.zcode.remote.relay.PendingApproval
import com.zcode.remote.relay.PendingElicitation
import com.zcode.remote.relay.SessionItem
import com.zcode.remote.ui.theme.ZCodeTokens

/**
 * 集中式审批与待办看板 (Inbox / Approvals Tab)
 * 对标 GitHub Mobile Notifications，统一聚合当前全部待决议权限与表单交互。
 */
@Composable
fun ApprovalsTab(
    approvals: List<PendingApproval>,
    elicitations: List<PendingElicitation>,
    sessions: List<SessionItem>,
    sessionPending: Map<String, Int>,
    subscribedSessionId: String?,
    feedback: String? = null,
    onResolveApproval: (PendingApproval, ApprovalOption) -> Unit,
    onAcceptElicitation: (PendingElicitation, Map<Int, List<String>>) -> Unit,
    onDeclineElicitation: (PendingElicitation) -> Unit,
    onFreeTextElicitation: (PendingElicitation, String) -> Unit,
    onOpenSession: (SessionItem) -> Unit,
) {
    val totalCurrentPending = approvals.size + elicitations.size
    // 其它会话中有待办事项的列表
    val otherPendingSessions = remember(sessions, sessionPending, subscribedSessionId) {
        sessions.filter { s ->
            s.taskId != subscribedSessionId && (sessionPending[s.taskId] ?: 0) > 0
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // 顶栏标头
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 12.dp)
        ) {
            Text(
                text = "待办与审批",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f)
            )
            if (totalCurrentPending > 0) {
                Surface(
                    color = ZCodeTokens.StatusPending.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(12.dp),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = androidx.compose.ui.graphics.SolidColor(ZCodeTokens.StatusPending.copy(alpha = 0.4f))
                    )
                ) {
                    Text(
                        text = "$totalCurrentPending 项待处理",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = ZCodeTokens.StatusPending,
                            fontWeight = FontWeight.SemiBold
                        ),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }

        // 统一操作反馈提示横幅
        feedback?.let { msg ->
            Surface(
                color = if (msg.startsWith("发送失败") || msg.startsWith("应答失败") || msg.startsWith("连接已断开"))
                    MaterialTheme.colorScheme.errorContainer
                else
                    MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
            ) {
                Text(
                    text = msg,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (msg.startsWith("发送失败") || msg.startsWith("应答失败") || msg.startsWith("连接已断开"))
                        MaterialTheme.colorScheme.onErrorContainer
                    else
                        MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }

        if (approvals.isEmpty() && elicitations.isEmpty() && otherPendingSessions.isEmpty()) {
            // 空状态展示：优雅静谧
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 48.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = ZCodeTokens.StatusOnline,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Text(
                        text = "暂无待决议事项",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "所有会话均正常运行或已就绪，新的权限申请与提问将在此显示。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 32.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                // 1. 当前会话的权限审批
                if (approvals.isNotEmpty()) {
                    item {
                        Text(
                            text = "权限审批请求 (${approvals.size})",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = ZCodeTokens.StatusPending
                        )
                    }
                    items(approvals, key = { it.interactionId }) { a ->
                        ApprovalInboxCard(a = a, onResolve = onResolveApproval)
                    }
                }

                // 2. 当前会话的表单/计划确认
                if (elicitations.isNotEmpty()) {
                    item {
                        Text(
                            text = "表单与计划应答 (${elicitations.size})",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    items(elicitations, key = { it.interactionId }) { el ->
                        ElicitationInboxCard(
                            el = el,
                            onAccept = { answers -> onAcceptElicitation(el, answers) },
                            onDecline = { onDeclineElicitation(el) },
                            onFreeText = { text -> onFreeTextElicitation(el, text) }
                        )
                    }
                }

                // 3. 其它会话的待处理提醒
                if (otherPendingSessions.isNotEmpty()) {
                    item {
                        Text(
                            text = "其它会话的待办",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    items(otherPendingSessions, key = { it.taskId }) { s ->
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            border = CardDefaults.outlinedCardBorder(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = s.title,
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = "${sessionPending[s.taskId] ?: 0} 项请求等待处理",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = ZCodeTokens.StatusPending
                                    )
                                }
                                Button(
                                    onClick = { onOpenSession(s) },
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Text("切换处理", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 官方规范级审批卡片
 */
@Composable
private fun ApprovalInboxCard(
    a: PendingApproval,
    onResolve: (PendingApproval, ApprovalOption) -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = CardDefaults.outlinedCardBorder(),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 来源标签与工具名
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = ZCodeTokens.ToolCallTrajectoryDark.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = "工具调用 · ${a.toolName ?: "Command"}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = ZCodeTokens.ToolCallTrajectoryDark,
                            fontWeight = FontWeight.SemiBold
                        ),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Spacer(Modifier.weight(1f))
                a.autoResolveAt?.let {
                    val remainingSec = ((it - System.currentTimeMillis()).coerceAtLeast(0) / 1000).toInt()
                    Text(
                        text = "${remainingSec}s 后自动决议",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 摘要
            a.summary?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // 代码/命令细节等宽预览
            a.detail?.let {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = it.take(800),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            // 决议操作按钮组
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val allowOptions = a.options.filter { it.isAllow }
                val denyOptions = a.options.filter { it.isDeny }

                // 拒绝按钮
                if (denyOptions.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { onResolve(a, denyOptions.first()) },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.weight(1f).height(38.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("拒绝", style = MaterialTheme.typography.labelMedium)
                    }
                }

                // 允许按钮
                allowOptions.forEach { opt ->
                    Button(
                        onClick = { onResolve(a, opt) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (opt.kind == "allowAlways") MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                        ),
                        modifier = Modifier.weight(if (allowOptions.size > 1) 1f else 1.2f).height(38.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = when (opt.kind) {
                                "allowOnce" -> "允许一次"
                                "allowAlways" -> "总是允许"
                                else -> opt.label.ifBlank { "允许" }
                            },
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
    }
}

/**
 * 官方规范级表单应答卡片
 */
@Composable
private fun ElicitationInboxCard(
    el: PendingElicitation,
    onAccept: (Map<Int, List<String>>) -> Unit,
    onDecline: () -> Unit,
    onFreeText: (String) -> Unit,
) {
    val selected = remember(el.interactionId) { mutableStateMapOf<Int, MutableList<String>>() }
    var freeTextDraft by remember(el.interactionId) { mutableStateOf("") }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder(),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    text = when {
                        el.isPlanApproval -> "实施方案待批准"
                        el.questions.isNotEmpty() -> "需要你的输入回答"
                        else -> "操作确认"
                    },
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.colorScheme.tertiary,
                        fontWeight = FontWeight.SemiBold
                    ),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            // 方案文本
            el.plan?.let {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = it.take(2000),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        ),
                        maxLines = 10,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            // plan_approval 直接展示批准/拒绝
            if (el.isPlanApproval) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onDecline,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.weight(1f).height(38.dp)
                    ) {
                        Text("拒绝方案")
                    }
                    Button(
                        onClick = { onAccept(emptyMap()) },
                        modifier = Modifier.weight(1.5f).height(38.dp)
                    ) {
                        Text("批准并执行")
                    }
                }
                return@Column
            }

            // 问答选项
            el.questions.forEachIndexed { qIdx, q ->
                Text(
                    text = q.question.ifBlank { q.header ?: "" },
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                )
                q.options.forEach { opt ->
                    val chosen = opt.value in (selected[qIdx] ?: emptyList())
                    OutlinedButton(
                        onClick = {
                            val cur = selected.getOrPut(qIdx) { mutableListOf() }
                            if (q.multiSelect) {
                                if (chosen) cur.remove(opt.value) else cur.add(opt.value)
                                selected[qIdx] = cur
                            } else {
                                selected[qIdx] = mutableListOf(opt.value)
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = if (chosen) ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        ) else ButtonDefaults.outlinedButtonColors(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(opt.label, style = MaterialTheme.typography.bodyMedium)
                                opt.description?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (chosen) {
                                Icon(
                                    Icons.Default.Check,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 自由文本输入
            if (el.freeText) {
                OutlinedTextField(
                    value = freeTextDraft,
                    onValueChange = { freeTextDraft = it },
                    placeholder = { Text("输入自定义回答…", style = MaterialTheme.typography.bodySmall) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    maxLines = 3
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onDecline,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.weight(1f).height(38.dp)
                ) {
                    Text("拒绝")
                }
                Button(
                    onClick = {
                        if (el.questions.isEmpty() && el.freeText) {
                            onFreeText(freeTextDraft.trim())
                        } else {
                            val answers = selected.mapValues { (_, list) -> list.toList() }.toMutableMap()
                            if (el.freeText && freeTextDraft.isNotBlank()) {
                                val cur = answers.getOrPut(0) { emptyList() }
                                answers[0] = cur + freeTextDraft.trim()
                            }
                            onAccept(answers.filter { it.value.isNotEmpty() })
                        }
                    },
                    enabled = selected.isNotEmpty() || freeTextDraft.isNotBlank(),
                    modifier = Modifier.weight(1.5f).height(38.dp)
                ) {
                    Text("提交回答")
                }
            }
        }
    }
}
