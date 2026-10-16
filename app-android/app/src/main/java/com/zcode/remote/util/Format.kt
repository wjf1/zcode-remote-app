package com.zcode.remote.util

import com.zcode.remote.R
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 数值/时长格式化。**故意做成无 Compose、无 Android 依赖的纯函数**，
 * 因为会话页的「已工作 3分20秒」「12.3k tokens」「78%」这类文案全靠它，
 * 一旦掺进 Composable 就只能靠人眼截图验收，写不成单测。
 *
 * 桌面端口径见 `research/asar/out/renderer/assets/styles-t2tKjMWX.css` 一带的组件源码：
 * 时长按「小时/分/秒」三级、数字按 1.2k / 1.2M 缩略、百分比取整。
 */
object Format {
    /**
     * 大数缩略：`999` → `999`，`1200` → `1.2k`，`10000` → `10k`，`1_234_000` → `1.2M`。
     *
     * 1000 以下原样返回；k/M 档只保留一位小数，且 `.0` 会被去掉（桌面端 `1.0k` 这种写法不出现）。
     */
    fun compactNumber(n: Long): String {
        val neg = n < 0
        val v = abs(n)
        val s =
            when {
                v < 1_000 -> v.toString()
                v < 1_000_000 -> trimZero(v / 1000.0, "k")
                v < 1_000_000_000L -> trimZero(v / 1_000_000.0, "M")
                else -> trimZero(v / 1_000_000_000.0, "B")
            }
        return if (neg) "-$s" else s
    }

    private fun trimZero(
        v: Double,
        unit: String,
    ): String {
        // 四舍五入到一位小数后，若小数位为 0 则只留整数部分
        val scaled = (v * 10).roundToInt()
        return if (scaled % 10 == 0) "${scaled / 10}$unit" else "${scaled / 10}.${scaled % 10}$unit"
    }

    /**
     * 比例 → 百分比整数，入参是 0..1 的比值（不是 0..100）。
     * `0.7823` → `78%`；越界值夹到 0..100，避免上下文用量异常时显示 `900%`。
     */
    fun percent(ratio: Double): String {
        val pct = (ratio * 100).roundToInt().coerceIn(0, 100)
        return "$pct%"
    }

    /**
     * 时长文案（不含前缀）。
     *
     * - `< 1s` → `不到 1 秒`
     * - `< 60s` → `12 秒`
     * - `< 1h` → `3 分 20 秒`（整分时省略秒：`3 分`）
     * - 其余 → `2 小时 5 分`
     */
    fun duration(ms: Long?): String {
        if (ms == null || ms < 0) return ""
        val totalSec = ms / 1000
        if (ms < 1000) return "不到 1 秒"
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return when {
            h > 0 -> if (m > 0) "$h 小时 $m 分" else "$h 小时"
            m > 0 -> if (s > 0) "$m 分 $s 秒" else "$m 分"
            else -> "$s 秒"
        }
    }

    /** 「已工作 X」+ [duration]；无时长返回空串（调用方据此隐藏该行）。 */
    fun workedDuration(ms: Long?): String {
        val d = duration(ms)
        return if (d.isEmpty()) "" else "已工作 $d"
    }

    /**
     * 会话流的日期分隔标签（走查 #14）。
     *
     * 输入是**行级绝对时间戳** `ConversationRow.createdAt`（协议文档 §6.2 明列，ms）。
     * 返回 [UiText] 而非 String：日期文案需本地化（今天/昨天/年月日），
     * 且本函数是纯函数，`UiText` 让 JVM 单测能断言资源 id 与参数、不必碰 Android。
     *
     * 判定按**本地时区的日历日**（`Calendar`），不是「距今 24 小时」——后者在 23:59 与
     * 00:01 的两行会被判为同一天，而用户看到的是两天的消息。
     *
     * @param nowMs 当前时刻（调用方传入，函数不读时钟——否则单测只能靠等到明天）
     */
    fun dateDividerLabel(
        createdAtMs: Long,
        nowMs: Long,
    ): UiText {
        val zone = java.util.TimeZone.getDefault()
        val then =
            java.util.Calendar.getInstance(zone).apply {
                timeInMillis = createdAtMs
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
        val today =
            java.util.Calendar.getInstance(zone).apply {
                timeInMillis = nowMs
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
        // 日历日差值：按毫秒差除以一天，四舍五入吸收夏令时导致的 23/25 小时日
        val dayDiff = Math.round((today.timeInMillis - then.timeInMillis).toDouble() / 86_400_000.0)
        return when (dayDiff) {
            0L -> UiText.Res(R.string.timeline_date_today)
            1L -> UiText.Res(R.string.timeline_date_yesterday)
            else ->
                UiText.Res(
                    R.string.timeline_date_other,
                    listOf(
                        then.get(java.util.Calendar.YEAR),
                        then.get(java.util.Calendar.MONTH) + 1,
                        then.get(java.util.Calendar.DAY_OF_MONTH),
                    ),
                )
        }
    }

    /**
     * 某一行的日期是否与上一行**不同日**——决定要不要在该行上方插日期分隔。
     *
     * @param prevCreatedAtMs 上一行的 createdAt（null = 本行是首行，总要插分隔）；
     *                        `prev` 为 null 但 `current` 也 null 时返回 false（都无时间戳，无从判断）
     */
    fun needsDateDivider(
        prevCreatedAtMs: Long?,
        currentCreatedAtMs: Long?,
    ): Boolean {
        if (currentCreatedAtMs == null) return false
        if (prevCreatedAtMs == null) return true
        // 用「年 + 月 + 日」三元组比日历日：**不可**用 `YEAR*366 + DAY_OF_YEAR`——
        // 平年/闰年的 DAY_OF_YEAR 起点不同（闰年 12-31 的 DAY_OF_YEAR=366，平年同一天=365），
        // 跨年比较会错位（如闰年 12-30 与次年 1-1 可能被误判为同一天）。
        val zone = java.util.TimeZone.getDefault()

        fun calendarDay(ms: Long): Triple<Int, Int, Int> =
            java.util.Calendar.getInstance(zone).apply { timeInMillis = ms }.let {
                Triple(
                    it.get(java.util.Calendar.YEAR),
                    it.get(java.util.Calendar.MONTH),
                    it.get(java.util.Calendar.DAY_OF_MONTH),
                )
            }
        return calendarDay(prevCreatedAtMs) != calendarDay(currentCreatedAtMs)
    }

    /**
     * 多行文本压成单行摘要（折叠态行内用）：所有空白折叠为一个空格。
     * 空白串与 null 都返回 null，让调用方可以直接 `?.let` 决定要不要渲染这一格。
     */
    fun singleLine(
        text: String?,
        limit: Int = 200,
    ): String? {
        val compressed = text?.replace(WHITESPACE, " ")?.trim().orEmpty()
        if (compressed.isEmpty()) return null
        return if (compressed.length <= limit) compressed else compressed.take(limit) + "…"
    }

    private val WHITESPACE = Regex("\\s+")
}
