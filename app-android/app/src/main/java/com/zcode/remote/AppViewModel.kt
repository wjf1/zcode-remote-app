package com.zcode.remote

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zcode.remote.relay.BridgeFrames
import com.zcode.remote.relay.ConversationChannel
import com.zcode.remote.relay.ConversationRow
import com.zcode.remote.relay.RelayClient
import com.zcode.remote.relay.RelayState
import com.zcode.remote.relay.RowStore
import com.zcode.remote.relay.RpcChannel
import com.zcode.remote.relay.SessionItem
import com.zcode.remote.relay.TaskEvent
import com.zcode.remote.relay.parseBootstrapSessions
import com.zcode.remote.storage.CredentialStore
import com.zcode.remote.storage.PairedDevice
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 应用状态中枢：配对 → 中继连接 → bootstrap（会话列表）→ 开桥 → RPC 会话流。
 * 协议见 PROTOCOL.md 与 research/FRAME-CODEC.md。
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = CredentialStore(app)

    var device by mutableStateOf(store.load())
        private set
    var relayState by mutableStateOf<RelayState>(RelayState.Idle)
        private set

    val events = mutableStateListOf<TaskEvent>()
    val sessions = mutableStateListOf<SessionItem>()

    // ---- 会话桥 / RPC ----
    var bridgeState by mutableStateOf<RpcChannel.BridgeState>(RpcChannel.BridgeState.Closed)
        private set
    var activeWorkspaceKey by mutableStateOf<String?>(null)
        private set
    var subscribedSessionId by mutableStateOf<String?>(null)
        private set
    /** 最近收到的 RPC 事件（原始 JSON，供 M2 阶段观测/渲染）。 */
    val rpcEvents = mutableStateListOf<String>()

    // ---- 会话流 ----
    /** 当前会话的行（已按 rowId 排序，UI 直接渲染）。 */
    val rows = mutableStateListOf<ConversationRow>()
    private val rowStore = RowStore(rows)
    var conversationStatus by mutableStateOf<ConversationChannel.Status>(ConversationChannel.Status.Idle)
        private set
    var conversationMeta by mutableStateOf(ConversationChannel.ConversationMeta())
        private set

    private var client: RelayClient? = null
    private var channel: RpcChannel? = null
    private var conversation: ConversationChannel? = null

    init {
        if (device != null) connect()
    }

    fun pair(newDevice: PairedDevice) {
        store.save(newDevice)
        device = newDevice
        connect()
    }

    fun connect() {
        val dev = device ?: return
        client?.close()
        channel?.reset()
        conversation?.reset()
        val c = RelayClient(dev)
        val ch = RpcChannel(c)
        val conv = ConversationChannel(ch)
        client = c
        channel = ch
        conversation = conv
        c.connect()

        viewModelScope.launch {
            c.state.collect { st ->
                relayState = st
                if (st is RelayState.Paired) {
                    ch.reset()
                    c.sendPayload(BridgeFrames.bootstrapRequest("boot-${System.currentTimeMillis()}"))
                }
            }
        }

        viewModelScope.launch {
            c.inbound.collect { payload ->
                when (BridgeFrames.zcodeTypeOf(payload)) {
                    "bootstrap-response" -> {
                        val list = parseBootstrapSessions(payload)
                        if (list.isNotEmpty()) {
                            sessions.clear(); sessions.addAll(list)
                        }
                        // 取活动工作区 → 开桥（会话流前置条件）
                        val wsKey = activeWorkspaceKeyOf(payload)
                        if (wsKey != null) {
                            activeWorkspaceKey = wsKey
                            if (ch.bridge.value !is RpcChannel.BridgeState.Ready) ch.openBridge(wsKey)
                        }
                    }
                    "workspace-bridge-ready",
                    "workspace-bridge-error",
                    "bridge-degraded" -> ch.onBridgeFrame(payload)

                    "rpc-frame" -> ch.onRpcFrame(payload)
                    "rpc-frame-ack" -> Unit

                    "workspace-list-response", "workspace-list-updated" -> Unit
                    null -> TaskEvent.from(payload)?.let { ev ->
                        events.add(0, ev)
                        if (events.size > 200) events.removeAt(events.lastIndex)
                    }
                    else -> Unit
                }
            }
        }

        viewModelScope.launch {
            ch.bridge.collect { st ->
                bridgeState = st
                Log.i(TAG, "bridge=$st")
                if (st is RpcChannel.BridgeState.Ready) {
                    val target = sessions.firstOrNull { it.isRunning } ?: sessions.firstOrNull()
                    target?.let { subscribeConversation(it) }
                }
            }
        }

        viewModelScope.launch {
            ch.events.collect { ev ->
                // 会话帧优先交给会话层解析
                conv.onEvent(ev, rowStore)
                val text = ev.data.toString()
                Log.i(TAG, "rpc event id=${ev.id}: ${text.take(800)}")
                rpcEvents.add(0, text.take(4000))
                if (rpcEvents.size > 50) rpcEvents.removeAt(rpcEvents.lastIndex)
            }
        }

        viewModelScope.launch { conv.status.collect { conversationStatus = it } }
        viewModelScope.launch { conv.meta.collect { conversationMeta = it } }
    }

    /**
     * 订阅某会话的流式内容。
     *
     * 走 zcode-agent 通道的官方序列：listen → hello → initialize → subscribe
     * （见 research/CONVERSATION-PROTOCOL.md §4）。
     */
    private fun subscribeConversation(s: SessionItem) {
        val conv = conversation ?: return
        val ws = s.workspacePath ?: activeWorkspaceKey ?: return
        subscribedSessionId = s.taskId
        Log.i(TAG, "subscribe conversation session=${s.taskId} ws=$ws")
        conv.subscribe(
            workspacePath = ws,
            workspaceIdentity = null,
            session = s.taskId,
            store = rowStore,
        )
    }

    /** 手动订阅指定会话（UI 点击会话卡片时调用）。 */
    fun openSession(s: SessionItem) = subscribeConversation(s)

    /** 从 bootstrap-response 取当前活动工作区 key。 */
    private fun activeWorkspaceKeyOf(payload: JsonObject): String? {
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

    fun disconnect() {
        client?.close()
        channel?.reset()
        conversation?.reset()
        client = null
        channel = null
        conversation = null
    }

    fun forget() {
        disconnect()
        store.clear()
        device = null
        events.clear()
        sessions.clear()
        rpcEvents.clear()
        rows.clear()
        subscribedSessionId = null
    }

    companion object { private const val TAG = "AppViewModel" }
}
