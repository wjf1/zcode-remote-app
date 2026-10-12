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
 * - [CompilationMode.Full]：每轮测量前对被测包做完整 AOT 编译（`cmd package compile -f -m speed`）。
 *   理由：库用 `dumpsys gfxinfo` 轮询确认启动完成，窗口硬编码约 10s（100×100ms）；模拟器上
 *   **未 AOT 编译**的 Compose 首帧可能超出该窗口，直接判失败（2026-10-12 实测：None 模式三场景
 *   全部报 `Unable to confirm activity launch completion []`）。完整编译后启动处于「已编译」
 *   状态、窗口内可确认，且每轮状态一致、可重复。**数值口径 = 已编译启动（偏乐观），
 *   绝对值不与真机「部分编译 + JIT」的日常状态直接比较**，只用于同一口径下的趋势比对。
 *   （官方正路是给 App 加 baseline profile 后用 [CompilationMode.Partial]——登记为后续待办。）
 * - 模拟器帧时序与真机不同源，数值仅作量级回归信号；真机口径以人工跑 [MacrobenchmarkRule]
 *   并把结果交给 `tools/bench_check.py` 比对为准（见 README/HANDOVER 的运行说明）。
 * - janky 帧口径 = `frameOverrunMs > 0`（错过帧期限），与真机「1336 帧 0 janky」同源。
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
            compilationMode = CompilationMode.Full(),
            setupBlock = { pressHome() },
        ) {
            requireHostInstalled()
            startHostAndWait(hostIntent(rows = LONG_ROWS))
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
            compilationMode = CompilationMode.Full(),
            setupBlock = {
                pressHome()
                requireHostInstalled()
                startHostAndWait(hostIntent(rows = LONG_ROWS))
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
            compilationMode = CompilationMode.Full(),
            setupBlock = {
                pressHome()
                requireHostInstalled()
                startHostAndWait(hostIntent(rows = STREAM_START_ROWS, appendIntervalMs = STREAM_INTERVAL_MS))
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

    /**
     * 运行时取证之一：确认被测包安装的是含宿主 Activity 的 benchmark 变体。
     * **必须在测试进程内执行**——AGP 跑完 connected 测试会卸载 APK，事后再 `pm dump` 必然误读为空
     * （2026-10-12 实测：事后诊断 `ActivityCount=0` 是卸载假象）。
     */
    private fun MacrobenchmarkScope.requireHostInstalled() {
        val out =
            runCatching {
                device.executeShellCommand("cmd package resolve-activity --brief $TARGET_PACKAGE/$HOST_ACTIVITY")
            }.getOrElse { "resolve-activity 执行失败：$it" }
        println("BENCH_DIAG resolve-activity: $out")
        require(out.contains(HOST_ACTIVITY)) {
            "被测包中不存在宿主 Activity（装的可能是非 benchmark 变体——须含 app/src/benchmark 源集）：$out"
        }
    }

    /**
     * 运行时取证之二：启动宿主并等待首帧；失败时把现场证据打到 logcat（`am start` 真实输出 +
     * activity 栈），使库的 `Unable to confirm activity launch completion` 不再是无从下手的黑盒。
     */
    private fun MacrobenchmarkScope.startHostAndWait(intent: Intent) {
        try {
            startActivityAndWait(intent)
        } catch (e: IllegalStateException) {
            println("BENCH_DIAG am start 手动重放（看真实输出）：")
            println(
                runCatching {
                    device.executeShellCommand("am start -W -n $TARGET_PACKAGE/$HOST_ACTIVITY")
                }.getOrElse { "重放失败：$it" },
            )
            println("BENCH_DIAG activity 栈：")
            println(
                runCatching {
                    device.executeShellCommand("dumpsys activity activities | head -40")
                }.getOrElse { "dump 失败：$it" },
            )
            println("BENCH_DIAG gfxinfo framestats（库的轮询依据，原始输出）：")
            println(
                runCatching {
                    device.executeShellCommand("dumpsys gfxinfo $TARGET_PACKAGE framestats | head -60")
                }.getOrElse { "dump 失败：$it" },
            )
            throw e
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
