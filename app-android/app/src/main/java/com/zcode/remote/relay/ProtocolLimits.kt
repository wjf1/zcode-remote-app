package com.zcode.remote.relay

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.zip.CRC32

/**
 * 协议资源预算（任务书 §11.3.3）——**集中唯一事实来源**。
 *
 * 上界取自官方实现实证（host asar 常量对象 + 官方 web bundle 的 zod schema，2026-09-29 定位，
 * 见 `PROTOCOL.md` §4.1、`research/FRAME-CODEC.md` §3.6/§5/§6）；App 侧本地预算单独标注。
 *
 * 违反预算一律产生**固定脱敏错误码**（[ProtocolViolation.code]，只记码与计数、不记 payload）
 * 并交由上层进入重同步；**禁止无限缓存或静默截断**。若上限与真实官方帧冲突，只调这里
 * （及对应样本测试），不得移除预算机制（任务书 §11.3.3 回退条款）。
 */
object ProtocolLimits {
    // ---------- 官方协议常量（勿凭猜测调整） ----------

    /** 单个物理帧上限 1 MiB：`dataBase64` 字符数 ≤ 1 MiB，且 `data` 信封 UTF-8 字节 ≤ 1 MiB。 */
    const val MAX_PHYSICAL_FRAME_BYTES = 1024 * 1024

    /** 单条消息（重组前）上限 16 MiB（官方 `maxMessageBytes`）。 */
    const val MAX_MESSAGE_BYTES = 16 * 1024 * 1024

    /** `fragmentCount` 上限 64，`fragmentIndex` ∈ [0,63] 且 < `fragmentCount`（官方 `maxFragments`）。 */
    const val MAX_FRAGMENTS = 64

    /** 分片重组超时 30s（官方 `assemblyTimeoutMs`）。 */
    const val ASSEMBLY_TIMEOUT_MS = 30_000L

    /** 身份三元组字段长度上限（官方 `transportIdMaxChars` 家族；超长即拒，防超长键值撑内存）。 */
    const val MAX_IDENTITY_FIELD_CHARS = 256

    // ---------- App 侧本地预算（保护手机端内存/队列，非协议常量） ----------

    /** 在途重组消息条数上限（官方严格单条串行；App 容错多留几条，超出逐最旧驱逐）。 */
    const val MAX_ASSEMBLY_IN_FLIGHT_MESSAGES = 4

    /** 在途分片总字节上限（= 单条消息上限；多条并发时的总量护栏）。 */
    const val MAX_ASSEMBLY_IN_FLIGHT_BYTES = MAX_MESSAGE_BYTES

    /** 入站帧队列容量（OkHttp 线程 → 单泵协程）；溢出 = 丢帧，必须进入重同步，不无限缓存。 */
    const val INBOUND_QUEUE_CAPACITY = 1024

    /** RPC 事件流缓冲容量（A-4 的 SUSPEND 语义下，背压缓冲的上限）。 */
    const val RPC_EVENT_BUFFER_CAPACITY = 512

    /** 协议违例触发重同步的最小间隔（防「违例风暴 → 重订阅风暴」，每次重订阅都会复位重订预算）。 */
    const val RESYNC_MIN_INTERVAL_MS = 5_000L

    /** 附件上传既有预算（原 `ConversationChannel.MAX_ATTACHMENT_BYTES`，收敛到集中预算）。 */
    const val MAX_UPLOAD_ATTACHMENT_BYTES = 20 * 1024 * 1024

    /** 附件上传分片数上限（原 `ConversationChannel.MAX_CHUNKS`）。 */
    const val MAX_UPLOAD_CHUNKS = 64

    /**
     * 会话快照行数**健全性护栏**（不是业务上限）：真机最长会话实测 1100+ 行、缓存侧上限 200 行，
     * 2 万行足以覆盖正常窗口；超过即视为异常帧，显式失同步而非落地（不做静默截断）。
     */
    const val MAX_SNAPSHOT_ROWS = 20_000

    /** 单帧增量条数健全性护栏（同上：防御异常帧，非业务上限）。 */
    const val MAX_DELTAS_PER_FRAME = 5_000
}

/**
 * 固定脱敏错误码。命名与官方 fault code 同名（`research/FRAME-CODEC.md` §5），
 * 便于与 PC 端日志/`bridge-degraded.reason` 对齐排查；日志**只出现码与计数**。
 */
enum class ProtocolViolation(val code: String) {
    /** 帧身份三元组与当前桥不符（旧桥/旧连接迟到帧）——官方非终态，丢弃不回 ack。 */
    IDENTITY_MISMATCH("remote.rpcFrame.identityMismatch"),

