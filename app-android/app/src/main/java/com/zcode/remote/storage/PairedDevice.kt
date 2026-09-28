package com.zcode.remote.storage

import android.net.Uri

/**
 * 官方二维码内容（PROTOCOL.md 第 2 节）：
 * https://zcode.z.ai/remote/v4?sid=<deviceSid>&hash=<passHash>&t=<ms>&mid=<机器ID>&name=<PC名>&app_version=..&theme=..
 */
data class PairedDevice(
    val deviceSid: String,
    val passHash: String,
    val deviceMid: String?,
    val deviceName: String?,
    val remoteUrl: String,
    val pairedAtMs: Long = System.currentTimeMillis(),
) {
    val relayWsUrl: String
        get() = if (remoteUrl.contains("chatglm.site")) "wss://zcode.chatglm.site/ws" else "wss://zcode.z.ai/ws"
}

object QrParser {
    fun parse(raw: String): PairedDevice? {
        // hash 是 base64url 前的原文，可能含字面 '+'；Uri.getQueryParameter 会把 '+' 解码成空格，
        // 先统一转义（官方 URLSearchParams 生成端同样语义），否则含 '+' 的凭据配对必失败
        val uri = runCatching { Uri.parse(raw.replace("+", "%2B")) }.getOrNull() ?: return null
        val sid = uri.getQueryParameter("sid")?.takeIf { it.isNotBlank() } ?: return null
        val hash = uri.getQueryParameter("hash")?.takeIf { it.isNotBlank() } ?: return null
        return PairedDevice(
            deviceSid = sid,
            passHash = hash,
            deviceMid = uri.getQueryParameter("mid"),
            deviceName = uri.getQueryParameter("name"),
            remoteUrl = raw.substringBefore("?"),
        )
    }
}
