package com.zcode.remote.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Random

/**
 * 协议资源预算**持续压力测试**（任务书 §11.3.3 验收：「30 分钟压力测试内存稳定，
 * 队列和重组缓存有明确上限」）。
 *
 * 默认跳过（CI 不跑；30 分钟量级不适合常规门禁）。本地取证运行：
 * ```
 * cd app-android && ZCODE_STRESS_SECONDS=1800 JAVA_HOME=... gradle.bat :app:testDebugUnitTest \
 *   --tests '*ProtocolStressTest*' --console=plain
 * ```
 * 断言口径（结构性、可复现，不依赖 GC 时序）：① 全程无异常逃逸；② 在途分片字节/条数**任何时刻**
 * 不超预算；③ 入站准入深度不超容量；④ 准入 + 丢弃 = 输入帧数、压力流真实推进并覆盖拒绝路径。
 * 另打印起止堆占用与 GC 后驻留量作为观测证据（驻留量是「无泄漏」的主证据）。
 *
 * 实跑证据（最终版本，2026-10-12，30m14s，failures=0）：64,749,568 帧（约 3.59 万帧/秒）；
 * 在途字节峰值 65,555 B（预算 16 MiB 的 0.39%）、在途条数峰值 1（预算 4）；准入峰值 = 容量 1024
 * （准入 4,533 万 / 丢弃 1,942 万）；堆 起点 62 MiB → 裸堆 148 MiB → GC 后驻留 16 MiB。
 */
class ProtocolStressTest {
    private fun stressSeconds(): Int =
        System.getenv("ZCODE_STRESS_SECONDS")?.toIntOrNull()?.takeIf { it > 0 } ?: 0

    /**
     * 混合流量流：正常多分片为主，按比例掺入篡改片 / 重复片 / 跳号片，并周期性触发超时淘汰。
     * 状态机独立成类，使测试主体保持无分支（detekt 复杂度门禁）。
     */
    private class TrafficStream(private val rnd: Random) {
        private val assembler = RpcFrameAssembler()
        private val admission = InboundAdmission()
        private val counts = mutableMapOf<String, Long>()
        private var messageSeq = 0
        private var parts = emptyList<ByteArray>()
        private var fragmentIndex = 0
        private var whole = ByteArray(0)
        private var crc = ""

        var frames = 0L
            private set
        var completes = 0L
            private set
        var rejected = 0L
            private set
        var now = 0L
            private set
        var peakInFlightBytes = 0
            private set
        var peakPending = 0
            private set

        /** 入站准入合计（与 frames 对账：每次准入尝试必落 admitted 或 dropped）。 */
        val admitted: Long get() = admission.admitted.toLong()
        val dropped: Long get() = admission.dropped.toLong()

        val codeSummary: Map<String, Long> get() = counts.toMap()
        val admissionSummary: String get() = admission.toString()

        init {
            newMessage()
        }

        /** 一条随机大小的新消息（32 B ~ 64 KiB，按 512~2560 B 等长切分）。 */
        fun newMessage() {
            messageSeq += 1
            val size = 32 + rnd.nextInt(64 * 1024)
            whole = ByteArray(size).also { rnd.nextBytes(it) }
            crc = crc32Hex(whole)
            val per = 512 + rnd.nextInt(2_048)
            parts = (0 until size step per).map { whole.copyOfRange(it, minOf(it + per, size)) }
            fragmentIndex = 0
        }

        /** 入站准入 + 消费（单泵滞后时形成积压，满员即丢并记账）。 */
        private fun admit() {
            if (admission.depth >= admission.capacity) {
                admission.onDropped()
            } else {
                admission.onAdmitted()
            }
            if (rnd.nextInt(100) < 70) admission.onConsumed()
        }

        /** 按比例取一片（篡改/重复/跳号/正常），返回本帧输入。 */
        private fun pickSlice(): Pair<ByteArray, RpcFrameMetadata> {
            val roll = rnd.nextInt(100)
            val index =
                when {
                    roll < 5 -> fragmentIndex // 篡改片：就地拷贝后翻转首字节（勿污染共享分片）
                    roll < 10 -> maxOf(0, fragmentIndex - 1) // 重复片（同指纹重传）
                    roll < 13 -> minOf(fragmentIndex + 1, parts.size - 1) // 跳号（缺口）
                    else -> fragmentIndex
                }
            val slice = parts[index].copyOf()
            if (roll < 5) slice[0] = (slice[0] + 1).toByte()
            return slice to
                RpcFrameMetadata(
                    seq = index + 1,
                    messageSeq = messageSeq,
                    fragmentIndex = index,
                    fragmentCount = parts.size,
                    messageBytes = whole.size,
                    checksumAlgorithm = "crc32",
                    checksumValue = crc,
                )
        }

