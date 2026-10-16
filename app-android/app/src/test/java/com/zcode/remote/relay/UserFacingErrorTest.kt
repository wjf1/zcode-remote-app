package com.zcode.remote.relay

import com.zcode.remote.R
import com.zcode.remote.util.UiText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [UserFacingError] 的纯函数单测（C-2 错误分层映射）。
 *
 * 这些文案会原样出现在横幅与顶栏（「发送失败：响应超时，请检查网络后重试」），
 * 而 `bridge not ready` / `timeout after 15000ms` 这类裸英文出现在 UI 上是
 * 任务书 §7 明确禁止的（对应 grep 断言）。映射判错 = 用户看到天书。
 *
 * 国际化（第 3 批）：返回值由 `String` 改为 [UiText]，故断言从「比较中文字面量」
 * 改为「比较资源 id + 参数」——**语义等价**（同一映射表），且不再依赖中文字面量
 * （改文案不必动测试）。已是中文的业务语义与未知错误走 [UiText.Raw]。
 */
class UserFacingErrorTest {
    private fun faultJson(code: String): JsonObject =
        buildJsonObject {
            put("code", code)
            put("message", "raw server message")
        }

    @Test
    fun mapsLocalChannelEnglishToChinese() {
        assertEquals(UiText.Res(R.string.error_local_bridge_not_ready), UserFacingError.map("bridge not ready"))
        assertEquals(UiText.Res(R.string.error_local_timeout), UserFacingError.map("timeout after 15000ms"))
        assertEquals(
            UiText.Res(R.string.error_local_bridge_reestablished),
            UserFacingError.map("bridge re-established"),
        )
        assertEquals(UiText.Res(R.string.error_local_channel_reset), UserFacingError.map("channel reset"))
    }

    @Test
    fun mapsServerFaultCode_fromRawReply() {
        assertEquals(
            UiText.Res(R.string.error_fault_handshake_required),
            UserFacingError.map(null, faultJson("fault.connection.handshakeRequired")),
        )
        assertEquals(
            UiText.Res(R.string.error_fault_client_mismatch),
            UserFacingError.map("whatever", faultJson("fault.command.clientMismatch")),
        )
    }

    @Test
    fun mapsFaultNestedUnderFaultKey() {
        val nested =
            buildJsonObject {
                put("fault", buildJsonObject { put("reasonCode", "fault.connection.handshakeRequired") })
            }
        assertEquals(UiText.Res(R.string.error_fault_handshake_required), UserFacingError.map(null, nested))
    }

    @Test
    fun unknownFaultCodeKeepsChinesePrefixAndCode() {
        // 未知 fault：中文前缀资源 + 原 code 作为参数（便于排查，且不直出裸英文段落）
        assertEquals(
            UiText.Res(R.string.error_fault_unknown, listOf("fault.weird.unknownThing")),
            UserFacingError.map(null, faultJson("fault.weird.unknownThing")),
        )
    }

    @Test
    fun chineseMessagesPassThroughAsRaw() {
        // 已是中文的业务语义：原样透出（不参与本地化）
        assertEquals(UiText.Raw("未订阅会话，无法发送"), UserFacingError.map("未订阅会话，无法发送"))
        assertEquals(UiText.Raw("握手超时，请检查网络后重试"), UserFacingError.map("握手超时，请检查网络后重试"))
    }

    @Test
    fun networkEnglishMapsToChinese() {
        assertEquals(
            UiText.Res(R.string.error_local_unable_resolve_host),
            UserFacingError.map("Unable to resolve host \"api.github.com\""),
        )
        assertEquals(UiText.Res(R.string.error_local_timed_out), UserFacingError.map("connect timed out"))
    }

    @Test
    fun blankMessageFallsBack() {
        assertEquals(UserFacingError.FALLBACK, UserFacingError.map(null))
        assertEquals(UserFacingError.FALLBACK, UserFacingError.map("   "))
    }

    /**
     * §7 grep 断言的等价形式：已知英文输入经映射后，结果**必须是资源 [UiText.Res]**
     * （即已本地化），而不得是 [UiText.Raw]（原样透出裸英文）。
     *
     * 注：原先比较的是中文字面量是否含英文子串；改为 UiText 后无法（也不该）在纯函数层
     * 拿到翻译后的文本——「已本地化」这一语义由「返回 Res 而非 Raw」精确表达。
     */
    @Test
    fun knownEnglishInputsAreLocalizedNotRaw() {
        val inputs =
            listOf(
                "bridge not ready",
                "timeout after 15000ms",
                "bridge re-established",
                "channel reset",
                "Unable to resolve host",
                "failed to connect",
                "connect timed out",
            )
        inputs.forEach { input ->
            val out = UserFacingError.map(input)
            assertEquals("「$input」应映射到资源文案（而非原样透出英文）：$out", true, out is UiText.Res)
        }
    }

    /** 兜底文案也必须是资源（可本地化），不是硬编码中文。 */
    @Test
    fun fallbackIsAResource() {
        assertEquals(UiText.Res(R.string.error_fallback), UserFacingError.FALLBACK)
    }
}
