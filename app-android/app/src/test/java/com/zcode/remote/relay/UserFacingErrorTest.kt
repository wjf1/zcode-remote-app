package com.zcode.remote.relay

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * [UserFacingError] 的纯函数单测（C-2 错误分层映射）。
 *
 * 这些文案会原样出现在横幅与顶栏（「发送失败：响应超时，请检查网络后重试」），
 * 而 `bridge not ready` / `timeout after 15000ms` 这类裸英文出现在 UI 上是
 * 任务书 §7 明确禁止的（对应 grep 断言）。映射判错 = 用户看到天书。
 */
class UserFacingErrorTest {

    private fun faultJson(code: String): JsonObject = buildJsonObject {
        put("code", code)
        put("message", "raw server message")
    }

    @Test
    fun mapsLocalChannelEnglishToChinese() {
        assertEquals("连接通道尚未就绪，请稍后重试", UserFacingError.map("bridge not ready"))
        assertEquals("响应超时，请检查网络后重试", UserFacingError.map("timeout after 15000ms"))
        assertEquals("连接已重建，请重试", UserFacingError.map("bridge re-established"))
        assertEquals("连接已重置，请重试", UserFacingError.map("channel reset"))
    }

    @Test
    fun mapsServerFaultCode_fromRawReply() {
        assertEquals(
            "连接尚未完成握手，请重试",
            UserFacingError.map(null, faultJson("fault.connection.handshakeRequired")),
        )
        assertEquals(
            "会话身份不匹配，请重新进入会话",
            UserFacingError.map("whatever", faultJson("fault.command.clientMismatch")),
        )
    }

    @Test
    fun mapsFaultNestedUnderFaultKey() {
        val nested = buildJsonObject {
            put("fault", buildJsonObject { put("reasonCode", "fault.connection.handshakeRequired") })
        }
        assertEquals("连接尚未完成握手，请重试", UserFacingError.map(null, nested))
    }

    @Test
    fun unknownFaultCodeKeepsChinesePrefixAndCode() {
        assertEquals(
            "服务端拒绝了该操作（fault.weird.unknownThing）",
            UserFacingError.map(null, faultJson("fault.weird.unknownThing")),
        )
    }

    @Test
    fun chineseMessagesPassThroughUntouched() {
        assertEquals("未订阅会话，无法发送", UserFacingError.map("未订阅会话，无法发送"))
        assertEquals("握手超时，请检查网络后重试", UserFacingError.map("握手超时，请检查网络后重试"))
    }

    @Test
    fun networkEnglishMapsToChinese() {
        assertEquals("无法解析服务器地址，请检查网络", UserFacingError.map("Unable to resolve host \"api.github.com\""))
        assertEquals("连接超时，请检查网络后重试", UserFacingError.map("connect timed out"))
    }

    @Test
    fun blankMessageFallsBack() {
        assertEquals(UserFacingError.FALLBACK, UserFacingError.map(null))
        assertEquals(UserFacingError.FALLBACK, UserFacingError.map("   "))
    }

    @Test
    fun noRawEnglishLeaksForKnownInputs() {
        // §7 异常的 grep 断言对应：已知输入经映射后不得再出现自造英文关键串
        val inputs = listOf(
            "bridge not ready", "timeout after 15000ms", "bridge re-established",
            "channel reset", "Unable to resolve host", "failed to connect", "connect timed out",
        )
        val forbidden = listOf("bridge not ready", "timeout after", "channel reset", "Unable to")
        inputs.forEach { input ->
            val out = UserFacingError.map(input)
            forbidden.forEach { word ->
                assertFalse("映射输出泄漏英文串「$word」：$out", out.contains(word, ignoreCase = true))
            }
        }
    }
}
