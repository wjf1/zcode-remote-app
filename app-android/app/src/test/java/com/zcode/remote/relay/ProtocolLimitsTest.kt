package com.zcode.remote.relay

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §11.3.3 集中协议预算 + §11.5.2 帧元数据/身份校验的纯函数单测。
 *
 * 常量值钉死官方实证（host asar / 官方 bundle zod schema，见 `PROTOCOL.md` §4.1 与
 * `research/FRAME-CODEC.md` §3.6）——任何人「顺手调大」都会红，必须同步证据与文档。
 */
class ProtocolLimitsTest {
    // ---------- 官方常量钉死（防漂移） ----------

    @Test
    fun officialLimitsMatchEvidence() {
        assertEquals(1024 * 1024, ProtocolLimits.MAX_PHYSICAL_FRAME_BYTES)
        assertEquals(16 * 1024 * 1024, ProtocolLimits.MAX_MESSAGE_BYTES)
        assertEquals(64, ProtocolLimits.MAX_FRAGMENTS)
        assertEquals(30_000L, ProtocolLimits.ASSEMBLY_TIMEOUT_MS)
    }

    @Test
    fun appSideBudgetsAreBounded() {
        assertTrue(ProtocolLimits.MAX_ASSEMBLY_IN_FLIGHT_MESSAGES in 1..16)
        assertEquals(ProtocolLimits.MAX_MESSAGE_BYTES, ProtocolLimits.MAX_ASSEMBLY_IN_FLIGHT_BYTES)
        assertTrue(ProtocolLimits.INBOUND_QUEUE_CAPACITY in 64..8192)
        assertTrue(ProtocolLimits.RPC_EVENT_BUFFER_CAPACITY >= 64)
        assertTrue(ProtocolLimits.RESYNC_MIN_INTERVAL_MS >= 1_000L)
        // 上传预算与官方 host 常量一致（20MiB / 64 片）
        assertEquals(20 * 1024 * 1024, ProtocolLimits.MAX_UPLOAD_ATTACHMENT_BYTES)
        assertEquals(64, ProtocolLimits.MAX_UPLOAD_CHUNKS)
        assertEquals(ProtocolLimits.MAX_UPLOAD_ATTACHMENT_BYTES, ConversationChannel.MAX_ATTACHMENT_BYTES)
    }

    // ---------- crc32Hex ----------

    @Test
    fun crc32MatchesStandardVector() {
        // 标准 CRC-32 测试向量："123456789" → 0xCBF43926
        assertEquals("cbf43926", crc32Hex("123456789".toByteArray(Charsets.UTF_8)))
        assertEquals("00000000", crc32Hex(ByteArray(0)))
    }

    // ---------- 元数据校验（官方 zod 约束的同构实现） ----------

    private fun meta(
        seq: Int = 1,
        messageSeq: Int = 1,
        index: Int = 0,
        count: Int = 1,
        bytes: Int = 10,
        algorithm: String? = "crc32",
        checksum: String? = "cbf43926",
    ) = RpcFrameMetadata(seq, messageSeq, index, count, bytes, algorithm, checksum)

    @Test
    fun metadataAcceptsWellFormedFrame() {
        assertNull(validateRpcFrameMetadata(meta()))
        assertNull(validateRpcFrameMetadata(meta(index = 63, count = 64, bytes = 16 * 1024 * 1024)))
    }

    @Test
    fun metadataRejectsNonPositiveSeq() {
        assertEquals(ProtocolViolation.METADATA, validateRpcFrameMetadata(meta(seq = 0)))
        assertEquals(ProtocolViolation.METADATA, validateRpcFrameMetadata(meta(seq = -1)))
        assertEquals(ProtocolViolation.METADATA, validateRpcFrameMetadata(meta(messageSeq = 0)))
        assertEquals(ProtocolViolation.METADATA, validateRpcFrameMetadata(meta(messageSeq = -7)))
    }

    @Test
    fun metadataRejectsFragmentCountOutOfRange() {
        assertEquals(ProtocolViolation.FRAGMENT_COUNT, validateRpcFrameMetadata(meta(count = 0)))
        assertEquals(ProtocolViolation.FRAGMENT_COUNT, validateRpcFrameMetadata(meta(count = -3, index = 0)))
        // 官方上限 64：65 即拒（极大分片数不消耗任何内存）
        assertEquals(ProtocolViolation.FRAGMENT_COUNT, validateRpcFrameMetadata(meta(count = 65, index = 0)))
        assertEquals(ProtocolViolation.FRAGMENT_COUNT, validateRpcFrameMetadata(meta(count = Int.MAX_VALUE, index = 0)))
    }

    @Test
    fun metadataRejectsFragmentIndexOutOfRange() {
        assertEquals(ProtocolViolation.METADATA, validateRpcFrameMetadata(meta(index = -1, count = 4)))
        assertEquals(ProtocolViolation.FRAGMENT_GAP, validateRpcFrameMetadata(meta(index = 4, count = 4)))
        assertEquals(ProtocolViolation.FRAGMENT_GAP, validateRpcFrameMetadata(meta(index = 99, count = 4)))
    }

    @Test
    fun metadataRejectsMessageBytesOverBudget() {
        assertEquals(ProtocolViolation.METADATA, validateRpcFrameMetadata(meta(bytes = 0)))
        assertEquals(
            ProtocolViolation.MESSAGE_BYTES,
            validateRpcFrameMetadata(meta(bytes = ProtocolLimits.MAX_MESSAGE_BYTES + 1)),
        )
        assertEquals(
            ProtocolViolation.MESSAGE_BYTES,
            validateRpcFrameMetadata(meta(bytes = Int.MAX_VALUE)),
        )
    }

