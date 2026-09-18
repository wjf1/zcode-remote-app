package com.zcode.remote.relay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import android.util.Base64

/**
 * 外层中继协议（见 PROTOCOL.md 第 3 节）。
 * 帧格式：{"type": "...", ...}，JSON 文本帧。
 */
object RelayProtocol {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    const val ROLE_TERMINAL = "terminal"
    const val ROLE_DEVICE = "device"

    // 中继错误码（PROTOCOL.md 第 4 节）
    enum class RelayError(val code: String) {
        KICKED("KICKED"), DEVICE_OFFLINE("DEVICE_OFFLINE"),
        AUTH_FAILED("AUTH_FAILED"), WRONG_PARAM("WRONG_PARAM"), INTERNAL("INTERNAL");
        companion object { fun from(code: String?) = entries.firstOrNull { it.code == code } }
    }

    fun authInit(deviceSid: String, appVersion: String): JsonObject = buildJsonObject {
        put("type", "auth_init")
        put("role", ROLE_TERMINAL)
        put("device_sid", deviceSid)
        put("meta", buildJsonObject {
            put("platform", "web")
            put("version", "web")
            put("name", "mobile-browser")
        })
        put("client_ts", System.currentTimeMillis())
    }

    fun nonceOf(obj: JsonObject): String? = obj["nonce"]?.let {
        runCatching { it.jsonPrimitive.content }.getOrNull()
    }

    /**
     * proof = base64url_nopad(HMAC-SHA256(key=passHash, msg="${nonce}|${role}|${deviceSid}"))
     * 与官方 WebCrypto 实现（btoa 后 +- 替换、去 =）逐字节一致（PROTOCOL.md 3 节）。
     */
    fun calculateProof(passHash: String, nonce: String, role: String, deviceSid: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(passHash.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val sig = mac.doFinal("${nonce}|${role}|${deviceSid}".toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sig, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    fun authResponse(deviceSid: String, proof: String): JsonObject = buildJsonObject {
        put("type", "auth_response")
        put("device_sid", deviceSid)
        put("proof", proof)
        put("client_ts", System.currentTimeMillis())
    }

    fun heartbeat(deviceSid: String): JsonObject = buildJsonObject {
        put("type", "pair_status_query")
        put("device_sid", deviceSid)
        put("client_ts", System.currentTimeMillis())
    }

    fun parse(text: String): JsonObject = json.parseToJsonElement(text).jsonObject

    fun typeOf(obj: JsonObject): String? = obj["type"]?.let {
        runCatching { it.jsonPrimitive.content }.getOrNull()
    }

    fun pairStatusOf(obj: JsonObject): String? = (obj["pair_status"] as? JsonObject)?.let { null }
        ?: obj["pair_status"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

    fun errorCodeOf(obj: JsonObject): String? = obj["code"]?.let {
        runCatching { it.jsonPrimitive.content }.getOrNull()
    }
}
