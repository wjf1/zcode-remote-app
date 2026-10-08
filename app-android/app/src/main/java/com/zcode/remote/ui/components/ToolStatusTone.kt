package com.zcode.remote.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.zcode.remote.ui.theme.LocalZCodeDark
import com.zcode.remote.ui.theme.ZCodeTokens

/**
 * 工具状态 → 语义色，对齐桌面端：等待/停止用灰、执行中用琥珀、成功用绿、失败与被拒用红。
 *
 * 单独放一个文件而不是写进 [ToolKindLabels]：那个文件是**无 Compose 依赖的纯逻辑**，
 * 直接跑 JVM 单测；这里要读 `MaterialTheme` / `LocalZCodeDark`，混进去会污染它的可测性。
 */
@Composable
fun toolStatusTone(status: ToolStatus): Color {
    val dark = LocalZCodeDark.current
    return when (status) {
        ToolStatus.Completed -> ZCodeTokens.StatusOnline
        ToolStatus.Failed -> ZCodeTokens.StatusError
        ToolStatus.Denied -> ZCodeTokens.StatusError
        ToolStatus.Pending -> ZCodeTokens.StatusOffline
        ToolStatus.Stopped -> ZCodeTokens.StatusOffline
        ToolStatus.Running ->
            if (dark) ZCodeTokens.ToolCallTrajectoryDark else ZCodeTokens.ToolCallTrajectoryLight
        ToolStatus.Unknown -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}
