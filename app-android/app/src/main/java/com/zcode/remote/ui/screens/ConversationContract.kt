package com.zcode.remote.ui.screens

import android.net.Uri
import com.zcode.remote.AppViewModel
import com.zcode.remote.repo.AttachmentUploader
import com.zcode.remote.relay.ApprovalOption
import com.zcode.remote.relay.ConversationChannel
import com.zcode.remote.relay.ConversationRow
import com.zcode.remote.relay.PendingApproval
import com.zcode.remote.relay.PendingElicitation
import com.zcode.remote.relay.WorkspaceConfigChannel

/**
 * 会话页状态/动作契约（§11.7 结构性拆分）：以 [ConversationUiState] + [ConversationAction]
 * 替代原 `ConversationScreen` 的 46 个散落参数与 callback，Route 层（Activity）单向装配。
 * 字段与原参数一一对应，仅移动不改行为。
 */

/** 会话页渲染所需的全部只读状态（自 AppViewModel 派生，跨会话过滤后下发）。 */
data class ConversationUiState(
    val title: String,
    val status: ConversationChannel.Status,
    val meta: ConversationChannel.ConversationMeta,
    val rows: List<ConversationRow>,
    /** C-6：已发出但服务端尚未回显的消息（本地 pending 气泡，渲染在消息流末尾）。 */
    val pendingUserMessages: List<AppViewModel.PendingUserMessage> = emptyList(),
    val approvals: List<PendingApproval> = emptyList(),
    val elicitations: List<PendingElicitation> = emptyList(),
    val earlier: ConversationChannel.EarlierState = ConversationChannel.EarlierState(),
    /** C3：会话级状态（上下文用量 / 目标 / 待办 / 后台任务 / 子智能体 / 排队输入）。 */
    val sessionState: com.zcode.remote.relay.ConversationFrames.SessionState =
        com.zcode.remote.relay.ConversationFrames.SessionState(),
    val prompt: String = "",
    val sending: Boolean = false,
    val canStop: Boolean = false,
    val stopState: String? = null,
    val commandFeedback: String? = null,
    /** C-11：反馈横幅是否为失败语义（决定配色，与 VM 的 FlashKind 同源）。 */
    val feedbackIsFailure: Boolean = false,
    val attachments: List<ConversationChannel.AttachmentRef> = emptyList(),
    val attachUploadName: String? = null,
    val attachUploadPercent: Int = 0,
    /** C-1：上传失败态（UI 显示重试/取消）；重试复用原 uploadId，已传分片不重来。 */
    val attachFailed: AttachmentUploader.FailedUpload? = null,
    val availableModels: List<WorkspaceConfigChannel.ModelOption> = emptyList(),
    /** 各模型合法思考档位：key = "providerId/modelId"（会话内切模型时用于二级档位菜单）。 */
    val modelReasoningLevels: Map<String, List<String>> = emptyMap(),
    /** 当前会话执行模式（P0-B：订阅 ack / 快照；用户切换后的乐观值由上层合并后传入）。 */
    val currentSessionMode: String? = null,
    /** 剧本 B「最近文件」：本会话文件预览状态与操作（AppViewModel 持有状态）。 */
    val filePreview: AppViewModel.FilePreview? = null,
    /** B-1：权威快照是否已落地（含 seq，用于「缓存行数 == 快照行数」时仍能定位最新行）。 */
    val snapshotAligned: ConversationChannel.SnapshotAligned? = null,
    /** B-3：附件上传成/败信号（tick 变化触发一次触觉）。 */
    val attachmentFeedback: AttachmentUploader.AttachmentFeedback? = null,
)

/** 会话页全部用户意图（原 21 个 callback + 返回导航）。 */
data class ConversationAction(
    val onBack: () -> Unit,
    val onCancelUpload: () -> Unit = {},
    val onRetryUpload: () -> Unit = {},
    val onDismissUploadFailure: () -> Unit = {},
    val onResolve: (PendingApproval, ApprovalOption) -> Unit = { _, _ -> },
    val onElicitationAccept: (PendingElicitation, Map<Int, List<String>>) -> Unit = { _, _ -> },
    val onElicitationDecline: (PendingElicitation) -> Unit = {},
    val onElicitationFreeText: (PendingElicitation, String) -> Unit = { _, _ -> },
    val onLoadEarlier: () -> Unit = {},
    val onPromptChange: (String) -> Unit = {},
    val onSend: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onAttachmentPicked: (uri: Uri, name: String, mime: String, size: Long) -> Unit = { _, _, _, _ -> },
    val onRemoveAttachment: (ConversationChannel.AttachmentRef) -> Unit = {},
    val onSwitchModel: (WorkspaceConfigChannel.ModelOption) -> Unit = {},
    /** 进入会话页时触发一次模型目录刷新（PC 端配置变更准实时同步）。 */
    val onLoadModels: () -> Unit = {},
    /** 切换执行模式（协议语义：仅对下一轮 agent turn 生效）。 */
    val onSwitchMode: (String) -> Unit = {},
    val onPreviewFile: (String) -> Unit = {},
    val onDismissFilePreview: () -> Unit = {},
    /** A-2：握手失败后用户手动重试订阅。 */
    val onRetrySubscribe: () -> Unit = {},
)
