package com.zcode.remote.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V2 重连策略（P2①②，2026-11-14 对齐官方 `web-remote-relay/domain/retry.ts`）：
 * 打散窗口只作用于「连接建立后的首次重连」、退避 500ms×2ⁿ ±20% 封顶 10s、
 * KICKED 首次 2~5s 自愈 / 60s 内二次终态、reset/acknowledged 不清 KICKED 窗口。
 */
class V2RetryPolicyTest {
    private val half = { 0.5 }

    @Test
    fun spread_appliesOnceAfterAcknowledged_thenFallsBackToCurve() {
        val p = V2RetryPolicy(random = half)
        p.acknowledged()
        // 打散：0~1500 全窗随机（random=0.5 → 750）
        assertEquals(750L, p.nextDelay())
        // 第二次不再打散：走退避曲线（500ms 基数 × jitter 1.0）
        assertEquals(500L, p.nextDelay())
    }

    @Test
    fun reset_clearsSpread() {
        val p = V2RetryPolicy(random = half)
        p.acknowledged()
        p.reset() // 用户显式连接：不打散
        assertEquals(500L, p.nextDelay())
    }

    @Test
    fun backoff_curve_doublesThenCapsAt10s() {
        val p = V2RetryPolicy(random = half, nowMs = { 0L })
        // random=0.5 → jitter 系数 = 1 - 0.2 + 0.5*0.4 = 1.0 → 基数值原样
        assertEquals(500L, p.nextDelay())
        assertEquals(1_000L, p.nextDelay())
        assertEquals(2_000L, p.nextDelay())
        assertEquals(4_000L, p.nextDelay())
        assertEquals(8_000L, p.nextDelay())
        // 第 6 次起封顶 10s
        assertEquals(10_000L, p.nextDelay())
        assertEquals(10_000L, p.nextDelay())
    }

    @Test
    fun kicked_firstDelayInRange2to5s_secondWithin60sIsTerminal() {
        var now = 1_000_000L
        val p = V2RetryPolicy(random = half, nowMs = { now })
        val d1 = p.kickedDelay()
        assertNotNull(d1)
        assertTrue("首次 KICKED 延迟应在 [2000,5000]", d1!! in 2_000L..5_000L)
        // 60s 内第二次 → null（真互踢，调用方进终态）
        now += 10_000L
        assertNull("60s 内二次 KICKED 必须进终态", p.kickedDelay())
        // 窗口过后恢复自愈路径
        now += 61_000L
        assertNotNull(p.kickedDelay())
    }

    @Test
    fun reset_doesNotClearKickedWindow() {
        var now = 1_000_000L
        val p = V2RetryPolicy(random = half, nowMs = { now })
        assertNotNull(p.kickedDelay())
        now += 5_000L
        p.reset() // auth_ack/matched 只清退避——官方明确「不清 KICKED 窗口」
        assertNull("reset 不得清 KICKED 窗口", p.kickedDelay())
    }

    @Test
    fun spread_windowConstant_belowDesktopGrace() {
        // 官方约束：打散窗口必须小于桌面 3s 手机断连宽限，否则短断线会关掉手机终端 owner
        assertTrue(V2RetryPolicy.SPREAD_MS < 3_000L)
        assertEquals(1_500L, V2RetryPolicy.SPREAD_MS)
    }
}
