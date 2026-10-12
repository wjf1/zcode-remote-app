package com.zcode.remote.relay

/**
 * 入站 rpc-frame 分片组装器（§11.5.2，纯逻辑、无 Android 依赖，JVM 单测覆盖）。
 *
 * 语义对齐官方 `WebRemoteControlRpcTransportAssembler`（`research/FRAME-CODEC.md` §5/§6）：
 * - 单分片与多分片**同一套校验**：声明长度（`messageBytes`）+ 整条消息 CRC32；
 * - 同一位点指纹一致的重复分片 → 幂等忽略（官方 `kind:"duplicate"`）；指纹冲突 → 拒绝；
 * - 分片序缺口（跳号/首片缺失）→ 拒绝（官方 `fragmentGap`）；
 * - 重组超时（官方 `assemblyTimeoutMs`）→ 淘汰，由调用方触发重同步；
 * - 在途条数与总字节有界（App 侧预算），超限驱逐最旧/拒绝当前帧，**不无限缓存**。
 *
 * 返回 [Outcome.Rejected] 的帧**不得 ack**（服务端可重放，上层进入重同步拿权威快照）。
 */
internal class RpcFrameAssembler(
    private val maxFragments: Int = ProtocolLimits.MAX_FRAGMENTS,
    private val maxMessageBytes: Int = ProtocolLimits.MAX_MESSAGE_BYTES,
    private val timeoutMs: Long = ProtocolLimits.ASSEMBLY_TIMEOUT_MS,
    private val maxInFlightMessages: Int = ProtocolLimits.MAX_ASSEMBLY_IN_FLIGHT_MESSAGES,
    private val maxInFlightBytes: Int = ProtocolLimits.MAX_ASSEMBLY_IN_FLIGHT_BYTES,
) {
    sealed interface Outcome {
        /** 已缓冲，等待其余分片。 */
        data object Buffered : Outcome

        /** 收齐且校验通过：可直接解码组装结果。 */
        data class Complete(val messageSeq: Int, val bytes: ByteArray) : Outcome

        /** 拒绝（固定错误码）：调用方不得 ack，应进入重同步。 */
        data class Rejected(val violation: ProtocolViolation) : Outcome
    }

    private class Pending(
        val messageSeq: Int,
        val messageBytes: Int,
        val fragmentCount: Int,
        val checksum: String,
        val startedAtMs: Long,
    ) {
        val parts = ArrayList<ByteArray>(fragmentCount)

        /** 位点指纹（长度 + 分片 CRC32）：用于重复分片幂等判定与冲突检测。 */
        val fingerprints = HashMap<Int, Long>()
        var stagedBytes = 0
    }

    /** messageSeq → 在途消息（保持插入序以便驱逐最旧）。 */
    private val pending = LinkedHashMap<Int, Pending>()

    /** 当前在途分片总字节（测试与观测用）。 */
    val inFlightBytes: Int get() = pending.values.sumOf { it.stagedBytes }

    /** 当前在途未完成消息条数（观测/压测断言用：永不超过 [maxInFlightMessages]）。 */
    val pendingCount: Int get() = pending.size

    fun clear() {
        pending.clear()
    }

    /**
     * 超时淘汰：返回每条被淘汰消息对应的固定错误码（调用方据此触发重同步）。
     * 时间由调用方注入（`elapsedRealtime`），保证纯逻辑可测。
     */
    fun sweepExpired(nowMs: Long): List<ProtocolViolation> {
        val expired = pending.values.filter { nowMs >= it.startedAtMs && nowMs - it.startedAtMs >= timeoutMs }
        if (expired.isEmpty()) return emptyList()
        expired.forEach { pending.remove(it.messageSeq) }
        return expired.map { ProtocolViolation.ASSEMBLY_TIMEOUT }
    }

    /**
     * 接收一片分片。`slice` 为已解码字节（base64 规范往返由调用方先行校验）。
     */
    fun accept(
        slice: ByteArray,
        metadata: RpcFrameMetadata,
        nowMs: Long,
    ): Outcome {
        validateRpcFrameMetadata(metadata, maxFragments, maxMessageBytes)?.let { return Outcome.Rejected(it) }
        val checksum = metadata.checksumValue?.lowercase() ?: return Outcome.Rejected(ProtocolViolation.METADATA)
        return if (metadata.fragmentCount == 1) {
            acceptSingle(slice, metadata, checksum)
        } else {
            acceptMulti(slice, metadata, checksum, nowMs)
        }
    }

    /** 单分片：与多分片**同一套**「声明长度 + 整条 CRC」校验（旧实现只对多分片校验，是修复点之一）。 */
    private fun acceptSingle(
        slice: ByteArray,
        metadata: RpcFrameMetadata,
        checksum: String,
    ): Outcome {
        if (slice.size != metadata.messageBytes) return Outcome.Rejected(ProtocolViolation.LENGTH_MISMATCH)
        if (crc32Hex(slice) != checksum) return Outcome.Rejected(ProtocolViolation.CHECKSUM_MISMATCH)
        return Outcome.Complete(metadata.messageSeq, slice)
    }

    private fun acceptMulti(
        slice: ByteArray,
        metadata: RpcFrameMetadata,
        checksum: String,
        nowMs: Long,
    ): Outcome {
        val fingerprint = fingerprint(slice)
        val active = pending[metadata.messageSeq]
        return if (active == null) {
            startNewMessage(slice, metadata, checksum, fingerprint, nowMs)
        } else {
            continueMessage(active, slice, metadata, checksum, fingerprint)
        }
    }

    /** 新消息首片：必须 index=0（缺首片无法补齐），入队时受条数/总字节上限约束。 */
    private fun startNewMessage(
        slice: ByteArray,
        metadata: RpcFrameMetadata,
        checksum: String,
        fingerprint: Long,
        nowMs: Long,
    ): Outcome {
        if (metadata.fragmentIndex != 0) return Outcome.Rejected(ProtocolViolation.FRAGMENT_GAP)
        if (pending.size >= maxInFlightMessages) evictOldest()
        val fresh = Pending(metadata.messageSeq, metadata.messageBytes, metadata.fragmentCount, checksum, nowMs)
        stage(fresh, slice, 0, fingerprint)
        pending[metadata.messageSeq] = fresh
        if (inFlightBytes > maxInFlightBytes) {
            pending.remove(metadata.messageSeq)
            return Outcome.Rejected(ProtocolViolation.BUFFER_OVERFLOW)
        }
        return Outcome.Buffered
    }

    /** 在途消息的后续分片：元数据全等 → 位点推进（重复幂等/冲突拒绝/缺口拒绝）→ 收齐校验。 */
    private fun continueMessage(
        active: Pending,
        slice: ByteArray,
        metadata: RpcFrameMetadata,
        checksum: String,
        fingerprint: Long,
    ): Outcome {
        if (active.fragmentCount != metadata.fragmentCount || active.messageBytes != metadata.messageBytes) {
            pending.remove(metadata.messageSeq)
            return Outcome.Rejected(ProtocolViolation.METADATA)
        }
        if (active.checksum != checksum) {
            pending.remove(metadata.messageSeq)
            return Outcome.Rejected(ProtocolViolation.CHECKSUM_MISMATCH)
        }
        if (metadata.fragmentIndex < active.parts.size) {
            // 重复分片：指纹一致 → 幂等忽略；指纹冲突 → 拒绝（官方 conflictingDuplicate）
            return if (active.fingerprints[metadata.fragmentIndex] == fingerprint) {
                Outcome.Buffered
            } else {
                pending.remove(metadata.messageSeq)
                Outcome.Rejected(ProtocolViolation.DUPLICATE_CONFLICT)
            }
        }
        if (metadata.fragmentIndex > active.parts.size) {
            pending.remove(metadata.messageSeq)
            return Outcome.Rejected(ProtocolViolation.FRAGMENT_GAP)
        }
        if (active.stagedBytes + slice.size > active.messageBytes) {
            pending.remove(metadata.messageSeq)
            return Outcome.Rejected(ProtocolViolation.LENGTH_MISMATCH)
        }
        stage(active, slice, metadata.fragmentIndex, fingerprint)
        if (active.parts.size < active.fragmentCount) return Outcome.Buffered
        return finishMessage(active)
    }

    /** 收齐：拼装后以声明长度 + 整条 CRC 收口，任一不符即拒绝（不 ack，交由上层重同步）。 */
    private fun finishMessage(active: Pending): Outcome {
        val joined = ByteArray(active.stagedBytes)
        var offset = 0
        for (part in active.parts) {
            part.copyInto(joined, offset)
            offset += part.size
        }
        pending.remove(active.messageSeq)
        if (joined.size != active.messageBytes) return Outcome.Rejected(ProtocolViolation.LENGTH_MISMATCH)
        if (crc32Hex(joined) != active.checksum) return Outcome.Rejected(ProtocolViolation.CHECKSUM_MISMATCH)
        return Outcome.Complete(active.messageSeq, joined)
    }

    private fun stage(
        target: Pending,
        slice: ByteArray,
        index: Int,
        fingerprint: Long,
    ) {
        target.parts.add(slice)
        target.fingerprints[index] = fingerprint
        target.stagedBytes += slice.size
    }

    private fun evictOldest() {
        pending.values.minByOrNull { it.startedAtMs }?.let { pending.remove(it.messageSeq) }
    }

    /** 位点指纹 = (分片长度 << 32) | crc32(分片)。 */
    private fun fingerprint(slice: ByteArray): Long = (slice.size.toLong() shl 32) or crc32Hex(slice).toLong(16)
}
