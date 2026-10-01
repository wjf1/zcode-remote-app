package com.zcode.remote.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.zcode.remote.relay.ApprovalOption
import com.zcode.remote.relay.ConversationChannel
import com.zcode.remote.relay.ConversationRow
import com.zcode.remote.relay.PendingApproval
import com.zcode.remote.relay.PendingElicitation
import com.zcode.remote.ui.components.MarkdownView
import com.zcode.remote.ui.theme.ZCodeTokens
import com.zcode.remote.ui.voice.VoiceInputButton
import kotlinx.coroutines.delay

/**
 * 沉浸式会话详情与对话控制台
 * 对标官方 ZCode 桌面端排版与交互规范，支持流式渲染、官方同款思考过程与工具调用。
 */
@Composable
fun ConversationScreen(
    title: String,
    status: ConversationChannel.Status,
    meta: ConversationChannel.ConversationMeta,
    rows: List<ConversationRow>,
    approvals: List<PendingApproval> = emptyList(),
    elicitations: List<PendingElicitation> = emptyList(),
    approvalFeedback: String? = null,
    earlier: ConversationChannel.EarlierState = ConversationChannel.EarlierState(),
    prompt: String = "",
    sending: Boolean = false,
    canStop: Boolean = false,
    stopState: String? = null,
    commandFeedback: String? = null,
    attachments: List<ConversationChannel.AttachmentRef> = emptyList(),
    attachUploadName: String? = null,
    attachUploadPercent: Int = 0,
    onResolve: (PendingApproval, ApprovalOption) -> Unit = { _, _ -> },
    onElicitationAccept: (PendingElicitation, Map<Int, List<String>>) -> Unit = { _, _ -> },
    onElicitationDecline: (PendingElicitation) -> Unit = {},
    onElicitationFreeText: (PendingElicitation, String) -> Unit = { _, _ -> },
    onLoadEarlier: () -> Unit = {},
    onFeedbackSeen: () -> Unit = {},
    onPromptChange: (String) -> Unit = {},
    onSend: () -> Unit = {},
    onStop: () -> Unit = {},
    onAttachmentPicked: (uri: android.net.Uri, name: String, mime: String, size: Long) -> Unit = { _, _, _, _ -> },
    onRemoveAttachment: (ConversationChannel.AttachmentRef) -> Unit = {},
    onBack: () -> Unit,
) {
    val listState = rememberLazyListState()
    val headerCount = if (rows.isEmpty()) 0 else 1

    var anchorRowId by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(rows.size) {
        val anchor = anchorRowId
        if (anchor != null) {
            val idx = rows.indexOfFirst { it.rowId == anchor }
            if (idx >= 0) listState.scrollToItem(idx + headerCount)
            anchorRowId = null
        } else {
            if (rows.isNotEmpty()) listState.animateScrollToItem(rows.lastIndex + headerCount)
        }
    }

    val loadGate = rememberUpdatedState(Triple(rows.size, earlier.hasMore, earlier.loading to earlier.pulled))
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { idx ->
            val (size, hasMore, loadingPulled) = loadGate.value
            val (loading, pulled) = loadingPulled
            if (size > 0 && idx <= 2 && !loading && (hasMore || !pulled)) {
                anchorRowId = rows.firstOrNull()?.rowId
                onLoadEarlier()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 1. 顶栏：标准返回导航键 + 标题 + 紧凑元数据
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回列表",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title.ifBlank { "会话控制台" },
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = listOfNotNull(
                        statusLabel(status),
                        meta.phase?.let { phaseLabel(it) },
                        meta.totalCount?.let { "共 $it 行" },
                        approvals.size.takeIf { it > 0 }?.let { "待审批 $it" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (status is ConversationChannel.Status.Failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 反馈横幅提示
        approvalFeedback?.let { msg ->
            LaunchedEffect(msg) { onFeedbackSeen() }
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = msg,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }

        commandFeedback?.let { msg ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = msg,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }

        // 浮动审批提醒卡片（待决议置顶）
        approvals.forEach { ApprovalCard(it, onResolve) }

        // 表单交互卡片（AskUserQuestion / 计划确认）
        elicitations.forEach { el ->
            ElicitationCard(
                el = el,
                onAccept = { answers -> onElicitationAccept(el, answers) },
                onDecline = { onElicitationDecline(el) },
                onFreeText = { text -> onElicitationFreeText(el, text) },
            )
        }

        if (rows.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = "正在同步会话内容…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            // 2. 消息流列表
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "earlier-head") {
                    Text(
                        text = when {
                            earlier.loading -> "加载更早历史…"
                            earlier.pulled && !earlier.hasMore -> "已到会话开头"
                            else -> "上滑加载更早历史"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
                items(rows, key = { it.rowId }) { row -> RowItem(row) }
            }
        }

        // 3. 附件条
        val context = LocalContext.current
        val filePicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            uri?.let { u ->
                inspectAttachment(context, u)?.let { (name, mime, size) ->
                    onAttachmentPicked(u, name, mime, size)
                }
            }
        }
        AttachmentBar(
            attachments = attachments,
            uploadName = attachUploadName,
            uploadPercent = attachUploadPercent,
            onPick = { filePicker.launch(arrayOf("*/*")) },
            onRemove = onRemoveAttachment,
        )

        // 4. 底部现代化输入栏
        InputBar(
            prompt = prompt,
            sending = sending,
            canStop = canStop,
            stopping = stopState == "stopping",
            canSend = prompt.isNotBlank() || attachments.isNotEmpty(),
            onPromptChange = onPromptChange,
            onSend = onSend,
            onStop = onStop,
            onPick = { filePicker.launch(arrayOf("*/*")) },
        )
    }
}

private fun inspectAttachment(context: Context, uri: Uri): Triple<String, String, Long>? {
    var name = "attachment"
    var size = -1L
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
            if (c.moveToFirst()) {
                if (nameIdx >= 0) c.getString(nameIdx)?.let { name = it }
                if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
            }
        }
    }
    val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
    if (size < 0) {
        size = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                var total = 0L
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    total += n
                }
                total
            }
        }.getOrNull() ?: -1L
        if (size < 0) return null
    }
    return Triple(name, mime, size)
}

