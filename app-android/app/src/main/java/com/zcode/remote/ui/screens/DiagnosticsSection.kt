package com.zcode.remote.ui.screens

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.zcode.remote.BuildConfig
import com.zcode.remote.util.ZLog
import com.zcode.remote.util.diag.DiagEventCode
import com.zcode.remote.util.diag.Diagnostics
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设置页「诊断日志」区块（任务书 §11.8 可观测性）。
 *
 * 内容：本地、脱敏、容量有界的诊断环形缓冲（[Diagnostics]）的规模摘要 + **用户主动导出**入口。
 * 导出走系统 SAF（`ACTION_CREATE_DOCUMENT`），由用户选择落盘位置——App **不**自动上传任何诊断数据；
 * 文本内容只含事件码/计数/耗时（见 `Diagnostics.exportText`），不含会话正文与凭据。
 *
 * 可测性：区块自包含（自己解析 `LocalContext`、自己发 SAF intent），渲染所需的外部状态只有环形缓冲；
 * 仪器化测试直接渲染本组件并**真实点击**导出按钮，断言「导出请求」事件已写入缓冲（见
 * `DiagnosticsSectionTest`）。
 */
@Composable
fun DiagnosticsSection(
    modifier: Modifier = Modifier,
    /**
     * 导出落盘的注入点（仅仪器化测试使用；null = 走真实系统 SAF 保存对话框）。
     * 测试注入可避免拉起外部 DocumentsUI 造成用例脆弱，同时仍以**真实点击**驱动本组件。
     */
    exportOverride: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    var eventCount by remember { mutableIntStateOf(Diagnostics.ring.size) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult // 用户取消：不写盘
            writeDiagnostics(context, uri)
            eventCount = Diagnostics.ring.size
        }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("诊断日志", style = MaterialTheme.typography.titleSmall)
            Text(
                "本地脱敏记录（事件码 / 计数 / 耗时），不含会话内容与凭据；" +
                    "已记录 $eventCount / ${Diagnostics.ring.capacity} 条（共 ${Diagnostics.ring.totalRecorded}）。" +
                    "仅在你主动导出时写出文件，App 不会自动上传。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    modifier = Modifier.testTag(TAG_EXPORT_BUTTON),
                    onClick = {
                        // 先记「用户请求导出」，再拉起系统保存对话框（用户取消也留痕，便于排障对齐）
                        Diagnostics.record(DiagEventCode.DIAG_EXPORT_REQUESTED)
                        eventCount = Diagnostics.ring.size
                        val name = defaultFileName()
                        if (exportOverride != null) exportOverride(name) else launcher.launch(name)
                    },
                ) { Text("导出诊断日志") }
            }
        }
    }
}

/** 默认文件名（时间戳；不含设备/会话信息）。 */
internal fun defaultFileName(nowMs: Long = System.currentTimeMillis()): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(nowMs))
    return "zcode-diagnostics-$stamp.txt"
}

/** 写出诊断文本到用户选定的 URI；成功/失败都记事件（失败原因只记异常类名，不记路径）。 */
private fun writeDiagnostics(
    context: Context,
    uri: Uri,
) {
    val text =
        Diagnostics.exportText(
            appVersion = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
        )
    val ok =
        runCatching {
            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
            } ?: error("openOutputStream returned null")
        }.fold(
            onSuccess = { true },
            onFailure = { t ->
                ZLog.w(TAG, "诊断导出失败 ${t::class.simpleName}")
                Diagnostics.record(DiagEventCode.DIAG_EXPORT_FAILED, "err=${t::class.simpleName}")
                false
            },
        )
    if (ok) {
        Diagnostics.record(DiagEventCode.DIAG_EXPORT_WRITTEN, "bytes=${text.toByteArray(Charsets.UTF_8).size}")
    }
    Toast
        .makeText(
            context,
            if (ok) "诊断日志已导出" else "导出失败，请重试",
            Toast.LENGTH_SHORT,
        ).show()
}

private const val TAG = "DiagSection"

/** 仪器化测试用标记（真实点击目标）；androidTest 与 main 分属不同编译单元，故为 public。 */
const val TAG_EXPORT_BUTTON = "diag_export_button"
