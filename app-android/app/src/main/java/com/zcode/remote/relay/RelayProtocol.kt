package com.zcode.remote.relay

import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 外层中继协议（见 PROTOCOL.md 第 3 节）。
 * 帧格式：{"type": "...", ...}，JSON 文本帧。
 */
object RelayProtocol {
    val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    const val ROLE_TERMINAL = "terminal"
    const val ROLE_DEVICE = "device"

    // 中继错误码（PROTOCOL.md 第 4 节）
    enum class RelayError(
        val code: String,
    ) {
        KICKED("KICKED"),
        DEVICE_OFFLINE("DEVICE_OFFLINE"),
        AUTH_FAILED("AUTH_FAILED"),
        WRONG_PARAM("WRONG_PARAM"),
        INTERNAL("INTERNAL"),
        ;

        companion object {
            fun from(code: String?) = entries.firstOrNull { it.code == code }
        }
    }

    fun authInit(
        deviceSid: String,
        appVersion: String,
    ): JsonObject =
        buildJsonObject {
            put("type", "auth_init")
            put("role", ROLE_TERMINAL)
            put("device_sid", deviceSid)
            put(
                "meta",
                buildJsonObject {
                    put("platform", "web")
                    put("version", "web")
                    put("name", "mobile-browser")
                },
            )
            put("client_ts", System.currentTimeMillis())
        }

    /**
     * V2 握手（3.16+ `ws2` 中继，2026-10-25 probe 实测 matched）：
     * 无 role 字段（strictObject 校验会拒未知字段）、client_ts 为**秒**
     * （schema 上限 253402300799 是毫秒语义的 9999 年，毫秒值会超限）。
     * 身份由 WS URL 的 `?token=`（JWT 换取的 relay token）决定。
     */
    fun authInitV2(
        deviceSid: String,
        appVersion: String,
    ): JsonObject =
        buildJsonObject {
            put("type", "auth_init")
            put("device_sid", deviceSid)
            put(
                "meta",
                buildJsonObject {
                    put("platform", "android")
                    put("version", appVersion)
                    put("name", "zcode-remote-app")
                },
            )
            put("client_ts", System.currentTimeMillis() / 1000)
        }

    /** V2 心跳：官方 web/桌面端均为无 device_sid 的 `pair_status_query`（strictObject 不容多余字段）。 */
    fun heartbeatV2(): JsonObject =
        buildJsonObject {
            put("type", "pair_status_query")
            put("client_ts", System.currentTimeMillis() / 1000)
        }

    /**
     * V2 中继 WS 端点（桌面端代码实证：`relayWsUrl: ${origin}/api/v1/remote/ws2`）。
     * 由配对链接的 https origin 推导；V1 的 `/ws` 端点不认 token，会下发 challenge。
     */
    fun v2RelayWsUrl(remoteUrl: String): String {
        val origin =
            runCatching { java.net.URI(remoteUrl).let { "${it.scheme}://${it.host}" } }
                .getOrNull()
                ?.takeIf { it.startsWith("http") }
                ?: "https://zcode.z.ai"
        val wsOrigin =
            if (origin.startsWith("https://")) {
                "wss://" + origin.removePrefix("https://")
            } else {
                "ws://" + origin.removePrefix("http://")
            }
        return "$wsOrigin/api/v1/remote/ws2"
    }

    fun nonceOf(obj: JsonObject): String? =
        obj["nonce"]?.let {
            runCatching { it.jsonPrimitive.content }.getOrNull()
        }

    /**
     * proof = base64url_nopad(HMAC-SHA256(key=passHash, msg="${nonce}|${role}|${deviceSid}"))
     * 与官方 WebCrypto 实现（btoa 后 +- 替换、去 =）逐字节一致（PROTOCOL.md 3 节）。
     */
    fun calculateProof(
        passHash: String,
        nonce: String,
        role: String,
        deviceSid: String,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(passHash.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val sig = mac.doFinal("$nonce|$role|$deviceSid".toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sig, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    fun authResponse(
        deviceSid: String,
        proof: String,
    ): JsonObject =
        buildJsonObject {
            put("type", "auth_response")
            put("device_sid", deviceSid)
            put("proof", proof)
            put("client_ts", System.currentTimeMillis())
        }

    fun heartbeat(deviceSid: String): JsonObject =
        buildJsonObject {
            put("type", "pair_status_query")
            put("device_sid", deviceSid)
            put("client_ts", System.currentTimeMillis())
        }

    fun parse(text: String): JsonObject = json.parseToJsonElement(text).jsonObject

    fun typeOf(obj: JsonObject): String? =
        obj["type"]?.let {
            runCatching { it.jsonPrimitive.content }.getOrNull()
        }

    fun pairStatusOf(obj: JsonObject): String? =
        (obj["pair_status"] as? JsonObject)?.let { null }
            ?: obj["pair_status"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

    fun errorCodeOf(obj: JsonObject): String? =
        obj["code"]?.let {
            runCatching { it.jsonPrimitive.content }.getOrNull()
        }
}
