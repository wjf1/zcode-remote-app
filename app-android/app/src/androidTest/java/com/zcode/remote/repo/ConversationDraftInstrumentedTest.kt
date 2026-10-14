package com.zcode.remote.repo

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 会话草稿隔离的仪器化网（模拟器/真机运行）——用**真实 [ConversationRepository] 实例**
 * 验证接线（纯函数测试覆盖不到 repo 的成员读写与 `subscribedSessionId` 的交互）。
 *
 * 复现用户可见缺陷：在会话 A 打了半句话切到 B，B 的输入框必须为空（而非把 A 的草稿带过去，
 * 导致点发送**错发到 B**）。不依赖中继/配对——草稿域不走网络。
 */
class ConversationDraftInstrumentedTest {
    private fun newRepo(): ConversationRepository {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        return ConversationRepository(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
            context = ctx,
            conversation = { null },
            activeWorkspaceKey = { null },
            flash = { _, _ -> },
            onNewSubscription = {},
            clearAttachments = {},
        )
    }

    /** A 输入草稿 → 切到 B（未订阅过）应为空 → 切回 A 草稿恢复。 */
    @Test
    fun draftIsolatedAcrossSessions() {
        val repo = newRepo()
        // 模拟订阅 A：切草稿 + 设 id（与 subscribeConversation 内部顺序一致）
        repo.switchDraftTo("A")
        repo.subscribedSessionId = "A"
        repo.updatePromptDraft("给A的半句话")
        assertEquals("给A的半句话", repo.promptDraft)

        // 切到 B：switchDraftTo 必须在改 id 之前调用（存回 A 的键，取 B 的空）
        repo.switchDraftTo("B")
        repo.subscribedSessionId = "B"
        assertEquals("B 会话输入框应为空（不得带过去 A 的草稿）", "", repo.promptDraft)

        // 切回 A：草稿应恢复
        repo.switchDraftTo("A")
        repo.subscribedSessionId = "A"
        assertEquals("切回 A 应恢复其草稿", "给A的半句话", repo.promptDraft)
    }

    /** 发送成功后清空当前会话草稿，且不影响其他会话已存草稿。 */
    @Test
    fun clearDraftOnlyAffectsCurrentSession() {
        val repo = newRepo()
        repo.switchDraftTo("A")
        repo.subscribedSessionId = "A"
        repo.updatePromptDraft("A的草稿")
        repo.switchDraftTo("B")
        repo.subscribedSessionId = "B"
        repo.updatePromptDraft("B的草稿")

        // 在 B 发送成功 → 清 B 草稿
        repo.clearPromptDraft()
        assertEquals("", repo.promptDraft)
        // 切回 A：A 的草稿仍在
        repo.switchDraftTo("A")
        repo.subscribedSessionId = "A"
        assertEquals("A 的草稿不应被 B 的清理影响", "A的草稿", repo.promptDraft)
    }

    /** 断开连接（clearAllDrafts）：全部草稿清空。 */
    @Test
    fun clearAllDraftsWipesEverything() {
        val repo = newRepo()
        repo.switchDraftTo("A")
        repo.subscribedSessionId = "A"
        repo.updatePromptDraft("A")
        repo.clearAllDrafts()
        assertEquals("", repo.promptDraft)
        repo.switchDraftTo("A")
        assertEquals("断开后草稿不应残留", "", repo.promptDraft)
    }
}
