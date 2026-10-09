package com.zcode.remote.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.zcode.remote.ui.theme.ZCodeTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * 会话正文渲染的仪器化回归网（在模拟器/真机上跑，不需要中继与配对）。
 *
 * 为什么需要它：JVM 单测碰不到 Compose 渲染，而 2026-10-08 的 beta17 真机验收里
 * **抽样会话视口内没有出现围栏代码块与 GFM 表格**，这两项当时只能标注「未取到真机样本」。
 * 本测试用 fixture Markdown 直接渲染真实的 [MarkdownView]，把该空洞变成可重复执行的断言：
 *
 * 1. [gfmTableAndInlineMarkupRender] —— 表格单元格、标题、行内代码、链接、任务列表都渲染出来了，
 *    并且位图上真的**着墨**（不只是语义节点存在）。
 * 2. [fencedCodeBlockGetsSyntaxHighlighting] —— 围栏语言名解析成功，且高亮主题的 **token 色真的被画到屏幕上**
 *    （按 [TOKEN_COLORS] 精确比对像素）。token 色由同仓库的 JVM 侧 `CodeHighlightTest` 实测得出 ——
 *    这里不再用「未知语言 = 无高亮」做差分：实测那个假设是错的（未知语言会回落 DEFAULT 泛化高亮）。
 *
 * 证据留存：捕获的位图写到 `files/render-evidence/`（debug 包可 run-as 取出）：
 * `adb exec-out run-as com.zcode.remote cat files/render-evidence/code_kotlin.png > code_kotlin.png`
 */
class MarkdownRenderTest {

    @get:Rule
    val rule = createComposeRule()

    /** 与 App 会话页一致的页面底色（beta17 验收实测值），用于让对比度接近真实场景。 */
    private val pageBg = Color(0xFF202024)

    private val tableFixture = """
        ## 上下文容量

        正文含**加粗**、`inline code`、[链接](https://example.com/zcode) 与行内 HTML <b>标记</b>。

        | 分类 | 占比 |
        | --- | --- |
        | 系统工具 | 38% |
        | MCP 工具 | 34.3% |
        | 消息 | 21.3% |

        - [x] 已完成项
        - [ ] 未完成项

        1. 有序一
        2. 有序二
    """.trimIndent()

    private val codeBody = """
        // 注释 comment
        fun main() {
            val message = "hello zcode"
            println(message)
        }
    """.trimIndent()

    /**
     * 拼装围栏代码块样本。
     *
     * **不要用 `trimIndent()` 拼多行插值**：插进来的代码正文各行缩进不同，trimIndent 取公共前缀
     * 会算成 0，于是围栏行连同前面的 8 个空格一起留下 —— CommonMark 里缩进 ≥4 空格的 ``` 不是
     * 围栏，会被当成缩进式代码块（语言名丢失、高亮不生效）。这里显式用 `\n` 拼接，正文顶格。
     */
    private fun codeFixture(language: String) = "### 代码块\n\n```$language\n$codeBody\n```\n"

    @Test
    fun gfmTableAndInlineMarkupRender() {
        rule.setContent {
            ZCodeTheme(forceDark = true) {
                Box(Modifier.fillMaxWidth().background(pageBg).padding(12.dp).testTag("mdroot")) {
                    MarkdownView(tableFixture)
                }
            }
        }
        rule.waitForIdle()
        dumpTree("GFM 表格样本")

        // 表格：表头与三行单元格都必须是独立节点（库内表格若退化，这些会整段丢失）
        listOf(
            "分类", "占比", "系统工具", "34.3%", "消息",
            "上下文容量", "inline code", "链接", "已完成项", "未完成项",
        ).forEach { assertTextRendered(it) }

        // 不只看语义节点：把这一屏抓成位图，要求真的画出文字（着墨像素）
        val bitmap = rule.onNodeWithTag("mdroot").captureToImage().asAndroidBitmap()
        saveEvidence(bitmap, "gfm_table.png")
        val ink = inkedPixelCount(bitmap)
        println("[MarkdownRenderTest] GFM 表格样本: 尺寸=${bitmap.width}x${bitmap.height} 着墨像素=$ink")
        assertTrue("表格样本应把文字真正画出来（着墨像素 $ink）", ink > 500)
    }

