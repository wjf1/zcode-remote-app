package com.zcode.remote.ui.components

import com.zcode.remote.R
import com.zcode.remote.util.UiText
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 审批自动决议倒计时文案的单测（纯函数 `countdownLabel`）。
 *
 * 背景：两处审批卡原先把倒计时渲染为**组合期算出的静态数字**，用户盯着的秒数不动；
 * 抽出纯函数后文案规则（含向上取整、到点文案）可被钉死，UI 侧只用计时器驱动它刷新。
 *
 * 国际化（第 4 批收尾）：返回类型由 `String` 改为 [UiText]，故断言由中文字面量
 * 改为「资源 id + 参数」——语义等价，且改文案不必动测试。
 */
class CountdownLabelTest {
    private val t0 = 1_000_000L

    @Test
    fun wholeSeconds() {
        assertEquals(
            UiText.Res(R.string.countdown_auto_resolve_seconds, listOf(10)),
            countdownLabel(deadlineAt = t0 + 10_000, now = t0),
        )
    }

    /** 剩余不足 1s 也要显示 1s（向上取整），不出现「0s 后自动决议」。 */
    @Test
    fun subSecondRoundsUpToOne() {
        assertEquals(
            UiText.Res(R.string.countdown_auto_resolve_seconds, listOf(1)),
            countdownLabel(deadlineAt = t0 + 400, now = t0),
        )
        assertEquals(
            UiText.Res(R.string.countdown_auto_resolve_seconds, listOf(1)),
            countdownLabel(deadlineAt = t0 + 1, now = t0),
        )
    }

    /** 超过整秒的零头向上取整（10.2s → 11s）。 */
    @Test
    fun partialSecondRoundsUp() {
        assertEquals(
            UiText.Res(R.string.countdown_auto_resolve_seconds, listOf(11)),
            countdownLabel(deadlineAt = t0 + 10_200, now = t0),
        )
    }

    /** 恰好到点：显示「即将自动决议」（不显示 0s）。 */
    @Test
    fun exactlyAtDeadline() {
        assertEquals(UiText.Res(R.string.countdown_auto_resolve_soon), countdownLabel(deadlineAt = t0, now = t0))
    }

    /** 已过期：同样显示「即将自动决议」（不等同出错文案）。 */
    @Test
    fun pastDeadline() {
        assertEquals(UiText.Res(R.string.countdown_auto_resolve_soon), countdownLabel(deadlineAt = t0 - 5_000, now = t0))
    }
}
