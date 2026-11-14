package com.zcode.remote.relay

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * mobile-task-view 上报发布器（G6b，2026-11-14 逐语义对齐官方
 * `webRemoteControlTaskView.ts` 的 `createWebRemoteControlTaskViewPublisher`）。
 *
 * 官方语义（`docs/web-remote-control/task-realtime-sync.md` mobileTaskView 节）：
 * - `mobileTaskView` 表示**前台实际查看地址**（与恢复用的 `mobileViewState` 区分）；
 *   Main 只保存瞬时事实、不写任务未读；
 * - epoch 由桌面 `mobile-task-view-request` 下发；epoch 变化时 seq 归零重计；
 * - seq 单调递增；send 失败（socket 满/断）保持 dirty，下次 flush 重发；
 * - 断连清 epoch 但保留 dirty——恢复后要求重新声明（新配对/bridge 代际拒绝旧消息）；
 * - 新 bridge（sessionId/generation 任一变化）先置 view=null：新桥的初始只是导航意图，
 *   必须等实际可见上报；旧会话的迟到回调不得清掉新 bridge 的可见事实（[matches] 过滤）；
 * - 配对请求可能先于 bootstrap：迟到 bootstrap 的 [seed] 不得把更新 epoch 改回去。
 */
internal class MobileTaskViewPublisher(
    private val send: (JsonObject) -> Boolean,
) {
    /** 桥身份（与 rpc-frame 三元组同源的 sessionId/generation 对）。 */
    data class BridgeIdentity(
        val bridgeSessionId: String,
        val bridgeGeneration: Int?,
    )

    /** 前台正在查看的任务（view=null = 不在前台看任何任务）。 */
    data class TaskView(
        val workspacePath: String,
        val workspaceIdentity: String?,
        val taskId: String,
    )

    private var bridge: BridgeIdentity? = null
    private var view: TaskView? = null
    private var epoch: String? = null
    private var seq = 0L
    private var dirty = false

    /** 桥身份是否为当前桥（旧 Root 迟到回调过滤）。 */
    fun matches(target: BridgeIdentity): Boolean =
        bridge?.bridgeSessionId == target.bridgeSessionId &&
            bridge?.bridgeGeneration == target.bridgeGeneration

    /** 立即尝试上报（dirty 且具备 epoch+bridge 才发；send 失败保持 dirty）。 */
    fun flush() {
        val currentBridge = bridge
        val currentEpoch = epoch
        if (currentBridge == null || currentEpoch == null || !dirty) return
        dirty = !send(buildPayload(currentBridge, currentEpoch))
    }

    /**
     * 桌面下发新 epoch（`mobile-task-view-request {epoch}`）：epoch 变化时 seq 归零重计，
     * 置 dirty 并立即 flush。
     */
    fun request(nextEpoch: String) {
        if (epoch != nextEpoch) seq = 0
        epoch = nextEpoch
        dirty = true
        flush()
    }

    /**
     * bootstrap 迟到兜底：仅在尚无 epoch 时采纳（官方 seed——配对请求可能先于 bootstrap
     * 回包，迟到的 bootstrap 不得把更新的 epoch 改回去）。
     */
    fun seed(nextEpoch: String) {
        if (epoch == null && nextEpoch.isNotEmpty()) request(nextEpoch)
    }

    /** 断连/降级：清 epoch（恢复后要求重新声明），dirty 保留。 */
    fun disconnected() {
        epoch = null
        dirty = true
    }

    /** 桥就绪/换代：非同桥才重置——新桥初始 view=null，等实际可见上报。 */
    fun setBridge(next: BridgeIdentity) {
        if (matches(next)) return
        bridge = next
        view = null
        dirty = true
        flush()
    }

    /** 前台可见事实变化（仅接受当前桥来源的回调）。 */
    fun update(
        source: BridgeIdentity,
        next: TaskView?,
    ) {
        if (!matches(source)) return
        view = next
        dirty = true
        flush()
    }

    /** wire payload（`web-remote-control.ts:506-512` schema：view=null 也是合法值）。 */
    private fun buildPayload(
        currentBridge: BridgeIdentity,
        currentEpoch: String,
    ): JsonObject =
        buildJsonObject {
            put("zcode_type", "mobile-task-view-update")
            put("epoch", currentEpoch)
            put("seq", ++seq)
            put("bridgeSessionId", currentBridge.bridgeSessionId)
            currentBridge.bridgeGeneration?.let { put("bridgeGeneration", it) }
            val v = view
            if (v == null) {
                put("view", JsonNull)
            } else {
                putJsonObject("view") {
                    put("workspacePath", v.workspacePath)
                    v.workspaceIdentity?.takeIf { it.isNotBlank() }?.let { put("workspaceIdentity", it) }
                    put("taskId", v.taskId)
                }
            }
        }
}
