package com.zcode.remote.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zcode.remote.relay.ApprovalOption
import com.zcode.remote.relay.ConversationChannel
import com.zcode.remote.relay.ConversationRow
import com.zcode.remote.relay.PendingApproval
import com.zcode.remote.relay.PendingElicitation
import com.zcode.remote.ui.voice.VoiceInputButton
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream

/**
 * 会话内容页：把会话流（snapshot 尾窗 + 增量）渲染成对话。
 *
 * 行没有统一 role，按 kind 区分：userInput / assistantText / reasoning / toolCall / turnHeader。
 * 底部输入栏：有草稿时显示「发送」（sendPrompt），空草稿且服务端报 canStop 时显示「停止」
 * （官方 web 版同款互斥逻辑）。
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
    onAttachmentPicked: (name: String, mime: String, data: ByteArray) -> Unit = { _, _, _ -> },
    onRemoveAttachment: (ConversationChannel.AttachmentRef) -> Unit = {},
    onBack: () -> Unit,
) {
    val listState = rememberLazyListState()
    // 「加载更早」占位项只在 rows 非空时占列表第 0 位，所以行 i 的列表下标是 i + headerCount。
    // 之前的贴底滚动漏算这一位，会停在倒数第二行（D-2）。
    val headerCount = if (rows.isEmpty()) 0 else 1

    // 新行到达时贴底（流式文本靠 RowStore 原地更新，size 不变时不滚动）；
    // 历史翻页前插时锚定原首行，避免视口跳变。
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

    // 滚到顶部附近：还有更早历史（或从未拉过）就自动拉一页
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
        Modifier.fillMaxSize()
            .statusBarsPadding()
            // 底部输入栏要避开手势条与键盘（targetSdk 35 强制 edge-to-edge，insets 正常分发）
            .navigationBarsPadding()
            .imePadding()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Column(Modifier.weight(1f)) {
                Text(title.ifBlank { "会话" }, style = MaterialTheme.typography.titleSmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        statusLabel(status),
                        meta.phase?.let { phaseLabel(it) },
                        meta.totalCount?.let { "共 $it 行" },
                        approvals.size.takeIf { it > 0 }?.let { "待审批 $it" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status is ConversationChannel.Status.Failed) MaterialTheme.colorScheme.error
                    else Color.Unspecified,
                )
            }
        }

        approvalFeedback?.let { msg ->
            LaunchedEffect(msg) { onFeedbackSeen() }
            Text(
                msg,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        commandFeedback?.let { msg ->
            Text(
                msg,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // 审批条置顶：待审批时正文可能被刷走，不能放在列表里
        approvals.forEach { ApprovalCard(it, onResolve) }

        // 表单交互卡片（AskUserQuestion / 计划批准 / 确认框），与审批条并列
        elicitations.forEach { el ->
            ElicitationCard(
                el = el,
                onAccept = { answers -> onElicitationAccept(el, answers) },
                onDecline = { onElicitationDecline(el) },
                onFreeText = { text -> onElicitationFreeText(el, text) },
            )
        }

        if (rows.isEmpty()) {
            Text("等待会话内容…", style = MaterialTheme.typography.bodySmall)
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (rows.isNotEmpty()) {
                item(key = "earlier-head") {
                    Text(
                        when {
                            earlier.loading -> "加载更早…"
                            earlier.pulled && !earlier.hasMore -> "已到最早"
                            else -> "上滑加载更早"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
            items(rows, key = { it.rowId }) { row -> RowItem(row) }
        }

        // 附件条（P1-3）：已上传 chip + 上传进度 + 选择文件
        val context = LocalContext.current
        val filePicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            uri?.let {
                readAttachment(context, it)?.let { (name, mime, data) ->
                    onAttachmentPicked(name, mime, data)
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

        // 底部输入栏：与审批条并存（审批条在顶部）；官方 web 版逻辑——
        // 有草稿显示「发送」，空草稿且 canStop 显示「停止」。
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

/** 大小上限与 ConversationChannel 保持一致（host attachmentMaxBytes = 20MiB）。 */
private const val MAX_ATTACHMENT_BYTES = 20 * 1024 * 1024

/**
 * 从 content:// 读取附件的名字、MIME 与字节。超过上限 / 读不到时返回 null。
 * 注意：整块读进内存（上限 20MiB），大文件后续可改成分片流式读。
 */
private fun readAttachment(context: Context, uri: Uri): Triple<String, String, ByteArray>? {
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
    if (size > MAX_ATTACHMENT_BYTES) return null
    val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
    val data = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                total += n
                if (total > MAX_ATTACHMENT_BYTES) return null
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
    }.getOrNull() ?: return null
    return Triple(name, mime, data)
}

