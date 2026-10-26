package com.zcode.remote.ui.screens

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.zcode.remote.R
import com.zcode.remote.storage.PairedDevice
import com.zcode.remote.storage.TokenStore
import com.zcode.remote.util.ZLog

/**
 * V2 登录配对页（PROTOCOL.md §11）：新版二维码（≥3.16）不含 hash，配对凭据由账号登录链路下发。
 *
 * 实现：内嵌 WebView 打开官方落地页（`remote/v4?sid=..&mid=..`），用户选择与 PC 端相同的
 * 登录平台（Z.ai / BigModel）完成 OAuth/互信登录。完成后：
 * - 若回调 URL 携带 `hash`（V1 凭据形态，登录链路下发）→ 补齐凭据直接回调；
 * - 若 localStorage 出现 `zcodejwttoken` → 持久化 JWT（多设备管理/relay token 用），
 *   并以原设备回调——连接层（RelayClient）自适应：auth_ack 直达 / challenge 时按凭据有无给出指引。
 *
 * 不拦截任何导航（bigmodel.cn / chat.z.ai 等三方登录页需正常跳转）；
 * Cookie 持久化开启，登录态跨会话保留。
 */
@Composable
fun LoginWebViewDialog(
    device: PairedDevice,
    onDismiss: () -> Unit,
    onLoggedIn: (PairedDevice) -> Unit,
) {
    val context = LocalContext.current
    val tokenStore = remember { TokenStore(context) }
    val bg = MaterialTheme.colorScheme.surface

    // 全屏普通组件（非 Dialog）：真机实证（2026-10-25 小米 15 Pro）Compose Dialog 内嵌
    // AndroidView(WebView) 存在窗口测量竞态——WebView 间歇性 attach 到无效 surface，
    // chromium 渲染进程活着但整页内容不上屏（5 次复现 3 次，与冷/热启动无关）。
    // 直接铺在 Activity 布局里（后声明绘制在上层），结构性消除该竞态。
    run {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text(
                        text =
                            stringResource(
                                R.string.login_pairing_title,
                                device.deviceName ?: stringResource(R.string.login_pairing_default_device),
                            ),
                        style = MaterialTheme.typography.titleMedium,
                        modifier =
                            Modifier
                                .weight(1f)
                                .padding(start = 12.dp),
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.login_pairing_close))
                    }
                }
                Text(
                    text = stringResource(R.string.login_pairing_hint),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                var finished by remember { mutableStateOf(false) }
                var webViewRef by remember { mutableStateOf<WebView?>(null) }

                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            webViewRef = this
                            setBackgroundColor(bg.toArgb())
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            // 真机实证（2026-10-25 小米 15 Pro）：默认 UA 的 "; wv" 内嵌标记会让
                            // bigmodel 登录页 / 微信 OAuth 二维码 iframe 拒绝渲染（整页白屏无按钮）。
                            // 去掉 wv 标记按移动浏览器身份加载。
                            settings.userAgentString =
                                android.webkit.WebSettings
                                    .getDefaultUserAgent(ctx)
                                    .replace("; wv)", ")")
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webViewClient =
                                object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView,
                                        request: WebResourceRequest,
                                    ): Boolean = false // 登录互信需要跨域跳转，全部放行

                                    @SuppressLint("SetJavaScriptEnabled")
                                    override fun onPageFinished(
                                        view: WebView,
                                        url: String,
                                    ) {
                                        if (finished) return
                                        // ① 登录回调把凭据发回 URL（V1 形态 hash）→ 直接补齐
                                        if (url.contains("hash=")) {
                                            val withHash = augmentFromUrl(device, url)
                                            if (withHash != null) {
                                                finished = true
                                                ZLog.i(TAG, "login: url 携带 hash，凭据补齐")
                                                harvestJwt(view, tokenStore) {}
                                                onLoggedIn(withHash)
                                                return
                                            }
                                        }
                                        // ② localStorage 出现 zcodejwttoken → 登录完成（凭据可能不经 URL）
                                        harvestJwt(view, tokenStore) { jwt ->
                                            if (!finished && jwt != null) {
                                                finished = true
                                                ZLog.i(TAG, "login: zcodejwttoken 已就绪（len=${jwt.length}）")
                                                onLoggedIn(device)
                                            }
                                        }
                                    }
                                }
                            // 冷启动即弹深链（onCreate 内 handleDeepLink）时 Dialog 首帧未完成布局，
                            // 直接 loadUrl 会渲染到未 attach 的 surface（chromium 进程活着但整页无内容，
                            // 2026-10-25 真机实证）。post 到视图消息队列，attach 完成后再加载。
                            post { loadUrl(landingUrl(device)) }
                        }
                    },
                )
                // 生命周期跟随：切后台/回前台转发 onPause/onResume —— 否则从别的 App 切回时
                // WebView 停在暂停态，整页白屏无按钮（2026-10-25 真机实证）。
                val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer =
                        androidx.lifecycle.LifecycleEventObserver { _, event ->
                            when (event) {
                                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> webViewRef?.onResume()
                                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> webViewRef?.onPause()
                                else -> Unit
                            }
                        }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                        CookieManager.getInstance().flush()
                    }
                }
            }
        }
    }
}

/** 落地页 URL：QR 的 base + 配对参数（sid 必带，mid/name 尽量带）。 */
internal fun landingUrl(device: PairedDevice): String {
    val base = device.remoteUrl.substringBefore("?")
    val params =
        buildList {
            add("sid=${device.deviceSid}")
            device.deviceMid?.let { add("mid=$it") }
            device.deviceName?.let { add("name=${android.net.Uri.encode(it)}") }
        }
    return "$base?${params.joinToString("&")}"
}

/** 从 URL 中提取 hash/t 补齐凭据；无 hash 返回 null。 */
internal fun augmentFromUrl(
    device: PairedDevice,
    url: String,
): PairedDevice? {
    val uri = runCatching { android.net.Uri.parse(url.replace("+", "%2B")) }.getOrNull() ?: return null
    val hash = uri.getQueryParameter("hash")?.takeIf { it.isNotBlank() } ?: return null
    return device.copy(
        passHash = hash,
        deviceMid = uri.getQueryParameter("mid")?.takeIf { it.isNotBlank() } ?: device.deviceMid,
        deviceName = uri.getQueryParameter("name") ?: device.deviceName,
    )
}

private fun harvestJwt(
    view: WebView,
    store: TokenStore,
    onResult: (String?) -> Unit,
) {
    runCatching {
        view.evaluateJavascript("localStorage.getItem('zcodejwttoken')") { v ->
            val jwt = v?.trim()?.removeSurrounding("\"")?.takeIf { it.isNotBlank() && it != "null" }
            if (jwt != null) store.writeZcodeJwt(jwt)
            onResult(jwt)
        }
    }.onFailure {
        ZLog.w(TAG, "harvestJwt failed: ${it.message}")
        onResult(null)
    }
}

private const val TAG = "LoginWebView"
