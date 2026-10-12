package com.zcode.remote.relay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §11.5.2 分片组装器单测（纯逻辑，无 Android 依赖）。
 *
 * 覆盖验收矩阵：单/多分片统一校验（声明长度 + 整条 CRC）、重复/缺失/CRC 错误分片、
 * 极大分片数/负数索引、在途容量与超时（超限可控拒绝，无 OOM、无无限缓存）。
 */
class RpcFrameAssemblerTest {
    private fun assembler(
        maxFragments: Int = ProtocolLimits.MAX_FRAGMENTS,
        maxMessageBytes: Int = ProtocolLimits.MAX_MESSAGE_BYTES,
        timeoutMs: Long = ProtocolLimits.ASSEMBLY_TIMEOUT_MS,
        maxInFlightMessages: Int = ProtocolLimits.MAX_ASSEMBLY_IN_FLIGHT_MESSAGES,
        maxInFlightBytes: Int = ProtocolLimits.MAX_ASSEMBLY_IN_FLIGHT_BYTES,
    ) = RpcFrameAssembler(maxFragments, maxMessageBytes, timeoutMs, maxInFlightMessages, maxInFlightBytes)

    /** 构造一片的元数据（seq 固定 1、algorithm 固定 crc32）；checksum 传**整条消息**的 CRC。 */
    private fun part(
        messageSeq: Int = 1,
        index: Int = 0,
        count: Int = 1,
        messageBytes: Int,
        checksum: String,
    ) = RpcFrameMetadata(1, messageSeq, index, count, messageBytes, "crc32", checksum)

    private fun complete(outcome: RpcFrameAssembler.Outcome): RpcFrameAssembler.Outcome.Complete {
        assertTrue("期望 Complete，实际 $outcome", outcome is RpcFrameAssembler.Outcome.Complete)
        return outcome as RpcFrameAssembler.Outcome.Complete
    }

    private fun rejected(outcome: RpcFrameAssembler.Outcome): ProtocolViolation {
        assertTrue("期望 Rejected，实际 $outcome", outcome is RpcFrameAssembler.Outcome.Rejected)
        return (outcome as RpcFrameAssembler.Outcome.Rejected).violation
    }

    private fun assertBuffered(outcome: RpcFrameAssembler.Outcome) {
        assertTrue("期望 Buffered，实际 $outcome", outcome is RpcFrameAssembler.Outcome.Buffered)
    }

    // ---------- 单分片：与多分片同一套校验 ----------

    @Test
    fun singleFragmentPassesWithMatchingChecksumAndLength() {
        val payload = "hello-single".toByteArray(Charsets.UTF_8)
        val out = assembler().accept(payload, part(messageBytes = payload.size, checksum = crc32Hex(payload)), 0)
        assertArrayEquals(payload, complete(out).bytes)
        assertEquals(1, complete(out).messageSeq)
    }

    @Test
    fun singleFragmentRejectsChecksumMismatch() {
        val payload = "hello-single".toByteArray(Charsets.UTF_8)
        val out = assembler().accept(payload, part(messageBytes = payload.size, checksum = "deadbeef"), 0)
        assertEquals(ProtocolViolation.CHECKSUM_MISMATCH, rejected(out))
    }

    @Test
    fun singleFragmentRejectsDeclaredLengthMismatch() {
        val payload = "hello-single".toByteArray(Charsets.UTF_8)
        val out = assembler().accept(payload, part(messageBytes = payload.size + 1, checksum = crc32Hex(payload)), 0)
        assertEquals(ProtocolViolation.LENGTH_MISMATCH, rejected(out))
    }

    @Test
    fun singleFragmentRejectsOversizeDeclaredLength() {
        val payload = ByteArray(16)
        // 声明 > 16 MiB：在组装前就被预算拒绝（不分配任何大缓冲）
        val oversize = ProtocolLimits.MAX_MESSAGE_BYTES + 1
        val out = assembler().accept(payload, part(messageBytes = oversize, checksum = crc32Hex(payload)), 0)
        assertEquals(ProtocolViolation.MESSAGE_BYTES, rejected(out))
    }

    // ---------- 多分片：顺序/CRC/长度/重复/缺片 ----------

