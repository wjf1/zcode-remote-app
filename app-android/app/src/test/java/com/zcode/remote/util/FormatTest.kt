package com.zcode.remote.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [Format] 的纯函数单测。
 *
 * 这些文案会**原样出现在会话页与状态面板上**（「已工作 3 分 20 秒」「12.3k」「78%」），
 * 边界值（0 秒、整分、整小时、越界百分比、null 时长）都必须钉死 —— 一旦回归，
 * 表现是界面上出现「0 秒」「101%」「已工作 」这类脏文案，而项目没有 UI 自动化测试能兜住。
 */
class FormatTest {

    // ---------- compactNumber ----------

    @Test
    fun compactNumber_keepsValuesBelowOneThousand() {
        assertEquals("0", Format.compactNumber(0))
        assertEquals("1", Format.compactNumber(1))
        assertEquals("999", Format.compactNumber(999))
    }

    @Test
    fun compactNumber_thousandBoundary() {
        // 恰好 1000 就进位到 k 档，且整千不带小数位
        assertEquals("1k", Format.compactNumber(1_000))
    }

    @Test
    fun compactNumber_keepsOneDecimalInKiloRange() {
        assertEquals("1.2k", Format.compactNumber(1_200))
        assertEquals("10k", Format.compactNumber(10_000))
        assertEquals("128.5k", Format.compactNumber(128_500))
    }

    @Test
    fun compactNumber_megaAndGigaRanges() {
        assertEquals("1.2M", Format.compactNumber(1_234_000))
        assertEquals("2M", Format.compactNumber(2_000_000))
        assertEquals("2B", Format.compactNumber(2_000_000_000L))
    }

    @Test
    fun compactNumber_handlesNegative() {
        assertEquals("-1.5k", Format.compactNumber(-1_500))
    }

    // ---------- percent ----------

    @Test
    fun percent_roundsToWholeNumber() {
        assertEquals("0%", Format.percent(0.0))
        assertEquals("78%", Format.percent(0.7823))
        assertEquals("100%", Format.percent(1.0))
    }

    @Test
    fun percent_clampsOutOfRange() {
        // 上下文用量在服务端异常时可能给出 >1 的比值，界面上绝不允许出现 900%
        assertEquals("100%", Format.percent(9.0))
        assertEquals("0%", Format.percent(-0.5))
    }

    // ---------- duration ----------

    @Test
    fun duration_returnsEmptyForMissingOrNegative() {
        assertEquals("", Format.duration(null))
        assertEquals("", Format.duration(-1))
    }

    @Test
    fun duration_belowOneSecondUsesFriendlyWording() {
        assertEquals("不到 1 秒", Format.duration(0))
        assertEquals("不到 1 秒", Format.duration(999))
    }

    @Test
    fun duration_secondsRange() {
        assertEquals("1 秒", Format.duration(1_000))
        assertEquals("12 秒", Format.duration(12_000))
        assertEquals("59 秒", Format.duration(59_999))
    }

    @Test
    fun duration_minutesRange() {
        // 整分省略秒位
        assertEquals("1 分", Format.duration(60_000))
        assertEquals("3 分 20 秒", Format.duration(200_000))
        assertEquals("59 分 59 秒", Format.duration(3_599_000))
    }

    @Test
    fun duration_hoursRange() {
        assertEquals("1 小时", Format.duration(3_600_000))
        assertEquals("2 小时 5 分", Format.duration(7_500_000))
        // 小时档不显示秒位：7 小时 30 分 45 秒 → 「7 小时 30 分」
        assertEquals("7 小时 30 分", Format.duration(27_045_000))
        assertEquals("1 小时 1 分", Format.duration(3_660_000))
    }

    // ---------- workedDuration ----------

    @Test
    fun workedDuration_prefixesAndHidesWhenUnknown() {
        assertEquals("", Format.workedDuration(null))
        assertEquals("", Format.workedDuration(-1))
        assertEquals("已工作 12 秒", Format.workedDuration(12_000))
        assertEquals("已工作 2 小时 5 分", Format.workedDuration(7_500_000))
    }

    // ---------- singleLine ----------

    @Test
    fun singleLine_returnsNullForBlank() {
        assertNull(Format.singleLine(null))
        assertNull(Format.singleLine(""))
        assertNull(Format.singleLine("   \n\t  "))
    }

    @Test
    fun singleLine_collapsesWhitespaceRuns() {
        assertEquals("a b c", Format.singleLine("a\n\nb   c"))
        assertEquals("hello world", Format.singleLine("  hello   world  "))
    }

    @Test
    fun singleLine_truncatesOnlyWhenOverLimit() {
        // 恰好等于上限不截断，避免给用户一个没有省内容的多余省略号
        assertEquals("abc", Format.singleLine("abc", limit = 3))
        assertEquals("abcd", Format.singleLine("abcd", limit = 4))
        assertEquals("abc…", Format.singleLine("abcdef", limit = 3))
    }
}
