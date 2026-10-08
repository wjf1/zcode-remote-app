package com.zcode.remote.ui.theme

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * 桌面端字号档位，取自 `styles-t2tKjMWX.css` 的 `--ui-*` 变量（基准 `--ui-font-size` = 14px）。
 *
 * 桌面端字号是固定像素、不随窗口缩放，故此处也用固定 sp，刻意不做响应式放大：
 * 手机屏幕更小，字号一旦跟着缩放就会比桌面端更大，反而拉大视觉差异。
 *
 * 行高同样对齐桌面端：14px 字号配 22px 行高（约 1.57 倍）。
 */
object ZCodeType {
    /** --ui-xs */
    val Xs: TextUnit = 10.sp

    /** --ui-sm */
    val Sm: TextUnit = 12.sp

    /** --ui-caption */
    val Caption: TextUnit = 13.sp

    /** --ui-base，正文基准 */
    val Base: TextUnit = 14.sp

    /** --ui-lg */
    val Lg: TextUnit = 16.sp

    /** --ui-xl */
    val Xl: TextUnit = 18.sp

    val Regular: FontWeight = FontWeight.Normal
    val Medium: FontWeight = FontWeight.Medium
    val SemiBold: FontWeight = FontWeight.SemiBold

    /** 正文行高（14sp / 22sp）。 */
    val BodyLineHeight: TextUnit = 22.sp

    /** 代码行高：桌面端代码块比正文更紧，取 20sp。 */
    val CodeLineHeight: TextUnit = 20.sp
}
