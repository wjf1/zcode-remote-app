package com.zcode.remote.relay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * 协议帧回放网（任务书 §11.8「协议录制帧回放」）。
 *
 * Fixture 由 `tools/gen_rpc_frame_fixtures.py`（独立 Python 实现，复用 `tools/probe.py` 的 VQL 与
 * CRC32）按**官方切分算法**构造（等长分片、`fragmentCount ≤ 64`、整条消息 CRC32、标准 base64；
 * 依据 `research/FRAME-CODEC.md` §3.6/§6），本测试用同一组帧回放**纯逻辑层**（身份判定 /
 * base64 形状 / 元数据校验 / 分片组装），逐帧断言期望结果——两份独立实现互为校验，
 * 上游协议若变，先动生成脚本，CI 立刻变红。
 *
 * 说明：测试侧 base64 解码用 `java.util.Base64`（fixture 全是规范编码）；生产路径的解码与
 * 「解码-重编码往返」校验在 `RpcChannel` 用 `android.util.Base64` 完成（JVM 单测不可用），
 * 这里以形状校验 + 组装结果等价覆盖。
 */
class RpcFrameReplayTest {
    private val json = Json

    private class Case(
        val name: String,
        val frames: List<JsonObject>,
        val outcomes: List<String>,
        val identityVerdict: String,
        val canonicalBase64: Boolean,
        val metadataViolation: String?,
        val messageHex: String?,
        val completedSeq: Int?,
        val completedTimes: Int,
        val head: JsonElement?,
        val args: JsonElement?,
    )

