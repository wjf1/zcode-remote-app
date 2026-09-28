package com.zcode.remote.storage

import android.content.Context
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/**
 * 多机凭据存储（M3）：同一 App 可配对多台 PC，一次只连接一台（活跃设备）。
 *
 * 存储格式 v2：`devices_v2` = AES-256-GCM(JSON {active, list})，JSON 结构
 *   {"active":"<sid>","list":["<sid>\u0001<hash>\u0001<mid>\u0001<name>\u0001<url>", …]}
 * 旧版单条格式（iv/data）首次读取时自动迁移。
 */
class MultiDeviceStore(context: Context) {

    private val prefs = context.getSharedPreferences("zcode_credentials", Context.MODE_PRIVATE)
    private val keyAlias = "zcode_remote_master_key"
    private val crypto = CredentialCrypto()

    data class Snapshot(val devices: List<PairedDevice>, val activeSid: String?)

    fun load(): Snapshot {
        readV2()?.let { return it }
        migrateV1()
        return readV2() ?: Snapshot(emptyList(), null)
    }

    fun save(snapshot: Snapshot) {
        val lines = snapshot.devices.map { d ->
            listOf(d.deviceSid, d.passHash, d.deviceMid ?: "", d.deviceName ?: "", d.remoteUrl)
                .joinToString("\u0001")
        }
        val json = buildString {
            append("{\"active\":\"")
            append((snapshot.activeSid ?: snapshot.devices.firstOrNull()?.deviceSid ?: "")
                .replace("\"", ""))
            append("\",\"list\":[")
            append(lines.joinToString(",") { l -> "\"" + l.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" })
            append("]}")
        }
        writeEncrypted("devices_v2", json)
    }

    /** 追加/更新设备（同 deviceSid 覆盖）并激活。 */
    fun upsertActive(device: PairedDevice): Snapshot {
        val cur = load()
        android.util.Log.i("MultiDeviceStore", "upsert cur=${cur.devices.size}")
        val list = cur.devices.filter { it.deviceSid != device.deviceSid } + device
        val snap = Snapshot(list, device.deviceSid)
        save(snap)
        return snap
    }

    fun setActive(sid: String): Snapshot {
        val cur = load()
        val snap = Snapshot(cur.devices, sid)
        save(snap)
        return snap
    }

    fun remove(sid: String): Snapshot {
        val cur = load()
        val list = cur.devices.filter { it.deviceSid != sid }
        val active = if (cur.activeSid == sid) list.firstOrNull()?.deviceSid else cur.activeSid
        val snap = Snapshot(list, active)
        save(snap)
        return snap
    }

    fun clear() = prefs.edit().remove("devices_v2").remove("iv").remove("data").apply()

    // ---------- 内部 ----------

    private fun readV2(): Snapshot? {
        val json = readEncrypted("devices_v2") ?: return null
        android.util.Log.i("MultiDeviceStore", "readV2 json.len=${json.length}")
        return runCatching {
            // 轻量解析（避免引 JSON 库）：active 与 list 行都经我们自己的转义规则写出
            val active = Regex("\"active\":\"([^\"]*)\"").find(json)?.groupValues?.get(1)
            val listPart = json.substringAfter("\"list\":[", "").substringBeforeLast("]")
            val devices = listPart.split("\",\"").mapNotNull { raw ->
                val line = raw.trim().removePrefix("\"").removeSuffix("\"")
                    .replace("\\\"", "\"").replace("\\\\", "\\")
                if (line.isBlank()) return@mapNotNull null
                val p = line.split("\u0001")
                if (p.size < 5) return@mapNotNull null
                PairedDevice(p[0], p[1], p[2].ifEmpty { null }, p[3].ifEmpty { null }, p[4])
            }
            android.util.Log.i("MultiDeviceStore", "readV2 parsed=${devices.size} active=$active")
            Snapshot(devices, active?.ifEmpty { null })
        }.onFailure { android.util.Log.w("MultiDeviceStore", "readV2 failed", it) }.getOrNull()
    }

    /** 旧版单条（iv/data）→ v2。 */
    private fun migrateV1() {
        val iv = prefs.getString("iv", null) ?: return
        val data = prefs.getString("data", null) ?: return
        val legacy = crypto.decrypt(iv, data)?.let { plain ->
            val p = plain.split("\u0001")
            if (p.size < 5) null
            else PairedDevice(p[0], p[1], p[2].ifEmpty { null }, p[3].ifEmpty { null }, p[4])
        } ?: return
        save(Snapshot(listOf(legacy), legacy.deviceSid))
        prefs.edit().remove("iv").remove("data").apply()
    }

    private fun readEncrypted(key: String): String? {
        val iv = prefs.getString("${key}_iv", null) ?: return null
        val data = prefs.getString(key, null) ?: return null
        return crypto.decrypt(iv, data)
    }

    private fun writeEncrypted(key: String, plain: String) {
        val (iv, data) = crypto.encrypt(plain)
        prefs.edit()
            .putString("${key}_iv", iv)
            .putString(key, data)
            .apply()
    }
}

/** AES-256-GCM 加解密（密钥入 Android Keystore；与 v1 共用同一把主密钥）。 */
class CredentialCrypto(private val keyAlias: String = "zcode_remote_master_key") {

    private fun getOrCreateKey(): SecretKeyAlias {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(keyAlias, null) as? javax.crypto.SecretKey)?.let { return it }
        val kg = javax.crypto.KeyGenerator.getInstance("AES", "AndroidKeyStore")
        kg.init(android.security.keystore.KeyGenParameterSpec.Builder(keyAlias,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return kg.generateKey()
    }

    fun encrypt(plain: String): Pair<String, String> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val enc = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) to
                Base64.encodeToString(enc, Base64.NO_WRAP)
    }

    fun decrypt(ivB64: String, dataB64: String): String? = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(),
            GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(dataB64, Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()
}

private typealias SecretKeyAlias = javax.crypto.SecretKey
