package com.zcode.remote.relay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 新建工作区（F3）：请求帧形状（schema 对齐官方 webRemoteControlActions.ts）+ 名称校验 + 应答解析。 */
class WorkspaceCreateTest {
    @Test
    fun `请求帧字段与官方schema一致`() {
        val f = BridgeFrames.workspaceCreateRequest("ws-123", "my-project").jsonObject
        assertEquals("workspace-create-request", f["zcode_type"]!!.jsonPrimitive.content)
        assertEquals("ws-123", f["requestId"]!!.jsonPrimitive.content)
        assertEquals("my-project", f["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `名称校验对齐官方规则`() {
        assertNull("合法名称", BridgeFrames.WorkspaceNameValidator.validate("my-project"))
        assertNull("前后空白由调用方 trim（validator 对纯空白判 required）", BridgeFrames.WorkspaceNameValidator.validate(" a b "))
        assertEquals("required", BridgeFrames.WorkspaceNameValidator.validate(""))
        assertEquals("required", BridgeFrames.WorkspaceNameValidator.validate("   "))
        assertEquals("separator", BridgeFrames.WorkspaceNameValidator.validate("a/b"))
        assertEquals("separator", BridgeFrames.WorkspaceNameValidator.validate("a\\b"))
        assertEquals("separator", BridgeFrames.WorkspaceNameValidator.validate("."))
        assertEquals("separator", BridgeFrames.WorkspaceNameValidator.validate(".."))
    }

    @Test
    fun `成功应答解析出workspacePath`() {
        val payload =
            Json
                .parseToJsonElement(
                    // 字段级 schema 对齐官方 WebRemoteControlWorkspaceCreateResponsePayload（success 分支）
                    """
                    {"zcode_type":"workspace-create-response","requestId":"ws-1",
                     "success":true,"workspacePath":"C:/Users/u/ZCodeProject/demo",
                     "workspaceKey":"C:/Users/u/ZCodeProject/demo"}
                    """.trimIndent(),
                ).jsonObject
        val r = BridgeFrames.workspaceCreateResultOf(payload)
        assertEquals(true, r.success)
        assertEquals("C:/Users/u/ZCodeProject/demo", r.workspacePath)
        assertNull(r.error)
    }

    @Test
    fun `失败应答解析出error`() {
        val payload =
            Json
                .parseToJsonElement(
                    """{"zcode_type":"workspace-create-response","requestId":"ws-1","success":false,"error":"盘符不可用"}""",
                ).jsonObject
        val r = BridgeFrames.workspaceCreateResultOf(payload)
        assertEquals(false, r.success)
        assertEquals("盘符不可用", r.error)
    }

    @Test
    fun `success缺失时不去认领`() {
        val payload =
            Json
                .parseToJsonElement(
                    """{"zcode_type":"workspace-create-response","requestId":"ws-1"}""",
                ).jsonObject
        assertTrue("success 为 null，编排层据此跳过 host 回调", BridgeFrames.workspaceCreateResultOf(payload).success !in listOf(true, false))
    }
}
