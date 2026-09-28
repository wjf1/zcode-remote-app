package com.zcode.remote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zcode.remote.relay.SessionItem
import com.zcode.remote.ui.screens.ConversationScreen
import com.zcode.remote.ui.screens.HomeScreen
import com.zcode.remote.ui.screens.ScanScreen
import com.zcode.remote.ui.theme.ZCodeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ZCodeTheme {
                val vm: AppViewModel = viewModel()
                var scanning by remember { mutableStateOf(false) }
                var opened by remember { mutableStateOf<SessionItem?>(null) }

                val target = opened
                when {
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
                        onResolve = { a, opt -> vm.resolve(a, opt) },
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
                        onSessionClick = { s -> vm.openSession(s); opened = s },
                        onDisconnect = { vm.disconnect() },
                        onRescan = { vm.forget(); opened = null; scanning = true },
                    )
                }
            }
        }
    }
}
