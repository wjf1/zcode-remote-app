package com.zcode.remote.relay

import com.zcode.remote.util.LogRedactor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream
import java.security.MessageDigest

/**
 * 上传过程的日志出口（注入式）。
 *
 * 为什么注入而不直接调 `ZLog`：本类要在**纯 JVM 单测**里跑，而 `ZLog` 会落到
 * `android.util.Log`（无 Robolectric 时抛 "not mocked"）。项目既有约定是
 * **被 JVM 单测覆盖的纯逻辑类零 `ZLog` 调用**（见 `RpcFrameAssembler` / `ProtocolLimits`），
 * 这里沿用同一手法：默认实现不输出，生产侧（`ConversationChannel`）注入 `ZLog`。
 */
internal fun interface UploadLogger {
    fun log(
        message: String,
        warn: Boolean,
    )

    companion object {
        /** 默认静默（JVM 单测与不关心日志的调用方用）。 */
        val NOOP = UploadLogger { _, _ -> }
    }
}

/**
 * 附件内容源（走查 #2）：按**绝对偏移**打开流。
 *
 * 旧实现每次续传都 `openStream()` 再 `skip()` 跳过前 N 片——重试时是 O(n) IO，
 * 且对不可 seek 的流（如某些 SAF provider）`skip()` 会退化成逐字节丢弃。
 * 改为按偏移打开后，SAF 走 `ParcelFileDescriptor` + `FileChannel.position()`（O(1) 定位），
 * 顺序上传路径则从偏移 0 开流、不做多余定位。
 *
 * 实现方须保证每次调用返回**独立**的流（可重复调用、各自关闭）。
 */
internal fun interface UploadSource {
    fun openAt(offset: Long): InputStream
}

/**
 * 上传期间的 RPC 出口（注入以便 JVM 单测用假实现钉死编排行为）。
 *
 * 只暴露「发一条命令并拿应答」这一能力，不泄漏 `RpcChannel`/`Handler`/Looper，
 * 因而可在纯 JVM 下测试。
 */
internal fun interface UploadRpc {
    suspend fun call(
        method: String,
        args: Map<String, Any>,
    ): RpcChannel.RpcReply
}

/**
 * 附件上传的**顺序 suspend** 流水线（走查 #2：递归回调 → suspend 顺序上传 + 可定位流）。
 *
 * 协议四步（PROTOCOL.md §6.6）：流式算 sha256 → `attachmentBeginV4` → 逐片 `attachmentChunkV4` → `attachmentCommitV4`。
 *
 * 与旧实现的差异（**对外行为等价**：文案、幂等短路、进度口径、失败语义全部保留）：
 * 1. 递归 `next()` 回调金字塔 → `while` 顺序循环（分片仍一进一出、顺序不变，协议本就串行）；
 * 2. 续传用 `openAt(N * chunkBytes)` **直接定位**，不再 `skip()` 前 N 片（消除重试的 O(n) IO）；
 * 3. 阻塞文件读全部下放 [ioDispatcher]（旧实现整文件哈希 + 逐片读都在**主线程**，
 *    20MiB 附件会明显卡顿）；RPC 与进度回调仍在调用方上下文（Main），UI 状态写入线程不变；
 * 4. 取消 = 协程取消：抛 [CancellationException] 前先以 [NonCancellable] 补发一次
 *    `attachmentAbortV4`，语义等同旧的 `UploadHandle.abort()`（服务端可回收在途分片）。
 *
 * 抽成独立 object + 注入 `source`/`rpc`/`ioDispatcher`，是为了让整条编排**可在纯 JVM 单测**里
 * 用内存假件跑通（旧实现在 `ConversationChannel` 内、依赖 Android Looper，无法单测）——
 * 这正是本轮把「上传正确性」从「只能真机人肉验」变成「可回归」的关键。
 */
internal object AttachmentUploadPipeline {
    /** 哈希缓冲：64KiB（与旧实现一致）。 */
    private const val HASH_BUFFER_BYTES = 64 * 1024

    const val METHOD_BEGIN = "attachmentBeginV4"
    const val METHOD_CHUNK = "attachmentChunkV4"
    const val METHOD_COMMIT = "attachmentCommitV4"
    const val METHOD_ABORT = "attachmentAbortV4"

    /**
     * 日志出口。默认静默（JVM 单测友好）；生产由 `ConversationChannel` 在类初始化时
     * 注入 `ZLog` 实现（见该文件 `AttachmentUploadPipeline.logger = ...`）。
     * 用可写单例而非构造参数，是为避免把 logger 顺着 5 个私有函数层层透传。
     */
    var logger: UploadLogger = UploadLogger.NOOP

