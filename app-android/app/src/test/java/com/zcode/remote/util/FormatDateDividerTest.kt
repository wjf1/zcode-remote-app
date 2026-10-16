package com.zcode.remote.util

import com.zcode.remote.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * 会话流日期分隔的纯函数单测（走查 #14）。
 *
 * 背景：长会话无时间锚点，用户往下翻几百行后分不清「这条是今天的还是上周的」。
 * 修复依赖 `ConversationRow.createdAt`（协议文档 §6.2 明列的行级绝对时间戳）。
 *
 * 两个函数都是纯函数 + 参数化 `nowMs`（不读时钟），故可把「今天/昨天/更早」与
 * 「跨日才插分隔」的边界钉死，无需 androidTest。
 *
 * **注意时区**：断言用 `TimeZone.getDefault()` 构造样本，与实现同源——
 * 若在断言里写死 UTC 毫秒数，会在非 UTC 时区的 CI 上偶发失败。
 */
class FormatDateDividerTest {
    private val zone: TimeZone get() = TimeZone.getDefault()

    /** 构造 y/m/d hh:mm 的毫秒（月份 1-based，与 UI 一致）。 */
    private fun atHour(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int = 0,
    ): Long =
        Calendar
            .getInstance(zone)
            .apply {
                clear()
                set(year, month - 1, day, hour, minute, 0)
            }.timeInMillis

    @Test
    fun sameCalendarDayIsToday() {
        val now = atHour(2026, 10, 16, 15, 30)
        // 同一天的凌晨 00:01 与 23:59 都应是「今天」（按日历日，不按距今 24h）
        assertEquals(UiText.Res(R.string.timeline_date_today), Format.dateDividerLabel(atHour(2026, 10, 16, 0, 1), now))
        assertEquals(UiText.Res(R.string.timeline_date_today), Format.dateDividerLabel(atHour(2026, 10, 16, 23, 59), now))
    }

    @Test
    fun previousCalendarDayIsYesterday() {
        val now = atHour(2026, 10, 16, 0, 30)
        // 昨天 23:59 → 昨天（即便距今仅 31 分钟）
        assertEquals(
            UiText.Res(R.string.timeline_date_yesterday),
            Format.dateDividerLabel(atHour(2026, 10, 15, 23, 59), now),
        )
    }

    @Test
    fun olderDayShowsYearMonthDay() {
        val now = atHour(2026, 10, 16, 12, 0)
        assertEquals(
            UiText.Res(R.string.timeline_date_other, listOf(2026, 10, 14)),
            Format.dateDividerLabel(atHour(2026, 10, 14, 9, 0), now),
        )
    }

    /**
     * 跨年边界：12-31 → 次年 1-1 **相差正好 1 个日历日**，故判为「昨天」是正确的
     * （这是产品语义：昨天就是昨天，不管跨不跨年）。此前误以为应显示完整年月日，
     * 是本测试的期望写错，实现正确。
     */
    @Test
    fun yearBoundaryIsYesterday() {
        val now = atHour(2026, 1, 1, 10, 0)
        assertEquals(
            UiText.Res(R.string.timeline_date_yesterday),
            Format.dateDividerLabel(atHour(2025, 12, 31, 23, 0), now),
        )
    }

    /** 跨年但超过 1 天（前年）→ 显示完整年月日。 */
    @Test
    fun twoDaysAgoAcrossYearShowsFullDate() {
        val now = atHour(2026, 1, 2, 10, 0)
        assertEquals(
            UiText.Res(R.string.timeline_date_other, listOf(2025, 12, 31)),
            Format.dateDividerLabel(atHour(2025, 12, 31, 23, 0), now),
        )
    }

    // ---------- needsDateDivider ----------

    @Test
    fun firstRowAlwaysNeedsDivider() {
        // 首行（无上一行）——有时间戳就要插分隔
        assertTrue(Format.needsDateDivider(prevCreatedAtMs = null, currentCreatedAtMs = atHour(2026, 10, 16, 9, 0)))
    }

    @Test
    fun noTimestampMeansNoDivider() {
        // 当前行无时间戳：无从判断，不插（宁可不插，也不插错的）
        assertFalse(Format.needsDateDivider(prevCreatedAtMs = atHour(2026, 10, 16, 9, 0), currentCreatedAtMs = null))
        assertFalse(Format.needsDateDivider(prevCreatedAtMs = null, currentCreatedAtMs = null))
    }

    @Test
    fun sameDayDoesNotNeedDivider() {
        assertFalse(
            Format.needsDateDivider(
                prevCreatedAtMs = atHour(2026, 10, 16, 1, 0),
                currentCreatedAtMs = atHour(2026, 10, 16, 23, 0),
            ),
        )
    }

    @Test
    fun crossingMidnightNeedsDivider() {
        // 23:59 → 次日 00:01：跨日历日，必须插
        assertTrue(
            Format.needsDateDivider(
                prevCreatedAtMs = atHour(2026, 10, 15, 23, 59),
                currentCreatedAtMs = atHour(2026, 10, 16, 0, 1),
            ),
        )
    }

    @Test
    fun midnightBoundaryUsesCalendarDay() {
        // 同一分钟内跨日：23:59:xx 与 00:00:xx 分属两天
        val before = atHour(2026, 10, 15, 23, 59)
        val after = atHour(2026, 10, 16, 0, 0)
        assertTrue(Format.needsDateDivider(before, after))
        assertFalse(Format.needsDateDivider(after, after + 60_000))
    }
}
