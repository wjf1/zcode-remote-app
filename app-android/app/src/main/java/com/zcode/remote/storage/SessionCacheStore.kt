package com.zcode.remote.storage

import android.content.Context
import com.zcode.remote.relay.ConversationRow
import com.zcode.remote.relay.SessionItem
import com.zcode.remote.util.ZLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /**
     * §11.6：缓存正文加密器（AES-256-GCM + Android Keystore，独立密钥别名）。
     * **懒加载**：Keystore 在部分环境（单元测试 / 极少数设备）不可用，构造失败时保持 null，
     * 加密写入与解密读取都安全降级（不写明文、读为空），绝不崩溃。
     */
    private val cipher: CacheCipher? by lazy {
        runCatching { KeystoreCacheCipher() }
            .onFailure { ZLog.w(TAG, "缓存加密器不可用，离线缓存将不落盘（不降级为明文）") }
            .getOrNull()
    }

    /** §11.6：写串行化 —— 流式期同一会话的保存可能并发，Mutex 保证「tmp 写 + rename」序列不交错。 */
    private val writeMutex = Mutex()

    /**
     * §11.6 补完：旧明文回写迁移的专用作用域。
     *
     * 读路径（`load` 在冷启动主线程同步读）不能因迁移而阻塞，故迁移异步执行；
     * 用独立的 IO 作用域而非调用方 scope，保证迁移不随页面销毁被取消。
     * 迁移是幂等的（已是密文则 `legacyRewriteTarget` 返回 null），重复触发无副作用。
     */
    private val migrationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** §11.6：缓存 TTL —— 超过 7 天视为过期（换设备/长期未用后不再回放陈旧会话）。 */
    const val CACHE_TTL_MS = 7 * 24 * 60 * 60_000L

    /** §11.6：单个行缓存文件的字节上限（200 行长正文仍可能到数 MB，这里硬封顶）。 */
    const val MAX_ROW_FILE_BYTES = 2 * 1024 * 1024

    /** 读侧行数上限（与写侧 takeLast(200) 对齐——写侧有兜底，读侧防旧文件超限）。 */
    const val MAX_ROWS_PER_FILE = 200

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    /**
     * §11.6：读文件 → 明文（新旧双读）。加密信封走解密，旧明文原样返回；
     * 解密失败（密钥不可用/篡改/损坏）返回 null，由调用方按空缓存处理——**绝不回退明文**。
     *
     * @param onLegacyPlain 命中「旧明文」时回调，携带明文以便上层**回写迁移为密文**。
     */
    private fun readDecoded(
        file: File,
        onLegacyPlain: ((String) -> Unit)? = null,
    ): String? {
        val raw = file.readText()
        val c = cipher
        if (!CacheEnvelope.isWrapped(raw)) {
            if (onLegacyPlain != null && c != null) onLegacyPlain(raw)
            return raw // 旧明文（迁移期）
        }
        if (c == null) return null // 无加密器 → 无法解密的加密文件，安全失败
        return c.decrypt(raw)
    }

    /**
     * §11.6 补完：把命中的**旧明文**缓存原子地重写为密文（回写迁移）。
     *
     * 背景：beta44 的「新旧双读」只在文件**再次被写入**时才转密文，长期不再打开的旧会话
     * 会一直留明文。这里在读取时主动迁移，闭合「数据目录不可检索正文」这条安全门。
     *
     * 约束：
     * - **保留原 mtime**：迁移是格式转换、不是内容更新，刷新时间戳会让过期文件被 TTL 判为新鲜；
     * - **绝不在加密失败时写明文**（`legacyRewriteTarget` 已保证）；
     * - 失败静默（读路径不能因迁移失败而失败），仅告警。
     */
    private fun rewriteLegacyAsEncrypted(
        file: File,
        plain: String,
    ) {
        migrationScope.launch {
            writeMutex.withLock {
                runCatching {
                    // 竞态防护：迁移任务与 save() 共享文件。若在本次迁移拿到锁之前，
                    // save() 已写入更新的密文（内容已变），则**放弃迁移**——
                    // 否则会用读取时捕获的旧明文覆盖刚写入的新数据。
                    val current = runCatching { file.readText() }.getOrNull()
                    if (current != plain) {
                        ZLog.d(TAG, "跳过旧明文迁移：文件已被更新（${file.name}）")
                        return@runCatching
                    }
                    val encrypted = legacyRewriteTarget(plain, encodeEncrypted(plain)) ?: return@runCatching
                    val originalModified = file.lastModified()
                    val tmp = File(file.parentFile, "${file.name}.tmp")
                    tmp.writeText(encrypted)
                    if (tmp.renameTo(file) || (file.delete() && tmp.renameTo(file))) {
                        if (originalModified > 0) file.setLastModified(originalModified)
                        ZLog.d(TAG, "旧明文缓存已回写迁移为密文: ${file.name}")
                    } else {
                        tmp.delete()
                    }
                }.onFailure { ZLog.w(TAG, "旧明文缓存回写失败（保留原文）: ${it.message}") }
            }
        }
    }

    /** §11.6：明文 → 加密信封文本；加密器不可用返回 null（调用方跳过落盘，不写明文）。 */
    private fun encodeEncrypted(plain: String): String? = cipher?.encrypt(plain)

    /** 读取缓存的会话列表。 */
    fun load(context: Context): List<SessionItem> {
        val file = File(context.filesDir, CACHE_FILE)
        if (!file.exists()) return emptyList()
        if (isCacheStale(file.lastModified(), System.currentTimeMillis())) {
            ZLog.d(TAG, "会话列表缓存超过 TTL，丢弃")
            file.delete()
            return emptyList()
        }
        return runCatching {
            // §11.6 补完：命中旧明文时异步触发回写迁移（不阻塞冷启动主线程）
            val plain = readDecoded(file) { legacy -> rewriteLegacyAsEncrypted(file, legacy) } ?: return emptyList()
            json.decodeFromString<List<SessionItem>>(plain)
        }.onFailure {
            ZLog.w(TAG, "解析本地会话缓存失败: ${it.message}")
        }.getOrDefault(emptyList())
    }

    /** 异步持久化保存会话列表（原子写 + 加密）。 */
    suspend fun save(
        context: Context,
        sessions: List<SessionItem>,
    ) = withContext(Dispatchers.IO) {
        if (sessions.isEmpty()) return@withContext
        writeMutex.withLock {
            runCatching {
                val raw = encodeEncrypted(json.encodeToString(sessions))
                if (raw == null) {
                    // 加密器不可用：不落明文（§11.6 安全门要求数据目录不可检索正文）
                    ZLog.w(TAG, "缓存加密器不可用，跳过会话列表落盘")
                    return@withLock
                }
                val target = File(context.filesDir, CACHE_FILE)
                val tmp = File(context.filesDir, "$CACHE_FILE.tmp")
                tmp.writeText(raw)
                if (tmp.renameTo(target) || (target.delete() && tmp.renameTo(target))) {
                    ZLog.d(TAG, "成功持久化 ${sessions.size} 条会话缓存（加密）")
                }
            }.onFailure {
                ZLog.w(TAG, "保存会话缓存失败: ${it.message}")
            }
        }
    }

    /** 读取单个会话的历史行缓存（用于点进会话首帧秒开渲染）。 */
    fun loadRows(
        context: Context,
        sessionId: String,
    ): List<ConversationRow> {
        val safeId = sessionId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val file = File(context.filesDir, "rows_$safeId.json")
        if (!file.exists()) return emptyList()
        // §11.6：TTL —— 过期行缓存直接丢弃（陈旧会话不回放）
        if (isCacheStale(file.lastModified(), System.currentTimeMillis())) {
            ZLog.d(TAG, "会话行缓存超过 TTL，丢弃 ($sessionId)")
            file.delete()
            return emptyList()
        }
        return runCatching {
            // §11.6 补完：命中旧明文时异步触发回写迁移（本方法已在 IO 线程）
            val plain = readDecoded(file) { legacy -> rewriteLegacyAsEncrypted(file, legacy) } ?: return emptyList()
            // §11.6：读侧行数上限 —— 与写侧对齐，防旧文件/异常文件超限
            json.decodeFromString<List<ConversationRow>>(plain).takeLast(MAX_ROWS_PER_FILE)
        }.onFailure {
            ZLog.w(TAG, "解析会话行缓存失败 ($sessionId): ${it.message}")
        }.getOrDefault(emptyList())
    }

    /** 异步保存单个会话的消息行（保留最近 200 行，防体积膨胀；加密落盘）。 */
    suspend fun saveRows(
        context: Context,
        sessionId: String,
        rows: List<ConversationRow>,
    ) = withContext(Dispatchers.IO) {
        if (rows.isEmpty()) return@withContext
        val safeId = sessionId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        writeMutex.withLock {
            runCatching {
                // §11.6：写侧行数上限 + 字节上限（200 行长正文仍可能到数 MB）。
                // 字节预算按**明文**口径判定（加密后体积会因 base64 膨胀 ~1/3，故预算按明文算更保守）。
                val slice =
                    trimRowsToBytes(
                        if (rows.size > MAX_ROWS_PER_FILE) rows.takeLast(MAX_ROWS_PER_FILE) else rows,
                        MAX_ROW_FILE_BYTES,
                    )
                val raw = encodeEncrypted(json.encodeToString(slice))
                if (raw == null) {
                    ZLog.w(TAG, "缓存加密器不可用，跳过会话行落盘 ($sessionId)")
                    return@withLock
                }
                val target = File(context.filesDir, "rows_$safeId.json")
                val tmp = File(context.filesDir, "rows_$safeId.json.tmp")
                tmp.writeText(raw)
                if (tmp.renameTo(target) || (target.delete() && tmp.renameTo(target))) {
                    ZLog.d(TAG, "成功持久化会话 $sessionId 的 ${slice.size} 行缓存（加密）")
                    // C-7：写入后顺手裁剪目录（LRU），保证文件数收敛到上限
                    pruneRowsCache(context)
                }
            }.onFailure {
                ZLog.w(TAG, "保存会话行缓存失败 ($sessionId): ${it.message}")
            }
        }
    }

    /**
     * C-7：删除单个会话的行缓存文件。会话被删除时调用——原实现只从列表移除会话项，
     * `rows_<sid>.json` 永久残留（缓存文件数量无任何上限）。
     */
    fun deleteRows(
        context: Context,
        sessionId: String,
    ) {
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
    fun pruneRowsCache(
        context: Context,
        keep: Int = MAX_ROW_CACHE_FILES,
    ) {
        runCatching {
            val files =
                context.filesDir
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
    internal fun staleCacheFilesToDelete(
        entries: List<Pair<String, Long>>,
        keep: Int,
    ): List<String> {
        if (keep <= 0) return entries.map { it.first }.sorted()
        val sorted =
            entries.sortedWith(
                compareByDescending<Pair<String, Long>> { it.second }.thenBy { it.first },
            )
        return sorted.drop(keep).map { it.first }.sorted()
    }

    /** 行缓存文件数量上限（LRU）：单个文件约 ≤200 行文本，30 个足够覆盖常用会话。 */
    const val MAX_ROW_CACHE_FILES = 30

    /** §11.6：TTL 判定（纯函数，单测覆盖）——lastModified 距今超过 [ttlMs] 即过期。 */
    internal fun isCacheStale(
        lastModified: Long,
        now: Long,
        ttlMs: Long = CACHE_TTL_MS,
    ): Boolean = lastModified > 0 && (now - lastModified) > ttlMs

    /**
     * §11.6：字节上限裁剪（纯函数，单测覆盖）。从最旧一端丢行直到序列化体积 ≤ [maxBytes]
     * （折半丢，避免逐行序列化过慢）；只剩 1 行仍超限则原样返回（单行即超限属病态，交由调用方兜底）。
     */
    internal fun trimRowsToBytes(
        rows: List<ConversationRow>,
        maxBytes: Int,
    ): List<ConversationRow> {
        var slice = rows
        while (slice.size > 1 &&
            json.encodeToString(slice).toByteArray(Charsets.UTF_8).size > maxBytes
        ) {
            slice = slice.takeLast(slice.size / 2)
        }
        return slice
    }

    /** §11.6：删设备/登出时清空全部会话缓存（会话列表 + 全部行缓存）。 */
    fun clearAll(context: Context) {
        runCatching {
            File(context.filesDir, CACHE_FILE).delete()
            context.filesDir
                .listFiles { f -> f.name.startsWith("rows_") && f.name.endsWith(".json") }
                ?.forEach { it.delete() }
            ZLog.d(TAG, "已清空全部会话缓存")
        }.onFailure { ZLog.w(TAG, "清空会话缓存失败: ${it.message}") }
    }
}
