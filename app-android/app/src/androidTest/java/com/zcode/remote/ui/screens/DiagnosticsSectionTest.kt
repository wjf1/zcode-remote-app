package com.zcode.remote.ui.screens

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.zcode.remote.ui.theme.ZCodeTheme
import com.zcode.remote.util.diag.DiagEventCode
import com.zcode.remote.util.diag.Diagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 诊断区块的仪器化**真实点击**网（模拟器/真机运行；不依赖中继与配对）。
 *
 * 覆盖任务书「前端 UI 变更须真实点击走查」的硬要求：用 Compose 测试框架派发真实触摸事件点按
 * 「导出诊断日志」，断言 ① 点击确实到达处理器（导出请求事件落入环形缓冲）；② 传给导出落盘的
 * 文件名形态正确（时间戳命名、无设备/会话信息）；③ 区块在真实主题下可见可点。
 *
 * 注：导出落盘走系统 SAF 保存对话框，测试用注入点替代（避免拉起外部 DocumentsUI 造成脆弱），
 * 真实「保存到文件」链路在真机走查时确认。
 */
class DiagnosticsSectionTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun exportButtonIsVisibleAndClickable() {
        rule.setContent { ZCodeTheme { DiagnosticsSection() } }
        rule.onNodeWithText("诊断日志").assertIsDisplayed()
        rule
            .onNodeWithTag(TAG_EXPORT_BUTTON)
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertHasClickAction()
    }

    @Test
    fun realClickRecordsExportRequestAndRequestsTimestampedFileName() {
        Diagnostics.ring.clear()
        var requestedName: String? = null
        rule.setContent {
            ZCodeTheme { DiagnosticsSection(exportOverride = { name -> requestedName = name }) }
        }
        rule.onNodeWithTag(TAG_EXPORT_BUTTON).performClick()
        rule.waitForIdle()

        // ① 真实点击到达处理器：导出请求事件已入缓冲
        val events = Diagnostics.ring.snapshot()
        assertTrue(
            "点击后未记录导出请求事件：$events",
            events.any { it.code == DiagEventCode.DIAG_EXPORT_REQUESTED },
        )
        // ② 文件名形态：zcode-diagnostics-<yyyyMMdd-HHmmss>.txt，不含设备/会话信息
        val name = requestedName
        assertTrue("未请求导出文件名", name != null)
        assertTrue("文件名形态不符：$name", Regex("^zcode-diagnostics-\\d{8}-\\d{6}\\.txt$").matches(name!!))
        assertEquals(1, events.count { it.code == DiagEventCode.DIAG_EXPORT_REQUESTED })
    }

    @Test
    fun eventCountShownReflectsRingSize() {
        Diagnostics.ring.clear()
        Diagnostics.record(DiagEventCode.APP_START, "app=test code=1")
        rule.setContent { ZCodeTheme { DiagnosticsSection() } }
        // 文案里带「已记录 1 / <capacity> 条」——用子串匹配避免排版空白差异
        rule.onNodeWithText("已记录 1 /", substring = true).assertIsDisplayed()
    }
}
