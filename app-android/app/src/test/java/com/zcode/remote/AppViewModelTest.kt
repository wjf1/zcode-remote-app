package com.zcode.remote

import org.junit.Assert.assertEquals
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
}