    @Test
    fun multiFragmentAssemblesInOrder() {
        val whole = ByteArray(300) { (it % 251).toByte() }
        val crc = crc32Hex(whole)
        val asm = assembler()
        assertBuffered(asm.accept(whole.copyOfRange(0, 100), part(9, 0, 3, whole.size, crc), 0))
        assertBuffered(asm.accept(whole.copyOfRange(100, 220), part(9, 1, 3, whole.size, crc), 1))
        val done = complete(asm.accept(whole.copyOfRange(220, 300), part(9, 2, 3, whole.size, crc), 2))
        assertEquals(9, done.messageSeq)
        assertArrayEquals(whole, done.bytes)
    }

    @Test
    fun multiFragmentRejectsFragmentGap() {
        val crc = crc32Hex(ByteArray(200))
        val asm = assembler()
        // 首片缺失（index=1 先到）
        assertEquals(ProtocolViolation.FRAGMENT_GAP, rejected(asm.accept(ByteArray(50), part(3, 1, 4, 200, crc), 0)))
        // 顺序中跳号（0 之后直接 2）
        assertBuffered(asm.accept(ByteArray(50), part(3, 0, 4, 200, crc), 0))
        assertEquals(ProtocolViolation.FRAGMENT_GAP, rejected(asm.accept(ByteArray(50), part(3, 2, 4, 200, crc), 0)))
    }

    @Test
    fun duplicateFragmentWithSameFingerprintIsIdempotent() {
        val whole = ByteArray(120) { it.toByte() }
        val first = whole.copyOfRange(0, 60)
        val second = whole.copyOfRange(60, 120)
        val crc = crc32Hex(whole)
        val asm = assembler()
        assertBuffered(asm.accept(first, part(1, 0, 2, whole.size, crc), 0))
        // 同一分片重复到达（服务端重传）：幂等忽略，不改变状态
        assertBuffered(asm.accept(first, part(1, 0, 2, whole.size, crc), 1))
        assertArrayEquals(whole, complete(asm.accept(second, part(1, 1, 2, whole.size, crc), 2)).bytes)
    }

    @Test
    fun duplicateFragmentWithConflictingContentIsRejected() {
        val whole = ByteArray(120) { it.toByte() }
        val crc = crc32Hex(whole)
        val asm = assembler()
        assertBuffered(asm.accept(whole.copyOfRange(0, 60), part(1, 0, 2, whole.size, crc), 0))
        // 同一位点、不同内容（指纹冲突）：官方 conflictingDuplicate
        val conflicting = whole.copyOfRange(0, 60).also { it[0] = (it[0] + 1).toByte() }
        assertEquals(ProtocolViolation.DUPLICATE_CONFLICT, rejected(asm.accept(conflicting, part(1, 0, 2, whole.size, crc), 0)))
        // 冲突后该消息的在途缓冲被丢弃（不残留半条消息）
        assertEquals(0, asm.inFlightBytes)
    }

    @Test
    fun multiFragmentRejectsChecksumMismatchOnJoin() {
        val whole = ByteArray(100) { it.toByte() }
        val crc = crc32Hex(whole)
        val asm = assembler()
        assertBuffered(asm.accept(whole.copyOfRange(0, 50), part(1, 0, 2, whole.size, crc), 0))
        // 第二片内容被篡改（长度仍对齐声明）→ 拼装后整条 CRC 不符
        val tampered = whole.copyOfRange(50, 100).also { it[0] = (it[0] + 1).toByte() }
        assertEquals(ProtocolViolation.CHECKSUM_MISMATCH, rejected(asm.accept(tampered, part(1, 1, 2, whole.size, crc), 0)))
    }

    @Test
    fun multiFragmentRejectsMetadataDriftWithinMessage() {
        val whole = ByteArray(100)
        val crc = crc32Hex(whole)
        val asm = assembler()
        assertBuffered(asm.accept(ByteArray(50), part(1, 0, 2, whole.size, crc), 0))
        // 同一条消息中途 fragmentCount 变化 → metadataMismatch 语义
        assertEquals(ProtocolViolation.METADATA, rejected(asm.accept(ByteArray(50), part(1, 1, 3, whole.size, crc), 0)))

        // 中途 checksum 变化 → checksumMismatch 语义（新组装器，避免复用上一条已被拒的缓冲）
        val asm2 = assembler()
        assertBuffered(asm2.accept(ByteArray(50), part(1, 0, 2, whole.size, crc), 0))
        assertEquals(ProtocolViolation.CHECKSUM_MISMATCH, rejected(asm2.accept(ByteArray(50), part(1, 1, 2, whole.size, "00000000"), 0)))
    }

