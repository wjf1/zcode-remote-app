package com.zcode.remote.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zcode.remote.ui.voice.VoiceInputButton
import kotlinx.coroutines.delay

/**
 * 会话页输入区（§11.7 结构性拆分：自 `ConversationScreen.kt` 原样搬出，只移动不改行为）。
 * 快捷指令胶囊栏 + 悬浮胶囊输入条（语音 / 文本 / 发送-停止变形按钮）。
 */

/**
 * 常用快捷指令胶囊栏（减少移动端软键盘输入成本）
 */
@Composable
internal fun ActionChipsBar(
    onSelectChip: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chips = listOf(
        "继续",
        "运行测试验证",
        "修复该问题",
        "检查 Git 状态",
        "整理并提交"
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        chips.forEach { chipText ->
            Surface(
                onClick = { onSelectChip(chipText) },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                modifier = Modifier.height(26.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp)
                ) {
                    Text(
                        text = chipText,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * 现代悬浮胶囊输入条
 */
@Composable
internal fun InputBar(
    prompt: String,
    sending: Boolean,
    canStop: Boolean,
    stopping: Boolean,
    canSend: Boolean,
    onPromptChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onPick: () -> Unit,
) {
    var voiceListening by remember { mutableStateOf(false) }
    var voiceLive by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val voiceLine = if (voiceListening) "🎤 正在聆听: ${voiceLive ?: "…"}" else voiceLive
        voiceLine?.let { line ->
            LaunchedEffect(line) {
                if (line.startsWith("⚠️")) { delay(4000); voiceLive = null }
            }
            Text(
                text = line,
                style = MaterialTheme.typography.labelSmall,
                color = if (line.startsWith("⚠️")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            )
        }

        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            border = CardDefaults.outlinedCardBorder(),
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                // B-2：CenterVertically 而非 Bottom —— 文本框 56dp 高、文字垂直居中（距顶 ~28dp），
                // 按钮若贴底则中心在 ~38dp，错位 ~10dp（用户反馈「文字偏上、按钮偏下」即此）。
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                // 附件选择（48dp 触控档，图标视觉 18dp；与语音/发送按钮同档，见任务书 B-2）
                IconButton(
                    onClick = onPick,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "添加附件",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 语音输入
                VoiceInputButton(
                    onFinalText = { text ->
                        onPromptChange(if (prompt.isBlank()) text else "$prompt $text")
                    },
                    onStateChange = { listening, live ->
                        voiceListening = listening
                        voiceLive = live
                    },
                )

                Spacer(Modifier.width(4.dp))

                // 文本输入
                OutlinedTextField(
                    value = prompt,
                    onValueChange = onPromptChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("发送指令到 PC 端 Agent…", style = MaterialTheme.typography.bodySmall) },
                    maxLines = 4,
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent
                    )
                )

                Spacer(Modifier.width(4.dp))

                // 发送 / 停止 变形按钮
                if (canSend) {
                    FilledIconButton(
                        onClick = onSend,
                        enabled = !sending,
                        modifier = Modifier.size(48.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        if (sending) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        } else {
                            Icon(Icons.Default.Send, contentDescription = "发送", modifier = Modifier.size(18.dp))
                        }
                    }
                } else if (canStop) {
                    FilledIconButton(
                        onClick = onStop,
                        enabled = !stopping,
                        modifier = Modifier.size(48.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "中断", modifier = Modifier.size(18.dp))
                    }
                } else {
                    IconButton(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = "发送",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}
