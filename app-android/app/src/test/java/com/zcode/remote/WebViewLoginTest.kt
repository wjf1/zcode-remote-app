package com.zcode.remote

import com.zcode.remote.storage.PairedDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T8：登录 WebView 宿主迁出 Compose 后的纯逻辑层（[landingUrl] / [parseCallbackParams] /
 * [augmentFromUrl] / [LoginCompletionGuard]）。
 *
 * 原实现内联在 LoginWebViewDialog.kt 且依赖 android.net.Uri，不可 JVM 测——迁移时改用
 * java.net 等价实现，本测试钉住 URL 构造、回调解析（含字面 `+` 凭据保真）与完成守护语义。
 */
class WebViewLoginTest {
    private fun device(
        passHash: String? = null,
        mid: String? = null,
        name: String? = null,
    ) = PairedDevice(
        deviceSid = "sid-1",
        passHash = passHash,
        deviceMid = mid,
        deviceName = name,
        remoteUrl = "https://zcode.z.ai/remote/v4",
    )

    // ---------- landingUrl（路由构造） ----------

    @Test
    fun landingUrl_carriesSidOnStrippedBase() {
        assertEquals("https://zcode.z.ai/remote/v4?sid=sid-1", landingUrl(device()))
    }

    @Test
    fun landingUrl_stripsExistingQueryFromRemoteUrl() {
        val withQuery = device().copy(remoteUrl = "https://zcode.z.ai/remote/v4?legacy=1")
        assertEquals("https://zcode.z.ai/remote/v4?sid=sid-1", landingUrl(withQuery))
    }

    @Test
    fun landingUrl_appendsMidAndNameWithPercent20Spaces() {
        val full = device(mid = "mid-9", name = "My PC")
        assertEquals(
            "https://zcode.z.ai/remote/v4?sid=sid-1&mid=mid-9&name=My%20PC",
            landingUrl(full),
        )
    }

    // ---------- parseCallbackParams / augmentFromUrl（结果解析） ----------

    @Test
    fun augmentFromUrl_fillsHashFromCallbackUrl() {
        val paired = augmentFromUrl(device(), "https://zcode.z.ai/remote/v4?sid=sid-1&hash=abc123")
        assertEquals("abc123", paired?.passHash)
        assertNull(paired?.deviceMid)
    }

    @Test
    fun augmentFromUrl_preservesLiteralPlusInHash() {
        // hash 原文含字面 '+'（base64url 前原文）：预转义后必须原样还原，不得折叠成空格
        val paired = augmentFromUrl(device(), "https://zcode.z.ai/remote/v4?hash=ab+cd")
        assertEquals("ab+cd", paired?.passHash)
    }

    @Test
    fun augmentFromUrl_returnsNullWhenHashMissingOrBlank() {
        assertNull(augmentFromUrl(device(), "https://zcode.z.ai/remote/v4?sid=sid-1"))
        assertNull(augmentFromUrl(device(), "https://zcode.z.ai/remote/v4?hash="))
        assertNull(augmentFromUrl(device(), "https://zcode.z.ai/remote/v4"))
    }

    @Test
    fun augmentFromUrl_overridesMidAndNameWithoutMutatingOriginal() {
        val original = device(mid = "old-mid", name = "Old Name")
        val paired = augmentFromUrl(original, "https://zcode.z.ai/remote/v4?hash=h1&mid=new-mid&name=New%20Name")
        assertEquals("new-mid", paired?.deviceMid)
        assertEquals("New Name", paired?.deviceName)
        assertEquals("old-mid", original.deviceMid) // copy 语义：原实例不被就地修改
    }

    @Test
    fun parseCallbackParams_decodesPercentEscapes() {
        val params = parseCallbackParams("https://x.y/p?hash=a%2Bb&name=%E4%B8%AD")
        assertEquals("a+b", params?.get("hash"))
        assertEquals("中", params?.get("name"))
    }

    // ---------- LoginCompletionGuard（状态机） ----------

    @Test
    fun guard_completesExactlyOnce() {
        val guard = LoginCompletionGuard()
        assertTrue(guard.tryMarkCompleted())
        assertFalse(guard.tryMarkCompleted())
        assertTrue(guard.isCompleted)
    }

    @Test
    fun guard_freshInstanceIsIncomplete() {
        val guard = LoginCompletionGuard()
        assertFalse(guard.isCompleted)
        assertTrue(guard.tryMarkCompleted())
    }
}
