package com.zcode.remote.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zcode.remote.relay.ConversationRow
import com.zcode.remote.ui.theme.LocalZCodeDark
import com.zcode.remote.ui.theme.ZCodeDimens
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 工具调用 diff 视图（Sprint 3 第一步，纯客户端零协议）。
 *
 * 数据源是会话行里早已送达的 inputText（Edit/Write/MultiEdit 的 old_string/new_string），
 * 不发任何 RPC、不做任何协议改动 —— 把「看 Agent 干活」从读等宽日志升级成做 review。
 * 性能约束（对齐 Happy docs/mobile-diff-highlighting.md 的预算思想）：纯红绿无语法高亮、
 * 行级 Myers 一次计算、超 4000 行降级整段替换显示；审批卡与正文渲染互不阻塞（本组件
 * 只在工具行展开时组合）。
 */

enum class DiffType { ADD, DEL, CONTEXT }

data class DiffLine(val type: DiffType, val text: String, val oldNo: Int? = null, val newNo: Int? = null)

class ToolDiff(val filePath: String?, val lines: List<DiffLine>) {
    val added: Int get() = lines.count { it.type == DiffType.ADD }
    val removed: Int get() = lines.count { it.type == DiffType.DEL }

    /** 一键复制用的 unified 风格文本（截断 64k 防剪贴板爆炸）。 */
    fun toUnifiedText(): String {
        val header = filePath?.let { "--- a/$it\n+++ b/$it\n" } ?: ""
        val body = lines.joinToString("\n") {
            (when (it.type) {
                DiffType.ADD -> "+"
                DiffType.DEL -> "-"
                DiffType.CONTEXT -> " "
            }) + it.text
        }
        return (header + body).take(64_000)
    }
}

object ToolDiffParser {
    private val json = Json

    /** 写类工具白名单（小写包含匹配；协议工具名 Edit/Write/MultiEdit/NotebookEdit）。 */
    private val WRITE_TOOLS = listOf("edit", "write")

    fun parse(row: ConversationRow): ToolDiff? = parse(row.toolName, row.inputText)

    fun parse(toolName: String?, inputText: String?): ToolDiff? {
        val name = toolName?.lowercase()?.substringAfterLast('.') ?: return null
        if (WRITE_TOOLS.none { name.contains(it) }) return null
        val raw = inputText?.trim()?.takeIf { it.startsWith("{") } ?: return null
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null

        fun str(vararg keys: String): String? =
            keys.firstNotNullOfOrNull { k ->
                obj[k]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            }

        val filePath = str("file_path", "filePath", "path", "notebook_path")
        // 统一成 (old, new) 片段序列：MultiEdit 是 edits 数组，Write 是整文件 content
        val edits = mutableListOf<Pair<String, String>>()
        if (name.contains("multi")) {
            obj["edits"]?.let { runCatching { it.jsonArray }.getOrNull() }?.forEach { e ->
                val o = runCatching { e.jsonObject }.getOrNull() ?: return@forEach
                val os = o["old_string"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() } ?: return@forEach
                val ns = o["new_string"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() } ?: ""
                edits += os to ns
            }
        } else if (name.contains("write")) {
            val content = str("content") ?: return null
            edits += "" to content
        } else {
            val os = str("old_string", "oldText") ?: return null
            val ns = str("new_string", "newText") ?: ""
            edits += os to ns
        }
        if (edits.isEmpty()) return null

        // 逐段 diff 并统一编行号（段间插 "⋯" 视觉分隔行，不占行号）
        val lines = mutableListOf<DiffLine>()
        var oldNo = 0
        var newNo = 0
        edits.forEachIndexed { idx, (oldStr, newStr) ->
            if (idx > 0) lines += DiffLine(DiffType.CONTEXT, "⋯")
            val seg = diffLines(oldStr.lines(), newStr.lines())
            seg.forEach { dl ->
                when (dl.type) {
                    DiffType.DEL -> { oldNo++; lines += dl.copy(oldNo = oldNo) }
                    DiffType.ADD -> { newNo++; lines += dl.copy(newNo = newNo) }
                    DiffType.CONTEXT -> { oldNo++; newNo++; lines += dl.copy(oldNo = oldNo, newNo = newNo) }
                }
            }
        }
        if (lines.none { it.type != DiffType.CONTEXT }) return null
        return ToolDiff(filePath, lines)
    }

