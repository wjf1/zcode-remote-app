package com.zcode.remote.relay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 交互式终端（F5）：bootstrap owner id / create 应答解析 / ANSI 过滤。 */
class TerminalChannelTest {
    @Test
    fun `bootstrap 默认不带终端owner id`() {
        val f = BridgeFrames.bootstrapRequest("boot-1").jsonObject
        assertEquals("老行为兼容：不带 terminalClientSessionId", false, f.containsKey("terminalClientSessionId"))
    }

    @Test
    fun `bootstrap 携带终端owner id 且 bridge-open 同源`() {
        val f = BridgeFrames.bootstrapRequest("boot-1", "android-term-abc").jsonObject
        assertEquals("android-term-abc", f["terminalClientSessionId"]!!.jsonPrimitive.content)
        // bridge-open 的 id 来自 RpcChannel 实例字段——同实例内恒一致由类字段保证，这里钉契约存在
        assertEquals("terminal", RpcChannel.CHANNEL_TERMINAL)
    }

    @Test
    fun `create应答三种形态均可提取id`() {
        assertEquals(
            "t1",
            TerminalChannel.extractTerminalId(
                Json.parseToJsonElement(""""t1""""),
            ),
        )
        assertEquals(
            "t2",
            TerminalChannel.extractTerminalId(
                Json.parseToJsonElement("""{"id":"t2","shell":"powershell"}""").jsonObject,
            ),
        )
        assertEquals(
            "t3",
            TerminalChannel.extractTerminalId(
                Json.parseToJsonElement("""{"result":{"id":"t3"}}""").jsonObject,
            ),
        )
        assertNull(TerminalChannel.extractTerminalId(null))
        assertNull(TerminalChannel.extractTerminalId(Json.parseToJsonElement("{}")))
    }

    @Test
    fun `stripAnsi 剥离CSI与OSC保留正文`() {
        assertEquals(
            "hello world",
            TerminalChannel.stripAnsi("\u001B[31mhello\u001B[0m \u001B[1mworld\u001B[0m"),
        )
        // OSC（ESC ] 0 ; title BEL）是「设置窗口标题」控制序列——整段剥除，只剩后续正文
        assertEquals(
            "text",
            TerminalChannel.stripAnsi("\u001B]0;title\u0007text"),
        )
        assertEquals(
            "plain\nlines",
            TerminalChannel.stripAnsi("plain\nlines"),
        )
        assertEquals("", TerminalChannel.stripAnsi("\u001B[2J\u001B[H"))
    }

    @Test
    fun `stripAnsi 对未终止序列安全截断`() {
        // 未终止的 OSC（无 BEL/ST）：丢弃到串尾，不输出垃圾
        assertEquals("", TerminalChannel.stripAnsi("\u001B]0; unfinished"))
        // 孤立 ESC 在串尾：丢弃
        assertEquals("ok", TerminalChannel.stripAnsi("ok\u001B"))
    }
}
