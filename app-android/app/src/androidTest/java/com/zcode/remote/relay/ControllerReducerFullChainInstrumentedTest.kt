package com.zcode.remote.relay

import com.zcode.remote.storage.ProtocolStack
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Controller 通道「订阅 → 快照 → 增量 → resync」全链路的仪器化取证（HANDOFF CI 覆盖缺口项）。
 *
 * 定位：`ControllerReducer` 是无 IO 的纯状态机，JVM 单测（`ControllerChannelTest`）已覆盖全部
 * 规则分支；本类把**同一套假事件序列**在真实 Android 运行时（ART + 真实 `android.util.Log`
 * 路径——注意 reducer 用默认构造走生产 `ZLog`，不注入空日志器，这是相对 JVM 单测的取证差异点）
 * 再跑一遍，补齐 CI 仪器化证据链。此前全链路只靠 `tools/probe.py ctrl` + 真机人工走查。
 *
 * 帧形状以桌面端 host 产物 zod strict schema 为准（PROTOCOL.md §11.6），与 JVM 单测同源。
 * 全部为纯函数驱动（假帧序列），不触网、不依赖 Espresso 注入，可在 CI API 35 emulator 直接跑。
 */
class ControllerReducerFullChainInstrumentedTest {
    // ---------- 帧构造助手（形状对齐 ControllerChannelTest） ----------

    private fun task(
        taskId: String,
        title: String = "任务 $taskId",
        updatedAt: Long = 1_000L,
        archived: Boolean = false,
        liveStatus: String? = "idle",
    ): JsonObject =
        buildJsonObject {
            putJsonObject("address") {
                put("workspacePath", "/ws/a")
                put("taskId", taskId)
            }
            putJsonObject("meta") {
                put("taskId", taskId)
                put("title", title)
                put("workspacePath", "/ws/a")
                put("createdAt", 1L)
                put("updatedAt", updatedAt)
                put("status", "idle")
            }
            putJsonObject("membership") {
                put("pinned", false)
                put("archived", archived)
                put("active", true)
            }
            put("sourceAvailability", "online")
            liveStatus?.let { put("liveStatus", it) }
        }

    private fun snapshotFrame(
        tasks: List<JsonObject>,
        logEpoch: String = "epoch-1",
        toSeq: Long = 0,
        sub: String = "sub-1",
    ): JsonObject =
        buildJsonObject {
            put("topic", ControllerReducer.TOPIC_TASKS)
            put("subscriptionId", sub)
            put("logEpoch", logEpoch)
            put("fromSeq", 0)
            put("toSeq", toSeq)
            putJsonObject("payload") {
                put("kind", "snapshot")
                putJsonObject("snapshot") {
                    put("protocolVersion", 1)
                    put("logEpoch", logEpoch)
                    putJsonArray("tasks") { tasks.forEach { add(it) } }
                }
            }
        }

    private fun deltasFrame(
        ops: List<JsonObject>,
        logEpoch: String = "epoch-1",
        fromSeq: Long,
        toSeq: Long,
        sub: String = "sub-1",
    ): JsonObject =
        buildJsonObject {
            put("topic", ControllerReducer.TOPIC_TASKS)
            put("subscriptionId", sub)
            put("logEpoch", logEpoch)
            put("fromSeq", fromSeq)
            put("toSeq", toSeq)
            putJsonObject("payload") {
                put("kind", "deltas")
                putJsonArray("deltas") { ops.forEach { add(it) } }
            }
        }

    private fun upsert(t: JsonObject): JsonObject =
        buildJsonObject {
            put("op", "task.upserted")
            put("task", t)
        }

    private fun remove(taskId: String): JsonObject =
        buildJsonObject {
            put("op", "task.removed")
            putJsonObject("address") {
                put("workspacePath", "/ws/a")
                put("taskId", taskId)
            }
        }

    // 仪器化环境有 android.util.Log：走生产 ZLog 路径（不注入空日志器）
    private fun reducer(): ControllerReducer = ControllerReducer()

