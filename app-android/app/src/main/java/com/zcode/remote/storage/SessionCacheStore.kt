package com.zcode.remote.storage

import android.content.Context
import com.zcode.remote.relay.ConversationRow
import com.zcode.remote.relay.SessionItem
import com.zcode.remote.util.ZLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 会话离线持久化缓存（Sprint 5 · Offline First / 秒开体验）：
 *
 * 解决启动冷开必须等待中继握手与 bootstrap 回包才渲染会话列表的白屏等待感。
 * AppViewModel 初始化时立即同步拉取本地缓存（0ms 瞬间渲染上一轮会话），
 * 网络连接就绪后自动拉取增量对拍并写回缓存。
 * 同时支持单会话消息行（ConversationRow）本地缓存，点进会话即刻呈现历史对话。
 * 使用原子文件替换写入，避免进程闪退造成缓存损坏。
 */
object SessionCacheStore {
    private const val TAG = "SessionCache"
    private const val CACHE_FILE = "sessions_cache.json"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 读取缓存的会话列表。 */
    fun load(context: Context): List<SessionItem> {
        val file = File(context.filesDir, CACHE_FILE)
        if (!file.exists()) return emptyList()
        return runCatching {
            val raw = file.readText()
            json.decodeFromString<List<SessionItem>>(raw)
        }.onFailure {
            ZLog.w(TAG, "解析本地会话缓存失败: ${it.message}")
        }.getOrDefault(emptyList())
    }

    /** 异步持久化保存会话列表（原子写）。 */
    suspend fun save(context: Context, sessions: List<SessionItem>) = withContext(Dispatchers.IO) {
        if (sessions.isEmpty()) return@withContext
        runCatching {
            val raw = json.encodeToString(sessions)
            val target = File(context.filesDir, CACHE_FILE)
            val tmp = File(context.filesDir, "$CACHE_FILE.tmp")
            tmp.writeText(raw)
            if (tmp.renameTo(target) || (target.delete() && tmp.renameTo(target))) {
                ZLog.d(TAG, "成功持久化 ${sessions.size} 条会话缓存")
            }
        }.onFailure {
            ZLog.w(TAG, "保存会话缓存失败: ${it.message}")
        }
    }

    /** 读取单个会话的历史行缓存（用于点进会话首帧秒开渲染）。 */
    fun loadRows(context: Context, sessionId: String): List<ConversationRow> {
        val safeId = sessionId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val file = File(context.filesDir, "rows_$safeId.json")
        if (!file.exists()) return emptyList()
        return runCatching {
            val raw = file.readText()
            json.decodeFromString<List<ConversationRow>>(raw)
        }.onFailure {
            ZLog.w(TAG, "解析会话行缓存失败 ($sessionId): ${it.message}")
        }.getOrDefault(emptyList())
    }

    /** 异步保存单个会话的消息行（保留最近 200 行，防止体积膨胀）。 */
    suspend fun saveRows(context: Context, sessionId: String, rows: List<ConversationRow>) = withContext(Dispatchers.IO) {
        if (rows.isEmpty()) return@withContext
        val safeId = sessionId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        runCatching {
            val slice = if (rows.size > 200) rows.takeLast(200) else rows
            val raw = json.encodeToString(slice)
            val target = File(context.filesDir, "rows_$safeId.json")
            val tmp = File(context.filesDir, "rows_$safeId.json.tmp")
            tmp.writeText(raw)
            if (tmp.renameTo(target) || (target.delete() && tmp.renameTo(target))) {
                ZLog.d(TAG, "成功持久化会话 $sessionId 的 ${slice.size} 行缓存")
                // C-7：写入后顺手裁剪目录（LRU），保证文件数收敛到上限
                pruneRowsCache(context)
            }
        }.onFailure {
            ZLog.w(TAG, "保存会话行缓存失败 ($sessionId): ${it.message}")
        }
    }

    /**
     * C-7：删除单个会话的行缓存文件。会话被删除时调用——原实现只从列表移除会话项，
     * `rows_<sid>.json` 永久残留（缓存文件数量无任何上限）。
     */
    fun deleteRows(context: Context, sessionId: String) {
        val safeId = sessionId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        runCatching {
            if (File(context.filesDir, "rows_$safeId.json").delete()) {
                ZLog.d(TAG, "已清理会话行缓存 $sessionId")
            }
        }.onFailure { ZLog.w(TAG, "删除会话行缓存失败 ($sessionId): ${it.message}") }
    }

    /**
     * C-7：LRU 裁剪行缓存目录——只保留最近写入的 [keep] 个 `rows_*.json`。
     * 每个文件最多 200 行，但文件数随「打开过的会话数」无上限增长，长期使用会持续占用存储。
     */
    fun pruneRowsCache(context: Context, keep: Int = MAX_ROW_CACHE_FILES) {
        runCatching {
            val files = context.filesDir
                .listFiles { f -> f.name.startsWith("rows_") && f.name.endsWith(".json") }
                ?: return
            val stale = staleCacheFilesToDelete(files.map { it.name to it.lastModified() }, keep)
            stale.forEach { name ->
                if (File(context.filesDir, name).delete()) ZLog.d(TAG, "LRU 清理过期行缓存 $name")
            }
        }.onFailure { ZLog.w(TAG, "裁剪行缓存失败: ${it.message}") }
    }

    /**
     * LRU 淘汰选择（纯函数，JVM 单测覆盖）：[entries] = (文件名, 最后修改时间)，
     * 返回应删除的文件名（按名称排序，保证结果确定）。
     * 保留最近修改的最多 [keep] 个；时间相同时按文件名升序，避免同毫秒写入抖动误删。
     */
    internal fun staleCacheFilesToDelete(entries: List<Pair<String, Long>>, keep: Int): List<String> {
        if (keep <= 0) return entries.map { it.first }.sorted()
        val sorted = entries.sortedWith(
            compareByDescending<Pair<String, Long>> { it.second }.thenBy { it.first }
        )
        return sorted.drop(keep).map { it.first }.sorted()
    }

    /** 行缓存文件数量上限（LRU）：单个文件约 ≤200 行文本，30 个足够覆盖常用会话。 */
    const val MAX_ROW_CACHE_FILES = 30
}
