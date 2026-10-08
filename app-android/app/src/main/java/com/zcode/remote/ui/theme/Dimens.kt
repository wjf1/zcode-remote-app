package com.zcode.remote.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 桌面端尺寸规范：间距基于 4px 网格，圆角为 2/4/6/8/12/16/24px。
 *
 * 注意主消息流本身是单列窄列（`max-w-4xl` = 896px），没有分栏/头像/轨迹线，
 * 所以这里只取「内边距、行距、圆角、限高」这类可复用的数值，
 * 不引入 896px 这种在手机上无意义的宽度上限（[BodyMaxWidth] 仅作语义标注保留）。
 */
object ZCodeDimens {
    /** 基准网格 4px。 */
    val Grid: Dp = 4.dp

    /** gap-5：轮次与轮次之间的垂直间距。 */
    val GapTurn: Dp = 20.dp

    /** gap-2：同一行内图标/标签/文案之间的间距。 */
    val GapInline: Dp = 8.dp

    /** gap-1：紧贴元素（如「·」两侧）。 */
    val GapTight: Dp = 4.dp

    /** px-4：消息流左右内边距。 */
    val PadInline: Dp = 16.dp

    /** py-3：气泡/折叠行纵向内边距。 */
    val PadBlock: Dp = 12.dp

    /** @md px-6：宽屏下的消息流内边距（手机不触发，保留语义）。 */
    val PadWide: Dp = 24.dp

    /** rounded-tr-xs = 2px：用户气泡右上角「尾巴」。 */
    val RadiusTail: Dp = 2.dp
    val RadiusSm: Dp = 4.dp
    val RadiusMd: Dp = 6.dp
    val RadiusLg: Dp = 8.dp

    /** rounded-xl = 12px：用户气泡、代码块、工具卡统一用这一档。 */
    val RadiusXl: Dp = 12.dp
    val RadiusXxl: Dp = 16.dp

    /** 桌面端 max-w-4xl，标注用。 */
    val BodyMaxWidth: Dp = 896.dp

    /** 桌面端用户气泡 max-w-xl = 576px。手机屏宽不足，实际按屏宽取 [BubbleWidthFraction]。 */
    val BubbleMaxWidth: Dp = 576.dp

    /** 手机端用户气泡宽度上限约占屏宽（桌面按固定 576px，手机改按比例才不至于贴满整屏）。 */
    const val BubbleWidthFraction: Float = 0.84f

    /** 桌面端 max-h-60 = 240px：折叠体展开后的限高。 */
    val CollapsedMaxHeight: Dp = 240.dp

    /** 桌面端代码块 min-height 200px（手机上偏大，故意不用，见 B9 说明）。 */
    val CodeMinHeight: Dp = 200.dp

    /** 桌面端 diff 行号槽 w-12 = 48px。 */
    val DiffGutterWidth: Dp = 48.dp

    /** 桌面端 diff 左侧着重竖条 3px（boxShadow inset 3px 0 0）。 */
    val DiffAccentWidth: Dp = 3.dp

    /** 引用/思考体的左侧竖线缩进：桌面端 ml-2（8px）。 */
    val QuoteMarginStart: Dp = 8.dp

    /** 引用/思考体的左侧竖线内缩：桌面端 pl-3.5（14px）。 */
    val QuotePaddingStart: Dp = 14.dp

    /** 工具家族图标尺寸：桌面端 `size-4` = 16px。 */
    val IconSm: Dp = 16.dp

    /** chevron 尺寸，与 [IconSm] 一致。 */
    val IconChevron: Dp = 16.dp
}
