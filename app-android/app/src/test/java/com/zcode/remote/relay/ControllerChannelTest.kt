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
 * T2：Controller 通道状态机（PROTOCOL.md §11.6）。
 *
 * 覆盖任务书要求的 ≥8 项可判定验收：snapshot 全量替换 / upserted 覆盖 / removed 删除 /
 * logEpoch 缺口触发 resync / seq 缺口触发 resync / 正常推进不误触发 / 未知 op 宽容 /
 * 归档过滤与排序 / 取消失败兜底（resync base 取最近水位）。
 *
 * 帧形状以桌面端 host 产物 zod strict schema 为准（`chunk-BG4MS6RN.js` + `chunk-RWMCBKS2.js`）。
 */
class ControllerChannelTest {
    // ---------- 帧构造助手 ----------

    private fun task(
        taskId: String,
        title: String = "任务 $taskId",
        updatedAt: Long = 1_000L,
        archived: Boolean = false,
        pinned: Boolean = false,
        liveStatus: String? = "idle",
        status: String? = "idle",
        lastErrorMsg: String? = null,
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
                status?.let { put("status", it) }
                lastErrorMsg?.let { msg ->
                    putJsonObject("lastError") { put("message", msg) }
                }
            }
            putJsonObject("membership") {
                put("pinned", pinned)
                put("archived", archived)
                put("active", true)
            }
            put("sourceAvailability", "online")
            liveStatus?.let { put("liveStatus", it) }
        }

    private fun snapshotFrame(
        tasks: List<JsonObject>,
        logEpoch: String = "epoch-1",
        fromSeq: Long = 0,
        toSeq: Long = 0,
        topic: String = ControllerReducer.TOPIC_TASKS,
        sub: String = "sub-1",
    ): JsonObject =
        buildJsonObject {
            put("topic", topic)
            put("subscriptionId", sub)
            put("logEpoch", logEpoch)
            put("fromSeq", fromSeq)
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
        fromSeq: Long = 1,
        toSeq: Long = 2,
        topic: String = ControllerReducer.TOPIC_TASKS,
        sub: String = "sub-1",
    ): JsonObject =
        buildJsonObject {
            put("topic", topic)
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

    // 单测环境无 android.util.Log：注入空日志器（状态机本身是纯逻辑）
    private fun reducer(): ControllerReducer = ControllerReducer { _, _ -> }

    private fun apply(
        r: ControllerReducer,
        frame: JsonObject,
    ): ReduceOutcome {
        val parsed = ControllerFrames.parse(frame)
        assertNotNull("帧应可解析: $frame", parsed)
        return r.apply(parsed!!)
    }

    // ---------- 1. 快照全量替换 ----------

    @Test
    fun `snapshot 全量替换已有列表`() {
        val r = reducer()
        apply(r, snapshotFrame(listOf(task("t1"), task("t2"))))
        assertEquals(setOf("t1", "t2"), r.tasks.value.keys)

        // 第二份快照只含一条：必须**替换**而非合并（旧 t2 不得残留）
        apply(r, snapshotFrame(listOf(task("t3")), toSeq = 5))
        assertEquals(setOf("t3"), r.tasks.value.keys)
    }

    // ---------- 2. upserted 覆盖 ----------

    @Test
    fun `task upserted 按 taskId 覆盖而非新增`() {
        val r = reducer()
        apply(r, snapshotFrame(listOf(task("t1", title = "旧标题", updatedAt = 100))))
        apply(
            r,
            deltasFrame(listOf(upsert(task("t1", title = "新标题", updatedAt = 200, liveStatus = "running")))),
        )
        assertEquals(1, r.tasks.value.size)
        val t = r.tasks.value.getValue("t1")
        assertEquals("新标题", t.title)
        assertEquals(200L, t.updatedAt)
        assertEquals("running", t.liveStatus)
    }

    // ---------- 3. removed 删除 ----------

    @Test
    fun `task removed 按 address taskId 删除`() {
        val r = reducer()
        apply(r, snapshotFrame(listOf(task("t1"), task("t2"))))
        apply(r, deltasFrame(listOf(remove("t1"))))
        assertEquals(setOf("t2"), r.tasks.value.keys)
    }

    // ---------- 4. logEpoch 变化 → 重同步 ----------

    @Test
    fun `logEpoch 变化触发失同步`() {
        val r = reducer()
        apply(r, snapshotFrame(listOf(task("t1")), logEpoch = "epoch-1"))
        val out = apply(r, deltasFrame(emptyList(), logEpoch = "epoch-2", fromSeq = 1, toSeq = 2))
        assertTrue("logEpoch 变化必须报失同步，实际 $out", out is ReduceOutcome.Desync)
        assertEquals("logEpoch", (out as ReduceOutcome.Desync).reason)
    }

    // ---------- 5. seq 缺口 → 重同步 ----------

    @Test
    fun `seq 缺口触发失同步`() {
        val r = reducer()
        apply(r, snapshotFrame(listOf(task("t1")), toSeq = 3))
        // 期望 fromSeq=4，实际 9 → 中间 5 帧丢失
        val out = apply(r, deltasFrame(listOf(upsert(task("t1"))), fromSeq = 9, toSeq = 10))
        assertTrue("缺口必须报失同步，实际 $out", out is ReduceOutcome.Desync)
        assertEquals("seq-mismatch", (out as ReduceOutcome.Desync).reason)
    }

    // ---------- 6. 连续推进不误触发 ----------

    @Test
    fun `连续推进不误触发失同步`() {
        val r = reducer()
        apply(r, snapshotFrame(listOf(task("t1")), toSeq = 1))
        val a = apply(r, deltasFrame(listOf(upsert(task("t1"))), fromSeq = 1, toSeq = 2))
        val b = apply(r, deltasFrame(listOf(upsert(task("t1"))), fromSeq = 2, toSeq = 3))
        assertEquals(ReduceOutcome.Applied, a)
        assertEquals(ReduceOutcome.Applied, b)
    }

    @Test
    fun `fromSeq 回退（重叠）同样触发失同步`() {
        // 对齐官方 web 端 Uue：fromSeq 必须恰好等于上次 toSeq，缺口与回退都算失同步
        val r = reducer()
        apply(r, snapshotFrame(listOf(task("t1")), toSeq = 5))
        val out = apply(r, deltasFrame(listOf(upsert(task("t1"))), fromSeq = 3, toSeq = 4))
        assertTrue("回退必须报失同步，实际 $out", out is ReduceOutcome.Desync)
        assertEquals("seq-mismatch", (out as ReduceOutcome.Desync).reason)
    }

    @Test
    fun `快照帧不做缺口检查且刷新水位`() {
        // 官方端对 snapshot 跳过 Uue 检查：快照本身就是全量权威
        val r = reducer()
        apply(r, snapshotFrame(listOf(task("t1")), toSeq = 3))
        // 一帧 deltas 推进到 4
        apply(r, deltasFrame(listOf(upsert(task("t1"))), fromSeq = 3, toSeq = 4))
        // 中途来一份快照（fromSeq=0 是快照的固定形态）——不得触发失同步
        val out = apply(r, snapshotFrame(listOf(task("t1"), task("t2")), toSeq = 4))
        assertEquals(ReduceOutcome.Applied, out)
        // 快照后的下一帧 deltas 从 4 继续
        val nxt = apply(r, deltasFrame(listOf(upsert(task("t2"))), fromSeq = 4, toSeq = 5))
        assertEquals(ReduceOutcome.Applied, nxt)
    }

    // ---------- 7. 未知 op 宽容忽略 ----------

    @Test
    fun `未知 op 宽容忽略且不抛异常`() {
        val r = reducer()
        apply(r, snapshotFrame(listOf(task("t1"))))
        val unknown =
            buildJsonObject {
                put("op", "task.someFutureOp")
                putJsonObject("task") { put("taskId", "tx") }
            }
        // 快照水位 toSeq=0，服务器下一帧 delta 的 fromSeq 应为 0（对齐 host 序列语义）
        val out = apply(r, deltasFrame(listOf(unknown, upsert(task("t2"))), fromSeq = 0, toSeq = 1))
        assertEquals(ReduceOutcome.Applied, out)
        // 未识别 op 不得动状态；同帧内的合法 op 仍生效
        assertEquals(setOf("t1", "t2"), r.tasks.value.keys)
    }

    // ---------- 8. 未知 payload kind 忽略 ----------

    @Test
    fun `未知 payload kind 不破坏现有状态`() {
        val r = reducer()
        apply(r, snapshotFrame(listOf(task("t1"))))
        val weird =
            buildJsonObject {
                put("topic", ControllerReducer.TOPIC_TASKS)
                put("subscriptionId", "sub-1")
                put("logEpoch", "epoch-1")
                put("fromSeq", 0)
                put("toSeq", 1)
                putJsonObject("payload") { put("kind", "telemetry.ping") }
            }
        val out = apply(r, weird)
        assertEquals(ReduceOutcome.Applied, out)
        assertEquals(setOf("t1"), r.tasks.value.keys)
    }

    // ---------- 9. 归档过滤 ----------

    @Test
    fun `归档任务不进列表且计入 archivedCount`() {
        val r = reducer()
        apply(
            r,
            snapshotFrame(
                listOf(
                    task("t1", updatedAt = 300),
                    task("t2", archived = true, updatedAt = 400),
                    task("t3", updatedAt = 200),
                ),
            ),
        )
        assertEquals(1, r.archivedCount.value)
        val visible = ControllerReducer.visibleSessions(r.tasks.value)
        assertEquals(listOf("t1", "t3"), visible.map { it.taskId })
    }

    @Test
    fun `列表按 updatedAt 降序且同值按 taskId 稳定排序`() {
        val r = reducer()
        apply(
            r,
            snapshotFrame(
                listOf(
                    task("b", updatedAt = 100),
                    task("a", updatedAt = 100),
                    task("c", updatedAt = 500),
                ),
            ),
        )
        assertEquals(listOf("c", "a", "b"), ControllerReducer.visibleSessions(r.tasks.value).map { it.taskId })
    }

    // ---------- 10. task → SessionItem 映射 ----------

    @Test
    fun `task 映射到 SessionItem 时 title 缺省退 taskId 且直取状态`() {
        val item =
            ControllerReducer.toSessionItem(
                ControllerTask(
                    taskId = "t9",
                    title = "",
                    liveStatus = "running",
                    status = "idle",
                    workspacePath = "/ws/a",
                    updatedAt = 42L,
                    lastError = "boom",
                ),
            )
        assertEquals("t9", item.title)
        assertEquals("running", item.displayStatus)
        assertEquals("/ws/a", item.workspacePath)
        assertEquals(42L, item.updatedAt)
        assertEquals("boom", item.lastError)
        assertFalse(item.archived)
    }

    @Test
    fun `liveStatus 缺失时退 meta status`() {
        val item =
            ControllerReducer.toSessionItem(
                ControllerTask(taskId = "t1", liveStatus = null, status = "completed"),
            )
        assertEquals("completed", item.displayStatus)
    }

    // ---------- 11. 工作区 topic ----------

    @Test
    fun `工作区快照与增量独立维护`() {
        val r = reducer()
        val wsFrame =
            buildJsonObject {
                put("topic", ControllerReducer.TOPIC_WORKSPACES)
                put("subscriptionId", "sub-2")
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
                                    put("connectionState", "online")
                                },
                            )
                        }
                    }
                }
            }
        apply(r, wsFrame)
        assertEquals(1, r.workspaces.value.size)
        assertTrue(r.workspaces.value[0].online)

        val wsDelta =
            buildJsonObject {
                put("topic", ControllerReducer.TOPIC_WORKSPACES)
                put("subscriptionId", "sub-2")
                put("logEpoch", "epoch-w")
                put("fromSeq", 0)
                put("toSeq", 1)
                putJsonObject("payload") {
                    put("kind", "deltas")
                    putJsonArray("deltas") {
                        add(
                            buildJsonObject {
                                put("op", "workspace.removed")
                                put("workspacePath", "/ws/a")
                            },
                        )
                    }
                }
            }
        apply(r, wsDelta)
        assertTrue(r.workspaces.value.isEmpty())
    }

    // ---------- 12. 接管判据 ----------

    @Test
    fun `V2 且 bootstrap 无 tasks 才接管列表`() {
        assertTrue(ControllerChannel.shouldTakeOver(ProtocolStack.V2, bootstrapTaskCount = 0))
        // V2 但 bootstrap 竟带 tasks（异常形态）→ 不接管，避免双写
        assertFalse(ControllerChannel.shouldTakeOver(ProtocolStack.V2, bootstrapTaskCount = 3))
        // V1 恒不接管
        assertFalse(ControllerChannel.shouldTakeOver(ProtocolStack.V1, bootstrapTaskCount = 0))
        assertFalse(ControllerChannel.shouldTakeOver(ProtocolStack.V1, bootstrapTaskCount = 3))
    }

    // ---------- 13. 帧解析 ----------

    @Test
    fun `subscriptionId 兼容 ack 信封与顶层两种形态`() {
        val enveloped =
            buildJsonObject { putJsonObject("ack") { put("subscriptionId", "sub-x") } }
        assertEquals("sub-x", ControllerFrames.subscriptionIdOf(enveloped))
        val flat = buildJsonObject { put("subscriptionId", "sub-y") }
        assertEquals("sub-y", ControllerFrames.subscriptionIdOf(flat))
        assertNull(ControllerFrames.subscriptionIdOf(null))
    }

    @Test
    fun `reset 清空全部状态与水位`() {
        val r = reducer()
        apply(r, snapshotFrame(listOf(task("t1"))))
        r.reset()
        assertTrue(r.tasks.value.isEmpty())
        assertTrue(r.workspaces.value.isEmpty())
        assertEquals(0, r.archivedCount.value)
        assertNull(r.watermarkOf(ControllerReducer.TOPIC_TASKS))
    }
}
