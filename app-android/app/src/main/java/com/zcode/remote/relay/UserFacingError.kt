package com.zcode.remote.relay

import com.zcode.remote.R
import com.zcode.remote.util.UiText
import com.zcode.remote.util.UiText.Companion.res
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 用户可见错误文案映射（C-2 错误分层映射）。
 *
 * 底层错误串有三类来源：
 * 1. **本地通道自造英文**：`bridge not ready` / `timeout after 15000ms` /
 *    `bridge re-established` / `channel reset`（RpcChannel 的内部状态措辞）；
 * 2. **服务端 fault**：`RpcReply.Err.raw` 里的 code（形如 `fault.connection.handshakeRequired`，
 *    `fault.command.clientMismatch`）—— **fault code 一直随 `Err` 第二参保存，只是此前无人读取**，
 *    可直接用，无需改协议层（任务书 §5 C-2 纠正点）；
 * 3. **业务语义**：已是中文（「未订阅会话，无法发送」等），原样透出。
 *
 * 收敛为单一纯函数：任务书 §7 要求「UI 字符串不含裸英文串」可用 grep 断言守住，
 * 全部展示出口经同一映射才能被 JVM 单测钉死（本项目无 androidTest，纯函数是唯一可守形式）。
 *
 * 国际化（第 3 批）：返回值由 `String` 改为 [UiText]（资源 id + 参数）——错误文案是
 * **UI 层最典型的用户可见串**，且消费点遍布 VM / repo / UI 三处，最需要集中管理。
 * 消费点经 `asString()`（Composable）/ `resolve(context)`（非 Compose）解析。
 * 已是中文的业务语义与未知错误原文走 [UiText.Raw] 原样透出。
 */
object UserFacingError {
    /** 兜底文案（message 为空时的最终可见文本）。 */
    val FALLBACK: UiText = res(R.string.error_fallback)

    /**
     * 本地通道自造英文 → 资源 id。改动 RpcChannel 措辞时须同步这里；
     * 单测 `UserFacingErrorTest` 覆盖全部已知串。
     */
    private val LOCAL_ERRORS: List<Pair<String, UiText>> =
        listOf(
            "timeout after" to res(R.string.error_local_timeout),
            "bridge not ready" to res(R.string.error_local_bridge_not_ready),
            "bridge re-established" to res(R.string.error_local_bridge_reestablished),
            "channel reset" to res(R.string.error_local_channel_reset),
            // OkHttp / JVM 网络栈的自造英文（检查更新、文件预览等 HTTP 路径）
            "unable to resolve host" to res(R.string.error_local_unable_resolve_host),
            "failed to connect" to res(R.string.error_local_failed_to_connect),
            "timed out" to res(R.string.error_local_timed_out),
        )

    /**
     * 服务端 fault code 关键词 → 资源 id（code 形如 `fault.connection.handshakeRequired`，
     * 取子串匹配以免把命名空间写死）。顺序即优先级，"timeout" 类放后避免误伤。
     */
    private val FAULT_ERRORS: List<Pair<String, UiText>> =
        listOf(
            "handshakeRequired" to res(R.string.error_fault_handshake_required),
            "clientMismatch" to res(R.string.error_fault_client_mismatch),
            "unauthorized" to res(R.string.error_fault_unauthorized),
            "forbidden" to res(R.string.error_fault_forbidden),
            "notFound" to res(R.string.error_fault_not_found),
            "rateLimit" to res(R.string.error_fault_rate_limit),
            "unavailable" to res(R.string.error_fault_unavailable),
            "timeout" to res(R.string.error_fault_timeout),
        )

    /**
     * 映射为面向用户的文案。
     *
     * @param message 底层错误串（可能是英文自造串、服务端 message 或已中文）
     * @param raw     `RpcChannel.RpcReply.Err` 的原始 JSON（fault code 在 `code`/`reasonCode` 等字段，可空）
     */
    fun map(
        message: String?,
        raw: JsonElement? = null,
    ): UiText {
        val code = faultCodeOf(raw)
        if (code != null) {
            translate(code)?.let { return it }
            // 未知 fault：至少给出中文前缀，并保留原 code 便于排查（不直出裸英文段落）
            return res(R.string.error_fault_unknown, code)
        }
        val msg = message?.trim().orEmpty()
        if (msg.isEmpty()) return FALLBACK
        translate(msg)?.let { return it }
        return UiText.Raw(msg)
    }

    /** 关键词翻译（先本地自造串、再 fault 关键词；都不命中返回 null 表示原样透出）。 */
    private fun translate(text: String): UiText? {
        LOCAL_ERRORS.firstOrNull { text.contains(it.first, ignoreCase = true) }?.let { return it.second }
        FAULT_ERRORS.firstOrNull { text.contains(it.first, ignoreCase = true) }?.let { return it.second }
        return null
    }

    /** 从 fault 原始 JSON 里取 code，兼容直挂与包在 `fault` 里两种形态。 */
    private fun faultCodeOf(raw: JsonElement?): String? {
        val obj = raw as? JsonObject ?: return null

        fun firstCode(o: JsonObject): String? =
            CODE_FIELDS.firstNotNullOfOrNull { k ->
                o[k]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }?.takeIf { it.isNotBlank() }
            }
        firstCode(obj)?.let { return it }
        val fault = obj["fault"] as? JsonObject ?: return null
        return firstCode(fault)
    }

    private val CODE_FIELDS = listOf("code", "reasonCode", "errorCode", "reason")
}
