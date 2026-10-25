package com.zcode.remote.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [DevicesCodec] 的纯函数单测（C-8 多机凭据持久化）。
 *
 * 这组用例同时钉死「新旧两种格式都读得回」——存量设备凭据读丢 = 用户被迫重新配对，
 * 是本地存储层最不可接受的回归。SharedPreferences/Keystore 本身无法在 JVM 测，
 * 故编解码全部收敛到这个纯对象里。
 */
class DevicesCodecTest {
    private fun device(
        sid: String,
        name: String? = "PC-1",
        mid: String? = "mid-1",
    ) = PairedDevice(
        deviceSid = sid,
        passHash = "hash+/=$sid",
        deviceMid = mid,
        deviceName = name,
        remoteUrl = "https://zcode.z.ai/remote/v4",
        pairedAtMs = 1234567890L,
    )

    @Test
    fun roundTrip_preservesSpecialCharacters() {
        // 设备名来自配对链接（用户可自定义）：引号、反斜杠、制表、换行、0x01、中文都可能出现
        val tricky = device("d_1", name = "我的\"PC\"\\ 机\t\n名\u0001尾")
        val encoded = DevicesCodec.encode(listOf(tricky), "d_1")
        val decoded = DevicesCodec.decode(encoded)
        assertEquals(listOf(tricky), decoded?.first)
        assertEquals("d_1", decoded?.second)
    }

    @Test
    fun roundTrip_preservesMultipleDevicesAndOrder() {
        val list = listOf(device("d_1"), device("d_2", name = null, mid = null), device("d_3"))
        val decoded = DevicesCodec.decode(DevicesCodec.encode(list, "d_2"))
        assertEquals(list, decoded?.first)
        assertEquals("d_2", decoded?.second)
    }

    @Test
    fun encode_defaultsActiveToFirstDevice() {
        val list = listOf(device("d_1"), device("d_2"))
        assertEquals("d_1", DevicesCodec.decode(DevicesCodec.encode(list, null))?.second)
    }

    @Test
    fun decode_legacyFormatWithRawControlChar() {
        // 旧格式样本：0x01 是**原始控制字符**（旧代码 joinToString("\u0001") 的产物，
        // 未按 JSON 规范转义——注意 raw string 里的 "\u0001" 只是 6 个字面字符，
        // 必须用普通字符串拼接出真正的 0x01）——只有 decodeLegacy 能读它。
        val sep = "\u0001"
        val legacy = "{\"active\":\"d_9\",\"list\":[\"d_9${sep}hash9${sep}mid9${sep}PC-9${sep}https://zcode.z.ai/remote/v4\"]}"
        val decoded = DevicesCodec.decodeLegacy(legacy)
        assertEquals(1, decoded?.first?.size)
        assertEquals("d_9", decoded?.first?.first()?.deviceSid)
        assertEquals("PC-9", decoded?.first?.first()?.deviceName)
        assertEquals("d_9", decoded?.second)
        // 新格式解析器读不了它（list 元素是字符串而非结构化对象）——这正是保留 legacy 分支的原因
        assertNull(DevicesCodec.decode(legacy))
    }

    @Test
    fun decode_garbageReturnsNull() {
        assertNull(DevicesCodec.decode("not json at all"))
    }

    @Test
    fun decode_legacyEmptyList() {
        val decoded = DevicesCodec.decodeLegacy("""{"active":"","list":[]}""")
        assertEquals(emptyList<PairedDevice>(), decoded?.first)
        assertNull(decoded?.second)
    }

    // ---- V2（ZCode ≥3.16，PROTOCOL.md §11）：passHash 可空 ----

    @Test
    fun roundTrip_v2DeviceWithoutPassHash() {
        // V2 新版二维码无 hash：登录配对未补齐凭据前，设备以 passHash=null 持久化
        val v2 =
            PairedDevice(
                deviceSid = "d_v2",
                passHash = null,
                deviceMid = "mid-v2",
                deviceName = "DESKTOP-V2",
                remoteUrl = "https://zcode.z.ai/remote/v4",
                pairedAtMs = 1234567890L,
            )
        val decoded = DevicesCodec.decode(DevicesCodec.encode(listOf(v2), "d_v2"))
        assertEquals(listOf(v2), decoded?.first)
        assertEquals(true, decoded?.first?.first()?.needsLogin)
    }

    @Test
    fun roundTrip_v1AndV2DevicesCoexist() {
        // 老设备（带 hash）与新设备（无 hash）共存；老数据读取路径不受可空化影响
        val v1 = device("d_v1")
        val v2 = device("d_v2").copy(passHash = null)
        val decoded = DevicesCodec.decode(DevicesCodec.encode(listOf(v1, v2), "d_v1"))
        assertEquals(listOf(v1, v2), decoded?.first)
        assertEquals(false, decoded?.first?.first()?.needsLogin)
        assertEquals(true, decoded?.first?.get(1)?.needsLogin)
    }
}
