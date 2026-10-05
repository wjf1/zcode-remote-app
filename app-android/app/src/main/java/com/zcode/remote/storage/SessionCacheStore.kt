package com.zcode.remote.storage

import android.content.Context
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
}
