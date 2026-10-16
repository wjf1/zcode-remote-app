package com.zcode.remote.relay

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.MessageDigest

/**
 * 附件上传顺序流水线的**纯 JVM** 回归网（走查 #2：递归回调 → suspend 顺序上传 + seekable 源）。
 *
 * 为什么需要：旧实现（递归 `next()` 回调金字塔 + `openStream()` + `skip()` 续传）埋在
 * `ConversationChannel` 内、依赖 Android Looper，**只能真机人肉验**。本轮把编排抽到
 * [AttachmentUploadPipeline]（依赖经 [UploadRpc]/[UploadSource] 注入）后，整条四步流程
 * 可在 JVM 用内存假件跑通——协议顺序、分片字节、幂等短路、**续传偏移**、失败发 abort、
 * 取消发 abort 全部钉死。
 *
 * 覆盖点与真机验收的分工：本测试保证「编排正确」，真机保证「SAF 开流 / 服务端接受」。
 */
class AttachmentUploadPipelineTest {
    private val chunkBytes = 4

    /** 确定性内容：字节 n = (n % 251)，便于逐片比对。 */
    private fun payload(size: Int) = ByteArray(size) { (it % 251).toByte() }

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun json(vararg pairs: Pair<String, Any>): JsonObject = jsonOf(*pairs)

    /** 记录全部 RPC 调用（顺序 + 参数），并按脚本给应答。 */
    private class FakeRpc(
        val chunkAckOffset: Int = 0,
        val beginNext: Int = 0,
        val committedRef: String? = null,
        val failChunkAt: Int? = null,
        val ackMismatchAt: Int? = null,
    ) : UploadRpc {
        val calls = mutableListOf<Pair<String, Map<String, Any>>>()

        private val decodedChunks = mutableListOf<ByteArray>()

        fun reconstructedChunks(): List<ByteArray> = decodedChunks.toList()

        override suspend fun call(
            method: String,
            args: Map<String, Any>,
        ): RpcChannel.RpcReply {
            calls += method to args
            return when (method) {
                AttachmentUploadPipeline.METHOD_BEGIN ->
                    RpcChannel.RpcReply.Ok(
                        committedRef?.let { jsonOf("state" to "committed", "ref" to it) }
                            ?: jsonOf("nextChunkIndex" to beginNext),
                    )
                AttachmentUploadPipeline.METHOD_CHUNK -> {
                    val idx = args["chunkIndex"] as Int
                    if (failChunkAt == idx) return RpcChannel.RpcReply.Err("boom", null)
                    decodedChunks +=
                        java.util.Base64
                            .getDecoder()
                            .decode(args["dataBase64"] as String)
                    val next = if (ackMismatchAt == idx) idx + 5 else idx + 1
                    RpcChannel.RpcReply.Ok(jsonOf("nextChunkIndex" to next))
                }
                AttachmentUploadPipeline.METHOD_COMMIT -> RpcChannel.RpcReply.Ok(jsonOf("ref" to "ref-$chunkAckOffset"))
                AttachmentUploadPipeline.METHOD_ABORT -> RpcChannel.RpcReply.Ok(jsonOf())
                else -> RpcChannel.RpcReply.Err("unexpected method $method", null)
            }
        }
    }

    private fun source(bytes: ByteArray): UploadSource = UploadSource { offset -> ByteArrayInputStream(bytes, offset.toInt(), bytes.size - offset.toInt()) }

    private fun run(
        bytes: ByteArray,
        rpc: UploadRpc,
        totalChunksOverride: Int? = null,
        onProgress: ((Long, Long) -> Unit)? = null,
    ) = runBlocking {
        AttachmentUploadPipeline.run(
            source = source(bytes),
            rpc = rpc,
            base = mapOf("sessionId" to "s1", "uploadId" to "u1"),
            fileName = "a.bin",
            mime = "application/octet-stream",
            totalBytes = bytes.size.toLong(),
            totalChunks = totalChunksOverride ?: ((bytes.size + chunkBytes - 1) / chunkBytes),
            chunkBytes = chunkBytes,
            ioDispatcher = Dispatchers.Default,
            onProgress = onProgress,
        )
    }

    @Test
    fun happyPath_sendsBeginChunksCommitInOrder() {
        val bytes = payload(10) // 3 chunks (4+4+2)
        val rpc = FakeRpc()
        val result = run(bytes, rpc)

        assertTrue("应成功：$result", result.isSuccess)
        assertEquals(
            listOf(
                AttachmentUploadPipeline.METHOD_BEGIN,
                AttachmentUploadPipeline.METHOD_CHUNK,
                AttachmentUploadPipeline.METHOD_CHUNK,
                AttachmentUploadPipeline.METHOD_CHUNK,
                AttachmentUploadPipeline.METHOD_COMMIT,
            ),
            rpc.calls.map { it.first },
        )
        // 分片字节应与原文一致（含末片不足一片的情形）
        val reassembled: List<Byte> = rpc.reconstructedChunks().flatMap { it.toList() }
        assertEquals(bytes.toList(), reassembled)
        assertEquals("ref-0", result.getOrNull()?.ref)
    }

