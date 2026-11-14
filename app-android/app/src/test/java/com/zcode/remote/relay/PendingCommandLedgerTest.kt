package com.zcode.remote.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G4 已提交命令账本（语义对齐官方 pendingCommandRegistry）：24h TTL、幂等记录、
 * 最旧优先出批 ≤64、绝不自动重放（账本只保存恢复线索）。
 */
class PendingCommandLedgerTest {
    private var now = 1_000_000L

    private fun ledger(): PendingCommandLedger {
        now = 1_000_000L
        return PendingCommandLedger(nowMs = { now }, ttlMs = 100_000L)
    }

    @Test
    fun record_isIdempotentPerCommandId() {
        val l = ledger()
        assertTrue(l.record("c1", "s1"))
        assertFalse(l.record("c1", "s1")) // 同 commandId 保留首条
        assertEquals(1, l.size())
    }

    @Test
    fun settle_removesEntry() {
        val l = ledger()
        l.record("c1", "s1")
        assertTrue(l.settle("c1"))
        assertFalse(l.settle("c1")) // 二次结算 no-op
        assertEquals(0, l.size())
    }

    @Test
    fun prune_expiresAfterTtl() {
        val l = ledger()
        l.record("c1", "s1")
        now += 99_999L
        assertEquals(0, l.prune())
        assertEquals(1, l.size())
        now += 1L // 越过 TTL
        assertEquals(1, l.prune())
        assertEquals(0, l.size())
    }

    @Test
    fun batch_isOldestFirst_andCappedAt64() {
        val l = ledger()
        repeat(70) { i -> l.record("c$i", "s1") }
        val batch = l.unsettledBatch()
        assertEquals(64, batch.size) // 官方 commands/query 每批 ≤64
        assertEquals("c0", batch.first().commandId) // 最旧优先
        assertEquals("c63", batch.last().commandId)
    }

    @Test
    fun batch_emptyIsSafe() {
        assertTrue(ledger().unsettledBatch().isEmpty())
    }
}
