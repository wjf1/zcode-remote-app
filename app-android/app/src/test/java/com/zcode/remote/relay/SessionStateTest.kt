package com.zcode.remote.relay

import com.zcode.remote.relay.ConversationFrames.BackgroundWorksState
import com.zcode.remote.relay.ConversationFrames.ContextUsage
import com.zcode.remote.relay.ConversationFrames.GoalState
import com.zcode.remote.relay.ConversationFrames.PlanState
import com.zcode.remote.relay.ConversationFrames.PlanTodo
import com.zcode.remote.relay.ConversationFrames.QueueState
import com.zcode.remote.relay.ConversationFrames.SessionState
import com.zcode.remote.relay.ConversationFrames.SubagentsState
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话级状态块解析单测（beta17 / C1）。
 *
 * 这些块的形状是从真机骨架与桌面包反解出来的，没有官方 schema，
 * 因此边界用例（缺块、空对象、越界值、字段形态漂移）比正常路径更重要 ——
 * 一条畸形数据不得让整块状态崩掉，也不得让面板顶出一个空分区。
 */
class SessionStateTest {

    // ---------- ContextUsage ----------

    @Test
    fun contextUsageParsesUsedMaxCacheAndBreakdown() {
        val u = ContextUsage.from(
            buildJsonObject {
                put("usedTokens", 50_000)
                put("maxTokens", 100_000)
                put("cache", buildJsonObject { put("hitRate", 0.82) })
                putJsonArray("breakdown") {
                    add(buildJsonObject { put("source", "messages"); put("chars", 300) })
                    add(buildJsonObject { put("source", "system_prompt"); put("chars", 100) })
                }
            },
        )
        assertNotNull(u)
        assertEquals(50_000L, u!!.usedTokens)
        assertEquals(100_000L, u.maxTokens)
        assertEquals(0.82, u.cacheHitRate!!, 1e-9)
        assertEquals(2, u.breakdown.size)
        assertEquals(0.5, u.ratio!!, 1e-9)
    }

    @Test
    fun contextUsageRatioIsNullWhenWindowUnknownOrZero() {
        assertNull(ContextUsage(usedTokens = 10, maxTokens = null, cacheHitRate = null).ratio)
        assertNull(ContextUsage(usedTokens = 10, maxTokens = 0, cacheHitRate = null).ratio)
        // 越界时夹到 0..1：服务端偶发 >100% 的瞬时值不该画出溢出的进度条
        assertEquals(1.0, ContextUsage(usedTokens = 200, maxTokens = 100, cacheHitRate = null).ratio!!, 1e-9)
    }

    @Test
    fun contextUsageAllEmptyIsNull() {
        assertNull(ContextUsage.from(buildJsonObject { }))
        assertNull(ContextUsage.from(null))
    }

    @Test
    fun cacheHitRateHiddenBelowDesktopThreshold() {
        val low = ContextUsage(null, null, 0.7799, emptyList())
        val high = ContextUsage(null, null, 0.78, emptyList())
        assertNull(low.cacheHitRateIfNotable())
        assertEquals(0.78, high.cacheHitRateIfNotable()!!, 1e-9)
    }

    @Test
    fun breakdownPercentagesSortByCharsDescThenDesktopRank() {
        val u = ContextUsage(
            usedTokens = 1, maxTokens = 2, cacheHitRate = null,
            breakdown = listOf(
                ConversationFrames.ContextSource("skills", 100),
                ConversationFrames.ContextSource("messages", 100),
                ConversationFrames.ContextSource("unknown_source", 50),
                ConversationFrames.ContextSource("zero", 0),
            ),
        )
        val slices = u.breakdownPercentages()
        // 0 字符的来源不占位；同 chars 时 messages（固定序第 0）先于 skills（第 3）
        assertEquals(listOf("messages", "skills", "unknown_source"), slices.map { it.source.source })
        assertEquals(0.4, slices[0].ratio, 1e-9)
        assertEquals(0.2, slices[2].ratio, 1e-9)
    }

    @Test
    fun breakdownPercentagesEmptyWhenNoPositiveChars() {
        val u = ContextUsage(1, 2, null, listOf(ConversationFrames.ContextSource("messages", 0)))
        assertTrue(u.breakdownPercentages().isEmpty())
    }

    // ---------- QueueState ----------

    @Test
    fun queueCountsItemsAndReadsAutoDrain() {
        val q = QueueState.from(
            buildJsonObject {
                put("autoDrain", false)
                putJsonArray("items") { add(buildJsonObject { put("text", "a") }) }
            },
        )!!
        assertEquals(1, q.itemCount)
        assertEquals(false, q.autoDrain)
        // items 缺失不该崩，按 0 处理
        assertEquals(0, QueueState.from(buildJsonObject { })!!.itemCount)
    }

    // ---------- BackgroundWorks ----------

