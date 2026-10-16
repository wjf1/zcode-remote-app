package com.zcode.remote.ui.screens

import com.zcode.remote.R
import com.zcode.remote.relay.ConversationChannel
import com.zcode.remote.util.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话空态「同步阶段 + 已等待秒数」的纯函数单测（任务书 §7）。
 *
 * 背景：空态此前只有一句静态「等待会话同步…」，既说不出当前卡在哪个阶段（连接 / 握手 / 订阅），
 * 也不给已等待时长，用户无法判断是建连中还是已卡死。抽出 [syncHeadline] / [syncSubtext] /
 * [isWaitingForSync] 三个纯函数后，各阶段文案与边界（草稿、失败、空会话、秒数进入占位符）
 * 可被钉死；UI 侧只用计时器驱动 [EmptySyncHeadline] 刷新。
 */
class ConversationSyncInfoTest {
    private val live =
        ConversationChannel.Status.Live(
            subscriptionId = "sub-1",
            mode = "build",
            logEpoch = "1",
        )
    private val failed =
        ConversationChannel.Status.Failed(
            reason = UiText.Raw("timeout"),
            retryable = true,
        )

    // ---------- syncHeadline：各阶段文案 ----------

    @Test
    fun draftShowsReadyNotWait() {
        val t = syncHeadline(ConversationChannel.Status.Idle, isDraft = true, elapsedSeconds = 99)
        assertEquals(UiText.Res(R.string.sync_new_session_ready), t)
    }

    @Test
    fun helloShowsHandshakeWithSeconds() {
        val t = syncHeadline(ConversationChannel.Status.Hello, isDraft = false, elapsedSeconds = 7)
        assertEquals(UiText.Res(R.string.sync_wait_handshake, listOf(7L)), t)
    }

    @Test
    fun initializedShowsSubscribingWithSeconds() {
        val t = syncHeadline(ConversationChannel.Status.Initialized, isDraft = false, elapsedSeconds = 12)
        assertEquals(UiText.Res(R.string.sync_wait_subscribing, listOf(12L)), t)
    }

    @Test
    fun idleShowsConnectingWithSeconds() {
        val t = syncHeadline(ConversationChannel.Status.Idle, isDraft = false, elapsedSeconds = 3)
        assertEquals(UiText.Res(R.string.sync_wait_connecting, listOf(3L)), t)
    }

    /** 已订阅但仍无行 = 真·空会话，不是「还在同步」，故不显示等待秒数。 */
    @Test
    fun liveShowsEmptySessionNotWait() {
        val t = syncHeadline(live, isDraft = false, elapsedSeconds = 30)
        assertEquals(UiText.Res(R.string.sync_empty_session), t)
    }

    @Test
    fun failedShowsFailureHintNotSeconds() {
        val t = syncHeadline(failed, isDraft = false, elapsedSeconds = 30)
        assertEquals(UiText.Res(R.string.sync_failed), t)
    }

    /** 草稿优先于一切状态：新建会话时的 Idle 不应被读成「建立连接中」。 */
    @Test
    fun draftTakesPrecedenceOverIdle() {
        val draft = syncHeadline(ConversationChannel.Status.Idle, isDraft = true, elapsedSeconds = 0)
        val nonDraft = syncHeadline(ConversationChannel.Status.Idle, isDraft = false, elapsedSeconds = 0)
        assertTrue(draft != nonDraft)
    }

    // ---------- syncSubtext：副文案 ----------

    @Test
    fun draftSubtextGuidesSending() {
        assertEquals(
            UiText.Res(R.string.sync_new_session_hint),
            syncSubtext(ConversationChannel.Status.Idle, isDraft = true),
        )
    }

    @Test
    fun waitingSubtextExplainsSyncing() {
        assertEquals(
            UiText.Res(R.string.sync_syncing_hint),
            syncSubtext(ConversationChannel.Status.Hello, isDraft = false),
        )
    }

    /** 失败态主标题已在解释原因，副文案不再重复。 */
    @Test
    fun failedHasNoSubtext() {
        assertNull(syncSubtext(failed, isDraft = false))
    }

    /** 空会话主标题已给出「输入即可开始」的引导，副文案不再重复。 */
    @Test
    fun liveHasNoSubtext() {
        assertNull(syncSubtext(live, isDraft = false))
    }

    // ---------- isWaitingForSync：计时开关 ----------

    @Test
    fun waitingOnlyInTransientStates() {
        assertTrue(isWaitingForSync(ConversationChannel.Status.Idle))
        assertTrue(isWaitingForSync(ConversationChannel.Status.Hello))
        assertTrue(isWaitingForSync(ConversationChannel.Status.Initialized))
        assertFalse(isWaitingForSync(live))
        assertFalse(isWaitingForSync(failed))
    }

    // ---------- 占位符参数：确保秒数确实进入文案而非被吞 ----------

    @Test
    fun secondsArgumentIsForwarded() {
        val t = syncHeadline(ConversationChannel.Status.Hello, isDraft = false, elapsedSeconds = 42) as UiText.Res
        assertEquals(listOf<Any>(42L), t.args)
    }
}
