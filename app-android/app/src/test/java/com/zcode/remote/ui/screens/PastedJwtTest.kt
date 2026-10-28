package com.zcode.remote.ui.screens

import com.zcode.remote.R
import com.zcode.remote.util.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T9：粘贴令牌入口的纯函数（[normalizePastedJwt] / [pastedJwtRejection]）。
 *
 * 动机：App 内 WebView 登录在 HyperOS + WebView 153 上点「登录」无响应（beta51 起已知、
 * 5 轮未根治）。用户从浏览器复制 JWT 时经常带上引号、`Bearer ` 前缀、换行（控制台
 * 复制多行值会把换行一起带出来）——直接落库就是一个永远换不到 token 的垃圾值，
 * 而用户看到的是「保存成功」，属于比不提供入口更糟的体验。
 *
 * 本测试钉住清理口径与下限判据。
 */
class PastedJwtTest {
    @Test
    fun plainJwt_isUnchanged() {
        assertEquals("abc.def.ghi", normalizePastedJwt("abc.def.ghi"))
    }

    @Test
    fun trimsWhitespaceAndNewlines() {
        assertEquals("abc.def", normalizePastedJwt("  abc.def  "))
        assertEquals("abc.def", normalizePastedJwt("abc.def\n"))
        assertEquals("abcdef", normalizePastedJwt("abc\ndef"))
        assertEquals("abcdef", normalizePastedJwt("abc\tdef"))
    }

    @Test
    fun stripsSurroundingQuotes() {
        assertEquals("abc.def", normalizePastedJwt("\"abc.def\""))
        assertEquals("abc.def", normalizePastedJwt("'abc.def'"))
        // 只剥成对引号；单边引号视为令牌内容的一部分（不做投机清理）
        assertEquals("\"abc.def", normalizePastedJwt("\"abc.def"))
    }

    @Test
    fun stripsBearerPrefix() {
        assertEquals("abc.def", normalizePastedJwt("Bearer abc.def"))
        assertEquals("abc.def", normalizePastedJwt("bearer abc.def"))
    }

    @Test
    fun stripsBom() {
        assertEquals("abc.def", normalizePastedJwt("\uFEFFabc.def"))
    }

    @Test
    fun handlesCombinedNoise() {
        assertEquals("abc.def", normalizePastedJwt("  \"Bearer abc.def\"  \n"))
    }

    @Test
    fun empty_isRejected() {
        assertEquals(
            UiText.Res(R.string.account_token_paste_empty),
            pastedJwtRejection(""),
        )
    }

    @Test
    fun tooShort_isRejected() {
        assertEquals(
            UiText.Res(R.string.account_token_paste_too_short),
            pastedJwtRejection("abc.def"),
        )
    }

    @Test
    fun plausibleLength_isAccepted() {
        // 正常 JWT 远超 16 字符；此处只验证下限判据不误拒
        assertNull(pastedJwtRejection("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.sig"))
    }
}
