package com.zcode.remote.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownBlockQuote
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownParagraph
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.model.PlaceholderConfig
import com.zcode.remote.R
import com.zcode.remote.ui.theme.LocalZCodeDark
import com.zcode.remote.ui.theme.ZCodeDimens
import com.zcode.remote.ui.theme.ZCodeTokens
import com.zcode.remote.ui.theme.ZCodeType
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxThemes
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode

/**
 * `dev.snipme highlights` 的 `ColorHighlight.rgb` 是**纯 RGB**（如 `0x2BBAC5`，没有 alpha 位），
 * 而 Compose 的 `Color(Int)` 按 **ARGB** 解释。直接把 `rgb` 传进 `Color(...)` 会得到 alpha=0x00
 * 的**完全透明**色 —— 症状不是「高亮没颜色」，而是**被高亮的片段整个看不见**
 * （关键字/字符串/注释凭空消失，只剩标识符与标点）。
 *
 * 2026-10-09 由模拟器仪器化测试（`MarkdownRenderTest.fencedCodeBlockGetsSyntaxHighlighting`）
 * 抓出：捕获到位图里着墨像素正常但主题 token 色命中 0，截图可见 `fun` / `val` / 字符串整段缺失。
 * 这里统一补上不透明 alpha，纯函数以便单测兜住（`CodeHighlightTest`）。
 */
internal fun opaqueHighlightArgb(rgb: Int): Int = rgb or 0xFF000000.toInt()

/**
 * Markdown 正文渲染。签名与旧的手写解析版完全一致，调用点无需改动。
 *
 * 内部换成 GFM 完整实现（表格 / 任务列表 / 嵌套列表 / 链接 / 行内 HTML），
 * 代码块走真语法高亮。安全边界：
 * - **不加载外链图片**：显式传入 `NoOpImageTransformerImpl`，且全工程没有图片加载器（无 Coil），
 *   模型输出里的任意 URL 都不会引发本机出站请求。[MarkdownImageNotice] 只渲染一行占位提示。
 * - **链接 scheme 白名单**：库内部走 `LocalUriHandler`，此处用 [rememberSafeUriHandler] 包裹，
 *   仅放行 http/https。
 * - 不做数学公式与 Mermaid（本轮范围红线）。
 */
