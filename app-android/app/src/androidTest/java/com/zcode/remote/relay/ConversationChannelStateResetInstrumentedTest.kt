package com.zcode.remote.relay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 切换会话时会话级状态复位网（模拟器/真机运行）。
 *
 * 复现的缺陷（2026-10-16 同类缺陷扫描）：`ConversationChannel.subscribe()` 原先只重置
 * `_status` / `_interactions` / `_snapshotAligned`，而 `_earlier`（向上翻页门控）等**跨会话沿用**。
 * `_earlier` 尤其有害：它**不被快照重写**（快照处理里没有 `_earlier.value = …`），
 * 于是上个会话拉到头后的 `(pulled=true, hasMore=false)` 会带给下一个会话，
 * 使 UI 门控 `(hasMore || !pulled)` 恒假——**新会话永远无法向上翻页**。
 *
 * 本用例不依赖中继：桥未就绪时 `loadEarlier` 走同步错误分支，恰好把 `_earlier` 置成上述状态，
 * 从而可在无网络下复现「翻页拉到头」。`ConversationChannel` 依赖 `RpcChannel`（Android Looper），
 * 故只能走仪器化（JVM 单测不可用）。
 */
class ConversationChannelStateResetInstrumentedTest {
    private fun newChannel(): ConversationChannel {
        val device =
            com.zcode.remote.storage.PairedDevice(
                deviceSid = "sid",
                passHash = "hash",
                deviceMid = null,
                deviceName = "test",
                remoteUrl = "https://zcode.z.ai/remote/v4",
            )
        // 桥始终未就绪：call/listen 同步回 "bridge not ready"，不触网
        return ConversationChannel(RpcChannel(RelayClient(device)))
    }

    private fun storeWithOneRow(): RowStore = RowStore(mutableListOf(ConversationRow(rowId = 1, kind = "userInput", text = "hi")))

    /**
     * 核心回归：会话 A 翻页拉到头后切到会话 B —— B 的翻页门控必须复位（不得继承 A 的 pulled）。
     *
     * 修复前：`_earlier` 保持 `(pulled=true, hasMore=false)` → UI 判 `(false || false)=false` → B 无法翻页。
     * 修复后：切会话时 `_earlier` 复位为默认 → `pulled=false` → `(false || true)=true` → B 可正常翻页。
     */
    @Test
    fun switchingSessionResetsPaginationGate() {
        val ch = newChannel()

        // 会话 A：订阅 → 翻页（桥未就绪，同步把 _earlier 置为 pulled=true, hasMore=false）
        ch.subscribe("F:/ws", null, "A", storeWithOneRow())
        ch.loadEarlier(storeWithOneRow()) { /* 结果不入断言：这里只关心 _earlier 的副作用 */ }
        val afterA = ch.earlier.value
        assertTrue("前置条件：A 翻页后应为 pulled（实际 pulled=${afterA.pulled}）", afterA.pulled)
        assertFalse("前置条件：A 桥未就绪时应无更多（hasMore=false）", afterA.hasMore)

        // 切到会话 B：门控必须复位
        ch.subscribe("F:/ws", null, "B", storeWithOneRow())
        val afterB = ch.earlier.value
        assertFalse(
            "切到 B 后不得继承 A 的 pulled（否则 UI 门控 (hasMore || !pulled) 恒假、B 无法翻页）",
            afterB.pulled,
        )
        // 门控公式：此刻应允许首次翻页
        assertTrue("B 的首次翻页应被放行", afterB.hasMore || !afterB.pulled)
    }

    /** 同一会话重订（A-2 自动重订 / 用户重试）**不得**清会话级状态，避免标题闪一下再回来。 */
    @Test
    fun resubscribingSameSessionKeepsState() {
        val ch = newChannel()
        ch.subscribe("F:/ws", null, "A", storeWithOneRow())
        ch.loadEarlier(storeWithOneRow()) { }
        assertTrue("前置条件：A 已 pulled", ch.earlier.value.pulled)

        // 重订同一会话 A：不应复位
        ch.subscribe("F:/ws", null, "A", storeWithOneRow())
        assertTrue("同会话重订应保留 _earlier（不清状态、不闪烁）", ch.earlier.value.pulled)
    }
}
