package com.zcode.remote.relay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * conversation V4 逻辑分帧重组（A-5，1.0 发布硬门）。
 *
 * 算法逐规则对齐官方 `wire-reassembly.ts` 的 `reassembleTopicWireFrames`：
 * 元数据全等 / 逐片字节上限 / 重复片幂等与冲突 / 缺片等待 /
 * CRC32 → UTF-8 严格 → JSON → 信封匹配 四连校验。
 */
class WireReassemblerTest {
    private val reassembler = WireReassembler(nowMs = { 1_000L })

    private fun jsonPrimitive(
        o: JsonObject,
        key: String,
    ): String? = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** 构造一帧 fragment 帧本体（字段 schema 对齐官方 wire.ts）。 */
    private fun frag(
        id: String,
        index: Int,
        count: Int,
        logicalBytes: Int,
        checksum: String,
        payload: String,
        ordinal: Int = 1,
        topic: String = "conversation/sess_a",
        subId: String = "sub-1",
        deliveryKind: String = "online",
    ): JsonObject =
        buildJsonObject {
            put("wireVersion", 4)
            put("kind", "fragment")
            put("deliveryKind", deliveryKind)
            put("logicalFrameId", id)
            put("logicalFrameOrdinal", ordinal)
            put("topic", topic)
            put("subscriptionId", subId)
            put("fragmentIndex", index)
            put("fragmentCount", count)
            put("logicalBytes", logicalBytes)
            put(
                "checksum",
                buildJsonObject {
                    put("algorithm", "crc32")
                    put("value", checksum)
                },
            )
            put(
                "dataBase64",
                java.util.Base64
                    .getEncoder()
                    .encodeToString(payload.toByteArray()),
            )
        }

    private fun crcOf(text: String): String = crc32Hex(text.toByteArray())

    /** 两片重组样本：返回（整条文本， 首片帧， 末片帧）。 */
    private fun twoPiece(
        part1: String,
        part2: String,
        id: String = "lf-1",
    ): Triple<String, JsonObject, JsonObject> {
        val whole = part1 + part2
        val crc = crcOf(whole)
        return Triple(
            whole,
            frag(id = id, index = 0, count = 2, logicalBytes = whole.length, checksum = crc, payload = part1),
            frag(id = id, index = 1, count = 2, logicalBytes = whole.length, checksum = crc, payload = part2),
        )
    }

    @Test
    fun `分片收齐重组出完整逻辑帧`() {
        // 一个真实 conversation 逻辑帧从中间切开（官方在 1 MiB 物理帧上限处按 UTF-8 字节切）
        val whole =
            """{"topic":"conversation/sess_a","subscriptionId":"sub-1","fromSeq":0,"toSeq":2,
               "payload":{"rows":[{"rowId":1,"text":"hello"}],"control":{"canStop":false}}}"""
        val splitAt = whole.length / 2
        val crc = crcOf(whole)
        reassembler.feed(
            frag(
                id = "lf-1",
                index = 0,
                count = 2,
                logicalBytes = whole.length,
                checksum = crc,
                payload = whole.substring(0, splitAt),
            ),
        )
        val r2 =
            reassembler.feed(
                frag(
                    id = "lf-1",
                    index = 1,
                    count = 2,
                    logicalBytes = whole.length,
                    checksum = crc,
                    payload = whole.substring(splitAt),
                ),
            )
        assertTrue(
            "r2 应为 Complete，实际 ${r2::class.simpleName} reason=${(r2 as? WireReassembler.Outcome.Rejected)?.reasonCode}",
            r2 is WireReassembler.Outcome.Complete,
        )
        val complete = r2 as WireReassembler.Outcome.Complete
        assertEquals("conversation/sess_a", jsonPrimitive(complete.frame, "topic"))
        assertEquals("online", complete.deliveryKind)
        // 重组帧必须能被 parseTopicFrame 解析（ConversationChannel 的落地点）
        assertTrue(ConversationFrames.parseTopicFrame(complete.frame) != null)
    }

