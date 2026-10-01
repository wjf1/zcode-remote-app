package com.zcode.remote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zcode.remote.relay.ApprovalOption
import com.zcode.remote.relay.PendingApproval
import com.zcode.remote.relay.SessionItem
import com.zcode.remote.ui.screens.*
import com.zcode.remote.ui.theme.ZCodeTheme
import com.zcode.remote.ui.theme.ZCodeTokens

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
                    var scanning by rememberSaveable { mutableStateOf(false) }
                    var opened by remember { mutableStateOf<SessionItem?>(null) }
                    var showGuide by rememberSaveable { mutableStateOf(false) }
                    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

                    // 全局物理返回键拦截（防止直接退出应用）
                    BackHandler(enabled = opened != null || showGuide || scanning) {
                        when {
                            opened != null -> opened = null
                            showGuide -> showGuide = false
                            scanning && vm.device != null -> scanning = false
                        }
                    }

                    val target = opened
                    when {
                        // 1. 保活指引全屏页
                        showGuide -> KeepAliveGuideScreen(onBack = { showGuide = false })

                        // 2. 扫码配对全屏页
                        vm.device == null || scanning -> ScanScreen(
                            onPaired = { vm.pair(it); scanning = false },
                        )

                        // 3. 沉浸式会话详情页
                        target != null -> ConversationScreen(
                            title = vm.conversationMeta.title ?: target.title,
                            status = vm.conversationStatus,
                            meta = vm.conversationMeta,
                            rows = vm.rows.toList(),
                            approvals = vm.approvals,
                            elicitations = vm.elicitations,
                            approvalFeedback = vm.approvalFeedback,
                            earlier = vm.earlier,
                            prompt = vm.promptDraft,
                            sending = vm.sending,
                            canStop = vm.conversationMeta.canStop == true,
                            stopState = vm.conversationMeta.stopState,
                            commandFeedback = vm.commandFeedback,
                            attachments = vm.attachments.toList(),
                            attachUploadName = vm.attachUpload?.name,
                            attachUploadPercent = vm.attachUpload?.percent ?: 0,
                            onResolve = { a: PendingApproval, opt: ApprovalOption -> vm.resolve(a, opt) },
                            onElicitationAccept = { el, answers -> vm.answerElicitation(el, answers) },
                            onElicitationDecline = { vm.declineElicitation(it) },
                            onElicitationFreeText = { el, text -> vm.answerElicitationFreeText(el, text) },
                            onLoadEarlier = { vm.loadEarlier() },
                            onFeedbackSeen = { vm.consumeApprovalFeedback() },
                            onPromptChange = { vm.updatePromptDraft(it) },
                            onSend = { vm.sendPrompt() },
                            onStop = { vm.stopSession() },
                            onAttachmentPicked = { uri, name, mime, size -> vm.addAttachment(uri, name, mime, size) },
                            onRemoveAttachment = { vm.removeAttachment(it) },
                            availableModels = vm.allAvailableModels,
                            modelReasoningLevels = vm.modelReasoningLevels,
                            onSwitchModel = { m -> vm.switchCurrentSessionModel(m) },
                            onSwitchModelCustom = { id -> vm.switchCurrentSessionModelCustom(id) },
                            onLoadModels = { vm.loadWorkspaceModels() },
                            onBack = { opened = null },
                        )

                        // 4. 底部 3-Tab 主工作台框架
                        else -> {
                            val totalPending = vm.approvals.size + vm.elicitations.size +
                                    vm.sessionPending.values.sum()

                            Scaffold(
                                bottomBar = {
                                    NavigationBar(
                                        modifier = Modifier.navigationBarsPadding(),
                                        containerColor = MaterialTheme.colorScheme.surface,
                                        tonalElevation = 4.dp
                                    ) {
                                        // Tab 0: 会话列表
                                        NavigationBarItem(
                                            selected = selectedTab == 0,
                                            onClick = { selectedTab = 0 },
                                            icon = { Icon(Icons.Default.List, contentDescription = "会话") },
                                            label = {
                                                Text(
                                                    "会话",
                                                    fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal
                                                )
                                            }
                                        )

                                        // Tab 1: 待办与审批 (带Badge)
                                        NavigationBarItem(
                                            selected = selectedTab == 1,
                                            onClick = { selectedTab = 1 },
                                            icon = {
                                                BadgedBox(
                                                    badge = {
                                                        if (totalPending > 0) {
                                                            Badge(
                                                                containerColor = ZCodeTokens.StatusPending,
                                                                contentColor = MaterialTheme.colorScheme.onPrimary
                                                            ) {
                                                                Text(if (totalPending > 99) "99+" else "$totalPending")
                                                            }
                                                        }
                                                    }
                                                ) {
                                                    Icon(Icons.Default.Notifications, contentDescription = "待办与审批")
                                                }
                                            },
                                            label = {
                                                Text(
                                                    "待办",
                                                    fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal
                                                )
                                            }
                                        )

                                        // Tab 2: 设置与设备
                                        NavigationBarItem(
                                            selected = selectedTab == 2,
                                            onClick = { selectedTab = 2 },
                                            icon = { Icon(Icons.Default.Settings, contentDescription = "设置") },
                                            label = {
                                                Text(
                                                    "设置",
                                                    fontWeight = if (selectedTab == 2) FontWeight.Bold else FontWeight.Normal
                                                )
                                            }
                                        )
                                    }
                                }
                            ) { innerPadding ->
                                AnimatedContent(
                                    targetState = selectedTab,
                                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                                    label = "tab_switch",
                                    modifier = Modifier.padding(innerPadding)
                                ) { tab ->
                                    when (tab) {
                                        0 -> HomeScreen(
                                            deviceName = vm.device?.deviceName ?: "",
                                            state = vm.relayState,
                                            sessions = vm.sessions.toList(),
                                            events = vm.events.toList(),
                                            bridgeState = vm.bridgeState,
                                            rpcEvents = vm.rpcEvents.toList(),
                                            devices = vm.devices,
                                            activeSid = vm.device?.deviceSid,
                                            sessionPending = vm.sessionPending,
                                            query = vm.sessionQuery,
                                            onQueryChange = { vm.updateSessionQuery(it) },
                                            subscribedSessionId = vm.subscribedSessionId,
                                            desktopActiveTaskId = vm.desktopActiveTaskId,
                                            onSessionClick = { s -> vm.openSession(s); opened = s },
                                            onDisconnect = { vm.disconnect() },
                                            onRescan = { opened = null; scanning = true },
                                            onNavigateToSettings = { selectedTab = 2 },
                                            modelState = vm.workspaceModelState,
                                            modelsLoading = vm.modelStateLoading,
                                            onLoadModels = { vm.loadWorkspaceModels() },
                                            currentModel = vm.conversationMeta.model,
                                            currentProvider = vm.conversationMeta.provider,
                                            availableModels = vm.allAvailableModels,
                                            modelReasoningLevels = vm.modelReasoningLevels,
                                            onSessionDelete = { vm.deleteSession(it) },
                                            onCreateSession = { prompt, modelOption ->
                                                vm.createNewSession(
                                                    firstPrompt = prompt,
                                                    attachments = emptyList(),
                                                    modelOption = modelOption,
                                                    onSuccess = { item -> opened = item },
                                                    onError = { err ->
                                                        android.widget.Toast.makeText(this@MainActivity, err, android.widget.Toast.LENGTH_SHORT).show()
                                                    }
                                                )
                                            }
                                        )

                                        1 -> ApprovalsTab(
                                            approvals = vm.approvals,
                                            elicitations = vm.elicitations,
                                            sessions = vm.sessions.toList(),
                                            sessionPending = vm.sessionPending,
                                            subscribedSessionId = vm.subscribedSessionId,
                                            feedback = vm.commandFeedback ?: vm.approvalFeedback,
                                            onResolveApproval = { a, opt -> vm.resolve(a, opt) },
                                            onAcceptElicitation = { el, answers -> vm.answerElicitation(el, answers) },
                                            onDeclineElicitation = { vm.declineElicitation(it) },
                                            onFreeTextElicitation = { el, text -> vm.answerElicitationFreeText(el, text) },
                                            onOpenSession = { s -> vm.openSession(s); opened = s }
                                        )

                                        2 -> SettingsTab(
                                            deviceName = vm.device?.deviceName ?: "",
                                            state = vm.relayState,
                                            bridgeState = vm.bridgeState,
                                            devices = vm.devices,
                                            activeSid = vm.device?.deviceSid,
                                            endpointMode = vm.endpointMode,
                                            customRelayUrl = vm.customRelayUrl,
                                            themeMode = vm.themeMode,
                                            updateState = vm.updateState,
                                            githubToken = vm.githubToken,
                                            onSwitchDevice = { vm.switchDevice(it) },
                                            onRemoveDevice = { vm.removeDevice(it) },
                                            onDisconnect = { vm.disconnect() },
                                            onRescan = { opened = null; scanning = true },
                                            onEndpointChange = { m, u -> vm.setEndpoint(m, u) },
                                            onThemeChange = { m -> vm.setTheme(m) },
                                            onShowGuide = { showGuide = true },
                                            onSetGithubToken = { vm.updateGithubToken(it) },
                                            onCheckUpdate = { vm.checkForUpdate() }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
