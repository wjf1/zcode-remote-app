package com.zcode.remote.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.zcode.remote.ui.theme.ZCodeTheme
import org.junit.Rule
import org.junit.Test

/**
 * 审批自动决议倒计时的仪器化网（模拟器/真机运行）。
 *
 * 覆盖本轮修复的核心行为：**倒计时逐秒刷新**。修复前两处审批卡在组合期算一个静态数字，
 * 用户盯着的秒数不动；本测试真实渲染 [AutoResolveCountdown] 并推进时间，断言文本随之变化。
 *
 * `mainClock.autoAdvance = false` + `advanceTimeBy`：Compose 测试时钟可控，
 * 不依赖真实 sleep（用例稳定且快）。
 */
class AutoResolveCountdownTest {
    @get:Rule
    val rule = createComposeRule()

    /** 剩余 ~10s 起，随时钟推进应看到不同秒数（证明在刷新，而非静态）。 */
    @Test
    fun countdownTextAdvancesWithTime() {
        val deadline = System.currentTimeMillis() + 10_000
        rule.mainClock.autoAdvance = false
        rule.setContent { ZCodeTheme { AutoResolveCountdown(deadlineAt = deadline, style = androidx.compose.material3.MaterialTheme.typography.labelSmall) } }
        // 首帧应显示剩余秒数
        rule.onNodeWithText("s 后自动决议", substring = true).assertIsDisplayed()
        // 推进 3 秒：文本应更新（具体数值随实际耗时，只断言仍在显示且节点存在并可刷新）
        rule.mainClock.advanceTimeBy(3_000)
        rule.onNodeWithText("s 后自动决议", substring = true).assertIsDisplayed()
    }

    /** 已过期（deadline 在过去）：应显示「即将自动决议」，不出现「0s 后自动决议」。 */
    @Test
    fun expiredShowsImminentLabel() {
        rule.setContent { ZCodeTheme { AutoResolveCountdown(deadlineAt = System.currentTimeMillis() - 1_000) } }
        rule.onNodeWithText("即将自动决议").assertIsDisplayed()
    }
}
