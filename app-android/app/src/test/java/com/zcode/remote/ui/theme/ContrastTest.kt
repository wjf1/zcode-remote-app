package com.zcode.remote.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.pow

/**
 * C-4：主题关键色对的 WCAG 对比度断言（纯 JVM，可进 CI）。
 *
 * 为什么用单测而不是真机截图：对比度是静态可算的（任务书 §7「可读性」验收），
 * 而本项目没有 androidTest；把「文字/图标可读」钉死在这一层后，
 * 颜色值一旦被改回低对比（例如把浅色轨迹色硬编码成深色主题取色）CI 立刻变红。
 */
class ContrastTest {

    /** WCAG 2.x 相对亮度。 */
    private fun luminance(c: Color): Double {
        fun channel(v: Float): Double {
            val s = v.toDouble()
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val (hi, lo) = if (la >= lb) la to lb else lb to la
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun assertReadable(name: String, fg: Color, bg: Color, min: Double = 4.5) {
        val cr = contrast(fg, bg)
        assertTrue(
            "$name 对比度 ${String.format(Locale.US, "%.2f", cr)}:1 低于 $min:1",
            cr >= min,
        )
    }

    @Test
    fun lightThemeTextPairsMeetWcagAa() {
        val s = ZCodeLightScheme
        assertReadable("浅色 onSurface/background", s.onSurface, s.background)
        assertReadable("浅色 onSurfaceVariant/surface", s.onSurfaceVariant, s.surface)
        assertReadable("浅色 onSurfaceVariant/surfaceVariant", s.onSurfaceVariant, s.surfaceVariant)
        assertReadable("浅色 onSurfaceVariant/background", s.onSurfaceVariant, s.background)
    }

    @Test
    fun darkThemeTextPairsMeetWcagAa() {
        val s = ZCodeDarkScheme
        assertReadable("深色 onSurface/background", s.onSurface, s.background)
        assertReadable("深色 onSurfaceVariant/surface", s.onSurfaceVariant, s.surface)
    }

    @Test
    fun lightTrajectoryAndStatusTokensMeetWcagAa() {
        val bg = ZCodeLightScheme.background
        assertReadable("浅色工具轨迹色", ZCodeTokens.ToolCallTrajectoryLight, bg)
        assertReadable("浅色思考轨迹色", ZCodeTokens.ReasoningTrajectoryLight, bg)
        assertReadable("浅色助手轨迹色", ZCodeTokens.AssistantTrajectoryLight, bg)
        assertReadable("浅色待处理橙（加深版）", ZCodeTokens.StatusPendingLight, bg)
    }

    @Test
    fun darkTrajectoryAndStatusTokensMeetWcagAa() {
        val bg = ZCodeDarkScheme.background
        assertReadable("深色工具轨迹色", ZCodeTokens.ToolCallTrajectoryDark, bg)
        assertReadable("深色待处理橙", ZCodeTokens.StatusPending, bg)
        assertReadable("深色错误色", ZCodeTokens.StatusError, bg)
    }

    /**
     * 反向断言：亮橙 #FF8A30 在浅色底上必然不达标——它只允许出现在深色主题，
     * 谁把它用回浅色场景（如硬编码容器色）这条断言会提醒为什么不能那样做。
     */
    @Test
    fun rawPendingOrangeIsUnreadableOnLightBackground() {
        val cr = contrast(ZCodeTokens.StatusPending, ZCodeLightScheme.background)
        assertTrue(
            "亮橙在浅色底上应 <4.5:1（用于提醒不得在浅色场景直接使用），实际 ${String.format(Locale.US, "%.2f", cr)}",
            cr < 4.5,
        )
    }

    /**
     * 反向断言：加深前的浅色次要文本与工具轨迹色（改色前的 #64748B / #D97706）
     * 在 surfaceVariant 卡内不达标——防止有人「改回去」。
     */
    @Test
    fun preFixLightValuesRemainUnreadableOnSurfaceVariant() {
        val bg = ZCodeLightScheme.surfaceVariant
        val oldOnSurfaceVariant = Color(0xFF64748B)
        val oldToolTrajectory = Color(0xFFD97706)
        assertTrue("旧次要文本色不应达标", contrast(oldOnSurfaceVariant, bg) < 4.5)
        assertTrue("旧工具轨迹色不应达标", contrast(oldToolTrajectory, bg) < 4.5)
    }
}