/** 附件条：无附件且未上传时不占位。 */
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
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("⏳ $name 上传中 $uploadPercent%",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                LinearProgressIndicator(
                    progress = { uploadPercent / 100f },
                    modifier = Modifier.width(80.dp),
                )
            }
        }
        attachments.forEach { a ->
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("📎 ${a.fileName}", style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(humanBytes(a.bytes), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { onRemove(a) }, contentPadding = PaddingValues(0.dp)) {
                    Text("移除", style = MaterialTheme.typography.labelSmall)
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
        // 语音状态条（P1-3）：聆听中的实时识别文本，或错误 / 权限提示（4s 自动清除）
        val voiceLine = if (voiceListening) "🎤 聆听中：${voiceLive ?: "…"}" else voiceLive
        voiceLine?.let { line ->
            LaunchedEffect(line) {
                if (line.startsWith("⚠️")) { delay(4000); voiceLive = null }
            }
            Text(
                line,
                style = MaterialTheme.typography.labelMedium,
                color = if (line.startsWith("⚠️")) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onPick, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("📎", style = MaterialTheme.typography.titleMedium)
            }
            VoiceInputButton(
                onFinalText = { text ->
                    onPromptChange(if (prompt.isBlank()) text else "$prompt $text")
                },
                onStateChange = { listening, live ->
                    voiceListening = listening
                    voiceLive = live
                },
            )
            OutlinedTextField(
                value = prompt,
                onValueChange = onPromptChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("发送消息到桌面端…") },
                maxLines = 4,
                shape = RoundedCornerShape(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            if (canSend) {
                Button(
                    onClick = onSend,
                    enabled = !sending,
                ) {
                    Text(if (sending) "发送中…" else "发送")
                }
            } else if (canStop) {
                Button(
                    onClick = onStop,
                    enabled = !stopping,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(if (stopping) "停止中…" else "停止")
                }
            } else {
                Button(onClick = {}, enabled = false) { Text("发送") }
            }
        }
    }
}

/**
 * 一条待应答表单交互（elicitation）：
 * plan_approval → 计划文本 + 批准/拒绝；questions → 逐题选项（多选可多选）+ 提交；
 * freeText → 文本框。构造规则见 PROTOCOL.md §6.5。
 */
@Composable
private fun ElicitationCard(
    el: PendingElicitation,
    onAccept: (Map<Int, List<String>>) -> Unit,
    onDecline: () -> Unit,
    onFreeText: (String) -> Unit,
) {
    // 每题已选值（多选可累加；单选点即替换），空 map 表示未作答
    val selected = remember(el.interactionId) { mutableStateMapOf<Int, MutableList<String>>() }
    val freeTextDraft = remember(el.interactionId) { mutableStateOf("") }

    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                listOfNotNull(
                    when {
                        el.isPlanApproval -> "📋 计划待批准"
                        el.questions.isNotEmpty() -> "❓ ${el.toolName ?: "表单"} · 需要你的回答"
                        else -> "❓ ${el.toolName ?: "确认"}"
                    },
                    el.questions.firstOrNull()?.header?.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                style = MaterialTheme.typography.titleSmall,
            )

            el.plan?.let {
                Text(it.take(2500), style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace, maxLines = 14, overflow = TextOverflow.Ellipsis)
            }

            if (el.isPlanApproval) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onAccept(emptyMap()) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) {
                        Text("批准计划")
                    }
                    Button(onClick = onDecline,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                        Text("拒绝")
                    }
                }
                return@Column
            }

            el.questions.forEachIndexed { qIdx, q ->
                Text(q.question.ifBlank { q.header ?: "" },
                    style = MaterialTheme.typography.bodyMedium)
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
                            containerColor = MaterialTheme.colorScheme.secondaryContainer)
                        else ButtonDefaults.outlinedButtonColors(),
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(opt.label, style = MaterialTheme.typography.bodyMedium)
                            opt.description?.let {
                                Text(it, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (el.freeText) {
                    OutlinedTextField(
                        value = freeTextDraft.value,
                        onValueChange = { freeTextDraft.value = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("或输入自定义答案…") },
                        maxLines = 3,
                    )
                }
            }

            if (el.questions.isEmpty() && el.freeText) {
                // 无 questions 的纯文本应答（官方走 {freeText}）
                OutlinedTextField(
                    value = freeTextDraft.value,
                    onValueChange = { freeTextDraft.value = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("输入回答…") },
                    maxLines = 3,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onFreeText(freeTextDraft.value.trim()) },
                        enabled = freeTextDraft.value.isNotBlank()) { Text("提交") }
                    Button(onClick = onDecline,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                        Text("拒绝")
                    }
                }
                return@Column
            }

            if (el.questions.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val answers = selected.mapValues { (_, list) -> list.toList() }
                                .toMutableMap()
                            // 自由文本并入第一题（追加为额外答案）
                            if (el.freeText && freeTextDraft.value.isNotBlank()) {
                                val cur = answers.getOrPut(0) { emptyList() }
                                answers[0] = cur + freeTextDraft.value.trim()
                            }
                            onAccept(answers.filter { it.value.isNotEmpty() })
                        },
                        enabled = selected.isNotEmpty() || freeTextDraft.value.isNotBlank(),
                    ) { Text("提交回答") }
                    Button(onClick = onDecline,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                        Text("拒绝")
                    }
                }
            }
        }
    }
}

