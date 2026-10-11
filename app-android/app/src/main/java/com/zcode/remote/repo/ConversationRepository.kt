package com.zcode.remote.repo

import android.content.Context
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zcode.remote.relay.ConversationChannel
import com.zcode.remote.relay.ConversationFrames
import com.zcode.remote.relay.ConversationRow
import com.zcode.remote.relay.RelayState
import com.zcode.remote.relay.RowStore
import com.zcode.remote.relay.SessionItem
import com.zcode.remote.relay.UserFacingError
import com.zcode.remote.storage.SessionCacheStore
import com.zcode.remote.util.LogRedactor
import com.zcode.remote.util.ZLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 会话流（§11.7 结构性拆分：自 `AppViewModel` 原样搬出，只移动不改行为）。
 * 持有当前会话的行存储与全部派生状态（meta / earlier / snapshotAligned / sessionState）、
 * 订阅与重订入口、翻页与本地 pending 回显气泡（C-6）。
 * 连接栈经 getter 注入；字段用 internal var 供 connect() 的状态 collector 直写
 * （通道在 connect 时才创建，repo 无法自挂 collector）。
 *
 * @param onNewSubscription 每次新会话订阅后的重订预算复位回调（A-2 调度器状态仍在 VM）。
 * @param clearAttachments 附件与会话绑定的清理回调（AttachmentUploader 在 VM 装配）。
 */