        /** 处理一帧：驱动组装器并推进状态机。 */
        fun tick() {
            frames++
            now += 1
            admit()
            val (slice, metadata) = pickSlice()
            when (val outcome = assembler.accept(slice, metadata, now)) {
                is RpcFrameAssembler.Outcome.Buffered ->
                    fragmentIndex = (fragmentIndex + 1).coerceAtMost(parts.size - 1)
                is RpcFrameAssembler.Outcome.Complete -> {
                    completes++
                    check(outcome.bytes.size == whole.size) { "完成字节数与声明不符" }
                    newMessage()
                }
                is RpcFrameAssembler.Outcome.Rejected -> {
                    rejected++
                    counts[outcome.violation.code] = (counts[outcome.violation.code] ?: 0) + 1
                    newMessage()
                }
            }
            if (assembler.inFlightBytes > peakInFlightBytes) peakInFlightBytes = assembler.inFlightBytes
            if (assembler.pendingCount > peakPending) peakPending = assembler.pendingCount
            checkAssemblerBounds(assembler)
            checkAdmissionBound(admission)
        }

        /** 组装器预算不变量（热路径，失败才构造消息串）。 */
        private fun checkAssemblerBounds(asm: RpcFrameAssembler) {
            if (asm.inFlightBytes > ProtocolLimits.MAX_ASSEMBLY_IN_FLIGHT_BYTES) {
                throw AssertionError("在途字节越界 ${asm.inFlightBytes}")
            }
            if (asm.pendingCount > ProtocolLimits.MAX_ASSEMBLY_IN_FLIGHT_MESSAGES) {
                throw AssertionError("在途条数越界 ${asm.pendingCount}")
            }
        }

        private fun checkAdmissionBound(a: InboundAdmission) {
            if (a.depth > a.capacity) throw AssertionError("入站深度越界 ${a.depth}")
        }

        /** 周期推进时钟触发超时淘汰（覆盖重组超时路径）。 */
        fun sweep() = assembler.sweepExpired(now + 60_000L)

        fun progressLine(): String =
            "[stress] frames=$frames completes=$completes rejected=$rejected " +
                "inFlight<=$peakInFlightBytes pending<=$peakPending $admissionSummary"
    }

    @Test
    @Suppress("ExplicitGarbageCollectionCall") // 刻意调用：量测「GC 后驻留」，是无泄漏的主证据
    fun sustainedMixedTrafficStaysBounded() {
        val seconds = stressSeconds()
        assumeTrue("未设置 ZCODE_STRESS_SECONDS，跳过 30 分钟压力测试", seconds > 0)
        val stream = TrafficStream(Random(20261013L))
        val deadline = System.nanoTime() + seconds.toLong() * 1_000_000_000L
        val heapBefore = usedHeap()
        var lastReport = System.currentTimeMillis()

        while (System.nanoTime() < deadline) {
            repeat(256) { stream.tick() }
            if (stream.now % 4L == 0L) stream.sweep()
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastReport >= 300_000) {
                lastReport = nowMs
                println(stream.progressLine())
            }
        }

        val heapAfter = usedHeap()
        System.gc()
        Thread.sleep(200)
        val heapRetained = usedHeap()
        println(
            "[stress] 结束：${seconds}s ${stream.progressLine()} codes=${stream.codeSummary} " +
                "heapBefore=${heapBefore / 1024 / 1024}MiB heapAfter=${heapAfter / 1024 / 1024}MiB " +
                "heapRetainedAfterGc=${heapRetained / 1024 / 1024}MiB",
        )
        assertEquals("准入 + 丢弃 应等于输入帧数", stream.frames, stream.admitted + stream.dropped)
        assertTrue("压力流未推进（frames=${stream.frames}）", stream.frames > 1_000)
        assertTrue("未覆盖到拒绝路径", stream.rejected > 0)
        assertTrue(
            "峰值在途字节越界 ${stream.peakInFlightBytes}",
            stream.peakInFlightBytes <= ProtocolLimits.MAX_ASSEMBLY_IN_FLIGHT_BYTES,
        )
        assertTrue("峰值在途条数越界 ${stream.peakPending}", stream.peakPending <= ProtocolLimits.MAX_ASSEMBLY_IN_FLIGHT_MESSAGES)
        // 内存稳定性：GC 后驻留不应显著高于起点（每帧分配均为短命对象）
        assertTrue(
            "GC 后驻留内存异常增长：${heapRetained / 1024 / 1024}MiB（起点 ${heapBefore / 1024 / 1024}MiB）",
            heapRetained <= heapBefore + 192L * 1024 * 1024,
        )
    }

    private fun usedHeap(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
}
