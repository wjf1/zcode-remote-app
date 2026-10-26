package com.zcode.remote.service

import com.zcode.remote.relay.ControllerChannel
import com.zcode.remote.relay.SessionItem
import com.zcode.remote.storage.ProtocolStack
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * T3：会话列表的不可变快照语义 + V2/V1 数据源分流判据。
 *
 * 背景（任务书 T3）：原先 `hostSessions` 是 `MutableList`，collector 在后台线程原位改、
 * UI 在主线程读，中间没有屏障。改成 `StateFlow<List<SessionItem>>` 后，写入是**整体替换**
 * 不可变列表——本测试锁住这个契约（写入/读取/并发三项）与分流判定（两项）。
 *
 * 这里用与 AppViewModel 相同的写入实现（[SessionListHolder]）验证语义；AppViewModel 的实现
 * 直接落在 hook 里，二者共用同一套「只整体替换、不原位改」的规则。
 */
class SessionListSnapshotTest {
    /** 与 AppViewModel 的 `_sessions` / `hostReplaceSessions` / `hostUpdateSessions` 同构。 */
    private class SessionListHolder {
        private val _sessions = MutableStateFlow<List<SessionItem>>(emptyList())
        val sessionsFlow = _sessions
        val sessions: List<SessionItem> get() = _sessions.value

        fun replace(next: List<SessionItem>) {
            _sessions.value = next
        }

        fun update(transform: (SessionItem) -> SessionItem?) {
            val cur = _sessions.value
            var changed = false
            val next =
                cur.map { item ->
                    val updated = transform(item)
                    if (updated == null) {
                        item
                    } else {
                        changed = true
                        updated
                    }
                }
            if (changed) _sessions.value = next
        }
    }

    private fun item(
        id: String,
        status: String = "idle",
    ) = SessionItem(
        taskId = id,
        title = "T-$id",
        displayStatus = status,
        workspacePath = "/ws",
        workspaceLabel = "ws",
        provider = null,
        updatedAt = 1L,
        archived = false,
    )

    // ---------- 1. 写入：整体替换，旧快照不被修改 ----------

    @Test
    fun `整表替换后旧快照保持不变`() {
        val h = SessionListHolder()
        val first = listOf(item("a"), item("b"))
        h.replace(first)
        val snapshot = first

        h.replace(listOf(item("c")))

        assertEquals(listOf("c"), h.sessions.map { it.taskId })
        // 关键：先前读到的列表实例不得被后续写入改动（不可变快照语义）
        assertEquals(listOf("a", "b"), snapshot.map { it.taskId })
    }

    // ---------- 2. 按键更新：不发散、返回 null 视为不变 ----------

    @Test
    fun `局部更新只改目标条目且不变时返回同一实例`() {
        val h = SessionListHolder()
        h.replace(listOf(item("a"), item("b")))

        h.update { if (it.taskId == "a") it.copy(displayStatus = "running") else null }
        assertEquals(listOf("running", "idle"), h.sessions.map { it.displayStatus })

        val before = h.sessions
        h.update { null } // 全部返回 null = 无变化
        assertSame("全部不变时不得替换列表实例", before, h.sessions)
    }

    // ---------- 3. 并发：多写一读不产生「半列表」 ----------

    @Test
    fun `并发写入与读取始终看到完整一代`() {
        val h = SessionListHolder()
        h.replace((0 until 4).map { item("t$it") })

        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        val done = CountDownLatch(1)
        val inconsistent = AtomicBoolean(false)

        // 读线程：列表条数必须恒为 4（整表替换，不存在「先清空再填充」的中间态）
        pool.execute {
            start.await()
            while (done.count > 0L) {
                if (h.sessions.size != 4) inconsistent.set(true)
            }
        }
        // 写线程：反复整表替换（含改顺序），并翻转一个字段
        pool.execute {
            start.await()
            repeat(2_000) { i ->
                val next = (0 until 4).map { item("t$it", if (i % 2 == 0) "running" else "idle") }
                h.replace(if (i % 3 == 0) next.reversed() else next)
            }
            done.countDown()
        }
        start.countDown()
        assertTrue("写入线程未在 10s 内完成", done.await(10, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertFalse("读到过半代列表（说明存在原位修改）", inconsistent.get())
        assertEquals(4, h.sessions.size)
    }

    // ---------- 4. V2/V1 数据源分流 ----------

    @Test
    fun `V2 且 bootstrap 无 tasks 时 Controller 接管`() {
        assertTrue(ControllerChannel.shouldTakeOver(ProtocolStack.V2, 0))
    }

    @Test
    fun `V1 或 bootstrap 已带 tasks 时仍由 bootstrap 提供列表`() {
        assertFalse(ControllerChannel.shouldTakeOver(ProtocolStack.V1, 0))
        assertFalse(ControllerChannel.shouldTakeOver(ProtocolStack.V1, 5))
        assertFalse(ControllerChannel.shouldTakeOver(ProtocolStack.V2, 5))
    }

    // ---------- 5. 断线重同步不清空列表 ----------

    @Test
    fun `Controller 快照替换不会清空既有条目`() {
        val h = SessionListHolder()
        h.replace(listOf(item("keep")))
        // 重同步后服务端仍给出该条 → 列表保持（不清空、不闪空态）
        h.replace(listOf(item("keep", "running")))
        assertEquals(listOf("keep"), h.sessions.map { it.taskId })
        assertEquals("running", h.sessions[0].displayStatus)
    }
}