@Composable
private fun AttachmentBar(
    attachments: List<ConversationChannel.AttachmentRef>,
    uploadName: String?,
    uploadPercent: Int,
    onPick: () -> Unit,
    onRemove: (ConversationChannel.AttachmentRef) -> Unit,
) {
    if (attachments.isEmpty() && uploadName == null) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        uploadName?.let { name ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "上传中: $name ($uploadPercent%)",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                LinearProgressIndicator(
                    progress = { uploadPercent / 100f },
                    modifier = Modifier.width(80.dp),
                )
            }
        }
        attachments.forEach { a ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Share, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(
                        text = a.fileName,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = humanBytes(a.bytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    IconButton(
                        onClick = { onRemove(a) },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(Icons.Default.Close, null, modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}

private fun humanBytes(n: Long): String = when {
    n < 1024 -> "${n}B"
    n < 1024 * 1024 -> "%.1fKB".format(n / 1024.0)
    else -> "%.1fMB".format(n / 1024.0 / 1024.0)
}

/**
 * 现代悬浮胶囊输入条
 */
@Composable
private fun InputBar(
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
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                // 附件选择
                IconButton(
                    onClick = onPick,
                    modifier = Modifier.size(36.dp)
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
                        modifier = Modifier.size(36.dp),
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
                        modifier = Modifier.size(36.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "中断", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ElicitationCard(
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
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f))
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = when {
                    el.isPlanApproval -> "实施方案待批准"
                    el.questions.isNotEmpty() -> "提问 · 需要你的回答"
                    else -> "确认请求"
                },
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.tertiary
            )

            el.plan?.let {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = it.take(2000),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                        maxLines = 10,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(10.dp)
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
                        colors = if (chosen) ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        ) else ButtonDefaults.outlinedButtonColors()
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
                    maxLines = 3
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
                    enabled = selected.isNotEmpty() || freeTextDraft.isNotBlank()
                ) { Text("提交回答") }
                OutlinedButton(onClick = onDecline) { Text("拒绝") }
            }
        }
    }
}

