package com.zcode.remote.relay

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** G6b mobile-task-view 发布器（逐语义对齐官方 `webRemoteControlTaskView.ts`）。 */
class MobileTaskViewPublisherTest {
    private val sent = mutableListOf<kotlinx.serialization.json.JsonObject>()
    private var accept = true

    private fun publisher(): MobileTaskViewPublisher {
        sent.clear()
        accept = true
        return MobileTaskViewPublisher { p ->
            // 模拟 socket send：失败 = 未真正发出（不进 sent），保持 dirty
            if (accept) {
                sent.add(p)
                true
            } else {
                false
            }
        }
    }

    private val bridgeA = MobileTaskViewPublisher.BridgeIdentity("sidA", 1)
    private val bridgeA2 = MobileTaskViewPublisher.BridgeIdentity("sidA", 2)
    private val bridgeB = MobileTaskViewPublisher.BridgeIdentity("sidB", null)
    private val view = MobileTaskViewPublisher.TaskView("/ws/a", null, "task1")

    @Test
    fun noBridge_noSend_evenAfterRequest() {
        val p = publisher()
        p.request("ep-1")
        p.update(bridgeA, view)
        assertEquals(0, sent.size)
    }

    @Test
    fun request_thenUpdateSendsEpochSeqAndBridgeIdentity() {
        val p = publisher()
        p.setBridge(bridgeA) // 无 epoch：官方 flush 条件（bridge+epoch+dirty）不满足，不发
        assertEquals(0, sent.size)
        p.request("ep-1") // epoch 就绪：发（view=null，seq=1）
        p.update(bridgeA, view) // 前台可见事实：发（seq=2）
        assertEquals(2, sent.size)
        val last = sent.last()
        assertEquals("mobile-task-view-update", last["zcode_type"]?.jsonPrimitive?.content)
        assertEquals("ep-1", last["epoch"]?.jsonPrimitive?.content)
        assertEquals(2L, last["seq"]?.jsonPrimitive?.content?.toLongOrNull())
        assertEquals("sidA", last["bridgeSessionId"]?.jsonPrimitive?.content)
        assertEquals("task1", last["view"]!!.jsonObject["taskId"]?.jsonPrimitive?.content)
    }

    @Test
    fun sendFailure_keepsDirty_forNextFlush() {
        val p = publisher()
        p.setBridge(bridgeA)
        p.request("ep-1") // 发一条（view=null，seq=1）
        accept = false // send 失败（缓冲满/断连）
        p.update(bridgeA, view) // 失败：不进 sent、保 dirty
        assertEquals(1, sent.size) // 仅 request 那条
        accept = true
        p.flush() // 官方语义：dirty 保留，下次 flush 重发
        assertEquals(2, sent.size)
        assertEquals(
            "task1",
            sent
                .last()["view"]!!
                .jsonObject["taskId"]
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun epochRotation_resetsSeq() {
        val p = publisher()
        p.setBridge(bridgeA)
        p.request("ep-1")
        p.update(bridgeA, view) // seq=2
        p.update(bridgeA, null) // seq=3
        val seqAfterNull =
            sent
                .last()["seq"]
                ?.jsonPrimitive
                ?.content
                ?.toLongOrNull()
        assertEquals(3L, seqAfterNull)
        p.request("ep-2") // epoch 变化 → seq 归零后自增 → 1
        val seqAfterRotate =
            sent
                .last()["seq"]
                ?.jsonPrimitive
                ?.content
                ?.toLongOrNull()
        assertEquals(1L, seqAfterRotate)
        val rotatedEpoch = sent.last()["epoch"]?.jsonPrimitive?.content
        assertEquals("ep-2", rotatedEpoch)
        assertTrue(sent.last()["view"] is JsonNull)
    }

    @Test
    fun seed_onlyAdoptsWhenNoEpochYet() {
        val p = publisher()
        p.setBridge(bridgeA) // 桥先行（官方：配对请求可能先于 bootstrap）
        p.seed("ep-boot") // 无 epoch：采纳
        assertEquals(1, sent.size)
        val firstEpoch = sent.last()["epoch"]?.jsonPrimitive?.content
        assertEquals("ep-boot", firstEpoch)
        p.seed("ep-late") // 迟到 bootstrap：不得把更新 epoch 改回去（不重置不重发）
        assertEquals(1, sent.size)
        val keptEpoch = sent.last()["epoch"]?.jsonPrimitive?.content
        assertEquals("ep-boot", keptEpoch)
    }

    @Test
    fun disconnected_clearsEpoch_butKeepsDirty() {
        val p = publisher()
        p.setBridge(bridgeA)
        p.request("ep-1")
        p.disconnected()
        p.flush() // 无 epoch → 不发
        assertEquals(1, sent.size) // 仅 request 那一条
        p.request("ep-2") // 恢复后重新声明
        assertEquals(2, sent.size)
        assertEquals("ep-2", sent.last()["epoch"]?.jsonPrimitive?.content)
    }

    @Test
    fun newBridge_resetsView_andLateCallbacksFromOldBridgeAreIgnored() {
        val p = publisher()
        p.setBridge(bridgeA)
        p.request("ep-1")
        p.update(bridgeA, view) // seq=2（view=task1）
        val before = sent.size
        p.setBridge(bridgeB) // 新桥：view=null（初始只是导航意图），重发
        assertEquals(before + 1, sent.size)
        assertTrue("新桥首条上报 view 应为 null", sent.last()["view"] is JsonNull)
        assertEquals("sidB", sent.last()["bridgeSessionId"]?.jsonPrimitive?.content)
        // 旧桥的迟到回调（同 sid 不同 generation / 旧实例）不得清掉新 bridge 的可见事实
        p.update(bridgeA2, view)
        p.update(bridgeA, view)
        assertEquals(before + 1, sent.size) // 均被 matches 挡住，无新增发送
    }

    @Test
    fun sameBridgeIdentity_isMatched_noReset() {
        val p = publisher()
        p.setBridge(bridgeA)
        p.request("ep-1")
        p.update(bridgeA, view)
        val before = sent.size
        p.setBridge(bridgeA) // 同身份：matches → 不重置 view、不重发
        assertEquals(before, sent.size)
    }
}
