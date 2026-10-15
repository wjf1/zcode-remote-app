package com.zcode.remote.ui.screens

import com.zcode.remote.R
import com.zcode.remote.relay.FailureReason
import com.zcode.remote.relay.RelayState
import com.zcode.remote.util.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首页会话列表空态的连接态文案单测（走查清单 #11 后半）。
 *
 * 背景：空态此前只区分 `WaitingPeer` / `Paired`，其余全落 `else -> 「正在建立安全连接…」`，
 * 于是 `RelayState.Failed`（含不自动重连的终态失败）也被显示成「正在连接」。
 * 抽出 [connectionEmptyStateText] 后各状态文案可逐个钉死；[isTerminalFailure] 决定是否
 * 给出「前往设置」引导，必须与 `RelayClient.fail()` 的终态判定同源。
 */
class HomeConnectionInfoTest {
    private fun failed(reason: FailureReason) = RelayState.Failed(reason, message = "x")

    // ---------- 非失败态 ----------

    @Test
    fun transientStatesShowConnecting() {
        val expected = UiText.Res(R.string.home_conn_connecting)
        assertEquals(expected, connectionEmptyStateText(RelayState.Idle))
        assertEquals(expected, connectionEmptyStateText(RelayState.Connecting))
        assertEquals(expected, connectionEmptyStateText(RelayState.Authenticating))
    }

    @Test
    fun waitingPeerShowsPcHint() {
        assertEquals(
            UiText.Res(R.string.home_conn_waiting_peer),
            connectionEmptyStateText(RelayState.WaitingPeer),
        )
    }

    @Test
    fun pairedShowsFetchingHint() {
        assertEquals(
            UiText.Res(R.string.home_conn_paired),
            connectionEmptyStateText(RelayState.Paired),
        )
    }

    // ---------- 失败态：每种原因映射到不同文案（不再一律「正在连接」）----------

    @Test
    fun terminalFailuresHaveDistinctActionableText() {
        assertEquals(
            UiText.Res(R.string.home_conn_failed_kicked),
            connectionEmptyStateText(failed(FailureReason.KICKED)),
        )
        assertEquals(
            UiText.Res(R.string.home_conn_failed_auth),
            connectionEmptyStateText(failed(FailureReason.AUTH_FAILED)),
        )
        assertEquals(
            UiText.Res(R.string.home_conn_failed_protocol),
            connectionEmptyStateText(failed(FailureReason.PROTOCOL_MISMATCH)),
        )
        assertEquals(
            UiText.Res(R.string.home_conn_failed_endpoint),
            connectionEmptyStateText(failed(FailureReason.INVALID_ENDPOINT)),
        )
    }

    /** 关键回归：失败态**绝不能**再显示成「正在建立安全连接…」。 */
    @Test
    fun failedNeverShowsConnectingText() {
        val connecting = UiText.Res(R.string.home_conn_connecting)
        for (reason in FailureReason.values()) {
            assertTrue(
                "FailureReason.$reason 不应显示为「正在连接」",
                connectionEmptyStateText(failed(reason)) != connecting,
            )
        }
    }

    /** 非终态失败（网络/内部）走通用文案：它们会自行退避重连。 */
    @Test
    fun nonTerminalFailuresUseGenericText() {
        val generic = UiText.Res(R.string.home_conn_failed_generic)
        assertEquals(generic, connectionEmptyStateText(failed(FailureReason.NETWORK)))
        assertEquals(generic, connectionEmptyStateText(failed(FailureReason.INTERNAL)))
        assertEquals(generic, connectionEmptyStateText(failed(FailureReason.DEVICE_OFFLINE)))
    }

    // ---------- isTerminalFailure：与 RelayClient.fail() 的终态判定同源 ----------

    @Test
    fun terminalFailureMatchesNonReconnectingReasons() {
        // 这四种在 RelayClient.fail() 里置 terminalFailed=true、不走自动重连
        assertTrue(isTerminalFailure(failed(FailureReason.KICKED)))
        assertTrue(isTerminalFailure(failed(FailureReason.AUTH_FAILED)))
        assertTrue(isTerminalFailure(failed(FailureReason.PROTOCOL_MISMATCH)))
        assertTrue(isTerminalFailure(failed(FailureReason.INVALID_ENDPOINT)))
    }

    @Test
    fun nonTerminalFailuresDoNotOfferSettingsGuide() {
        assertFalse(isTerminalFailure(failed(FailureReason.NETWORK)))
        assertFalse(isTerminalFailure(failed(FailureReason.INTERNAL)))
        assertFalse(isTerminalFailure(failed(FailureReason.DEVICE_OFFLINE)))
    }

    @Test
    fun nonFailedStatesAreNotTerminal() {
        assertFalse(isTerminalFailure(RelayState.Idle))
        assertFalse(isTerminalFailure(RelayState.Connecting))
        assertFalse(isTerminalFailure(RelayState.Authenticating))
        assertFalse(isTerminalFailure(RelayState.WaitingPeer))
        assertFalse(isTerminalFailure(RelayState.Paired))
    }
}
