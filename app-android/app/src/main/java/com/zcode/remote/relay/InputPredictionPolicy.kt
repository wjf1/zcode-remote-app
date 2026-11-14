package com.zcode.remote.relay

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 输入预测客户端策略（G3，2026-11-14 schema 逐字段对齐官方
 * `packages/shared/src/zcode-protocol-v4/input-prediction/contract.ts`）。
 *
 * 官方口径：
 * - RPC 方法 `conversationInputPredictionV4`（agent 通道，只读无状态；Host 开关关闭 /
 *   无运行时 / 失败一律回空结果，不抛错——`zcodeAgent.ts` 接口注释）；
 * - 五种 decision：`show` / `suppress_below_threshold` / `suppress_no_context` /
 *   `unavailable` / `cancelled`；仅 `show` 可展示 `candidates[0]`；
 *   topConfidence 门槛（0.8）由服务端应用，App 按 decision 判定即可；
 * - 请求参数：`sessionId`、`revision`（手机当前看到的快照版本：缓存键的一部分，
 *   迟到响应按它丢弃）、`draft?`（≤4000，缺席/纯空白 = 预测下一条完整输入）、
 *   `requestId?`（手机生成的请求号，原样回显供丢弃过期响应）；
 * - 候选：≤3 个按 confidence 降序，text 1..40 UTF-16 单行不 trim（是「接在草稿后面的
 *   文本」），不能纯空白；
 * - 手机建议超时 25s（略长于 CLI 20s）、续写防抖 600–800ms（官方建议值）。
 */
object InputPredictionPolicy {
    const val MAX_CANDIDATES = 3
    const val MAX_TEXT_CHARS = 40
    const val MAX_DRAFT_CHARS = 4_000
    const val MAX_REQUEST_ID_CHARS = 128

    /** 续写防抖（官方建议 600–800ms 区间中值）。 */
    const val DEBOUNCE_MS = 700L

    /** RPC 超时（官方建议 25s > CLI 20s 模型超时）。 */
    const val RPC_TIMEOUT_MS = 25_000L

    /** 单个候选（宽容解析后）。 */
    data class Candidate(
        val text: String,
        val confidence: Double,
    )

    /** 预测结果（宽容解析后；解析失败返回 null = 静默无 hint）。 */
    data class Result(
        val decision: String,
        val candidates: List<Candidate>,
        val topConfidence: Double,
        val requestId: String?,
    )

    /** 是否展示 ghost hint（官方：仅 decision=="show" 且候选非空）。 */
    fun shouldShow(result: Result?): Boolean = result != null && result.decision == "show" && result.candidates.isNotEmpty()

    /** 展示用候选（已通过宽容校验的头部候选；无合格候选返回 null）。 */
    fun displayCandidate(result: Result?): Candidate? = result?.candidates?.firstOrNull { isCandidateValid(it) }

    /** 候选校验（官方 schema 的 App 侧防御口径）：1..40 字符、单行、非纯空白。 */
    fun isCandidateValid(c: Candidate): Boolean =
        c.text.isNotEmpty() &&
            c.text.length <= MAX_TEXT_CHARS &&
            c.text.trim().isNotEmpty() &&
            !c.text.contains('\n') &&
            !c.text.contains('\r')

    /** 草稿裁剪（官方「超出由手机截断再发，服务端整包拒绝」）。 */
    fun clampDraft(draft: String): String = draft.take(MAX_DRAFT_CHARS)

    /**
     * 迟到响应判定：requestId 与最新请求不匹配即丢弃（官方「原样回显，供手机丢弃过期响应」）。
     * [latestRequestId] = 最近一次发出的 requestId（null = 无在途请求，任何响应都算过期）。
     */
    fun isStaleResponse(
        result: Result?,
        latestRequestId: String?,
    ): Boolean = latestRequestId == null || result?.requestId != latestRequestId

    /**
     * 宽容解析应答（官方 schema strict，桌面端已校验；App 再防御——非法候选逐个丢弃而非整包失败）。
     * 形如 `{decision, candidates:[{text,confidence}], topConfidence, requestId?}`。
     */
    private val DECISIONS =
        setOf("show", "suppress_below_threshold", "suppress_no_context", "unavailable", "cancelled")

    fun parse(o: JsonObject?): Result? {
        if (o == null) return null
        // decision 必须在官方五种枚举内（官方 zod z.enum；数字/未知值整包拒绝）
        val decision = o.str("decision")?.takeIf { it in DECISIONS } ?: return null
        val candidates =
            o["candidates"]
                ?.let { el ->
                    runCatching {
                        (el as? JsonArray)
                            ?.mapNotNull { c ->
                                val co = c as? JsonObject ?: return@mapNotNull null
                                val text = co.str("text") ?: return@mapNotNull null
                                val conf =
                                    co["confidence"]?.let { runCatching { it.jsonPrimitive.doubleOrNull }.getOrNull() }
                                        ?: return@mapNotNull null
                                Candidate(text, conf)
                            }?.filter { isCandidateValid(it) }
                            ?.take(MAX_CANDIDATES)
                    }.getOrNull()
                }.orEmpty()
        val top =
            o["topConfidence"]?.let { runCatching { it.jsonPrimitive.doubleOrNull }.getOrNull() }
                ?: candidates.maxOfOrNull { it.confidence } ?: 0.0
        val requestId = o.str("requestId")?.takeIf { it.length <= MAX_REQUEST_ID_CHARS }
        return Result(decision, candidates, top, requestId)
    }

    /** 请求参数构造（revision = 当前已应用快照的 revision，官方「手机当前看到的快照版本」）。 */
    fun buildParams(
        sessionId: String,
        revision: Long,
        draft: String?,
        requestId: String,
    ): Map<String, Any> {
        val m = LinkedHashMap<String, Any>()
        m["sessionId"] = sessionId
        m["revision"] = revision
        if (!draft.isNullOrBlank()) m["draft"] = clampDraft(draft)
        m["requestId"] = requestId
        return m
    }

    private fun JsonObject.str(k: String): String? = this[k]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
}
