package com.zcode.remote.storage

import android.content.Context
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 配对凭据本地加密存储（AES-256-GCM，密钥入 Android Keystore）。
 * 对标 PC 端凭据库的 enc:v1 方案（PROTOCOL.md 第 2 节）。
 */
class CredentialStore(context: Context) {
    private val prefs = context.getSharedPreferences("zcode_credentials", Context.MODE_PRIVATE)
    private val keyAlias = "zcode_remote_master_key"

    private fun getOrCreateKey(): SecretKey {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        kg.init(android.security.keystore.KeyGenParameterSpec.Builder(keyAlias,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return kg.generateKey()
    }

    fun save(device: PairedDevice) {
        val plain = listOf(device.deviceSid, device.passHash, device.deviceMid ?: "",
            device.deviceName ?: "", device.remoteUrl).joinToString("\u0001")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val enc = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            .putString("data", Base64.encodeToString(enc, Base64.NO_WRAP))
            .apply()
    }

    fun load(): PairedDevice? {
        val iv = prefs.getString("iv", null) ?: return null
        val data = prefs.getString("data", null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            val plain = String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
            val p = plain.split("\u0001")
            PairedDevice(p[0], p[1], p[2].ifEmpty { null }, p[3].ifEmpty { null }, p[4])
        }.getOrNull()
    }

    fun clear() = prefs.edit().clear().apply()
}