    @Test
    fun beginCarriesNormalizedSha256AndChunkCount() {
        val bytes = payload(10)
        val rpc = FakeRpc()
        run(bytes, rpc)
        val begin = rpc.calls.first { it.first == AttachmentUploadPipeline.METHOD_BEGIN }.second
        assertEquals("sha256:${sha256Hex(bytes)}", begin["checksum"])
        assertEquals(3, begin["totalChunks"])
        assertEquals(10L, begin["totalBytes"])
        assertEquals("u1", begin["uploadId"])
        assertEquals("s1", begin["sessionId"])
    }

    @Test
    fun committedOnBeginShortCircuitsWithoutChunks() {
        // C-1 幂等：服务端对该 uploadId 已有 committed 内容 → 不再传分片，直接复用 ref
        val rpc = FakeRpc(committedRef = "ref-committed")
        val result = run(payload(10), rpc)

        assertTrue(result.isSuccess)
        assertEquals(listOf(AttachmentUploadPipeline.METHOD_BEGIN), rpc.calls.map { it.first })
        assertEquals("ref-committed", result.getOrNull()?.ref)
    }

    /**
     * **本次重构的核心回归**：续传时按**绝对偏移**开流，只传剩余分片。
     *
     * 旧实现从偏移 0 开流再 `skip(N*chunkBytes)`——本测试断言「源收到的 offset 正确」且
     * 「不存在对已完成分片的重复上传」。
     */
    @Test
    fun resumeOpensSourceAtOffsetAndSendsOnlyRemainingChunks() {
        val bytes = payload(10) // chunks: [0..3][4..7][8..9]
        val offsetsSeen = mutableListOf<Long>()
        val rpc = FakeRpc(beginNext = 2)
        val result =
            runBlocking {
                AttachmentUploadPipeline.run(
                    // 记录请求的偏移，并**从该偏移**返回剩余内容
                    source = { offset ->
                        offsetsSeen += offset
                        ByteArrayInputStream(bytes, offset.toInt(), bytes.size - offset.toInt())
                    },
                    rpc = rpc,
                    base = mapOf("sessionId" to "s1", "uploadId" to "u1"),
                    fileName = "a.bin",
                    mime = "application/octet-stream",
                    totalBytes = bytes.size.toLong(),
                    totalChunks = 3,
                    chunkBytes = chunkBytes,
                    ioDispatcher = Dispatchers.Default,
                )
            }

        assertTrue("应成功：$result", result.isSuccess)
        // 第一遍哈希从 0 开流；上传剩余分片从偏移 2*chunkBytes = 8 开流（不再重读 0..7）
        assertEquals(listOf(0L, 8L), offsetsSeen)
        val chunks = rpc.calls.filter { it.first == AttachmentUploadPipeline.METHOD_CHUNK }
        assertEquals("只应上传剩余 1 片", 1, chunks.size)
        assertEquals(2, chunks.first().second["chunkIndex"])
        // 上传的字节应恰为末片 [8,10)
        assertEquals(bytes.toList().subList(8, 10), rpc.reconstructedChunks().first().toList())
    }

    @Test
    fun progressReportsCumulativeBytesCappedAtTotal() {
        val bytes = payload(10)
        val progress = mutableListOf<Pair<Long, Long>>()
        run(bytes, FakeRpc(), onProgress = { up, all -> progress += up to all })

        // 3 片（4+4+2）：进度按 index*chunkBytes 递增，末片封顶到 total（10），与旧实现口径一致
        assertEquals(3, progress.size)
        assertEquals(listOf(4L, 8L, 10L), progress.map { it.first })
        assertTrue("进度上界应恒为 total", progress.all { it.second == 10L })
    }

    @Test
    fun chunkFailureSendsAbortAndFails() {
        val rpc = FakeRpc(failChunkAt = 1)
        val result = run(payload(10), rpc)

        assertTrue(result.isFailure)
        assertTrue(
            "失败应先发 abort 回收在途分片，实际调用：${rpc.calls.map { it.first }}",
            rpc.calls.any { it.first == AttachmentUploadPipeline.METHOD_ABORT },
        )
        assertTrue("失败后不得再 commit", rpc.calls.none { it.first == AttachmentUploadPipeline.METHOD_COMMIT })
    }

