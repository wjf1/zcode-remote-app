package com.zcode.remote.relay

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * 交互式终端（F5，官方 2026-10-22 恢复手机端 allowlist）。
 *
 * 协议（官方 `packages/services/src/terminal/terminal.ts:11-21`，频道 `terminal`）：
 * - `create({cols, rows, cwd?})` → `{id, shell, fontFamily, ...}`（App 只消费 id）
 * - `write({id, data})` / `resize({id, cols, rows})` / `dispose({id})`
 * - 输出：`onDynamicData(id)` 事件流（UTF-8 文本**增量**，含 ANSI 转义序列）
 * - 退出：`onDynamicExit(id)` 事件（进程退出码）
 *
 * owner 语义（官方 `windowHostAttachmentRpcPolicy.ts:188-277`）：owner 由**桌面端**持有；
 * 手机侧掉线 3s（`MOBILE_DISCONNECT_STATUS_GRACE_MS`）后桌面回收 PTY。App 侧无需保活——
 * 重连后 bootstrap/bridge-open 已带同一 `terminalClientSessionId` 重新认领，旧终端实例失效
 * （`bind` 抛 "Terminal owner has ended"），由 App 重新 create。
 */
class TerminalChannel(
    private val rpc: RpcChannel,
) {
    var onData: ((String) -> Unit)? = null
    var onExit: ((Int) -> Unit)? = null

    private var dataListenId: Int? = null
    private var exitListenId: Int? = null

    /** 当前终端实例 id（create 成功后非空；dispose/owner 回收后失效）。 */
    var terminalId: String? = null
        private set

    /** 创建终端实例并订阅输出/退出事件流。[onCreated] 回调成败（主线程外回调，UI 自行调度）。 */
    fun create(
        cols: Int,
        rows: Int,
        onCreated: (Result<Unit>) -> Unit,
    ) {
        rpc.call(
            RpcChannel.CHANNEL_TERMINAL,
            "create",
            listOf(mapOf("cols" to cols, "rows" to rows)),
            timeoutMs = 15_000,
        ) { reply ->
            when (reply) {
                is RpcChannel.RpcReply.Ok -> {
                    val id = extractTerminalId(reply.data)
                    if (id.isNullOrBlank()) {
                        onCreated(Result.failure(IllegalStateException("terminal create 应答缺 id")))
                    } else {
                        terminalId = id
                        subscribeEvents(id)
                        onCreated(Result.success(Unit))
                    }
                }
                is RpcChannel.RpcReply.Err -> onCreated(Result.failure(IllegalStateException(reply.message)))
            }
        }
    }

    private fun subscribeEvents(id: String) {
        // 官方 listen 参数段是单个裸值（RpcChannel.listen 注释）：即 terminalId 本身
        dataListenId =
            rpc.listen(RpcChannel.CHANNEL_TERMINAL, "onDynamicData", id) { reply ->
                if (reply is RpcChannel.RpcReply.Err) onData?.invoke("\r\n[终端事件订阅失败: ${reply.message}]")
            }
        exitListenId =
            rpc.listen(RpcChannel.CHANNEL_TERMINAL, "onDynamicExit", id) { reply ->
                if (reply is RpcChannel.RpcReply.Err) onData?.invoke("\r\n[终端退出订阅失败: ${reply.message}]")
            }
    }

    /** 写入一行用户输入（发送端约定补 `\r` 触发执行；空串不发送）。 */
    fun write(line: String) {
        val id = terminalId ?: return
        if (line.isEmpty()) return
        rpc.call(RpcChannel.CHANNEL_TERMINAL, "write", listOf(mapOf("id" to id, "data" to "$line\r")))
    }

    /** 原样写入（粘贴多行 / 控制字符场景）。 */
    fun writeRaw(data: String) {
        val id = terminalId ?: return
        if (data.isEmpty()) return
        rpc.call(RpcChannel.CHANNEL_TERMINAL, "write", listOf(mapOf("id" to id, "data" to data)))
    }

    fun resize(
        cols: Int,
        rows: Int,
    ) {
        val id = terminalId ?: return
        rpc.call(RpcChannel.CHANNEL_TERMINAL, "resize", listOf(mapOf("id" to id, "cols" to cols, "rows" to rows)))
    }

    fun dispose() {
        val id = terminalId ?: return
        rpc.call(RpcChannel.CHANNEL_TERMINAL, "dispose", listOf(mapOf("id" to id)))
        terminalId = null
        dataListenId = null
        exitListenId = null
    }

    /** RpcChannel 事件分发入口（ConnectionCoordinator 的 events collector 调用）。 */
    fun onEvent(event: RpcChannel.RpcEvent) {
        if (terminalId == null) return
        when (event.id) {
            dataListenId -> {
                val text = (event.data as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
                if (!text.isNullOrEmpty()) onData?.invoke(text)
            }
            exitListenId -> {
                val code = (event.data as? JsonPrimitive)?.intOrNull
                if (code != null) {
                    onData?.invoke("\r\n[进程退出 code=$code]")
                    onExit?.invoke(code)
                }
            }
        }
    }

    fun reset() {
        terminalId = null
        dataListenId = null
        exitListenId = null
    }

    companion object {
        /**
         * 从 create 应答提取终端实例 id（纯函数，单测覆盖）。
         * 兼容三种形态：裸字符串、`{"id":…}`、`{"result":{"id":…}}`（Promise 应答包装）。
         */
        fun extractTerminalId(data: JsonElement?): String? =
            when (val el = data ?: return null) {
                is JsonNull -> null
                is JsonPrimitive -> el.takeIf { it.isString }?.content
                is kotlinx.serialization.json.JsonObject ->
                    (
                        el["id"] as? JsonPrimitive
                            ?: ((el["result"] as? kotlinx.serialization.json.JsonObject)?.get("id") as? JsonPrimitive)
                    )?.takeIf { it.isString }?.content
                else -> null
            }

        /**
         * ANSI 转义序列过滤（纯函数，JVM 单测）。
         *
         * Compose 无现成 xterm；MVP 终端面板用 Monospace Text 渲染纯文本——把三类
         * ANSI 转义序列整段剥除，保留普通文本与换行/制表。这会丢配色与光标控制
         * （后续可上 terminal-emulator 库渲染真 ANSI），但命令输出正文完整可读。
         *
         * 三类序列（ECMA-48）：
         * - CSI：`ESC [ 参数(0x30-3F) 中间(0x20-2F) 终止(0x40-7E)`
         * - OSC：`ESC ] … BEL(0x07) | ST(ESC \)`（未终止时剥到串尾）
         * - 其它：`ESC + 单字符`（如 ESC 7 / ESC ( B）
         */
        fun stripAnsi(raw: String): String = CSI_OR_OSC_OR_ESC.matcher(raw).replaceAll("")

        private val CSI_OR_OSC_OR_ESC: java.util.regex.Pattern =
            java.util.regex.Pattern
                // 尾项 `\u001B.?` 用 `?` 而非 `.`：串尾孤立 ESC（后续无字符）也要剥掉，
                // 否则会漏一个控制字符到终端正文（单测「未终止序列安全截断」钉住）。
                .compile("\u001B\\[[0-?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)?|\u001B.?")
    }
}
