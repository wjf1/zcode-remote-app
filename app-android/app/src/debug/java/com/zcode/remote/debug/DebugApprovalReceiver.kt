package com.zcode.remote.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zcode.remote.notify.ApprovalNotifier
import com.zcode.remote.notify.ElicitationNotifier
import com.zcode.remote.relay.ApprovalOption
import com.zcode.remote.relay.PendingApproval
import com.zcode.remote.relay.PendingElicitation

/**
 * 仅 debug 构建存在：注入一条假的待审批或表单交互，用来在没有真实请求时验证
 * 通知渲染、按钮回调、RemoteInput 内联回复以及"连接不在时不能假装批准"这条路径。
 *
 *   adb shell am broadcast -n com.zcode.remote/.debug.DebugApprovalReceiver \
 *        -a com.zcode.remote.action.DEBUG_APPROVAL
 *   adb shell am broadcast -n com.zcode.remote/.debug.DebugApprovalReceiver \
 *        -a com.zcode.remote.action.DEBUG_ELICITATION
 *   adb shell am broadcast -n com.zcode.remote/.debug.DebugApprovalReceiver \
 *        -a com.zcode.remote.action.DEBUG_APPROVAL_CLEAR
 */
class DebugApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_CLEAR -> {
                ApprovalNotifier.clearAll(context)
                ElicitationNotifier.clearAll(context)
            }
            ACTION_ELICITATION -> ElicitationNotifier.sync(context, fakeElicitation())
            else -> ApprovalNotifier.sync(context, fake())
        }
    }

    private fun fakeElicitation(): List<PendingElicitation> = listOf(
        PendingElicitation(
            interactionId = "el_debug_001",
            toolName = "AskUserQuestion",
            prompt = "测试提问：请补充当前任务的验收标准或参数",
            questions = emptyList(),
            freeText = true,
            plan = null,
            autoResolveAt = null,
            sessionId = "sess_debug",
        )
    )

    private fun fake(): List<PendingApproval> = listOf(
        PendingApproval(
            interactionId = "intc_debug_0001",
            toolCallId = "call_debug_1",
            toolName = "Bash",
            summary = "调试用：模拟一次需要审批的命令执行",
            detail = "rm -rf F:/tmp/build-cache",
            options = listOf(
                ApprovalOption("opt_debug_allow", "Allow once", "allowOnce", "allow"),
                ApprovalOption("opt_debug_always", "Always allow", "allowAlways", "allow"),
                ApprovalOption("opt_debug_deny", "Deny", "deny", "deny"),
            ),
            anchorRowId = null,
            autoResolveAt = System.currentTimeMillis() + 5 * 60_000,
            sessionId = "sess_debug",
        ),
        // 第二条验并行审批：通知栏应出现两条，各自带自己的按钮
        PendingApproval(
            interactionId = "intc_debug_0002",
            toolCallId = "call_debug_2",
            toolName = "WebFetch",
            summary = "调试用：第二条并行审批",
            detail = "https://example.com/api",
            options = listOf(
                ApprovalOption("opt_debug_allow2", "Allow", "allowOnce", "allow"),
                ApprovalOption("opt_debug_deny2", "Reject once", "deny", "deny"),
            ),
            anchorRowId = null,
            autoResolveAt = null,
            sessionId = "sess_debug",
        ),
    )

    companion object {
        const val ACTION_INJECT = "com.zcode.remote.action.DEBUG_APPROVAL"
        const val ACTION_ELICITATION = "com.zcode.remote.action.DEBUG_ELICITATION"
        const val ACTION_CLEAR = "com.zcode.remote.action.DEBUG_APPROVAL_CLEAR"
    }
}