    @Test
    fun acksProgressMismatchFails() {
        val rpc = FakeRpc(ackMismatchAt = 0)
        val result = run(payload(10), rpc)
        assertTrue(result.isFailure)
        assertTrue(rpc.calls.any { it.first == AttachmentUploadPipeline.METHOD_ABORT })
    }

    @Test
    fun shortContentFailsWithReadableMessage() {
        // 声明 10 字节但源只给 5 字节 → 读流提前结束
        val declared = 10
        val actual = payload(5)
        val rpc = FakeRpc()
        val result =
            runBlocking {
                AttachmentUploadPipeline.run(
                    source = { offset -> ByteArrayInputStream(actual, offset.toInt(), actual.size - offset.toInt()) },
                    rpc = rpc,
                    base = mapOf("sessionId" to "s1"),
                    fileName = "a.bin",
                    mime = "m",
                    totalBytes = declared.toLong(),
                    totalChunks = 3,
                    chunkBytes = chunkBytes,
                    ioDispatcher = Dispatchers.Default,
                )
            }
        assertTrue(result.isFailure)
        assertTrue(
            "应给出「内容比声明大小短」的可读原因，实际：${result.exceptionOrNull()?.message}",
            result.exceptionOrNull()?.message?.contains("短") == true,
        )
    }

    /**
     * 取消（A-1 切会话）：协程取消后 pipeline 必须**补发 abort**（走 NonCancellable），
     * 且不再 commit——这与旧实现 `UploadHandle.abort()` 的语义等价。
     */
    @Test
    fun cancellationSendsAbortEvenUnderCancellation() {
        val abortSeen = CompletableDeferred<Boolean>()
        val chunkReached = CompletableDeferred<Unit>()
        val rpc =
            object : UploadRpc {
                override suspend fun call(
                    method: String,
                    args: Map<String, Any>,
                ): RpcChannel.RpcReply =
                    when (method) {
                        AttachmentUploadPipeline.METHOD_BEGIN -> RpcChannel.RpcReply.Ok(jsonOf("nextChunkIndex" to 0))
                        AttachmentUploadPipeline.METHOD_CHUNK -> {
                            chunkReached.complete(Unit)
                            CompletableDeferred<Unit>().await() // 永远挂起，等被取消
                            RpcChannel.RpcReply.Ok(jsonOf("nextChunkIndex" to 1))
                        }
                        AttachmentUploadPipeline.METHOD_ABORT -> {
                            abortSeen.complete(true)
                            RpcChannel.RpcReply.Ok(jsonOf())
                        }
                        else -> RpcChannel.RpcReply.Err("unexpected", null)
                    }
            }
        runBlocking {
            val job =
                launch {
                    AttachmentUploadPipeline.run(
                        source = source(payload(10)),
                        rpc = rpc,
                        base = mapOf("sessionId" to "s1"),
                        fileName = "a.bin",
                        mime = "m",
                        totalBytes = 10L,
                        totalChunks = 3,
                        chunkBytes = chunkBytes,
                        ioDispatcher = Dispatchers.Default,
                    )
                }
            withTimeout(5_000) { chunkReached.await() } // 确保已进入分片上传再取消
            job.cancel()
            job.join()
            assertTrue("取消时应补发 abort（NonCancellable）", abortSeen.await())
        }
    }

    @Test
    fun invalidServerProgressFailsWithoutChunkUpload() {
        val rpc = FakeRpc(beginNext = 99)
        val result = run(payload(10), rpc, totalChunksOverride = 3)
        assertTrue(result.isFailure)
        assertNull("非法进度不得进入分片阶段", rpc.calls.firstOrNull { it.first == AttachmentUploadPipeline.METHOD_CHUNK })
        assertTrue(rpc.calls.any { it.first == AttachmentUploadPipeline.METHOD_ABORT })
    }

    @Test
    fun emptyChunkListCommitsDirectly() {
        // totalChunks 边界：服务端说 next==totalChunks（已传完）→ 直接 commit，不传分片
        val rpc = FakeRpc(beginNext = 3)
        val bytes = payload(10)
        val result = run(bytes, rpc, totalChunksOverride = 3)
        assertTrue(result.isSuccess)
        assertNull(rpc.calls.firstOrNull { it.first == AttachmentUploadPipeline.METHOD_CHUNK })
        assertNotNull(rpc.calls.firstOrNull { it.first == AttachmentUploadPipeline.METHOD_COMMIT })
    }
}

/** 构造简单 JSON 对象（顶层函数：让嵌套的假件类也能复用）。 */
private fun jsonOf(vararg pairs: Pair<String, Any>): JsonObject =
    buildJsonObject {
        pairs.forEach { (k, v) ->
            when (v) {
                is Int -> put(k, v)
                is String -> put(k, v)
                else -> put(k, v.toString())
            }
        }
    }
