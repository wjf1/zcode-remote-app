package com.zcode.remote.relay

import com.zcode.remote.relay.V2AccountApi.RemoteDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 旧 SID 自动接续落点选择（官方 2026-10-27 行为，relay-v2-pairing.md §「旧 SID 自动接续」）：
 * 旧 SID 在线→沿用；同 MID 恰好一个其他在线 SID→切换；0 个/≥2 个→无落点。
 * 规则与官方 resolveAutoConnectSid 逐条对齐，纯函数 JVM 钉死。
 */
class SidContinuationTest {
    private val mid = "m_pc01"

    private fun device(
        sid: String,
        mid: String = this.mid,
        online: Boolean,
    ): RemoteDevice = RemoteDevice(deviceSid = sid, deviceMid = mid, online = online)

    @Test
    fun switch_whenExactlyOneOtherOnlineSid() {
        val outcome =
            SidContinuation.resolve(
                "d_old",
                mid,
                listOf(device("d_old", online = false), device("d_new", online = true)),
            )
        assertEquals("同 MID 恰好一个其他在线 SID → 切换", SidContinuation.Outcome.Switch("d_new"), outcome)
    }

    @Test
    fun keepCurrent_whenOldSidStillOnline() {
        val outcome =
            SidContinuation.resolve(
                "d_old",
                mid,
                listOf(device("d_old", online = true), device("d_new", online = true)),
            )
        assertEquals("旧 SID 仍在线 → 沿用旧 SID（官方语义，不抢新 SID）", SidContinuation.Outcome.KeepCurrent("d_old"), outcome)
    }

    @Test
    fun none_whenNoOnlineSid() {
        val outcome =
            SidContinuation.resolve(
                "d_old",
                mid,
                listOf(device("d_old", online = false), device("d_new", online = false)),
            )
        assertEquals("0 个在线 SID → 无落点，维持原终态", SidContinuation.Outcome.None, outcome)
    }

    @Test
    fun none_whenTwoOrMoreOtherOnlineSids() {
        val outcome =
            SidContinuation.resolve(
                "d_old",
                mid,
                listOf(device("d_new1", online = true), device("d_new2", online = true)),
            )
        assertEquals("≥2 个其他在线 SID → 落点歧义，不猜", SidContinuation.Outcome.None, outcome)
    }

    @Test
    fun filtersByMid_andIgnoresOffline() {
        val outcome =
            SidContinuation.resolve(
                "d_old",
                mid,
                listOf(
                    device("d_other_mid", mid = "m_pc02", online = true), // 异 MID：忽略
                    device("d_offline", online = false), // 同 MID 但离线：忽略
                    device("d_new", online = true),
                ),
            )
        assertEquals("只看同 MID 且在线的候选", SidContinuation.Outcome.Switch("d_new"), outcome)
    }

    @Test
    fun none_whenNoMidMatchAtAll() {
        val outcome =
            SidContinuation.resolve(
                "d_old",
                "m_unknown",
                listOf(device("d_new", online = true)),
            )
        assertEquals("MID 无任何匹配 → 无落点", SidContinuation.Outcome.None, outcome)
    }

    @Test
    fun offlineGrace_windowMatchesOfficial15s() {
        assertTrue(
            "DEVICE_OFFLINE 宽限须对齐官方 desktopOfflineGraceMs ?? 15_000",
            RelayClient.DEVICE_OFFLINE_GRACE_MS == 15_000L,
        )
    }

    @Test
    fun desktopDisconnected_isTerminalFailure_andParticipatesInContinuation() {
        assertTrue(
            "DESKTOP_DISCONNECTED 必须进终态集合：否则宽限期调度的退避重连会穿透终态，" +
                "形成 15s 周期「超时→重连→再超时」循环",
            FailureReason.DESKTOP_DISCONNECTED in RelayClient.TERMINAL_FAILURES,
        )
        assertTrue(
            "AUTH_FAILED（官方 device-not-owned）必须进终态集合",
            FailureReason.AUTH_FAILED in RelayClient.TERMINAL_FAILURES,
        )
        assertTrue(
            "KICKED（官方 session-conflict）是终态但**不参与**接续——由编排层 reason 判据保证",
            FailureReason.KICKED in RelayClient.TERMINAL_FAILURES,
        )
    }
}
