package com.zcode.remote.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zcode.remote.ui.theme.LocalZCodeDark
import com.zcode.remote.ui.theme.ZCodeDimens
import com.zcode.remote.ui.theme.ZCodeTokens
import com.zcode.remote.ui.theme.ZCodeType

/**
 * 桌面端「无外框内联折叠行」的通用实现 —— 图标 +类型标签 + 主文案 + 尾部徽标 + chevron，
 * 展开体默认不限宽限高，由调用方通过 [bodyMaxHeight] 决定是否加纵向滚动。
 *
 * 这是工具调用（B4）、思考（B3）、Hook 调用（B6）三类行共用的骨架，
 * 提取出来是为了让这三处形态严格一致 —— 桌面上它们本就是同一套渲染器。
 *
 * **有意偏离桌面端**：桌面的 chevron 常态 `opacity-0`，仅在 hover 或展开时淡入
 * （`group-hover`）。手机没有悬停态，照搬会让「可折叠」完全不可发现，故 chevron 常显。
 *
 * 调用方约定：主文案需要占据剩余宽度时自行加 `Modifier.weight(1f)`，
 * 这样 [trailing] 与 chevron 才会被推到行尾。
 */
@Composable
fun CollapsibleRow(
    expanded: Boolean,
    onToggle: () -> Unit,
    leading: @Composable () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    primary: @Composable RowScope.() -> Unit = {},
    trailing: @Composable RowScope.() -> Unit = {},
    bodyMaxHeight: Dp? = null,
    chevronColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    body: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = ZCodeDimens.Grid),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZCodeDimens.GapInline),
        ) {
            leading()
            label()
            primary()
            trailing()
            val rotation by animateFloatAsState(
                targetValue = if (expanded) 90f else 0f,
                label = "collapsibleChevron",
            )
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = if (expanded) "收起" else "展开",
                tint = chevronColor,
                modifier = Modifier
                    .size(ZCodeDimens.IconChevron)
                    .rotate(rotation),
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(
                modifier = if (bodyMaxHeight != null) {
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = bodyMaxHeight)
                        .verticalScroll(rememberScrollState())
                } else {
                    Modifier.fillMaxWidth()
                },
                content = { body() },
            )
        }
    }
}

/**
 * 折叠行的展开体骨架：左侧一条 1px 中性引导线 + 缩进内容，与桌面端工具/思考详情一致。
 *
 * **为什么用 `drawBehind` 画竖线而不是 `Row` + `Box(fillMaxHeight)`**：
 * 展开体位于 LazyColumn 项内，高度约束是无限的，`fillMaxHeight` 在这种约束下会退化成 0 高，
 * 竖线直接消失；`IntrinsicSize.Min` 又和内部的 `verticalScroll` 不兼容（滚动容器不参与固有测量）。
 * `drawBehind` 不参与测量，只按节点最终尺寸作画，是这里唯一稳妥的写法。
 *
 * @param maxHeight 展开体高度上限，超出后内部纵向滚动（桌面端为 240px）。
 */
@Composable
fun ToolBody(
    modifier: Modifier = Modifier,
    maxHeight: Dp? = ZCodeDimens.CollapsedMaxHeight,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dark = LocalZCodeDark.current
    val lineColor = if (dark) ZCodeTokens.BorderHoverDark else ZCodeTokens.BorderHoverLight
    val density = LocalDensity.current
    val lineWidthPx = with(density) { 1.dp.toPx() }
    val startPad = ZCodeDimens.QuotePaddingStart

    Column(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                drawRect(color = lineColor, size = Size(lineWidthPx, size.height))
            }
            .padding(start = startPad + 1.dp, top = ZCodeDimens.GapInline)
            .let {
                if (maxHeight != null) {
                    it.heightIn(max = maxHeight).verticalScroll(rememberScrollState())
                } else {
                    it
                }
            },
        content = content,
    )
}

/**
 * 展开体内的一个小节（Parameters / Result / Error）。
 *
 * **不做「每节各限高 240dp」**：那要求在一层滚动里再嵌一层滚动，Compose 会给出
 * 嵌套滚动方向的运行时告警，手指拖动时分不清滚的是哪一层。改为由外层 [ToolBody]
 * 统一限高一次——观感与桌面端一致（可见区域同样约 240dp），但只有一个滚动宿主。
 */
@Composable
fun ToolBodySection(
    title: String,
    modifier: Modifier = Modifier,
    titleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    background: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    content: String,
    mono: Boolean = true,
) {
    if (content.isBlank()) return
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = ZCodeType.Sm,
                fontWeight = ZCodeType.SemiBold,
            ),
            color = titleColor,
        )
        Spacer(Modifier.height(ZCodeDimens.GapTight))
        Surface(
            shape = RoundedCornerShape(ZCodeDimens.RadiusMd),
            color = background,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = content,
                style = if (mono) {
                    MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                    )
                } else {
                    MaterialTheme.typography.bodySmall.copy(fontSize = ZCodeType.Caption)
                },
                modifier = Modifier.padding(ZCodeDimens.GapInline),
            )
        }
    }
}

/**
 * 折叠行尾部的计数徽标（桌面端 `+3`、`−1`、`2 个` 这类小胶囊）。
 * 色调由调用方给：增删统计用绿/红，中性计数用副色。
 */
@Composable
fun ToolSummaryBadge(
    text: String,
    tone: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(ZCodeDimens.RadiusSm))
            .background(tone.copy(alpha = 0.15f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = ZCodeType.Sm,
                fontWeight = ZCodeType.Medium,
            ),
            color = tone,
        )
    }
}
