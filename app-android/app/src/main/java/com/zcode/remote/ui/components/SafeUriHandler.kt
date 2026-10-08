package com.zcode.remote.ui.components

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler

/**
 * 只放行 http/https 的 [UriHandler]。
 *
 * 会话正文里的链接来自模型输出，内容不受我们控制。若直接交给系统 ACTION_VIEW，
 * `file://`、`content://`、`intent://`、`javascript:` 这类 scheme 可能被已安装应用
 * 注册的 intent-filter 接走，形成越权跳转（包括启动本机其它 App 的内部页面）。
 * 这里做白名单收敛：非 http(s) 一律拦下并提示。
 */
private class SafeUriHandler(
    private val delegate: UriHandler,
    private val onBlocked: (String) -> Unit,
) : UriHandler {

    override fun openUri(uri: String) {
        val scheme = uri.substringBefore(':', missingDelimiterValue = "").lowercase()
        if (scheme == "http" || scheme == "https") {
            delegate.openUri(uri)
        } else {
            onBlocked(uri)
        }
    }
}

/**
 * 取得带 scheme 白名单的 [UriHandler]，用于包裹 Markdown 渲染以接管链接点击。
 *
 * 用法：
 * ```
 * CompositionLocalProvider(LocalUriHandler provides rememberSafeUriHandler()) { ... }
 * ```
 */
@Composable
fun rememberSafeUriHandler(): UriHandler {
    val delegate = LocalUriHandler.current
    val context = LocalContext.current
    return remember(delegate, context) {
        SafeUriHandler(delegate) {
            Toast.makeText(context, "已拦截非 http(s) 链接", Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * 判断链接 scheme 是否在白名单内。抽成纯函数以便单测覆盖。
 */
fun isAllowedLinkScheme(uri: String): Boolean {
    val scheme = uri.substringBefore(':', missingDelimiterValue = "").lowercase()
    return scheme == "http" || scheme == "https"
}
