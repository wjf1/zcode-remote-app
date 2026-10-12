package com.zcode.remote.relay

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * 协议资源预算模糊测试（任务书 §11.3.3/§11.8：随机与畸形输入下**无异常逃逸、无 OOM、
 * 拒绝可控且码固定、内部结构始终有界**）。
 *
 * 用固定种子（可复现）驱动纯逻辑层：畸形元数据 / 任意长度分片 / 随机 JSON 形状 /
 * 乱序与重复分片 / 随机身份串。断言不依赖具体随机结果，只依赖**不变量**。
 */
class ProtocolFuzzTest {
    private val knownCodes = ProtocolViolation.entries.map { it.code }.toSet()

    private fun randomMetadata(rnd: Random): RpcFrameMetadata {
        fun extremeInt(): Int =
            when (rnd.nextInt(8)) {
                0 -> Int.MIN_VALUE
                1 -> Int.MAX_VALUE
                2 -> -1
                3 -> 0
                4 -> 1
                5 -> ProtocolLimits.MAX_FRAGMENTS
                6 -> ProtocolLimits.MAX_MESSAGE_BYTES
                else -> rnd.nextInt(1 shl 20)
            }
        return RpcFrameMetadata(
            seq = extremeInt(),
            messageSeq = extremeInt(),
            fragmentIndex = extremeInt(),
            fragmentCount = extremeInt(),
            messageBytes = extremeInt(),
            checksumAlgorithm = if (rnd.nextBoolean()) "crc32" else listOf("", "CRC32", "sha256", "md5")[rnd.nextInt(4)],
            checksumValue =
                if (rnd.nextBoolean()) {
                    crc32Hex(ByteArray(rnd.nextInt(32)))
                } else {
                    listOf("", "ZZZZZZZZ", "cbf4392", "cbf43926ff", "CBF43926")[rnd.nextInt(5)]
                },
        )
    }

    @Test
    fun fuzzAssemblerNeverEscapesAndStaysBounded() {
        val rnd = Random(20261012L)
        val asm = RpcFrameAssembler()
        val seenCodes = mutableSetOf<String>()
        var iterations = 0
        var completes = 0
        var now = 0L
        repeat(30_000) {
            iterations++
            now += rnd.nextInt(5_000).toLong()
            val metadata = randomMetadata(rnd)
            val size = if (rnd.nextInt(10) == 0) rnd.nextInt(2_000_000) else rnd.nextInt(4_096)
            val slice = ByteArray(size).also { rnd.nextBytes(it) }
            val outcome =
                try {
                    asm.accept(slice, metadata, now)
                } catch (t: Throwable) {
                    throw AssertionError("accept 抛出异常（iter=$iterations meta=$metadata size=$size）", t)
                }
            when (outcome) {
                is RpcFrameAssembler.Outcome.Buffered -> Unit
                is RpcFrameAssembler.Outcome.Complete -> {
                    completes++
                    assertTrue("Complete 的字节数非法：${outcome.bytes.size}", outcome.bytes.size in 1..ProtocolLimits.MAX_MESSAGE_BYTES)
                }
                is RpcFrameAssembler.Outcome.Rejected -> {
                    assertTrue("未知错误码 ${outcome.violation}", outcome.violation.code in knownCodes)
                    seenCodes += outcome.violation.code
                }
            }
            // 不变量：任何时刻内部结构有界
            assertTrue(
                "在途字节越界 ${asm.inFlightBytes}",
                asm.inFlightBytes <= ProtocolLimits.MAX_ASSEMBLY_IN_FLIGHT_BYTES,
            )
            assertTrue(
                "在途条数越界 ${asm.pendingCount}",
                asm.pendingCount <= ProtocolLimits.MAX_ASSEMBLY_IN_FLIGHT_MESSAGES,
            )
            if (rnd.nextInt(50) == 0) {
                val expired = asm.sweepExpired(now)
                assertTrue("sweep 返回未知码 $expired", expired.all { it.code in knownCodes })
            }
            if (rnd.nextInt(500) == 0) asm.clear()
        }
        assertEquals(30_000, iterations)
        assertTrue("模糊测试未覆盖到任何拒绝码（用例失去意义）", seenCodes.size >= 4)
        // 极端声明（Int.MAX_VALUE 等）必须被拒绝而不是被当作大缓冲分配
        assertTrue(
            "缺少用量拒绝码覆盖：$seenCodes",
            ProtocolViolation.MESSAGE_BYTES.code in seenCodes ||
                ProtocolViolation.FRAGMENT_COUNT.code in seenCodes ||
                ProtocolViolation.METADATA.code in seenCodes,
        )
        println("[fuzz] assembler 30k 轮：completes=$completes 拒绝码=${seenCodes.sorted()}")
    }

    @Test
    fun fuzzPayloadParsersNeverEscape() {
        val rnd = Random(7L)
        repeat(5_000) { exerciseParsers(randomFrameJson(rnd)) }
    }

