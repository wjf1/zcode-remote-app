package com.zcode.remote.repo

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zcode.remote.R
import com.zcode.remote.relay.ConversationChannel
import com.zcode.remote.relay.UserFacingError
import com.zcode.remote.util.UiText
import com.zcode.remote.util.UiText.Companion.res
import com.zcode.remote.util.ZLog
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
    private val flash: (UiText, Boolean) -> Unit,
) {
    /** 已上传完成、随下一条消息发出去的附件。 */
    val attachments = mutableStateListOf<ConversationChannel.AttachmentRef>()

    /** 上传中的附件（UI 显示进度；同一时刻只允许一个）。 */
    var attachUpload by mutableStateOf<AttachUpload?>(null)
        private set

    data class AttachUpload(
        val name: String,
        val uploaded: Long,
        val total: Long,
    ) {
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
    fun dismissAttachFailure() {
        attachFailed = null
    }

    /**
     * 附件上传成/败信号（B-3）：tick 每次自增，UI 侧以 `LaunchedEffect(tick)` 消费一次触觉。
     * 上传完成是异步的（由本类驱动），Compose 侧拿不到点击上下文，故需要一条信号桥。
     */
    data class AttachmentFeedback(
        val ok: Boolean,
        val tick: Long,
    )

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
     * **走查 #2**：内容经**按偏移开流**分片读（内存峰值一倍分片 + 64KiB 哈希缓冲），
     * 续传时 `FileChannel.position()` 直接定位（不再重读/跳过前 N 片）；选中后文件被移动/删除
     * 会在上传时报错提示。编排见 `AttachmentUploadPipeline`（顺序 suspend，可 JVM 单测）。
     *
     * A-1：上传发起时把「发起会话」捕获进闭包，所有回调先校验会话归属——否则在途上传
     * 完成后会把文件塞进用户已经切过去的另一个会话的附件条（数据正确性 + 隐私风险）。
     *
     * C-1：失败进 [attachFailed] 失败态（UI 提供重试/取消）；重选同一文件复用 uploadId。
     */
    fun addAttachment(
        uri: android.net.Uri,
        fileName: String,
        mime: String,
        size: Long,
    ) {
        val prev = attachFailed
        val id =
            uploadIdForAttempt(
                prev?.fileName,
                prev?.size ?: -1L,
                prev?.uploadId,
                fileName,
                size,
                newUploadId(),
            )
        attachFailed = null
        startUpload(uri, fileName, mime, size, id)
    }

    private fun startUpload(
        uri: android.net.Uri,
        fileName: String,
        mime: String,
        size: Long,
        uploadId: String,
    ) {
        val conv =
            conversation() ?: run {
                flash(res(R.string.flash_disconnected_cannot_upload), true)
                return
            }
        if (attachUpload != null) {
            flash(res(R.string.flash_attachment_in_progress), false)
            return
        }
        if (size > ConversationChannel.MAX_ATTACHMENT_BYTES) {
            flash(res(R.string.flash_attachment_limit), false)
            return
        }
        val originSession = subscribedSessionId()
        attachUpload = AttachUpload(fileName, 0, size)
        attachUploadHandle =
            conv.uploadAttachment(
                uploadId,
                fileName,
                mime,
                size,
                scope = scope,
                // 走查 #2：按绝对偏移开流。SAF 走 ParcelFileDescriptor + FileChannel.position
                // 做 **O(1) 定位**（续传不再重读/跳过前 N 片）；拿不到 fd 时才回退
                // openInputStream + skip（旧行为，仍正确但退化为 O(offset)）。
                openAt = { offset -> openAttachmentStream(context, uri, offset) },
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
                            flash(res(R.string.flash_attachment_added, it.fileName), false)
                        },
                        onFailure = {
                            signalAttachmentFeedback(ok = false)
                            // C-1：记录失败态供「重试/取消」；重试复用同一 uploadId
                            attachFailed = FailedUpload(uri, fileName, mime, size, uploadId, it.message ?: context.getString(R.string.error_upload_failed))
                            flash(res(R.string.flash_upload_failed, UserFacingError.map(it.message)), true)
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
        attachFailed = null // C-1：失败态同样与会话绑定
    }

    companion object {
        private const val TAG = "AttachmentUploader"

        /**
         * 按绝对偏移打开附件流（走查 #2 的 seekable 源）。
         *
         * 首选 `openFileDescriptor` + `FileChannel.position(offset)`：**O(1) 定位**，
         * 续传时不再重读/跳过前 N 片（旧实现每次 `openStream()` + `skip()` 是 O(offset) IO，
         * 对 20MiB 附件续传代价显著）。
         *
         * 回退路径：部分 provider 不提供可 seek 的 fd（`openFileDescriptor` 返回 null 或
         * `FileChannel.position` 抛异常），此时退回 `openInputStream` + `skip`——语义仍正确
         * （只是定位退化为 O(offset)），保证「拿不到 fd 也能传完」。
         */
        internal fun openAttachmentStream(
            context: Context,
            uri: android.net.Uri,
            offset: Long,
        ): java.io.InputStream {
            if (offset > 0L) {
                val seekable = seekableStream(context, uri, offset)
                if (seekable != null) return seekable
            }
            val input =
                context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException(context.getString(R.string.error_open_file_failed))
            var remaining = offset
            while (remaining > 0) {
                val n = input.skip(remaining)
                if (n <= 0) break
                remaining -= n
            }
            return input
        }

        /**
         * 尝试以 O(1) 定位打开；不可 seek 时返回 null（由调用方回退到 openInputStream + skip）。
         *
         * 用 [android.os.ParcelFileDescriptor.AutoCloseInputStream]：它既是 `FileInputStream`
         * （暴露 `channel` 可 `position()`），又负责在 `close()` 时关闭 PFD——不会泄漏描述符。
         * 对管道等不可 seek 的 fd，`channel.position()` 会抛异常 → 由 runCatching 收敛为 null。
         */
        private fun seekableStream(
            context: Context,
            uri: android.net.Uri,
            offset: Long,
        ): java.io.InputStream? =
            runCatching {
                val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
                android.os.ParcelFileDescriptor
                    .AutoCloseInputStream(pfd)
                    .also { it.channel.position(offset) }
            }.getOrNull()

        /**
         * 上传回调的会话归属判定（A-1）。仅当发起上传时的会话仍是当前打开的会话时才允许
         * 回写状态；任一侧为空（未订阅 / 已断开）一律判「不属于」并丢弃回调。
         */
        internal fun uploadBelongsTo(
            originSession: String?,
            currentSession: String?,
        ): Boolean = originSession != null && originSession == currentSession

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
            if (previousFileName != null &&
                previousUploadId != null &&
                previousFileName == fileName &&
                previousSize == size
            ) {
                previousUploadId
            } else {
                freshId
            }
    }
}