    suspend fun run(
        source: UploadSource,
        rpc: UploadRpc,
        base: Map<String, Any>,
        fileName: String,
        mime: String,
        totalBytes: Long,
        totalChunks: Int,
        chunkBytes: Int,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        onProgress: ((uploaded: Long, total: Long) -> Unit)? = null,
    ): Result<ConversationChannel.AttachmentRef> =
        try {
            runInner(source, rpc, base, fileName, mime, totalBytes, totalChunks, chunkBytes, ioDispatcher, onProgress)
        } catch (ce: CancellationException) {
            // 用户取消 / 切会话：先补发 abort（服务端回收在途分片），再把取消原样抛出，
            // 让结构化并发语义保持（不吞 CancellationException）。
            abort(rpc, base)
            throw ce
        }

    /**
     * 第一遍：流式算整文件 sha256（`begin` 的 checksum 必须先于 `begin`；此步在 IO 线程）。
     *
     * 捕获面刻意放宽到 [Exception]：`openAt` 的失败形态不止 IO——SAF 可能抛
     * `IllegalStateException`（provider 打不开）、`SecurityException`（权限被撤）等，
     * 而旧实现用 `runCatching` 也是全捕获。这里的语义是「读取失败 → 以可读失败收场，
     * **绝不崩溃**」，故显式 `@Suppress` 并保留取消转发（[CancellationException] 先被
     * 单独捕获并原样抛出，不会被下面这层吞掉）。
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun sha256Of(
        source: UploadSource,
        ioDispatcher: CoroutineDispatcher,
    ): HashResult =
        try {
            HashResult.Ok(
                withContext(ioDispatcher) {
                    val md = MessageDigest.getInstance("SHA-256")
                    source.openAt(0L).use { input ->
                        val buf = ByteArray(HASH_BUFFER_BYTES)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            md.update(buf, 0, n)
                        }
                    }
                    md.digest().joinToString("") { "%02x".format(it) }
                },
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            HashResult.Failed(e.message ?: "unknown")
        }

    private sealed interface HashResult {
        data class Ok(
            val hex: String,
        ) : HashResult

        data class Failed(
            val message: String,
        ) : HashResult
    }

    /**
     * `begin` 的应答处理结果：要么已给出终局 [Result]，要么带着服务端进度进入分片阶段。
     *
     * [Done.needsAbort] 区分两类失败（与旧实现严格一致）：
     * - `begin` 被服务端拒（`RpcReply.Err`）→ **不发 abort**（服务端根本没建会话）；
     * - 服务端返回非法进度 → 服务端已建会话，需 abort 回收。
     * 幂等短路成功（`state=="committed"`）自然 `needsAbort=false`。
     */
    private sealed interface BeginOutcome {
        data class Done(
            val result: Result<ConversationChannel.AttachmentRef>,
            val needsAbort: Boolean = false,
        ) : BeginOutcome

        data class SendChunks(
            val next: Int,
        ) : BeginOutcome
    }

    private fun interpretBeginReply(
        reply: RpcChannel.RpcReply,
        fileName: String,
        mime: String,
        totalBytes: Long,
        totalChunks: Int,
    ): BeginOutcome =
        when (reply) {
            is RpcChannel.RpcReply.Err ->
                BeginOutcome.Done(Result.failure(IllegalStateException("附件上传初始化失败：${reply.message}")))
            is RpcChannel.RpcReply.Ok -> {
                val o = reply.data.asObj()
                val ref = o?.get("ref").asStr()
                // 幂等：服务端对该 uploadId 已有 committed 内容（重试场景）→ 直接复用 ref
                if (o?.get("state").asStr() == "committed" && !ref.isNullOrBlank()) {
                    BeginOutcome.Done(Result.success(ConversationChannel.AttachmentRef(ref, fileName, mime, totalBytes)))
                } else {
                    val next = o?.get("nextChunkIndex").asInt() ?: 0
                    if (next > totalChunks) {
                        BeginOutcome.Done(Result.failure(IllegalStateException("服务端返回了非法进度 $next")), needsAbort = true)
                    } else {
                        BeginOutcome.SendChunks(next)
                    }
                }
            }
        }