@Composable
fun MarkdownView(
    markdown: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val dark = LocalZCodeDark.current
    val borderColor = if (dark) ZCodeTokens.BorderSubtleDark else ZCodeTokens.BorderSubtleLight
    val inlineCodeBg = if (dark) ZCodeTokens.OverlaySurfaceHoverDark else ZCodeTokens.OverlaySurfaceHoverLight

    val colors =
        markdownColor(
            text = textColor,
            codeText = textColor,
            inlineCodeText = textColor,
            linkText = MaterialTheme.colorScheme.primary,
            // 代码块自带卡片底色，这里把库内底色置空，避免叠出双层不规则色块
            codeBackground = Color.Transparent,
            inlineCodeBackground = inlineCodeBg,
            dividerColor = borderColor,
        )

    val body = TextStyle(fontSize = ZCodeType.Base, lineHeight = ZCodeType.BodyLineHeight)
    val typography =
        markdownTypography(
            h1 = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = ZCodeType.SemiBold),
            h2 = TextStyle(fontSize = ZCodeType.Xl, lineHeight = 26.sp, fontWeight = ZCodeType.SemiBold),
            h3 = TextStyle(fontSize = ZCodeType.Lg, lineHeight = 24.sp, fontWeight = ZCodeType.SemiBold),
            h4 = TextStyle(fontSize = ZCodeType.Base, lineHeight = 22.sp, fontWeight = ZCodeType.SemiBold),
            h5 = TextStyle(fontSize = ZCodeType.Caption, lineHeight = 20.sp, fontWeight = ZCodeType.SemiBold),
            h6 = TextStyle(fontSize = ZCodeType.Sm, lineHeight = 18.sp, fontWeight = ZCodeType.SemiBold),
            text = body,
            paragraph = body,
            ordered = body,
            bullet = body,
            list = body,
            quote = body,
            link = body.copy(textDecoration = TextDecoration.Underline),
            code =
                TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = ZCodeType.Sm,
                    lineHeight = ZCodeType.CodeLineHeight,
                ),
            inlineCode =
                TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = ZCodeType.Caption,
                    lineHeight = 20.sp,
                ),
        )

    val components =
        markdownComponents(
            codeFence = { model -> HighlightedCodeCard(model) },
            codeBlock = { model -> HighlightedIndentedCodeCard(model) },
            // 图片兜底走**段落级**注入，而不是 `image = {}`：库的 `handleElement` 只对
            // `MARKDOWN_FILE` 的顶层子节点分发，而 `![alt](url)` 经解析器实测恒为
            // `MARKDOWN_FILE > PARAGRAPH > IMAGE`（`PARAGRAPH` 被 `components.paragraph`
            // 消费后不再下钻）——故 `components.image` 在真实文档里**永不触发**。
            // 段落（含列表项内的段落）与引用块才是图片真正落点。
            paragraph = { model ->
                MarkdownParagraph(model.content, model.node, style = model.typography.paragraph)
                imageUrlsIn(model.node, model.content).forEach { MarkdownImageNotice(it) }
            },
            blockQuote = { model ->
                MarkdownBlockQuote(model.content, model.node, style = model.typography.quote)
                imageUrlsIn(model.node, model.content).forEach { MarkdownImageNotice(it) }
            },
            // 顶格图片（IMAGE 直接挂在 MARKDOWN_FILE 下）的兜底；与段落路径不会重复渲染。
            // 注意：`model.content` 是**整篇文档**而非图片 URL，URL 必须从 AST 节点取。
            image = { model -> imageUrlsIn(model.node, model.content).forEach { MarkdownImageNotice(it) } },
        )

    val uriHandler = rememberSafeUriHandler()

    CompositionLocalProvider(LocalUriHandler provides uriHandler) {
        Markdown(
            content = markdown,
            colors = colors,
            typography = typography,
            // 库默认 modifier 是 fillMaxSize()，不覆盖会把消息行撑满整屏
            modifier = modifier.fillMaxWidth(),
            components = components,
            imageTransformer = ZeroSizeImageTransformer,
        )
    }
}

/**
 * 零尺寸图片占位。
 *
 * 库对行内图片会预留一个默认约 180dp 的占位块（`ImageTransformer.placeholderConfig`），
 * 由于本工程**不加载任何外链图片**（`transform` 恒返回 null），那块位置会留成一块空白。
 * 图片说明由段落下方的 [MarkdownImageNotice] 单独给出，故这里把占位收敛为 0，
 * 既不留空洞，也不改变「零出站请求」的安全决策。
 */
private object ZeroSizeImageTransformer : ImageTransformer {
    @Composable
    override fun transform(link: String): ImageData? = null

    override fun placeholderConfig(
        density: Density,
        containerSize: Size,
        intrinsicImageSize: Size,
    ): PlaceholderConfig = PlaceholderConfig(Size.Zero, animate = false)
}

/**
 * 取出给定 AST 子树里所有图片的 URL（按文档顺序）。
 *
 * 库的 `findChildOfTypeRecursive` / `getUnescapedTextInNode` 是 `internal`、外部不可见，
 * 故这里直接用 `org.intellij.markdown` 的公开 API 自遍历：IMAGE → INLINE_LINK →
 * LINK_DESTINATION，按 span 从原文切片即为 URL（与库内 `appendInlineContent` 取的是同一段）。
 *
 * `internal` 而非 `private`：走查 #13 的兜底路径（`components.image` 永不可达）是真实踩过的坑，
 * 用 JVM 单测（`MarkdownImageUrlsTest`，无需 Robolectric/设备）钉死解析结果。
 */
