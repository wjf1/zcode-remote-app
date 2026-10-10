package com.zcode.remote

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import com.zcode.remote.relay.ConversationRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AppViewModel.consumePendingByEcho] 的纯函数单测（C-6 本地回显气泡的移除判定）。
 *
 * 判错的两个方向都不可接受：该移除时不移除 → 气泡与服务端回显重复显示；
 * 不该移除时移除 → 用户刚发的消息从界面上消失（比「按了没反应」更糟）。
 */
class AppViewModelTest {

    private fun pending(id: Long, text: String) =
        AppViewModel.PendingUserMessage(id, text, emptyList())

    @Test
    fun matchingEcho_removesOldestPending() {
        val list = listOf(pending(1, "第一条"), pending(2, "第二条"))
        assertEquals(listOf(pending(2, "第二条")), AppViewModel.consumePendingByEcho(list, "第一条"))
    }

    @Test
    fun echoTextIsTrimmedBeforeMatch() {
        val list = listOf(pending(1, "  hello  "))
        assertEquals(
            emptyList<AppViewModel.PendingUserMessage>(),
            AppViewModel.consumePendingByEcho(list, "hello"),
        )
    }

    @Test
    fun unrelatedEcho_keepsAllPending() {
        val list = listOf(pending(1, "手机发的"))
        assertEquals(list, AppViewModel.consumePendingByEcho(list, "桌面端发的"))
    }

    @Test
    fun blankEcho_keepsAllPending() {
        val list = listOf(pending(1, "手机发的"))
        assertEquals(list, AppViewModel.consumePendingByEcho(list, null))
        assertEquals(list, AppViewModel.consumePendingByEcho(list, "   "))
    }

    @Test
    fun emptyPending_returnsEmpty() {
        assertEquals(
            emptyList<AppViewModel.PendingUserMessage>(),
            AppViewModel.consumePendingByEcho(emptyList(), "任何文本"),
        )
    }

    // ---------- C-1：上传 id 复用（重试命中服务端幂等的前提） ----------

    @Test
    fun uploadIdReuse_sameFileReusesPreviousId() {
        assertEquals(
            "upload-abc",
            AppViewModel.uploadIdForAttempt("report.pdf", 1024L, "upload-abc", "report.pdf", 1024L, "upload-new"),
        )
    }

    @Test
    fun uploadIdReuse_differentNameOrSizeGetsFreshId() {
        // 换了文件（名字不同）或用同名不同内容的文件（大小不同）都必须用新 id，
        // 否则服务端会把旧文件内容当作新文件提交
        assertEquals(
            "upload-new",
            AppViewModel.uploadIdForAttempt("report.pdf", 1024L, "upload-abc", "other.pdf", 1024L, "upload-new"),
        )
        assertEquals(
            "upload-new",
            AppViewModel.uploadIdForAttempt("report.pdf", 1024L, "upload-abc", "report.pdf", 2048L, "upload-new"),
        )
    }

    @Test
    fun uploadIdReuse_withoutPreviousFailureGetsFreshId() {
        assertEquals(
            "upload-new",
            AppViewModel.uploadIdForAttempt(null, -1L, null, "a.txt", 1L, "upload-new"),
        )
    }

    // ---------- 桥失败自动重开（2026-10-10 真机故障修复） ----------

    @Test
    fun bridgeReopenDelay_backsOffThenStops() {
        // 退避序列 1s/2s/4s；到上限即停手——否则会把「服务端持续降级」放大成重开风暴
        assertEquals(1_000L, AppViewModel.bridgeReopenDelayMs(1))
        assertEquals(2_000L, AppViewModel.bridgeReopenDelayMs(2))
        assertEquals(4_000L, AppViewModel.bridgeReopenDelayMs(3))
        assertNull("超过上限应停手，避免重开风暴", AppViewModel.bridgeReopenDelayMs(4))
        assertNull(AppViewModel.bridgeReopenDelayMs(0))
        assertNull(AppViewModel.bridgeReopenDelayMs(-1))
    }

    // ---------- C-5⑤：异步离线缓存的竞态判定（2026-10-10 立项） ----------

    @Test
    fun shouldApplyCachedRows_onlyWhenSameSessionAndStoreEmpty() {
        // 正常路径：仍在该会话 + 权威快照尚未落地 → 应用缓存
        assertTrue(AppViewModel.shouldApplyCachedRows("sess_a", "sess_a", true))
        // 快照已到（rowStore 非空）→ 丢弃缓存——否则旧缓存会覆盖新快照
        assertFalse(AppViewModel.shouldApplyCachedRows("sess_a", "sess_a", false))
        // 加载期间已切走会话 → 丢弃
        assertFalse(AppViewModel.shouldApplyCachedRows("sess_b", "sess_a", true))
        assertFalse(AppViewModel.shouldApplyCachedRows(null, "sess_a", true))
    }

    // ---------- C-5③：会话行派生快照（引用稳定；无关重组不再整表拷贝） ----------

    @Test
    fun derivedRowsSnapshot_stableUntilContentChanges() {
        // 该契约是 ④ 增量缓存生效的前提：快照实例不随无关重组变化，
        // `remember(rows)` 的派生（SessionFiles / TurnChanges 逐行解析）才只在
        // 行内容真的变化时重算，而不是每次 UI 重组都重算。
        val rows = mutableStateListOf<ConversationRow>()
        val snapshot by derivedStateOf { rows.toList() }

        val first = snapshot
        assertSame("无内容变化时必须复用同一实例（否则等价于每次重组都拷贝）", first, snapshot)

        rows += ConversationRow(rowId = 1, kind = "assistantText", text = "hi")
        val second = snapshot
        assertNotSame("内容变化后必须产生新实例（否则下游 remember 不会重算）", first, second)
        assertEquals(1, second.size)
        assertSame("再次读取仍是同一新实例", second, snapshot)
    }
}
