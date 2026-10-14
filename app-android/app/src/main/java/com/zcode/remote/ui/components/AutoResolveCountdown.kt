package com.zcode.remote.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay

/**
 * 审批自动决议倒计时（实时刷新）。
 *
 * 缺陷背景：此前两处审批卡（待办 Tab / 会话内联卡）用 `System.currentTimeMillis()` 在**组合期**
 * 直接算一个剩余秒数并渲染成静态文本——数字只在别的状态触发重组时才跳变，用户盯着看时往往
 * 是一个**不动的秒数**，与「倒计时」的语义不符。
 *
 * 这里用 [LaunchedEffect] 每秒驱动一次重组，`deadlineAt` 不变则不会重启计时协程；
 * 到点后停止刷新（显示「即将自动决议」），避免无意义的重组与计时器空转。
 */
@Composable
fun AutoResolveCountdown(
    deadlineAt: Long,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.labelSmall,
) {
    var now by remember(deadlineAt) { mutableLongStateOf(System.currentTimeMillis()) }
    // 每秒推进一次「当前时刻」；到点（剩余 <= 0）后不再 sleep，循环自然结束。
    LaunchedEffect(deadlineAt) {
        while (true) {
            val remaining = deadlineAt - System.currentTimeMillis()
            if (remaining <= 0) break
            // 对齐到下一秒边界附近刷新（最小 250ms，避免剩余很小时疯狂重组）
            delay(remaining.coerceAtMost(1000L).coerceAtLeast(250L))
            now = System.currentTimeMillis()
        }
        now = System.currentTimeMillis()
    }
    Text(
        text = countdownLabel(deadlineAt, now),
        style = style,
        color = color,
        modifier = modifier,
        maxLines = 1,
    )
}

/**
 * 倒计时文案（纯函数，参数化 `now` 以便 JVM 单测钉死边界）：
 * 剩余 > 0 → 「Ns 后自动决议」；已到点 → 「即将自动决议」。
 * 秒数向上取整（剩余 0.4s 也显示 1s），避免出现「0s 后自动决议」这种读起来像出错的文案。
 */
internal fun countdownLabel(
    deadlineAt: Long,
    now: Long,
): String {
    val remainingMs = deadlineAt - now
    if (remainingMs <= 0) return "即将自动决议"
    val sec = ((remainingMs + 999) / 1000).toInt()
    return "${sec}s 后自动决议"
}
