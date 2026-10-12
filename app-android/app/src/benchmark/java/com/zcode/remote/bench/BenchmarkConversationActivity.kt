package com.zcode.remote.bench

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.zcode.remote.relay.ConversationChannel
import com.zcode.remote.ui.screens.ConversationAction
import com.zcode.remote.ui.screens.ConversationScreen
import com.zcode.remote.ui.screens.ConversationUiState
import com.zcode.remote.ui.theme.ZCodeTheme
import kotlinx.coroutines.delay

/**
 * Macrobenchmark 宿主 Activity（仅存在于 `benchmark` 构建变体，不进 debug/release 包）。
 *
 * 用 fixture 长会话数据直接渲染**真实的** [ConversationScreen]（不是仿制布局），
 * 让 `:benchmark` 模块在无中继、无配对、无网络的前提下量测：冷启动到首帧、
 * 长列表滚动、流式追加三类负载下的真实帧时。
 *
 * 参数经 `am start -W "<intent-uri>"` 传入（macrobenchmark 的 `startActivityAndWait`
 * 走 `Intent.toUri(URI_INTENT_SCHEME)`，extras 以 `i.`/`l.` 前缀编码）：
 * - [EXTRA_ROWS]：初始行数（默认 [BenchmarkLongConversation.DEFAULT_ROWS]）；
 * - [EXTRA_APPEND_INTERVAL_MS]：>0 时按该间隔持续追加行（模拟流式增量），0 = 静态。
 *
 * `testTagsAsResourceId` 把 Compose testTag 暴露为 resource-id，供 UiAutomator
 * 定位消息流列表（`conv-timeline-list`）；只在 benchmark 变体的宿主里开启，不影响生产包。
 */
class BenchmarkConversationActivity : ComponentActivity() {
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialRows = intent.getIntExtra(EXTRA_ROWS, BenchmarkLongConversation.DEFAULT_ROWS)
        val appendIntervalMs = intent.getLongExtra(EXTRA_APPEND_INTERVAL_MS, 0L)
        // 标题固定用「起始行数」供 UiAutomator 断言 extras 是否真的传进来了（默认参数 = 静态
        // 长会话；流式模式标题不同 → benchmark 的 setupBlock 用它证明 am start 的 extras 生效，
        // 防止「extras 静默丢失 → 流式场景退化成静态」的假绿）。
        val title =
            if (appendIntervalMs > 0L) {
                "Macrobenchmark 流式会话（起始 $initialRows 行）"
            } else {
                "Macrobenchmark 长会话（起始 $initialRows 行）"
            }

        setContent {
            ZCodeTheme(forceDark = true) {
                var rows by remember { mutableStateOf(BenchmarkLongConversation.rows(initialRows)) }
                if (appendIntervalMs > 0L) {
                    LaunchedEffect(Unit) {
                        var nextRowId = initialRows
                        while (true) {
                            delay(appendIntervalMs)
                            nextRowId += 1
                            rows = rows + BenchmarkLongConversation.appendRow(nextRowId)
                        }
                    }
                }
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .semantics { testTagsAsResourceId = true },
                ) {
                    ConversationScreen(
                        ui =
                            ConversationUiState(
                                title = title,
                                status = ConversationChannel.Status.Idle,
                                meta =
                                    ConversationChannel.ConversationMeta(
                                        phase = "executing",
                                        totalCount = rows.size,
                                        model = "benchmark/long-conversation",
                                    ),
                                rows = rows,
                            ),
                        actions = ConversationAction(onBack = {}),
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_ROWS = "rows"
        const val EXTRA_APPEND_INTERVAL_MS = "appendIntervalMs"
    }
}
