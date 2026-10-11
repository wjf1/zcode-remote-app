package com.zcode.remote.service

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import com.zcode.remote.BuildConfig
import com.zcode.remote.notify.ApprovalBridge
import com.zcode.remote.notify.ElicitationBridge
import com.zcode.remote.notify.TerminalNotifier
import com.zcode.remote.relay.BridgeFrames
import com.zcode.remote.relay.ConversationChannel
import com.zcode.remote.relay.FailureReason
import com.zcode.remote.relay.NetworkGate
import com.zcode.remote.relay.RelayClient
import com.zcode.remote.relay.RelayState
import com.zcode.remote.relay.RpcChannel
import com.zcode.remote.relay.SessionItem
import com.zcode.remote.relay.SessionsIndexChannel
import com.zcode.remote.relay.TaskEvent
import com.zcode.remote.relay.WorkspaceConfigChannel
import com.zcode.remote.relay.displayStatusForPhase
import com.zcode.remote.relay.displayStatusForTaskEvent
import com.zcode.remote.relay.parseBootstrapSessions
import com.zcode.remote.repo.ApprovalRepository
import com.zcode.remote.repo.ConversationRepository
import com.zcode.remote.repo.ModelConfigRepository
import com.zcode.remote.repo.NotificationCoordinator
import com.zcode.remote.storage.PairedDevice
import com.zcode.remote.storage.SessionCacheStore
import com.zcode.remote.util.LogRedactor
import com.zcode.remote.util.ZLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 连接编排器（§11.7 结构性拆分：自 `AppViewModel.connect()` 原样搬出，只移动不改行为）。
 *
 * 职责：装配连接栈（RelayClient / RpcChannel / ConversationChannel / SessionsIndexChannel /
 * WorkspaceConfigChannel / NetworkGate）并挂接全部状态流 collector；A-2 握手自动重订与
 * 桥自动重开（2026-10-10 真机故障修复）两个连接韧性调度器也在此。
 *
 * 对 VM 状态的写入经 [ConnectionHost]（由 AppViewModel 实现）；对职责域的操作直接调用
 * 注入的 repo 实例——不感知 ViewModel 类型，无新增跨层依赖。
 */