    @Test
    fun `三缺一返回Incomplete与缺失下标`() {
        val id = "lf-m2"
        val crc = crcOf("abcd")
        reassembler.feed(frag(id = id, index = 0, count = 4, logicalBytes = 4, checksum = crc, payload = "a"))
        reassembler.feed(frag(id = id, index = 1, count = 4, logicalBytes = 4, checksum = crc, payload = "b"))
        reassembler.feed(frag(id = id, index = 3, count = 4, logicalBytes = 4, checksum = crc, payload = "d"))
        val r = reassembler.feed(frag(id = id, index = 0, count = 4, logicalBytes = 4, checksum = crc, payload = "a"))
        val incomplete = r as WireReassembler.Outcome.Incomplete
        assertEquals(listOf(2), incomplete.missingIndexes)
    }

    @Test
    fun `重复片内容相同幂等跳过`() {
        val (_, first, _) = twoPiece("a", "b")
        reassembler.feed(first)
        val again = reassembler.feed(first)
        assertTrue(again is WireReassembler.Outcome.Incomplete)
    }

    @Test
    fun `重复片内容不同报冲突`() {
        val (_, first, _) = twoPiece("a", "b")
        reassembler.feed(first)
        // 官方语义：元数据全等（含 checksum）是重复片冲突检查的前置——
        // checksum 变化属于 metadataMismatch（另有用例覆盖）。此处只有 payload 不同。
        val conflicting = frag(id = "lf-1", index = 0, count = 2, logicalBytes = 2, checksum = crcOf("ab"), payload = "ZZ")
        val r = reassembler.feed(conflicting)
        assertEquals("proto.frameAssemblyFragmentConflict", (r as WireReassembler.Outcome.Rejected).reasonCode)
    }

    @Test
    fun `crc不匹配报校验错`() {
        val wrong = "00000000"
        reassembler.feed(frag(id = "lf-x", index = 0, count = 2, logicalBytes = 2, checksum = wrong, payload = "a"))
        val r = reassembler.feed(frag(id = "lf-x", index = 1, count = 2, logicalBytes = 2, checksum = wrong, payload = "b"))
        assertEquals("proto.frameAssemblyChecksumMismatch", (r as WireReassembler.Outcome.Rejected).reasonCode)
    }

    @Test
    fun `元数据变化报不匹配`() {
        val (_, first, _) = twoPiece("a", "b")
        reassembler.feed(first)
        val changed = frag(id = "lf-1", index = 1, count = 5, logicalBytes = 2, checksum = crcOf("ab"), payload = "b")
        val r = reassembler.feed(changed)
        assertEquals("proto.frameAssemblyMetadataMismatch", (r as WireReassembler.Outcome.Rejected).reasonCode)
    }

    @Test
    fun `fragmentIndex越界报不匹配`() {
        val r =
            reassembler.feed(
                frag(id = "lf-y", index = 2, count = 2, logicalBytes = 4, checksum = crcOf("ab"), payload = "ab"),
            )
        assertEquals("proto.frameAssemblyMetadataMismatch", (r as WireReassembler.Outcome.Rejected).reasonCode)
    }

    @Test
    fun `片数超官方1024上限拒绝`() {
        val r =
            reassembler.feed(
                frag(id = "lf-z", index = 0, count = 2000, logicalBytes = 2000, checksum = crcOf("a"), payload = "a"),
            )
        assertEquals("proto.frameFragmentCountExceeded", (r as WireReassembler.Outcome.Rejected).reasonCode)
    }

    @Test
    fun `重组后frame信封不匹配报错`() {
        // 片内 JSON 的 topic 与 wire 信封 topic 不一致（官方 complete 专属校验在重组后同样执行）
        val body = """{"topic":"conversation/other","subscriptionId":"sub-1"}"""
        val crc = crcOf(body)
        val r =
            reassembler.feed(
                frag(
                    id = "lf-m",
                    index = 0,
                    count = 1,
                    logicalBytes = body.length,
                    checksum = crc,
                    payload = body,
                    topic = "conversation/sess_a",
                ),
            )
        assertEquals("proto.frameAssemblyMetadataMismatch", (r as WireReassembler.Outcome.Rejected).reasonCode)
    }

