package com.zcode.remote.repo

import androidx.test.platform.app.InstrumentationRegistry
import com.zcode.remote.relay.ConversationRow
import com.zcode.remote.relay.SessionItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 断开态下切换会话的视图隔离网（模拟器/真机运行）。
 *
 * 复现用户可见缺陷（2026-10-16 真机走查定性）：`rows` / `conversationMeta` 是**单一共享实例**，
 * 而 `subscribeConversation()` 原先把 `conversation() ?: return` 放在最前——断开连接时
 * 直接早退、跳过 `rowStore.clear()`，于是**点开会话卡片看到的是上一个会话的标题与内容行**。
 *
 * 与 [ConversationDraftInstrumentedTest] 同套路：`conversation = { null }` 即断开态，
 * 不依赖中继/配对（本用例只走视图切换，不发网络请求）。
 */
class ConversationOfflineSwitchInstrumentedTest {
    private fun newRepo(): ConversationRepository {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        return ConversationRepository(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
            context = ctx,
            conversation = { null }, // 断开态：无连接栈
            activeWorkspaceKey = { "F:/ws" },
            flash = { _, _ -> },
            onNewSubscription = {},
            clearAttachments = {},
        )
    }

    private fun session(id: String) =
        SessionItem(
            taskId = id,
            title = "会话 $id",
            displayStatus = "idle",
            workspacePath = "F:/ws",
            workspaceLabel = "ws",
            provider = "glm",
            updatedAt = null,
            archived = false,
        )

    /**
     * 核心回归：断开态下，先渲染了会话 B 的行，再点开会话 A —— A 不得显示 B 的残留行。
     *
     * 修复前：`subscribeConversation(A)` 因 `conversation() == null` 早退，B 的行原样保留，
     * 用户点 A 却看到 B 的内容。修复后：视图先清空（本用例无 A 的离线缓存，故为空）。
     */
    @Test
    fun switchingSessionsOfflineDoesNotShowPreviousSessionRows() {
        val repo = newRepo()

        // 模拟「上一个被订阅的会话 B」已把行写进共享 rowStore
        repo.rowStore.replaceAll(
            listOf(
                ConversationRow(rowId = 1, kind = "userInput", text = "B 会话的历史内容"),
                ConversationRow(rowId = 2, kind = "assistant", text = "B 的回复"),
            ),
        )
        assertEquals("前置条件：B 的行已在共享 rowStore 中", 2, repo.rows.size)

        // 断开态下点开会话 A（无 A 的离线缓存文件）
        repo.subscribeConversation(session("A"))

        assertTrue(
            "点开会话 A 后不得残留会话 B 的内容行（实际 ${repo.rows.size} 行）",
            repo.rows.isEmpty(),
        )
        assertEquals("A", repo.subscribedSessionId)
    }

    /** 断开态下设 id 后，会话内草稿键跟随切换（视图切换须早于/独立于订阅成败）。 */
    @Test
    fun draftKeyFollowsViewedSessionWhenOffline() {
        val repo = newRepo()
        repo.switchDraftTo("B")
        repo.subscribedSessionId = "B"
        repo.updatePromptDraft("B 的半句话")

        repo.subscribeConversation(session("A"))
        assertEquals("切换后当前会话应为 A", "A", repo.subscribedSessionId)
        assertEquals("A 的输入框应为空（不得带过来 B 的草稿）", "", repo.promptDraft)

        // 切回 B：草稿恢复（与在线路径一致）
        repo.subscribeConversation(session("B"))
        assertEquals("切回 B 应恢复其草稿", "B 的半句话", repo.promptDraft)
    }
}
