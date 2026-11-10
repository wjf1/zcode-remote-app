package com.zcode.remote.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V2 未读判定（纯函数）：为什么需要这条路径——
 *
 * 真机实测（Xiaomi 15 Pro / ZCode 3.16+ V2）：任务事件流走 RPC 桥通道，**不会**进入
 * `ConnectionCoordinator` 的 `data` 层 `null` 分支，故 V1 的「TaskEvent → 未读」在 V2
 * 恒不触发。V2 改用 Controller 全量快照的 `updatedAt` 前进作为「该会话有新内容」的信号。
 */
class UnreadDetectionTest {
    @Test
    fun `updatedAt 前进且非订阅会话 → 标记未读`() {
        assertTrue(ConnectionCoordinator.isNewlyUpdated(100L, 200L, "sess_a", "sess_other"))
    }

    @Test
    fun `当前订阅会话不标未读`() {
        assertFalse(
            "用户正在看这个会话——事件是自己触发的，不该显示未读",
            ConnectionCoordinator.isNewlyUpdated(100L, 200L, "sess_a", "sess_a"),
        )
    }

    @Test
    fun `首份快照没有基准不标未读`() {
        assertFalse(
            "prev=null 表示首次见到该会话（首份快照/新建会话），无基准可比，全标会刷出一片未读",
            ConnectionCoordinator.isNewlyUpdated(null, 200L, "sess_a", null),
        )
    }

    @Test
    fun `时间戳未前进不标未读`() {
        assertFalse("updatedAt 相同 = 无变化", ConnectionCoordinator.isNewlyUpdated(200L, 200L, "sess_a", null))
        assertFalse("updatedAt 回退（时钟偏移/服务端乱序）不应当作新内容", ConnectionCoordinator.isNewlyUpdated(200L, 100L, "sess_a", null))
    }

    @Test
    fun `缺失 now 不标未读`() {
        assertFalse("now=null：该会话没有 updatedAt 字段，无从判断", ConnectionCoordinator.isNewlyUpdated(100L, null, "sess_a", null))
    }
}
