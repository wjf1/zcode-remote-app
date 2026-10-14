package com.zcode.remote.ui.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.zcode.remote.relay.ConversationRow
import com.zcode.remote.relay.RowAttachment
import com.zcode.remote.ui.screens.RowItem
import com.zcode.remote.ui.theme.ZCodeTheme

/**
 * 会话时间线行渲染器的布局预览（仅 debug 变体渲染，见 build.gradle.kts 的 `ui-tooling`）。
 *
 * 为什么需要：本项目本机**跑不了模拟器**（兆芯 CPU 无虚拟化），UI 改动原先只能靠真机验收
 * ——每次调样式都要编译装机。有了 `@Preview` + fixture 数据，Android Studio 里可直接看到
 * 各行的真实渲染，迭代速度大幅提升（真机验收仍是最终门禁，见 AGENTS.md）。
 *
 * 预览只覆盖**纯展示型**行（不依赖 ViewModel / 网络 / 环境）；交互与状态逻辑仍以单测和
 * 真机走查为准。fixture 与仪器化测试（ConversationTimeline 的渲染网）保持同源语义。
 */
@Composable
private fun PreviewSurface(content: @Composable () -> Unit) {
    // 深色为产品默认主题（设置项默认 dark），浅色由 ZCodeTheme(forceDark=false) 切换
    ZCodeTheme(forceDark = true) {
        Surface(Modifier.fillMaxSize()) { content() }
    }
}

// ---------------- fixture ----------------

private fun userRow(
    id: Int,
    text: String,
    attachments: List<RowAttachment> = emptyList(),
) = ConversationRow(rowId = id, kind = "userInput", text = text, attachments = attachments)

private fun assistantRow(
    id: Int,
    text: String,
    streaming: Boolean = false,
) = ConversationRow(rowId = id, kind = "assistantText", text = text, state = if (streaming) "streaming" else null)

private fun reasoningRow(
    id: Int,
    text: String,
) = ConversationRow(rowId = id, kind = "reasoning", text = text, durationMs = 4_200)

private fun toolRow(
    id: Int,
    tool: String,
    status: String,
    input: String,
    output: String? = null,
) = ConversationRow(
    rowId = id,
    kind = "toolCall",
    toolName = tool,
    toolCallId = "call_$id",
    status = status,
    inputText = input,
    outputText = output,
)

private fun turnHeaderRow(id: Int) = ConversationRow(rowId = id, kind = "turnHeader", origin = "mobile", state = "completed", activeMs = 12_000)

private fun timelineMarkerRow(id: Int) = ConversationRow(rowId = id, kind = "timelineMarker", summaryText = "会话已压缩 · 保留近期上下文")

private fun subagentRow(id: Int) = ConversationRow(rowId = id, kind = "subagent", origin = "explore", status = "completed", summaryText = "扫描仓库并汇总优化点")

/** 覆盖全部行类型的混合时间线（一次看全渲染形态）。 */
private val mixedTimeline: List<ConversationRow> =
    listOf(
        turnHeaderRow(1),
        userRow(2, "帮我看看这个仓库有哪些可以优化的地方"),
        reasoningRow(3, "先确认项目结构与依赖版本，再逐个模块检查是否有过时用法或可简化的实现。"),
        assistantRow(4, "我先通读仓库结构与构建配置，然后给出分优先级的建议。"),
        toolRow(5, "Grep", "completed", """{"pattern":"@Preview","path":"app-android"}""", "no matches"),
        toolRow(6, "Read", "running", """{"file_path":"app/build.gradle.kts"}"""),
        toolRow(7, "Edit", "error", """{"file_path":"app/build.gradle.kts","old_string":"a","new_string":"b"}""", "old_string not found"),
        assistantRow(8, "正在生成后续分析…", streaming = true),
        subagentRow(9),
        timelineMarkerRow(10),
    )

// ---------------- previews ----------------

@Preview(name = "Timeline / 全部行类型（深色）", showBackground = true, widthDp = 400, heightDp = 900)
@Composable
private fun PreviewTimelineMixed() {
    PreviewSurface {
        Column(
            modifier = Modifier.fillMaxSize().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            mixedTimeline.forEach { row ->
                RowItem(row = row, expanded = false, onToggleExpand = {})
            }
        }
    }
}

@Preview(name = "用户气泡 / 带附件", showBackground = true, widthDp = 400)
@Composable
private fun PreviewUserBubbleWithAttachment() {
    PreviewSurface {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RowItem(
                row =
                    userRow(
                        20,
                        "把这个 diff 看一下，重点是错误处理",
                        attachments =
                            listOf(
                                RowAttachment(ref = "a1", fileName = "patch.diff", mime = "text/plain", bytes = 20_480),
                            ),
                    ),
                expanded = false,
                onToggleExpand = {},
            )
        }
    }
}

@Preview(name = "折叠行 / 展开态（工具 + 思考）", showBackground = true, widthDp = 400, heightDp = 700)
@Composable
private fun PreviewCollapsibleExpanded() {
    PreviewSurface {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RowItem(
                row = toolRow(30, "Bash", "completed", """{"command":"gradle testDebugUnitTest"}""", "BUILD SUCCESSFUL"),
                expanded = true,
                onToggleExpand = {},
            )
            RowItem(
                row = reasoningRow(31, "这段思考在展开态下应显示完整正文，并带「复制」按钮与字符数。"),
                expanded = true,
                onToggleExpand = {},
            )
        }
    }
}

@Preview(name = "时间线 / 浅色主题", showBackground = true, widthDp = 400, heightDp = 700)
@Composable
private fun PreviewTimelineLight() {
    ZCodeTheme(forceDark = false) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    userRow(40, "浅色主题下的用户气泡与轨迹色对比度"),
                    assistantRow(41, "助手正文：**加粗**、`inline code` 与列表：\n\n- 第一项\n- 第二项"),
                    toolRow(42, "Grep", "completed", """{"pattern":"TODO"}""", "3 matches"),
                ).forEach { RowItem(row = it, expanded = false, onToggleExpand = {}) }
            }
        }
    }
}