    @Test
    fun backgroundWorksCountsOnlyRunning() {
        val s = BackgroundWorksState.from(
            buildJsonArray {
                add(buildJsonObject {
                    put("kind", "bash"); put("status", "running"); put("workId", "w1")
                })
                add(buildJsonObject {
                    put("kind", "bash"); put("status", "ended"); put("workId", "w2")
                })
                add(buildJsonObject {
                    put("kind", "subagent"); put("status", "running"); put("childSessionId", "c1")
                })
            },
        )!!
        assertEquals(3, s.works.size)
        assertEquals(2, s.running.size)
        assertEquals(1, s.bashRunningCount)
    }

    @Test
    fun backgroundWorksNullWhenNotAnArray() {
        assertNull(BackgroundWorksState.from(JsonNull))
        assertNull(BackgroundWorksState.from(null))
    }

    // ---------- Subagents ----------

    @Test
    fun subagentsAcceptsObjectAndStringRunningEntries() {
        val s = SubagentsState.from(
            buildJsonObject {
                put("endedTotal", 3)
                put("revision", 7)
                putJsonArray("running") {
                    add(buildJsonObject { put("childSessionId", "child-a") })
                    add(JsonPrimitive("child-b"))
                }
            },
        )!!
        assertEquals(listOf("child-a", "child-b"), s.runningChildSessionIds)
        assertEquals(2, s.runningCount)
        assertEquals(3L, s.endedTotal)
        assertEquals(7L, s.revision)
    }

    // ---------- Goal ----------

    @Test
    fun goalAllEmptyFieldsIsNull() {
        assertNull(GoalState.from(buildJsonObject { }))
        assertNull(GoalState.from(null))
        val g = GoalState.from(
            buildJsonObject {
                put("objective", "把面板做出来")
                put("status", "active")
                put("timeUsedSeconds", 42)
            },
        )!!
        assertEquals("把面板做出来", g.objective)
        assertEquals(42L, g.timeUsedSeconds)
    }

    // ---------- Plan ----------

    @Test
    fun planParsesTodosAndSkipsEmpty() {
        assertNull(PlanState.from(buildJsonObject { }))
        assertNull(PlanState.from(buildJsonObject { putJsonArray("items") { } }))
        val p = PlanState.from(
            buildJsonObject {
                put("updatedAt", 1_789_302_334_894L)
                putJsonArray("items") {
                    add(buildJsonObject {
                        put("id", "t1")
                        put("content", "修复会话列表")
                        put("status", "pending")
                    })
                    add(buildJsonObject {
                        put("id", "t2")
                        put("content", "补充单测")
                        put("status", "completed")
                    })
                    // 裸字符串项：按无状态处理，保留而不是丢弃
                    add(JsonPrimitive("只读协议"))
                    // 没有正文的脏项直接跳过
                    add(buildJsonObject { put("id", "t3") })
                }
            },
        )!!
        assertEquals(1_789_302_334_894L, p.updatedAt)
        assertEquals(3, p.totalCount)
        assertEquals(1, p.completedCount)
        assertEquals("修复会话列表", p.items[0].content)
        assertEquals(false, p.items[0].isCompleted)
        assertEquals(true, p.items[1].isCompleted)
        assertEquals("只读协议", p.items[2].content)
        assertEquals(null, p.items[2].status)
        assertEquals(false, p.allCompleted)
    }

    @Test
    fun planDisplayFoldsCompletedThenOverflow() {
        fun todo(content: String, done: Boolean) =
            PlanTodo(id = null, content = content, status = if (done) "completed" else "pending")

        // 未完成 2 + 已完成 1：全展开，无折叠
        val small = PlanState(
            items = listOf(todo("甲", false), todo("乙", false), todo("丙", true)),
            updatedAt = null,
        ).display(limit = 6)
        assertEquals(listOf("甲", "乙", "丙"), small.visible.map { it.content })
        assertEquals(0, small.foldedCompleted)
        assertEquals(0, small.foldedTail)

        // 未完成 8 + 已完成 2：未完成占满 6 个，已完成全折叠，尾部 2 个未完成折叠
        val big = PlanState(
            items = (1..8).map { todo("待$it", false) } + listOf(todo("完1", true), todo("完2", true)),
            updatedAt = null,
        ).display(limit = 6)
        assertEquals((1..6).map { "待$it" }, big.visible.map { it.content })
        assertEquals(2, big.foldedCompleted)
        assertEquals(2, big.foldedTail)

        // 未完成 3 + 已完成 5：已完成回填剩余 3 个位置，还剩 2 个折叠
        val mixed = PlanState(
            items = (1..3).map { todo("待$it", false) } + (1..5).map { todo("完$it", true) },
            updatedAt = null,
        ).display(limit = 6)
        assertEquals(listOf("待1", "待2", "待3", "完1", "完2", "完3"), mixed.visible.map { it.content })
        assertEquals(2, mixed.foldedCompleted)
        assertEquals(0, mixed.foldedTail)
    }

