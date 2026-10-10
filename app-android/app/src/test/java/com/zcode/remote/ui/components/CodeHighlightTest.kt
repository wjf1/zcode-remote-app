package com.zcode.remote.ui.components

import com.zcode.remote.ui.components.opaqueHighlightArgb
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxThemes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 代码块语法高亮的**纯 JVM** 回归网（与 [MarkdownRenderTest] 互补）。
 *
 * 为什么单开一条：模拟器上的渲染断言只能看到「最终像素」，一旦没出现高亮色，
 * 无法区分是「语言名解析失败」「主题没给颜色」还是「截图/布局没覆盖到」。本测试直接调用
 * `MarkdownView` 里同一条高亮流水线（Highlights + SyntaxThemes.atom + SyntaxLanguage.getByName），
 * 把这三层逐一钉住；模拟器那边只负责证明「颜色真的画到了屏幕上」。
 */
class CodeHighlightTest {

    private val code = """
        // 注释 comment
        fun main() {
            val message = "hello zcode"
            println(message)
        }
    """.trimIndent()

    private fun highlight(language: String?) =
        Highlights.Builder()
            .theme(SyntaxThemes.atom(true))
            .also { builder -> language?.let { SyntaxLanguage.getByName(it)?.let(builder::language) } }
            .code(code)
            .build()

    @Test
    fun kotlinLanguageNameResolves() {
        // 围栏里的语言名是小写（```kotlin）；库内部按小写比对，解析不到就会静默退化为泛化高亮。
        // 注意 getNames() 返回的是枚举名的原样大小写（Kotlin / Go / Php…），不是小写别名。
        assertNotNull("SyntaxLanguage.getByName(\"kotlin\") 应能解析到 KOTLIN", SyntaxLanguage.getByName("kotlin"))
        assertTrue(
            "语言名清单应包含 Kotlin（当前：${SyntaxLanguage.getNames()}）",
            SyntaxLanguage.getNames().contains("Kotlin"),
        )
    }

    @Test
    fun kotlinCodeProducesColorHighlights() {
        val spans = highlight("kotlin").getHighlights().filterIsInstance<ColorHighlight>()
        val colors = spans.map { it.rgb }.toSet()
        println("[CodeHighlightTest] kotlin 高亮片段=${spans.size} 颜色数=${colors.size} 颜色=${colors.map { String.format("#%06X", it) }}")
        assertTrue("kotlin 代码应产出 ≥3 段颜色高亮（实际 ${spans.size}）", spans.size >= 3)
        assertTrue("高亮应至少用到 3 种不同颜色（实际 ${colors.size}）", colors.size >= 3)
    }

    /**
     * 语言名不可识别时的真实行为：**不是「不亮」，而是回落到 DEFAULT 泛化高亮**（仍会给字符串/
     * 注释/数字上色）。这条结论推翻了模拟器测试里原本「未知语言 = 无高亮对照组」的假设 ——
     * 那里已改成直接断言主题 token 色真的被画到屏幕上（见 MarkdownRenderTest）。
     */
    @Test
    fun unknownLanguageFallsBackToGenericHighlighting() {
        assertNull("getByName(\"nosuchlang\") 应为 null", SyntaxLanguage.getByName("nosuchlang"))
        val spans = highlight("nosuchlang").getHighlights().filterIsInstance<ColorHighlight>()
        println("[CodeHighlightTest] nosuchlang 高亮片段=${spans.size}（回落 DEFAULT）")
        assertTrue("未知语言应回落到泛化高亮而不是无高亮（实际 ${spans.size} 段）", spans.isNotEmpty())
    }

    /**
     * 库给的是纯 RGB，Compose 的 `Color(Int)` 按 ARGB 解释 —— 不补 alpha 就是**全透明**，
     * 症状是「被高亮的片段整段看不见」。这条单测把转换函数钉死（真机/模拟器侧的端到端证据见
     * `MarkdownRenderTest.fencedCodeBlockGetsSyntaxHighlighting`，该缺陷正是被它抓出来的）。
     */
    @Test
    fun highlightColorConversionKeepsRgbAndForcesOpaqueAlpha() {
        // 实测 token 色（SyntaxThemes.atom(dark=true)）
        val cyan = 0x2BBAC5
        val magenta = 0xD55FDE
        assertEquals("alpha 必须补成 0xFF", 0xFF, (opaqueHighlightArgb(cyan) ushr 24) and 0xFF)
        assertEquals("RGB 位必须原样保留（青）", "#2BBAC5", String.format("#%06X", opaqueHighlightArgb(cyan) and 0xFFFFFF))
        assertEquals("RGB 位必须原样保留（品红）", "#D55FDE", String.format("#%06X", opaqueHighlightArgb(magenta) and 0xFFFFFF))
        // 反例锚点：库返回的原始值按 ARGB 解释就是全透明，这正是缺陷成因
        assertEquals("原始 rgb 的 alpha 位应为 0（说明它不含 alpha）", 0, (cyan ushr 24) and 0xFF)
    }
}
