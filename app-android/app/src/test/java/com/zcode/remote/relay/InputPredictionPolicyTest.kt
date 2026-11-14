package com.zcode.remote.relay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** G3 输入预测客户端策略（schema 逐字段对齐官方 input-prediction/contract.ts）。 */
class InputPredictionPolicyTest {
    private fun parse(json: String?): InputPredictionPolicy.Result? =
        if (json == null) {
            null
        } else {
            runCatching {
                InputPredictionPolicy.parse(Json.parseToJsonElement(json).jsonObject)
            }.getOrNull()
        }

    @Test
    fun parse_fullShape() {
        val r =
            parse(
                """{"decision":"show","candidates":[{"text":"帮我写","confidence":0.9}],
                   "topConfidence":0.9,"requestId":"req-1"}""",
            )
        assertEquals("show", r?.decision)
        assertEquals(1, r?.candidates?.size)
        assertEquals("req-1", r?.requestId)
        assertTrue(InputPredictionPolicy.shouldShow(r))
        assertEquals("帮我写", InputPredictionPolicy.displayCandidate(r)?.text)
    }

    @Test
    fun show_requiresNonEmptyCandidates() {
        val r = parse("""{"decision":"show","candidates":[],"topConfidence":0.9}""")
        assertFalse(InputPredictionPolicy.shouldShow(r))
        assertNull(InputPredictionPolicy.displayCandidate(r))
    }

    @Test
    fun nonShowDecision_neverShows() {
        for (d in listOf("suppress_below_threshold", "suppress_no_context", "unavailable", "cancelled")) {
            val r = parse("""{"decision":"$d","candidates":[{"text":"x","confidence":1.0}],"topConfidence":1.0}""")
            assertFalse(d, InputPredictionPolicy.shouldShow(r))
        }
    }

    @Test
    fun invalidCandidates_areDropped() {
        // 41 字符超长 / 含换行 / 纯空白 → displayCandidate 跳过该候选
        val long41 = "a".repeat(41)
        val r =
            parse(
                """{"decision":"show","candidates":[
                   {"text":"$long41","confidence":0.9},
                   {"text":"line1\nline2","confidence":0.8},
                   {"text":"   ","confidence":0.7},
                   {"text":"ok 候选","confidence":0.6}],"topConfidence":0.9}""",
            )
        assertEquals("ok 候选", InputPredictionPolicy.displayCandidate(r)?.text)
    }

    @Test
    fun candidates_cappedAt3() {
        val r =
            parse(
                """{"decision":"show","candidates":[
                   {"text":"a","confidence":0.9},{"text":"b","confidence":0.8},
                   {"text":"c","confidence":0.7},{"text":"d","confidence":0.6}],"topConfidence":0.9}""",
            )
        assertEquals(3, r?.candidates?.size)
    }

    @Test
    fun staleResponse_discardsLateReply() {
        val r = parse("""{"decision":"show","candidates":[{"text":"x","confidence":1.0}],"topConfidence":1.0,"requestId":"r1"}""")
        assertFalse(InputPredictionPolicy.isStaleResponse(r, "r1"))
        assertTrue(InputPredictionPolicy.isStaleResponse(r, "r2")) // 新请求已发出：旧响应丢弃
        assertTrue(InputPredictionPolicy.isStaleResponse(r, null)) // 无在途请求：任何响应过期
        assertTrue(InputPredictionPolicy.isStaleResponse(null, "r1"))
    }

    @Test
    fun parse_garbageIsSafe() {
        assertNull(parse("""{"decision":123}"""))
        assertNull(parse("[]"))
        assertNull(parse(null))
    }

    @Test
    fun buildParams_clampsLongDraft_andOmitsBlank() {
        val p1 = InputPredictionPolicy.buildParams("s1", 7L, "d".repeat(4_100), "r1")
        assertEquals(4_000, (p1["draft"] as String).length)
        assertEquals(7L, p1["revision"])
        val p2 = InputPredictionPolicy.buildParams("s1", 7L, "   ", "r1")
        assertFalse(p2.containsKey("draft")) // 纯空白草稿 = 预测下一条完整输入（官方缺席语义）
        assertTrue("requestId" in p2)
    }
}
