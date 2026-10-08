package com.zcode.remote.util

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
        val s = when {
            v < 1_000 -> v.toString()
            v < 1_000_000 -> trimZero(v / 1000.0, "k")
            v < 1_000_000_000L -> trimZero(v / 1_000_000.0, "M")
            else -> trimZero(v / 1_000_000_000.0, "B")
        }
        return if (neg) "-$s" else s
    }

    private fun trimZero(v: Double, unit: String): String {
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
     * 多行文本压成单行摘要（折叠态行内用）：所有空白折叠为一个空格。
     * 空白串与 null 都返回 null，让调用方可以直接 `?.let` 决定要不要渲染这一格。
     */
    fun singleLine(text: String?, limit: Int = 200): String? {
        val compressed = text?.replace(WHITESPACE, " ")?.trim().orEmpty()
        if (compressed.isEmpty()) return null
        return if (compressed.length <= limit) compressed else compressed.take(limit) + "…"
    }

    private val WHITESPACE = Regex("\\s+")
}
