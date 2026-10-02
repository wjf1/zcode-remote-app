package com.zcode.remote.service

import com.zcode.remote.relay.ConversationChannel
import com.zcode.remote.relay.RelayClient
import com.zcode.remote.relay.RpcChannel
import com.zcode.remote.relay.SessionsIndexChannel
import com.zcode.remote.relay.WorkspaceConfigChannel

/**
 * 进程级连接作用域（Sprint 1 / P0-A）。
 *
 * 中继连接栈的真正持有方，生命周期 = 进程（由 [ConnectionService] 前台服务保护），
 * 不再随 Activity / ViewModel 生灭：锁屏、切后台、划掉界面后连接与审批通知仍存活 ——
 * 这正是锁屏审批（本 App 招牌功能）可达性的前提。
 *
 * AppViewModel 通过读写代理访问这里的字段（见 AppViewModel.client 等属性），
 * 连接编排（bootstrap / 订阅 / 事件分发）仍留在 ViewModel。
 */
object ConnectionScope {
    var client: RelayClient? = null
    var channel: RpcChannel? = null
    var conversation: ConversationChannel? = null
    var sessionsIndex: SessionsIndexChannel? = null
    var workspaceConfig: WorkspaceConfigChannel? = null

    /** 用户主动断开（首页「断开」/移除设备/忘记配对）：此后服务重启不得自动重连。 */
    @Volatile
    var manuallyDisconnected: Boolean = false

    val isAlive: Boolean get() = client != null
}
