package com.zcode.remote.relay

import com.zcode.remote.relay.RelayEndpointValidator.Reason
import com.zcode.remote.relay.RelayEndpointValidator.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 中继端点校验单测（任务书 §11.3.1 验收：ws/http、userinfo、无 host、IPv6、localhost、
 * 私网、异常端口、大小写变体）。
 */
class RelayEndpointValidatorTest {

    private fun rejected(url: String?, allowInsecure: Boolean = false): Reason {
        val r = RelayEndpointValidator.validate(url, allowInsecure)
        assertTrue("期望拒绝但通过：$url -> $r", r is Result.Rejected)
        return (r as Result.Rejected).reason
    }

    private fun ok(url: String, allowInsecure: Boolean = false) {
        val r = RelayEndpointValidator.validate(url, allowInsecure)
        assertEquals("期望通过但被拒：$url -> $r", Result.Ok(url.trim()), r)
    }

    // ---------- 放行 ----------

    @Test
    fun acceptsAbsoluteWss() = ok("wss://zcode.z.ai/ws")

    @Test
    fun acceptsWssWithoutPath() = ok("wss://zcode.z.ai")

    @Test
    fun acceptsUppercaseScheme() = ok("WSS://zcode.z.ai/ws")

    @Test
    fun acceptsWssWithExplicitPort() = ok("wss://internal.example:8443/relay")

    @Test
    fun acceptsWssIpv6Loopback() = ok("wss://[::1]:8443/ws")

    @Test
    fun acceptsWssPrivateIp() = ok("wss://192.168.1.10:9443/ws")

    @Test
    fun acceptsWssLocalhost() = ok("wss://localhost:9443/ws")

    @Test
    fun trimsSurroundingWhitespace() {
        val r = RelayEndpointValidator.validate("  wss://zcode.z.ai/ws  ", allowInsecure = false)
        assertEquals(Result.Ok("wss://zcode.z.ai/ws"), r)
    }

    // ---------- 拒绝：明文 / scheme ----------

    @Test
    fun rejectsInsecureWsInRelease() =
        assertEquals(Reason.INSECURE_REJECTED, rejected("ws://10.0.0.2:8080/ws"))

    @Test
    fun allowsInsecureWsOnlyWhenDebug() = ok("ws://10.0.0.2:8080/ws", allowInsecure = true)

    @Test
    fun rejectsHttpScheme() = assertEquals(Reason.SCHEME_NOT_ALLOWED, rejected("https://zcode.z.ai/ws"))

    @Test
    fun rejectsBareHttp() = assertEquals(Reason.SCHEME_NOT_ALLOWED, rejected("http://zcode.z.ai"))

    @Test
    fun rejectsCustomScheme() = assertEquals(Reason.SCHEME_NOT_ALLOWED, rejected("zcode://pair"))

    @Test
    fun rejectsSchemePrefixLookalike() =
        assertEquals(Reason.SCHEME_NOT_ALLOWED, rejected("wssx://evil/ws"))

    // ---------- 拒绝：结构 ----------

    @Test
    fun rejectsBlank() = assertEquals(Reason.BLANK, rejected("   "))

    @Test
    fun rejectsNull() = assertEquals(Reason.BLANK, rejected(null))

    @Test
    fun rejectsRelativePath() = assertEquals(Reason.NOT_ABSOLUTE, rejected("/ws"))

    @Test
    fun rejectsSchemeRelative() = assertEquals(Reason.NOT_ABSOLUTE, rejected("//zcode.z.ai/ws"))

    @Test
    fun rejectsUserinfo() =
        assertEquals(Reason.USERINFO_PRESENT, rejected("wss://user:pass@zcode.z.ai/ws"))

    @Test
    fun rejectsUserinfoAtOnly() =
        assertEquals(Reason.USERINFO_PRESENT, rejected("wss://evil@zcode.z.ai/ws"))

    @Test
    fun rejectsMissingHost() = assertEquals(Reason.MISSING_HOST, rejected("wss:///ws"))

    @Test
    fun rejectsPortOutOfRange() = assertEquals(Reason.PORT_INVALID, rejected("wss://zcode.z.ai:99999/ws"))

    @Test
    fun rejectsZeroPort() = assertEquals(Reason.PORT_INVALID, rejected("wss://zcode.z.ai:0/ws"))

    @Test
    fun isAcceptableMirrorsValidate() {
        assertTrue(RelayEndpointValidator.isAcceptable("wss://zcode.z.ai/ws", allowInsecure = false))
        assertTrue(!RelayEndpointValidator.isAcceptable("ws://zcode.z.ai/ws", allowInsecure = false))
        assertTrue(RelayEndpointValidator.isAcceptable("ws://zcode.z.ai/ws", allowInsecure = true))
    }
}
