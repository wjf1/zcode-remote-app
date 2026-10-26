package com.zcode.remote.storage

import android.net.Uri

/**
 * 配对设备的认证协议栈（PROTOCOL.md §11 前后两代）。
 *
 * - [V1]（≤ ZCode 3.15）：共享密钥 `passHash` + challenge/proof；`/ws` 端点、`tasks[]` 下发会话列表。
 * - [V2]（≥ ZCode 3.16）：账号登录 JWT → relay token → `ws2`；会话列表走 Controller 通道（§11.6）。
 */
enum class ProtocolStack {
    V1,
    V2,
}

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

    /**
     * 协议栈判定——**全仓唯一的 passHash 判据**。
     *
     * 判定规则：`passHash` 非空 → [ProtocolStack.V1]（V1 QR 自带共享密钥，proof 认证可用）；
     * 空 → [ProtocolStack.V2]（3.16+ 的 QR 不带 hash，凭据由登录链路提供）。
     * 连接层（V2 token 直连）、bootstrap 分流（V2 无 `tasks[]`）、配对入口（Ready/NeedsLogin）
     * 三处都必须读本属性，不得各自重写这个判据。
     */
    val protocolStack: ProtocolStack
        get() = if (passHash.isNullOrBlank()) ProtocolStack.V2 else ProtocolStack.V1

    /** 会话列表数据源是否由 Controller 通道提供（V2 host 的 bootstrap 不再下发 `tasks[]`）。 */
    val usesControllerChannel: Boolean get() = protocolStack == ProtocolStack.V2

    /** V2 配对（无 passHash）：连接需先经登录流程补齐凭据。 */
    val needsLogin: Boolean get() = protocolStack == ProtocolStack.V2
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
        return if (device.protocolStack == ProtocolStack.V1) {
            QrParseResult.Ready(device)
        } else {
            QrParseResult.NeedsLogin(device)
        }
    }

    /** 兼容旧调用方：只要解析出设备就返回（V1/V2 均可能，凭据可空性由 [PairedDevice.passHash] 表达）。 */
    fun parse(raw: String): PairedDevice? = (parseResult(raw) as? QrParseResult.Ready)?.device
}
