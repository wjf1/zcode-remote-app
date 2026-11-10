package com.zcode.remote.relay

import com.zcode.remote.relay.V2AccountApi.RemoteDevice

/**
 * 旧 SID 自动接续的落点选择（官方 2026-10-27 行为，官方仓库 `relay-v2-pairing.md` §「旧 SID 自动接续」）。
 *
 * 背景：桌面端每次重注册（重启/重登）都会换新 `device_sid`（MID 不变）。此前旧 SID 失效后
 * App 只能「无限同 SID 重试」或「终态退回手动重扫码」；官方手机端改为失败后按 MID 查一次
 * 设备列表、自动接续到同 MID 的新在线 SID。
 *
 * 规则（与官方 `resolveAutoConnectSid` 一致，纯函数便于 JVM 单测）：
 * - 可接续失败 = auth_init `AUTH_FAILED`（官方 `device-not-owned`）或 DEVICE_OFFLINE 宽限超时
 *   （官方 `desktop-disconnected`）；
 * - 旧 SID 仍在线 → 沿用旧 SID 重建连；
 * - 否则同 MID 的在线 SID **恰好一个**（且 ≠ 旧 SID）→ 切换到新 SID；
 * - 其余（0 个 / ≥2 个 / MID 缺失）→ 无落点，维持原终态交回用户；
 * - 接续出的连接在配对成功前再失败**不再**二次接续（有界，由编排层 `sidContinuationUsed` 保证）；
 * - 无定时器/轮询；KICKED（官方 `session-conflict`）等其它失败保持原行为。
 */
object SidContinuation {
    sealed interface Outcome {
        /** 旧 SID 仍在线：沿用原 SID 重新建连。 */
        data class KeepCurrent(
            val sid: String,
        ) : Outcome

        /** 同 MID 恰好一个其他在线 SID：切换到新 SID 重建连。 */
        data class Switch(
            val sid: String,
        ) : Outcome

        /** 无落点：维持原终态（常驻通知 + 首页横幅），交回用户处理。 */
        data object None : Outcome
    }

    fun resolve(
        failedSid: String,
        deviceMid: String,
        devices: List<RemoteDevice>,
    ): Outcome {
        val online = devices.filter { it.deviceMid == deviceMid && it.online }
        if (online.any { it.deviceSid == failedSid }) return Outcome.KeepCurrent(failedSid)
        val others = online.filter { it.deviceSid != failedSid }
        return if (others.size == 1) Outcome.Switch(others.first().deviceSid) else Outcome.None
    }
}