    private fun load(): Pair<FrameIdentity, List<Case>> {
        val stream =
            javaClass.classLoader.getResourceAsStream("rpc_frame_fixtures.json")
                ?: error("找不到 rpc_frame_fixtures.json —— 先跑 tools/gen_rpc_frame_fixtures.py")
        val root = json.parseToJsonElement(stream.readBytes().decodeToString()).jsonObject
        val identityObj = root["identity"]!!.jsonObject
        val identity =
            FrameIdentity(
                sessionId = identityObj["sessionId"]?.jsonPrimitive?.content,
                generation = identityObj["generation"]?.jsonPrimitive?.content,
                recoveryId = identityObj["recoveryId"]?.jsonPrimitive?.content,
            )
        val cases =
            root["cases"]!!.let { it as JsonArray }.map { el ->
                val o = el.jsonObject
                Case(
                    name = o["name"]!!.jsonPrimitive.content,
                    frames = o["frames"]!!.let { it as JsonArray }.map { f -> f.jsonObject },
                    outcomes = o["outcomes"]!!.let { it as JsonArray }.map { v -> v.jsonPrimitive.content },
                    identityVerdict = o["identityVerdict"]!!.jsonPrimitive.content,
                    canonicalBase64 = o["canonicalBase64"]!!.jsonPrimitive.boolean,
                    metadataViolation =
                        o["metadataViolation"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
                    messageHex = o["messageHex"]?.jsonPrimitive?.content,
                    completedSeq = o["completedSeq"]?.jsonPrimitive?.int,
                    completedTimes = o["completedTimes"]?.jsonPrimitive?.int ?: 0,
                    head = o["head"],
                    args = o["args"],
                )
            }
        return identity to cases
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun outcomeLabel(outcome: RpcFrameAssembler.Outcome): String =
        when (outcome) {
            is RpcFrameAssembler.Outcome.Buffered -> "buffered"
            is RpcFrameAssembler.Outcome.Complete -> "complete"
            is RpcFrameAssembler.Outcome.Rejected -> "rejected:${outcome.violation.code}"
        }

    @Test
    fun replayAllFixtureCases() {
        val (currentIdentity, cases) = load()
        assertTrue("fixture 用例数异常：${cases.size}", cases.size >= 15)
        val failed = mutableListOf<String>()
        for (c in cases) {
            fun check(cond: Boolean, what: String) {
                if (!cond) failed += "${c.name}: $what"
            }

            // ① 身份判定（上层编排：不一致即丢弃且不 ack）
            val frameIdentity = frameIdentityOf(c.frames.first())
            val mismatch = identityMismatch(frameIdentity, currentIdentity)
            check(
                mismatch == (c.identityVerdict == "mismatch"),
                "identity 判定 ${c.identityVerdict} 不符（mismatch=$mismatch）",
            )

            // ② base64 形状（官方 isCanonicalBase64 的第一道，纯逻辑）
            val b64 = c.frames.first()["dataBase64"]!!.jsonPrimitive.content
            check(isCanonicalBase64Shape(b64) == c.canonicalBase64, "base64 形状判定不符")

            // ③ 元数据校验（官方 zod 约束的同构实现）
            val metadata = rpcFrameMetadataOf(c.frames.first())
            assertNotNull("${c.name}: 元数据解析失败", metadata)
            val violation = validateRpcFrameMetadata(metadata!!)
            check(violation?.code == c.metadataViolation, "元数据校验 ${violation?.code} != ${c.metadataViolation}")

            if (!c.canonicalBase64) continue // 形状非法：上层在解码前即拒，不进组装

            // ④ 分片组装：逐帧回放，断言结果序列
            val assembler = RpcFrameAssembler()
            val actual = mutableListOf<String>()
            var completedBytes: ByteArray? = null
            var completedSeq: Int? = null
            var completes = 0
            var now = 0L
            for (frame in c.frames) {
                val slice = Base64.getDecoder().decode(frame["dataBase64"]!!.jsonPrimitive.content)
                val meta = rpcFrameMetadataOf(frame)!!
                val outcome = assembler.accept(slice, meta, now)
                now += 1
                actual += outcomeLabel(outcome)
                if (outcome is RpcFrameAssembler.Outcome.Complete) {
                    completes += 1
                    completedBytes = outcome.bytes
                    completedSeq = outcome.messageSeq
                }
            }
            check(actual == c.outcomes, "组装序列 $actual != ${c.outcomes}")
            check(completes == c.completedTimes, "完成次数 $completes != ${c.completedTimes}")
            if (c.messageHex != null && c.completedTimes > 0) {
                check(
                    completedBytes != null && completedBytes!!.contentEquals(hexToBytes(c.messageHex)),
                    "拼装字节与 fixture 不一致",
                )
                check(completedSeq == c.completedSeq, "completedSeq $completedSeq != ${c.completedSeq}")
            }

            // ⑤ 官方文档示例：Kotlin VQL 编码必须复现 Python 侧的报文（帧层跨实现对拍）
            if (c.head != null && c.args != null && c.messageHex != null) {
                val headValue = jsonValueOf(c.head)
                val argsValue = jsonValueOf(c.args)
                val encoded = Vql.serialize(headValue as List<*>, argsValue)
                check(encoded.contentEquals(hexToBytes(c.messageHex)), "VQL 编码与 fixture 报文不一致")
            }
        }
        assertTrue("回放断言失败：\n" + failed.joinToString("\n"), failed.isEmpty())
    }

    /** fixture 的 head/args 是 JSON；转成 Vql.serialize 接受的原生值。 */
    private fun jsonValueOf(el: JsonElement): Any? =
        when (el) {
            is JsonNull -> null
            is JsonPrimitive ->
                when {
                    el.isString -> el.content
                    el.content == "true" -> true
                    el.content == "false" -> false
                    else -> el.content.toIntOrNull() ?: el.content.toLongOrNull() ?: el.content
                }
            is JsonArray -> el.map { jsonValueOf(it) }
            is JsonObject -> el.mapValues { jsonValueOf(it.value) }
        }

    @Test
    fun everyFixtureCaseIsCovered() {
        val (_, cases) = load()
        // 关键用例必须在场（防「fixture 被大改后测试静默变空」）
        val names = cases.map { it.name }.toSet()
        for (required in listOf(
            "doc_example_single_fragment", "multi_max_fragments_64", "multi_checksum_mismatch_on_join",
            "multi_conflicting_duplicate", "single_fragment_count_over_limit",
        )) {
            assertTrue("缺少关键用例 $required", required in names)
        }
        assertFalse("fixture 里不应有空帧用例", cases.any { it.frames.isEmpty() && it.outcomes.isNotEmpty() })
    }
}
