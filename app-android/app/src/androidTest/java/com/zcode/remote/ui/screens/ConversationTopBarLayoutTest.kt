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
                LocalDensity provides Density(base.density, fontScale = 1.3f)
            ) {
                ZCodeTheme(forceDark = true) {
                    ConversationScreen(
                        title = "分析 ZCode STREAM_IDLE_TIMEOUT 报错原因并给出修复建议",
                        status = ConversationChannel.Status.Idle,
                        meta = ConversationChannel.ConversationMeta(
                            phase = "executing",
                            totalCount = 254,
                            model = "cn:deepseek-v4.1-flash-preview-extremely-long-name",
                        ),
                        rows = listOf(
                            ConversationRow(rowId = 1, kind = "assistant", text = "hello"),
                        ),
                        onBack = {},
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
}