    @Test
    fun goalElapsedTicksOnlyWhileRunning() {
        val running = GoalState(
            objective = "把会话页对齐桌面端", summaryTitle = null, status = "active",
            tokenBudget = 50_000L, tokensUsed = 12_000L, timeUsedSeconds = 30L,
            activeRunStartedAtMs = 1_000_000L,
        )
        assertEquals(true, running.isRunning)
        // 落库 30s + 本轮已跑 5s
        assertEquals(35L, running.elapsedSeconds(nowMs = 1_005_000L))
        // 时钟回拨 / 本轮未开始 → 退回落库值，不出现负增量
        assertEquals(30L, running.elapsedSeconds(nowMs = 900_000L))

        val ended = running.copy(status = "achieved", activeRunStartedAtMs = null)
        assertEquals(false, ended.isRunning)
        assertEquals(30L, ended.elapsedSeconds(nowMs = 2_000_000L))

        val verifying = running.copy(status = "verifying")
        assertEquals(true, verifying.isRunning)
        assertEquals(35L, verifying.elapsedSeconds(nowMs = 1_005_000L))
    }

    // ---------- SessionState.merge ----------

    @Test
    fun mergeReplacesOnlyKeysPresentInPatch() {
        val base = SessionState(
            usage = ContextUsage(1, 10, null, emptyList()),
            plan = PlanState(emptyList(), 5),
        )
        // patch 只带 subagents → usage/plan 必须原样保留
        val merged = base.merge(
            buildJsonObject {
                putJsonObject("subagents") {
                    putJsonArray("running") { add(buildJsonObject { put("childSessionId", "c") }) }
                }
            },
        )
        assertEquals(1L, merged.usage?.usedTokens)
        assertEquals(5L, merged.plan?.updatedAt)
        assertEquals(1, merged.subagents?.runningCount)
    }

    @Test
    fun mergeExplicitNullClearsBlock() {
        val base = SessionState(goal = GoalState("目标", null, "active", null, null, null))
        val merged = base.merge(buildJsonObject { put("goal", JsonNull) })
        assertNull(merged.goal)
    }

    @Test
    fun mergeIgnoresUnknownAndMalformedKeys() {
        val base = SessionState(usage = ContextUsage(5, 10, null, emptyList()))
        // 未识别的键（含 availability）与畸形结构都不该动已有状态
        val merged = base.merge(
            buildJsonObject {
                put("availability", buildJsonObject { put("fork", buildJsonObject { }) })
                put("plan", "不是对象")
            },
        )
        assertEquals(5L, merged.usage?.usedTokens)
        assertNull(merged.plan)
    }

    // ---------- SessionState.from / hasContent ----------

    @Test
    fun sessionStateFromEmptyObjectHasNoContent() {
        val s = SessionState.from(buildJsonObject { })
        assertFalse(s.hasContent)
        assertNull(s.usage)
        assertNull(s.goal)
        assertFalse(SessionState.from(null).hasContent)
    }

    @Test
    fun sessionStateFromReadsUsageSubBlockAndHasContent() {
        val s = SessionState.from(
            buildJsonObject {
                putJsonObject("usage") {
                    putJsonObject("contextWindow") {
                        put("usedTokens", 7)
                        put("maxTokens", 70)
                    }
                    // cumulative 块存在但不解析（面板不展示），不得影响 contextWindow 的读取
                    put("cumulative", buildJsonObject { put("totalTokens", 1) })
                }
            },
        )
        assertEquals(7L, s.usage?.usedTokens)
        assertTrue(s.hasContent)
    }

    @Test
    fun queueWithZeroItemsDoesNotCountAsContent() {
        val s = SessionState.from(
            buildJsonObject {
                putJsonObject("queue") {
                    put("autoDrain", true)
                    putJsonArray("items") { }
                }
            },
        )
        assertNotNull(s.queue)
        assertFalse(s.hasContent)
    }

    /** 快照顶层是否真的把状态块接了出来（防「解析对了但没接线」）。 */
    @Test
    fun parseSnapshotCarriesSessionState() {
        val payload = buildJsonObject {
            putJsonObject("snapshot") {
                put("sessionId", "s1")
                putJsonObject("rows") {
                    put("totalCount", 0)
                    putJsonArray("window") { }
                }
                putJsonObject("usage") {
                    putJsonObject("contextWindow") { put("usedTokens", 11); put("maxTokens", 22) }
                }
                putJsonArray("backgroundWorks") {
                    add(buildJsonObject { put("kind", "bash"); put("status", "running") })
                }
            }
        }
        val snap = ConversationFrames.parseSnapshot(payload)!!
        assertEquals(11L, snap.sessionState.usage?.usedTokens)
        assertEquals(1, snap.sessionState.backgroundWorks?.bashRunningCount)
        assertNull(snap.sessionState.plan)
    }
}
