package com.zcode.remote.relay

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V2 握手帧构造（PROTOCOL.md §11，2026-10-25 ws2 直连 matched 实证）：
 * auth_init 无 role / client_ts 秒 / 心跳无 device_sid / ws2 端点推导。
 */
class V2HandshakeTest {
    @Test
    fun authInitV2_omitsRole_andUsesSeconds() {
        val f = RelayProtocol.authInitV2("d_abc", "0.5.0-beta51").jsonObject
        assertFalse("V2 auth_init 不得携带 role（strictObject 拒未知字段）", f.containsKey("role"))
        assertEquals("d_abc", f["device_sid"]!!.jsonPrimitive.content)
        val ts = f["client_ts"]!!.jsonPrimitive.content.toLong()
        assertTrue("client_ts 必须是秒级（毫秒会超 schema 上限）", ts in 1_700_000_000L..2_534_023_007L)
        val meta = f["meta"]!!.jsonObject
        assertEquals("android", meta["platform"]!!.jsonPrimitive.content)
        assertEquals("0.5.0-beta51", meta["version"]!!.jsonPrimitive.content)
    }

    @Test
    fun heartbeatV2_hasNoDeviceSid() {
        val f = RelayProtocol.heartbeatV2().jsonObject
        assertEquals("pair_status_query", f["type"]!!.jsonPrimitive.content)
        assertFalse("V2 心跳不得携带 device_sid", f.containsKey("device_sid"))
        val ts = f["client_ts"]!!.jsonPrimitive.content.toLong()
        assertTrue(ts in 1_700_000_000L..2_534_023_007L)
    }

    @Test
    fun v1AuthInit_keepsRoleForLegacyRelay() {
        val f = RelayProtocol.authInit("d_abc", "0.5.0").jsonObject
        assertEquals("terminal", f["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun v2RelayWsUrl_derivedFromRemoteUrlOrigin() {
        assertEquals(
            "wss://zcode.z.ai/api/v1/remote/ws2",
            RelayProtocol.v2RelayWsUrl("https://zcode.z.ai/remote/v4?sid=d_abc&mid=m"),
        )
        assertEquals(
            "wss://zcode.chatglm.site/api/v1/remote/ws2",
            RelayProtocol.v2RelayWsUrl("https://zcode.chatglm.site/remote/v4"),
        )
        // 非法输入回落官方主端点
        assertEquals(
            "wss://zcode.z.ai/api/v1/remote/ws2",
            RelayProtocol.v2RelayWsUrl("not a url"),
        )
    }
}