    /**
     * 行级 Myers diff（O(ND)）。输入合计超 4000 行时降级为「整删整增」——
     * 保证 UI 永不被巨型 diff 的计算阻塞（Happy 预算思想的本地化）。
     */
    fun diffLines(old: List<String>, new: List<String>): List<DiffLine> {
        // Kotlin 陷阱："".lines() == [""]（单空行）而非空列表——Write 的空文件、
        // Edit 的空 old_string（纯插入）都会踩到；统一规范化为空列表
        val a = if (old == listOf("")) emptyList() else old
        val b = if (new == listOf("")) emptyList() else new
        if (a.isEmpty()) return b.map { DiffLine(DiffType.ADD, it) }
        if (b.isEmpty()) return a.map { DiffLine(DiffType.DEL, it) }
        val n = a.size
        val m = b.size
        if (n + m > 4000) {
            return a.map { DiffLine(DiffType.DEL, it) } + b.map { DiffLine(DiffType.ADD, it) }
        }
        val max = n + m
        val offset = max
        val v = IntArray(2 * max + 2)
        val trace = mutableListOf<IntArray>()
        var foundD = -1
        for (d in 0..max) {
            trace += v.copyOf()
            var k = -d
            while (k <= d) {
                var x = if (k == -d || (k != d && v[k - 1 + offset] < v[k + 1 + offset])) {
                    v[k + 1 + offset]
                } else {
                    v[k - 1 + offset] + 1
                }
                var y = x - k
                while (x < n && y < m && a[x] == b[y]) { x++; y++ }
                v[k + offset] = x
                if (x >= n && y >= m) { foundD = d; break }
                k += 2
            }
            if (foundD >= 0) break
        }
        if (foundD < 0) {  // 理论不可达，防御
            return a.map { DiffLine(DiffType.DEL, it) } + b.map { DiffLine(DiffType.ADD, it) }
        }
        val out = ArrayList<DiffLine>(n + m)
        var x = n
        var y = m
        for (d in foundD downTo 1) {
            // trace[k] = 处理第 k 轮之前的 V 数组快照（= 第 k-1 轮的终态），正是回溯第 d 轮所需
            val vv = trace[d]
            val k = x - y
            val prevK = if (k == -d || (k != d && vv[k - 1 + offset] < vv[k + 1 + offset])) k + 1 else k - 1
            val prevX = vv[prevK + offset]
            val prevY = prevX - prevK
            while (x > prevX && y > prevY) { out += DiffLine(DiffType.CONTEXT, a[x - 1]); x--; y-- }
            if (x == prevX) { out += DiffLine(DiffType.ADD, b[prevY]); y = prevY }
            else { out += DiffLine(DiffType.DEL, a[prevX]); x = prevX }
        }
        while (x > 0 && y > 0 && a[x - 1] == b[y - 1]) { out += DiffLine(DiffType.CONTEXT, a[x - 1]); x--; y-- }
        out.reverse()
        return out
    }
}

