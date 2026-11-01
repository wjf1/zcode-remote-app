package com.zcode.remote

import com.zcode.remote.storage.PairedDevice
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

/*
 * V2 登录配对（PROTOCOL.md §11）的纯逻辑层：落地页 URL 构造、登录回调解析、完成守护。
 *
 * T8 结构性修复的迁移产物：原实现（landingUrl / augmentFromUrl）内联在 Compose 文件
 * `LoginWebViewDialog.kt` 中且依赖 android.net.Uri，无法 JVM 单测；宿主迁往
 * [WebViewActivity] 时一并抽到这里（改用 java.net，行为等价），JVM 单测覆盖见
 * `WebViewLoginTest`。
 */

/** 落地页 URL：QR 的 base + 配对参数（sid 必带，mid/name 尽量带）。 */
internal fun landingUrl(device: PairedDevice): String {
    val base = device.remoteUrl.substringBefore("?")
    val params =
        buildList {
            add("sid=${device.deviceSid}")
            device.deviceMid?.let { add("mid=${encodeQueryValue(it)}") }
            device.deviceName?.let { add("name=${encodeQueryValue(it)}") }
        }
    return "$base?${params.joinToString("&")}"
}

/**
 * 解析登录回调 URL 的 query 参数；无 query 或结构损坏返回 null。
 *
 * 与 `QrParser` 同一坑：字面 `+` 是凭据（base64url 前）原文的一部分，预转义 `%2B`
 * 防止解码阶段被折叠成空格——与原 android.net.Uri 实现（`url.replace("+", "%2B")`
 * 后 `getQueryParameter`）语义一致。
 */
internal fun parseCallbackParams(url: String): Map<String, String>? {
    val rawQuery = url.substringBefore('#').substringAfter('?', "").takeIf { it.isNotEmpty() } ?: return null
    return runCatching {
        rawQuery
            .replace("+", "%2B")
            .split('&')
            .mapNotNull { pair ->
                val eq = pair.indexOf('=')
                if (eq <= 0) return@mapNotNull null
                val key = urlDecode(pair.substring(0, eq)) ?: return@mapNotNull null
                val value = urlDecode(pair.substring(eq + 1)) ?: return@mapNotNull null
                key to value
            }.toMap()
    }.getOrNull()
}

/** 从 URL 中提取 hash 补齐凭据（V1 形态回调）；无 hash（或 URL 损坏）返回 null。 */
internal fun augmentFromUrl(
    device: PairedDevice,
    url: String,
): PairedDevice? {
    val params = parseCallbackParams(url) ?: return null
    val hash = params["hash"]?.takeIf { it.isNotBlank() } ?: return null
    return device.copy(
        passHash = hash,
        deviceMid = params["mid"]?.takeIf { it.isNotBlank() } ?: device.deviceMid,
        deviceName = params["name"] ?: device.deviceName,
    )
}

/**
 * 登录完成守护：onPageFinished（同步回调流）与 localStorage jwt 求值（异步回调流）
 * 可能先后抵达，一次成功后必须忽略后续所有完成信号——原实现以 `finished` 布尔承载，
 * 迁移时抽为独立可测状态机。进程被杀重建 = 新一轮登录，随 Activity 实例重置是正确语义。
 */
internal class LoginCompletionGuard {
    private val completed = AtomicBoolean(false)

    /** 首次调用返回 true（由本次回调负责完成登录），其后一律 false。 */
    fun tryMarkCompleted(): Boolean = completed.compareAndSet(false, true)

    val isCompleted: Boolean get() = completed.get()
}

/** query 值编码：空格按 `%20`（对齐原 android.net.Uri.encode 语义；URLEncoder 的 `+` 形态不采用）。 */
private fun encodeQueryValue(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

/** query 值解码：`%XX` → 字符（同 android Uri.getQueryParameter 语义）；损坏序列返回 null。 */
private fun urlDecode(value: String): String? = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull()
