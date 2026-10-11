package com.zcode.remote.service

import com.zcode.remote.relay.ConversationChannel
import com.zcode.remote.relay.RelayClient
import com.zcode.remote.relay.RpcChannel
import com.zcode.remote.relay.SessionsIndexChannel
import com.zcode.remote.relay.WorkspaceConfigChannel

/**
 * 进程级连接作用域（Sprint 1 / P0-A）。
 *
 * **§11.4 阶段①起变为读写代理**：字段实际存储已迁入 [RemoteConnectionManager]（由前台服务
 * [ConnectionService] 保护进程、onDestroy 统一收口）。保留本 facade 是为了让 AppViewModel 的
 * 既有引用点零改动、迁移可逐条回退；②③完成后本类随之删除。
 */
object ConnectionScope {
    var client: RelayClient?
        get() = RemoteConnectionManager.client
        set(value) {
            RemoteConnectionManager.client = value
        }
    var channel: RpcChannel?
        get() = RemoteConnectionManager.channel
        set(value) {
            RemoteConnectionManager.channel = value
        }
    var conversation: ConversationChannel?
        get() = RemoteConnectionManager.conversation
        set(value) {
            RemoteConnectionManager.conversation = value
        }
    var sessionsIndex: SessionsIndexChannel?
        get() = RemoteConnectionManager.sessionsIndex
        set(value) {
            RemoteConnectionManager.sessionsIndex = value
        }
    var workspaceConfig: WorkspaceConfigChannel?
        get() = RemoteConnectionManager.workspaceConfig
        set(value) {
            RemoteConnectionManager.workspaceConfig = value
        }

    /** 用户主动断开（首页「断开」/移除设备/忘记配对）：此后服务重启不得自动重连。 */
    var manuallyDisconnected: Boolean
        get() = RemoteConnectionManager.manuallyDisconnected
        set(value) {
            RemoteConnectionManager.manuallyDisconnected = value
        }

    val isAlive: Boolean get() = RemoteConnectionManager.isAlive
}
