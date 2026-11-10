package com.zcode.remote.ui.screens

import com.zcode.remote.relay.FailureReason
import com.zcode.remote.relay.RelayState
import com.zcode.remote.storage.ProtocolStack
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 连接过程分段进度映射（对标官方 `webRemoteControlConnectingScreen` `STEP_INDEX`：
 * connecting:0 authenticating:1 waiting:2 paired:3，failed 不高亮）。
 * 第 4 段完成信号 = T5 的 controllerSynced（V1 恒 true → Paired 即全完成）。
 */
class HomeConnectionPhasesTest {
    @Test
    fun `前三段与官方 STEP_INDEX 一致`() {
        assertEquals(0, connectionPhaseIndex(RelayState.Connecting, ProtocolStack.V2, controllerSynced = false))
        assertEquals(1, connectionPhaseIndex(RelayState.Authenticating, ProtocolStack.V2, controllerSynced = false))
        assertEquals(2, connectionPhaseIndex(RelayState.WaitingPeer, ProtocolStack.V2, controllerSynced = false))
    }

    @Test
    fun `paired 后 V2 未同步为第4段 已同步为全完成`() {
        assertEquals(3, connectionPhaseIndex(RelayState.Paired, ProtocolStack.V2, controllerSynced = false))
        assertEquals(4, connectionPhaseIndex(RelayState.Paired, ProtocolStack.V2, controllerSynced = true))
    }

    @Test
    fun `V1 恒视为已同步 paired 即全完成`() {
        assertEquals(4, connectionPhaseIndex(RelayState.Paired, ProtocolStack.V1, controllerSynced = true))
    }

    @Test
    fun `idle 与终态失败不高亮任何段`() {
        assertEquals(-1, connectionPhaseIndex(RelayState.Idle, ProtocolStack.V2, controllerSynced = false))
        assertEquals(
            -1,
            connectionPhaseIndex(
                RelayState.Failed(FailureReason.DESKTOP_DISCONNECTED, null),
                ProtocolStack.V2,
                controllerSynced = false,
            ),
        )
    }
}