/** 一条待审批：工具名 + 摘要 + 详情 + 按 kind 排好序的选项按钮。 */
@Composable
private fun ApprovalCard(
    a: PendingApproval,
    onResolve: (PendingApproval, ApprovalOption) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("⚠️ 需要审批 · ${a.toolName ?: "工具调用"}",
                style = MaterialTheme.typography.titleSmall)
            a.summary?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3,
                    overflow = TextOverflow.Ellipsis)
            }
            a.detail?.let {
                Text(it.take(600), style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace)
            }
            a.autoResolveAt?.let {
                Text("桌面端 ${(it - System.currentTimeMillis()).coerceAtLeast(0) / 1000}s 后自动决议",
                    style = MaterialTheme.typography.labelSmall)
            }
            if (a.options.isEmpty()) {
                Text("服务端没给选项，请到桌面端处理", style = MaterialTheme.typography.bodySmall)
            }
            a.options.forEach { opt ->
                Button(
                    onClick = { onResolve(a, opt) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (opt.isAllow) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(
                        when {
                            opt.kind == "allowOnce" -> "允许一次"
                            opt.kind == "allowAlways" -> "总是允许"
                            opt.isDeny -> "拒绝"
                            else -> opt.label.ifBlank { "选项" }
                        }
                    )
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
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            modifier = Modifier.widthIn(max = 300.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(Modifier.padding(10.dp)) {
                Text(text, style = MaterialTheme.typography.bodyMedium)
                if (streaming) {
                    Text("生成中…", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun ReasoningBlock(row: ConversationRow) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(Modifier.padding(8.dp)) {
            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                Text(if (expanded) "▾ 思考过程" else "▸ 思考过程", style = MaterialTheme.typography.labelMedium)
            }
            if (expanded) {
                Text(
                    row.text.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ToolCallCard(row: ConversationRow) {
    var expanded by remember { mutableStateOf(false) }
    val tone = when (row.status) {
        "success" -> Color(0xFF4CAF50)
        "error", "cancelled" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    Card(shape = RoundedCornerShape(8.dp)) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔧 ${row.toolName ?: "tool"}", style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f))
                Text(row.status ?: "", style = MaterialTheme.typography.labelSmall, color = tone)
            }
            // 摘要行：把输入压成一行看个大概
            val summary = row.inputText?.replace('\n', ' ')?.trim()
            if (!summary.isNullOrBlank()) {
                Text(summary, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                Text(if (expanded) "收起" else "详情", style = MaterialTheme.typography.labelSmall)
            }
            if (expanded) {
                CodeBlock("输入", row.inputText)
                CodeBlock("输出", row.outputText)
            }
        }
    }
}

@Composable
private fun CodeBlock(label: String, content: String?) {
    if (content.isNullOrBlank()) return
    Text(label, style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(top = 4.dp))
    Text(
        content.take(4000),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(top = 2.dp),
    )
}

@Composable
private fun TurnHeaderRow(row: ConversationRow) {
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    Text(
        listOfNotNull(
            row.state,
            row.createdAt?.let { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(it)) },
        ).joinToString(" · "),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PlainRow(row: ConversationRow) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(8.dp)) {
            Text("${row.kind} #${row.rowId}", style = MaterialTheme.typography.labelSmall)
            row.text?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 4,
                overflow = TextOverflow.Ellipsis) }
        }
    }
}

private fun statusLabel(s: ConversationChannel.Status) = when (s) {
    ConversationChannel.Status.Idle -> "未订阅"
    ConversationChannel.Status.Hello -> "握手中…"
    ConversationChannel.Status.Initialized -> "订阅中…"
    is ConversationChannel.Status.Live -> "已订阅"
    is ConversationChannel.Status.Failed -> "失败：${s.reason.take(80)}"
}

private fun phaseLabel(p: String) = when (p) {
    "completedInterrupted" -> "已结束"
    "idle" -> "空闲"
    "running" -> "运行中"
    else -> p
}
