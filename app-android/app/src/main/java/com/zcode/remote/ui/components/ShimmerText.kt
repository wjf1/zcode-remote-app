package com.zcode.remote.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle

/**
 * 运行中状态文字：一道高光带自左向右扫过，对齐桌面端 running 态的 gradient shimmer。
 *
 * 只用于「正在执行 / 正在思考」这类瞬时态；静止态直接用普通 `Text`，
 * 常驻动画会让列表持续重绘（手机端耗电与掉帧都不划算）。
 */
@Composable
fun ShimmerText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    baseColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    highlightColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerProgress",
    )

    // 渐变需要真实宽度，否则高光带的像素位移无法与文字长度对应
    var widthPx by remember { mutableFloatStateOf(0f) }
    val width = widthPx.coerceAtLeast(1f)
    val band = width * 0.6f
    val head = progress * (width + band * 2f) - band

    Text(
        text = text,
        modifier = modifier.onSizeChanged { widthPx = it.width.toFloat() },
        style = style.copy(
            brush = Brush.linearGradient(
                colors = listOf(baseColor, highlightColor, baseColor),
                start = Offset(head, 0f),
                end = Offset(head + band, 0f),
            ),
        ),
    )
}
