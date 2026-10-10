package com.zcode.remote.util

import java.net.URI

/**
 * 日志脱敏纯函数（任务书 §11.3.2 · P0 日志安全止血）。
 *
 * 红线：二维码内容、SID/hash、会话正文、完整文件路径、RPC payload、服务端错误原文、
 * 完整中继 URL **永不进入日志**。此前 relay/rpc 层多处把整帧/整 payload 直接打日志
 * （`ws recv $text`、`rpc recv ... data=...take(6000)`、`sid=...` 等），等于把全部会话与
 * 凭据写给任何能 `adb logcat` / 读 bugreport 的人。
 *
 * 本对象只提供**纯函数**（无 Android 依赖），输出里不含任何原始敏感子串，可被 JVM 单测钉死；
 * 调用点一律用这些 helper 生成日志文案，禁止再拼接原始值。
 */
object LogRedactor {

    private const val ELLIPSIS = "…"

    /**
     * 标识符（SID / hash / subscriptionId / interactionId …）只保留长度，不保留任何字符。
     * 例：`maskId("sid_abcdef") == "…(10)"`。
     */
    fun maskId(raw: String?): String = when {
        raw == null -> "null"
        raw.isEmpty() -> "empty"
        else -> "$ELLIPSIS(${raw.length})"
    }

    /**
     * 文件 / 工作区路径：只保留长度，绝不输出任何路径分量（目录名/文件名本身就是敏感信息）。
     * 例：`pathLabel("F:/proj/secret/a.txt") == "<path:21>"`。
     */
    fun pathLabel(raw: String?): String = "<path:${raw?.length ?: 0}>"

    /**
     * payload / 正文 / 任意 JSON 文本：只保留字节数，绝不输出内容。
     * 例：`payloadLabel("{\"text\":\"hi\"}") == "<payload:13>"`。
     */
    fun payloadLabel(raw: String?): String = "<payload:${raw?.length ?: 0}>"

    /**
     * 中继 URL：只保留 `scheme://host`（公开信息），剥掉 path / query（`?mid=`）/ userinfo。
     * 解析失败时返回固定占位，绝不回吐原文。
     */
    fun endpointLabel(raw: String?): String {
        val u = raw?.trim().orEmpty()
        if (u.isEmpty()) return "<endpoint>"
        val uri = runCatching { URI(u) }.getOrNull() ?: return "<endpoint>"
        val scheme = uri.scheme?.lowercase() ?: return "<endpoint>"
        val host = uri.host ?: return "<endpoint>"
        return "$scheme://$host"
    }

    /**
     * 异常：只输出类名。`Throwable.message` 常含 URL / 路径 / 服务端原文，
     * Release 构建不得把原始 Throwable 交给日志（任务书 §11.3.2）。
     */
    fun exceptionLabel(t: Throwable?): String = t?.javaClass?.simpleName ?: "unknown"
}
