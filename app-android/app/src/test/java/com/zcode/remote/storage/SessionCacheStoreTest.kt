package com.zcode.remote.storage

import org.junit.Assert.assertEquals
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
}
