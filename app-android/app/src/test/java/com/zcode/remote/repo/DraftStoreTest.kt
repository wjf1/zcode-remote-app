package com.zcode.remote.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话草稿按 taskId 隔离的单测（纯函数 `putDraft` 的 LRU 语义）。
 *
 * 缺陷背景：草稿原为单一全局字段，切会话不清空 → 在 A 打的半句话切到 B 后仍在输入框，
 * 点发送会**错发到 B**。修复后按 taskId 保存/恢复，本测试钉住 map 的读写与淘汰语义。
 */
class DraftStoreTest {
    private fun map() = LinkedHashMap<String, String>()

    /** 写入后可按会话读回（基本隔离）。 */
    @Test
    fun draftsAreIsolatedPerSession() {
        val m = map()
        ConversationRepository.putDraft(m, "sessA", "给A的半句话")
        ConversationRepository.putDraft(m, "sessB", "给B的话")
        assertEquals("给A的半句话", m["sessA"])
        assertEquals("给B的话", m["sessB"])
    }

    /** 空串代表「该会话无草稿」：按删除处理，不在 map 里留空值。 */
    @Test
    fun emptyDraftRemovesEntry() {
        val m = map()
        ConversationRepository.putDraft(m, "sessA", "内容")
        ConversationRepository.putDraft(m, "sessA", "")
        assertNull(m["sessA"])
        assertTrue(m.isEmpty())
    }

    /** 覆盖同一会话：以最后一次写入为准。 */
    @Test
    fun laterWriteWins() {
        val m = map()
        ConversationRepository.putDraft(m, "sessA", "第一版")
        ConversationRepository.putDraft(m, "sessA", "第二版")
        assertEquals("第二版", m["sessA"])
        assertEquals(1, m.size)
    }

    /** 超出上限时淘汰「最久未更新」的会话（LRU，头部先出）。 */
    @Test
    fun evictsLeastRecentlyUsed() {
        val m = map()
        ConversationRepository.putDraft(m, "s1", "v", max = 3)
        ConversationRepository.putDraft(m, "s2", "v", max = 3)
        ConversationRepository.putDraft(m, "s3", "v", max = 3)
        // 触碰 s1，使其变为最近使用
        ConversationRepository.putDraft(m, "s1", "v2", max = 3)
        // 写入 s4 → 应淘汰最旧的 s2（s1 刚被触碰、s3 次之）
        ConversationRepository.putDraft(m, "s4", "v", max = 3)
        assertEquals(3, m.size)
        assertNull("s2 应被淘汰", m["s2"])
        assertEquals("v2", m["s1"])
        assertTrue(m.containsKey("s3"))
        assertTrue(m.containsKey("s4"))
    }

    /** 上限为 1 的退化情形：只保留最后写入的那个会话。 */
    @Test
    fun maxOneKeepsOnlyLatest() {
        val m = map()
        ConversationRepository.putDraft(m, "a", "1", max = 1)
        ConversationRepository.putDraft(m, "b", "2", max = 1)
        assertEquals(1, m.size)
        assertNull(m["a"])
        assertEquals("2", m["b"])
    }

    // ---------- switchDraft：切会话时「存旧 + 恢复新」的时序 ----------

    /**
     * 核心缺陷场景：会话 A 打了草稿 → 切到 B，B 必须是空的（而不是把 A 的草稿带过去）。
     * 这正是原实现「单一全局字段」的错发风险。
     */
    @Test
    fun switchingToFreshSessionClearsDraft() {
        val m = map()
        val restored = ConversationRepository.switchDraft(m, oldSessionId = "A", newSessionId = "B", currentDraft = "给A的半句话")
        assertEquals("B 应为空草稿", "", restored)
        assertEquals("A 的草稿已存回", "给A的半句话", m["A"])
        assertNull(m["B"])
    }

    /** 切回 A：草稿应被恢复（用户可继续编辑，不丢）。 */
    @Test
    fun switchingBackRestoresSavedDraft() {
        val m = map()
        ConversationRepository.switchDraft(m, "A", "B", "给A的半句话") // A→B（存A）
        val back = ConversationRepository.switchDraft(m, "B", "A", "") // B→A（B为空，恢复A）
        assertEquals("给A的半句话", back)
    }

    /** 存旧必须写进**旧**会话键——即调用方必须在改 subscribedSessionId 之前调用。 */
    @Test
    fun draftsStoredUnderOldKeyNotNew() {
        val m = map()
        ConversationRepository.switchDraft(m, oldSessionId = "A", newSessionId = "B", currentDraft = "内容")
        assertEquals("内容", m["A"])
        assertNull("绝不能写到新会话键上", m["B"])
    }

    /** 同一会话「切换」到自己：不应把自己存了又读（无害但没必要），草稿原样返回。 */
    @Test
    fun switchingToSameSessionKeepsDraft() {
        val m = map()
        m["A"] = "已有"
        val r = ConversationRepository.switchDraft(m, oldSessionId = "A", newSessionId = "A", currentDraft = "已有")
        assertEquals("已有", r)
    }

    /** 从未订阅（old=null）时切到某会话：直接取该会话草稿，不写任何键。 */
    @Test
    fun noPreviousSessionDoesNotWrite() {
        val m = map()
        val r = ConversationRepository.switchDraft(m, oldSessionId = null, newSessionId = "B", currentDraft = "游离草稿")
        assertEquals("", r)
        assertTrue(m.isEmpty())
    }
}
