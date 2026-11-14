package com.zcode.remote.relay

/**
 * V2 重连策略（纯逻辑类，JVM 单测直驱；2026-11-14 对齐官方 `web-remote-relay/domain/retry.ts`
 * 的 `WebRemoteControlV2RetryPolicy`，V1 链路沿用同一套——官方 V1 已删、无从单独对标，
 * 同一中继统一行为优先）。
 *
 * 官方语义：
 * - **打散窗口**：连接建立（matched）后的**第一次**重连在 `0 ~ [SPREAD_MS]` 全窗随机——
 *   relay 重启时所有设备同时断线，原退避第 1 次仅 ±20%（手机 0.4~0.6s），一次性 token
 *   请求会挤在同一时刻；只打散这一次，失败后回原曲线，避免 relay 短暂不可用后恢复时间被拉长。
 *   窗口必须小于桌面 3s 手机断连宽限，否则短断线会被当成确认断连（关闭手机终端 owner、
 *   bridge 失效，官方 retry.ts Bugfix 注释原文）。
 * - **退避**：500ms × 2ⁿ ±20% 双向抖动、10s 封顶（官方手机端口径；桌面 1s/30s）。
 * - **KICKED**：首次 2~5s 随机重连——官方已知限制：KICKED 无法区分「同角色替换」与
 *   「relay owner 丢失」，后者是可自愈误报；60s 内第二次 = 真互踢，[kickedDelay] 返回 null
 *   由调用方进终态（防 3s 级互踢死循环，2026-09-30 真机教训的官方解法）。
 * - **auth_ack/matched 只清退避、不清 KICKED 窗口**：[reset]/[acknowledged] 均不动
 *   [lastKickAtMs]（官方 retry.ts「每个 transport 实例独占，auth_ack 只清退避，不清
 *   KICKED 窗口」注释原文）。
 */
internal class V2RetryPolicy(
    private val random: () -> Double = Math::random,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private var failures = 0
    private var spreadNext = false
    private var lastKickAtMs = 0L

    /** 用户显式连接（扫码 / 手动重连）：清退避，不打散。**不清 KICKED 窗口**。 */
    fun reset() {
        failures = 0
        spreadNext = false
    }

    /**
     * 连接建立（pair_status=matched）：清退避；下一次重连可能与同 relay 上的其他设备同时
     * 发生（relay 重启场景），故下一次进入打散窗口。
     */
    fun acknowledged() {
        failures = 0
        spreadNext = true
    }

    /** 下一次自动重连延迟：若上一条连接成功建立过，先打散一次（只一次），否则走官方退避曲线。 */
    fun nextDelay(): Long {
        if (spreadNext) {
            spreadNext = false
            return (random() * SPREAD_MS).toLong()
        }
        val base = minOf(MAX_BACKOFF_MS, INITIAL_MS * (1L shl minOf(failures, 16)))
        failures++
        // ±20% 双向抖动（官方 `base * (1 - jitter + random * jitter * 2)`，round 后封顶）
        val jittered = base * (1.0 - JITTER + random() * JITTER * 2)
        return minOf(MAX_BACKOFF_MS, jittered.toLong())
    }

    /**
     * KICKED 重连延迟：60s 内第二次 → null（调用方进 [FailureReason.KICKED] 终态）；
     * 否则记录本次时刻并返回 `[KICKED_MIN_MS, KICKED_MAX_MS]` 随机延迟（官方
     * `2000 + random * 3001`）。
     */
    fun kickedDelay(): Long? {
        val now = nowMs()
        if (lastKickAtMs != 0L && now - lastKickAtMs < KICKED_WINDOW_MS) return null
        lastKickAtMs = now
        return KICKED_MIN_MS + (random() * (KICKED_MAX_MS - KICKED_MIN_MS + 1)).toLong()
    }

    companion object {
        /**
         * 打散窗口：两端共用（官方 `WEB_REMOTE_CONTROL_V2_RECONNECT_SPREAD_MS`）；
         * 必须 < 桌面 3s 手机断连宽限（`MOBILE_DISCONNECT_STATUS_GRACE_MS`）。
         */
        const val SPREAD_MS = 1_500L

        /** 退避基数 500ms（官方手机端口径）。 */
        const val INITIAL_MS = 500L

        /** 退避封顶 10s（官方手机端口径；桌面为 30s）。 */
        const val MAX_BACKOFF_MS = 10_000L

        /** 退避抖动幅度 ±20%（双向，官方 jitter=0.2）。 */
        const val JITTER = 0.2

        /** KICKED 自愈窗口：窗口内第二次 KICKED = 真互踢（官方 60s）。 */
        const val KICKED_WINDOW_MS = 60_000L

        /** KICKED 重连延迟下界 / 上界（官方 `2000 + random * 3001` → [2000, 5000]）。 */
        const val KICKED_MIN_MS = 2_000L
        const val KICKED_MAX_MS = 5_000L
    }
}
