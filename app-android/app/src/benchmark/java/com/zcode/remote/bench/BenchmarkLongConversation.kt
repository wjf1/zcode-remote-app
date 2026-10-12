package com.zcode.remote.bench

import com.zcode.remote.relay.ConversationRow

/**
 * Macrobenchmark 长会话 fixture 生成器（仅存在于 `benchmark` 构建变体，不进 debug/release 包）。
 *
 * 目标：把「真机实测过的长会话」（1100+ 行、混合用户消息 / 助手 Markdown / 思考 / 工具调用）
 * 固化成确定性可重复生成的数据，让 `:benchmark` 模块能在无中继、无配对、无网络的前提下
 * 量测会话页的真实渲染负载（§11.8 长会话基准）。
 *
 * 行构成按真机会话近似（每 10 行：2 用户 + 4 助手 + 2 思考 + 2 工具），助手行携带标题 /
 * 有序列表 / 围栏代码块 / GFM 表格等真实 Markdown 结构——渲染成本与真机同源（走同一条
 * MarkdownView 解析与高亮路径），不是占位文本。
 */
internal object BenchmarkLongConversation {
    /** 默认长会话行数：对齐真机实测的 1100+ 行量级。 */
    const val DEFAULT_ROWS: Int = 1200

    private val USER_TEXTS =
        listOf(
            "继续：把模块 %d 的构建产物与体积对比一下，有回归就指出是哪一步引入的。",
            "第 %d 轮：检查一下 lint 报告里新增的告警，只报结论，不要贴全文。",
        )

    private val REASONING_TEXTS =
        listOf(
            "先确认缓存是否命中，若不命中再退化为全量重算——避免无谓的长任务。",
            "这里需要区分「协议层错误」与「展示层文案」，前者进诊断缓冲、后者走映射表。",
        )

    private val TOOL_OUTPUT_GRADLE =
        """
        > Task :app:testDebugUnitTest UP-TO-DATE
        BUILD SUCCESSFUL in 42s
        121 actionable tasks: 86 executed, 35 up-to-date
        """.trimIndent()

    private val TOOL_OUTPUT_READ =
        """
        object ProtocolLimits {
            const val MAX_PHYSICAL_FRAME_BYTES = 1L * 1024 * 1024
            const val MAX_MESSAGE_BYTES = 16L * 1024 * 1024
            const val MAX_FRAGMENTS = 64
            const val ASSEMBLY_TIMEOUT_MS = 30_000L
        }
        """.trimIndent()

    private val TOOL_CALLS =
        listOf(
            Triple("run_command", "gradle :app:testDebugUnitTest --console=plain", TOOL_OUTPUT_GRADLE),
            Triple("read_file", "app-android/app/src/main/java/com/zcode/remote/relay/ProtocolLimits.kt", TOOL_OUTPUT_READ),
        )

    private val ASSISTANT_PARAGRAPH =
        """
        已完成第 %d 轮检查。结论先说：**没有发现功能性缺陷**，但有两处可以改进——

        1. 构建产物体积比上一轮增加 1.8%，来自新增的图标资源；
        2. lint 报告中 `UnusedResources` 新增 3 条，均为历史遗留，不阻塞发布。

        详细数据见下方表格与代码块。
        """.trimIndent()

    private val ASSISTANT_CODE =
        """
        修复落在 `RelayClient` 的入站处理上，核心改动是让队列溢出可见：

        ```kotlin
        private val inboundQueue = Channel<Frame>(capacity = INBOUND_CAPACITY)
        // 溢出不静默：计数 + 回调（掉帧 = 失同步信号 → 节流重订阅）
        if (!inboundQueue.trySend(frame).isSuccess) {
            droppedFrames++
            onInboundOverflow(droppedFrames)
        }
        ```

        这样服务端重放与客户端重订阅形成闭环，避免「连接正常但 UI 停旧状态」。
        """.trimIndent()

    private val ASSISTANT_TABLE =
        """
        | 分项 | 本轮 | 上轮 | 变化 |
        | --- | --- | --- | --- |
        | 首帧耗时 P50 | 412 ms | 398 ms | +3.5% |
        | 滚动帧 P99 | 14 ms | 12 ms | +2 ms |
        | 包体 | 12.4 MB | 12.2 MB | +1.8% |

        指标来自回归网，绝对值仅供趋势参考；真机口径以小米 15 Pro 实测为准。
        """.trimIndent()

    /** 生成 [count] 行的确定性长会话（rowId 从 1 起，与真实 loadMore 拼接语义一致）。 */
    fun rows(count: Int): List<ConversationRow> = (1..count).map(::rowAt)

    /** 流式追加：生成下一行（rowId 递增，kind 周期与初始批次一致）。 */
    fun appendRow(rowId: Int): ConversationRow = rowAt(rowId)

    private fun rowAt(i: Int): ConversationRow =
        when (i % 10) {
            0 -> ConversationRow(rowId = i, kind = "userInput", text = USER_TEXTS[0].format(i))
            1 ->
                ConversationRow(
                    rowId = i,
                    kind = "reasoning",
                    text = REASONING_TEXTS[i % REASONING_TEXTS.size],
                    state = "completed",
                )

            2 -> ConversationRow(rowId = i, kind = "assistantText", text = ASSISTANT_PARAGRAPH.format(i))
            3 -> toolRow(i, TOOL_CALLS[0])
            4 -> ConversationRow(rowId = i, kind = "assistantText", text = ASSISTANT_CODE)
            5 -> toolRow(i, TOOL_CALLS[1])
            6 -> ConversationRow(rowId = i, kind = "assistantText", text = ASSISTANT_TABLE)
            7 -> ConversationRow(rowId = i, kind = "userInput", text = USER_TEXTS[1].format(i))
            8 ->
                ConversationRow(
                    rowId = i,
                    kind = "reasoning",
                    text = REASONING_TEXTS[(i + 1) % REASONING_TEXTS.size],
                    state = "completed",
                )

            else ->
                ConversationRow(
                    rowId = i,
                    kind = "assistantText",
                    text =
                        "第 $i 轮收尾：改动共 3 个文件（+42 / -17 行），单测与 lint 全绿。" +
                            "下一步建议先把 ${i / 10} 号待办清掉，再做一次真机灰度。",
                )
        }

    private fun toolRow(
        i: Int,
        call: Triple<String, String, String>,
    ): ConversationRow =
        ConversationRow(
            rowId = i,
            kind = "toolCall",
            toolName = call.first,
            toolCallId = "bench-call-$i",
            status = "completed",
            inputText = call.second,
            outputText = call.third,
        )
}