    @Suppress("LongParameterList")
    private suspend fun runInner(
        source: UploadSource,
        rpc: UploadRpc,
        base: Map<String, Any>,
        fileName: String,
        mime: String,
        totalBytes: Long,
        totalChunks: Int,
        chunkBytes: Int,
        ioDispatcher: CoroutineDispatcher,
        onProgress: ((uploaded: Long, total: Long) -> Unit)?,
    ): Result<ConversationChannel.AttachmentRef> {
        val ctx = currentCoroutineContext()
        ctx.ensureActive()

        val digest =
            when (val hash = sha256Of(source, ioDispatcher)) {
                is HashResult.Ok -> hash.hex
                is HashResult.Failed -> {
                    // 与旧实现一致：读取失败不回写 abort（此时服务端尚未见到任何分片）
                    logger.log("读取附件失败：${hash.message}", true)
                    return Result.failure(IllegalStateException("读取附件失败：${hash.message}"))
                }
            }
        ctx.ensureActive()

        // 四步之 ②：begin
        val beginArgs =
            HashMap(base).apply {
                put("fileName", fileName)
                put("mime", mime)
                put("totalBytes", totalBytes)
                put("totalChunks", totalChunks)
                put("checksum", "sha256:$digest")
            }
        logger.log("attachmentBegin file=${LogRedactor.pathLabel(fileName)} mime=$mime bytes=$totalBytes chunks=$totalChunks", false)
        when (val outcome = interpretBeginReply(rpc.call(METHOD_BEGIN, beginArgs), fileName, mime, totalBytes, totalChunks)) {
            is BeginOutcome.Done -> {
                // 仅「服务端已建会话但进度非法」需要 abort；begin 被拒 / 幂等短路成功都不发。
                if (outcome.needsAbort) {
                    val message = outcome.result.exceptionOrNull()?.message ?: "附件上传初始化失败"
                    return fail(rpc, base, message)
                }
                return outcome.result
            }
            is BeginOutcome.SendChunks -> {
                ctx.ensureActive()

                // 四步之 ③：顺序上传分片（一次开流，按 next 偏移直接定位）
                val chunks =
                    uploadChunks(
                        source = source,
                        rpc = rpc,
                        base = base,
                        startChunk = outcome.next,
                        totalChunks = totalChunks,
                        chunkBytes = chunkBytes,
                        totalBytes = totalBytes,
                        ioDispatcher = ioDispatcher,
                        onProgress = onProgress,
                        ctx = ctx,
                    )
                if (chunks != null) return chunks // 已是失败的 Result

                // 四步之 ④：commit
                ctx.ensureActive()
                return when (val commit = rpc.call(METHOD_COMMIT, HashMap(base))) {
                    is RpcChannel.RpcReply.Err -> fail(rpc, base, "提交附件失败：${commit.message}")
                    is RpcChannel.RpcReply.Ok -> {
                        val committed =
                            commit.data
                                .asObj()
                                ?.get("ref")
                                .asStr()
                        if (committed.isNullOrBlank()) {
                            fail(rpc, base, "提交附件未返回 ref：${commit.data}")
                        } else {
                            logger.log("附件已提交 ref=$committed file=${LogRedactor.pathLabel(fileName)} bytes=$totalBytes", false)
                            Result.success(ConversationChannel.AttachmentRef(committed, fileName, mime, totalBytes))
                        }
                    }
                }
            }
        }
    }

    /** 返回 null = 全部分片成功（继续 commit）；非 null = 已以失败收场的 [Result]。 */
    @Suppress("LongParameterList", "TooGenericExceptionCaught")
    private suspend fun uploadChunks(
        source: UploadSource,
        rpc: UploadRpc,
        base: Map<String, Any>,
        startChunk: Int,
        totalChunks: Int,
        chunkBytes: Int,
        totalBytes: Long,
        ioDispatcher: CoroutineDispatcher,
        onProgress: ((uploaded: Long, total: Long) -> Unit)?,
        ctx: kotlin.coroutines.CoroutineContext,
    ): Result<ConversationChannel.AttachmentRef>? {
        if (startChunk >= totalChunks) return null
        val input =
            try {
                withContext(ioDispatcher) { source.openAt(startChunk.toLong() * chunkBytes) }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                // 同 sha256Of：开流失败形态不止 IO（SAF 可能抛 ISE/SecurityException），
                // 目标是以可读失败收场而非崩溃；取消已在上面单独转发。
                return fail(rpc, base, "读取附件失败：${e.message}")
            }
        try {
            var index = startChunk
            val buf = ByteArray(chunkBytes)
            while (index < totalChunks) {
                ctx.ensureActive()
                val step = sendOneChunk(input, buf, index, rpc, base, chunkBytes, totalBytes, ioDispatcher, onProgress)
                when (step) {
                    is ChunkStep.Sent -> index += 1
                    is ChunkStep.Failed -> return step.result
                }
            }
        } finally {
            withContext(NonCancellable + ioDispatcher) { runCatching { input.close() } }
        }
        return null
    }

