package com.zcode.remote.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2⑤ 终态失败「下一步」引导（对齐官方失败页交互：每 reason 恰 2 条定制建议；
 * 非终态 DEVICE_OFFLINE / NETWORK 不渲染——自动重连进行中）。
 */
class FailureGuidanceTest {
    @Test
    fun everyTerminalReason_hasExactlyTwoSteps() {
        for (reason in RelayClient.TERMINAL_FAILURES) {
            assertEquals("终态 $reason 应恰有 2 条下一步引导", 2, FailureGuidance.steps(reason).size)
        }
    }

    @Test
    fun nonTerminalReasons_renderNothing() {
        assertTrue(FailureGuidance.steps(FailureReason.DEVICE_OFFLINE).isEmpty())
        assertTrue(FailureGuidance.steps(FailureReason.NETWORK).isEmpty())
    }

    @Test
    fun allFailureReasons_areCovered() {
        // FailureReason 枚举全集必须被 steps() 显式处理（新增枚举值时这里会编译失败提醒补引导）
        val all = FailureReason.entries.toSet()
        assertEquals(
            "steps() 必须覆盖全部 FailureReason",
            all,
            setOf(
                FailureReason.KICKED,
                FailureReason.AUTH_FAILED,
                FailureReason.PROTOCOL_MISMATCH,
                FailureReason.INVALID_ENDPOINT,
                FailureReason.DESKTOP_DISCONNECTED,
                FailureReason.INTERNAL,
                FailureReason.DEVICE_OFFLINE,
                FailureReason.NETWORK,
            ),
        )
    }
}
