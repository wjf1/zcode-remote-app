@file:OptIn(ExperimentalTestApi::class)

package com.zcode.remote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.zcode.remote.ui.theme.ZCodeTheme
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片兜底（走查 #13）的**非 Espresso** 仪器化回归网。
 *
 * 为什么单开一条而不是并入 [MarkdownRenderTest]：后者用 `createComposeRule`（底层 Espresso），
 * 而 **API 37 真机上 `android.hardware.input.InputManager.getInstance()` 已被移除** —— Espresso
 * 注入初始化即抛 `NoSuchMethodException`，故那类用例只能在 CI 的 API 35 emulator 跑，
 * 真机（本项目唯一可用的 UI 验收环境）**跑不了**。本类改用 `runComposeUiTest`（不注入事件、
 * 只读语义树），因而真机可跑，把「图片兜底真的渲染出来了」变成**可重复的真机断言**。
 *
 * 另一条更有价值的护栏在纯 JVM 侧（`MarkdownImageUrlsTest`）——它钉死「图片落在 PARAGRAPH 里、
 * `components.image` 因此永不可达」这一根因；本类只负责证明修复后**用户真能看到兜底**。
 *
 * 注：`runComposeUiTest` 标注为实验性 API，opt-in 放在**文件级**（Kotlin 不允许在 lambda 体内
 * 使用 opt-in），故本文件统一 `@file:OptIn`。
 */
class MarkdownImageFallbackInstrumentedTest {
    private val pageBg = Color(0xFF202024)

    private fun render(
        test: ComposeUiTest,
        markdown: String,
    ) {
        test.setContent {
            ZCodeTheme(forceDark = true) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(pageBg)
                        .padding(12.dp)
                        .testTag("mdroot"),
                ) {
                    MarkdownView(markdown)
                }
            }
        }
        test.waitForIdle()
    }

    private fun ComposeUiTest.exists(text: String): Boolean =
        onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** http(s) 图片：占位提示 + 「复制链接」+「打开」三者都在（用户有路可走）。 */
    @Test
    fun httpImageShowsPlaceholderCopyAndOpen() =
        runComposeUiTest {
            render(this, "![示意图](https://example.com/a.png)")
            assertTrue("应显示图片占位提示", exists("图片（未加载）"))
            assertTrue("应提供「复制链接」兜底", exists("复制链接"))
            assertTrue("http(s) 图片应提供「打开」入口", exists("打开"))
        }

    /** 非 http(s) 图片：仍给「复制链接」，但**不给**「打开」（scheme 白名单收敛）。 */
    @Test
    fun nonHttpImageHasNoOpenAction() =
        runComposeUiTest {
            render(this, "![x](ftp://example.com/a.png)")
            assertTrue("非 http(s) 图片仍应显示占位提示", exists("图片（未加载）"))
            assertTrue("非 http(s) 图片仍应提供「复制链接」", exists("复制链接"))
            onAllNodesWithText("打开", substring = true, useUnmergedTree = true).assertCountEquals(0)
        }

    /** 段落内联图片（与文字混排）也要兜底——这是最常见的真实形态。 */
    @Test
    fun inlineImageInsideParagraphStillGetsFallback() =
        runComposeUiTest {
            render(this, "看这张 ![图](https://e.com/i.png) 就够了")
            assertTrue("段落内联图片也应显示占位提示", exists("图片（未加载）"))
            assertTrue("段落内联图片也应提供「复制链接」", exists("复制链接"))
        }

    /** 代码块里的 `![..](..)` 是字面文本，不得被当成图片给兜底。 */
    @Test
    fun fencedCodePseudoImageGetsNoFallback() =
        runComposeUiTest {
            render(this, "```md\n![不是图片](https://e.com/nope.png)\n```")
            onAllNodesWithText("图片（未加载）", substring = true, useUnmergedTree = true).assertCountEquals(0)
        }
}
