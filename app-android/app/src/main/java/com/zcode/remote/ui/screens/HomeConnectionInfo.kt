package com.zcode.remote.ui.screens

import com.zcode.remote.R
import com.zcode.remote.relay.FailureReason
import com.zcode.remote.relay.RelayState
import com.zcode.remote.util.UiText
import com.zcode.remote.util.UiText.Companion.res

/**
 * 首页会话列表空态的连接态文案（走查清单 #11 后半）。
 *
 * 缺陷背景：列表为空时的占位文案只覆盖了 `WaitingPeer` / `Paired` 两种状态，
 * 其余全部落到 `else -> "正在建立安全连接…"`——于是 `RelayState.Failed` 也会显示「正在连接」。
 * 对 `KICKED` / `AUTH_FAILED` / `PROTOCOL_MISMATCH` / `INVALID_ENDPOINT` 这些**终态失败**
 * （`RelayClient.fail()` 里明确不自动重连，见其注释），界面会一直停在「正在建立安全连接…」，
 * 用户以为还在连、实则永远不会成功，也无从知道要去设置页重新配对。
 *
 * [connectionEmptyStateText] 为纯函数（无 Compose 依赖），JVM 单测可逐个状态钉死文案；
 * UI 侧仅在 `sessions.isEmpty()` 时调用它。
 */
internal fun connectionEmptyStateText(state: RelayState): UiText =
    when (state) {
        RelayState.Idle, RelayState.Connecting, RelayState.Authenticating -> res(R.string.home_conn_connecting)
        RelayState.WaitingPeer -> res(R.string.home_conn_waiting_peer)
        RelayState.Paired -> res(R.string.home_conn_paired)
        is RelayState.Failed ->
            when (state.reason) {
                FailureReason.KICKED -> res(R.string.home_conn_failed_kicked)
                FailureReason.AUTH_FAILED -> res(R.string.home_conn_failed_auth)
                FailureReason.PROTOCOL_MISMATCH -> res(R.string.home_conn_failed_protocol)
                FailureReason.INVALID_ENDPOINT -> res(R.string.home_conn_failed_endpoint)
                // NETWORK / INTERNAL / DEVICE_OFFLINE：非终态，RelayClient 会退避后自动重连
                else -> res(R.string.home_conn_failed_generic)
            }
    }

/**
 * 该失败态是否是「需要用户去设置页介入」的终态。
 *
 * 与 `RelayClient.fail()` 的终态判定同源（那四处失败会置 `terminalFailed`、停止自动重连）：
 * 首页据此决定是否在空态下方给出「前往设置」的引导动作，避免用户对着一个永远不会自愈的
 * 「正在连接」干等。非终态失败（网络/内部错误）不给该引导——它们会自己恢复，给了反而误导。
 */
internal fun isTerminalFailure(state: RelayState): Boolean =
    state is RelayState.Failed &&
        (
            state.reason == FailureReason.KICKED ||
                state.reason == FailureReason.AUTH_FAILED ||
                state.reason == FailureReason.PROTOCOL_MISMATCH ||
                state.reason == FailureReason.INVALID_ENDPOINT
        )
