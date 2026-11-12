package com.zcode.remote.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 出站物理分片（§11.5.2，1.0 硬门收官）。
 *
 * 算法依据：与入站对拍 fixture（`tools/gen_rpc_frame_fixtures.py` 的 `slices()`——
 * 独立 Python 实现的官方等长切分）同一算法：每片 [RpcChannel.OUTBOUND_FRAGMENT_PAYLOAD_BYTES]
 * 字节、最后一片可能更短；所有片拼接必须还原原消息。
 */
class OutboundFragmentationTest {
    private val per = RpcChannel.OUTBOUND_FRAGMENT_PAYLOAD_BYTES

    @Test
    fun `空消息返回单空片`() {
        val slices = RpcChannel.outboundSlices(ByteArray(0), per)
        assertEquals(1, slices.size)
        assertEquals(0, slices[0].size)
    }

    @Test
    fun `短消息不分片`() {
        val msg = ByteArray(1024) { it.toByte() }
        val slices = RpcChannel.outboundSlices(msg, per)
        assertEquals(1, slices.size)
        assertTrue(msg.contentEquals(slices[0]))
    }

    @Test
    fun `恰好一片大小不分片`() {
        val msg = ByteArray(per) { (it % 251).toByte() }
        val slices = RpcChannel.outboundSlices(msg, per)
        assertEquals(1, slices.size)
        assertTrue(msg.contentEquals(slices[0]))
    }

    @Test
    fun `超一片一字节切两片且第二片仅一字节`() {
        val msg = ByteArray(per + 1) { (it % 7).toByte() }
        val slices = RpcChannel.outboundSlices(msg, per)
        assertEquals(2, slices.size)
        assertEquals(per, slices[0].size)
        assertEquals(1, slices[1].size)
        // 拼接还原
        val joined = slices[0] + slices[1]
        assertTrue(msg.contentEquals(joined))
    }

    @Test
    fun `多片等长切分拼接还原`() {
        // 3.5 片大小
        val size = per * 3 + per / 2
        val msg = ByteArray(size) { ((it * 31) % 251).toByte() }
        val slices = RpcChannel.outboundSlices(msg, per)
        assertEquals(4, slices.size)
        assertEquals("前 3 片等长", per, slices[0].size)
        assertEquals(per, slices[1].size)
        assertEquals(per, slices[2].size)
        assertEquals("末片为余数", size - per * 3, slices[3].size)
        val joined = slices.reduce { a, b -> a + b }
        assertTrue("拼接必须还原原消息", msg.contentEquals(joined))
    }

    @Test
    fun `16MiB 上限消息片数不超官方 64 片上限`() {
        val size = ProtocolLimits.MAX_MESSAGE_BYTES
        val count = (size + per - 1) / per
        assertTrue(
            "16 MiB / 700KB = 24 片，必须 ≤ 官方 fragmentCount 上限 64",
            count <= ProtocolLimits.MAX_FRAGMENTS,
        )
    }

    @Test
    fun `非法per拒绝`() {
        val e =
            org.junit.Assert.assertThrows(
                IllegalArgumentException::class.java,
            ) { RpcChannel.outboundSlices(ByteArray(10), 0) }
        assertEquals("per 必须为正", e.message)
    }
}