    private fun apply(
        r: ControllerReducer,
        frame: JsonObject,
    ): ReduceOutcome {
        val parsed = ControllerFrames.parse(frame)
        assertNotNull("帧应可解析: $frame", parsed)
        return r.apply(parsed!!)
    }

    // ---------- 订阅侧（接管判据 + ack 解析） ----------

    @Test
    fun `订阅侧——V2 无 tasks 才接管且 ack 双形态可解析`() {
        // shouldTakeOver：协议栈 V2 + bootstrap 未下发 tasks[] 才由 Controller 通道接管列表
        assertTrue("V2 + 无 tasks 应接管", ControllerChannel.shouldTakeOver(ProtocolStack.V2, 0))
        assertFalse("V2 但 bootstrap 带 tasks 不得接管（防双写）", ControllerChannel.shouldTakeOver(ProtocolStack.V2, 3))
        assertFalse("V1 恒不接管", ControllerChannel.shouldTakeOver(ProtocolStack.V1, 0))
        // subscribeControllerV4 的 ack：信封 {ack:{subscriptionId}} 与顶层两种形态
        val ack = buildJsonObject { putJsonObject("ack") { put("subscriptionId", "sub-live") } }
        assertEquals("sub-live", ControllerFrames.subscriptionIdOf(ack))
        val flat = buildJsonObject { put("subscriptionId", "sub-flat") }
        assertEquals("sub-flat", ControllerFrames.subscriptionIdOf(flat))
        assertNull(ControllerFrames.subscriptionIdOf(null))
    }

    // ---------- 全链路：快照 → 增量 → logEpoch 失同步 → resync 恢复 ----------

    @Test
    fun `全链路——快照到增量推进到 epoch 失同步再到 resync 恢复`() {
        val r = reducer()

        // 阶段 1：首份快照建立基线（含一条归档，验证 archivedCount）
        val first = apply(r, snapshotFrame(listOf(task("t1"), task("t2", archived = true)), toSeq = 3))
        assertEquals("首份快照对空水位不报失同步", ReduceOutcome.Applied, first)
        assertEquals(setOf("t1", "t2"), r.tasks.value.keys)
        assertEquals("归档条目应计入 archivedCount", 1, r.archivedCount.value)
        assertEquals("水位应推进到快照 toSeq", 3L, r.watermarkOf(ControllerReducer.TOPIC_TASKS)?.toSeq)

        // 阶段 2：连续 deltas 正常推进（upsert 覆盖 + removed 删除），不误报失同步
        val step1 = apply(r, deltasFrame(listOf(upsert(task("t1", title = "新标题", updatedAt = 200L))), fromSeq = 3, toSeq = 4))
        assertEquals("连续推进应 Applied", ReduceOutcome.Applied, step1)
        val upserted = r.tasks.value.getValue("t1")
        assertEquals("增量应覆盖既有条目", "新标题", upserted.title)
        val step2 = apply(r, deltasFrame(listOf(remove("t2")), fromSeq = 4, toSeq = 5))
        assertEquals(ReduceOutcome.Applied, step2)
        assertEquals(setOf("t1"), r.tasks.value.keys)
        assertEquals("删除归档条目后 archivedCount 归零", 0, r.archivedCount.value)

        // 阶段 3：PC 重启 → logEpoch 变化 → 失同步（调用方据此发起 resync）
        val desync = apply(r, deltasFrame(emptyList(), logEpoch = "epoch-2", fromSeq = 5, toSeq = 6))
        assertTrue("logEpoch 变化必须报失同步，实际 $desync", desync is ReduceOutcome.Desync)
        assertEquals("logEpoch", (desync as ReduceOutcome.Desync).reason)
        // resync base 的来源 = 水位（该帧已把 logEpoch 刷新为最新值，与生产 resyncControllerV4 的 base 对齐）
        val wm = r.watermarkOf(ControllerReducer.TOPIC_TASKS)
        assertEquals("epoch-2", wm?.logEpoch)
        assertEquals(6L, wm?.toSeq)

        // 阶段 4：resync 响应快照全量重建（epoch-2 新基线），旧条目不得残留
        val rebuilt = apply(r, snapshotFrame(listOf(task("t1"), task("t3")), logEpoch = "epoch-2", toSeq = 8))
        assertEquals("恢复快照本身不判缺口", ReduceOutcome.Applied, rebuilt)
        assertEquals(setOf("t1", "t3"), r.tasks.value.keys)

        // 阶段 5：恢复后 deltas 从新水位继续 → 全链路回到 Applied 稳态
        val resumed = apply(r, deltasFrame(listOf(upsert(task("t3", updatedAt = 300L))), logEpoch = "epoch-2", fromSeq = 8, toSeq = 9))
        assertEquals("resync 后增量应恢复 Applied", ReduceOutcome.Applied, resumed)
        assertEquals(9L, r.watermarkOf(ControllerReducer.TOPIC_TASKS)?.toSeq)
    }

