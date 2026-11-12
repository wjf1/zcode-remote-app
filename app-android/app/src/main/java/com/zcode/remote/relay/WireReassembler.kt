package com.zcode.remote.relay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Base64
import java.util.TreeMap

/**
 * conversation V4 逻辑帧重组器（A-5，1.0 发布硬门）。
 *
 * **算法逐规则转译自官方 `packages/shared/src/zcode-protocol-v4/wire-reassembly.ts` 的
 * `reassembleTopicWireFrames`**（2026-11-12 从官方镜像逐行核对），字段 schema 见同目录
 * `wire.ts`。与 rpc-frame 的物理分片（≤64 片 / VQL 层）是**两个不同的层**：本类处理的是
 * conversation 快照/增量帧在 topic wire 层的逻辑分帧（≤1024 片 / 16 MiB）。
 *
 * 背景与必要性：桌面端在单条 conversation 帧 > 1 MiB 物理帧上限时按逻辑帧分片下发
 * （`kind:"fragment"`）。旧实现收到 fragment 即显式失同步 → 重订阅 → 又收到 fragment，
 * 形成「大会话永远同步不上」的潜在死循环——本类把 fragment 正常重组后走与 complete
 * 相同的处理路径，根治该问题。
 *
 * App 侧护栏（官方许可范围内的本地收紧）：
 * - staging 并发 assembly 上限 [MAX_OPEN_ASSEMBLIES]（官方 32，App 收紧到 8），满员驱逐最旧；
 * - 每个 assembly [ASSEMBLY_TIMEOUT_MS] 无新片即丢弃（官方靠订阅生命周期回收，App 弹层
 *   生命周期独立，需自带超时防泄漏）。
 */
