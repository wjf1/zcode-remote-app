package com.zcode.remote.repo

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import com.zcode.remote.relay.ConversationChannel
import com.zcode.remote.relay.UserFacingError
import com.zcode.remote.util.ZLog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch

/**
 * 附件上传（§11.7 结构性拆分：自 `AppViewModel` 原样搬出，只移动不改行为）。
 * P1-3 协议见 PROTOCOL.md §6.6；C-1 可取消/失败重试；A-1 会话归属校验。
 * 连接栈（conversation / subscribedSessionId）为运行期代理，经 getter 注入。
 *
 * @param flash 反馈横幅回调：(文案, 是否失败语义)。
 */
class AttachmentUploader(
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val context: Context,
    private val conversation: () -> ConversationChannel?,
    private val subscribedSessionId: () -> String?,
    private val flash: (String, Boolean) -> Unit,
) {
    /** 已上传完成、随下一条消息发出去的附件。 */
    val attachments = mutableStateListOf<ConversationChannel.AttachmentRef>()

    /** 上传中的附件（UI 显示进度；同一时刻只允许一个）。 */
    var attachUpload by mutableStateOf<AttachUpload?>(null)
        private set

    data class AttachUpload(val name: String, val uploaded: Long, val total: Long) {
        val percent: Int get() = if (total <= 0) 0 else ((uploaded * 100) / total).toInt()
    }

    /** 在途上传句柄（A-1）：切会话 / 清理附件时用于终止，防止跨会话回写。 */
    private var attachUploadHandle: ConversationChannel.UploadHandle? = null

    /**
     * C-1：上传失败态（可重试）。记录重试所需的全部信息——**特别是 uploadId**：
     * 重试必须复用同一 id 才能命中服务端幂等分支（`state=="committed"` 直接返回 ref，
     * 已上传分片不重传）；原实现 `ConversationChannel` 每次随机生成 id，幂等分支永不命中。
     */
    data class FailedUpload(
        val uri: android.net.Uri,
        val fileName: String,
        val mime: String,
        val size: Long,
        val uploadId: String,
        val message: String,
    )

    var attachFailed by mutableStateOf<FailedUpload?>(null)
        private set

    private fun newUploadId(): String = "upload-${java.util.UUID.randomUUID()}"

    /** C-1：取消在途上传（用户主动放弃，不记失败态）。 */
    fun cancelAttachmentUpload() {
        attachUploadHandle?.abort()
        attachUploadHandle = null
        attachUpload = null
    }

    /** C-1：失败后重试——复用原 uploadId（同一文件不重传已提交的分片）。 */
    fun retryAttachmentUpload() {
        val f = attachFailed ?: return
        attachFailed = null
        startUpload(f.uri, f.fileName, f.mime, f.size, f.uploadId)
    }

    /** C-1：放弃失败的上传（清掉失败态）。 */
    fun dismissAttachFailure() { attachFailed = null }

    /**
     * 附件上传成/败信号（B-3）：tick 每次自增，UI 侧以 `LaunchedEffect(tick)` 消费一次触觉。
     * 上传完成是异步的（由本类驱动），Compose 侧拿不到点击上下文，故需要一条信号桥。
     */
    data class AttachmentFeedback(val ok: Boolean, val tick: Long)

    var attachmentFeedback by mutableStateOf<AttachmentFeedback?>(null)
        private set
    private var attachmentFeedbackSeq = 0L

    private fun signalAttachmentFeedback(ok: Boolean) {
        attachmentFeedbackSeq += 1
        attachmentFeedback = AttachmentFeedback(ok, attachmentFeedbackSeq)
    }

    /**
     * 上传一个附件。走 conversation 通道的四步流程（begin/chunk/commit），
     * 成功后加入 [attachments]，UI 显示 chip，发送时随 sendPrompt 带走。
     * P2-3 流式改造：只持 uri，内容经 openStream 分片读（内存峰值一倍分片），
     * 不再整文件读进内存；选中后文件被移动/删除会在上传时报错提示。
     *
     * A-1：上传发起时把「发起会话」捕获进闭包，所有回调先校验会话归属——否则在途上传
     * 完成后会把文件塞进用户已经切过去的另一个会话的附件条（数据正确性 + 隐私风险）。
     *
     * C-1：失败进 [attachFailed] 失败态（UI 提供重试/取消）；重选同一文件复用 uploadId。
     */
    fun addAttachment(uri: android.net.Uri, fileName: String, mime: String, size: Long) {
        val prev = attachFailed
        val id = uploadIdForAttempt(
            prev?.fileName, prev?.size ?: -1L, prev?.uploadId, fileName, size, newUploadId())
        attachFailed = null
        startUpload(uri, fileName, mime, size, id)
    }

    private fun startUpload(uri: android.net.Uri, fileName: String, mime: String, size: Long, uploadId: String) {
        val conv = conversation() ?: run { flash("连接已断开，无法上传", true); return }
        if (attachUpload != null) { flash("还有附件在上传中", false); return }
        if (size > ConversationChannel.MAX_ATTACHMENT_BYTES) {
            flash("附件超过 20MiB 上限", false)
            return
        }
        val originSession = subscribedSessionId()
        attachUpload = AttachUpload(fileName, 0, size)
        attachUploadHandle = conv.uploadAttachment(uploadId, fileName, mime, size,
            openStream = {
                context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("无法打开所选文件（可能已被移动或删除）")
            },
            onProgress = { up, total ->
                // 切走会话后旧上传的进度不得再点亮新会话的进度条
                if (uploadBelongsTo(originSession, subscribedSessionId())) {
                    attachUpload = AttachUpload(fileName, up, total)
                }
            },
        ) { r ->
            scope.launch {
                if (!uploadBelongsTo(originSession, subscribedSessionId())) {
                    ZLog.i(TAG, "丢弃跨会话上传回调 origin=$originSession current=${subscribedSessionId()}")
                    return@launch
                }
                attachUpload = null
                r.fold(
                    onSuccess = {
                        attachments.add(it)
                        attachFailed = null
                        signalAttachmentFeedback(ok = true)
                        flash("已添加附件 ${it.fileName}", false)
                    },
                    onFailure = {
                        signalAttachmentFeedback(ok = false)
                        // C-1：记录失败态供「重试/取消」；重试复用同一 uploadId
                        attachFailed = FailedUpload(uri, fileName, mime, size, uploadId, it.message ?: "上传失败")
                        flash("附件上传失败：${UserFacingError.map(it.message)}", true)
                    },
                )
            }
        }
    }

    fun removeAttachment(ref: ConversationChannel.AttachmentRef) {
        attachments.remove(ref)
    }

    fun clearAttachments() {
        // A-1：切会话/清理时终止在途上传，避免其回调在别的会话里落地
        attachUploadHandle?.abort()
        attachUploadHandle = null
        attachments.clear()
        attachUpload = null
        attachFailed = null   // C-1：失败态同样与会话绑定
    }

    companion object {
        private const val TAG = "AttachmentUploader"

        /**
         * 上传回调的会话归属判定（A-1）。仅当发起上传时的会话仍是当前打开的会话时才允许
         * 回写状态；任一侧为空（未订阅 / 已断开）一律判「不属于」并丢弃回调。
         */
        internal fun uploadBelongsTo(originSession: String?, currentSession: String?): Boolean =
            originSession != null && originSession == currentSession

        /**
         * C-1：上传 id 的复用判定（纯函数，参数化以避免测试依赖 android.net.Uri）。
         * 名字与大小都相同视为同一附件 → 复用原 id 命中服务端幂等（已 committed 直接返回 ref）；
         * 换了文件必须换新 id，否则服务端会把旧文件内容当作新文件提交（数据正确性）。
         * previous* 传 null 表示没有可复用的失败态。
         */
        internal fun uploadIdForAttempt(
            previousFileName: String?,
            previousSize: Long,
            previousUploadId: String?,
            fileName: String,
            size: Long,
            freshId: String,
        ): String =
            if (previousFileName != null && previousUploadId != null &&
                previousFileName == fileName && previousSize == size
            ) previousUploadId else freshId
    }
}
