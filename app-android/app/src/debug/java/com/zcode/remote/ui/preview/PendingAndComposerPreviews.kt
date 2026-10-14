package com.zcode.remote.ui.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.zcode.remote.relay.ApprovalOption
import com.zcode.remote.relay.PendingApproval
import com.zcode.remote.ui.components.AutoResolveCountdown
import com.zcode.remote.ui.screens.ApprovalCard
import com.zcode.remote.ui.screens.InputBar
import com.zcode.remote.ui.theme.ZCodeTheme

/**
 * 审批卡 / 输入栏 / 倒计时的布局预览（仅 debug 变体渲染）。
 *
 * 覆盖会话页底部这几处高频交互 UI 的静态形态；点击后的真实应答链路由单测
 * （`UserFacingErrorTest` 等）与真机走查覆盖，预览只解决「样式迭代要靠装机」的效率问题。
 */
@Composable
private fun LightSurface(content: @Composable () -> Unit) {
    ZCodeTheme(forceDark = true) {
        Surface(Modifier.fillMaxSize()) { content() }
    }
}

private fun sampleApproval(
    withDeadline: Boolean,
    tool: String = "Bash",
): PendingApproval =
    PendingApproval(
        interactionId = "perm_demo_1",
        toolCallId = "call_demo",
        toolName = tool,
        summary = "执行命令：gradle testDebugUnitTest",
        detail = "cd app-android && gradle testDebugUnitTest --console=plain",
        options =
            listOf(
                ApprovalOption("opt_once", "允许一次", "allowOnce", "allow"),
                ApprovalOption("opt_always", "总是允许", "allowAlways", "allow"),
                ApprovalOption("opt_deny", "拒绝", "deny", "deny"),
            ),
        anchorRowId = 6,
        autoResolveAt = if (withDeadline) System.currentTimeMillis() + 45_000 else null,
        sessionId = "sess_demo",
    )

@Preview(name = "审批卡 / 带自动决议倒计时", showBackground = true, widthDp = 400)
@Composable
private fun PreviewApprovalCardWithCountdown() {
    LightSurface {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ApprovalCard(a = sampleApproval(withDeadline = true), onResolve = { _, _ -> })
        }
    }
}

@Preview(name = "审批卡 / 无倒计时（写文件类）", showBackground = true, widthDp = 400)
@Composable
private fun PreviewApprovalCardWriteFile() {
    LightSurface {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ApprovalCard(a = sampleApproval(withDeadline = false, tool = "Write"), onResolve = { _, _ -> })
        }
    }
}

@Preview(name = "输入栏 / 空态（发送禁用）", showBackground = true, widthDp = 400)
@Composable
private fun PreviewInputBarEmpty() {
    LightSurface {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            InputBar(
                prompt = "",
                sending = false,
                canStop = false,
                stopping = false,
                canSend = false,
                onPromptChange = {},
                onSend = {},
                onStop = {},
                onPick = {},
            )
        }
    }
}

@Preview(name = "输入栏 / 运行中（停止按钮）", showBackground = true, widthDp = 400)
@Composable
private fun PreviewInputBarRunning() {
    LightSurface {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            InputBar(
                prompt = "",
                sending = false,
                canStop = true,
                stopping = false,
                canSend = false,
                onPromptChange = {},
                onSend = {},
                onStop = {},
                onPick = {},
            )
        }
    }
}

@Preview(name = "输入栏 / 有草稿（发送可用）", showBackground = true, widthDp = 400)
@Composable
private fun PreviewInputBarWithDraft() {
    LightSurface {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            InputBar(
                prompt = "把这个会话的错误原因整理成一份排查清单",
                sending = false,
                canStop = false,
                stopping = false,
                canSend = true,
                onPromptChange = {},
                onSend = {},
                onStop = {},
                onPick = {},
            )
        }
    }
}

@Preview(name = "自动决议倒计时 / 三种剩余时长", showBackground = true, widthDp = 400)
@Composable
private fun PreviewCountdown() {
    LightSurface {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AutoResolveCountdown(deadlineAt = System.currentTimeMillis() + 90_000)
            AutoResolveCountdown(deadlineAt = System.currentTimeMillis() + 5_000)
            AutoResolveCountdown(deadlineAt = System.currentTimeMillis() - 1_000)
        }
    }
}