    // ---------- 全链路：seq 缺口失同步 ----------

    @Test
    fun `全链路——seq 缺口触发失同步且 base 取自最近水位`() {
        val r = reducer()
        apply(r, snapshotFrame(listOf(task("t1")), toSeq = 3))
        // 期望 fromSeq=3，实际 9：中间帧已丢 → 整份重取
        val desync = apply(r, deltasFrame(listOf(upsert(task("t1"))), fromSeq = 9, toSeq = 10))
        assertTrue("缺口必须报失同步，实际 $desync", desync is ReduceOutcome.Desync)
        assertEquals("seq-mismatch", (desync as ReduceOutcome.Desync).reason)
        assertEquals("resync base 应取最近水位的 toSeq", 10L, r.watermarkOf(ControllerReducer.TOPIC_TASKS)?.toSeq)
        // resync 快照恢复后缺口消除
        apply(r, snapshotFrame(listOf(task("t1"), task("t4")), toSeq = 12))
        val next = apply(r, deltasFrame(listOf(upsert(task("t4"))), fromSeq = 12, toSeq = 13))
        assertEquals(ReduceOutcome.Applied, next)
    }

    // ---------- 全链路：双 topic 独立水位 ----------

    @Test
    fun `全链路——tasks 与 workspaces 双 topic 独立维护互不干扰`() {
        val r = reducer()
        // tasks 基线
        apply(r, snapshotFrame(listOf(task("t1")), toSeq = 2))
        // workspaces 快照（独立 sub / 独立水位）
        val wsFrame =
            buildJsonObject {
                put("topic", ControllerReducer.TOPIC_WORKSPACES)
                put("subscriptionId", "sub-ws")
                put("logEpoch", "epoch-w")
                put("fromSeq", 0)
                put("toSeq", 0)
                putJsonObject("payload") {
                    put("kind", "snapshot")
                    putJsonObject("snapshot") {
                        put("protocolVersion", 1)
                        put("logEpoch", "epoch-w")
                        putJsonArray("workspaces") {
                            add(
                                buildJsonObject {
                                    put("workspacePath", "/ws/a")
                                    put("sourceAvailability", "online")
                                },
                            )
                        }
                    }
                }
            }
        assertEquals(ReduceOutcome.Applied, apply(r, wsFrame))
        assertEquals(1, r.workspaces.value.size)
        assertTrue(r.workspaces.value[0].online)

        // tasks topic 的 epoch 变化只影响 tasks 水位，workspaces 不受牵连
        val desync = apply(r, deltasFrame(emptyList(), logEpoch = "epoch-2", fromSeq = 2, toSeq = 3))
        assertTrue(desync is ReduceOutcome.Desync)
        assertEquals("epoch-w", r.watermarkOf(ControllerReducer.TOPIC_WORKSPACES)?.logEpoch)
        assertEquals("epoch-2", r.watermarkOf(ControllerReducer.TOPIC_TASKS)?.logEpoch)
        assertEquals("tasks 失同步不影响工作区列表", 1, r.workspaces.value.size)
        assertEquals("tasks 增量丢失不影响既有任务", setOf("t1"), r.tasks.value.keys)
    }
}
