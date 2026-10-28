package com.zcode.remote.relay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T11：心跳「单向失活」判定（纯函数 [RelayClient.isHeartbeatStale]）。
 *
 * 场景：WS 仍连着、`send` 不报错，但对端再也不回 `pair_status_ack`（中间盒静默丢包，
 * 或对端进程已死而 socket 未及时 FIN）。此时若不检测，`_state` 会永远停在 `Paired`，
 * 用户看到「已连接」却什么都收不到，也不会自动重连。
 *
 * 判据刻意保守：只在「**发过**心跳且超过阈值仍无 ack」时命中，刚连上（未发过）不判。
 */
class HeartbeatStaleTest {
    private val client =
        RelayClient(
            device =
                com.zcode.remote.storage.PairedDevice(
                    deviceSid = "d_test",
                    passHash = null,
                    deviceMid = "m_test",
                    deviceName = "test",
                    remoteUrl = "wss://zcode.z.ai/ws",
                ),
        )

    private val stale = RelayClient.HEARTBEAT_STALE_MS

    @Test
    fun noHeartbeatSent_isNeverStale() {
        assertFalse(
            "从未发出心跳（sentAt=0）不得判失活——刚连上就该是安全的",
            client.isHeartbeatStale(sentAtMs = 0L, nowMs = 1_000_000_000L),
        )
    }

    @Test
    fun withinThreshold_isNotStale() {
        val sent = 1_000_000L
        assertFalse(
            "阈值内（3 个心跳周期未满）仍有 ack 到达的可能，不判",
            client.isHeartbeatStale(sentAtMs = sent, nowMs = sent + stale - 1),
        )
    }

    @Test
    fun exactlyAtThreshold_isStale() {
        val sent = 1_000_000L
        assertTrue(
            "达到阈值即判失活（>= 而非 >，边界须可预期）",
            client.isHeartbeatStale(sentAtMs = sent, nowMs = sent + stale),
        )
    }

    @Test
    fun wellBeyondThreshold_isStale() {
        val sent = 1_000_000L
        assertTrue(
            "远超阈值必然失活",
            client.isHeartbeatStale(sentAtMs = sent, nowMs = sent + stale * 10),
        )
    }

    @Test
    fun customThreshold_isRespected() {
        // 阈值可注入（便于真机调参/回归），5000ms 时 6s 即判失活
        assertTrue(client.isHeartbeatStale(sentAtMs = 1_000L, nowMs = 7_000L, staleMs = 5_000L))
        assertFalse(client.isHeartbeatStale(sentAtMs = 1_000L, nowMs = 3_000L, staleMs = 5_000L))
    }

    @Test
    fun defaultThresholdIsThirtySeconds() {
        // 与 PROTOCOL.md §4.1 的服务端 ack 超时口径一致：不抢在服务端判定之前误判
        org.junit.Assert.assertEquals(30_000L, RelayClient.HEARTBEAT_STALE_MS)
    }
}