internal fun imageUrlsIn(
    node: ASTNode,
    content: String,
): List<String> {
    val urls = mutableListOf<String>()

    fun walk(n: ASTNode) {
        if (n.type == MarkdownElementTypes.IMAGE) {
            n.children
                .firstOrNull { it.type == MarkdownElementTypes.INLINE_LINK }
                ?.children
                ?.firstOrNull { it.type == MarkdownElementTypes.LINK_DESTINATION }
                ?.let { dest ->
                    if (dest.endOffset <= content.length && dest.startOffset >= 0) {
                        content
                            .substring(dest.startOffset, dest.endOffset)
                            .takeIf { it.isNotBlank() }
                            ?.let(urls::add)
                    }
                }
        }
        n.children.forEach(::walk)
    }
    walk(node)
    return urls
}

/**
 * 代码块渲染：库负责把围栏内容与语言名解析出来，外观由 [CodeBlockCard] 决定。
 *
 * 对齐桌面端：圆角 12dp、独立卡片底色、头部左侧语言名 + 右侧复制。
 * **有意偏离**：桌面端代码块有 `min-height: 200px`（为流式追加时稳定高度），
 * 手机屏宽下这会让两行代码也占去半屏，故不加限高。
 */
@Composable
private fun HighlightedCodeCard(model: MarkdownComponentModel) {
    MarkdownCodeFence(model.content, model.node) { code, language ->
        CodeBlockCard(code = code, language = language)
    }
}

/** 缩进式代码块（无围栏语言名）：沿用同一张卡片，语言标记回落为 text。 */
@Composable
private fun HighlightedIndentedCodeCard(model: MarkdownComponentModel) {
    MarkdownCodeBlock(model.content, model.node) { code, language ->
        CodeBlockCard(code = code, language = language)
    }
}

@Composable
private fun CodeBlockCard(
    code: String,
    language: String?,
) {
    val context = LocalContext.current
    val dark = LocalZCodeDark.current
    val bg = if (dark) ZCodeTokens.CodeBgDark else ZCodeTokens.CodeBgLight
    val headerBg = if (dark) ZCodeTokens.CodeHeaderDark else ZCodeTokens.CodeHeaderLight
    val border = if (dark) ZCodeTokens.BorderSubtleDark else ZCodeTokens.BorderSubtleLight
    val shape = RoundedCornerShape(ZCodeDimens.RadiusXl)
    val label =
        language
            ?.trim()
            ?.lowercase()
            .orEmpty()
            .ifEmpty { "text" }

    var copied by remember { mutableStateOf(false) }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .clip(shape)
                .background(bg)
                .border(1.dp, border, shape),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(headerBg)
                    .padding(horizontal = ZCodeDimens.PadBlock, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = ZCodeType.Sm),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (copied) stringResource(R.string.common_copied) else stringResource(R.string.common_copy),
                style = MaterialTheme.typography.labelSmall,
                color = if (copied) ZCodeTokens.StatusOnline else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(ZCodeDimens.RadiusSm))
                        .clickable {
                            copyToClipboard(context, code)
                            copied = true
                        }.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }

        HighlightedCodeText(
            code = code,
            language = language,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ZCodeDimens.PadBlock, vertical = 10.dp),
        )
    }
}

/**
 * 高亮代码正文。
 *
 * 没有直接用库的 `MarkdownHighlightedCode`：它自带一层圆角底色与上下外边距，
 * 与 [CodeBlockCard] 的卡片底色叠加会出现双层色块。这里只复用它的算法
 * （同款 `Highlights` + `ColorHighlight` → `SpanStyle`），外观完全自控。
 */