    @Test
    fun `非法base64拒绝`() {
        val r =
            reassembler.feed(
                buildJsonObject {
                    put("kind", "fragment")
                    put("logicalFrameId", "lf-b64")
                    put("logicalFrameOrdinal", 1)
                    put("topic", "conversation/sess_a")
                    put("subscriptionId", "sub-1")
                    put("deliveryKind", "online")
                    put("fragmentIndex", 0)
                    put("fragmentCount", 1)
                    put("logicalBytes", 2)
                    put(
                        "checksum",
                        buildJsonObject {
                            put("algorithm", "crc32")
                            put("value", "00000000")
                        },
                    )
                    put("dataBase64", "!!!not-base64!!!")
                },
            )
        assertEquals("proto.frameAssemblyInvalidBase64", (r as WireReassembler.Outcome.Rejected).reasonCode)
    }

    @Test
    fun `非fragment帧返回Ignored`() {
        val complete =
            buildJsonObject {
                put("kind", "complete")
                put("logicalFrameId", "lf-c")
            }
        assertEquals(WireReassembler.Outcome.Ignored, reassembler.feed(complete))
    }

    @Test
    fun `staging满员驱逐最旧`() {
        val tight = WireReassembler(maxOpenAssemblies = 2, nowMs = { 1_000L })
        val crc = crcOf("ab")
        tight.feed(frag(id = "lf-a", index = 0, count = 2, logicalBytes = 2, checksum = crc, payload = "a"))
        tight.feed(frag(id = "lf-b", index = 0, count = 2, logicalBytes = 2, checksum = crc, payload = "a"))
        tight.feed(frag(id = "lf-c", index = 0, count = 2, logicalBytes = 2, checksum = crc, payload = "a"))
        // lf-a 已被驱逐：再喂它的第二片 → 当作新 assembly（缺片等待）
        val r = tight.feed(frag(id = "lf-a", index = 1, count = 2, logicalBytes = 2, checksum = crc, payload = "b"))
        assertTrue(r is WireReassembler.Outcome.Incomplete)
    }

    @Test
    fun `超时assembly被清除`() {
        var clock = 1_000L
        val timed = WireReassembler(nowMs = { clock })
        val crc = crcOf("ab")
        timed.feed(frag(id = "lf-t", index = 0, count = 2, logicalBytes = 2, checksum = crc, payload = "a"))
        clock += WireReassembler.ASSEMBLY_TIMEOUT_MS + 1
        val r = timed.feed(frag(id = "lf-t", index = 0, count = 2, logicalBytes = 2, checksum = crc, payload = "a"))
        assertTrue(r is WireReassembler.Outcome.Incomplete)
    }

    @Test
    fun `reset清空staging`() {
        val crc = crcOf("ab")
        reassembler.feed(frag(id = "lf-r", index = 0, count = 2, logicalBytes = 2, checksum = crc, payload = "a"))
        reassembler.reset()
        val r = reassembler.feed(frag(id = "lf-r", index = 0, count = 2, logicalBytes = 2, checksum = crc, payload = "a"))
        assertTrue(r is WireReassembler.Outcome.Incomplete)
    }

    @Test
    fun `UTF8严格解码非法序列拒绝`() {
        // 0xC0 0x80 是非法 UTF-8（超长编码）
        val bad = byteArrayOf(0xC0.toByte(), 0x80.toByte())
        val crc = crc32Hex(bad)
        val b64 =
            java.util.Base64
                .getEncoder()
                .encodeToString(bad)
        val r =
            reassembler.feed(
                Json
                    .parseToJsonElement(
                        """{"kind":"fragment","logicalFrameId":"lf-u2","logicalFrameOrdinal":1,
                           "topic":"conversation/sess_a","subscriptionId":"sub-1","deliveryKind":"online",
                           "fragmentIndex":0,"fragmentCount":1,"logicalBytes":2,
                           "checksum":{"algorithm":"crc32","value":"$crc"},
                           "dataBase64":"$b64"}""",
                    ).let { it as JsonObject },
            )
        assertEquals("proto.frameAssemblyInvalidUtf8", (r as WireReassembler.Outcome.Rejected).reasonCode)
    }
}
