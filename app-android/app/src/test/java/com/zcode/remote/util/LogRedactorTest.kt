package com.zcode.remote.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志脱敏纯函数单测（任务书 §11.3.2 验收：canary 作为 SID/hash/提示词/路径/URL 输入，
 * 脱敏输出零命中）。
 */
class LogRedactorTest {

    private val canarySid = "sid_9f3c1ab2deadbeef"
    private val canaryHash = "xoh7AeqK0mNpQrStUvWxYz"
    private val canaryPath = "F:/Users/admin/secret-project/internal/keys.pem"
    private val canaryPrompt = "请把生产数据库密码改成 hunter2 并推到 main"
    private val canaryUrl = "wss://relay.internal.example:9443/ws?mid=PC-SECRET-001"

    @Test
    fun maskIdNeverLeaksIdContent() {
        val out = LogRedactor.maskId(canarySid)
        assertFalse(out.contains(canarySid))
        assertFalse(out.contains("9f3c1ab2"))
        assertTrue(out.startsWith("…"))
    }

    @Test
    fun maskIdHandlesNullAndEmpty() {
        assertEquals("null", LogRedactor.maskId(null))
        assertEquals("empty", LogRedactor.maskId(""))
    }

    @Test
    fun pathLabelNeverLeaksAnySegment() {
        val out = LogRedactor.pathLabel(canaryPath)
        assertFalse(out.contains("secret-project"))
        assertFalse(out.contains("keys.pem"))
        assertFalse(out.contains("admin"))
        assertEquals("<path:${canaryPath.length}>", out)
    }

    @Test
    fun payloadLabelNeverLeaksBody() {
        val out = LogRedactor.payloadLabel(canaryPrompt)
        assertFalse(out.contains("hunter2"))
        assertFalse(out.contains("数据库"))
        assertEquals("<payload:${canaryPrompt.length}>", out)
    }

    @Test
    fun endpointLabelKeepsSchemeAndHostOnly() {
        val out = LogRedactor.endpointLabel(canaryUrl)
        assertEquals("wss://relay.internal.example", out)
        assertFalse(out.contains("/ws"))
        assertFalse(out.contains("mid="))
        assertFalse(out.contains("PC-SECRET-001"))
    }

    @Test
    fun endpointLabelFallsBackWithoutLeaking() {
        assertEquals("<endpoint>", LogRedactor.endpointLabel(null))
        assertEquals("<endpoint>", LogRedactor.endpointLabel("   "))
        assertEquals("<endpoint>", LogRedactor.endpointLabel("not a url"))
    }

    @Test
    fun exceptionLabelOnlyClassName() {
        val t = IllegalStateException("failed to connect to $canaryUrl at $canaryPath")
        val out = LogRedactor.exceptionLabel(t)
        assertEquals("IllegalStateException", out)
        assertFalse(out.contains("relay.internal.example"))
        assertFalse(out.contains("secret-project"))
        assertEquals("unknown", LogRedactor.exceptionLabel(null))
    }

    @Test
    fun combinedLineHasZeroCanaryHits() {
        // 模拟一处真实日志行的拼装，断言最终字符串对全部 canary 零命中
        val line = "resolve sid=${LogRedactor.maskId(canarySid)} " +
            "hash=${LogRedactor.maskId(canaryHash)} " +
            "ws=${LogRedactor.pathLabel(canaryPath)} " +
            "answer=${LogRedactor.payloadLabel(canaryPrompt)} " +
            "endpoint=${LogRedactor.endpointLabel(canaryUrl)}"
        for (canary in listOf(canarySid, canaryHash, canaryPath, canaryPrompt, canaryUrl)) {
            assertFalse("canary 泄漏到日志行：$canary", line.contains(canary))
        }
        assertFalse(line.contains("hunter2"))
        assertFalse(line.contains("keys.pem"))
        assertFalse(line.contains("PC-SECRET-001"))
    }
}
