package com.zcode.remote.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zcode.remote.ui.theme.ZCodeTokens

/**
 * 纯 Jetpack Compose 原生 Markdown 渲染组件
 * 对齐官方 ZCode 桌面端排版体验，自动适配深浅双色，支持代码高亮容器、一键复制、列表与引用。
 */
@Composable
fun MarkdownView(
    markdown: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Heading -> {
                    val style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        2 -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        3 -> MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                        else -> MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                    }
                    val topPadding = if (block.level <= 2) 6.dp else 2.dp
                    Text(
                        text = buildInlineAnnotatedString(block.text, textColor),
                        style = style,
                        color = textColor,
                        modifier = Modifier.padding(top = topPadding, bottom = 2.dp)
                    )
                }
                is MarkdownBlock.CodeBlock -> {
                    CodeCard(language = block.language, code = block.code)
                }
                is MarkdownBlock.Quote -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min)
                            .background(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                shape = RoundedCornerShape(4.dp)
                            )
                            .padding(vertical = 6.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(1.5.dp))
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = buildInlineAnnotatedString(block.text, textColor),
                            style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                is MarkdownBlock.UnorderedList -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = "• ",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = buildInlineAnnotatedString(block.text, textColor),
                            style = MaterialTheme.typography.bodyMedium,
                            color = textColor,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                is MarkdownBlock.OrderedList -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = "${block.index}. ",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = buildInlineAnnotatedString(block.text, textColor),
                            style = MaterialTheme.typography.bodyMedium,
                            color = textColor,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                is MarkdownBlock.Divider -> {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
                is MarkdownBlock.Paragraph -> {
                    Text(
                        text = buildInlineAnnotatedString(block.text, textColor),
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor,
                        lineHeight = 22.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun CodeCard(language: String, code: String) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val isDark = isSystemInDarkTheme()
    var copied by remember { mutableStateOf(false) }

    val bgColor = if (isDark) ZCodeTokens.CodeBgDark else ZCodeTokens.CodeBgLight
    val headerColor = if (isDark) ZCodeTokens.CodeHeaderDark else ZCodeTokens.CodeHeaderLight
    val codeColor = if (isDark) Color(0xFFE2E8F0) else Color(0xFF1E293B)
    val langColor = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)

    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        border = CardDefaults.outlinedCardBorder(),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(headerColor)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = language.ifBlank { "code" },
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                    color = langColor,
                    fontFamily = FontFamily.Monospace
                )
                TextButton(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("code", code))
                        copied = true
                        Toast.makeText(context, "代码已复制", Toast.LENGTH_SHORT).show()
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    if (copied) {
                        Icon(Icons.Default.Check, null, tint = ZCodeTokens.StatusOnline, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(2.dp))
                        Text("已复制", style = MaterialTheme.typography.labelSmall, color = ZCodeTokens.StatusOnline)
                    } else {
                        Text("复制", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState)
                    .padding(12.dp)
            ) {
                Text(
                    text = code,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp
                    ),
                    color = codeColor,
                )
            }
        }
    }
}

/**
 * 将行内文本转换为 AnnotatedString，支持粗体、斜体、行内代码与删除线。
 */
@Composable
private fun buildInlineAnnotatedString(text: String, defaultColor: Color): AnnotatedString {
    val inlineCodeBg = MaterialTheme.colorScheme.surfaceVariant
    val inlineCodeColor = MaterialTheme.colorScheme.primary

    return remember(text, defaultColor, inlineCodeBg, inlineCodeColor) {
        buildAnnotatedString {
            var i = 0
            val len = text.length
            while (i < len) {
                // 1. 行内代码 `code`
                if (text[i] == '`') {
                    val nextBacktick = text.indexOf('`', i + 1)
                    if (nextBacktick > i + 1) {
                        pushStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                background = inlineCodeBg,
                                color = inlineCodeColor,
                                fontSize = 13.sp
                            )
                        )
                        append(" " + text.substring(i + 1, nextBacktick) + " ")
                        pop()
                        i = nextBacktick + 1
                        continue
                    }
                }

                // 2. 粗体 **bold**
                if (i + 1 < len && text[i] == '*' && text[i + 1] == '*') {
                    val nextDouble = text.indexOf("**", i + 2)
                    if (nextDouble > i + 2) {
                        pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                        append(text.substring(i + 2, nextDouble))
                        pop()
                        i = nextDouble + 2
                        continue
                    }
                }

                // 3. 删除线 ~~strike~~
                if (i + 1 < len && text[i] == '~' && text[i + 1] == '~') {
                    val nextDouble = text.indexOf("~~", i + 2)
                    if (nextDouble > i + 2) {
                        pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                        append(text.substring(i + 2, nextDouble))
                        pop()
                        i = nextDouble + 2
                        continue
                    }
                }

                // 4. 斜体 *italic*
                if (text[i] == '*' && (i + 1 >= len || text[i + 1] != '*')) {
                    val nextSingle = text.indexOf('*', i + 1)
                    if (nextSingle > i + 1 && (nextSingle + 1 >= len || text[nextSingle + 1] != '*')) {
                        pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                        append(text.substring(i + 1, nextSingle))
                        pop()
                        i = nextSingle + 1
                        continue
                    }
                }

                append(text[i])
                i++
            }
        }
    }
}

private sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class CodeBlock(val language: String, val code: String) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class UnorderedList(val text: String) : MarkdownBlock
    data class OrderedList(val index: String, val text: String) : MarkdownBlock
    data object Divider : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
}

private fun parseMarkdownBlocks(raw: String): List<MarkdownBlock> {
    val lines = raw.lines()
    val blocks = mutableListOf<MarkdownBlock>()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        if (trimmed.startsWith("```")) {
            val lang = trimmed.removePrefix("```").trim()
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            blocks.add(MarkdownBlock.CodeBlock(lang, codeLines.joinToString("\n")))
            i++
            continue
        }

        if (trimmed.isEmpty()) {
            i++
            continue
        }

        if (trimmed.matches(Regex("^[\\-*_]{3,}$"))) {
            blocks.add(MarkdownBlock.Divider)
            i++
            continue
        }

        val headingMatch = Regex("^(#{1,4})\\s+(.+)$").find(trimmed)
        if (headingMatch != null) {
            val level = headingMatch.groupValues[1].length
            val text = headingMatch.groupValues[2].trim()
            blocks.add(MarkdownBlock.Heading(level, text))
            i++
            continue
        }

        if (trimmed.startsWith(">")) {
            val text = trimmed.removePrefix(">").trim()
            blocks.add(MarkdownBlock.Quote(text))
            i++
            continue
        }

        val unorderedMatch = Regex("^[-*]\\s+(.+)$").find(trimmed)
        if (unorderedMatch != null) {
            val text = unorderedMatch.groupValues[1].trim()
            blocks.add(MarkdownBlock.UnorderedList(text))
            i++
            continue
        }

        val orderedMatch = Regex("^(\\d+)[.)]\\s+(.+)$").find(trimmed)
        if (orderedMatch != null) {
            val index = orderedMatch.groupValues[1]
            val text = orderedMatch.groupValues[2].trim()
            blocks.add(MarkdownBlock.OrderedList(index, text))
            i++
            continue
        }

        blocks.add(MarkdownBlock.Paragraph(line))
        i++
    }

    return blocks
}
