package com.zcode.remote.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.zcode.remote.relay.ConversationChannel
import com.zcode.remote.relay.ConversationRow
import com.zcode.remote.ui.theme.ZCodeTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * C-14 顶栏布局回归网。
 *
 * 缺陷机制（2026-10-10 真机）：顶栏右侧四个胶囊（文件/状态/模式/模型）在「长模型名 +
 * 大字体」下会占满整行宽度，把中部的标题列压到近乎零宽；该列内的元数据文本此前没有
 * `maxLines` 兜底，于是在零宽下逐字符换行成十几行，把整条顶栏 `Row` 撑到近千像素——
 * 表现为「顶栏下移 + 上下双空白 + 会话流被压扁裁切」。
 *
 * 本断言用 testTag 顶栏行 + 加长模型名 + `fontScale = 1.3` 复现该压力条件，把顶栏高度
 * 钉在单行量级。修复前该行高约 1000px（≈333dp）必然失败，修复后约 2 行（< 96dp）通过。
 */
class ConversationTopBarLayoutTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun topBarHeightStaysSingleRowUnderPressure() {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(base.density, fontScale = 1.3f),
            ) {
                ZCodeTheme(forceDark = true) {
                    ConversationScreen(
                        ui =
                            ConversationUiState(
                                title = "分析 ZCode STREAM_IDLE_TIMEOUT 报错原因并给出修复建议",
                                status = ConversationChannel.Status.Idle,
                                meta =
                                    ConversationChannel.ConversationMeta(
                                        phase = "executing",
                                        totalCount = 254,
                                        model = "cn:deepseek-v4.1-flash-preview-extremely-long-name",
                                    ),
                                rows =
                                    listOf(
                                        ConversationRow(rowId = 1, kind = "assistant", text = "hello"),
                                    ),
                            ),
                        actions = ConversationAction(onBack = {}),
                    )
                }
            }
        }

        // 修复前该行约 1000px（≈333dp）必然失败；修复后约 2 行（< 96dp）通过。
        val bounds = rule.onNodeWithTag("conv-topbar").getUnclippedBoundsInRoot()
        val heightDp = bounds.bottom - bounds.top
        assertTrue(
            "顶栏高度 $heightDp 超过单行上限——疑似标题列被四胶囊压到零宽后、元数据文本换行撑爆整行（C-14）",
            heightDp <= 96.dp,
        )
    }

    /**
     * 输入栏高度有界（走查 #15 布局回归网扩展）。
     *
     * 缺陷模式（与 C-14 同族）：输入栏是底部常驻容器，一旦内容侧缺少高度约束，
     * `fillMaxWidth` 的容器会纵向膨胀、把上方会话流压没。本用例在 `fontScale = 1.3`
     * 压力下用**长草稿**（输入框 `maxLines = 4` 是唯一的软约束点）断言：
     * ① 输入栏高度有界；② 会话流容器仍占有可见高度（未被挤压）。
     *
     * 注：附件条（`AttachmentBar`）是**容器外的独立行**（2026-10-10 真机实测：移入容器内
     * 会压缩输入框，见 C-3 记录），故不在本容器高度内——此处不构造附件。
     */
    @Test
    fun inputBarHeightStaysBoundedUnderFontScale() {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(base.density, fontScale = 1.3f),
            ) {
                ZCodeTheme(forceDark = true) {
                    ConversationScreen(
                        ui =
                            ConversationUiState(
                                title = "会话",
                                status = ConversationChannel.Status.Live("s", "build", "1"),
                                meta = ConversationChannel.ConversationMeta(),
                                rows = listOf(ConversationRow(rowId = 1, kind = "assistant", text = "hello")),
                                // 长草稿：输入框 maxLines=4 封顶，容器不应因此无界增高
                                prompt = "请帮我把这段很长的指令完整地写下来用于压测输入栏高度是否会被撑开".repeat(3),
                            ),
                        actions = ConversationAction(onBack = {}),
                    )
                }
            }
        }
        rule.waitForIdle()

        val bar = rule.onNodeWithTag("conv-inputbar").getUnclippedBoundsInRoot()
        val heightDp = bar.bottom - bar.top
        // 上限取「4 行输入框（1.3x 字体）+ 内边距 + 48dp 按钮行」的宽松值；
        // 超出即说明内容侧约束失效（如 maxLines 被绕过、按钮行换行）。
        assertTrue(
            "输入栏高度 $heightDp 超上限（1.3x 字体 + 长草稿）——疑似内容侧缺少高度约束",
            heightDp <= 260.dp,
        )
        // 会话流容器必须仍然存在且占可见高度（被压没时该节点会消失或高度趋零）
        val list = rule.onNodeWithTag("conv-timeline-list").getUnclippedBoundsInRoot()
        val listDp = list.bottom - list.top
        assertTrue("会话流容器高度 $listDp 趋零——疑似被输入栏挤压", listDp > 40.dp)
    }
}
