package com.zcode.remote.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §11.5.2 请求位点表（(桥代次, requestId) 键控）与重同步节流的纯逻辑单测。
 *
 * 核心验收：**旧连接/旧桥上迟到的应答不得完成新桥上的同 id 请求**。
 */
class PendingRequestsTest {
    @Test
    fun sameEpochAndIdCompletes() {
        val map = PendingRequests<String>()
        map.register(epoch = 1, id = 7, value = "reply-7")
        assertEquals(1, map.size)
        assertEquals("reply-7", map.complete(epoch = 1, id = 7))
        assertEquals(0, map.size)
        // 二次完成（重复应答）返回 null，不会二次触发回调
        assertNull(map.complete(epoch = 1, id = 7))
    }

    @Test
    fun staleEpochCannotCompleteNewRequest() {
        val map = PendingRequests<String>()
        // 新桥（代次 2）上复用了旧桥（代次 1）用过的 id=0 —— id 计数在 reset 后归零，这是真实场景
        map.register(epoch = 2, id = 0, value = "new-bridge-reply")
        // 旧代次的迟到应答：必须落空，且不破坏新请求的挂起
        assertNull(map.complete(epoch = 1, id = 0))
        assertEquals(1, map.size)
        assertEquals("new-bridge-reply", map.complete(epoch = 2, id = 0))
    }

    @Test
    fun drainAllReturnsAndClearsEverything() {
        val map = PendingRequests<String>()
        map.register(epoch = 3, id = 1, value = "a")
        map.register(epoch = 3, id = 2, value = "b")
        val drained = map.drainAll().sortedBy { it.first }
        assertEquals(listOf(1 to "a", 2 to "b"), drained)
        assertEquals(0, map.size)
    }

    @Test
    fun keyPackingIsCollisionFreeAcrossEpochAndId() {
        assertNotEquals(PendingRequests.key(1, 2), PendingRequests.key(2, 1))
        assertEquals(PendingRequests.key(0, 5), PendingRequests.key(0, 5))
        // 高 32 位代次、低 32 位 id：极值组合仍然互不相同
        assertNotEquals(PendingRequests.key(Int.MAX_VALUE, 0), PendingRequests.key(0, Int.MAX_VALUE))
    }

    @Test
    fun completingByIdOnAnotherEpochLeavesOtherEntriesIntact() {
        val map = PendingRequests<String>()
        map.register(epoch = 1, id = 4, value = "old")
        map.register(epoch = 2, id = 4, value = "new")
        assertEquals("new", map.complete(2, 4))
        // 旧代次条目仍可被显式收场（failPending 路径），不会互相污染
        assertEquals("old", map.complete(1, 4))
        assertEquals(0, map.size)
    }
}

/** 重同步节流：间隔内抑制（计数）、跨间隔放行、reset 归零。 */
class ResyncThrottleTest {
    @Test
    fun firstCallAlwaysTriggers() {
        val throttle = ResyncThrottle(minIntervalMs = 5_000L)
        assertTrue(throttle.shouldTrigger(1_000L))
        assertEquals(0, throttle.suppressed)
    }

    @Test
    fun withinIntervalIsSuppressedAndCounted() {
        val throttle = ResyncThrottle(minIntervalMs = 5_000L)
        assertTrue(throttle.shouldTrigger(10_000L))
        assertFalse(throttle.shouldTrigger(10_001L))
        assertFalse(throttle.shouldTrigger(14_999L))
        assertEquals(2, throttle.suppressed)
    }

    @Test
    fun acrossIntervalTriggersAgain() {
        val throttle = ResyncThrottle(minIntervalMs = 5_000L)
        assertTrue(throttle.shouldTrigger(10_000L))
        assertFalse(throttle.shouldTrigger(14_000L))
        assertTrue(throttle.shouldTrigger(15_000L))
        assertEquals(1, throttle.suppressed)
    }

    @Test
    fun resetClearsStateAndSuppressionCounter() {
        val throttle = ResyncThrottle(minIntervalMs = 5_000L)
        assertTrue(throttle.shouldTrigger(10_000L))
        assertFalse(throttle.shouldTrigger(11_000L))
        throttle.reset()
        assertEquals(0, throttle.suppressed)
        assertTrue(throttle.shouldTrigger(11_500L))
    }

    @Test
    fun timestampGoingBackwardsFailsOpen() {
        // 单调时钟理论上不会回退；真回退时宁可放行（可见恢复）也不永久抑制
        val throttle = ResyncThrottle(minIntervalMs = 5_000L)
        assertTrue(throttle.shouldTrigger(10_000L))
        assertTrue(throttle.shouldTrigger(9_000L))
    }
}
