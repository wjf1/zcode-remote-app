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
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
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