    private sealed interface ChunkStep {
        data object Sent : ChunkStep

        data class Failed(
            val result: Result<ConversationChannel.AttachmentRef>,
        ) : ChunkStep
    }

    /** 读一片 → 上传 → 校验 ack；返回「已成功推进」或「已收场的失败」。 */
    @Suppress("LongParameterList")
    private suspend fun sendOneChunk(
        input: InputStream,
        buf: ByteArray,
        index: Int,
        rpc: UploadRpc,
        base: Map<String, Any>,
        chunkBytes: Int,
        totalBytes: Long,
        ioDispatcher: CoroutineDispatcher,
        onProgress: ((uploaded: Long, total: Long) -> Unit)?,
    ): ChunkStep {
        val read = withContext(ioDispatcher) { input.readChunk(buf, chunkBytes) }
        when (read) {
            is ChunkRead.Short -> return ChunkStep.Failed(fail(rpc, base, "附件内容比声明大小短"))
            is ChunkRead.Error -> return ChunkStep.Failed(fail(rpc, base, "读取附件分片失败：${read.cause.message}"))
            is ChunkRead.Data -> Unit
        }
        val bytes = (read as ChunkRead.Data).bytes
        val args =
            HashMap(base).apply {
                put("chunkIndex", index)
                // 用 java.util.Base64（无换行，与 android.util.Base64.NO_WRAP 等价；minSdk 31 可用）。
                // 选它是因为本类要在**纯 JVM 单测**里跑，android.util.Base64 无 Robolectric 会抛未实现。
                put(
                    "dataBase64",
                    java.util.Base64
                        .getEncoder()
                        .encodeToString(bytes),
                )
            }
        return when (val reply = rpc.call(METHOD_CHUNK, args)) {
            is RpcChannel.RpcReply.Err -> ChunkStep.Failed(fail(rpc, base, "分片 $index 上传失败：${reply.message}"))
            is RpcChannel.RpcReply.Ok -> {
                val ack =
                    reply.data
                        .asObj()
                        ?.get("nextChunkIndex")
                        .asInt()
                if (ack != index + 1) {
                    ChunkStep.Failed(fail(rpc, base, "服务端分片进度异常（期望 ${index + 1}，收到 $ack）"))
                } else {
                    onProgress?.invoke(minOf((index + 1).toLong() * chunkBytes, totalBytes), totalBytes)
                    ChunkStep.Sent
                }
            }
        }
    }

    /** 发 abort（幂等、失败不抛）+ 以固定文案收场。对应旧实现的 `fail()`。 */
    private suspend fun fail(
        rpc: UploadRpc,
        base: Map<String, Any>,
        message: String,
    ): Result<ConversationChannel.AttachmentRef> {
        logger.log("uploadAttachment 失败：$message", true)
        abort(rpc, base)
        return Result.failure(IllegalStateException(message))
    }

    /** 发一次 `attachmentAbortV4`；取消场景下也要发得出去，故走 [NonCancellable]。 */
    private suspend fun abort(
        rpc: UploadRpc,
        base: Map<String, Any>,
    ) {
        withContext(NonCancellable) { runCatching { rpc.call(METHOD_ABORT, base) } }
    }

    /** 读满一片；读异常/短读以密封结果回报（避免在 IO 边界上抛业务异常）。 */
    private fun InputStream.readChunk(
        buf: ByteArray,
        chunkBytes: Int,
    ): ChunkRead {
        var filled = 0
        while (filled < chunkBytes) {
            val n =
                try {
                    read(buf, filled, chunkBytes - filled)
                } catch (e: java.io.IOException) {
                    return ChunkRead.Error(e)
                }
            if (n <= 0) break
            filled += n
        }
        return if (filled == 0) ChunkRead.Short else ChunkRead.Data(buf.copyOf(filled))
    }

    private sealed interface ChunkRead {
        data class Data(
            val bytes: ByteArray,
        ) : ChunkRead

        data object Short : ChunkRead

        data class Error(
            val cause: Throwable,
        ) : ChunkRead
    }

    private fun JsonElement?.asObj(): JsonObject? = this?.let { runCatching { it.jsonObject }.getOrNull() }

    private fun JsonElement?.asStr(): String? = this?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

    private fun JsonElement?.asInt(): Int? = this?.let { runCatching { it.jsonPrimitive.content.toIntOrNull() }.getOrNull() }
}
