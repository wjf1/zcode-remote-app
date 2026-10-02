package com.zcode.remote.relay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * VQL 金标准对拍测试（Sprint 6）：Kotlin [Vql] ↔ tools/probe.py（Python 独立实现）。
 *
 * 向量由 `tools/gen_vql_fixtures.py` 生成（见 fixture 内说明）。每个向量做双向断言：
 * ① encode：Kotlin 序列化结果与 Python 编码**字节级一致**；
 * ② decode：Kotlin 反解 Python 编码的字节流，语义（JSON 规范化后）一致。
 * 上游协议若变，重新跑生成脚本后本测试立即暴露两端漂移。
 */
class VqlCrossVectorTest {

    private val json = Json

    private data class Fixture(val cases: List<Case>, val multi: List<Multi>)
    private data class Case(val name: String, val kind: String, val value: JsonElement, val hex: String)
    private data class Multi(val name: String, val values: List<JsonElement>, val hex: String)

    private fun loadFixture(): Fixture {
        val stream = javaClass.classLoader.getResourceAsStream("vql_fixtures.json")
            ?: error("找不到 vql_fixtures.json —— 先跑 tools/gen_vql_fixtures.py")
        val root = json.parseToJsonElement(stream.readBytes().decodeToString()).jsonObject
        return Fixture(
            cases = (root["cases"] as JsonArray).map {
                val o = it as JsonObject
                Case(
                    o["name"]!!.jsonPrimitive.content, o["kind"]!!.jsonPrimitive.content,
                    o["value"]!!, o["hex"]!!.jsonPrimitive.content,
                )
            },
            multi = (root["multi"] as JsonArray).map {
                val o = it as JsonObject
                Multi(o["name"]!!.jsonPrimitive.content, (o["values"] as JsonArray).toList(), o["hex"]!!.jsonPrimitive.content)
            },
        )
    }

    // ---------- helpers ----------

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    /** 按 fixture 的 kind 把 JSON 值构造成 Kotlin 侧应输入 serialize 的原生值。 */
    private fun toKotlinValue(kind: String, el: JsonElement): Any? = when (kind) {
        "null" -> null
        "string" -> (el as JsonPrimitive).content
        "int" -> (el as JsonPrimitive).int
        "bool" -> (el as JsonPrimitive).booleanOrNull == true
        "buffer" -> (el as JsonPrimitive).content.hexToBytes()
        "array", "object" -> toNative(el)
        else -> error("未知 kind: $kind")
    }

    /** JSON → Kotlin 原生嵌套值（Map/List/String/Int/Boolean/null），与 Python dict/list 同构。 */
    private fun toNative(el: JsonElement): Any? = when (el) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            el.isString -> el.content
            el.booleanOrNull != null -> el.booleanOrNull
            else -> el.content.toInt()
        }
        is JsonArray -> el.map { toNative(it) }
        is JsonObject -> el.entries.associate { (k, v) -> k to toNative(v) }
    }

    /** 解码结果 → JSON 规范形态，与 fixture 的 value 对拍（buffer 以 hex 字符串表示，同 fixture）。 */
    private fun normalize(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is String -> JsonPrimitive(v)
        is Int -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is ByteArray -> JsonPrimitive(v.toHex())
        is List<*> -> JsonArray(v.map { normalize(it) })
        is JsonElement -> v
        else -> error("意外解码类型: ${v::class}")
    }

    // ---------- tests ----------

    @Test
    fun encodeMatchesPythonByteForByte() {
        loadFixture().cases.forEach { c ->
            val kotlinInput = toKotlinValue(c.kind, c.value)
            val encoded = Vql.serialize(kotlinInput)
            assertEquals("encode 漂移: ${c.name}", c.hex, encoded.toHex())
        }
    }

    @Test
    fun decodeMatchesPythonSemantics() {
        loadFixture().cases.forEach { c ->
            val decoded = Vql.deserialize(c.hex.hexToBytes())
            assertEquals("decode 漂移: ${c.name}", c.value, normalize(decoded.value))
            assertEquals("decode 应消费整段字节: ${c.name}", c.hex.length / 2, decoded.nextOffset)
        }
    }

    @Test
    fun multiValueConcatenationMatchesPython() {
        loadFixture().multi.forEach { m ->
            val values = m.values.map { toNative(it) }.toTypedArray()
            val encoded = Vql.serialize(*values)
            assertEquals("multi encode 漂移: ${m.name}", m.hex, encoded.toHex())
        }
    }

    @Test
    fun decodeMultiStreamRespectsOffsets() {
        loadFixture().multi.forEach { m ->
            val buf = m.hex.hexToBytes()
            var offset = 0
            m.values.forEach { expected ->
                val dec = Vql.deserialize(buf, offset)
                assertEquals("multi decode 漂移: ${m.name}", expected, normalize(dec.value))
                offset = dec.nextOffset
            }
            assertEquals("multi decode 应消费整段: ${m.name}", buf.size, offset)
        }
    }
}
