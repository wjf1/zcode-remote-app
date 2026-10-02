package com.zcode.remote.relay

import com.zcode.remote.util.ZLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * workspace 级 sessions-index 订阅（E-1 会话权威角标）：
 * `subscribeSessionsIndexV4` + listen `onDynamicSessionsIndexFrame`。
 *
 * 服务端按会话维护 `pendingInteractionSummary {permissionCount, userInputCount}`：
 * snapshot（`sessions[]` 全量，键 `sessionId`）与增量（`session.upserted` / `session.removed`）
 * 双路更新。相比任务事件流推导的角标——这是服务端权威统计：覆盖全部会话、消解即清零、
 * 不怕事件流漏帧。协议实证见 PROTOCOL.md §6.7（tools/_e1_verify.py）。
 */
class SessionsIndexChannel(private val rpc: RpcChannel) {

    /** 单个会话的待处理计数（服务端权威）。 */
    data class PendingSummary(val permissionCount: Int, val userInputCount: Int) {
        val total: Int get() = permissionCount + userInputCount
    }

    private val _summaries = MutableStateFlow<Map<String, PendingSummary>>(emptyMap())
    val summaries: StateFlow<Map<String, PendingSummary>> = _summaries

    private var listenId: Int? = null
    private var subscriptionId: String? = null

    /** 开桥后按当前工作区订阅一次（会话切换无需重订，与 ConversationChannel 生命周期不同）。 */
    fun subscribe(workspacePath: String, workspaceIdentity: String?) {
        val target = buildMap<String, Any> {
            put("workspacePath", workspacePath)
            workspaceIdentity?.let { put("workspaceIdentity", it) }
        }
        listenId = rpc.listen(RpcChannel.CHANNEL_AGENT, "onDynamicSessionsIndexFrame", target)
        rpc.call(RpcChannel.CHANNEL_AGENT, "subscribeSessionsIndexV4",
            listOf(target + mapOf("runtimePolicy" to "existing-only"))) { reply ->
            when (reply) {
                is RpcChannel.RpcReply.Ok -> {
                    val root = reply.data as? JsonObject
                    val ack = root ?: (root?.get("ack") as? JsonObject)
                    subscriptionId = ack?.str("subscriptionId")
                    ZLog.i(TAG, "sessions-index subscribed sub=$subscriptionId")
                }
                is RpcChannel.RpcReply.Err ->
                    ZLog.w(TAG, "sessions-index subscribe 失败: ${reply.message}")
            }
        }
    }

    /** RpcChannel.events 统一分发，只认本次 listen 注册的事件。 */
    fun onEvent(event: RpcChannel.RpcEvent) {
        if (listenId == null || event.id != listenId) return
        val data = event.data as? JsonObject ?: return
        val lf = ConversationFrames.parseLogicalFrame(data) ?: return
        if (!lf.topic.startsWith("sessions-index/")) return
        if (subscriptionId != null && lf.subscriptionId != subscriptionId) return
        val frame = lf.frame ?: return
        when (frame.payloadKind) {
            "snapshot" -> {
                val sessions = frame.payload["sessions"]?.let {
                    runCatching { it as? kotlinx.serialization.json.JsonArray }.getOrNull()
                } ?: return
                val next = HashMap<String, PendingSummary>()
                for (item in sessions) {
                    val o = item as? JsonObject ?: continue
                    val sid = o.str("sessionId") ?: continue
                    parseSummary(o)?.let { next[sid] = it }
                }
                _summaries.value = next
                ZLog.i(TAG, "sessions-index snapshot: ${next.size} 会话，待处理 " +
                        next.values.count { it.total > 0 })
            }
            "deltas" -> {
                val deltas = frame.payload["deltas"]?.let {
                    runCatching { it as? kotlinx.serialization.json.JsonArray }.getOrNull()
                } ?: return
                var changed = false
                val cur = _summaries.value.toMutableMap()
                for (d in deltas) {
                    val o = d as? JsonObject ?: continue
                    when (o.str("op")) {
                        "session.upserted" -> {
                            val sess = o["session"] as? JsonObject ?: continue
                            val sid = sess.str("sessionId") ?: continue
                            parseSummary(sess)?.let { cur[sid] = it; changed = true }
                        }
                        "session.removed" -> {
                            o.str("sessionId")?.let { if (cur.remove(it) != null) changed = true }
                        }
                    }
                }
                if (changed) _summaries.value = cur
            }
        }
    }

    /** 无 summary 字段的会话条目视为 0/0（仍在索引里）。 */
    private fun parseSummary(sess: JsonObject): PendingSummary {
        val s = sess["pendingInteractionSummary"] as? JsonObject
        return PendingSummary(
            permissionCount = s?.int("permissionCount") ?: 0,
            userInputCount = s?.int("userInputCount") ?: 0,
        )
    }

    fun reset() {
        listenId = null
        subscriptionId = null
        _summaries.value = emptyMap()
    }

    companion object {
        private const val TAG = "SessionsIndex"

        private fun JsonObject.str(k: String): String? =
            this[k]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

        private fun JsonObject.int(k: String): Int? =
            this[k]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }
    }
}
