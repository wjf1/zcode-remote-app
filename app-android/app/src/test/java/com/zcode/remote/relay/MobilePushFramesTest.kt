package com.zcode.remote.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 官方推送事件 schema 固化（F6 协议准备，契约 = contract.ts mobilePushEventSchema）。 */
class MobilePushFramesTest {
    private val eventJson =
        """
        {"version":1,"locale":"zh-CN","session_title":"重构登录页",
         "desktop_platform":"windows","event_id":"evt-1","kind":"approval",
         "workspace_id":"F:/AI/Zcode","session_id":"sess_a","run_id":"run_1",
         "interaction_id":"ia_9","state":"open","occurred_at":1794000000}
        """.trimIndent()

    @Test
    fun `事件解析全字段`() {
        val e = MobilePushFrames.parseEvent(eventJson)!!
        assertEquals("evt-1", e.eventId)
        assertEquals("approval", e.kind)
        assertTrue(e.isOpen)
        assertTrue(e.isKnownKind)
        assertEquals("sess_a", e.sessionId)
        assertEquals(1794000000L, e.occurredAt)
    }

    @Test
    fun `resolved 补发同id状态翻转`() {
        val resolved = eventJson.replace("\"state\":\"open\"", "\"state\":\"resolved\"")
        val e = MobilePushFrames.parseEvent(resolved)!!
        assertFalse(e.isOpen)
        assertEquals("同一事实 resolve 补发同 event_id（官方去重契约）", "evt-1", e.eventId)
    }

    @Test
    fun `未知kind与坏JSON宽容处理`() {
        val e = MobilePushFrames.parseEvent(eventJson.replace("\"approval\"", "\"mystery\""))!!
        assertFalse("未收录 kind 不崩溃，仅标记不可识别", e.isKnownKind)
        assertNull(MobilePushFrames.parseEvent("{bad json"))
    }

    @Test
    fun `未知字段忽略前向兼容`() {
        // 官方 mobile-log 同款策略：多字段忽略（schema 前向兼容口径）
        val withExtra =
            MobilePushFrames
                .parseEvent(
                    """
                    {"event_id":"e2","kind":"plan","workspace_id":"w","session_id":"s",
                     "run_id":"r","state":"open","occurred_at":1,"extra_future_field":123}
                    """.trimIndent(),
                )!!
        assertEquals("e2", withExtra.eventId)
    }

    @Test
    fun `通知Ext意图解析与目标识别`() {
        val i = MobilePushFrames.parseIntent("""{"open_target":"zcode_remote_control","conversation_id":"sess_a"}""")!!
        assertTrue(i.isRemoteControlTarget)
        assertEquals("sess_a", i.conversationId)
        val notTarget = MobilePushFrames.parseIntent("""{"open_target":"other"}""")!!
        assertFalse(notTarget.isRemoteControlTarget)
    }
}