/** 红绿 diff 渲染：行号 + 横向滚动 + 长 context 折叠 + 一键复制 unified 文本。 */
@Composable
fun DiffBlock(diff: ToolDiff, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    val haptic = LocalHapticFeedback.current
    var forceExpanded by remember { mutableStateOf(false) }

    // 折叠长段未变更行：连续 CONTEXT > 10 行时中间折叠，留头尾各 3 行
    data class Slice(val from: Int, val to: Int, val collapsedRun: Int? = null)

    val slices = remember(diff.lines, forceExpanded) {
        val result = mutableListOf<Slice>()
        if (forceExpanded) {
            result += Slice(0, diff.lines.size)
        } else {
            var i = 0
            val n = diff.lines.size
            while (i < n) {
                if (diff.lines[i].type != DiffType.CONTEXT) { result += Slice(i, i + 1); i++; continue }
                var j = i
                while (j < n && diff.lines[j].type == DiffType.CONTEXT) j++
                val run = j - i
                if (run > 10) {
                    result += Slice(i, i + 3)
                    result += Slice(j - 3, j, collapsedRun = run - 6)
                } else {
                    result += Slice(i, j)
                }
                i = j
            }
        }
        result
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = RoundedCornerShape(6.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
            ) {
                Text(
                    text = buildString {
                        diff.filePath?.let { append("📄 ${it.substringAfterLast('/')}  ") }
                        append("+${diff.added} −${diff.removed}")
                    },
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        clipboard.setText(AnnotatedString(diff.toUnifiedText()))
                    },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
                    modifier = Modifier.height(24.dp)
                ) {
                    // material-icons-core 无 ContentCopy，用字形替代（避免引入 icons-extended 增包体）
                    Text("⧉ 复制", style = MaterialTheme.typography.labelSmall)
                }
            }
            // 桌面端 diff 的增删比手写值更淡（14%），底色只做提示，靠左侧竖条承担主要辨识
            val addBg = Color(0xFF2E7D32).copy(alpha = 0.14f)
            val delBg = Color(0xFFC62828).copy(alpha = 0.14f)
            // C-4：增删前景色此前硬编码深色主题取色，浅色主题下对比度不足
            // （#66BB6A 在白底仅 2.39:1）—— 浅色改用加深版（#2E7D32 / #C62828，均 ≥5:1）。
            val dark = LocalZCodeDark.current
            val addFg = if (dark) Color(0xFF66BB6A) else Color(0xFF2E7D32)
            val delFg = if (dark) Color(0xFFEF5350) else Color(0xFFC62828)
            // C-4：行号栏原为 onSurfaceVariant@50%（白底仅 2.63:1），去掉 alpha 用量足色
            val gutter = MaterialTheme.colorScheme.onSurfaceVariant

            slices.forEach { slice ->
                if (slice.collapsedRun != null) {
                    Text(
                        text = "⋯ ${slice.collapsedRun} 行未变更，点击展开 ⋯",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { forceExpanded = true }
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                } else {
                    val scroll = rememberScrollState()
                    Column(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(scroll)
                    ) {
                        Column {
                            diff.lines.subList(slice.from, slice.to).forEach { dl ->
                                val bg = when (dl.type) {
                                    DiffType.ADD -> addBg
                                    DiffType.DEL -> delBg
                                    DiffType.CONTEXT -> Color.Transparent
                                }
                                val fg = when (dl.type) {
                                    DiffType.ADD -> addFg
                                    DiffType.DEL -> delFg
                                    DiffType.CONTEXT -> MaterialTheme.colorScheme.onSurface
                                }
                                val sign = when (dl.type) {
                                    DiffType.ADD -> "+"
                                    DiffType.DEL -> "−"
                                    DiffType.CONTEXT -> " "
                                }
                                // 单列行号：新增取新号、删除取老号、上下文取新号 —— 与桌面端一致。
                                val lineNo = when (dl.type) {
                                    DiffType.DEL -> dl.oldNo
                                    else -> dl.newNo ?: dl.oldNo
                                }
                                val accent = when (dl.type) {
                                    DiffType.ADD -> addFg
                                    DiffType.DEL -> delFg
                                    DiffType.CONTEXT -> Color.Transparent
                                }
                                Row(
                                    modifier = Modifier
                                        .background(bg)
                                        .drawBehind {
                                            if (accent != Color.Transparent) {
                                                drawRect(
                                                    color = accent,
                                                    size = Size(
                                                        ZCodeDimens.DiffAccentWidth.toPx(),
                                                        size.height,
                                                    ),
                                                )
                                            }
                                        }
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .width(ZCodeDimens.DiffGutterWidth)
                                            .background(accent.copy(alpha = 0.18f)),
                                        contentAlignment = Alignment.CenterEnd,
                                    ) {
                                        Text(
                                            text = lineNo?.toString().orEmpty(),
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontFamily = FontFamily.Monospace, fontSize = 10.sp
                                            ),
                                            color = gutter,
                                            modifier = Modifier.padding(end = 6.dp, start = 4.dp),
                                        )
                                    }
                                    Text(
                                        text = "$sign${dl.text.take(2000)}",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp
                                        ),
                                        color = fg,
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
