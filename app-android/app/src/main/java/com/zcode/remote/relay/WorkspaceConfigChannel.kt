package com.zcode.remote.relay

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * workspace 级配置订阅（模型目录数据源）：
 * `subscribeWorkspaceConfigV4` + listen `onDynamicWorkspaceConfigFrame`。
 *
 * 背景（真机实证 2026-10-01）：远程桥不暴露 `readWorkspaceState`（zcode-agent/session 均回
 * Method not found），`model-provider` 通道未注册（Unknown channel）。官方远程链路上唯一的
 * 模型目录来源是 workspace-config 订阅——推送帧 payload.configOptions 中 id=="model" 的
 * select 项带全部可选模型（options[{value,name,modelProviderId,modelProviderName}]）与
 * currentValue，等价于桌面端 readWorkspaceState.settings.model 的展平形态。
 *
 * 选项 value 形如 "providerId/modelId"（与移动端 pf() 解析一致）；
 * createSession 的 config 需要拆成 {provider, model} 两段。
 */
class WorkspaceConfigChannel(private val rpc: RpcChannel) {

    /** 模型目录条目（与 ConversationChannel.ModelConfig 同构，避免跨层依赖）。 */
    data class ModelOption(
        val value: String,
        val name: String,
        val providerId: String?,
        val providerName: String?,
    ) {
        /** 拆出 createSession config 用的 modelId：value 含 provider 前缀时取斜杠后段。 */
        fun modelId(): String {
            val pid = providerId
            return if (pid != null && value.startsWith("$pid/")) value.substringAfter('/') else value
        }
    }

    data class WorkspaceState(
        /** 当前选中模型的 value（configOptions currentValue），未读到为 null。 */
        val currentValue: String?,
        /** 全部可选模型。 */
        val models: List<ModelOption>,
    ) {
        fun findCurrent(): ModelOption? = models.firstOrNull { it.value == currentValue }
    }

    private val _state = MutableStateFlow<WorkspaceState?>(null)
    val state: StateFlow<WorkspaceState?> = _state

    private var listenId: Int? = null
    private var subscriptionId: String? = null

    /** 开桥后按当前工作区订阅一次（会话切换无需重订，幂等：已订阅直接跳过）。 */
    fun subscribe(workspacePath: String, workspaceIdentity: String?) {
        if (listenId != null) return   // conv.status 会多次发射 Live，避免重复 listen 泄漏
        val target = buildMap<String, Any> {
            put("workspacePath", workspacePath)
            workspaceIdentity?.let { put("workspaceIdentity", it) }
        }
        listenId = rpc.listen(RpcChannel.CHANNEL_AGENT, "onDynamicWorkspaceConfigFrame", target)
        rpc.call(RpcChannel.CHANNEL_AGENT, "subscribeWorkspaceConfigV4",
            listOf(target + mapOf("runtimePolicy" to "existing-only"))) { reply ->
            when (reply) {
                is RpcChannel.RpcReply.Ok -> {
                    val root = reply.data as? JsonObject
                    val ack = root ?: (root?.get("ack") as? JsonObject)
                    subscriptionId = ack?.str("subscriptionId")
                    Log.i(TAG, "workspace-config subscribed sub=$subscriptionId")
                }
                is RpcChannel.RpcReply.Err ->
                    Log.w(TAG, "workspace-config subscribe 失败: ${reply.message}")
            }
        }
    }

    /** 已订阅但 snapshot 未到时的兜底：让服务端重推一次 workspace-config 全量帧。 */
    fun resync(workspacePath: String, onDone: () -> Unit = {}) {
        val target = buildMap<String, Any> {
            put("workspacePath", workspacePath)
        }
        rpc.call(RpcChannel.CHANNEL_AGENT, "resyncWorkspaceConfigV4", listOf(target)) { reply ->
            when (reply) {
                is RpcChannel.RpcReply.Err ->
                    Log.w(TAG, "workspace-config resync 失败: ${reply.message}")
                is RpcChannel.RpcReply.Ok ->
                    Log.i(TAG, "workspace-config resync 已请求")
            }
            onDone()
        }
    }

    /** RpcChannel.events 统一分发，只认本次 listen 注册的事件。 */
    fun onEvent(event: RpcChannel.RpcEvent) {
        if (listenId == null || event.id != listenId) return
        val data = event.data as? JsonObject ?: return
        val lf = ConversationFrames.parseLogicalFrame(data) ?: return
        if (!lf.topic.startsWith("workspace-config/")) return
        if (subscriptionId != null && lf.subscriptionId != subscriptionId) return
        val frame = lf.frame ?: return

        when (frame.payloadKind) {
            // 真机帧实证：payload.snapshot = {protocolVersion, workspaceId, logEpoch,
            //   config:{configOptions:[...], slashCommands:[...]}}
            // configOptions 在 snapshot.config 里（三层嵌套）
            "snapshot" -> {
                val snap = frame.payload["snapshot"] as? JsonObject
                val cfg = snap?.get("config") as? JsonObject
                applyOptions(cfg?.get("configOptions"), "snapshot")
            }
            "deltas" -> {
                val deltas = frame.payload["deltas"]?.let {
                    runCatching { it as? kotlinx.serialization.json.JsonArray }.getOrNull()
                } ?: return
                for (d in deltas) {
                    val o = d as? JsonObject ?: continue
                    val patch = o["patch"] as? JsonObject ?: continue
                    val opts = patch["configOptions"]
                        ?: (patch["config"] as? JsonObject)?.get("configOptions")
                        ?: ((patch["snapshot"] as? JsonObject)?.get("config") as? JsonObject)?.get("configOptions")
                    if (opts != null) applyOptions(opts, "deltas")
                }
            }
        }
    }

    private fun applyOptions(raw: kotlinx.serialization.json.JsonElement?, source: String) {
        val arr = raw as? kotlinx.serialization.json.JsonArray ?: return
        for (item in arr) {
            val o = item as? JsonObject ?: continue
            if (o.str("id") != "model" || o.str("type") != "select") continue
            val currentValue = o.str("currentValue")
            val models = mutableListOf<ModelOption>()
            val options = o["options"] as? kotlinx.serialization.json.JsonArray
            options?.forEach { el ->
                val opt = el as? JsonObject ?: return@forEach
                val value = opt.str("value") ?: return@forEach
                models.add(
                    ModelOption(
                        value = value,
                        name = opt.str("name") ?: value,
                        providerId = opt.str("modelProviderId"),
                        providerName = opt.str("modelProviderName"),
                    )
                )
            }
            _state.value = WorkspaceState(currentValue = currentValue, models = models)
            Log.i(TAG, "workspace-config $source: ${models.size} 个模型, current=$currentValue")
            return
        }
    }

    fun reset() {
        listenId = null
        subscriptionId = null
        _state.value = null
    }

    companion object {
        private const val TAG = "WorkspaceConfig"
        private const val FALLBACK_PROVIDER = "glm"   // 官方 pf() 无斜杠时的兜底 providerId

        private fun JsonObject.str(k: String): String? =
            this[k]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

        /** 供 createSession config 使用：把 value 拆成 (providerId, modelId)。 */
        fun splitModelValue(value: String): Pair<String, String> {
            if (!value.contains('/')) return FALLBACK_PROVIDER to value
            val pid = value.substringBefore('/')
            val mid = value.substringAfter('/')
            return pid to (mid.substringBefore('$'))   // 剥离 $variant
        }
    }
}
