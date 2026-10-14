package com.zcode.remote.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zcode.remote.relay.ApprovalOption
import com.zcode.remote.relay.PendingApproval
import com.zcode.remote.relay.PendingElicitation
import com.zcode.remote.ui.components.AutoResolveCountdown
import com.zcode.remote.ui.theme.statusPendingTone

/**
 * 会话页内联待办卡（§11.7 结构性拆分：自 `ConversationScreen.kt` 原样搬出，只移动不改行为）。
 * 表单卡与权限审批卡按当前会话过滤后在此渲染；通知栏聚合视图见 ApprovalsTab。
 */

@Composable
internal fun ElicitationCard(
    el: PendingElicitation,
    onAccept: (Map<Int, List<String>>) -> Unit,
    onDecline: () -> Unit,
    onFreeText: (String) -> Unit,
) {
    val selected = remember(el.interactionId) { mutableStateMapOf<Int, MutableList<String>>() }
    var freeTextDraft by remember(el.interactionId) { mutableStateOf("") }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border =
            CardDefaults.outlinedCardBorder().copy(
                brush =
                    androidx.compose.ui.graphics
                        .SolidColor(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f)),
            ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text =
                    when {
                        el.isPlanApproval -> "实施方案待批准"
                        el.questions.isNotEmpty() -> "提问 · 需要你的回答"
                        else -> "确认请求"
                    },
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.tertiary,
            )

            el.plan?.let {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        text = it.take(2000),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                        maxLines = 10,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }

            if (el.isPlanApproval) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onAccept(emptyMap()) }) { Text("批准方案") }
                    OutlinedButton(onClick = onDecline) { Text("拒绝") }
                }
                return@Column
            }

            el.questions.forEachIndexed { qIdx, q ->
                Text(q.question.ifBlank { q.header ?: "" }, style = MaterialTheme.typography.bodyMedium)
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
                        modifier = Modifier.fillMaxWidth(),
                        colors =
                            if (chosen) {
                                ButtonDefaults.outlinedButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                )
                            } else {
                                ButtonDefaults.outlinedButtonColors()
                            },
                    ) {
                        Text(opt.label, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (el.freeText) {
                OutlinedTextField(
                    value = freeTextDraft,
                    onValueChange = { freeTextDraft = it },
                    placeholder = { Text("输入自定义回答…", style = MaterialTheme.typography.bodySmall) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    maxLines = 3,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val answers = selected.mapValues { (_, list) -> list.toList() }.toMutableMap()
                        if (el.freeText && freeTextDraft.isNotBlank()) {
                            val cur = answers.getOrPut(0) { emptyList() }
                            answers[0] = cur + freeTextDraft.trim()
                        }
                        onAccept(answers.filter { it.value.isNotEmpty() })
                    },
                    enabled = selected.isNotEmpty() || freeTextDraft.isNotBlank(),
                ) { Text("提交回答") }
                OutlinedButton(onClick = onDecline) { Text("拒绝") }
            }
        }
    }
}

@Composable
internal fun ApprovalCard(
    a: PendingApproval,
    onResolve: (PendingApproval, ApprovalOption) -> Unit,
) {
    // B-3：会话内审批按钮与 ApprovalsTab 保持一致，点击给一次触觉反馈
    val haptic = LocalHapticFeedback.current
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border =
            CardDefaults.outlinedCardBorder().copy(
                brush =
                    androidx.compose.ui.graphics
                        .SolidColor(statusPendingTone().copy(alpha = 0.6f)),
            ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "权限审批 · ${a.toolName ?: "Command"}",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = statusPendingTone(),
                )
                Spacer(Modifier.weight(1f))
                a.autoResolveAt?.let { deadline ->
                    AutoResolveCountdown(deadlineAt = deadline)
                }
            }
            a.summary?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            a.detail?.let {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        it.take(500),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp),
                        modifier = Modifier.padding(6.dp),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                a.options.forEach { opt ->
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onResolve(a, opt)
                        },
                        modifier = Modifier.weight(1f).height(34.dp),
                        contentPadding =
                            androidx.compose.foundation.layout
                                .PaddingValues(0.dp),
                        colors =
                            ButtonDefaults.buttonColors(
                                containerColor = if (opt.isAllow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            ),
                    ) {
                        Text(
                            when {
                                opt.kind == "allowOnce" -> "允许一次"
                                opt.kind == "allowAlways" -> "总是允许"
                                opt.isDeny -> "拒绝"
                                else -> opt.label.ifBlank { "选项" }
                            },
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}
