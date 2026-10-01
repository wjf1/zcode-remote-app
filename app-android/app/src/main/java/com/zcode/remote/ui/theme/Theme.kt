package com.zcode.remote.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 对齐官方 ZCode (zai-dark / zai-light) 设计系统的色彩体系。
 * 遵循官方 calm, dense, operational 的高信息密度与低视觉疲劳规范。
 */
private val ZCodeDarkScheme = darkColorScheme(
    primary = Color(0xFF7C9EFF),
    onPrimary = Color(0xFF0F172A),
    primaryContainer = Color(0xFF233054),
    onPrimaryContainer = Color(0xFFDCE6FF),

    secondary = Color(0xFF2DD4BF),
    onSecondary = Color(0xFF042F2C),
    secondaryContainer = Color(0xFF113835),
    onSecondaryContainer = Color(0xFFA7F3D0),

    tertiary = Color(0xFFA78BFA),
    onTertiary = Color(0xFF2E1065),
    tertiaryContainer = Color(0xFF2E234A),
    onTertiaryContainer = Color(0xFFDDD6FE),

    background = Color(0xFF161616),
    onBackground = Color(0xFFE2E8F0),

    surface = Color(0xFF202024),
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Color(0xFF282830),
    onSurfaceVariant = Color(0xFF94A3B8),

    outline = Color(0x26FFFFFF),          // 15% 官方微边框线
    outlineVariant = Color(0x14FFFFFF),   // 8% 极淡分割线

    error = Color(0xFFFF5C5C),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF451919),
    onErrorContainer = Color(0xFFFFD1D1),
)

private val ZCodeLightScheme = lightColorScheme(
    primary = Color(0xFF3B5BDB),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDBE4FE),
    onPrimaryContainer = Color(0xFF1E3A8A),

    secondary = Color(0xFF0F766E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCFBF1),
    onSecondaryContainer = Color(0xFF115E59),

    tertiary = Color(0xFF7C3AED),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFEDE9FE),
    onTertiaryContainer = Color(0xFF5B21B6),

    background = Color(0xFFF8F8FA),
    onBackground = Color(0xFF1E293B),

    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1E293B),
    surfaceVariant = Color(0xFFF1F3F7),
    onSurfaceVariant = Color(0xFF64748B),

    outline = Color(0x1F000000),          // 12% 官方浅色微边框
    outlineVariant = Color(0x0F000000),

    error = Color(0xFFE03131),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFE3E3),
    onErrorContainer = Color(0xFF991B1B),
)

/**
 * 官方 Trajectory 轨迹色标与状态色（跨深浅主题语义对齐）。
 */
object ZCodeTokens {
    val UserTrajectoryDark = Color(0xFF60A5FA)
    val UserTrajectoryLight = Color(0xFF2563EB)

    val AssistantTrajectoryDark = Color(0xFF2DD4BF)
    val AssistantTrajectoryLight = Color(0xFF0F766E)

    val ReasoningTrajectoryDark = Color(0xFFA78BFA)
    val ReasoningTrajectoryLight = Color(0xFF7C3AED)

    val ToolCallTrajectoryDark = Color(0xFFF59E0B)
    val ToolCallTrajectoryLight = Color(0xFFD97706)

    // 运行/连接状态色
    val StatusOnline = Color(0xFF46BF72)      // 官方 success 绿
    val StatusPending = Color(0xFFFF8A30)     // 官方 warning 橙
    val StatusOffline = Color(0xFF8B949E)     // 官方 neutral 灰
    val StatusError = Color(0xFFFF5C5C)       // 官方 destructive 红

    // 代码块专用背景（深浅隔离）
    val CodeBgDark = Color(0xFF1A1B22)
    val CodeBgLight = Color(0xFFF3F4F6)
    val CodeHeaderDark = Color(0xFF232530)
    val CodeHeaderLight = Color(0xFFE5E7EB)
}

/** dark=null 表示跟随系统（设置项 THEME_SYSTEM）。 */
@Composable
fun ZCodeTheme(forceDark: Boolean? = true, content: @Composable () -> Unit) {
    val dark = forceDark ?: isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) ZCodeDarkScheme else ZCodeLightScheme,
        content = content
    )
}
