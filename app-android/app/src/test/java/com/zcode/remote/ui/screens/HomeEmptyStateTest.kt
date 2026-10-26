package com.zcode.remote.ui.screens

import com.zcode.remote.R
import com.zcode.remote.relay.FailureReason
import com.zcode.remote.relay.RelayState
import com.zcode.remote.relay.SessionItem
import com.zcode.remote.storage.ProtocolStack
import com.zcode.remote.util.UiText
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T5：首页空列表三态判定（未连接 / 已连接未同步 / 已同步且确实为空）。
 *
 * 缺陷背景：V2 的会话列表来自 Controller 订阅，配对上到首份快照之间有真实空窗；
 * 此前这段窗口与「PC 上真的没有会话」共用同一句文案，用户分不清在等还是在空。
 * 判定必须是纯函数（不依赖计时器猜同步进度），故逐个状态钉死。
 */
class HomeEmptyStateTest {
    private fun item(id: String) =
        SessionItem(
            taskId = id,
            title = "T-$id",
            displayStatus = "idle",
            workspacePath = "/ws",
            workspaceLabel = "ws",
            provider = null,
            updatedAt = 1L,
            archived = false,
        )

    // ---------- 三态判定 ----------

    @Test
    fun `未配对成功时是未连接态（沿用既有文案）`() {
        val cases =
            listOf(
                RelayState.Idle,
                RelayState.Connecting,
                RelayState.Authenticating,
                RelayState.WaitingPeer,
                RelayState.Failed(FailureReason.KICKED, "x"),
            )
        for (s in cases) {
            assertEquals(
                "state=$s 应判为未连接",
                HomeEmptyState.NotConnected,
                homeEmptyState(s, 0, ProtocolStack.V2, controllerSynced = false),
            )
            assertEquals(
                HomeEmptyState.NotConnected,
                homeEmptyState(s, 0, ProtocolStack.V1, controllerSynced = true),
            )
        }
    }

    @Test
    fun `V2 已配对但首份快照未到是同步中态`() {
        assertEquals(
            HomeEmptyState.Syncing,
            homeEmptyState(RelayState.Paired, 0, ProtocolStack.V2, controllerSynced = false),
        )
    }

    @Test
    fun `V2 已配对且快照已到但确实为空是空态`() {
        assertEquals(
            HomeEmptyState.Empty,
            homeEmptyState(RelayState.Paired, 0, ProtocolStack.V2, controllerSynced = true),
        )
    }

    @Test
    fun `V1 配对即完成不进入同步中态`() {
        // V1 的列表来自 bootstrap，配对成功就已经有结论
        assertEquals(
            HomeEmptyState.Empty,
            homeEmptyState(RelayState.Paired, 0, ProtocolStack.V1, controllerSynced = false),
        )
    }

    @Test
    fun `有条目时不走空态`() {
        assertEquals(
            HomeEmptyState.Empty,
            homeEmptyState(RelayState.Paired, 3, ProtocolStack.V2, controllerSynced = false),
        )
    }

    // ---------- 三态文案 ----------

    @Test
    fun `同步中与真空使用不同文案`() {
        val syncing = homeEmptyStateText(HomeEmptyState.Syncing, RelayState.Paired)
        val empty = homeEmptyStateText(HomeEmptyState.Empty, RelayState.Paired)
        assertEquals(UiText.Res(R.string.home_syncing_sessions), syncing)
        assertEquals(UiText.Res(R.string.home_no_sessions), empty)
    }

    @Test
    fun `未连接态文案沿用连接态映射`() {
        assertEquals(
            connectionEmptyStateText(RelayState.WaitingPeer),
            homeEmptyStateText(HomeEmptyState.NotConnected, RelayState.WaitingPeer),
        )
        assertEquals(
            connectionEmptyStateText(RelayState.Failed(FailureReason.AUTH_FAILED, "x")),
            homeEmptyStateText(HomeEmptyState.NotConnected, RelayState.Failed(FailureReason.AUTH_FAILED, "x")),
        )
    }

    // ---------- 过滤为空与真空的区分 ----------

    @Test
    fun `过滤后为空与一个会话都没有用不同提示`() {
        assertEquals(UiText.Res(R.string.home_no_sessions), homeFilteredEmptyText(0))
        assertEquals(UiText.Res(R.string.home_no_match), homeFilteredEmptyText(5))
    }

    // ---------- 过滤语义（与 HomeScreen 既有行为等价） ----------

    @Test
    fun `过滤按标题与工作区路径匹配且忽略大小写`() {
        val a = item("a").copy(title = "Refactor Auth", workspacePath = "/proj/alpha")
        val b = item("b").copy(title = "写文档", workspacePath = "/proj/BETA")
        val all = listOf(a, b)

        assertEquals(listOf("a"), homeVisibleSessions(all, "auth", false, false) { 0 }.map { it.taskId })
        assertEquals(listOf("b"), homeVisibleSessions(all, "beta", false, false) { 0 }.map { it.taskId })
        // 工作区路径也参与匹配
        assertEquals(listOf("a"), homeVisibleSessions(all, "alpha", false, false) { 0 }.map { it.taskId })
        assertEquals(2, homeVisibleSessions(all, "  ", false, false) { 0 }.size)
    }

    @Test
    fun `运行中与待处理筛选可叠加`() {
        val running = item("r").copy(displayStatus = "running")
        val pending = item("p")
        val idle = item("i")
        val all = listOf(running, pending, idle)
        val pendingOf = { id: String -> if (id == "p") 1 else 0 }

        assertEquals(listOf("r"), homeVisibleSessions(all, "", true, false, pendingOf).map { it.taskId })
        assertEquals(listOf("p"), homeVisibleSessions(all, "", false, true, pendingOf).map { it.taskId })
        // 同时勾选：两个条件都要满足 → 无交集
        assertEquals(emptyList<String>(), homeVisibleSessions(all, "", true, true, pendingOf).map { it.taskId })
    }
}
