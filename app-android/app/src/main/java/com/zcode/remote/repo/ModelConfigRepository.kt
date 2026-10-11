package com.zcode.remote.repo

import androidx.compose.runtime.mutableStateOf
import com.zcode.remote.relay.ConversationChannel
import com.zcode.remote.relay.RpcChannel
import com.zcode.remote.relay.UserFacingError
import com.zcode.remote.relay.WorkspaceConfigChannel
import com.zcode.remote.util.ZLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * 模型目录（§11.7 结构性拆分：自 `AppViewModel` 原样搬出，只移动不改行为）。
 * 新建会话弹窗与会话内切模型的数据源：三路并发探测（provider_config 直读 +
 * workspace-config resync + readWorkspaceState）+ 思考档位注册表视图。
 * 连接栈（channel / conversation / workspaceConfig / activeWorkspaceKey / sessions /
 * conversationMeta.provider）为运行期代理，经 getter 注入。
 *
 * @param flash 反馈横幅回调：(文案, 是否失败语义)。
 * @param onWorkspaceMode readWorkspaceState 返回的执行模式（P0-B 权威来源）回写 VM。
 */
class ModelConfigRepository(
    private val scope: CoroutineScope,
    private val channel: () -> RpcChannel?,
    private val conversation: () -> ConversationChannel?,
    private val workspaceConfig: () -> WorkspaceConfigChannel?,
    private val activeWorkspaceKey: () -> String?,
    private val firstSessionWorkspace: () -> String?,
    private val onWorkspaceMode: (String) -> Unit,
    private val flash: (String, Boolean) -> Unit,
) {
    /** 工作区模型状态（workspace-config 订阅推送，snapshot/deltas 双路更新）。 */
    val workspaceModelState: WorkspaceConfigChannel.WorkspaceState?
        get() = workspaceConfig()?.state?.value

    /** 从 zcode-session::readWorkspaceState 读取的模型列表。 */
    var workspaceSessionModels by mutableStateOf<List<WorkspaceConfigChannel.ModelOption>>(emptyList())
        private set

    /** 综合可用模型列表（优先配置订阅，回退 session 目录）。 */
    val allAvailableModels: List<WorkspaceConfigChannel.ModelOption>
        get() {
            val fromConfig = workspaceConfig()?.state?.value?.models ?: emptyList()
            if (fromConfig.isNotEmpty()) return fromConfig
            if (workspaceSessionModels.isNotEmpty()) return workspaceSessionModels
            return emptyList()
        }

    var modelStateLoading by mutableStateOf(false)
        private set

    /**
     * 各模型合法的思考档位：key = "providerId/modelId" → 合法档位值列表。
     * 来源 = PC 端 model-selection::getView（registry 视图，含 model.config.optionSpecs.reasoningLevel.values）。
     * 官方 registry 对推理模型强制校验 reasoningLevel：值不在合法列表里就直接报
     * "Reasoning level is required"（或 not supported）导致会话失败，所以必须按模型真实档位下发。
     */
    var modelReasoningLevels by mutableStateOf<Map<String, List<String>>>(emptyMap())
        private set

    /**
     * 取该模型应当下发的思考档位；无合法档位信息时返回 null（不下发，交由 PC 默认）。
     * 挑选策略：优先启用推理（首个非 disabled 档位），全为 disabled 时才用 disabled。
     * 例如 deepseek [disabled,low,high,max] → "low"；gemini [disabled,enabled] → "enabled"。
     */
    internal fun pickReasoningLevel(providerId: String, modelId: String): String? {
        val levels = modelReasoningLevels["$providerId/$modelId"] ?: return null
        return levels.firstOrNull { !it.equals("disabled", ignoreCase = true) }
            ?: levels.firstOrNull()
    }

    /**
     * 拉取 PC 端模型注册表视图（model-selection::getView），提取每个模型的合法思考档位。
     * 非推理模型不会出现在结果里；拉取失败静默降级为不下发 thought。
     */
    fun loadModelReasoningLevels() {
        val ch = channel() ?: return
        ch.call(RpcChannel.CHANNEL_MODEL_SELECTION, "getView", listOf<Any>()) { reply ->
            when (reply) {
                is RpcChannel.RpcReply.Err ->
                    ZLog.w(TAG, "model-selection.getView 失败: ${reply.message}")
                is RpcChannel.RpcReply.Ok -> {
                    val raw = reply.data?.toString() ?: ""
                    ZLog.i(TAG, "model-selection.getView 原始响应(截断): ${raw.take(3000)}")
                    val parsed = parseReasoningLevels(reply.data)
                    if (parsed.isNotEmpty()) {
                        modelReasoningLevels = parsed
                        ZLog.i(TAG, "模型思考档位加载成功: " +
                                parsed.entries.joinToString(", ") { "${it.key}->${it.value}" })
                    }
                }
            }
        }
    }

    /**
     * 从 getView 响应中按多种可能的嵌套形态提取 models[].config.optionSpecs.reasoningLevel.values。
     * 兼容：根即视图 / 包在 view|snapshot 里；model 标识在 modelId / ref.modelId / config.modelId。
     */
    private fun parseReasoningLevels(data: kotlinx.serialization.json.JsonElement?): Map<String, List<String>> {
        val out = LinkedHashMap<String, List<String>>()
        fun objOf(e: kotlinx.serialization.json.JsonElement?) =
            runCatching { e?.jsonObject }.getOrNull()

        val root = objOf(data) ?: return out
        // 视图可能被包一层
        val view = objOf(root["view"]) ?: objOf(root["snapshot"]) ?: objOf(root["registry"]) ?: root
        val providers = runCatching { view["providers"]?.jsonArray }.getOrNull() ?: return out

        for (pEl in providers) {
            val p = objOf(pEl) ?: continue
            val pid = runCatching { p["providerId"]?.jsonPrimitive?.content }.getOrNull() ?: continue
            val models = runCatching { p["models"]?.jsonArray }.getOrNull() ?: continue
            for (mEl in models) {
                val m = objOf(mEl) ?: continue
                val cfg = objOf(m["config"])
                val mid = runCatching { m["modelId"]?.jsonPrimitive?.content }.getOrNull()
                    ?: runCatching { objOf(m["ref"])?.get("modelId")?.jsonPrimitive?.content }.getOrNull()
                    ?: runCatching { cfg?.get("modelId")?.jsonPrimitive?.content }.getOrNull()
                    ?: continue
                val levels = runCatching {
                    objOf(cfg?.get("optionSpecs"))
                        ?.get("reasoningLevel")?.jsonObject
                        ?.get("values")?.jsonArray
                        ?.mapNotNull { it.jsonPrimitive.content }
                }.getOrNull()
                if (!levels.isNullOrEmpty()) out["$pid/$mid"] = levels
            }
        }
        return out
    }

    /**
     * 触发一次模型目录刷新：三路并发探测（provider_config 配置文件直读 + workspace-config resync + readWorkspaceState），
     * 只要有一路返回模型数据即可填充到 UI。
     */
    fun loadWorkspaceModels() {
        val ws = activeWorkspaceKey() ?: firstSessionWorkspace() ?: return
        modelStateLoading = true
        // 1) 最稳定的直接链路：通过 file 服务的 resolvePath/readTextFile 读取 ~/.zcode/v2/provider_config.json
        loadModelsFromConfigFile {
            modelStateLoading = false
        }
        // 2) 远程会话目录链路（若支持）
        conversation()?.readWorkspaceState(ws) { res ->
            scope.launch {
                res.onSuccess { state ->
                    // 执行模式的权威来源（P0-B）：settings.mode.current——
                    // 勿用订阅 ack 的 mode（那是订阅模式 snapshot/live，与执行模式撞名）
                    state.currentMode?.takeIf { it.isNotBlank() }?.let {
                        onWorkspaceMode(it)
                    }
                    val list = state.available.map {
                        WorkspaceConfigChannel.ModelOption(
                            value = "${it.providerId}/${it.modelId}",
                            name = it.displayName(),
                            providerId = it.providerId,
                            providerName = it.providerLabel,
                        )
                    }
                    if (list.isNotEmpty()) {
                        workspaceSessionModels = list
                        ZLog.i(TAG, "readWorkspaceState 模型加载成功: ${list.size} 个模型")
                    }
                }
            }
        }
        // 3) 配置流 resync
        workspaceConfig()?.resync(ws)
    }

    /**
     * 从 PC 端 ~/.zcode/v2/provider_config.json 配置文件中直接拉取用户已配置的模型目录。
     * （先通过 system::info 获取真实的 homedir，再通过 file::readTextFile 读取配置文件，100% 绕过远程 RPC 限制）。
     */
    fun loadModelsFromConfigFile(onDone: () -> Unit = {}) {
        val ch = channel() ?: run { onDone(); return }

        fun readConfigByPath(realPath: String) {
            ZLog.i(TAG, "正在读取 PC 端模型配置: $realPath")
            ch.call(RpcChannel.CHANNEL_FILE, "readTextFile", listOf(mapOf("path" to realPath))) { r2 ->
                scope.launch {
                    when (r2) {
                        is RpcChannel.RpcReply.Err -> {
                            ZLog.w(TAG, "readTextFile 失败 ($realPath): ${r2.message}")
                        }
                        is RpcChannel.RpcReply.Ok -> {
                            val content = runCatching {
                                r2.data?.jsonObject?.get("content")?.jsonPrimitive?.content
                            }.getOrNull()
                            if (!content.isNullOrBlank()) {
                                val parsed = parseProviderConfigModels(content)
                                if (parsed.isNotEmpty()) {
                                    workspaceSessionModels = parsed
                                    ZLog.i(TAG, "从 provider_config.json 成功解析 ${parsed.size} 个可用模型: ${parsed.map { it.name }}")
                                }
                            }
                        }
                    }
                    onDone()
                }
            }
        }

        // 1) 优先调用 system::info 获取桌面系统真实 homedir
        ch.call(RpcChannel.CHANNEL_SYSTEM, "info", listOf<Any>()) { rSys ->
            when (rSys) {
                is RpcChannel.RpcReply.Ok -> {
                    val homedir = runCatching {
                        rSys.data?.jsonObject?.get("homedir")?.jsonPrimitive?.content
                    }.getOrNull()
                    if (!homedir.isNullOrBlank()) {
                        val fullPath = "${homedir.trimEnd('\\', '/')}/.zcode/v2/provider_config.json"
                        readConfigByPath(fullPath)
                        return@call
                    }
                    // homedir 缺失则回退 resolvePath
                    fallbackResolvePath(ch, ::readConfigByPath, onDone)
                }
                is RpcChannel.RpcReply.Err -> {
                    ZLog.w(TAG, "system.info 失败: ${rSys.message}，回退 resolvePath")
                    fallbackResolvePath(ch, ::readConfigByPath, onDone)
                }
            }
        }
    }

    private fun fallbackResolvePath(ch: RpcChannel, onPath: (String) -> Unit, onDone: () -> Unit) {
        val pathArg = mapOf("path" to "~/.zcode/v2/provider_config.json")
        ch.call(RpcChannel.CHANNEL_FILE, "resolvePath", listOf(pathArg)) { r1 ->
            when (r1) {
                is RpcChannel.RpcReply.Err -> {
                    ZLog.w(TAG, "resolvePath 兜底亦失败: ${r1.message}")
                    onDone()
                }
                is RpcChannel.RpcReply.Ok -> {
                    val realPath = r1.data?.let {
                        runCatching { it.jsonPrimitive.content }.getOrNull()
                    } ?: runCatching {
                        r1.data?.jsonObject?.get("path")?.jsonPrimitive?.content
                    }.getOrNull() ?: "~/.zcode/v2/provider_config.json"
                    onPath(realPath)
                }
            }
        }
    }

    /** 解析 provider_config.json 结构中的全部模型。 */
    private fun parseProviderConfigModels(jsonStr: String): List<WorkspaceConfigChannel.ModelOption> {
        val result = mutableListOf<WorkspaceConfigChannel.ModelOption>()
        runCatching {
            val root = Json.parseToJsonElement(jsonStr).jsonObject
            val config = root["config"]?.jsonObject
            val rules = config?.get("providerConfigRules")?.jsonObject?.get("providerRules")?.jsonArray
            rules?.forEach { rEl ->
                val r = rEl.jsonObject
                val pid = r["providerId"]?.jsonPrimitive?.content ?: ""
                val pname = r["providerName"]?.jsonPrimitive?.content
                val cfg = r["config"]?.jsonObject
                val modelIds = (cfg?.get("personalModelIds")?.jsonArray ?: cfg?.get("modelOrder")?.jsonArray)
                    ?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
                    ?: emptyList()
                for (mid in modelIds) {
                    result.add(
                        WorkspaceConfigChannel.ModelOption(
                            value = "$pid/$mid",
                            name = mid,
                            providerId = pid,
                            providerName = pname,
                        )
                    )
                }
            }
        }.onFailure { ZLog.w(TAG, "parseProviderConfigModels 解析异常", it) }
        return result
    }

    /**
     * 在已有会话中动态切换模型（官方 V4 switchModelConfig 原生信封链路）。
     * 成功后服务端广播 state.updated 增量帧，顶栏模型回显即时更新。
     */
    fun switchCurrentSessionModel(modelOption: WorkspaceConfigChannel.ModelOption) {
        val conv = conversation() ?: run { flash("连接尚未就绪", true); return }
        val (pid, mid) = WorkspaceConfigChannel.splitModelValue(modelOption.value)
        // 用户选了档位就用选的，否则按该模型自动挑一个合法档位，避免 registry 校验失败
        val thought = modelOption.thought ?: pickReasoningLevel(pid, mid)
        conv.switchModelConfig(provider = pid, model = mid, thought = thought) { result ->
            scope.launch {
                result.fold(
                    onSuccess = { flash("模型已切换为 $mid" + (thought?.let { " · $it" } ?: ""), false) },
                    onFailure = { flash("切换模型失败: ${UserFacingError.map(it.message)}", true) }
                )
            }
        }
    }

    companion object {
        private const val TAG = "ModelConfigRepo"
    }
}
