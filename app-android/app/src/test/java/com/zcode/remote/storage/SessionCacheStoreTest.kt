package com.zcode.remote.storage

import com.zcode.remote.relay.ConversationRow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SessionCacheStore.staleCacheFilesToDelete] 的纯函数单测（C-7 缓存 LRU）。
 *
 * 该函数决定「哪些 `rows_*.json` 会被删掉」——判错就是误删用户最近会话的离线缓存
 * （表现为点进会话秒开失效），且发生在 IO 线程、无 UI 可观察，必须由单测钉死。
 */
class SessionCacheStoreTest {

    private fun entry(name: String, modified: Long) = name to modified

    @Test
    fun withinLimit_deletesNothing() {
        val entries = listOf(entry("rows_a.json", 100L), entry("rows_b.json", 200L))
        assertEquals(emptyList<String>(), SessionCacheStore.staleCacheFilesToDelete(entries, 2))
        assertEquals(emptyList<String>(), SessionCacheStore.staleCacheFilesToDelete(entries, 5))
    }

    @Test
    fun overLimit_deletesOldestFirst() {
        val entries = listOf(
            entry("rows_old.json", 100L),
            entry("rows_mid.json", 200L),
            entry("rows_new.json", 300L),
        )
        assertEquals(listOf("rows_old.json"), SessionCacheStore.staleCacheFilesToDelete(entries, 2))
        assertEquals(
            listOf("rows_mid.json", "rows_old.json"),
            SessionCacheStore.staleCacheFilesToDelete(entries, 1),
        )
    }

    @Test
    fun sameTimestamp_isDeterministic() {
        // 同毫秒写入时按文件名升序保留，结果确定（不能依赖目录枚举顺序）
        val entries = listOf(entry("rows_b.json", 100L), entry("rows_a.json", 100L))
        assertEquals(listOf("rows_b.json"), SessionCacheStore.staleCacheFilesToDelete(entries, 1))
    }

    @Test
    fun zeroKeep_deletesAll() {
        val entries = listOf(entry("rows_a.json", 1L), entry("rows_b.json", 2L))
        assertEquals(
            listOf("rows_a.json", "rows_b.json"),
            SessionCacheStore.staleCacheFilesToDelete(entries, 0),
        )
    }

    @Test
    fun emptyDirectory_returnsEmpty() {
        assertEquals(emptyList<String>(), SessionCacheStore.staleCacheFilesToDelete(emptyList(), 5))
    }

    // ---------- §11.6 缓存治理：TTL 与字节上限 ----------

    private val testJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun ttl_freshCache_isNotStale() {
        val now = 1_000_000_000L
        assertFalse(SessionCacheStore.isCacheStale(now - 1_000, now))
    }

    @Test
    fun ttl_expiredCache_isStale() {
        val now = 1_000_000_000L
        assertTrue(SessionCacheStore.isCacheStale(now - SessionCacheStore.CACHE_TTL_MS - 1, now))
    }

    @Test
    fun ttl_boundary_exactlyAtLimit_isNotStale() {
        val now = 1_000_000_000L
        assertFalse(SessionCacheStore.isCacheStale(now - SessionCacheStore.CACHE_TTL_MS, now))
    }

    @Test
    fun ttl_zeroLastModified_neverStale() {
        // lastModified=0（取不到时间戳的文件）不按过期处理，避免误删
        assertFalse(SessionCacheStore.isCacheStale(0L, 1_000_000_000L))
    }

    @Test
    fun trimRows_withinLimit_keepsAll() {
        val rows = List(50) { ConversationRow(rowId = it, kind = "assistant", text = "x".repeat(100)) }
        assertEquals(rows, SessionCacheStore.trimRowsToBytes(rows, 1 * 1024 * 1024))
    }

    @Test
    fun trimRows_overLimit_dropsOldestUntilFits() {
        val big = "x".repeat(100_000)
        val rows = List(40) { ConversationRow(rowId = it, kind = "assistant", text = big) }
        val trimmed = SessionCacheStore.trimRowsToBytes(rows, 1 * 1024 * 1024)
        assertTrue("应裁掉部分行", trimmed.size < rows.size)
        // 保留的是最新的行
        assertEquals(rows.last().rowId, trimmed.last().rowId)
        // 裁剪后必须真的满足上限（或只剩单行兜底）
        if (trimmed.size > 1) {
            assertTrue(
                "裁剪后仍超限",
                testJson.encodeToString(trimmed).toByteArray(Charsets.UTF_8).size <= 1 * 1024 * 1024,
            )
        }
    }

    @Test
    fun trimRows_singleOversizedRow_returnsItAsIs() {
        // 单行即超限属病态：折半到 1 行为止，原样返回（交由调用方兜底），不得死循环
        val huge = "y".repeat(3 * 1024 * 1024)
        val rows = listOf(ConversationRow(rowId = 1, kind = "assistant", text = huge))
        assertEquals(rows, SessionCacheStore.trimRowsToBytes(rows, 1024))
    }
}
