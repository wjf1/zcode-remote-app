package com.zcode.remote.storage

import androidx.test.platform.app.InstrumentationRegistry
import com.zcode.remote.relay.ConversationRow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 离线缓存加密的仪器化网（模拟器/真机运行）——用**真实 Keystore** 验证加解密往返与
 * 「落盘文件不含明文正文」（任务书 §11.6 安全门的核心验收：数据目录不可检索到 canary 正文）。
 *
 * 不依赖中继/配对；用 canary 字符串（明文可得）证明：写入后落盘文件里**搜不到**该串。
 */
class CacheEncryptionInstrumentedTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun row(
        id: Int,
        text: String,
    ) = ConversationRow(rowId = id, kind = "assistantText", text = text)

    /** canary 正文不会被以明文写入磁盘；但读回能正确还原（加解密链路通）。 */
    @Test
    fun cachedRowsAreEncryptedOnDiskAndDecryptableOnRead() {
        val canary = "CANARY-会话正文-9f3a7c-NEVER-IN-PLAINTEXT"
        val sid = "enc-test-${System.nanoTime()}"
        val rows = listOf(row(1, canary), row(2, "第二条消息"))

        runBlocking { SessionCacheStore.saveRows(ctx, sid, rows) }

        // ① 落盘文件存在，且**字节里搜不到 canary 明文**
        val safeId = sid.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val file = File(ctx.filesDir, "rows_$safeId.json")
        assertTrue("缓存文件应已写入", file.exists())
        val bytes = file.readBytes()
        val asLatin = String(bytes, Charsets.ISO_8859_1)
        assertFalse("落盘文件不得含 canary 明文", asLatin.contains(canary))
        // 加密信封前缀应在（证明确实走了加密路径）
        assertTrue("应以加密信封前缀开头", String(bytes, Charsets.UTF_8).startsWith(CacheEnvelope.PREFIX))

        // ② 读回能还原（加解密往返正确）
        val loaded = SessionCacheStore.loadRows(ctx, sid)
        assertTrue("应读回 2 行", loaded.size == 2)
        assertTrue("第一行正文应还原", loaded.any { it.text == canary })

        file.delete()
    }

    /** 会话列表缓存同样加密：列表里的标题不得以明文落盘。 */
    @Test
    fun sessionListCacheIsEncryptedOnDisk() {
        val canaryTitle = "CANARY-会话标题-4b21-NEVER-PLAINTEXT"
        val sessions =
            listOf(
                com.zcode.remote.relay.SessionItem(
                    taskId = "sid-${System.nanoTime()}",
                    title = canaryTitle,
                    displayStatus = "completed",
                    workspacePath = "/x",
                    workspaceLabel = "x",
                    provider = "glm",
                    updatedAt = System.currentTimeMillis(),
                    archived = false,
                ),
            )
        runBlocking { SessionCacheStore.save(ctx, sessions) }

        val file = File(ctx.filesDir, "sessions_cache.json")
        assertTrue(file.exists())
        val asLatin = String(file.readBytes(), Charsets.ISO_8859_1)
        assertFalse("会话列表缓存不得含标题明文", asLatin.contains(canaryTitle))
        assertTrue(String(file.readBytes(), Charsets.UTF_8).startsWith(CacheEnvelope.PREFIX))

        // 读回能还原
        val loaded = SessionCacheStore.load(ctx)
        assertTrue("应读回标题", loaded.any { it.title == canaryTitle })
        file.delete()
    }
}
