package com.zcode.remote.benchmark

import android.content.Intent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 长会话 Macrobenchmark（§11.8 第二层门禁最后一项）。
 *
 * 把 2026-10-10 真机实测得到的「长会话（1100+ 行）滚动 + 流式期 1336 帧 0 janky」
 * 固化为可重复、可对比的基准：宿主 Activity 用 fixture 长会话渲染真实 [com.zcode.remote.ui.screens.ConversationScreen]，
 * 三个场景分别覆盖「打开长会话」「长列表滚动」「流式追加」三类真实负载。
 *
 * 口径说明（防误读）：
 * - [CompilationMode.None]：不预编译、每轮重置编译状态——数值是「未 AOT 编译」的最坏情况，
 *   **绝对值不可与真机已编译包直接比较**，只用于同一口径下的趋势比对（性能回退探测）。
 * - 模拟器（CI）帧时序与真机不同源，数值仅作量级回归信号；真机口径以人工跑 [MacrobenchmarkRule]
 *   并把结果交给 `tools/bench_check.py` 比对为准（见 README/HANDOVER 的运行说明）。
 */
@RunWith(AndroidJUnit4::class)
class LongConversationBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    /**
     * 场景 1：从桌面冷启动打开 1200 行长会话 —— 测启动耗时与首帧渲染帧时
     * （真机上的原始观测场景：「长会话 1100+ 行打开即渲染」）。
     */
    @Test
    fun openLongConversation() =
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
            iterations = ITERATIONS,
            startupMode = StartupMode.COLD,
            compilationMode = CompilationMode.None(),
            setupBlock = { pressHome() },
        ) {
            startActivityAndWait(hostIntent(rows = LONG_ROWS))
            device.waitForIdle()
            requireTitle("长会话（起始 $LONG_ROWS 行）")
        }

    /**
     * 场景 2：长会话上下来回滚动 —— 测滚动期帧时（长列表回收/复用的真实路径）。
     * 启动放在 setupBlock（不计入测量），measureBlock 只做滚动。
     */
    @Test
    fun scrollLongConversation() =
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            iterations = ITERATIONS,
            startupMode = StartupMode.WARM,
            compilationMode = CompilationMode.None(),
            setupBlock = {
                pressHome()
                startActivityAndWait(hostIntent(rows = LONG_ROWS))
                device.waitForIdle()
                requireTitle("长会话（起始 $LONG_ROWS 行）")
            },
        ) {
            val list =
                requireNotNull(device.wait(Until.findObject(By.res(TIMELINE_RES_ID)), UI_TIMEOUT_MS)) {
                    "未找到消息流列表（$TIMELINE_RES_ID）——testTag 未暴露为 resource-id？"
                }
            list.fling(Direction.UP)
            device.waitForIdle()
            list.fling(Direction.DOWN)
            device.waitForIdle()
        }

    /**
     * 场景 3：流式追加（每 33ms 一行、持续 2s 窗口）——测流式渲染期帧时。
     * 流式期 UI 不进入 idle，measureBlock 用固定窗口采样（App 侧持续重组）。
     */
    @Test
    fun streamingAppendLongConversation() =
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            iterations = ITERATIONS,
            startupMode = StartupMode.WARM,
            compilationMode = CompilationMode.None(),
            setupBlock = {
                pressHome()
                startActivityAndWait(hostIntent(rows = STREAM_START_ROWS, appendIntervalMs = STREAM_INTERVAL_MS))
                // 流式期 UI 不进入 idle，故不等 waitForIdle；标题断言同时证明两件事：
                // 页面已就绪 + `am start` 的 extras 真的传进来了（否则会退化为静态 1200 行而「假绿」）。
                requireTitle("流式会话（起始 $STREAM_START_ROWS 行）")
            },
        ) {
            Thread.sleep(STREAM_WINDOW_MS)
        }

    /**
     * 断言宿主页面标题出现 [text]：证明 Activity 就绪且 am start 的 extras 生效。
     * 失败信息明确指向「extras 未生效 → 场景退化」，避免出现静默假绿。
     */
    private fun MacrobenchmarkScope.requireTitle(text: String) {
        requireNotNull(device.wait(Until.findObject(By.textContains(text)), UI_TIMEOUT_MS)) {
            "宿主标题未出现「$text」——Activity 未就绪，或 am start 的 extras 未生效（场景会退化）"
        }
    }

    /** 宿主 Intent：extras 经 `Intent.toUri(URI_INTENT_SCHEME)` 编码进 `am start -W`（i./l. 前缀）。 */
    private fun hostIntent(
        rows: Int,
        appendIntervalMs: Long = 0L,
    ): Intent =
        Intent()
            .setClassName(TARGET_PACKAGE, HOST_ACTIVITY)
            .putExtra(EXTRA_ROWS, rows)
            .putExtra(EXTRA_APPEND_INTERVAL_MS, appendIntervalMs)

    private companion object {
        const val TARGET_PACKAGE = "com.zcode.remote"
        const val HOST_ACTIVITY = "com.zcode.remote.bench.BenchmarkConversationActivity"
        const val TIMELINE_RES_ID = "conv-timeline-list"
        const val EXTRA_ROWS = "rows"
        const val EXTRA_APPEND_INTERVAL_MS = "appendIntervalMs"

        /** 对齐真机实测的 1100+ 行长会话量级。 */
        const val LONG_ROWS = 1200
        const val STREAM_START_ROWS = 600
        const val STREAM_INTERVAL_MS = 33L
        const val STREAM_WINDOW_MS = 2_000L

        /** 模拟器上未 AOT 编译的首帧与 UiAutomator 首次连接都慢，等待窗口放宽；与 CI timeout 一并留有裕量。 */
        const val UI_TIMEOUT_MS = 30_000L

        /** 轮数控制总时长（3 场景 × 3 轮）；真机复测可上调以收紧置信区间。 */
        const val ITERATIONS = 3
    }
}