    @Test
    fun metadataRejectsChecksumShape() {
        assertEquals(ProtocolViolation.METADATA, validateRpcFrameMetadata(meta(checksum = null)))
        assertEquals(ProtocolViolation.METADATA, validateRpcFrameMetadata(meta(checksum = "CB F43926")))
        assertEquals(ProtocolViolation.METADATA, validateRpcFrameMetadata(meta(checksum = "cbf4392")))
        assertEquals(ProtocolViolation.METADATA, validateRpcFrameMetadata(meta(checksum = "cbf43926ff")))
        assertEquals(ProtocolViolation.METADATA, validateRpcFrameMetadata(meta(algorithm = "sha256")))
        assertEquals(ProtocolViolation.METADATA, validateRpcFrameMetadata(meta(algorithm = null)))
    }

    @Test
    fun metadataThresholdsAreInjectableForTests() {
        // 阈值可注入（回退条款：冲突时只调集中配置并补样本测试）
        assertNull(validateRpcFrameMetadata(meta(count = 4, index = 3), maxFragments = 4))
        assertEquals(
            ProtocolViolation.FRAGMENT_COUNT,
            validateRpcFrameMetadata(meta(count = 5, index = 0), maxFragments = 4),
        )
    }

    // ---------- 身份三元组 ----------

    @Test
    fun identityMismatchFollowsOfficialRule() {
        val current = FrameIdentity("bridge-1", "1", "rec-1")
        // 无身份键：不判定（官方 schema 会话键必带，此为兜底）
        assertFalse(identityMismatch(FrameIdentity(null, null, null), current))
        // 全等：匹配
        assertFalse(identityMismatch(FrameIdentity("bridge-1", "1", "rec-1"), current))
        // sessionId / generation / recoveryId 只要「出现」就必须全等
        assertTrue(identityMismatch(FrameIdentity("bridge-2", "1", "rec-1"), current))
        assertTrue(identityMismatch(FrameIdentity("bridge-1", "2", "rec-1"), current))
        assertTrue(identityMismatch(FrameIdentity("bridge-1", "1", "rec-2"), current))
        assertTrue(identityMismatch(FrameIdentity("bridge-9", null, null), current))
        assertFalse(identityMismatch(FrameIdentity("bridge-1", null, null), current))
        // 宽容方向（与官方唯一偏差，防「可选键缺省 → 整帧误拒」的全量故障）：
        // 可选键缺席不参与比较；但必带的 sessionId 缺失而仅带可选键时，仍按出现键全等判定
        assertFalse(identityMismatch(FrameIdentity("bridge-1", "1", null), current))
        assertFalse(identityMismatch(FrameIdentity("bridge-1", null, "rec-1"), current))
        assertTrue(identityMismatch(FrameIdentity(null, "2", null), current))
    }

    @Test
    fun identityMismatchWhenNoBridge() {
        // 无桥（复位/未开桥）时，带身份键的帧一律视为旧代次迟到帧
        assertTrue(identityMismatch(FrameIdentity("bridge-1", "1", null), null))
        assertFalse(identityMismatch(FrameIdentity(null, null, null), null))
    }

    @Test
    fun identityLengthGuard() {
        val long = "x".repeat(ProtocolLimits.MAX_IDENTITY_FIELD_CHARS + 1)
        assertEquals(ProtocolViolation.METADATA, validateIdentityLengths(FrameIdentity(long, null, null)))
        assertNull(validateIdentityLengths(FrameIdentity("bridge-1", "1", null)))
    }

    @Test
    fun frameIdentityParsesFromPayload() {
        val payload =
            buildJsonObject {
                put("bridgeSessionId", "bridge-1")
                put("bridgeGeneration", 1)
                put("recoveryId", "rec-1")
            }
        assertEquals(FrameIdentity("bridge-1", "1", "rec-1"), frameIdentityOf(payload))
        assertEquals(FrameIdentity(null, null, null), frameIdentityOf(buildJsonObject { put("seq", 1) }))
    }

    // ---------- 元数据解析 ----------

    @Test
    fun metadataParsesFromPayload() {
        val payload =
            buildJsonObject {
                put("seq", 7)
                put("messageSeq", 5)
                put("fragmentIndex", 1)
                put("fragmentCount", 3)
                put("messageBytes", 1234)
                putJsonObject("checksum") {
                    put("algorithm", "crc32")
                    put("value", "cbf43926")
                }
            }
        assertEquals(meta(seq = 7, messageSeq = 5, index = 1, count = 3, bytes = 1234), rpcFrameMetadataOf(payload))
    }

    @Test
    fun metadataParsingRequiresAllFields() {
        val missingChecksum =
            buildJsonObject {
                put("seq", 1)
                put("messageSeq", 1)
                put("fragmentIndex", 0)
                put("fragmentCount", 1)
                put("messageBytes", 1)
            }
        // checksum 键缺失 → 结构仍可解析（checksum 字段为 null），但校验判 METADATA
        assertNull(rpcFrameMetadataOf(missingChecksum)?.checksumAlgorithm)
        assertNull(rpcFrameMetadataOf(missingChecksum)?.checksumValue)
        assertEquals(ProtocolViolation.METADATA, validateRpcFrameMetadata(rpcFrameMetadataOf(missingChecksum)!!))
        // 顶层字段缺失 → 解析失败（null）
        assertNull(rpcFrameMetadataOf(buildJsonObject { put("seq", 1) }))
    }
}