class ConnectionCoordinator(
    private val host: ConnectionHost,
    private val convRepo: ConversationRepository,
    private val approvalRepo: ApprovalRepository,
    private val modelRepo: ModelConfigRepository,
    private val notifyCoordinator: NotificationCoordinator,
) {

    /** 宿主状态/能力视图（AppViewModel 实现）：connect 编排里仅有的对 VM 的触碰点。 */
    interface ConnectionHost {
        val hostContext: Context
        val hostScope: CoroutineScope
        val hostDevice: PairedDevice?

        fun hostRelayOverride(): String?

        var hostRelayState: RelayState
        val hostEvents: MutableList<TaskEvent>
        val hostSessions: MutableList<SessionItem>
        var hostBridgeState: RpcChannel.BridgeState
        var hostActiveWorkspaceKey: String?
        val hostRpcEvents: MutableList<String>
        var hostSessionModeOverride: String?

        fun hostRetrySubscribe()
    }

    /**
     * 握手自动重订计数（A-2）：每次新会话订阅归零；`Status.Live` 到达也归零。
     * 上限 1 次——再失败即置 Failed 并把重试权交回用户（UI 重试入口）。
     */
    private var handshakeRetryCount = 0
    private var handshakeRetryJob: kotlinx.coroutines.Job? = null

    /**
     * 桥自动重开计数与任务（2026-10-10 真机故障修复）：桥失败后此前无自愈路径，
     * 详见 [scheduleBridgeReopen]。桥就绪即归零。
     */
    private var bridgeReopenCount = 0
    private var bridgeReopenJob: kotlinx.coroutines.Job? = null

    /** A-2/删会话/断开共用：作废在途的握手自动重订并把预算归零。 */
    fun resetHandshakeRetry() {
        handshakeRetryJob?.cancel()
        handshakeRetryJob = null
        handshakeRetryCount = 0
    }

    fun connect() {
        val dev = host.hostDevice ?: return
        ConnectionScope.manuallyDisconnected = false
        // P0-A：前台服务保住进程 —— App 在后台/锁屏时连接与审批通知才可达（招牌功能的性命）
        ConnectionService.start(host.hostContext)
        // §11.4 阶段②：重建连接栈前先取消旧 collector —— 否则重连会叠加旧流，
        // 旧流继续写旧实例（重复订阅 / 重复审批卡 / 重复通知）。
        RemoteConnectionManager.cancelCollectors()
        ConnectionScope.client?.close()
        ConnectionScope.channel?.reset()
        ConnectionScope.conversation?.reset()
        val c =
            RelayClient(
                dev,
                // B 组：自报真实版本号（原默认 0.1.0，上游按 app_version 做能力协商时是哑雷）
                appVersion = BuildConfig.VERSION_NAME,
                relayWsUrlOverride = host.hostRelayOverride(),
            )
        // B 组：网络感知重连 —— 常驻监控：断网立即断 socket 调度重连（attempt 归零），
        // 恢复即刻放行（不等 OkHttp 迟到的失败回调，真机实证其延迟到恢复才冒出）
        val cm = (host.hostContext as Application).getSystemService(ConnectivityManager::class.java)
        val gate = NetworkGate(cm)
        gate.onLost = c::onNetworkLost
        // 网络恢复：重置退避 + 取消已排队的退避等待并立即重连（否则恢复要白等一轮退避，实测 ~47s）
        gate.onAvailable = c::onNetworkAvailable
        c.networkWait = gate::waitBeforeReconnect
        gate.startWatch()
        val ch = RpcChannel(c)
        // A-4：事件流 seq 缺口 → 会话重订阅（缺口帧已 ack、服务端不重发，重取权威快照是唯一自愈路径）。
        ch.onSequenceGap = { host.hostRetrySubscribe() }
        val conv = ConversationChannel(ch)
        conv.onRowsUpdated = { sid, updatedRows ->
            host.hostScope.launch {
                SessionCacheStore.saveRows(host.hostContext, sid, updatedRows)
            }
        }
        // C-6：服务端回显 userInput 行 → 移除对应的本地 pending 回显气泡
        conv.onUserInputEcho = { row -> convRepo.consumePendingEcho(row.text) }
        // 会话级错误原因回填首页列表：bootstrap 的 tasks[] 形状不含 lastError（PROTOCOL.md 实测样本），
        // 只有打开过的会话能从快照 control.lastError 拿到原因——聊胜于无，完整原因在会话页横幅。
        conv.onSessionError = { sid, err ->
            val idx = host.hostSessions.indexOfFirst { it.taskId == sid }
            if (idx >= 0 && host.hostSessions[idx].lastError != err) {
                host.hostSessions[idx] = host.hostSessions[idx].copy(lastError = err)
            }
        }
        // 会话相位回填首页列表：列表的 displayStatus 只在配对成功时的 bootstrap 取一次快照，
        // 全仓没有刷新入口——「会话页明明在跑、退回主界面却没有运行中的会话」就是这一半缺口。
        conv.onSessionPhase = { sid, phase ->
            val mapped = displayStatusForPhase(phase)
            if (mapped != null) {
                val idx = host.hostSessions.indexOfFirst { it.taskId == sid }
                if (idx >= 0 && host.hostSessions[idx].displayStatus != mapped) {
                    host.hostSessions[idx] = host.hostSessions[idx].copy(displayStatus = mapped)
                }
            }
        }
        val sidx = SessionsIndexChannel(ch)
        val wcfg = WorkspaceConfigChannel(ch)
        ConnectionScope.client = c
        ConnectionScope.channel = ch
        ConnectionScope.conversation = conv
        ConnectionScope.sessionsIndex = sidx
        ConnectionScope.workspaceConfig = wcfg
        c.connect()

        // §11.4 阶段③：relay 状态流迁入进程级作用域 —— 终态通知（KICKED/配对失效等）是
        // 锁屏可达性本体，VM 被清后也必须能发；引导触发与 Widget 刷新随流同迁。
        RemoteConnectionManager.launchCollector {
            c.state.collect { st ->
                host.hostRelayState = st
                // Sprint 2 / A 组第 3 项：终态失败发常驻系统通知 —— 手机在兜里时不能
                // 静默失去全部审批能力（KICKED/配对失效/协议升级用户都必须立刻知道）。
                val terminal =
                    (st as? RelayState.Failed)?.takeIf {
                        it.reason == FailureReason.KICKED || it.reason == FailureReason.AUTH_FAILED ||
                            it.reason == FailureReason.PROTOCOL_MISMATCH ||
                            it.reason == FailureReason.INVALID_ENDPOINT
                    }
                if (terminal != null) {
                    TerminalNotifier.show(host.hostContext, NotificationCoordinator.terminalTitle(terminal.reason), terminal.message)
                } else {
                    TerminalNotifier.clear(host.hostContext)
                }
                if (st is RelayState.Paired) {
                    ch.reset()
                    c.sendPayload(BridgeFrames.bootstrapRequest("boot-${System.currentTimeMillis()}"))
                    // 接管后立即上报视图状态（P1-2）：PC 端知道手机在看哪个工作区/会话
                    notifyCoordinator.reportViewState()
                }
                // 连接状态变化即刷新桌面 Widget（含断线 → 未连接态）
                notifyCoordinator.syncWidget()
            }
        }

        host.hostScope.launch {
            c.inbound.collect { payload ->
                when (BridgeFrames.zcodeTypeOf(payload)) {
                    "bootstrap-response" -> {
                        val list = parseBootstrapSessions(payload)
                        if (list.isNotEmpty()) {
                            host.hostSessions.clear()
                            host.hostSessions.addAll(list)
                            host.hostScope.launch { SessionCacheStore.save(host.hostContext, list) }
                        }
                        // 取活动工作区 → 开桥（会话流前置条件）
                        val wsKey = activeWorkspaceKeyOf(payload)
                        if (wsKey != null) {
                            host.hostActiveWorkspaceKey = wsKey
                            if (ch.bridge.value !is RpcChannel.BridgeState.Ready) ch.openBridge(wsKey)
                        }
                    }
                    "workspace-bridge-ready",
                    "workspace-bridge-error",
                    "bridge-degraded",
                    -> ch.onBridgeFrame(payload)

                    "rpc-frame" -> ch.onRpcFrame(payload)
                    "rpc-frame-ack" -> Unit

                    "workspace-list-response", "workspace-list-updated" -> Unit
                    null ->
                        TaskEvent.from(payload)?.let { ev ->
                            host.hostEvents.add(0, ev)
                            if (host.hostEvents.size > 200) host.hostEvents.removeAt(host.hostEvents.lastIndex)
                            // 审批推送走任务事件流（实测会话帧里没有 pendingInteractions）：
                            // permission_request 建卡，permission_resolved 按 requestId 撤卡。
                            when (ev.type) {
                                "permission_request" ->
                                    com.zcode.remote.relay.PendingApproval.fromTaskEvent(
                                        ev.raw,
                                        ev.taskId,
                                    )?.let { approvalRepo.ingestTaskApproval(it) }
                                "permission_resolved" ->
                                    ev.raw["requestId"]?.let {
                                        runCatching { it.jsonPrimitive.content }.getOrNull()
                                    }?.let { approvalRepo.resolveTaskApproval(it) }
                                "elicitation_request" ->
                                    com.zcode.remote.relay.PendingElicitation.fromTaskEvent(
                                        ev.raw,
                                        ev.taskId,
                                    )?.let { approvalRepo.ingestTaskElicitation(it) }
                                "elicitation_resolved" ->
                                    ev.raw["requestId"]?.let {
                                        runCatching { it.jsonPrimitive.content }.getOrNull()
                                    }?.let { approvalRepo.resolveTaskElicitation(it) }
                            }
                            // 列表状态实时化：任务事件是 host 推的实时流（PROTOCOL.md §6.2），而列表的
                            // displayStatus 只在 bootstrap 取过一次快照——不回写就会出现「会话页在跑、
                            // 退回主界面仍显示已完成」。映射规则见 displayStatusForTaskEvent。
                            ev.taskId?.let { tid ->
                                val mapped = displayStatusForTaskEvent(ev.type)
                                if (mapped != null) {
                                    val idx = host.hostSessions.indexOfFirst { it.taskId == tid }
                                    if (idx >= 0 && host.hostSessions[idx].displayStatus != mapped) {
                                        host.hostSessions[idx] = host.hostSessions[idx].copy(displayStatus = mapped)
                                    }
                                }
                            }
                        }
                    else -> Unit
                }
            }
        }

        // §11.4 阶段③：桥状态流迁入进程级作用域 —— 桥降级后的自动重开是连接韧性本体。
        RemoteConnectionManager.launchCollector {
            ch.bridge.collect { st ->
                host.hostBridgeState = st
                ZLog.i(TAG, "bridge=$st")
                if (st is RpcChannel.BridgeState.Ready) {
                    // 桥就绪：自动重开预算归零（下次失败可再走完整退避序列）
                    bridgeReopenCount = 0
                    bridgeReopenJob?.cancel()
                    bridgeReopenJob = null
                    // E-1：workspace 级 sessions-index 订阅（权威角标，一次订阅覆盖全部会话）
                    host.hostActiveWorkspaceKey?.let { sidx.subscribe(it, null) }
                    // 从 PC 端 ~/.zcode/v2/provider_config.json 预加载已配置模型目录
                    modelRepo.loadModelsFromConfigFile()
                    // 模型注册表：各模型的合法思考档位（createSession/switchModelConfig 必需）
                    modelRepo.loadModelReasoningLevels()
                    val target = host.hostSessions.firstOrNull { it.isRunning } ?: host.hostSessions.firstOrNull()
                    target?.let { convRepo.subscribeConversation(it) }
                }
                // 桥失败（服务端降级 `bridge-degraded` 或看门狗用尽）→ 自动重开（2026-10-10 真机故障修复）
                if (st is RpcChannel.BridgeState.Failed) scheduleBridgeReopen()
            }
        }

        // §11.4 阶段②：首条迁入进程级作用域的事件流 —— rpc 事件是会话流 / 会话角标 / 配置
        // 三通道的唯一入口，此前跑在 viewModelScope：Activity 销毁、VM 被清后这条流一断，
        // 锁屏审批、离线缓存、会话角标全部停摆（连接对象虽存活、事件却断流）。
        // 逻辑原样搬迁，仅换作用域；其余流在后续提交逐条迁移（每条独立 commit 可回退）。
        RemoteConnectionManager.launchCollector {
            ch.events.collect { ev ->
                // 会话帧优先交给会话层解析；sessions-index 帧交给 E-1 权威角标通道
                conv.onEvent(ev, convRepo.rowStore)
                sidx.onEvent(ev)
                wcfg.onEvent(ev)
                // C-5①：观测面板只在 debug 构建保留——release 不再为**每个 delta** 做一次
                // `ev.data.toString()`（全量 JSON 序列化，主线程 collector 内）与 4000 字符拷贝。
                // ZLog.d 本身在 release 已被 R8 剥离（proguard -assumenosideeffects），但实参求值
                // 不会——这才是真凶（任务书 §5 C-5① 纠正点：v1 误判为「ZLog 未剥离」）。
                if (BuildConfig.DEBUG) {
                    val text = ev.data.toString()
                    // P0 日志止血：logcat 里也不落会话正文（canary 验收要求 debug 日志同样零命中）
                    ZLog.d(TAG, "rpc event id=${ev.id} data=${LogRedactor.payloadLabel(text)}")
                    host.hostRpcEvents.add(0, text.take(4000))
                    if (host.hostRpcEvents.size > 50) host.hostRpcEvents.removeAt(host.hostRpcEvents.lastIndex)
                }
            }
        }

        // §11.4 阶段③：会话状态流迁入进程级作用域 —— 握手瞬态失败的自动重订（A-2）是连接韧性本体。
        RemoteConnectionManager.launchCollector {
            conv.status.collect { st ->
                convRepo.conversationStatus = st
                // 换会话/重订阅时清掉上一个会话的模式乐观覆盖，回到订阅 ack 的真实值
                if (st is ConversationChannel.Status.Idle) host.hostSessionModeOverride = null
                // 模型目录订阅需要会话握手完成（真机实证：bridge Ready 即订会报
                // fault.connection.handshakeRequired，Live 后才可用）
                if (st is ConversationChannel.Status.Live) {
                    handshakeRetryCount = 0
                    host.hostActiveWorkspaceKey?.let { wcfg.subscribe(it, null) }
                }
                // A-2：握手瞬态失败（超时）→ 退避自动重订一次，避免永久停在「握手中…」
                if (st is ConversationChannel.Status.Failed && st.retryable) {
                    scheduleHandshakeRetry()
                }
            }
        }
        host.hostScope.launch { conv.meta.collect { convRepo.conversationMeta = it } }
        host.hostScope.launch { conv.snapshotAligned.collect { convRepo.snapshotAligned = it } }
        host.hostScope.launch { conv.sessionState.collect { convRepo.sessionState = it } }
        host.hostScope.launch { conv.earlier.collect { convRepo.earlier = it } }
        // §11.4 阶段②（续）：审批/表单「通知链路」迁入进程级作用域 —— 通知是锁屏可达性的本体，
        // 必须在 VM 被清后仍能发出。refreshApprovals/refreshElicitations 内部无 viewModelScope
        // 依赖（context 在 VM 被清后仍可用），故原样换作用域。
        RemoteConnectionManager.launchCollector {
            conv.interactions.collect { approvalRepo.refreshApprovals() }
        }
        RemoteConnectionManager.launchCollector {
            conv.elicitations.collect { approvalRepo.refreshElicitations() }
        }
        RemoteConnectionManager.launchCollector {
            sidx.summaries.collect { notifyCoordinator.recomputeSessionPending() }
        }
        // 通知按钮 → 应答（连接只活在这里，所以桥必须在连接建立时挂上）
        ApprovalBridge.handler = { interactionId, optionId -> approvalRepo.resolveById(interactionId, optionId) }
        ElicitationBridge.handler = { interactionId, answerJson ->
            approvalRepo.resolveElicitationById(interactionId, answerJson)
        }
    }

    /**
     * A-2：握手超时等瞬态失败后的自动重订（退避 1s → 2s，上限 1 次）。
     *
     * 只重发订阅，不重载离线缓存、不清附件——那些是「进入会话」的一次性副作用。
     * 再失败则停在 Failed，重试权交回用户（UI 重试入口 retrySubscribe）。
     */
    private fun scheduleHandshakeRetry() {
        val s = convRepo.subscribedSession ?: return
        if (handshakeRetryCount >= MAX_HANDSHAKE_RETRIES) {
            ZLog.w(TAG, "握手自动重订已用尽（$handshakeRetryCount 次），停在失败态等用户重试")
            return
        }
        handshakeRetryCount += 1
        val delayMs = if (handshakeRetryCount == 1) 1_000L else 2_000L
        ZLog.i(TAG, "握手失败，${delayMs}ms 后自动重订（第 $handshakeRetryCount 次）")
        handshakeRetryJob?.cancel()
        // §11.4 阶段③：重试调度挂进程级作用域 —— VM 被清后自动重订仍要生效（连接韧性）。
        handshakeRetryJob =
            RemoteConnectionManager.scope.launch {
                kotlinx.coroutines.delay(delayMs)
                // 期间用户可能已切走会话，此时重订无意义
                if (convRepo.subscribedSessionId != s.taskId) return@launch
                val conv = ConnectionScope.conversation ?: return@launch
                val ws = s.workspacePath ?: host.hostActiveWorkspaceKey ?: return@launch
                ZLog.i(TAG, "自动重订 session=${s.taskId}")
                conv.subscribe(ws, null, s.taskId, convRepo.rowStore)
            }
    }

    /**
     * 桥失败后的自动重开（2026-10-10 真机故障修复）。
     *
     * 策略：退避 1s/2s/4s、上限 3 次（bridgeReopenDelayMs）；桥就绪即归零；用尽后停失败态
     * 交由上层兜底，不无限重试（避免把「服务端持续降级」放大成重开风暴）。
     */
    private fun scheduleBridgeReopen() {
        val wsKey = host.hostActiveWorkspaceKey ?: return
        val delayMs = bridgeReopenDelayMs(bridgeReopenCount + 1)
        if (delayMs == null) {
            ZLog.w(TAG, "桥自动重开已用尽（$bridgeReopenCount 次），停在失败态等待上层重连")
            return
        }
        bridgeReopenCount += 1
        bridgeReopenJob?.cancel()
        bridgeReopenJob =
            RemoteConnectionManager.scope.launch {
                kotlinx.coroutines.delay(delayMs)
                if (host.hostBridgeState is RpcChannel.BridgeState.Ready) return@launch // 期间已恢复（如 relay 重连撞回来）
                ZLog.i(TAG, "桥失败，${delayMs}ms 后自动重开（第 $bridgeReopenCount 次）ws=${LogRedactor.pathLabel(wsKey)}")
                ConnectionScope.channel?.openBridge(wsKey)
            }
    }

    /** 从 bootstrap-response 取当前活动工作区 key。 */
    private fun activeWorkspaceKeyOf(payload: kotlinx.serialization.json.JsonObject): String? {
        val result = payload["result"]?.let { runCatching { it.jsonObject }.getOrNull() } ?: return null
        val ivs = result["initialViewState"]?.let { runCatching { it.jsonObject }.getOrNull() }
        ivs?.get("activeWorkspaceKey")?.let {
            runCatching { it.jsonPrimitive.content }.getOrNull()?.let { k -> if (k.isNotBlank()) return k }
        }
        // 回退：取第一个会话的 workspacePath
        val tasks = result["tasks"]?.let { runCatching { it.jsonArray }.getOrNull() } ?: return null
        return tasks.firstOrNull()?.let {
            runCatching { it.jsonObject["workspacePath"]?.jsonPrimitive?.content }.getOrNull()
        }
    }

    companion object {
        private const val TAG = "ConnCoord"
        private const val MAX_HANDSHAKE_RETRIES = 1

        /**
         * 桥自动重开的退避延迟（纯函数，单测覆盖）：第 1/2/3 次 → 1s/2s/4s；
         * 超出上限（默认 3）返回 null = 不再重开（停失败态，等 relay 重连或用户操作兜底）。
         */
        internal fun bridgeReopenDelayMs(
            nextAttempt: Int,
            maxAttempts: Int = 3,
        ): Long? = if (nextAttempt in 1..maxAttempts) 1_000L shl (nextAttempt - 1) else null
    }
}
