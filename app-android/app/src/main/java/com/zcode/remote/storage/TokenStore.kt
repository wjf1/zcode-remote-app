package com.zcode.remote.storage

import android.content.Context

/**
 * C-9：敏感令牌存储（GitHub Token）—— AES-256-GCM，密钥入 Android Keystore。
 *
 * 复用 [CredentialCrypto]（与 [MultiDeviceStore] 同一把主密钥，无新增密钥材料）；
 * **禁止引入 EncryptedSharedPreferences**（任务书 §11.6 明令）。
 * 明文令牌不再落任何 SharedPreferences；旧明文由 SettingsStore 首次读取时迁移并删除。
 */
class TokenStore(context: Context) {

    private val prefs = context.getSharedPreferences("zcode_credentials", Context.MODE_PRIVATE)
    private val crypto = CredentialCrypto()

    fun read(): String? {
        val iv = prefs.getString(KEY_IV, null) ?: return null
        val data = prefs.getString(KEY, null) ?: return null
        return crypto.decrypt(iv, data)
    }

    /** 写入（空串/空白 = 清除）。 */
    fun write(token: String) {
        val t = token.trim()
        if (t.isEmpty()) {
            clear()
            return
        }
        val (iv, data) = crypto.encrypt(t)
        prefs.edit().putString(KEY_IV, iv).putString(KEY, data).apply()
    }

    fun clear() = prefs.edit().remove(KEY).remove(KEY_IV).apply()

    private companion object {
        const val KEY = "github_token_enc"
        const val KEY_IV = "github_token_enc_iv"
    }
}
