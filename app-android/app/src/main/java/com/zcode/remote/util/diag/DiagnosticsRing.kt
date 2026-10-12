package com.zcode.remote.util.diag

/**
 * 诊断事件固定码（§11.8「可观测性」）。
 *
 * **只允许码 + 计数 + 耗时 + 状态名**：绝不记录会话正文、凭据、SID/hash、完整 URL 或 RPC payload。
 * 每个码在 [Diagnostics.record] 入口还要过一遍 [sanitizeDetail]（长度截断 + 长串掩码）作为兜底。
 */
enum class DiagEventCode(val code: String) {
    APP_START("app.start"),
    RELAY_STATE("relay.state"),
    RELAY_RECONNECT("relay.reconnect"),
    BRIDGE_OPEN("bridge.open"),
    BRIDGE_READY("bridge.ready"),
    BRIDGE_FAILED("bridge.failed"),
    BRIDGE_REOPEN("bridge.reopen"),
    RPC_VIOLATION("rpc.violation"),
    INBOUND_OVERFLOW("inbound.overflow"),
    RESYNC_TRIGGERED("resync.triggered"),
    RESYNC_THROTTLED("resync.throttled"),
    CONV_DESYNC("conv.desync"),
    CONV_SUBSCRIBE("conv.subscribe"),
    CONV_SUBSCRIBE_FAILED("conv.subscribe_failed"),
    CONV_HANDSHAKE_RETRY("conv.handshake_retry"),
    DIAG_EXPORT_REQUESTED("diag.export_requested"),
    DIAG_EXPORT_WRITTEN("diag.export_written"),
    DIAG_EXPORT_FAILED("diag.export_failed"),
}

/** 一条诊断事件（detail 已脱敏、已截断）。 */
data class DiagEvent(
    val atMs: Long,
    val code: DiagEventCode,
    val detail: String,
)

/**
 * 容量有界的诊断环形缓冲（纯逻辑，JVM 单测覆盖）。
 *
 * 设计约束（任务书 §11.8）：本地、脱敏、**容量有界**、由用户主动导出、**不接入默认上传**。
 * 满员后覆盖最旧一条；`snapshot()` 返回按时间升序的副本。
 */
class DiagnosticsRing(
    capacity: Int = DEFAULT_CAPACITY,
) {
    /** 实际容量（≥1；容量 0 会让「满员覆盖」逻辑在空队列上移除元素）。 */
    private val limit: Int = capacity.coerceAtLeast(1)

    /** 对外报告的有效容量。 */
    val capacity: Int get() = limit

    private val events = ArrayDeque<DiagEvent>(limit)

    /** 累计写入条数（含已被覆盖的），用于导出头部统计。 */
    var totalRecorded: Long = 0L
        private set

    val size: Int get() = events.size

    fun record(
        code: DiagEventCode,
        detail: String = "",
        atMs: Long = 0L,
    ) {
        totalRecorded += 1
        if (events.size >= limit) events.removeFirst()
        events.addLast(DiagEvent(atMs, code, sanitizeDetail(detail)))
    }

    /** 按时间升序快照（副本，外部不可改内部状态）。 */
    fun snapshot(): List<DiagEvent> = events.toList()

    fun clear() {
        events.clear()
        totalRecorded = 0
    }

    companion object {
        /** 512 条 × ~80 字节 ≈ 40 KB 级别，长驻内存可忽略；只保留最近窗口。 */
        const val DEFAULT_CAPACITY = 512

        /** detail 截断长度。 */
        const val MAX_DETAIL_CHARS = 120

        /**
         * 兜底脱敏：① 截断到 [MAX_DETAIL_CHARS]；② 长「疑似编码/标识符」串掩码成 `<masked>`。
         *
         * 掩码判据（**熵启发式**，避免误伤合法诊断信息）：连续 `[A-Za-z0-9+/_]` 且长度 ≥16 的串，
         * 仅当「字母与数字并存」（典型 base64/hex/UUID 片段）或「长度 ≥32」（长纯字母密钥）时才掩码。
         * 这样 `checksumMismatch`（16 位纯字母码）、`inbound-queue-overflow`、纯数字计数都能保留，
         * 而 `8f3a2b1c9d4e5f60`、`QUJD…Vo`、32 位 hex 一律被掩。
         *
         * 正常 detail 只应传「码/计数/状态名/耗时」，本函数是最后一道保险。
         */
        fun sanitizeDetail(raw: String): String {
            if (raw.isEmpty()) return ""
            val truncated = raw.take(MAX_DETAIL_CHARS)
            val masked = CANDIDATE_RUN.replace(truncated) { m ->
                val s = m.value
                val suspicious = s.length >= 32 || (s.any { it.isDigit() } && s.any { it.isLetter() })
                if (suspicious) "<masked>" else s
            }
            return if (masked.length > MAX_DETAIL_CHARS) masked.take(MAX_DETAIL_CHARS) else masked
        }

        /** 候选串：字母/数字/base64 字母表/下划线；`-` `.` `:` `=` 与空格天然断开连续串。 */
        private val CANDIDATE_RUN = Regex("[A-Za-z0-9+/_]{16,}")
    }
}
