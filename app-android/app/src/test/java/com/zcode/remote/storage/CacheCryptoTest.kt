package com.zcode.remote.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 离线缓存加密信封与双读逻辑的纯函数单测（任务书 §11.6）。
 *
 * 这些纯函数决定「旧明文能否继续读」（迁移不丢数据）与「密文损坏时是否安全失败」
 * （绝不回退明文、绝不崩溃）——发生在 IO 线程且无 UI 可观察，必须由单测钉死。
 * 真实 Keystore 加解密走仪器化路径，这里用假 cipher 覆盖编排逻辑。
 */
class CacheCryptoTest {
    /** 记录调用的假 cipher：加密加前缀，解密去前缀；可模拟失败。 */
    private class FakeCipher(
        private val failEncrypt: Boolean = false,
        private val failDecrypt: Boolean = false,
    ) : CacheCipher {
        override fun encrypt(plain: String): String? = if (failEncrypt) null else "ENC<$plain>"

        override fun decrypt(envelope: String): String? {
            if (failDecrypt) return null
            val body = CacheEnvelope.unwrap(envelope) ?: return null
            return "PLAIN<${body.first}|${body.second}>"
        }
    }

    // ---------- CacheEnvelope 纯编解码 ----------

    @Test
    fun wrapThenUnwrap_roundTrips() {
        val s = CacheEnvelope.wrap("IV123", "DATA456")
        assertTrue(CacheEnvelope.isWrapped(s))
        assertEquals("IV123" to "DATA456", CacheEnvelope.unwrap(s))
    }

    /** 旧明文（JSON 以 [ 或 { 开头）绝不被误判为加密信封。 */
    @Test
    fun plainJsonIsNotWrapped() {
        assertFalse(CacheEnvelope.isWrapped("[{\"taskId\":\"x\"}]"))
        assertFalse(CacheEnvelope.isWrapped("{\"active\":\"s\"}"))
        assertNull(CacheEnvelope.unwrap("[1,2,3]"))
    }

    /** 缺分隔符 / 空字段的畸形信封返回 null（安全失败，不抛异常）。 */
    @Test
    fun malformedEnvelopeReturnsNull() {
        assertNull(CacheEnvelope.unwrap("ZCE1:noSeparator"))
        assertNull(CacheEnvelope.unwrap("ZCE1::data")) // 空 iv
        assertNull(CacheEnvelope.unwrap("ZCE1:iv:")) // 空 data
        assertNull(CacheEnvelope.unwrap(""))
    }

    /** base64（URL_SAFE/NO_WRAP）不含 `:`，故 iv 与 data 自身不可能被误切分。 */
    @Test
    fun base64PayloadWithoutColonSplitsCorrectly() {
        val s = CacheEnvelope.wrap("aB-_0", "Zz9+/=")
        assertEquals("aB-_0" to "Zz9+/=", CacheEnvelope.unwrap(s))
    }

    // ---------- decodeCacheText 双读编排 ----------

    /** 加密信封 → 走解密。 */
    @Test
    fun wrappedTextIsDecrypted() {
        val c = FakeCipher()
        val env = CacheEnvelope.wrap("iv", "data")
        assertEquals("PLAIN<iv|data>", decodeCacheText(env, c))
    }

    /** 旧明文 → 原样返回（迁移期不丢用户既有离线数据）。 */
    @Test
    fun plainTextPassesThrough() {
        val c = FakeCipher()
        assertEquals("[{\"rowId\":1}]", decodeCacheText("[{\"rowId\":1}]", c))
    }

    /** 密文解密失败（密钥不可用/篡改）→ null（安全失败，上层按空缓存处理）。 */
    @Test
    fun decryptionFailureReturnsNull() {
        val c = FakeCipher(failDecrypt = true)
        assertNull(decodeCacheText(CacheEnvelope.wrap("iv", "data"), c))
    }

    /** 加密失败 → null（调用方据此跳过落盘，绝不写明文）。 */
    @Test
    fun encryptionFailureReturnsNull() {
        val c = FakeCipher(failEncrypt = true)
        assertNull(c.encrypt("sensitive conversation body"))
    }

    // ---------- §11.6 补完：旧明文回写迁移判定 ----------

    /** 旧明文 + 加密可用 → 返回密文（触发回写迁移）。 */
    @Test
    fun legacyPlainTriggersRewriteToCiphertext() {
        val target = legacyRewriteTarget("""[{"title":"旧明文缓存"}]""", "ZCE1:iv:data")
        assertEquals("ZCE1:iv:data", target)
    }

    /** 已是密文 → 不回写（幂等：重复读取不会重复迁移）。 */
    @Test
    fun alreadyWrappedNeedsNoRewrite() {
        val wrapped = CacheEnvelope.wrap("iv", "data")
        assertNull(legacyRewriteTarget(wrapped, "ZCE1:newiv:newdata"))
    }

    /**
     * 旧明文 + 加密失败 → null（**绝不返回明文**）。
     * 这是迁移的安全底线：若此处返回明文，上层会把「加密」变成「原样重写」的假迁移，
     * 明文将继续留在磁盘上而系统却报告迁移成功。
     */
    @Test
    fun legacyPlainWithFailedEncryptionDoesNotRewritePlaintext() {
        val plain = """[{"title":"旧明文缓存"}]"""
        assertNull(legacyRewriteTarget(plain, null))
    }
}
