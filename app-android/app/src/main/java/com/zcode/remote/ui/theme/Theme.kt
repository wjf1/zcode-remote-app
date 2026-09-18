package com.zcode.remote.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF7C9EFF),
    background = Color(0xFF161616),   // 对齐官方 Web App theme-color
    surface = Color(0xFF1E1E22),
)

@Composable
fun ZCodeTheme(content: @Composable () -> Unit) {
    // 深色优先（对标官方 remote/v4 color-scheme: dark）
    MaterialTheme(colorScheme = DarkScheme, content = content)
}
