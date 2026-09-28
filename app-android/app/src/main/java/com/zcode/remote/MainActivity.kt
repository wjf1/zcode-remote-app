package com.zcode.remote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zcode.remote.relay.ApprovalOption
import com.zcode.remote.relay.PendingApproval
import com.zcode.remote.relay.SessionItem
import com.zcode.remote.ui.screens.ConversationScreen
import com.zcode.remote.ui.screens.KeepAliveGuideScreen
import com.zcode.remote.ui.screens.HomeScreen
import com.zcode.remote.ui.screens.ScanScreen
import com.zcode.remote.ui.theme.ZCodeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val vm: AppViewModel = viewModel()
            ZCodeTheme(forceDark = when (vm.themeMode) {
                "dark" -> true
                "light" -> false
                else -> null
            }) {
                Surface(Modifier.fillMaxSize()) {
                var scanning by remember { mutableStateOf(false) }
                var opened by remember { mutableStateOf<SessionItem?>(null) }
                var showGuide by remember { mutableStateOf(false) }

                val target = opened
                when {
                    showGuide -> KeepAliveGuideScreen(onBack = { showGuide = false })
                    vm.device == null || scanning -> ScanScreen(
                        onPaired = { vm.pair(it); scanning = false },
                    )
                    target != null -> ConversationScreen(
                        title = vm.conversationMeta.title ?: target.title,
                        status = vm.conversationStatus,
                        meta = vm.conversationMeta,
                        rows = vm.rows.toList(),
                        approvals = vm.approvals,
                        approvalFeedback = vm.approvalFeedback,
                        earlier = vm.earlier,
                        onResolve = { a: PendingApproval, opt: ApprovalOption -> vm.resolve(a, opt) },
                        onLoadEarlier = { vm.loadEarlier() },
                        onFeedbackSeen = { vm.consumeApprovalFeedback() },
                        onBack = { opened = null },
                    )
                    else -> HomeScreen(
                        deviceName = vm.device?.deviceName ?: "",
                        state = vm.relayState,
                        sessions = vm.sessions.toList(),
                        events = vm.events.toList(),
                        bridgeState = vm.bridgeState,
                        rpcEvents = vm.rpcEvents.toList(),
                        devices = vm.devices,
                        activeSid = vm.device?.deviceSid,
                        onSwitchDevice = { vm.switchDevice(it) },
                        onRemoveDevice = { vm.removeDevice(it) },
                        endpointMode = vm.endpointMode,
                        customRelayUrl = vm.customRelayUrl,
                        themeMode = vm.themeMode,
                        onEndpointChange = { m, u -> vm.setEndpoint(m, u) },
                        onThemeChange = { m -> vm.setTheme(m) },
                        onShowGuide = { showGuide = true },
                        onSessionClick = { s -> vm.openSession(s); opened = s },
                        onDisconnect = { vm.disconnect() },
                        onRescan = { opened = null; scanning = true },   // 添加设备：不清现有凭据
                    )
                }
                }
            }
        }
    }
}
