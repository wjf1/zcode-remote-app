package com.zcode.remote.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 多机凭据文件（`devices_v2`）的编解码（C-8）。
 *
 * 原实现手拼 JSON（`buildString` + `replace`）且转义只覆盖 `\` 与 `"`——设备名/URL 里
 * 若出现换行、制表或其它控制字符就会写出损坏的 JSON；解析端用正则 + `split` 同样脆弱。
 * 现改为 kotlinx.serialization，字段结构同时从「0x01 拼接行」升级为结构化对象。
 *
 * **旧格式兼容**：v2 旧数据形如
 * `{"active":"…","list":["sid\u0001hash\u0001mid\u0001name\u0001url", …]}`，
 * 字符串内嵌的 0x01 控制字符**未转义（非法 JSON）**，kotlinx 解析器读不了，故保留
 * [decodeLegacy] 分支；读到旧格式后调用方会立即以新格式重写（一次性迁移）。
 *
 * 纯函数、零 Android 依赖（不含 Keystore/SharedPreferences），可直接进 JVM 单测。
 */
internal object DevicesCodec {

    @Serializable
    internal data class File(
        val active: String? = null,
        val list: List<Entry> = emptyList(),
    )

    @Serializable
    internal data class Entry(
        val sid: String,
        val hash: String,
        val mid: String? = null,
        val name: String? = null,
        val url: String,
        val pairedAtMs: Long = 0L,
    )

    private val json = Json {
        ignoreUnknownKeys = true   // 未来加字段时旧版仍能读
        encodeDefaults = true
    }

    /** 编码为新格式（转义由 kotlinx 保证：引号 / 反斜杠 / 换行 / 控制字符全部正确转义）。 */
    fun encode(devices: List<PairedDevice>, activeSid: String?): String =
        json.encodeToString(
            File.serializer(),
            File(
                active = activeSid ?: devices.firstOrNull()?.deviceSid,
                list = devices.map { d ->
                    Entry(d.deviceSid, d.passHash, d.deviceMid, d.deviceName, d.remoteUrl, d.pairedAtMs)
                },
            ),
        )

    /** 新格式解析；失败返回 null（调用方再试 [decodeLegacy]）。 */
    fun decode(raw: String): Pair<List<PairedDevice>, String?>? =
        runCatching {
            val f = json.decodeFromString(File.serializer(), raw)
            f.list.map { it.toDevice() } to f.active?.takeIf { it.isNotBlank() }
        }.getOrNull()

    /** 旧格式（C-8 之前的手写行编码）解析；失败返回 null。 */
    fun decodeLegacy(raw: String): Pair<List<PairedDevice>, String?>? =
        runCatching {
            val active = Regex("\"active\":\"([^\"]*)\"").find(raw)?.groupValues?.get(1)
            val listPart = raw.substringAfter("\"list\":[", "").substringBeforeLast("]")
            val devices = listPart.split("\",\"").mapNotNull { chunk ->
                val line = chunk.trim().removePrefix("\"").removeSuffix("\"")
                    .replace("\\\"", "\"").replace("\\\\", "\\")
                if (line.isBlank()) return@mapNotNull null
                val p = line.split("\u0001")
                if (p.size < 5) return@mapNotNull null
                PairedDevice(p[0], p[1], p[2].ifEmpty { null }, p[3].ifEmpty { null }, p[4])
            }
            devices to active?.ifEmpty { null }
        }.getOrNull()

    private fun Entry.toDevice() = PairedDevice(
        deviceSid = sid,
        passHash = hash,
        deviceMid = mid?.takeIf { it.isNotEmpty() },
        deviceName = name?.takeIf { it.isNotEmpty() },
        remoteUrl = url,
        pairedAtMs = pairedAtMs,
    )
}