    @Test
    fun multiFragmentRejectsStagedOverflowAgainstDeclaredLength() {
        val crc = crc32Hex(ByteArray(100))
        val asm = assembler()
        assertBuffered(asm.accept(ByteArray(60), part(1, 0, 3, 100, crc), 0))
        // 已缓冲 60 + 本片 60 > 声明 100 → lengthMismatch（不无限增长）
        assertEquals(ProtocolViolation.LENGTH_MISMATCH, rejected(asm.accept(ByteArray(60), part(1, 1, 3, 100, crc), 0)))
    }

    // ---------- 上限与超时（§11.3.3 验收：阈值处可控拒绝、无 OOM、无无限缓存） ----------

    @Test
    fun fragmentCountOverLimitIsRejectedBeforeBuffering() {
        val asm = assembler(maxFragments = 4)
        assertEquals(ProtocolViolation.FRAGMENT_COUNT, rejected(asm.accept(ByteArray(8), part(1, 0, 5, 1024, "00000000"), 0)))
        assertEquals(0, asm.inFlightBytes)
    }

    @Test
    fun negativeIndexIsRejected() {
        assertEquals(ProtocolViolation.METADATA, rejected(assembler().accept(ByteArray(8), part(1, -1, 2, 16, "00000000"), 0)))
    }

    @Test
    fun assemblyTimesOutAndIsEvicted() {
        val whole = ByteArray(100) { it.toByte() }
        val crc = crc32Hex(whole)
        val asm = assembler(timeoutMs = 30_000L)
        assertBuffered(asm.accept(whole.copyOfRange(0, 50), part(1, 0, 2, whole.size, crc), 1_000L))
        // 未到点：不淘汰
        assertEquals(emptyList<ProtocolViolation>(), asm.sweepExpired(30_999L))
        // 到点：淘汰并给出固定错误码
        assertEquals(listOf(ProtocolViolation.ASSEMBLY_TIMEOUT), asm.sweepExpired(31_000L))
        assertEquals(0, asm.inFlightBytes)
        // 淘汰后不残留：下次 sweep 无事可做
        assertEquals(emptyList<ProtocolViolation>(), asm.sweepExpired(99_000L))
    }

    @Test
    fun inFlightMessagesAreBoundedByEvictingOldest() {
        val asm = assembler(maxInFlightMessages = 2, maxInFlightBytes = 1024)
        // 三条消息各留一片在途 → 只保留最近两条
        for (seq in 1..3) {
            assertBuffered(asm.accept(ByteArray(10), part(seq, 0, 2, 20, "00000000"), seq.toLong()))
        }
        assertEquals(20, asm.inFlightBytes)
        // 最旧的 seq=1 已被驱逐：它的后续分片只能按「新消息首片」处理 → 缺首片拒绝
        assertEquals(ProtocolViolation.FRAGMENT_GAP, rejected(asm.accept(ByteArray(10), part(1, 1, 2, 20, "00000000"), 10L)))
    }

    @Test
    fun inFlightBytesOverBudgetIsRejectedAndFreed() {
        val asm = assembler(maxInFlightBytes = 100)
        val out = asm.accept(ByteArray(101), part(1, 0, 2, 500, "00000000"), 0)
        assertEquals(ProtocolViolation.BUFFER_OVERFLOW, rejected(out))
        assertEquals(0, asm.inFlightBytes)
    }

    @Test
    fun clearDropsAllInFlightState() {
        val asm = assembler()
        assertBuffered(asm.accept(ByteArray(10), part(1, 0, 3, 30, "00000000"), 0))
        asm.clear()
        assertEquals(0, asm.inFlightBytes)
        assertEquals(emptyList<ProtocolViolation>(), asm.sweepExpired(Long.MAX_VALUE))
    }
}
