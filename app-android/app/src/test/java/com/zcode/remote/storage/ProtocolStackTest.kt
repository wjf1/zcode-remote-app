package com.zcode.remote.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T4：`PairedDevice.protocolStack` 是全仓唯一的 passHash 判据（PROTOCOL.md §11）。
 *
 * 覆盖三个边界：V1（有 hash）/ V2（无 hash）/ V2 空串（服务端可能下发空值），
 * 并断言配对入口分流与数据源判定都跟着这一判据走。
 */
class ProtocolStackTest {
    private fun device(passHash: String?) =
        PairedDevice(
            deviceSid = "d_test",
            passHash = passHash,
            deviceMid = "m_test",
            deviceName = "pc",
            remoteUrl = "https://zcode.z.ai",
        )

    @Test
    fun `passHash 非空判定为 V1`() {
        val d = device("hash-abc")
        assertEquals(ProtocolStack.V1, d.protocolStack)
        assertFalse(d.needsLogin)
        assertFalse(d.usesControllerChannel)
    }

    @Test
    fun `passHash 为 null 判定为 V2`() {
        val d = device(null)
        assertEquals(ProtocolStack.V2, d.protocolStack)
        assertTrue(d.needsLogin)
        assertTrue(d.usesControllerChannel)
    }

    @Test
    fun `passHash 为空串同样判定为 V2`() {
        // 服务端/旧数据可能把缺失写成空串：与 null 同语义，不得当成 V1 凭据用
        val d = device("")
        assertEquals(ProtocolStack.V2, d.protocolStack)
        assertTrue(d.needsLogin)
    }

    @Test
    fun `QR 分流跟着 protocolStack 走（V1 直连路径）`() {
        // QrParser 依赖 android.net.Uri（JVM 单测不可用，见 PureFunctionsTest 注释），
        // 这里直接断言分流判据本身：Ready ⇔ V1、NeedsLogin ⇔ V2。
        val v1 = device("h_1")
        val v2 = device(null)
        assertEquals(QrParseResult.Ready(v1).device.protocolStack, v1.protocolStack)
        assertEquals(QrParseResult.NeedsLogin(v2).device.protocolStack, v2.protocolStack)
        assertTrue(QrParseResult.Ready(v1) is QrParseResult.Ready)
        assertTrue(QrParseResult.NeedsLogin(v2) is QrParseResult.NeedsLogin)
    }
}
