package com.zcode.remote.ui.screens

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
import androidx.compose.ui.text.TextStyle
import com.zcode.remote.R
import com.zcode.remote.relay.ConversationChannel
import com.zcode.remote.util.UiText
import com.zcode.remote.util.UiText.Companion.res
import com.zcode.remote.util.asString
import kotlinx.coroutines.delay

/**
 * 会话空态的「同步阶段 + 已等待秒数」（任务书 §7）。
 *
 * 缺陷背景：会话页在无行可渲染时只显示一句静态的「等待会话同步…」，既说不出**当前卡在哪个
 * 阶段**（连接 / 握手 / 订阅），也不给出**已经等了多久**——用户无法判断是正常建连还是已经卡死，
 * 更没法据此决定要不要点顶栏的「重试」。任务书 §7 明文要求「空态须显示当前阶段与已等待秒数」，
 * C-3 条目将其记为「Status 细粒度化，留待需要时立项」，本文件落地该项。
 *
 * [syncHeadline] 为纯函数（参数化 [elapsedSeconds]），JVM 单测可钉死各阶段文案与边界；
 * [EmptySyncHeadline] 是唯一的计时驱动点，等待态下每秒推进一次重组。
 */
internal fun syncHeadline(
    status: ConversationChannel.Status,
    isDraft: Boolean,
    elapsedSeconds: Long,
): UiText =
    when {
        // 新建会话草稿：还没开始同步，不必显示等待时长
        isDraft -> res(R.string.sync_new_session_ready)
        status is ConversationChannel.Status.Failed -> res(R.string.sync_failed)
        // 已订阅但一行都没有 = 真·空会话（新会话 / 尚未产生内容），不是「还在同步」
        status is ConversationChannel.Status.Live -> res(R.string.sync_empty_session)
        status is ConversationChannel.Status.Hello -> res(R.string.sync_wait_handshake, elapsedSeconds)
        status is ConversationChannel.Status.Initialized -> res(R.string.sync_wait_subscribing, elapsedSeconds)
        // Idle 及未知：尚未拿到会话状态，按「建立连接中」呈现
        else -> res(R.string.sync_wait_connecting, elapsedSeconds)
    }

/** 空态副文案：等待态解释在同步什么；草稿态给出发送引导；失败/空会话不再赘述。 */
internal fun syncSubtext(
    status: ConversationChannel.Status,
    isDraft: Boolean,
): UiText? =
    when {
        isDraft -> res(R.string.sync_new_session_hint)
        status is ConversationChannel.Status.Failed -> null
        status is ConversationChannel.Status.Live -> null
        else -> res(R.string.sync_syncing_hint)
    }

/**
 * 等待态是否应继续计时：草稿、已失败、已订阅（空会话）都不再等待。
 * 抽出为纯函数，供计时协程与单测共用同一判定。
 */
internal fun isWaitingForSync(status: ConversationChannel.Status): Boolean =
    status is ConversationChannel.Status.Idle ||
        status is ConversationChannel.Status.Hello ||
        status is ConversationChannel.Status.Initialized

/**
 * 空态主标题（带已等待秒数）。
 *
 * 计时锚点 = 「进入等待」这一刻：`isWaitingForSync` 由 true 变 false 时协程自然结束，
 * 秒数冻结在最后一刻，不会在切到「空会话」文案后继续无意义地跳数。
 * 由 Idle → Hello → Initialized 的阶段迁移不重启计时（`waiting` 恒为 true），
 * 故显示的是**累计等待时长**，而非各阶段各自的时长——用户关心的是「总共等了多久」。
 */
@Composable
internal fun EmptySyncHeadline(
    status: ConversationChannel.Status,
    isDraft: Boolean,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleMedium,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    val waiting = !isDraft && isWaitingForSync(status)
    var elapsedSeconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(waiting) {
        if (!waiting) return@LaunchedEffect
        elapsedSeconds = 0L
        while (true) {
            delay(1000L)
            elapsedSeconds += 1
        }
    }
    Text(
        text = syncHeadline(status, isDraft, elapsedSeconds).asString(),
        style = style,
        color = color,
        modifier = modifier,
        maxLines = 2,
    )
}
