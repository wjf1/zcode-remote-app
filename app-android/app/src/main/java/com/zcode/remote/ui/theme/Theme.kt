package com.zcode.remote.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 对齐官方 ZCode (zai-dark / zai-light) 设计系统的色彩体系。
 * 遵循官方 calm, dense, operational 的高信息密度与低视觉疲劳规范。
 */
/** 深色主题色表。internal 供 JVM 对比度断言测试（[ContrastTest]）引用。 */
internal val ZCodeDarkScheme = darkColorScheme(
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

/** 浅色主题色表。internal 供 JVM 对比度断言测试（[ContrastTest]）引用。 */
internal val ZCodeLightScheme = lightColorScheme(
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
    // C-4 对比度修正：原 #64748B 在 surfaceVariant 卡内仅 4.28:1（<4.5 卡内不达标），
    // 加深为 #556074（白底 6.34:1 / #F8F8FA 上 5.98:1 / surfaceVariant 上约 5.9:1）。
    onSurfaceVariant = Color(0xFF556074),

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
    // C-4 对比度修正：原 #D97706 在白底 3.19:1 / 浅色面上不足——加深为 #B45309
    // （#F8F8FA 上 4.73:1 / 白底 5.02:1）。
    val ToolCallTrajectoryLight = Color(0xFFB45309)

    // 工具执行结果轨迹色（桌面端 toolResult：暗 #38bdf8 / 浅 #0284c7）——此前缺失
    val ToolResultTrajectoryDark = Color(0xFF38BDF8)
    val ToolResultTrajectoryLight = Color(0xFF0284C7)

    // 运行/连接状态色
    val StatusOnline = Color(0xFF46BF72)      // 官方 success 绿
    val StatusPending = Color(0xFFFF8A30)     // 官方 warning 橙（深色主题用）
    /**
     * C-4 对比度修正：浅色主题的待处理橙。原 #FF8A30 在白底仅 2.35:1，
     * 作为文字/徽标不可读——加深为 #C2410C（#F8F8FA 上 4.88:1 / 白底 5.18:1）。
     * 取色入口见 [statusPendingTone]。
     */
    val StatusPendingLight = Color(0xFFC2410C)
    val StatusOffline = Color(0xFF8B949E)     // 官方 neutral 灰
    val StatusError = Color(0xFFFF5C5C)       // 官方 destructive 红

    // 代码块专用背景（深浅隔离）
    val CodeBgDark = Color(0xFF1A1B22)
    val CodeBgLight = Color(0xFFF3F4F6)
    val CodeHeaderDark = Color(0xFF232530)
    val CodeHeaderLight = Color(0xFFE5E7EB)

    // ---- 桌面端半透明叠加层（surface / hover / selected）----
    val OverlaySurfaceDark = Color(0x0DFFFFFF)
    val OverlaySurfaceLight = Color(0x080D0D0D)
    val OverlaySurfaceHoverDark = Color(0x1AFFFFFF)
    val OverlaySurfaceHoverLight = Color(0x0D0D0D0D)
    val OverlayHoverDark = Color(0x0DFFFFFF)
    val OverlayHoverLight = Color(0x0D0D0D0D)
    val OverlaySelectedDark = Color(0x1AFFFFFF)
    val OverlaySelectedLight = Color(0x0D0D0D0D)

    // ---- 桌面端边框（border / border-hover）----
    val BorderSubtleDark = Color(0x1AFFFFFF)
    val BorderSubtleLight = Color(0x1A0D0D0D)
    val BorderHoverDark = Color(0x26FFFFFF)
    val BorderHoverLight = Color(0x260D0D0D)

    // ---- 容器底色 ----
    val CardDark = Color(0xFF2B2B2B)
    val PanelDark = Color(0xFF202020)
    val TagDark = Color(0xFF363636)
    val TagLight = Color(0xFFE6E6E6)

    // 闲时任务（idle task）标识色
    val IdleTaskDark = Color(0xFF7B5CE5)
    val IdleTaskLight = Color(0xFF9E77ED)
}

/**
 * 当前是否暗色。由 [ZCodeTheme] 注入：应用以 forceDark 覆盖系统主题时，
 * 深层组件用 isSystemInDarkTheme() 会读到相反的值，故统一走这里。
 */
val LocalZCodeDark = staticCompositionLocalOf { true }

/**
 * 待处理橙（主题感知，C-4）：浅色主题用加深版 [ZCodeTokens.StatusPendingLight]
 * （亮橙 #FF8A30 在白底仅 2.35:1，作文字/徽标不可读）；深色主题保持亮橙。
 *
 * 注意：只能用在本就 @Composable 的地方；非 Composable 的取色逻辑请显式接收
 * `dark: Boolean` 参数（本项目不做全局色值单例，避免主题切换后取到旧值）。
 */
@Composable
fun statusPendingTone(): Color =
    if (LocalZCodeDark.current) ZCodeTokens.StatusPending else ZCodeTokens.StatusPendingLight

/**
 * 工具调用轨迹色（主题感知，C-4）：浅色主题用加深版 [ZCodeTokens.ToolCallTrajectoryLight]
 * —— 原实现多处硬编码 Dark 版（#F59E0B）导致浅色主题下对比度仅 2.15:1。
 */
@Composable
fun toolCallTrajectoryTone(): Color =
    if (LocalZCodeDark.current) ZCodeTokens.ToolCallTrajectoryDark else ZCodeTokens.ToolCallTrajectoryLight

/** dark=null 表示跟随系统（设置项 THEME_SYSTEM）。 */
@Composable
fun ZCodeTheme(forceDark: Boolean? = true, content: @Composable () -> Unit) {
    val dark = forceDark ?: isSystemInDarkTheme()
    CompositionLocalProvider(LocalZCodeDark provides dark) {
        MaterialTheme(
            colorScheme = if (dark) ZCodeDarkScheme else ZCodeLightScheme,
            content = content
        )
    }
}