class ConversationRepository(
    private val scope: CoroutineScope,
    private val context: Context,
    private val conversation: () -> ConversationChannel?,
    private val activeWorkspaceKey: () -> String?,
    private val flash: (String, Boolean) -> Unit,
    private val onNewSubscription: () -> Unit,
    private val clearAttachments: () -> Unit,
) {
    private val TAG = "ConvRepo"

    /** 当前会话的行（已按 rowId 排序，UI 直接渲染）。 */
    val rows = mutableStateListOf<ConversationRow>()
    internal val rowStore = RowStore(rows)

    /**
     * C-5③：会话行的派生快照——仅在行内容变化时产生新实例。
     *
     * UI 侧原为每次重组都 `rows.toList()`：整表拷贝（长会话 1100+ 行）之外，
     * 新实例还会让 `remember(rows)` 的派生（SessionFiles / TurnChanges 逐行 JSON 解析）
     * 一起失效重算——输入框、滚动等无关重组也在付 O(n) 解析的代价。
     * 派生快照把「拷贝 + 下游失效」收敛为「行内容真的变化时」一次。
     * 契约（单测钉住）：内容不变 → 同一实例；内容变化 → 新实例。
     */
    val rowsSnapshot: List<ConversationRow> by derivedStateOf { rows.toList() }

    internal var conversationStatus by mutableStateOf<ConversationChannel.Status>(ConversationChannel.Status.Idle)
    internal var conversationMeta by mutableStateOf(ConversationChannel.ConversationMeta())
    /** 向上翻页状态（加载中/hasMore/pulled）。 */
    internal var earlier by mutableStateOf(ConversationChannel.EarlierState())

    /**
     * 当前会话的权威快照是否已落地（B-1）。UI 据此在「缓存行数 == 快照行数」
     * （rows.size 不变、旧触发器完全不跑）时也能定位到最新一行。
     */
    internal var snapshotAligned by mutableStateOf<ConversationChannel.SnapshotAligned?>(null)

    /**
     * 当前会话的会话级状态（上下文用量 / 目标 / 待办 / 后台任务 / 子智能体 / 排队输入）。
     * 面板与顶栏「状态」入口都读它；全空时 ConversationFrames.SessionState.hasContent
     * 为 false，入口不出现。
     */
    internal var sessionState by mutableStateOf(ConversationFrames.SessionState())

    internal var subscribedSessionId by mutableStateOf<String?>(null)
    /** 最近一次订阅的会话条目（A-2 自动重订需要原始 workspacePath/sessionId）。 */
    internal var subscribedSession: SessionItem? = null

    /**
     * 本地回显的待确认消息（C-6）：发送瞬间插入一条 pending 气泡，解决「按了没反应」的空窗期——
     * sendText ack 之后服务端要等 turn 排队才回显 userInput 行（快则几百毫秒、慢则数分钟）。
     *
     * 移除时机：服务端回显同文本的 userInput 行（ConversationChannel.onUserInputEcho）→ 移除；
     * 发送失败 → 立即撤回（错误横幅已说明原因）；切会话 / 断开连接 → 清空（气泡与会话绑定）。
     */
    data class PendingUserMessage(val id: Long, val text: String, val attachmentNames: List<String>)

    internal var pendingUserMessages by mutableStateOf<List<PendingUserMessage>>(emptyList())
    private var pendingSeq = 0L

    /** 发送域入口：插入一条 pending 回显气泡（返回供失败撤回）。 */
    fun addPendingMessage(text: String, attachmentNames: List<String>): PendingUserMessage {
        pendingSeq += 1
        val pending = PendingUserMessage(pendingSeq, text, attachmentNames)
        pendingUserMessages = pendingUserMessages + pending
        return pending
    }

    /** 发送失败撤回本地气泡：错误横幅已说明原因，不留下只在手机上存在的「幽灵消息」。 */
    fun removePendingMessage(id: Long) {
        pendingUserMessages = pendingUserMessages.filterNot { it.id == id }
    }

    /** 服务端回显到达时按文本匹配移除（多条 pending 时只移除最先插入的那条）。 */
    fun consumePendingEcho(echoText: String?) {
        pendingUserMessages = consumePendingByEcho(pendingUserMessages, echoText)
    }

    /** 切会话/断开：清空 pending 回显（气泡与会话绑定）。 */
    fun clearPendingMessages() {
        pendingUserMessages = emptyList()
    }

    /**
     * 订阅某会话的流式内容。
     *
     * 走 zcode-agent 通道的官方序列：listen → hello → initialize → subscribe
     * （见 research/CONVERSATION-PROTOCOL.md §4）。
     */
    internal fun subscribeConversation(s: SessionItem) {
        val conv = conversation() ?: return
        val ws = s.workspacePath ?: activeWorkspaceKey() ?: return
        subscribedSession = s
        subscribedSessionId = s.taskId
        // A-2：新会话订阅重置自动重订预算
        onNewSubscription()
        clearAttachments()   // 附件与会话绑定，切会话即清空
        pendingUserMessages = emptyList()   // C-6：本地回显气泡同样与会话绑定

        // Sprint 5 离线缓存秒开：点击会话卡片首帧立即呈现历史消息行。
        // C-5⑤（2026-10-10，真机故障后立项）：读取**移出主线程**——原实现同步 readText + JSON 解码
        // 阻塞 UI（大缓存会话进页首帧卡顿的来源之一）；改 IO 线程加载，且**仅在权威快照未落地时应用**
        // （否则慢磁盘读会覆盖已到达的新快照 = 旧盖新）；期间切走会话则丢弃。
        rowStore.clear()
        scope.launch(Dispatchers.IO) {
            val cached = SessionCacheStore.loadRows(context, s.taskId)
            if (cached.isEmpty()) return@launch
            scope.launch(Dispatchers.Main) {
                if (shouldApplyCachedRows(subscribedSessionId, s.taskId, rowStore.snapshot().isEmpty())) {
                    rowStore.replaceAll(cached)
                    ZLog.i(TAG, "离线缓存秒开（异步）: ${cached.size} 行 session=${s.taskId.take(24)}")
                }
            }
        }

        ZLog.i(TAG, "subscribe conversation session=${LogRedactor.maskId(s.taskId)} ws=${LogRedactor.pathLabel(ws)}")
        conv.subscribe(
            workspacePath = ws,
            workspaceIdentity = null,
            session = s.taskId,
            store = rowStore,
        )
    }

    /** 用户手动重试订阅（A-2 的 UI 重试入口）：重置重订预算后重新发起订阅。 */
    internal fun retrySubscribe() {
        val s = subscribedSession ?: return
        onNewSubscription()
        val conv = conversation() ?: run {
            flash("连接已断开，无法重试", true)
            return
        }
        val ws = s.workspacePath ?: activeWorkspaceKey() ?: run {
            flash("未定位到工作区，无法重试", true)
            return
        }
        ZLog.i(TAG, "用户重试订阅 session=${s.taskId}")
        conv.subscribe(ws, null, s.taskId, rowStore)
    }

    /** 向上拉一页更早历史（会话页滚到顶部时触发）。 */
    internal fun loadEarlier() {
        val conv = conversation() ?: return
        conv.loadEarlier(rowStore) { r ->
            r.onFailure { ZLog.w(TAG, "loadEarlier: ${it.message}") }
        }
    }

    companion object {
        /**
         * 服务端回显到达时的 pending 匹配移除（C-6 纯函数）：按文本 trim 相等匹配，
         * 只移除最先插入的一条；不匹配 / 空文本时原样返回
         * （回显可能是桌面端发的消息，不能误删本机的 pending 气泡）。
         */
        internal fun consumePendingByEcho(
            pending: List<PendingUserMessage>,
            echoText: String?,
        ): List<PendingUserMessage> {
            val text = echoText?.trim().orEmpty()
            if (text.isEmpty() || pending.isEmpty()) return pending
            val idx = pending.indexOfFirst { it.text.trim() == text }
            if (idx < 0) return pending
            return pending.filterIndexed { i, _ -> i != idx }
        }

        /**
         * 异步加载的离线缓存是否应落地（C-5⑤ 竞态判定，纯函数）：
         * 仅当「仍订阅着该会话」且「rowStore 为空（权威快照尚未 replaceAll）」时应用——
         * 否则慢磁盘读会把已到达的新快照覆盖成旧数据（旧盖新）。
         */
        internal fun shouldApplyCachedRows(
            subscribedSessionId: String?,
            taskId: String,
            storeIsEmpty: Boolean,
        ): Boolean = subscribedSessionId == taskId && storeIsEmpty
    }
}