    @Test
    fun fencedCodeBlockGetsSyntaxHighlighting() {
        rule.setContent {
            ZCodeTheme(forceDark = true) {
                Column(
                    Modifier
                        .width(360.dp)
                        .background(pageBg)
                        .padding(12.dp)
                        .testTag("mdroot"),
                ) {
                    MarkdownView(codeFixture("kotlin"))
                }
            }
        }
        rule.waitForIdle()

        // 围栏被正确解析：卡片头部回显语言名，代码正文在（语言名丢失时高亮一定不生效）
        assertTextRendered("kotlin")
        assertTextRendered("fun main()")
        dumpTree("围栏代码块样本（kotlin）")

        val shot = rule.onNodeWithTag("mdroot").captureToImage().asAndroidBitmap()
        saveEvidence(shot, "code_kotlin.png")
        val ink = inkedPixelCount(shot)
        val tokens = matchedTokenPixelCount(shot)
        println(
            "[MarkdownRenderTest] 代码块样本: 尺寸=${shot.width}x${shot.height} 着墨像素=$ink " +
                "token 色像素=$tokens 期望色=${TOKEN_COLORS.joinToString { String.format("#%06X", it) }}",
        )

        assertTrue("代码块应把文字真正画出来（着墨像素 $ink）", ink > 200)
        assertTrue(
            "语法高亮的主题 token 色应真的画到屏幕上（命中 $tokens 像素，期望 ≥ 30）",
            tokens >= 30,
        )
    }

    /**
     * 断言某段文字出现在渲染结果里（语义树中存在对应文本节点）。
     *
     * 两点写法上的讲究：
     * - `onAllNodesWithText(useUnmergedTree = true).onFirst()` 而不是 `onNodeWithText`：
     *   表格/代码块这类组合组件既有叶子节点也有合并后的父节点，同一段文本会命中 1 个以上节点
     *   （实测「分类」命中 2 个），`onNodeWithText` 会以 "Expected at most 1 node" 直接失败。
     * - 默认按**子串**匹配：行内代码与链接是整段正文的一部分（整段是一个文本节点），
     *   精确匹配找不到「inline code」这样的片段。
     *
     * 可见性不在这里判：交给下面的位图断言 —— 「真的画到屏幕上了」比「语义节点存在」更接近验收标准。
     */
    private fun assertTextRendered(text: String, substring: Boolean = true) {
        rule.onAllNodesWithText(text, substring = substring, useUnmergedTree = true)
            .onFirst()
            .assertExists()
    }

    /** 把整棵语义树打进测试报告（system-out），作为「渲染出了什么」的原始证据。 */
    private fun dumpTree(tag: String) {
        println("[MarkdownRenderTest] $tag 语义树:\n" + rule.onNodeWithTag("mdroot").printToString())
    }

    /** 统计「着墨」像素数（亮度 ≥ 90）：证明文字真的被画到了位图上，而不只是存在于语义树里。 */
    private fun inkedPixelCount(bitmap: Bitmap): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var count = 0
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            if (maxOf(r, g, b) >= 90) count++
        }
        return count
    }

    /** 命中高亮主题 token 色的像素数（容差 [tolerance] 吸收抗锯齿/子像素渲染带来的微小偏移）。 */
    private fun matchedTokenPixelCount(bitmap: Bitmap, tolerance: Int = 10): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var count = 0
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            for (c in TOKEN_COLORS) {
                if (abs(r - ((c shr 16) and 0xFF)) <= tolerance &&
                    abs(g - ((c shr 8) and 0xFF)) <= tolerance &&
                    abs(b - (c and 0xFF)) <= tolerance
                ) {
                    count++
                    break
                }
            }
        }
        return count
    }

    /**
     * 把捕获到的位图写进**应用内部目录**留证（`files/render-evidence/`）。
     * 不用 externalCacheDir：那个目录在未启动过 App 的设备上并不存在，直接写会失败。
     */
    private fun saveEvidence(bitmap: Bitmap, name: String) {
        runCatching {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            val dir = File(ctx.filesDir, "render-evidence").apply { mkdirs() }
            File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.onFailure { println("[MarkdownRenderTest] 留存截图失败 $name: ${it.message}") }
    }

    private companion object {
        /**
         * `dev.snipme highlights` 的 `SyntaxThemes.atom(dark = true)` 实际用到的 token 色，
         * 由 JVM 侧 `CodeHighlightTest.kotlinCodeProducesColorHighlights` 实测输出（#2BBAC5 青 / #D55FDE 品红 / #89CA78 绿）。
         * 主题色一旦调整，这条断言会失败 —— 那正是我们要知道的（提醒同步更新这里与文档截图）。
         */
        val TOKEN_COLORS = intArrayOf(0x2BBAC5, 0xD55FDE, 0x89CA78)
    }
}