@Composable
private fun ApprovalCard(
    a: PendingApproval,
    onResolve: (PendingApproval, ApprovalOption) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(ZCodeTokens.StatusPending.copy(alpha = 0.6f))
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "权限审批 · ${a.toolName ?: "Command"}",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = ZCodeTokens.StatusPending
                )
                Spacer(Modifier.weight(1f))
                a.autoResolveAt?.let {
                    val sec = ((it - System.currentTimeMillis()).coerceAtLeast(0) / 1000).toInt()
                    Text("${sec}s 自动决议", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            a.summary?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            a.detail?.let {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(it.take(500), style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp), modifier = Modifier.padding(6.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                a.options.forEach { opt ->
                    Button(
                        onClick = { onResolve(a, opt) },
                        modifier = Modifier.weight(1f).height(34.dp),
                        contentPadding = PaddingValues(0.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (opt.isAllow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(
                            when {
                                opt.kind == "allowOnce" -> "允许一次"
                                opt.kind == "allowAlways" -> "总是允许"
                                opt.isDeny -> "拒绝"
                                else -> opt.label.ifBlank { "选项" }
                            },
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RowItem(row: ConversationRow) = when (row.kind) {
    "userInput" -> Bubble(row.text.orEmpty(), isUser = true)
    "assistantText" -> Bubble(row.text.orEmpty(), isUser = false, streaming = row.isStreaming)
    "reasoning" -> ReasoningBlock(row)
    "toolCall" -> ToolCallCard(row)
    "turnHeader" -> TurnHeaderRow(row)
    else -> PlainRow(row)
}

@Composable
private fun Bubble(text: String, isUser: Boolean, streaming: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (isUser) {
            Surface(
                modifier = Modifier.widthIn(max = 310.dp),
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
                color = MaterialTheme.colorScheme.primary,
                shadowElevation = 1.dp
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onPrimary),
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        } else {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = CardDefaults.outlinedCardBorder()
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    MarkdownView(
                        markdown = text,
                        modifier = Modifier.fillMaxWidth(),
                        textColor = MaterialTheme.colorScheme.onSurface
                    )
                    if (streaming) {
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "正在生成…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 官方 ZCode 风格深度思考折叠胶囊 (ReasoningBlock)
 */
@Composable
private fun ReasoningBlock(row: ConversationRow) {
    var expanded by remember { mutableStateOf(false) }
    val content = row.text.orEmpty().trim()
    if (content.isEmpty()) return
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                // 紫色思考轨迹圆点
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(ZCodeTokens.ReasoningTrajectoryDark)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "思考过程 (${content.length} 字符)",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                    color = ZCodeTokens.ReasoningTrajectoryDark,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    modifier = Modifier.height(26.dp)
                ) {
                    Text(if (expanded) "收起" else "展开", style = MaterialTheme.typography.labelSmall)
                }
                if (expanded) {
                    TextButton(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("reasoning", content))
                            Toast.makeText(context, "思考过程已复制", Toast.LENGTH_SHORT).show()
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        modifier = Modifier.height(26.dp)
                    ) {
                        Text("复制", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, start = 4.dp)
            ) {
                // 左侧微弱竖向引导线（官方同款）
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(ZCodeTokens.ReasoningTrajectoryDark.copy(alpha = 0.35f))
                )
                Spacer(Modifier.width(8.dp))
                MarkdownView(
                    markdown = content,
                    modifier = Modifier.weight(1f),
                    textColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 官方 ZCode 风格紧凑工具调用卡片 (ToolCallBlock)
 */
@Composable
private fun ToolCallCard(row: ConversationRow) {
    val tool = row.toolName?.trim().orEmpty()
    val input = row.inputText?.trim().orEmpty()
    val output = row.outputText?.trim().orEmpty()
    if (tool.isEmpty() && input.isEmpty() && output.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }
    val tone = when (row.status) {
        "success" -> ZCodeTokens.StatusOnline
        "error", "cancelled" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }

    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder(),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    color = ZCodeTokens.ToolCallTrajectoryDark.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = tool.ifEmpty { "Command" },
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = ZCodeTokens.ToolCallTrajectoryDark,
                            fontWeight = FontWeight.SemiBold
                        ),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = when (row.status) {
                        "success" -> "✓ 成功"
                        "error" -> "✗ 失败"
                        "running" -> "执行中…"
                        "cancelled" -> "已取消"
                        else -> row.status ?: ""
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = tone,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text(if (expanded) "收起" else "详情", style = MaterialTheme.typography.labelSmall)
                }
            }

            val summary = input.replace('\n', ' ').trim()
            if (!expanded && summary.isNotEmpty()) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(top = 6.dp)) {
                    CodeBlock("输入", input)
                    CodeBlock("输出", output)
                }
            }
        }
    }
}

@Composable
private fun CodeBlock(label: String, content: String?) {
    if (content.isNullOrBlank()) return
    Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = content.take(4000),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, lineHeight = 16.sp),
            modifier = Modifier.padding(8.dp),
        )
    }
}

@Composable
private fun TurnHeaderRow(row: ConversationRow) {
    HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
    Text(
        text = listOfNotNull(
            row.state?.let { phaseLabel(it) },
            row.createdAt?.let { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(it)) },
        ).joinToString(" · "),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PlainRow(row: ConversationRow) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(Modifier.padding(8.dp)) {
            Text("${row.kind} #${row.rowId}", style = MaterialTheme.typography.labelSmall)
            row.text?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis) }
        }
    }
}

private fun statusLabel(s: ConversationChannel.Status) = when (s) {
    ConversationChannel.Status.Idle -> "未订阅"
    ConversationChannel.Status.Hello -> "握手中…"
    ConversationChannel.Status.Initialized -> "订阅中…"
    is ConversationChannel.Status.Live -> "已连接"
    is ConversationChannel.Status.Failed -> "异常：${s.reason.take(50)}"
}

private fun phaseLabel(p: String) = when (p) {
    "completedSuccess" -> "已完成"
    "completedInterrupted" -> "已中断"
    "completedError" -> "执行出错"
    "idle" -> "空闲"
    "running" -> "运行中"
    "waitingUserInput" -> "等待输入"
    "aborted" -> "已中止"
    else -> p
}
