package com.zcode.remote.service

import com.zcode.remote.relay.ConversationChannel
import com.zcode.remote.relay.RelayClient
import com.zcode.remote.relay.RpcChannel
import com.zcode.remote.relay.SessionsIndexChannel
import com.zcode.remote.relay.WorkspaceConfigChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 进程级连接管理器（任务书 §11.4 阶段①「旁路」落地）。
 *
 * 目标架构里「连接栈 + 连接编排」的真正持有方：生命周期 = 进程，由前台服务 [ConnectionService]
 * 保活、由其 onDestroy 统一收口。此前连接栈只挂在 [ConnectionScope] 的静态字段上，而
 * **事件流 collector 全部跑在 AppViewModel.viewModelScope** —— Activity 销毁、ViewModel 被清后
 * collector 全灭：连接对象虽存活、事件却断流（锁屏审批可达性的隐患）。
 *
 * 三步回退计划（§11.4，任一步出问题可整体回退）：
 *  ① 旁路：本类接管字段存储，[ConnectionScope] 变为读写代理，**调用点零改动、零行为变化**（本提交）；
 *  ② 单事件流迁移：把 relayState / inbound / 任务事件等 collector 逐条迁入 [scope]，每条独立 commit；
 *  ③ 完整切换：connect() 编排整体迁入，AppViewModel 只留 UI 状态映射。
 *
 * 阶段①刻意只做存储搬运 + 提供进程级 [scope] 供②③落位。
 */
object RemoteConnectionManager {

    /** 进程级协程作用域：②③迁移 collector 的落点；[shutdown] 时回收并重建，保证可再次 connect。 */
    @Volatile
    var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private set

    var client: RelayClient? = null
    var channel: RpcChannel? = null
    var conversation: ConversationChannel? = null
    var sessionsIndex: SessionsIndexChannel? = null
    var workspaceConfig: WorkspaceConfigChannel? = null

    /** 用户主动断开（首页「断开」/移除设备/忘记配对）：此后服务重启不得自动重连。 */
    @Volatile
    var manuallyDisconnected: Boolean = false

    val isAlive: Boolean get() = client != null

    // ---- §11.4 阶段②：单事件流迁移的落位基础设施 ----
    private val collectorJobs = mutableListOf<Job>()

    /**
     * 在进程级作用域里启动一条事件流 collector。
     * connect() 重建连接栈前经 [cancelCollectors] 统一取消 —— 否则每次重连都会
     * 叠加一条旧流，旧流继续写旧实例（重复订阅/重复通知）。
     */
    fun launchCollector(block: suspend CoroutineScope.() -> Unit): Job =
        scope.launch(block = block).also { collectorJobs += it }

    /** 取消当前全部 collector（connect() 重建连接栈前调用）。 */
    fun cancelCollectors() {
        collectorJobs.forEach { it.cancel() }
        collectorJobs.clear()
    }

    /**
     * 统一清理（§11.4：onDestroy 收口）。关闭连接栈并回收进程级协程作用域；
     * 作用域随即重建，使下次 connect() 的 collector 仍有落点。
     */
    fun shutdown() {
        runCatching { client?.close() }
        runCatching { channel?.reset() }
        client = null
        channel = null
        conversation = null
        sessionsIndex = null
        workspaceConfig = null
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
