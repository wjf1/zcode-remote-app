package com.zcode.remote.relay

import com.zcode.remote.R
import com.zcode.remote.util.UiText
import com.zcode.remote.util.UiText.Companion.res

/**
 * 终态失败的结构化「下一步」引导（P2⑤，2026-11-14 对齐官方失败页交互——
 * 官方 `WebRemoteControlFailureScreen` 的 11 种 reason 各配 2 条定制「下一步」建议；
 * App 侧按自有 [FailureReason] 体系映射，每 reason 恰 2 条、随终态横幅/设置页渲染）。
 *
 * 纯函数 + 资源 id（i18n 资源化纪律同 [UserFacingError]）；`UserFacingErrorTest` 同款
 * JVM 单测钉住「每 reason 恰 2 条且非空」。
 */
object FailureGuidance {
    /**
     * 每个失败原因的 2 条「下一步」建议（顺序即展示顺序）。
     * DEVICE_OFFLINE / NETWORK 为非终态（自动重连进行中），不渲染引导——返回空表。
     */
    fun steps(reason: FailureReason): List<UiText> =
        when (reason) {
            FailureReason.KICKED ->
                listOf(
                    res(R.string.failure_next_kicked_1),
                    res(R.string.failure_next_kicked_2),
                )
            FailureReason.AUTH_FAILED ->
                listOf(
                    res(R.string.failure_next_auth_1),
                    res(R.string.failure_next_auth_2),
                )
            FailureReason.PROTOCOL_MISMATCH ->
                listOf(
                    res(R.string.failure_next_protocol_1),
                    res(R.string.failure_next_protocol_2),
                )
            FailureReason.INVALID_ENDPOINT ->
                listOf(
                    res(R.string.failure_next_endpoint_1),
                    res(R.string.failure_next_endpoint_2),
                )
            FailureReason.DESKTOP_DISCONNECTED ->
                listOf(
                    res(R.string.failure_next_desktop_offline_1),
                    res(R.string.failure_next_desktop_offline_2),
                )
            FailureReason.INTERNAL ->
                listOf(
                    res(R.string.failure_next_internal_1),
                    res(R.string.failure_next_internal_2),
                )
            FailureReason.DEVICE_OFFLINE, FailureReason.NETWORK -> emptyList()
        }
}
