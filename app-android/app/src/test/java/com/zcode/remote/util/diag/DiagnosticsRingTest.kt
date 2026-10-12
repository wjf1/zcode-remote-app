package com.zcode.remote.util.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §11.8 诊断环形缓冲单测：容量有界（覆盖最旧）、计数自洽、**硬脱敏**（长 token 掩码 + 长度截断）、
 * 导出文本只含事件码/计数/耗时（canary 不落地）。
 */
class DiagnosticsRingTest {
    @Test
    fun ringIsBoundedAndOverwritesOldest() {
        val ring = DiagnosticsRing(capacity = 8)
        repeat(20) { i -> ring.record(DiagEventCode.RELAY_STATE, "state=S$i", atMs = i.toLong()) }
        assertEquals(8, ring.size)
        assertEquals(20L, ring.totalRecorded)
        val events = ring.snapshot()
        // 只保留最近 8 条（S12..S19），按时间升序
        assertEquals(listOf("state=S12", "state=S19"), listOf(events.first().detail, events.last().detail))
        assertEquals(12L, events.first().atMs)
    }

    @Test
    fun clearResetsEverything() {
        val ring = DiagnosticsRing(capacity = 4)
        repeat(6) { ring.record(DiagEventCode.BRIDGE_OPEN) }
        ring.clear()
        assertEquals(0, ring.size)
        assertEquals(0L, ring.totalRecorded)
        assertTrue(ring.snapshot().isEmpty())
    }

    @Test
    fun detailIsTruncated() {
        val ring = DiagnosticsRing()
        ring.record(DiagEventCode.RPC_VIOLATION, "x".repeat(500))
        val detail = ring.snapshot().single().detail
        assertTrue("detail 未截断：${detail.length}", detail.length <= DiagnosticsRing.MAX_DETAIL_CHARS)
    }

    @Test
    fun longTokensAreMaskedButCodesSurvive() {
        // 合法诊断信息（纯字母码 / 数字计数 / 短词 cause）必须原样保留
        assertEquals(
            "code=remote.rpcFrame.checksumMismatch seq=7",
            DiagnosticsRing.sanitizeDetail("code=remote.rpcFrame.checksumMismatch seq=7"),
        )
        assertEquals(
            "dropped=19418731 cause=inbound-queue-overflow",
            DiagnosticsRing.sanitizeDetail("dropped=19418731 cause=inbound-queue-overflow"),
        )
        // 疑似编码/标识符（字母数字混合且 ≥16，或长度 ≥32）一律掩码
        assertEquals("sid=bridge-<masked>", DiagnosticsRing.sanitizeDetail("sid=bridge-8f3a2b1c9d4e5f60"))
        assertEquals("hash=<masked>", DiagnosticsRing.sanitizeDetail("hash=9f2c1a4b7e5d8c3f0a1b2c3d4e5f6071"))
        assertTrue(DiagnosticsRing.sanitizeDetail("data=QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVo=").contains("<masked>"))
        assertTrue(DiagnosticsRing.sanitizeDetail("k=${"A".repeat(40)}").contains("<masked>"))
    }

    @Test
    fun exportTextIsStructuredAndMasksIdentifierStyleTokens() {
        val ring = DiagnosticsRing(capacity = 4)
        ring.record(DiagEventCode.APP_START, "app=0.5.0-beta35 code=47", atMs = 10)
        ring.record(DiagEventCode.CONV_DESYNC, "reason=logical-fragment", atMs = 20)
        ring.record(DiagEventCode.RPC_VIOLATION, "code=remote.rpcFrame.checksumMismatch sid=bridge-8f3a2b1c9d4e5f60", atMs = 30)
        val text =
            Diagnostics.exportText(
                appVersion = "0.5.0-beta35",
                versionCode = 47,
                events = ring.snapshot(),
                totalRecorded = ring.totalRecorded,
                capacity = ring.capacity,
            )
        assertTrue(text.contains("# app=0.5.0-beta35 (47)"))
        assertTrue(text.contains("events=3/4  total=3"))
        assertTrue(text.contains("app.start  app=0.5.0-beta35 code=47"))
        // 码本身完整保留（诊断价值最高的信息），只有长标识符片段被掩
        assertTrue(text.contains("rpc.violation  code=remote.rpcFrame.checksumMismatch sid=bridge-<masked>"))
        assertFalse("导出文本泄漏了长标识符", text.contains("bridge-8f3a2b1c9d4e5f60"))
        // 边界说明：环形缓冲只保证「长度截断 + 标识符样式串掩码」这道机械防线；
        // 「不传正文」由调用点纪律保证（detail 一律用固定码/计数/状态名，见 DiagEventCode 与各埋点）。
    }

    @Test
    fun snapshotIsACopy() {
        val ring = DiagnosticsRing(capacity = 4)
        ring.record(DiagEventCode.BRIDGE_READY)
        val snap = ring.snapshot()
        ring.record(DiagEventCode.BRIDGE_FAILED)
        assertEquals(1, snap.size)
        assertEquals(2, ring.size)
    }

    @Test
    fun capacityIsAtLeastOne() {
        val ring = DiagnosticsRing(capacity = 0)
        ring.record(DiagEventCode.APP_START)
        assertEquals(1, ring.size)
    }
}
