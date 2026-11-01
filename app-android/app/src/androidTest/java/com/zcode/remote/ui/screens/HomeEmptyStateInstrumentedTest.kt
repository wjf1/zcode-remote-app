package com.zcode.remote.ui.screens

import androidx.test.platform.app.InstrumentationRegistry
import com.zcode.remote.R
import com.zcode.remote.relay.FailureReason
import com.zcode.remote.relay.RelayState
import com.zcode.remote.relay.SessionItem
import com.zcode.remote.storage.ProtocolStack
import com.zcode.remote.util.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T5 首页空列表三态的仪器化取证（HANDOFF CI 覆盖缺口项）。
 *
 * `HomeEmptyStateTest`（JVM 单测）已把三态判定 / 文案 / 过滤语义逐项钉死；本类把**同一套纯函数
 * 断言**在真实 Android 运行时再跑一遍，并做一项 JVM 做不到的增强取证：`UiText.Res` 指向的
 * 资源条目能在真实 APK 资源表里解析出非空中文文案——单测环境只有 R 常量，解析不了资源。
 *
 * 全部为纯函数断言（不依赖 Espresso 注入 / `createComposeRule`，规避 API 37 真机 Espresso
 * 缺失问题），可在 CI API 35 emulator 直接跑。
 */
class HomeEmptyStateInstrumentedTest {
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

    // ---------- 三态判定（与 JVM 单测同口径） ----------

    @Test
    fun `三态判定——未配对是未连接态`() {
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
            assertEquals(HomeEmptyState.NotConnected, homeEmptyState(s, 0, ProtocolStack.V1, controllerSynced = true))
        }
    }

    @Test
    fun `三态判定——V2 已配对未同步是同步中态且快照到后是空态`() {
        assertEquals(
            HomeEmptyState.Syncing,
            homeEmptyState(RelayState.Paired, 0, ProtocolStack.V2, controllerSynced = false),
        )
        assertEquals(
            HomeEmptyState.Empty,
            homeEmptyState(RelayState.Paired, 0, ProtocolStack.V2, controllerSynced = true),
        )
    }

    @Test
    fun `三态判定——V1 配对即完成且有条目时不走空态`() {
        assertEquals(HomeEmptyState.Empty, homeEmptyState(RelayState.Paired, 0, ProtocolStack.V1, controllerSynced = false))
        assertEquals(HomeEmptyState.Empty, homeEmptyState(RelayState.Paired, 3, ProtocolStack.V2, controllerSynced = false))
    }

    // ---------- 三态文案（UiText 映射 + 真实资源解析增强） ----------

    @Test
    fun `三态文案——同步中与真空映射到不同资源`() {
        assertEquals(UiText.Res(R.string.home_syncing_sessions), homeEmptyStateText(HomeEmptyState.Syncing, RelayState.Paired))
        assertEquals(UiText.Res(R.string.home_no_sessions), homeEmptyStateText(HomeEmptyState.Empty, RelayState.Paired))
    }

    @Test
    fun `三态文案——未连接态沿用连接态映射`() {
        assertEquals(
            connectionEmptyStateText(RelayState.WaitingPeer),
            homeEmptyStateText(HomeEmptyState.NotConnected, RelayState.WaitingPeer),
        )
        assertEquals(
            connectionEmptyStateText(RelayState.Failed(FailureReason.AUTH_FAILED, "x")),
            homeEmptyStateText(HomeEmptyState.NotConnected, RelayState.Failed(FailureReason.AUTH_FAILED, "x")),
        )
    }

    @Test
    fun `三态文案——真实 APK 资源表可解析且两态文案可区分`() {
        // 仪器化增强：JVM 单测只有 R 常量，此处验证资源条目真实存在并可解析
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val syncing = ctx.resources.getString(R.string.home_syncing_sessions)
        val empty = ctx.resources.getString(R.string.home_no_sessions)
        assertTrue("同步中文案不得为空", syncing.isNotBlank())
        assertTrue("空列表文案不得为空", empty.isNotBlank())
        assertNotEquals("「还在同步」与「真的没有」必须可区分", syncing, empty)
    }

    // ---------- 过滤为空与真空的区分 + 过滤语义 ----------

    @Test
    fun `过滤空提示——过滤前无条目与有条目被挡是两种提示`() {
        assertEquals(UiText.Res(R.string.home_no_sessions), homeFilteredEmptyText(0))
        assertEquals(UiText.Res(R.string.home_no_match), homeFilteredEmptyText(5))
    }

    @Test
    fun `过滤语义——标题与工作区路径匹配且忽略大小写`() {
        val a = item("a").copy(title = "Refactor Auth", workspacePath = "/proj/alpha")
        val b = item("b").copy(title = "写文档", workspacePath = "/proj/BETA")
        val all = listOf(a, b)

        assertEquals(listOf("a"), homeVisibleSessions(all, "auth", false, false) { 0 }.map { it.taskId })
        assertEquals(listOf("b"), homeVisibleSessions(all, "beta", false, false) { 0 }.map { it.taskId })
        assertEquals(listOf("a"), homeVisibleSessions(all, "alpha", false, false) { 0 }.map { it.taskId })
        assertEquals(2, homeVisibleSessions(all, "  ", false, false) { 0 }.size)
    }

    @Test
    fun `过滤语义——运行中与待处理筛选可叠加`() {
        val running = item("r").copy(displayStatus = "running")
        val pending = item("p")
        val idle = item("i")
        val all = listOf(running, pending, idle)
        val pendingOf = { id: String -> if (id == "p") 1 else 0 }

        assertEquals(listOf("r"), homeVisibleSessions(all, "", true, false, pendingOf).map { it.taskId })
        assertEquals(listOf("p"), homeVisibleSessions(all, "", false, true, pendingOf).map { it.taskId })
        assertEquals(emptyList<String>(), homeVisibleSessions(all, "", true, true, pendingOf).map { it.taskId })
    }
}
