package com.zcode.remote.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.zcode.remote.ui.components.MarkdownView
import com.zcode.remote.ui.theme.ZCodeTheme

/**
 * 仅 debug 变体的 Markdown 渲染探针（真机走查用，**不进 release 包**）。
 *
 * 为什么需要：走查 #13（图片兜底）的真实渲染路径在 API 37 真机上**无法用
 * `createComposeRule` 验**——`androidx.test.espresso` 的 `InputManager.getInstance()`
 * 在该版本已移除，Espresso 注入初始化即抛 `NoSuchMethodException`（HANDOFF §5 已记录，
 * 故 `MarkdownRenderTest` 只在 CI 的 API 35 emulator 跑）。
 *
 * 本探针用真实 `MarkdownView` 渲染受控 Markdown，再由 `uiautomator dump` + 文本断言取证
 * （AGENTS.md「真实浏览器/真机走查 + 可编程断言」，不依赖 Espresso）。
 *
 * 启动：`adb shell am start -n com.zcode.remote/.debug.MarkdownProbeActivity`
 */
class MarkdownProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ZCodeTheme(forceDark = true) {
                Surface(Modifier.fillMaxSize()) {
                    ProbeContent()
                }
            }
        }
    }

    @Composable
    private fun ProbeContent() {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp)
                    .testTag("mdprobe"),
        ) {
            ProbeCase("http 图片（应给 复制链接 + 打开）", "![示意图](https://example.com/a.png)")
            ProbeCase("非 http 图片（应给 复制链接、不给 打开）", "![x](ftp://example.com/a.png)")
            ProbeCase("行内图片（段落中）", "看这张 ![图](https://e.com/i.png) 就够了")
            ProbeCase("代码块内的伪图片（不应给兜底）", "```md\n![不是图片](https://e.com/nope.png)\n```")
            ProbeCase("纯文字（不应给兜底）", "这是一段没有图片的普通文字。")
        }
    }

    @Composable
    private fun ProbeCase(
        label: String,
        markdown: String,
    ) {
        Text(text = "CASE: $label")
        MarkdownView(markdown)
    }
}
