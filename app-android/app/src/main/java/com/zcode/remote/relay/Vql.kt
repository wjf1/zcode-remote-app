package com.zcode.remote.relay

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream

/**
 * ZCode 自有 RPC 序列化器（tag 化二进制，非 protobuf）。
 * 规格见 research/FRAME-CODEC.md §3.3（逆向自 PC 端 chunk-X2DDW7XG.js 的 serialize/deserialize）。
 *
 * 格式：tag(1B) + ULEB128 varint 长度 + payload
 * tag: Undefined=0 / String=1 / Buffer=2 / VSBuffer=3 / Array=4 / Object(JSON)=5 / Int=6
 */
object Vql {
    private const val T_UNDEFINED = 0
    private const val T_STRING = 1
    private const val T_BUFFER = 2
    private const val T_VSBUFFER = 3
    private const val T_ARRAY = 4
    private const val T_OBJECT = 5
    private const val T_INT = 6

    // ---------- encode ----------

    fun serialize(value: Any?): ByteArray {
        val out = ByteArrayOutputStream()
        write(out, value)
        return out.toByteArray()
    }

    /** 连续序列化多个值（RPC 报文 = 头部数组 ++ 参数）。 */
    fun serialize(vararg values: Any?): ByteArray {
        val out = ByteArrayOutputStream()
        values.forEach { write(out, it) }
        return out.toByteArray()
    }

    private fun write(out: ByteArrayOutputStream, v: Any?) {
        when (v) {
            null -> out.write(T_UNDEFINED)
            is String -> {
                out.write(T_STRING); writeVarint(out, utf8(v).size); out.writeBytes(utf8(v))
            }
            is ByteArray -> {
                out.write(T_BUFFER); writeVarint(out, v.size); out.writeBytes(v)
            }
            is List<*> -> {
                out.write(T_ARRAY); writeVarint(out, v.size)
                v.forEach { write(out, it) }
            }
            is JsonElement -> writeJson(out, v)
            is Map<*, *> -> writeJson(out, toJson(v))
            is Int, is Long -> {
                // 仅 int32 范围的整数走 Int tag（与官方 (e|0)===e 判定一致），其余降级为 JSON
                val n = (v as Number).toLong()
                if (n >= Int.MIN_VALUE && n <= Int.MAX_VALUE) {
                    out.write(T_INT); writeVarint(out, n.toInt())
                } else writeJson(out, JsonPrimitive(n))
            }
            is Boolean, is Double, is Float -> writeJson(out, toJsonPrimitive(v))
            else -> writeJson(out, JsonPrimitive(v.toString()))
        }
    }

    private fun writeJson(out: ByteArrayOutputStream, el: JsonElement) {
        val b = utf8(el.toString())
        out.write(T_OBJECT); writeVarint(out, b.size); out.writeBytes(b)
    }

    private fun utf8(s: String) = s.toByteArray(Charsets.UTF_8)

    private fun toJsonPrimitive(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        else -> JsonPrimitive(v.toString())
    }

    private fun toJson(m: Map<*, *>): JsonElement = JsonObject(
        m.entries.associate { (k, v) -> k.toString() to toJsonValue(v) }
    )

    private fun toJsonValue(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is Map<*, *> -> toJson(v)
        is List<*> -> JsonArray(v.map { toJsonValue(it) })
        else -> toJsonPrimitive(v)
    }

    /** ULEB128（官方 writeInt32VQL：uint32 语义）。 */
    private fun writeVarint(out: ByteArrayOutputStream, value: Int) {
        var v = value
        if (v == 0) { out.write(0); return }
        while (v != 0) {
            val b = v and 0x7F
            v = v ushr 7
            out.write(if (v > 0) b or 0x80 else b)
        }
    }

    // ---------- decode ----------

    private class Reader(val buf: ByteArray) {
        var pos = 0
        fun byte(): Int = buf[pos++].toInt() and 0xFF
        fun take(n: Int): ByteArray {
            val r = buf.copyOfRange(pos, pos + n); pos += n; return r
        }
        fun varint(): Int {
            var result = 0; var shift = 0
            while (true) {
                val b = byte()
                result = result or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            return result
        }
    }

    fun deserialize(buf: ByteArray, offset: Int = 0): Decoded {
        val r = Reader(buf)
        r.pos = offset
        val v = read(r)
        return Decoded(v, r.pos)
    }

    data class Decoded(val value: Any?, val nextOffset: Int)

    private fun read(r: Reader): Any? = when (val tag = r.byte()) {
        T_UNDEFINED -> null
        T_STRING -> String(r.take(r.varint()), Charsets.UTF_8)
        T_BUFFER, T_VSBUFFER -> r.take(r.varint())
        T_ARRAY -> { val n = r.varint(); (0 until n).map { read(r) } }
        T_OBJECT -> RelayProtocol.json.parseToJsonElement(String(r.take(r.varint()), Charsets.UTF_8))
        T_INT -> r.varint()
        else -> throw IllegalArgumentException("unknown vql tag: $tag")
    }
}
