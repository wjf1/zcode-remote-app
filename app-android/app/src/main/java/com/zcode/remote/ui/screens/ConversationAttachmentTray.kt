package com.zcode.remote.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zcode.remote.AppViewModel
import com.zcode.remote.relay.ConversationChannel
import com.zcode.remote.ui.theme.ZCodeType

/**
 * 会话页附件托盘（§11.7 结构性拆分：自 `ConversationScreen.kt` 原样搬出，只移动不改行为）。
 * 已选附件列表 / 上传进度（可取消）/ 失败重试行，数据由 AppViewModel 下发。
 */

internal fun inspectAttachment(context: Context, uri: Uri): Triple<String, String, Long>? {
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
internal fun AttachmentBar(
    attachments: List<ConversationChannel.AttachmentRef>,
    uploadName: String?,
    uploadPercent: Int,
    onPick: () -> Unit,
    onRemove: (ConversationChannel.AttachmentRef) -> Unit,
    failedUpload: AppViewModel.FailedUpload?,
    onCancelUpload: () -> Unit,
    onRetryUpload: () -> Unit,
    onDismissUploadFailure: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty() && uploadName == null && failedUpload == null) return
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                // C-1：在途上传可取消（原实现只能等它失败；取消会发 attachmentAbortV4）
                TextButton(
                    onClick = onCancelUpload,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    modifier = Modifier.height(24.dp),
                ) {
                    Text("取消", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        // C-1：失败态行（重试复用同一 uploadId —— 服务端幂等分支命中后已传分片不重传）
        failedUpload?.let { f ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "上传失败：${f.fileName}",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = f.message,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = ZCodeType.Xs),
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    TextButton(
                        onClick = onRetryUpload,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        modifier = Modifier.height(24.dp),
                    ) {
                        Text("重试", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                    TextButton(
                        onClick = onDismissUploadFailure,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        modifier = Modifier.height(24.dp),
                    ) {
                        Text("取消", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
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
                    Icon(Icons.Default.AttachFile, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
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

internal fun humanBytes(n: Long): String = when {
    n < 1024 -> "${n}B"
    n < 1024 * 1024 -> "%.1fKB".format(n / 1024.0)
    else -> "%.1fMB".format(n / 1024.0 / 1024.0)
}