    /** 随机形状的帧 JSON（含非法类型：checksum 为字符串/对象、超长身份串、null 值等）。 */
    private fun randomFrameJson(rnd: Random): JsonObject =
        buildJsonObject {
            if (rnd.nextBoolean()) put("seq", rnd.nextInt())
            if (rnd.nextBoolean()) put("messageSeq", rnd.nextInt())
            if (rnd.nextBoolean()) put("fragmentIndex", rnd.nextInt())
            if (rnd.nextBoolean()) put("fragmentCount", rnd.nextInt())
            if (rnd.nextBoolean()) put("messageBytes", rnd.nextInt())
            if (rnd.nextBoolean()) put("dataBase64", randomString(rnd, 0, 64))
            if (rnd.nextBoolean()) put("bridgeSessionId", randomString(rnd, 0, 300))
            if (rnd.nextBoolean()) put("bridgeGeneration", rnd.nextInt())
            if (rnd.nextBoolean()) put("recoveryId", JsonNull)
            if (rnd.nextBoolean()) put("checksum", JsonPrimitive(randomString(rnd, 0, 12)))
            if (rnd.nextBoolean()) {
                putJsonObject("checksum") {
                    put("algorithm", randomString(rnd, 0, 8))
                    put("value", randomString(rnd, 0, 12))
                }
            }
            if (rnd.nextBoolean()) put("extra", JsonArray(listOf(JsonPrimitive(1), JsonNull)))
        }

    /** 全部纯解析/校验函数都不得抛异常，且结果必须是稳定码或 null。 */
    private fun exerciseParsers(el: JsonObject) {
        val metadata = rpcFrameMetadataOf(el)
        if (metadata != null) {
            val v = validateRpcFrameMetadata(metadata)
            assertTrue("未知码 $v", v == null || v.code in knownCodes)
        }
        val identity = frameIdentityOf(el)
        validateIdentityLengths(identity)?.let { assertTrue(it.code in knownCodes) }
        identityMismatch(identity, FrameIdentity("bridge-1", "1", "rec-1"))
        identityMismatch(identity, null)
        val b64 = (el["dataBase64"] as? JsonPrimitive)?.content ?: return
        isCanonicalBase64Shape(b64)
    }

    @Test
    fun fuzzBase64ShapeMatchesRoundTripProperty() {
        val rnd = Random(99L)
        var accepted = 0
        repeat(20_000) {
            val n = rnd.nextInt(24)
            val s = randomString(rnd, n, n)
            val shapeOk = isCanonicalBase64Shape(s)
            if (!shapeOk) return@repeat
            accepted++
            // 形状通过的串必须可被标准解码器解码（形状校验不得放过解码器无法处理的串）
            val decoded =
                try {
                    java.util.Base64.getDecoder().decode(s)
                } catch (t: IllegalArgumentException) {
                    throw AssertionError("形状校验放过了解码器无法处理的串：'$s'", t)
                }
            // 且往返重编码全等（真规范）
            assertEquals("往返不等价：'$s'", s, java.util.Base64.getEncoder().encodeToString(decoded))
        }
        assertTrue("形状校验通过率异常（accepted=$accepted）", accepted > 0)
    }

    @Test
    fun fuzzInboundAdmissionStaysBounded() {
        val rnd = Random(31337L)
        val admission = InboundAdmission()
        var admitAttempts = 0
        var consumed = 0
        repeat(50_000) {
            when (rnd.nextInt(10)) {
                // 70% 试图准入、30% 消费（生产语义：trySend 满员时失败 → onDropped）
                0, 1, 2, 3, 4, 5, 6 -> {
                    admitAttempts++
                    if (admission.depth >= admission.capacity) {
                        admission.onDropped()
                    } else {
                        admission.onAdmitted()
                    }
                }
                else -> {
                    admission.onConsumed()
                    consumed++
                }
            }
            assertTrue("深度越界 ${admission.depth}", admission.depth in 0..admission.capacity)
            assertTrue("峰值越界 ${admission.peakDepth}", admission.peakDepth <= admission.capacity)
        }
        assertEquals("每次准入尝试必落 admitted 或 dropped", admitAttempts, admission.admitted + admission.dropped)
        assertTrue("容量约束未生效（无拒绝）", admission.dropped > 0)
        assertTrue("深度轨迹异常（未接近容量）", admission.peakDepth == admission.capacity)
        assertTrue("消费计数异常", consumed > 0)
        println("[fuzz] admission：$admission 消费调用=$consumed")
    }

    private fun randomString(rnd: Random, min: Int, max: Int): String {
        val len = if (min == max) min else min + rnd.nextInt(max - min + 1)
        val alphabet = "ABCabc012+/=-_ 中文\u0000"
        return (0 until len).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
    }
}