class WireReassembler(
    private val maxAssemblyBytes: Int = ProtocolLimits.MAX_MESSAGE_BYTES,
    private val maxFragments: Int = LOGICAL_FRAME_MAX_FRAGMENTS,
    private val maxOpenAssemblies: Int = MAX_OPEN_ASSEMBLIES,
    private val assemblyTimeoutMs: Long = ASSEMBLY_TIMEOUT_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    sealed interface Outcome {
        /** 重组成功：[frame] 为拼接出的逻辑帧 JSON（形态 = 官方 complete 的 `frame` 字段）。 */
        data class Complete(
            val frame: JsonObject,
            val deliveryKind: String?,
        ) : Outcome

        /** 缺片等待：不构成错误，等待后续 fragment 补齐。 */
        data class Incomplete(
            val logicalFrameId: String,
            val missingIndexes: List<Int>,
        ) : Outcome

        /** 协议违例：reasonCode 与官方 fault 口径一致（proto.frameAssembly*）。 */
        data class Rejected(
            val reasonCode: String,
        ) : Outcome

        /** 非 fragment 帧（complete 走调用方原路径，不经重组器）。 */
        data object Ignored : Outcome
    }

    /** 重组所需的全部 wire 元数据（字段缺失 = 官方 typed fault）。 */
    private data class Meta(
        val id: String,
        val index: Int,
        val count: Int,
        val logicalBytes: Int,
        val checksumValue: String,
        val deliveryKind: String?,
        val topic: String,
        val subscriptionId: String,
        val ordinal: Int,
        val dataB64: String,
    )

    /** 单个 logicalFrameId 的 staging 状态（元数据以显式字段存放，供后续片全等比对）。 */
    private class Assembly(
        val fragmentCount: Int,
        val logicalBytes: Int,
        val checksumValue: String,
        val deliveryKind: String?,
        val topic: String,
        val subscriptionId: String,
        val ordinal: Int,
    ) {
        val fragments = TreeMap<Int, ByteArray>()
        var decodedBytes = 0
        var lastTouchMs = 0L
    }

    private val open = LinkedHashMap<String, Assembly>()

    /**
     * 喂入一帧 wire 帧本体（`event.data` 解出的 JsonObject）。
     * `kind != "fragment"` 返回 [Outcome.Ignored]（complete 走调用方原路径）。
     */
    fun feed(wire: JsonObject): Outcome {
        evictExpired()
        if ((wire.str("kind") ?: return Outcome.Ignored) != "fragment") return Outcome.Ignored
        val meta = parseMeta(wire) ?: return reject("proto.frameAssemblyMetadataMismatch")

        validate(meta, open[meta.id])?.let { return reject(it) }

        val decoded =
            try {
                Base64.getDecoder().decode(meta.dataB64)
            } catch (
                @Suppress("SwallowedException") e: IllegalArgumentException,
            ) {
                return reject("proto.frameAssemblyInvalidBase64")
            }

        val state = open[meta.id]
        if (state == null) {
            // staging 满员驱逐最旧（App 本地护栏；官方 32 并发由订阅生命周期兜底）
            while (open.size >= maxOpenAssemblies) {
                val oldest = open.keys.firstOrNull() ?: break
                open.remove(oldest)
            }
            val assembly =
                Assembly(
                    fragmentCount = meta.count,
                    logicalBytes = meta.logicalBytes,
                    checksumValue = meta.checksumValue,
                    deliveryKind = meta.deliveryKind,
                    topic = meta.topic,
                    subscriptionId = meta.subscriptionId,
                    ordinal = meta.ordinal,
                )
            assembly.lastTouchMs = nowMs()
            open[meta.id] = assembly
            return admit(assembly, meta.id, meta.index, decoded)
        }

        state.lastTouchMs = nowMs()
        return admit(state, meta.id, meta.index, decoded)
    }

    /**
     * 全量校验（纯函数）：官方 wire.ts superRefine 两条硬约束 + 1024 片/16 MiB 上限 +
     * 已存在 assembly 的元数据全等比对。返回 null = 通过，否则为官方 fault reasonCode。
     */
    private fun validate(
        meta: Meta,
        state: Assembly?,
    ): String? =
        when {
            meta.index >= meta.count -> "proto.frameAssemblyMetadataMismatch"
            meta.count > meta.logicalBytes -> "proto.frameAssemblyMetadataMismatch"
            meta.count > maxFragments -> "proto.frameFragmentCountExceeded"
            meta.logicalBytes > maxAssemblyBytes -> "proto.frameAssemblyTooLarge"
            // 已存在的 staging：官方要求 id/ordinal/deliveryKind/topic/subId/count/
            // logicalBytes/checksum 任一变化即 metadataMismatch
            state != null &&
                (
                    state.fragmentCount != meta.count ||
                        state.logicalBytes != meta.logicalBytes ||
                        state.checksumValue != meta.checksumValue ||
                        state.deliveryKind != meta.deliveryKind ||
                        state.topic != meta.topic ||
                        state.subscriptionId != meta.subscriptionId ||
                        state.ordinal != meta.ordinal
                ) -> "proto.frameAssemblyMetadataMismatch"
            else -> null
        }

    /** 清空全部 staging（订阅重置 / 断开时调用）。 */
    fun reset() {
        open.clear()
    }

    // ---------- 内部 ----------

    private fun parseMeta(wire: JsonObject): Meta? {
        val id = wire.str("logicalFrameId") ?: return null
        if (id.isEmpty()) return null
        val index = wire.int("fragmentIndex") ?: return null
        val count = wire.int("fragmentCount") ?: return null
        val logicalBytes = wire.int("logicalBytes") ?: return null
        val checksumValue = wire.obj("checksum")?.str("value") ?: return null
        val topic = wire.str("topic") ?: return null
        val subscriptionId = wire.str("subscriptionId") ?: return null
        val dataB64 = wire.str("dataBase64") ?: return null
        return Meta(
            id = id,
            index = index,
            count = count,
            logicalBytes = logicalBytes,
            checksumValue = checksumValue,
            deliveryKind = wire.str("deliveryKind"),
            topic = topic,
            subscriptionId = subscriptionId,
            ordinal = wire.int("logicalFrameOrdinal") ?: 0,
            dataB64 = dataB64,
        )
    }

    private fun admit(
        state: Assembly,
        id: String,
        index: Int,
        decoded: ByteArray,
    ): Outcome {
        val previous = state.fragments[index]
        if (previous != null) {
            // 官方：重复 index 内容不同 → fragmentConflict；完全相同 → 幂等跳过
            if (!previous.contentEquals(decoded)) {
                open.remove(id)
                return reject("proto.frameAssemblyFragmentConflict")
            }
            return incomplete(state, id)
        }
        val nextTotal = state.decodedBytes + decoded.size
        if (nextTotal > maxAssemblyBytes) return reject("proto.frameAssemblyTooLarge")
        if (nextTotal > state.logicalBytes) {
            open.remove(id)
            return reject("proto.frameAssemblyLengthMismatch")
        }
        state.fragments[index] = decoded
        state.decodedBytes = nextTotal

        // 片数未收齐 → 等待（官方要求 fragmentCount ≤ logicalBytes，片数有界）
        if (state.fragments.size < state.fragmentCount) return incomplete(state, id)
        if (state.decodedBytes != state.logicalBytes) {
            open.remove(id)
            return reject("proto.frameAssemblyLengthMismatch")
        }

        // 按 index 顺序拼接（TreeMap 天然有序）
        val logical = ByteArray(state.decodedBytes)
        var offset = 0
        for (i in 0 until state.fragmentCount) {
            val fragment = state.fragments.getValue(i)
            fragment.copyInto(logical, offset)
            offset += fragment.size
        }
        open.remove(id)

        // CRC32 → UTF-8 严格 → JSON → 信封匹配（官方四连校验）
        if (crc32Hex(logical) != state.checksumValue) return reject("proto.frameAssemblyChecksumMismatch")
        val text =
            try {
                STRICT_UTF8.decode(java.nio.ByteBuffer.wrap(logical)).toString()
            } catch (
                @Suppress("SwallowedException") e: java.nio.charset.CharacterCodingException,
            ) {
                return reject("proto.frameAssemblyInvalidUtf8")
            }

        // 官方 InvalidJson 口径：payload 不是合法 JSON（错误码即语义，原异常无需保留）
        @Suppress("SwallowedException")
        val frame =
            try {
                Json.parseToJsonElement(text) as? JsonObject ?: return reject("proto.frameAssemblyInvalidJson")
            } catch (e: kotlinx.serialization.SerializationException) {
                return reject("proto.frameAssemblyInvalidJson")
            }
        val frameTopic = frame.str("topic")
        val frameSub = frame.str("subscriptionId")
        if (frameTopic != state.topic || frameSub != state.subscriptionId) {
            return reject("proto.frameAssemblyMetadataMismatch")
        }
        return Outcome.Complete(frame = frame, deliveryKind = state.deliveryKind)
    }

    private fun incomplete(
        state: Assembly,
        id: String,
    ): Outcome {
        val missing = (0 until state.fragmentCount).filter { it !in state.fragments }
        return Outcome.Incomplete(logicalFrameId = id, missingIndexes = missing)
    }

    private fun evictExpired() {
        val now = nowMs()
        val it = open.entries.iterator()
        while (it.hasNext()) {
            if (now - it.next().value.lastTouchMs > assemblyTimeoutMs) it.remove()
        }
    }

    private fun reject(reason: String): Outcome = Outcome.Rejected(reason)

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    companion object {
        /** 官方 `logicalFrameAssemblyMaxFragments`（core.ts，PROTOCOL.md §4.1 已记录）。 */
        const val LOGICAL_FRAME_MAX_FRAGMENTS = 1024

        /** App 本地 staging 并发上限（官方 32，收紧到 8：手机侧单会话订阅用不满）。 */
        const val MAX_OPEN_ASSEMBLIES = 8

        /** assembly 超时：30s 无新片即丢弃（官方 ack 超时同口径）。 */
        const val ASSEMBLY_TIMEOUT_MS = 30_000L

        private val STRICT_UTF8 =
            java.nio.charset.Charset
                .forName("UTF-8")
                .newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
    }
}
