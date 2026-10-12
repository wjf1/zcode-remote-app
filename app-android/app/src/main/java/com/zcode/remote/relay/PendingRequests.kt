package com.zcode.remote.relay

import java.util.concurrent.ConcurrentHashMap

/**
 * 挂起请求位点表（§11.5.2）：key = **（桥代次, requestId）**。
 *
 * 历史缺陷：key 只有 requestId，而 `reset()` 会把 id 计数归零——旧连接/旧桥上迟到的应答
 * 可以「恰好」完成新桥上的同 id 请求（错单）。引入桥代次后，迟到应答在 key 上就不匹配，
 * 结构上不可能串单；`bridge-ready` / `reset()` 时代次递增使旧代次整批作废。
 *
 * 键打包为 Long：高 32 位代次、低 32 位 id（id 生成侧保持非负）。
 */
internal class PendingRequests<T> {
    private val map = ConcurrentHashMap<Long, T>()

    val size: Int get() = map.size

    fun register(
        epoch: Int,
        id: Int,
        value: T,
    ) {
        map[key(epoch, id)] = value
    }

    /** 命中即移除。代次不匹配（迟到应答）→ null，绝不完成新代次的同 id 请求。 */
    fun complete(
        epoch: Int,
        id: Int,
    ): T? = map.remove(key(epoch, id))

    /** 清空并返回残余（requestId → 值），供调用方逐个以错误收场。 */
    fun drainAll(): List<Pair<Int, T>> =
        map.entries
            .map { (k, v) -> (k and 0xffffffffL).toInt() to v }
            .also { map.clear() }

    companion object {
        internal fun key(
            epoch: Int,
            id: Int,
        ): Long = (epoch.toLong() shl 32) or (id.toLong() and 0xffffffffL)
    }
}

/**
 * 重同步节流（纯逻辑，单测覆盖）：协议违例/丢帧/未知逻辑帧触发重订阅的**最小间隔**。
 *
 * 为什么需要：每次重订阅都会复位 A-2 的握手重订预算（`resetHandshakeRetry`），
 * 若违例（如持续 CRC 错帧）高频出现，不节流会把「帧错误」放大成「订阅风暴」。
 */
internal class ResyncThrottle(
    private val minIntervalMs: Long = ProtocolLimits.RESYNC_MIN_INTERVAL_MS,
) {
    private var lastTriggeredAtMs: Long? = null

    /** 因节流被抑制的次数（观测用，只记计数不记 payload）。 */
    var suppressed: Int = 0
        private set

    fun shouldTrigger(nowMs: Long): Boolean {
        val last = lastTriggeredAtMs
        if (last != null && nowMs >= last && nowMs - last < minIntervalMs) {
            suppressed += 1
            return false
        }
        lastTriggeredAtMs = nowMs
        return true
    }

    fun reset() {
        lastTriggeredAtMs = null
        suppressed = 0
    }
}
