package com.zcode.remote.ui.screens

import com.zcode.remote.relay.FailureReason
import com.zcode.remote.relay.RelayState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页主按钮语义（真机走查 2026-11-10 发现的存量缺陷：按钮恒为「断开」，
 * 用户断开后无法从设置页重连）。
 */
class SettingsConnectionButtonTest {
    @Test
    fun `连接中与已连接显示断开`() {
        assertTrue(isLiveConnection(RelayState.Connecting))
        assertTrue(isLiveConnection(RelayState.Authenticating))
        assertTrue(isLiveConnection(RelayState.WaitingPeer))
        assertTrue(isLiveConnection(RelayState.Paired))
    }

    @Test
    fun `空闲与终态失败显示连接`() {
        assertFalse("断开后是 Idle——必须能从这里重新连上", isLiveConnection(RelayState.Idle))
        assertFalse(
            "终态失败（互踢/配对失效/桌面离线）会让用户去设置页，此时更要给「连接」入口",
            isLiveConnection(RelayState.Failed(FailureReason.DESKTOP_DISCONNECTED, null)),
        )
    }
}
