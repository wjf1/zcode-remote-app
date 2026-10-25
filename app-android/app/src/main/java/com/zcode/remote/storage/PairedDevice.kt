package com.zcode.remote.storage

import android.net.Uri

/**
 * 官方二维码内容（PROTOCOL.md 第 2 节，V1 ≤3.15 与 V2 ≥3.16 双格式）：
 * - V1：`...?sid=&hash=&t=<ms>&mid=&name=&app_version=&theme=`（hash 为共享密钥，proof 认证）
 * - V2（3.16+）：`...?sid=&mid=&name=&app_version=`（无 hash/t，凭据由登录链路下发）
 *
 * [passHash] 在 V2 配对下可能为 null：此时 challenge/proof 路径不可用，
 * 需经登录流程（WebView 打开官方落地页）获得凭据后补齐。
 */
data class PairedDevice(
    val deviceSid: String,
    val passHash: String?,
    val deviceMid: String?,
    val deviceName: String?,
    val remoteUrl: String,
    val pairedAtMs: Long = System.currentTimeMillis(),
) {
    val relayWsUrl: String
        get() = if (remoteUrl.contains("chatglm.site")) "wss://zcode.chatglm.site/ws" else "wss://zcode.z.ai/ws"

    /** V2 配对（无 passHash）：连接需先经登录流程补齐凭据。 */
    val needsLogin: Boolean get() = passHash.isNullOrBlank()
}

/** QrParser 解析结果：区分「直接可连」与「需登录补凭据」两条路径。 */
sealed interface QrParseResult {
    /** V1（带 hash）或已补齐凭据的 V2：可直接建立中继连接。 */
    data class Ready(
        val device: PairedDevice,
    ) : QrParseResult

    /** V2 新版二维码（无 hash）：sid/mid 已拿到，凭据待登录流程下发（PROTOCOL.md §11）。 */
    data class NeedsLogin(
        val device: PairedDevice,
    ) : QrParseResult
}

object QrParser {
    /**
     * 解析官方二维码 / 手输链接。
     *
     * - 带 `sid` + `hash`（V1，或登录回调补齐后的 URL）→ [QrParseResult.Ready]
     * - 仅 `sid`（V2 新版 QR，3.16+）→ [QrParseResult.NeedsLogin]
     * - 其余 → null
     */
    fun parseResult(raw: String): QrParseResult? {
        // hash 是 base64url 前的原文，可能含字面 '+'；Uri.getQueryParameter 会把 '+' 解码成空格，
        // 先统一转义（官方 URLSearchParams 生成端同样语义），否则含 '+' 的凭据配对必失败
        val uri = runCatching { Uri.parse(raw.replace("+", "%2B")) }.getOrNull() ?: return null
        val sid = uri.getQueryParameter("sid")?.takeIf { it.isNotBlank() } ?: return null
        val hash = uri.getQueryParameter("hash")?.takeIf { it.isNotBlank() }
        val device =
            PairedDevice(
                deviceSid = sid,
                passHash = hash,
                deviceMid = uri.getQueryParameter("mid")?.takeIf { it.isNotBlank() },
                deviceName = uri.getQueryParameter("name"),
                remoteUrl = raw.substringBefore("?"),
            )
        return if (hash != null) QrParseResult.Ready(device) else QrParseResult.NeedsLogin(device)
    }

    /** 兼容旧调用方：只要解析出设备就返回（V1/V2 均可能，凭据可空性由 [PairedDevice.passHash] 表达）。 */
    fun parse(raw: String): PairedDevice? = (parseResult(raw) as? QrParseResult.Ready)?.device
}
