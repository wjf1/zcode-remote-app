package com.zcode.remote.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zcode.remote.AppViewModel
import com.zcode.remote.R
import com.zcode.remote.util.asString

/**
 * 会话页弹层（§11.7 结构性拆分：自 `ConversationScreen.kt` 原样搬出，只移动不改行为）。
 * 「最近文件」面板（Sprint 3 第二步·剧本 B）：本会话涉及的文件列表 → 点选 →
 * `file.readTextFile` 预览（~ 路径经 resolvePath 展开）。只读，无任何写路径（§8 红线）。
 */

@Composable
internal fun SessionFilesPanel(
    files: List<com.zcode.remote.relay.SessionFiles.Entry>,
    preview: AppViewModel.FilePreview?,
    onPreview: (String) -> Unit,
    onDismissPreview: () -> Unit,
    onClose: () -> Unit,
) {
    var selected by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(max = 540.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            if (selected != null) {
                TextButton(onClick = {
                    onDismissPreview()
                    selected = null
                }) { Text(stringResource(R.string.dialog_back_to_files)) }
            } else {
                Text(
                    stringResource(R.string.dialog_session_files),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.weight(1f),
                )
            }
            TextButton(onClick = {
                onDismissPreview()
                onClose()
            }) { Text(stringResource(R.string.common_close)) }
        }

        when {
            selected == null -> {
                if (files.isEmpty()) {
                    Text(
                        stringResource(R.string.dialog_files_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                } else {
                    LazyColumn {
                        items(files.size) { idx ->
                            val f = files[idx]
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selected = f.path
                                            onPreview(f.path)
                                        }.padding(vertical = 8.dp, horizontal = 4.dp),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        f.path.substringAfterLast('/').substringAfterLast('\\'),
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        f.path,
                                        style =
                                            MaterialTheme.typography.labelSmall.copy(
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 11.sp,
                                            ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    f.toolName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }

            else ->
                when (val p = preview) {
                    is AppViewModel.FilePreview.Loading ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) { CircularProgressIndicator(Modifier.size(28.dp)) }

                    is AppViewModel.FilePreview.Loaded ->
                        Column {
                            Text(
                                p.path,
                                style =
                                    MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                    ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                val scroll = rememberScrollState()
                                Text(
                                    p.content.take(20_000),
                                    style =
                                        MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            lineHeight = 15.sp,
                                        ),
                                    modifier = Modifier.padding(8.dp).horizontalScroll(scroll).heightIn(max = 380.dp),
                                )
                            }
                        }

                    is AppViewModel.FilePreview.Failed ->
                        Text(
                            text = stringResource(R.string.file_preview_failed, p.message.asString(), p.path),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )

                    null -> Unit
                }
        }
    }
}
