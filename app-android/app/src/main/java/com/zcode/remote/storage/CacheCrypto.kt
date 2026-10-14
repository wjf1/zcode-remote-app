package com.zcode.remote.storage

/**
 * 离线缓存加密（任务书 §11.6「敏感缓存与存储治理」）。
 *
 * 背景：`SessionCacheStore` 原先把**会话正文**（每个会话最近 200 行消息）以明文 JSON 写入
 * `filesDir/rows_<sid>.json` ——配对凭据与 GitHub Token 都已用 Keystore AES-GCM 加密，
 * 唯独占比最大、最敏感的对话正文没有。设备被 root / 备份提取 / 同沙箱读取即可明文检索。
 *
 * 设计（遵循 §11.6「复用既有 Keystore 能力，禁止引入 EncryptedSharedPreferences」）：
 * - 复用 [CredentialCrypto]（AES-256-GCM + Android Keystore），但用**独立密钥别名**
 *   （`zcode_remote_cache_key`）——与配对凭据的主密钥隔离，二者互不牵连；
 * - 文件采用**版本化信封**前缀（[CacheEnvelope.PREFIX]），据此区分「加密文件」与「旧明文文件」，
 *   实现**新旧双读**：旧明文照常读（迁移期不丢用户离线数据），新写入一律加密；
 * - 加解密失败一律**安全失败**（返回 null → 上层按空缓存处理，绝不落明文、绝不崩）。
 */
internal const val CACHE_KEY_ALIAS = "zcode_remote_cache_key"

/**
 * 加密信封的编解码（纯函数，JVM 单测覆盖）。
 *
 * 格式：`ZCE1:<ivB64>:<dataB64>`。base64 的 URL_SAFE/NO_WRAP 字符集不含 `:`，故用 `:` 分隔安全；
 * 前缀 `ZCE1:` 用于与「旧明文 JSON」区分——JSON 必以 `[` 或 `{` 开头，绝不会撞上前缀。
 */
internal object CacheEnvelope {
    const val PREFIX = "ZCE1:"

    /** 是否为本格式的加密信封（否则视为旧明文）。 */
    fun isWrapped(text: String): Boolean = text.startsWith(PREFIX)

    /** 组装信封；iv/data 均为 base64（不含 `:`）。 */
    fun wrap(
        ivB64: String,
        dataB64: String,
    ): String = "$PREFIX$ivB64:$dataB64"

    /** 解析信封 → (ivB64, dataB64)；格式不符返回 null（安全失败）。 */
    fun unwrap(text: String): Pair<String, String>? {
        if (!isWrapped(text)) return null
        val body = text.removePrefix(PREFIX)
        val sep = body.indexOf(':')
        if (sep <= 0 || sep == body.length - 1) return null
        return body.substring(0, sep) to body.substring(sep + 1)
    }
}

/** 缓存加解密抽象：便于 JVM 单测注入假实现（真实实现依赖 Android Keystore）。 */
internal interface CacheCipher {
    /** 明文 → 密文信封；失败返回 null（调用方**不得**回退写明文）。 */
    fun encrypt(plain: String): String?

    /** 密文信封 → 明文；篡改/损坏/密钥不可用一律返回 null（安全失败）。 */
    fun decrypt(envelope: String): String?
}

/**
 * 真实实现：复用 [CredentialCrypto]（AES-256-GCM + Android Keystore），独立密钥别名。
 * Keystore 不可用（极少数设备）时加解密返回 null → 缓存静默降级为「不落盘」，不写明文。
 */
internal class KeystoreCacheCipher(
    alias: String = CACHE_KEY_ALIAS,
) : CacheCipher {
    private val crypto = CredentialCrypto(alias)

    override fun encrypt(plain: String): String? =
        runCatching {
            val (iv, data) = crypto.encrypt(plain)
            CacheEnvelope.wrap(iv, data)
        }.getOrNull()

    override fun decrypt(envelope: String): String? {
        val (iv, data) = CacheEnvelope.unwrap(envelope) ?: return null
        return runCatching { crypto.decrypt(iv, data) }.getOrNull()
    }
}

/**
 * 读侧解码（纯函数，JVM 单测覆盖）：加密信封 → 解密；旧明文 → 原样返回。
 *
 * 这是「新旧双读」的落点：迁移期两种格式都能读，确保不丢用户既有离线数据。
 * 加密文件解密失败（密钥轮换/文件损坏/被篡改）→ 返回 null，由上层按空缓存处理。
 */
internal fun decodeCacheText(
    raw: String,
    cipher: CacheCipher,
): String? = if (CacheEnvelope.isWrapped(raw)) cipher.decrypt(raw) else raw
