package com.zcode.remote.relay

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
 */
object UserFacingError {

    /** 兜底文案（message 为空时的最终可见文本）。 */
    const val FALLBACK = "操作失败，请重试"

    /**
     * 本地通道自造英文 → 中文。改动 RpcChannel 措辞时须同步这里；
     * 单测 `UserFacingErrorTest` 覆盖全部已知串。
     */
    private val LOCAL_ERRORS: List<Pair<String, String>> = listOf(
        "timeout after" to "响应超时，请检查网络后重试",
        "bridge not ready" to "连接通道尚未就绪，请稍后重试",
        "bridge re-established" to "连接已重建，请重试",
        "channel reset" to "连接已重置，请重试",
        // OkHttp / JVM 网络栈的自造英文（检查更新、文件预览等 HTTP 路径）
        "unable to resolve host" to "无法解析服务器地址，请检查网络",
        "failed to connect" to "无法连接到服务器，请稍后重试",
        "timed out" to "连接超时，请检查网络后重试",
    )

    /**
     * 服务端 fault code 关键词 → 中文（code 形如 `fault.connection.handshakeRequired`，
     * 取子串匹配以免把命名空间写死）。顺序即优先级，"timeout" 类放后避免误伤。
     */
    private val FAULT_ERRORS: List<Pair<String, String>> = listOf(
        "handshakeRequired" to "连接尚未完成握手，请重试",
        "clientMismatch" to "会话身份不匹配，请重新进入会话",
        "unauthorized" to "登录态失效，请重新配对",
        "forbidden" to "没有权限执行该操作",
        "notFound" to "目标不存在或已被删除",
        "rateLimit" to "操作过于频繁，请稍后重试",
        "unavailable" to "服务暂时不可用，请稍后重试",
        "timeout" to "服务端响应超时，请检查网络后重试",
    )

    /**
     * 映射为面向用户的文案。
     *
     * @param message 底层错误串（可能是英文自造串、服务端 message 或已中文）
     * @param raw     `RpcChannel.RpcReply.Err` 的原始 JSON（fault code 在 `code`/`reasonCode` 等字段，可空）
     */
    fun map(message: String?, raw: JsonElement? = null): String {
        val code = faultCodeOf(raw)
        if (code != null) {
            translate(code)?.let { return it }
            // 未知 fault：至少给出中文前缀，并保留原 code 便于排查（不直出裸英文段落）
            return "服务端拒绝了该操作（$code）"
        }
        val msg = message?.trim().orEmpty()
        if (msg.isEmpty()) return FALLBACK
        translate(msg)?.let { return it }
        return msg
    }

    /** 关键词翻译（先本地自造串、再 fault 关键词；都不命中返回 null 表示原样透出）。 */
    private fun translate(text: String): String? {
        LOCAL_ERRORS.firstOrNull { text.contains(it.first, ignoreCase = true) }?.let { return it.second }
        FAULT_ERRORS.firstOrNull { text.contains(it.first, ignoreCase = true) }?.let { return it.second }
        return null
    }

    /** 从 fault 原始 JSON 里取 code，兼容直挂与包在 `fault` 里两种形态。 */
    private fun faultCodeOf(raw: JsonElement?): String? {
        val obj = raw as? JsonObject ?: return null
        fun firstCode(o: JsonObject): String? = CODE_FIELDS.firstNotNullOfOrNull { k ->
            o[k]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }?.takeIf { it.isNotBlank() }
        }
        firstCode(obj)?.let { return it }
        val fault = obj["fault"] as? JsonObject ?: return null
        return firstCode(fault)
    }

    private val CODE_FIELDS = listOf("code", "reasonCode", "errorCode", "reason")
}
