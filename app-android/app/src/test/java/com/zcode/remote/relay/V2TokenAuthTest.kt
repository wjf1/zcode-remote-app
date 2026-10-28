package com.zcode.remote.relay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * T10：`fetchRelayToken` 的失败分类（401 与网络失败必须区分）。
 *
 * 背景（beta54 任务书 T10）：原实现 `check(resp.code == 200)` 把所有非 200 压成一个
 * `IllegalStateException("HTTP 401")`，`acquireV2Token` 又一律报 `FailureReason.NETWORK`
 * ——于是**登录态失效被当成网络抖动**，界面进入无限重连转圈，用户看不出要重新登录。
 *
 * 这里的分类判据是 [RelayClient.isAuthFailure]，它是纯函数，本项目无 Robolectric，
 * 纯函数是唯一可守形式。CLAUDE/AGENTS 规范：每条断言都要能在修复前失败。
 */
class V2TokenAuthTest {
    private val client =
        RelayClient(
            device =
                com.zcode.remote.storage.PairedDevice(
                    deviceSid = "d_test",
                    passHash = null, // null → V2 栈，本测试不实际连接
                    deviceMid = "m_test",
                    deviceName = "test",
                    remoteUrl = "wss://zcode.z.ai/ws",
                ),
        )

    @Test
    fun tokenAuthException_isAuthFailure() {
        assertTrue(
            "401/403 必须判为鉴权失败（终态，不重连）",
            client.isAuthFailure(V2AccountApi.TokenAuthException(401)),
        )
        assertTrue(
            "403 同理",
            client.isAuthFailure(V2AccountApi.TokenAuthException(403)),
        )
    }

    @Test
    fun businessCodeRejection_isAuthFailure() {
        // 服务端用 HTTP 200 + envelope code!=0 表示「该 JWT 无权换 token」，
        // 由 runCatching 内的 check() 抛 IllegalStateException("api code=…")
        assertTrue(
            "业务码拒绝属于鉴权问题，不该反复重连",
            client.isAuthFailure(IllegalStateException("api code=10001 msg=invalid token")),
        )
    }

    @Test
    fun networkErrors_areNotAuthFailure() {
        assertFalse(
            "DNS 解析失败是网络问题，应重连",
            client.isAuthFailure(UnknownHostException("zcode.z.ai")),
        )
        assertFalse(
            "超时是网络问题，应重连",
            client.isAuthFailure(SocketTimeoutException("timeout")),
        )
        assertFalse(
            "连接重置是网络问题，应重连",
            client.isAuthFailure(IOException("Connection reset")),
        )
    }

    @Test
    fun nullAndUnexpected_areNotAuthFailure() {
        assertFalse("null 不是鉴权失败", client.isAuthFailure(null))
        assertFalse(
            "非 api code 的 IllegalStateException（如无 token 字段）不算鉴权失败",
            client.isAuthFailure(IllegalStateException("响应无 token 字段")),
        )
        assertFalse(
            "任意 RuntimeException 不应被误判",
            client.isAuthFailure(RuntimeException("boom")),
        )
    }
}
