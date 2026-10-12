package com.zcode.remote.util.diag

/**
 * 诊断缓冲的进程级入口（§11.8「可观测性」）。
 *
 * 与 [com.zcode.remote.service.ConnectionScope] 同款：连接栈与通知链路需要记录事件，
 * 但它们不应各自持有 UI 引用，故用一个极薄的全局入口。**只记码与计数**——写入口径见
 * [diagnosticDetail] 与 [DiagnosticsRing.sanitizeDetail] 的兜底脱敏。
 *
 * 隐私边界（硬约束）：不记录会话正文/提示词/文件路径/凭据/SID/完整 URL/RPC payload；
 * 导出**只由用户主动触发**，App 不做任何自动上传。
 */
object Diagnostics {
    val ring = DiagnosticsRing()

    /** 记录一条诊断事件（detail 只传码/计数/状态名/耗时，不传内容）。 */
    fun record(
        code: DiagEventCode,
        detail: String = "",
    ) {
        ring.record(code, detail, atMs = android.os.SystemClock.elapsedRealtime())
    }

    @Volatile
    private var appStartRecorded = false

    /** 进程内只记一次的启动事件（版本/版本码；不记设备名与任何标识符）。 */
    fun recordAppStart(
        versionName: String,
        versionCode: Int,
    ) {
        if (appStartRecorded) return
        appStartRecorded = true
        record(DiagEventCode.APP_START, "app=$versionName code=$versionCode")
    }

    /**
     * 生成导出文本（脱敏）：头部为版本/容量/计数摘要，正文为事件行 `[-]elapsed  code  detail`。
     * 纯函数（不触盘/不触 UI），JVM 单测覆盖。
     */
    fun exportText(
        appVersion: String,
        versionCode: Int,
        events: List<DiagEvent> = ring.snapshot(),
        totalRecorded: Long = ring.totalRecorded,
        capacity: Int = ring.capacity,
    ): String =
        buildString {
            appendLine("# ZCode Remote 诊断日志（脱敏；仅事件码/计数/耗时，无会话内容与凭据）")
            appendLine("# app=$appVersion ($versionCode)  events=${events.size}/$capacity  total=$totalRecorded")
            appendLine("# 本文件由用户主动导出，App 不上传任何诊断数据")
            for (e in events) {
                append(e.atMs)
                append("  ")
                append(e.code.code)
                if (e.detail.isNotEmpty()) {
                    append("  ")
                    append(e.detail)
                }
                appendLine()
            }
        }
}
