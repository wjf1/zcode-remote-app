package com.zcode.remote.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zcode.remote.R
import com.zcode.remote.relay.RelayState
import com.zcode.remote.relay.SessionItem
import com.zcode.remote.storage.ProtocolStack
import com.zcode.remote.util.UiText
import com.zcode.remote.util.UiText.Companion.res

/**
 * 首页列表的空态三态（任务书 T5）。
 *
 * 背景：V2（≥3.16）的会话列表来自 Controller 订阅（PROTOCOL.md §11.6），配对上到首份快照
 * 到达之间存在一段真实空窗。此前这段窗口一律显示「已与 PC 配对，正在拉取会话列表…」，
 * 与「PC 上确实一个会话都没有」共用同一句文案——用户分不清「还在同步」和「真没有」。
 *
 * 三态：
 * - [NotConnected]：未配对/未连上（沿用既有连接态文案，不新增语义）；
 * - [Syncing]：已连接但列表仍空 → 骨架卡片 + 说明（这是 V2 的常态短暂窗口）；
 * - [Empty]：已同步完成且确实没有会话 → 「暂无会话，从 PC 端或 + 新建」。
 *
 * 判定只用「连接状态 + 是否已有条目 + 协议栈 + 是否有过一份快照」，不依赖计时器，
 * 避免用时间猜测同步进度。
 */
enum class HomeEmptyState {
    NotConnected,
    Syncing,
    Empty,
}

/**
 * 计算列表空态的展示态（纯函数，单测覆盖）。
 *
 * @param state 中继连接状态
 * @param sessionCount 当前列表条数（>0 时调用方不走空态，这里只看 0）
 * @param protocolStack 当前设备的协议栈（V2 才有 Controller 快照空窗）
 * @param controllerSynced 是否已收到过至少一份 Controller 快照（V2）；V1 恒 true
 */
internal fun homeEmptyState(
    state: RelayState,
    sessionCount: Int,
    protocolStack: ProtocolStack,
    controllerSynced: Boolean,
): HomeEmptyState {
    if (sessionCount > 0) return HomeEmptyState.Empty
    // 未配对成功（含终态失败）：沿用既有连接态文案，不说「暂无会话」
    if (state !is RelayState.Paired) return HomeEmptyState.NotConnected
    // V1 的列表来自 bootstrap，配对即完成；V2 要等 Controller 首份快照
    if (protocolStack == ProtocolStack.V1) return HomeEmptyState.Empty
    return if (controllerSynced) HomeEmptyState.Empty else HomeEmptyState.Syncing
}

/** 空态主文案（三态各自的 UiText）。 */
internal fun homeEmptyStateText(
    emptyState: HomeEmptyState,
    state: RelayState,
): UiText =
    when (emptyState) {
        // 未连接：完整沿用既有连接态文案（终态失败的原因区分已在那里做）
        HomeEmptyState.NotConnected -> connectionEmptyStateText(state)
        HomeEmptyState.Syncing -> res(R.string.home_syncing_sessions)
        HomeEmptyState.Empty -> res(R.string.home_no_sessions)
    }

/**
 * 过滤后为空时的提示：列表本身有条目但被筛选条件挡住，与「一个会话都没有」是不同语义。
 * [total] 为过滤前条数。
 */
internal fun homeFilteredEmptyText(total: Int): UiText = if (total == 0) res(R.string.home_no_sessions) else res(R.string.home_no_match)

/** 供 UI 复用的行数（骨架卡片数量）。 */
internal const val HOME_SKELETON_ROW_COUNT = 6

/**
 * 列表视图模型：把「原始条目 + 筛选」折叠成一次计算，避免每个组合都在 composition 里重算。
 * 保持为纯函数以便单测钉死排序与筛选语义（与 HomeScreen 既有行为逐项等价）。
 */
internal fun homeVisibleSessions(
    sessions: List<SessionItem>,
    query: String,
    onlyRunning: Boolean,
    onlyPending: Boolean,
    pendingOf: (String) -> Int,
): List<SessionItem> {
    var list = sessions
    if (onlyRunning) list = list.filter { it.isRunning }
    if (onlyPending) list = list.filter { pendingOf(it.taskId) > 0 }
    val q = query.trim()
    if (q.isNotEmpty()) {
        // 标题与工作区路径都参与匹配（既有行为，不要悄悄收窄）
        list =
            list.filter {
                it.title.contains(q, ignoreCase = true) ||
                    (it.workspacePath ?: "").contains(q, ignoreCase = true)
            }
    }
    return list
}

/**
 * 「已连接但还未同步到列表」的骨架屏（T5）。
 *
 * 用静态占位块而不是一条会呼吸动画的列表：这是**短暂**过渡态（V2 首份 Controller 快照
 * 通常 <1s 到达），常驻动画只会白耗电；同时它明确表达「结构已知、内容未到」，
 * 比一句「正在拉取会话列表…」更能让人知道下一步会看到什么。
 */
@Composable
fun HomeSyncingSkeleton() {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(top = 12.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.home_syncing_sessions),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        repeat(HOME_SKELETON_ROW_COUNT) { idx ->
            SkeletonSessionCard(
                // 宽度错落，避免六行完全一样像渲染故障
                titleWidthFraction = if (idx % 2 == 0) 0.62f else 0.48f,
            )
        }
    }
}

/** 单张骨架卡片：与 SessionItemCard 同圆角/同内边距，内容替换为静态占位块。 */
@Composable
private fun SkeletonSessionCard(titleWidthFraction: Float) {
    val block = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .semantics { contentDescription = "skeleton-card" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(block),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                Modifier
                    .fillMaxWidth(titleWidthFraction)
                    .height(12.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(block),
            )
            Box(
                Modifier
                    .fillMaxWidth(0.3f)
                    .height(9.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(block.copy(alpha = 0.7f)),
            )
        }
    }
}
