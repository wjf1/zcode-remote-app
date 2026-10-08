package com.zcode.remote.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.NoOpImageTransformerImpl
import com.zcode.remote.ui.theme.LocalZCodeDark
import com.zcode.remote.ui.theme.ZCodeDimens
import com.zcode.remote.ui.theme.ZCodeTokens
import com.zcode.remote.ui.theme.ZCodeType
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxThemes

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

    val colors = markdownColor(
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
    val typography = markdownTypography(
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
        code = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = ZCodeType.Sm,
            lineHeight = ZCodeType.CodeLineHeight,
        ),
        inlineCode = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = ZCodeType.Caption,
            lineHeight = 20.sp,
        ),
    )

    val components = markdownComponents(
        codeFence = { model -> HighlightedCodeCard(model) },
        codeBlock = { model -> HighlightedIndentedCodeCard(model) },
        image = { model -> MarkdownImageNotice() },
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
            imageTransformer = NoOpImageTransformerImpl(),
        )
    }
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
private fun CodeBlockCard(code: String, language: String?) {
    val context = LocalContext.current
    val dark = LocalZCodeDark.current
    val bg = if (dark) ZCodeTokens.CodeBgDark else ZCodeTokens.CodeBgLight
    val headerBg = if (dark) ZCodeTokens.CodeHeaderDark else ZCodeTokens.CodeHeaderLight
    val border = if (dark) ZCodeTokens.BorderSubtleDark else ZCodeTokens.BorderSubtleLight
    val shape = RoundedCornerShape(ZCodeDimens.RadiusXl)
    val label = language?.trim()?.lowercase().orEmpty().ifEmpty { "text" }

    var copied by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(shape)
            .background(bg)
            .border(1.dp, border, shape),
    ) {
        Row(
            modifier = Modifier
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
                text = if (copied) "已复制" else "复制",
                style = MaterialTheme.typography.labelSmall,
                color = if (copied) ZCodeTokens.StatusOnline else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(ZCodeDimens.RadiusSm))
                    .clickable {
                        copyToClipboard(context, code)
                        copied = true
                    }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }

        HighlightedCodeText(
            code = code,
            language = language,
            modifier = Modifier
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
private fun HighlightedCodeText(code: String, language: String?, modifier: Modifier = Modifier) {
    val dark = LocalZCodeDark.current

    val result = remember(code, language, dark) {
        val builder = Highlights.Builder().theme(SyntaxThemes.atom(dark))
        // 语言名来自模型输出，可能是不存在的别名，getByName 返回 null 时退化为无高亮
        language?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { SyntaxLanguage.getByName(it) }
            ?.let { builder.language(it) }
        builder.code(code).build()
    }

    val annotated = remember(result) {
        // `getCode()` / `getHighlights()` 是库的普通函数（不是属性），不要写成 result.code。
        val spans = result.getHighlights()
        buildAnnotatedString {
            append(result.getCode())
            spans.filterIsInstance<ColorHighlight>().forEach {
                addStyle(
                    SpanStyle(color = Color(it.rgb)),
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
 * 图片占位。因为全工程没有图片加载器，这里只显示一行提示，
 * 明确告诉用户「这里有一张图，但没有加载」，避免空白区域被误认为渲染失败。
 *
 * **不回显 alt 文本**：取 alt 需要 `findChildOfTypeRecursive` / `getUnescapedTextInNode`，
 * 这两个助手在 0.27.0 里是 `internal`，外部模块不可见；自己遍历 AST 又要把
 * `MarkdownElementTypes` 的节点名当字符串比对，代价高于收益。只保留固定提示。
 */
@Composable
private fun MarkdownImageNotice() {
    val dark = LocalZCodeDark.current
    val border = if (dark) ZCodeTokens.BorderSubtleDark else ZCodeTokens.BorderSubtleLight
    val shape = RoundedCornerShape(ZCodeDimens.RadiusLg)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = ZCodeDimens.GapTight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "图片（未加载）",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(shape)
                .border(1.dp, border, shape)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText("zcode-code", text))
}
