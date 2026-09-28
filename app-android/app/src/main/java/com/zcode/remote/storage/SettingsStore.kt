package com.zcode.remote.storage

import android.content.Context
import android.content.SharedPreferences

/**
 * 应用设置（SharedPreferences，与凭据分离）。
 *
 * 线路（PROTOCOL.md §1）：auto=按配对二维码推断（默认）；main/backup=强制主线/备线；
 * custom=自建中继（填完整 wss:// URL，配合桌面端 ZCODE_WEB_REMOTE_CONTROL_RELAY_WS_URL）。
 */
class SettingsStore(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var endpointMode: String
        get() = sp.getString(KEY_ENDPOINT, "auto") ?: "auto"
        set(v) = sp.edit().putString(KEY_ENDPOINT, v).apply()

    var customRelayUrl: String
        get() = sp.getString(KEY_CUSTOM_URL, "") ?: ""
        set(v) = sp.edit().putString(KEY_CUSTOM_URL, v.trim()).apply()

    var themeMode: String
        get() = sp.getString(KEY_THEME, "dark") ?: "dark"
        set(v) = sp.edit().putString(KEY_THEME, v).apply()

    companion object {
        private const val KEY_ENDPOINT = "endpoint_mode"
        private const val KEY_CUSTOM_URL = "custom_relay_url"
        private const val KEY_THEME = "theme_mode"

        const val ENDPOINT_AUTO = "auto"
        const val ENDPOINT_MAIN = "main"
        const val ENDPOINT_BACKUP = "backup"
        const val ENDPOINT_CUSTOM = "custom"

        const val THEME_DARK = "dark"
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
    }
}