    /** `dataBase64` 缺失/非法/非规范往返（官方 `invalidBase64`）。 */
    INVALID_BASE64("remote.rpcFrame.invalidBase64"),

    /** 元数据（seq/messageSeq/checksum 形状等）不满足官方 zod 约束（`invalidMetadata`）。 */
    METADATA("remote.rpcFrame.invalidMetadata"),

    /** `fragmentCount` 超上限或非法（官方 `fragmentLimitExceeded`）。 */
    FRAGMENT_COUNT("remote.rpcFrame.fragmentLimitExceeded"),

    /** 分片序缺口（`fragmentIndex` 跳号/首片缺失，官方 `fragmentGap`）。 */
    FRAGMENT_GAP("remote.rpcFrame.fragmentGap"),

    /** 声明长度与实际不符（官方 `lengthMismatch`）。 */
    LENGTH_MISMATCH("remote.rpcFrame.lengthMismatch"),

    /** 整条消息 CRC32 不符（官方 `checksumMismatch`）。 */
    CHECKSUM_MISMATCH("remote.rpcFrame.checksumMismatch"),

    /** 单条消息超 16 MiB（官方 `messageTooLarge`）。 */
    MESSAGE_BYTES("remote.rpcFrame.messageTooLarge"),

    /** 信封超 1 MiB（官方 `envelopeTooLarge`）。 */
    FRAME_BYTES("remote.rpcFrame.envelopeTooLarge"),

    /** 同一位点重复分片但指纹不一致（官方 `conflictingDuplicate`）。 */
    DUPLICATE_CONFLICT("remote.rpcFrame.conflictingDuplicate"),

    /** 在途分片/队列超容（官方 `bridge-degraded.reason=buffer-overflow` 同一语义）。 */
    BUFFER_OVERFLOW("remote.rpcFrame.bufferOverflow"),

    /** 分片重组超时（官方 `assemblyTimeoutMs` 到点）。 */
    ASSEMBLY_TIMEOUT("remote.rpcFrame.assemblyTimeout"),

    /** 组装完成但 VQL 解码失败——**不 ack**，重同步。 */
    DECODE_FAILED("remote.rpcFrame.decodeFailed"),

    /** 出站报文被本地预算拒绝（16 MiB / 信封 1 MiB）——物理分片未实现前不发超限帧。 */
    OUTBOUND_TOO_LARGE("remote.rpcFrame.outboundTooLarge"),
}

/**
 * rpc-frame 元数据（官方 zod `.strict()` 要求的字段子集；`seq`/`messageSeq` 为正整数，
 * `fragmentIndex` ∈ [0,63] 且 < `fragmentCount`，`messageBytes` ≤ 16 MiB，
 * `checksum{algorithm:"crc32", value:/^[0-9a-f]{8}$/}`）。
 */
internal data class RpcFrameMetadata(
    val seq: Int,
    val messageSeq: Int,
    val fragmentIndex: Int,
    val fragmentCount: Int,
    val messageBytes: Int,
    val checksumAlgorithm: String?,
    val checksumValue: String?,
)

/** 官方校验和的十六进制形态判定（小写 8 位）。 */
private val CHECKSUM_HEX = Regex("^[0-9a-f]{8}$")

/**
 * 元数据合法性校验（纯函数，单测覆盖）。返回 null = 通过；否则为固定错误码。
 * 阈值可由参数覆盖以便测试边界（生产用 [ProtocolLimits] 默认值）。
 */
internal fun validateRpcFrameMetadata(
    m: RpcFrameMetadata,
    maxFragments: Int = ProtocolLimits.MAX_FRAGMENTS,
    maxMessageBytes: Int = ProtocolLimits.MAX_MESSAGE_BYTES,
): ProtocolViolation? =
    when {
        m.seq <= 0 || m.messageSeq <= 0 -> ProtocolViolation.METADATA
        m.fragmentCount < 1 || m.fragmentCount > maxFragments -> ProtocolViolation.FRAGMENT_COUNT
        m.fragmentIndex < 0 -> ProtocolViolation.METADATA
        m.fragmentIndex >= m.fragmentCount -> ProtocolViolation.FRAGMENT_GAP
        m.messageBytes <= 0 -> ProtocolViolation.METADATA
        m.messageBytes > maxMessageBytes -> ProtocolViolation.MESSAGE_BYTES
        m.checksumAlgorithm != "crc32" -> ProtocolViolation.METADATA
        m.checksumValue == null || !CHECKSUM_HEX.matches(m.checksumValue) -> ProtocolViolation.METADATA
        else -> null
    }

