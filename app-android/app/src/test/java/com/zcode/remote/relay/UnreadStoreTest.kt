package com.zcode.remote.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** G6a 未读存储：unreadAt 时间戳语义 + expectedUnreadAt CAS 清除（官方 mobile-task-home-design.md）。 */
class UnreadStoreTest {
    @Test
    fun mark_keepsEarliestTimestamp_onRepeatedMark() {
        var m = UnreadStore.mark(emptyMap(), "t1", 100L)
        assertEquals(100L, m["t1"])
        // 重复标记不刷新（未读起点语义）
        m = UnreadStore.mark(m, "t1", 200L)
        assertEquals(100L, m["t1"])
    }

    @Test
    fun mark_ignoresBlankTaskId() {
        assertEquals(emptyMap<String, Long>(), UnreadStore.mark(emptyMap(), " ", 1L))
    }

    @Test
    fun casClear_clearsOnlyWhenExpectedMatches() {
        var m = UnreadStore.mark(emptyMap(), "t1", 100L)
        // 不匹配：期间有新未读写入 → 拒绝清除
        m = UnreadStore.compareAndClear(m, "t1", 999L)
        assertTrue(m.containsKey("t1"))
        // 匹配：迟到旧点击不得覆盖新未读（官方 CAS 语义）
        m = UnreadStore.compareAndClear(m, "t1", 100L)
        assertTrue(!m.containsKey("t1"))
    }

    @Test
    fun casClear_absentEntry_isNoOp() {
        val m = UnreadStore.compareAndClear(emptyMap(), "t1", 100L)
        assertTrue(m.isEmpty())
    }

    @Test
    fun casClear_nonPositiveExpected_isUnconditional() {
        var m = UnreadStore.mark(emptyMap(), "t1", 100L)
        m = UnreadStore.compareAndClear(m, "t1", 0L)
        assertTrue(!m.containsKey("t1"))
    }
}
