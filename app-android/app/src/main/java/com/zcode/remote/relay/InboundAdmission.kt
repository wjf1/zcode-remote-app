package com.zcode.remote.relay

/**
 * 入站队列准入与深度记账（§11.3.3「入站队列容量和过载策略」的可测部分）。
 *
 * `RelayClient` 的 `kotlinx.coroutines.channels.Channel` 只负责投递（有界容量 +
 * `trySend` 非阻塞），本类负责**记账**：当前深度、峰值深度、准入/消费/丢弃计数。
 * 把策略与 Channel 解耦后，过载路径可被 JVM 单测与压测直接驱动、断言有界性
 * （Channel 自身在纯 JVM 里无法实例化断言，且此前队列容量是 `UNLIMITED`——即无限缓存）。
 *
 * 线程模型：`onAdmitted`（OkHttp 读线程）与 `onConsumed`（单泵协程）可能并发，
 * 计数用 `@Volatile` + 单点自增（丢帧只在计数量级上可接受轻微竞态，不用于调度决策）。
 */
internal class InboundAdmission(
    val capacity: Int = ProtocolLimits.INBOUND_QUEUE_CAPACITY,
) {
    @Volatile
    var depth: Int = 0
        private set

    /** 峰值深度（压测断言：永远 ≤ [capacity]）。 */
    @Volatile
    var peakDepth: Int = 0
        private set

    @Volatile
    var admitted: Int = 0
        private set

    @Volatile
    var dropped: Int = 0
        private set

    /** 已入队一帧（trySend 成功）。 */
    fun onAdmitted() {
        admitted += 1
        val next = depth + 1
        depth = next
        if (next > peakDepth) peakDepth = next
    }

    /** 单泵消费一帧。 */
    fun onConsumed() {
        if (depth > 0) depth -= 1
    }

    /** 队列已满、帧被丢弃（未 ack 的帧服务端可重放；调用方须记录并触发重同步）。 */
    fun onDropped() {
        dropped += 1
    }

    val isFull: Boolean get() = depth >= capacity

    override fun toString(): String = "admission(depth=$depth peak=$peakDepth admitted=$admitted dropped=$dropped cap=$capacity)"
}