@Composable
private fun HighlightedCodeText(
    code: String,
    language: String?,
    modifier: Modifier = Modifier,
) {
    val dark = LocalZCodeDark.current

    val result =
        remember(code, language, dark) {
            val builder = Highlights.Builder().theme(SyntaxThemes.atom(dark))
            // 语言名来自模型输出，可能是不存在的别名，getByName 返回 null 时退化为无高亮
            language
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { SyntaxLanguage.getByName(it) }
                ?.let { builder.language(it) }
            builder.code(code).build()
        }

    val annotated =
        remember(result) {
            // `getCode()` / `getHighlights()` 是库的普通函数（不是属性），不要写成 result.code。
            val spans = result.getHighlights()
            buildAnnotatedString {
                append(result.getCode())
                spans.filterIsInstance<ColorHighlight>().forEach {
                    addStyle(
                        // 必须补 alpha：库给的是纯 RGB，直接 Color(it.rgb) 会是全透明（字都看不见）
                        SpanStyle(color = Color(opaqueHighlightArgb(it.rgb))),
                        start = it.location.start,
                        end = it.location.end,
                    )
                }
                spans.filterIsInstance<BoldHighlight>().forEach {
                    addStyle(
                        SpanStyle(fontWeight = FontWeight.Bold),
                        start = it.location.start,
                        end = it.location.end,
                    )
                }
            }
        }

    Text(
        text = annotated,
        modifier = modifier.horizontalScroll(rememberScrollState()),
        color = if (dark) Color(0xFFE6E6E6) else Color(0xFF1F2328),
        fontFamily = FontFamily.Monospace,
        fontSize = ZCodeType.Sm,
        lineHeight = ZCodeType.CodeLineHeight,
        softWrap = false,
    )
}

/**
 * 图片占位。因为全工程没有图片加载器（不发起任何外链请求），这里只显示一行提示 +
 * 两个兜底动作，明确告诉用户「这里有一张图，但没有加载」，并用**复制链接 / 浏览器打开**
 * 让有意查看的用户自己去看（不改变「App 不加载外链图片」的安全决策）。
 *
 * **URL 来源**：image lambda 的入参 `MarkdownComponentModel.getContent()` 即图片 URL
 * （库 `ImageTransformer.transform(url)` 印证；0.27.0 的 `content` 字段是 public）。
 * 打开动作走 `isAllowedLinkScheme` 白名单 + [rememberSafeUriHandler]，
 * 与正文链接同一条安全路径——非 http(s) 一律拦下。
 *
 * **仍不回显 alt 文本**：取 alt 需要 `findChildOfTypeRecursive` / `getUnescapedTextInNode`，
 * 这两个助手在 0.27.0 里是 `internal`，外部模块不可见；自己遍历 AST 代价高于收益。
 */
@Composable
private fun MarkdownImageNotice(url: String) {
    val dark = LocalZCodeDark.current
    val border = if (dark) ZCodeTokens.BorderSubtleDark else ZCodeTokens.BorderSubtleLight
    val shape = RoundedCornerShape(ZCodeDimens.RadiusLg)
    val context = LocalContext.current
    val uriHandler = rememberSafeUriHandler()
    val safe = isAllowedLinkScheme(url)

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = ZCodeDimens.GapTight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZCodeDimens.GapTight),
    ) {
        Text(
            text = stringResource(R.string.markdown_image_notice),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier
                    .clip(shape)
                    .border(1.dp, border, shape)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
        )
        // 兜底动作：链接非空才显示（防御空 URL；安全 scheme 判断与正文链接同源）
        if (url.isNotBlank()) {
            TextButton(
                onClick = {
                    copyToClipboard(context, url)
                    Toast.makeText(context, context.getString(R.string.markdown_image_link_copied), Toast.LENGTH_SHORT).show()
                },
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                modifier = Modifier.height(28.dp),
            ) {
                Text(stringResource(R.string.markdown_image_copy_link), style = MaterialTheme.typography.labelSmall)
            }
            // 仅白名单 scheme 给「打开」入口（非 http(s) 无意义且不安全）
            if (safe) {
                TextButton(
                    onClick = { uriHandler.openUri(url) },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    modifier = Modifier.height(28.dp),
                ) {
                    Text(stringResource(R.string.markdown_image_open), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private fun copyToClipboard(
    context: Context,
    text: String,
) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText("zcode-code", text))
}
