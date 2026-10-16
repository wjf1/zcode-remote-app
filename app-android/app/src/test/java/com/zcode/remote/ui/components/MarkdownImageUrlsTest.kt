package com.zcode.remote.ui.components

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片 URL 抽取的**纯 JVM** 回归网（走查 #13 的真修复）。
 *
 * 背景（beta48 教训）：`#13` 最初把图片兜底挂在 `markdownComponents(image = { ... })`，
 * 而 `multiplatform-markdown-renderer 0.27.0` 的 `Markdown.kt` 只对 `MARKDOWN_FILE`
 * 的**顶层子节点**调 `handleElement`，`PARAGRAPH` 被 `components.paragraph` 消费后不再下钻——
 * 而 `![alt](url)` 恒为 `MARKDOWN_FILE > PARAGRAPH > IMAGE`。于是那个 lambda 在真实文档里
 * **永不触发**，兜底等于死代码；该缺陷在本地只跑过编译、未跑仪器化用例，直到 CI 首次真正
 * 执行 `MarkdownRenderTest` 才暴露（2 个用例红）。
 *
 * 本测试把「图片落在哪、URL 是什么」用**库自带解析器**钉死（纯 JVM，无需 Robolectric/设备），
 * 修复后的实现据此在**段落级**注入兜底；顺带锁定「URL 来自 AST 节点、不是整篇文档」这一关键点。
 */
class MarkdownImageUrlsTest {
    private val flavour = GFMFlavourDescriptor()

    private fun parse(md: String): ASTNode = MarkdownParser(flavour).buildMarkdownTreeFromString(md)

    private fun urlsOf(md: String): List<String> = imageUrlsIn(parse(md), md)

    @Test
    fun extractsHttpsImageUrlFromParagraph() {
        val md = "![示意图](https://example.com/a.png)"
        assertEquals(listOf("https://example.com/a.png"), urlsOf(md))
    }

    @Test
    fun extractsNonHttpImageUrlToo() {
        // 非 http(s) 的 URL 也要抽出来：是否给「打开」入口由 isAllowedLinkScheme 决定，
        // 「复制链接」对所有 URL 都提供（两件事不要混）。
        assertEquals(listOf("ftp://example.com/a.png"), urlsOf("![x](ftp://example.com/a.png)"))
    }

    @Test
    fun extractsInlineImageInMiddleOfText() {
        val md = "看这张 ![图](https://e.com/i.png) 就够了"
        assertEquals(listOf("https://e.com/i.png"), urlsOf(md))
    }

    @Test
    fun preservesDocumentOrderForMultipleImages() {
        val md =
            """
            ![一](https://e.com/1.png)

            中间文字 ![二](https://e.com/2.png) 尾部

            ![](https://e.com/3.png)
            """.trimIndent()
        assertEquals(
            listOf("https://e.com/1.png", "https://e.com/2.png", "https://e.com/3.png"),
            urlsOf(md),
        )
    }

    @Test
    fun ignoresImagesInsideFencedCodeBlock() {
        // 代码块里的 ![..](..) 是字面文本，不得被当成图片（库的 AST 也不产出 IMAGE 节点）。
        val md =
            """
            正文。

            ```md
            ![不是图片](https://e.com/nope.png)
            ```
            """.trimIndent()
        assertTrue("代码块内的伪图片不应被抽取：${urlsOf(md)}", urlsOf(md).isEmpty())
    }

    @Test
    fun returnsEmptyWhenNoImage() {
        assertTrue(urlsOf("纯文字，没有任何图片。").isEmpty())
        assertTrue(urlsOf("[普通链接](https://e.com/x)").isEmpty())
    }

    /**
     * 关键回归断言：图片 URL 必须取自 **IMAGE 节点的 LINK_DESTINATION 子节点**，
     * 而不是 `MarkdownComponentModel.content`（那是**整篇文档**）。
     *
     * 这正是旧实现把 `model.content` 当 URL 传进兜底所踩的坑——按老写法，
     * 「URL」会变成整篇 Markdown 原文。此处用一段带前后文的文档把它钉死。
     */
    @Test
    fun urlIsNodeSliceNotWholeDocument() {
        val md = "前缀 ![alt](https://e.com/only.png) 后缀"
        val urls = urlsOf(md)
        assertEquals(listOf("https://e.com/only.png"), urls)
        assertTrue("URL 不应等于整篇文档原文", urls.none { it == md })
        assertTrue("URL 不应包含标记或前后文", urls.none { it.contains("![") || it.contains("后缀") })
    }

    /** AST 形状护栏：图片恒被 PARAGRAPH 包裹——这正是 `components.image` 永不可达的根据。 */
    @Test
    fun imageIsAlwaysNestedInParagraph() {
        val md = "![示意图](https://example.com/a.png)"

        fun find(
            n: ASTNode,
            type: String,
        ): ASTNode? {
            if (n.type == MarkdownElementTypes.IMAGE && type == "IMAGE") return n
            for (c in n.children) find(c, type)?.let { return it }
            return null
        }

        val image = find(parse(md), "IMAGE")
        assertTrue("解析器应产出 IMAGE 节点（否则整条兜底路径都无从谈起）", image != null)
        assertEquals(
            "IMAGE 的父节点应是 PARAGRAPH（库只在顶层分发，故段落级注入才是正确挂钩点）",
            MarkdownElementTypes.PARAGRAPH,
            image!!.parent?.type,
        )
    }
}