/** 整条消息 CRC-32（标准反射多项式，与官方 `crc32WireBytes` 等价），小写 8 位 hex。 */
internal fun crc32Hex(bytes: ByteArray): String =
    String.format(java.util.Locale.ROOT, "%08x", CRC32().apply { update(bytes) }.value)

/**
 * 帧身份三元组（`bridgeSessionId` / `bridgeGeneration` / `recoveryId`）。
 * 三个字段都可能缺席（官方 schema 后两者可选）——缺席用 null 表示。
 */
internal data class FrameIdentity(
    val sessionId: String?,
    val generation: String?,
    val recoveryId: String?,
) {
    /** 帧里是否出现任一身份键（缺席 = 无法判定，官方按「不做全等校验」处理）。 */
    val anyPresent: Boolean get() = sessionId != null || generation != null || recoveryId != null
}

/** 从 rpc-frame 负载取身份三元组（缺失即 null）。 */
internal fun frameIdentityOf(payload: JsonObject): FrameIdentity =
    FrameIdentity(
        sessionId = payload["bridgeSessionId"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() },
        generation = payload["bridgeGeneration"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() },
        recoveryId = payload["recoveryId"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() },
    )

/** 身份字段长度护栏（超长 → [ProtocolViolation.METADATA]，不把超长键值用作 map key / 日志）。 */
internal fun validateIdentityLengths(
    identity: FrameIdentity,
    maxChars: Int = ProtocolLimits.MAX_IDENTITY_FIELD_CHARS,
): ProtocolViolation? =
    if (listOfNotNull(identity.sessionId, identity.generation, identity.recoveryId).any { it.length > maxChars }) {
        ProtocolViolation.METADATA
    } else {
        null
    }

/**
 * 帧身份与当前桥的一致性（纯函数，单测覆盖）。
 *
 * 官方规则（`PROTOCOL.md` §5、`research/CONVERSATION-PROTOCOL.md` §8）：身份三元组全等校验，
 * **任一身份键出现就必须三个全等**（含 `undefined` 的比较）；当年 App 的 ack 漏带
 * `bridgeGeneration` 即被 PC 端整帧丢弃、导致服务端无限重放。
 *
 * 本实现与官方的**唯一偏差是宽容方向**：`bridgeGeneration` / `recoveryId` 在官方 schema 里
 * 是可选键，若 PC 端某帧省略其一，严格三全等会把**全部正常帧**判为不一致（无真机可验时
 * 这是不可逆的静默故障）。这里改为：
 * - `bridgeSessionId`（zod 必带，且是桥的唯一强区分符）：出现即必须全等；
 * - `bridgeGeneration` / `recoveryId`：**出现**才比较，缺席按「不比较」处理；
 * - 三个键都不出现：不判定（兜底；官方 schema 下 sessionId 必带）。
 *
 * 放宽不会引入跨桥串帧：旧桥帧的 `bridgeSessionId` 必然不同 → 仍被拒；
 * 「迟到旧应答不得完成新请求」由 [PendingRequests] 的代次键在结构上另行保证。
 */
internal fun identityMismatch(
    frame: FrameIdentity,
    current: FrameIdentity?,
): Boolean {
    if (!frame.anyPresent) return false
    if (current == null) return true
    if (frame.sessionId != null && frame.sessionId != current.sessionId) return true
    if (frame.generation != null && frame.generation != current.generation) return true
    if (frame.recoveryId != null && frame.recoveryId != current.recoveryId) return true
    return false
}

/** 从 rpc-frame 负载取元数据；形状不符（缺键/类型不对）返回 null。 */
internal fun rpcFrameMetadataOf(payload: JsonObject): RpcFrameMetadata? {
    fun int(key: String): Int? =
        payload[key]?.let { runCatching { it.jsonPrimitive.content.toIntOrNull() }.getOrNull() }
    val seq = int("seq") ?: return null
    val messageSeq = int("messageSeq") ?: return null
    val fragmentIndex = int("fragmentIndex") ?: return null
    val fragmentCount = int("fragmentCount") ?: return null
    val messageBytes = int("messageBytes") ?: return null
    val checksum = payload["checksum"] as? JsonObject
    val algorithm = checksum?.get("algorithm")?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
    val value = checksum?.get("value")?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
    return RpcFrameMetadata(seq, messageSeq, fragmentIndex, fragmentCount, messageBytes, algorithm, value)
}
