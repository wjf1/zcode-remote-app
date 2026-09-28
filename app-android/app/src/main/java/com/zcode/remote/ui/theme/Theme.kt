package com.zcode.remote.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF7C9EFF),
    background = Color(0xFF161616),   // 对齐官方 Web App theme-color
    surface = Color(0xFF1E1E22),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF3B5BDB),
    background = Color(0xFFF6F7F9),
    surface = Color(0xFFFFFFFF),
)

/** dark=null 表示跟随系统（设置项 THEME_SYSTEM）。 */
@Composable
fun ZCodeTheme(forceDark: Boolean? = true, content: @Composable () -> Unit) {
    val dark = forceDark ?: isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
}
