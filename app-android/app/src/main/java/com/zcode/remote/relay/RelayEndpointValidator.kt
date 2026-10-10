package com.zcode.remote.relay

import java.net.URI

/**
 * 中继端点校验（任务书 §11.3.1 · P0 传输安全止血）。
 *
 * 设计要点：
 * 1. **结构化解析，绝不用 `startsWith("ws")`**——前缀匹配会把
 *    `wss://` 之外的一切（`ws://`、`wssx://`、`ws://host@evil`、相对路径、
 *    带 userinfo/异常端口的 URL）放过去。
 * 2. **Release 只接受绝对 `wss://`**；`ws://` 仅在 debug 构建放行（局域网明文调试）。
 * 3. 解析用 `java.net.URI`（本项目无 androidTest / Robolectric，纯函数才能被 JVM 单测钉死；
 *    等价的严格结构化解析，语义与 `Uri`/`HttpUrl` 一致）。
 *
 * 使用方式：把「将要连接的原始端点」交给 [validate]，只有 [Result.Ok] 才能进入
 * [RelayClient.connect]。校验失败必须拒绝连接，不能降级重试。
 */
object RelayEndpointValidator {

    /** 允许的 scheme（小写）。wss=加密，ws=明文（仅 debug）。 */
    private val ALLOWED_SCHEMES = setOf("wss", "ws")

    enum class Reason {
        BLANK,            // 空串
        MALFORMED,        // 无法解析
        NOT_ABSOLUTE,     // 无 scheme（相对路径）
        SCHEME_NOT_ALLOWED, // 非 ws/wss（http/https/自定义……）
        INSECURE_REJECTED,  // 明文 ws:// 出现在不允许明文的构建
        MISSING_HOST,     // host 为空
        USERINFO_PRESENT, // 携带 userinfo（wss://user:pass@host/ws）
        PORT_INVALID,     // 端口越界
    }

    sealed interface Result {
        /** 通过校验；[url] 为规范化后的端点原文（去首尾空白）。 */
        data class Ok(val url: String) : Result

        /** 拒绝；[message] 为面向日志的安全文案（不含原始 URL，避免二次泄漏）。 */
        data class Rejected(val reason: Reason, val message: String) : Result
    }

    /**
     * @param rawUrl        原始端点（可能来自设置里的「自建中继」或配对推断）
     * @param allowInsecure 是否允许明文 `ws://`（仅 debug 构建传 true）
     */
    fun validate(rawUrl: String?, allowInsecure: Boolean): Result {
        val raw = rawUrl?.trim().orEmpty()
        if (raw.isEmpty()) return Result.Rejected(Reason.BLANK, "中继端点为空")

        val uri = runCatching { URI(raw) }.getOrNull()
            ?: return Result.Rejected(Reason.MALFORMED, "中继端点无法解析")

        val scheme = uri.scheme?.lowercase()
            ?: return Result.Rejected(Reason.NOT_ABSOLUTE, "中继端点缺少 scheme（必须是绝对 wss:// 地址）")
        if (scheme !in ALLOWED_SCHEMES) {
            return Result.Rejected(Reason.SCHEME_NOT_ALLOWED, "中继端点 scheme 不受支持")
        }

        val host = uri.host
        if (host.isNullOrBlank()) {
            return Result.Rejected(Reason.MISSING_HOST, "中继端点缺少 host")
        }
        if (uri.userInfo != null) {
            return Result.Rejected(Reason.USERINFO_PRESENT, "中继端点不得包含 userinfo")
        }
        val port = uri.port
        if (port != -1 && port !in 1..65535) {
            return Result.Rejected(Reason.PORT_INVALID, "中继端点端口非法")
        }
        if (scheme == "ws" && !allowInsecure) {
            return Result.Rejected(
                Reason.INSECURE_REJECTED,
                "Release 构建拒绝明文 ws:// 中继端点（需 wss://）",
            )
        }
        return Result.Ok(raw)
    }

    /** 便捷判断：端点是否可安全用于当前构建。 */
    fun isAcceptable(rawUrl: String?, allowInsecure: Boolean): Boolean =
        validate(rawUrl, allowInsecure) is Result.Ok
}
