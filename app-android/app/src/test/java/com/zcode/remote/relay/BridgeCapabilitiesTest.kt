package com.zcode.remote.relay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * G5 能力位严格判定（官方 `=== true` 口径）：显式 JSON 布尔 true 才算支持；
 * 缺字段 / 非布尔 / 字符串 "true" 一律 false——老桌面缺字段 = 禁用，绝不把「缺」当「有」。
 */
class BridgeCapabilitiesTest {
    private fun caps(json: String) = BridgeCapabilities.fromBridgeReady(Json.parseToJsonElement(json).jsonObject)

    @Test
    fun missingCapabilitiesObject_meansNothingSupported() {
        val c = caps("""{"bridgeSessionId":"s1"}""")
        assertFalse(c.inputPrediction)
        assertFalse(c.windowProjectDirectory)
        assertFalse(c.anyDeclared)
    }

    @Test
    fun explicitTrue_isTheOnlyPositive() {
        val c = caps("""{"capabilities":{"inputPrediction":true}}""")
        assertEquals(true, c.inputPrediction)
        assertFalse(c.windowProjectDirectory)
    }

    @Test
    fun stringTrue_isNotTrue() {
        // 字符串 "true" 在官方 zod `z.boolean()` 下必然校验失败；App 侧同样不得视为支持
        val c = caps("""{"capabilities":{"inputPrediction":"true"}}""")
        assertFalse(c.inputPrediction)
    }

    @Test
    fun explicitFalse_andOtherFields() {
        val c = caps("""{"capabilities":{"inputPrediction":false,"windowProjectDirectory":true}}""")
        assertFalse(c.inputPrediction)
        assertEquals(true, c.windowProjectDirectory)
    }

    @Test
    fun helloPath_usesSameStrictParsing() {
        val hello =
            BridgeCapabilities.fromHello(
                Json
                    .parseToJsonElement("""{"protocolVersion":3,"capabilities":{"inputPrediction":true}}""")
                    .jsonObject,
            )
        assertEquals(true, hello.inputPrediction)
        assertFalse(hello.windowProjectDirectory)
    }
}
