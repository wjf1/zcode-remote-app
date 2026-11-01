package com.zcode.remote

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.zcode.remote.storage.PairedDevice
import com.zcode.remote.storage.TokenStore
import com.zcode.remote.util.ZLog

/**
 * V2 登录配对页（PROTOCOL.md §11）——T8 结构性修复：WebView 宿主从 Compose 布局
 * （AndroidView 内嵌）拆成独立 Activity。
 *
 * 背景：HyperOS + WebView 153 上，Compose AndroidView 内嵌 WebView 间歇性 attach 到
 * 无效 surface——chromium 渲染进程活着但整页内容不上屏，登录按钮点不动（进程存活、
 * 无 ANR，5 轮渲染层修复未根治，见 HANDOFF「App 内 WebView 登录卡死」）。改为传统
 * View 层级的 Activity 宿主后，WebView 自 [onCreate] 起就在稳定的 View 树里，
 * 无 Compose 重组/窗口测量时序参与，该竞态面结构性消除。
 *
 * 登录行为契约与原 LoginWebViewDialog 完全一致（纯逻辑在 [WebViewLogin]）：
 * - 回调 URL 携带 `hash`（V1 凭据形态）→ `augmentFromUrl` 补齐凭据后回传设备；
 * - localStorage 出现 `zcodejwttoken` → 持久化 JWT（[TokenStore.writeZcodeJwt]）并原样回传；
 * - 不拦截任何导航（bigmodel.cn / chat.z.ai 等三方登录页需正常跳转）；
 * - Cookie 持久化开启（登录态跨会话保留）；UA 去掉 `; wv` 内嵌标记。
 *
 * 结果回传：RESULT_OK + 设备字段 extras（[PairedDevice] 非 @Serializable，简单类型
 * 逐字段传输）；关闭/返回键未 setResult → RESULT_CANCELED，调用方仅结束本次登录。
 * 调用方 MainActivity 经 ActivityResultContracts.StartActivityForResult 接收。
 *
 * 生命周期：onPause/onResume 转发 WebView 同名调用（否则从别的 App 切回时 WebView
 * 停在暂停态、整页白屏——2026-10-25 真机实证）；onDestroy flush Cookie 并 destroy。
 * 进程被杀重建：intent extras 原样恢复，WebView 重新加载落地页（Cookie 保留登录态，
 * 用户直接重登即可），无需额外的 savedInstanceState 逻辑。
 */
class WebViewActivity : ComponentActivity() {
    private lateinit var device: PairedDevice
    private lateinit var tokenStore: TokenStore
    private var webView: WebView? = null
    private val completionGuard = LoginCompletionGuard()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val parsed = deviceFromIntent(intent)
        if (parsed == null) {
            ZLog.w(TAG, "login: extras 缺失或损坏，直接退出")
            finish()
            return
        }
        device = parsed
        tokenStore = TokenStore(this)
        webView = buildWebView()
        setContentView(buildContentView())
        // 传统 View 层级：WebView 在 setContentView 时已进入稳定 View 树，直接加载即可
        // （原 Compose AndroidView 方案需 post 到首帧后再 loadUrl——该时序坑随宿主迁移一并消除）。
        webView?.loadUrl(landingUrl(device))
    }

    /** 生命周期跟随：回前台恢复渲染（否则切回时 WebView 停在暂停态，整页白屏无按钮）。 */
    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onPause() {
        webView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        CookieManager.getInstance().flush()
        webView?.let { wv ->
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.destroy()
        }
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(): WebView =
        WebView(this).apply {
            setBackgroundColor(BACKGROUND_COLOR)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // 真机实证（2026-10-25 小米 15 Pro）：默认 UA 的 "; wv" 内嵌标记会让
            // bigmodel 登录页 / 微信 OAuth 二维码 iframe 拒绝渲染（整页白屏无按钮）。
            // 去掉 wv 标记按移动浏览器身份加载。
            settings.userAgentString = WebSettings.getDefaultUserAgent(this@WebViewActivity).replace("; wv)", ")")
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient =
                object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean = false // 登录互信需要跨域跳转，全部放行

                    override fun onPageFinished(
                        view: WebView,
                        url: String,
                    ) {
                        onLoginPageFinished(url)
                    }
                }
        }

    private fun onLoginPageFinished(url: String) {
        if (completionGuard.isCompleted) return
        // ① 登录回调把凭据发回 URL（V1 形态 hash）→ 直接补齐
        if (url.contains("hash=")) {
            val withHash = augmentFromUrl(device, url)
            if (withHash != null) {
                completionGuard.tryMarkCompleted()
                ZLog.i(TAG, "login: url 携带 hash，凭据补齐")
                harvestJwt() // jwt 若同时就绪则顺手持久化（多设备管理 / relay token 用）
                completeLogin(withHash)
                return
            }
        }
        // ② localStorage 出现 zcodejwttoken → 登录完成（凭据可能不经 URL）
        harvestJwt()
    }

    private fun harvestJwt() {
        runCatching {
            webView?.evaluateJavascript("localStorage.getItem('zcodejwttoken')") { v ->
                val jwt = v?.trim()?.removeSurrounding("\"")?.takeIf { it.isNotBlank() && it != "null" }
                if (jwt != null) tokenStore.writeZcodeJwt(jwt)
                if (jwt != null && completionGuard.tryMarkCompleted()) {
                    ZLog.i(TAG, "login: zcodejwttoken 已就绪（len=${jwt.length}）")
                    completeLogin(device)
                }
            }
        }.onFailure {
            ZLog.w(TAG, "harvestJwt failed: ${it.message}")
        }
    }

    private fun completeLogin(result: PairedDevice) {
        setResult(RESULT_OK, deviceResultIntent(result))
        finish()
    }

    /** 结果 Intent：仅设备 extras（RESULT_OK 由 setResult 携带），字段与启动 Intent 同构。 */
    private fun deviceResultIntent(device: PairedDevice): Intent = Intent().also { putDeviceExtras(it, device) }

    /** 传统 View 层级构建标题栏 + 提示 + WebView（深色主题固定色，与项目深色基线一致）。 */
    private fun buildContentView(): View {
        val density = resources.displayMetrics.density

        fun dp(value: Int): Int = (value * density).toInt()

        val closeButton =
            ImageButton(this).apply {
                setImageResource(R.drawable.ic_close)
                val bgValue = TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, bgValue, true)
                setBackgroundResource(bgValue.resourceId)
                contentDescription = getString(R.string.login_pairing_close)
                setOnClickListener { finish() } // 未 setResult → RESULT_CANCELED
            }

        val title =
            TextView(this).apply {
                text =
                    getString(
                        R.string.login_pairing_title,
                        device.deviceName ?: getString(R.string.login_pairing_default_device),
                    )
                textSize = 16f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(TITLE_COLOR)
                gravity = Gravity.CENTER_VERTICAL
            }

        val header =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(2), dp(4), dp(2))
                addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                addView(closeButton, LinearLayout.LayoutParams(dp(40), dp(40)))
            }

        val hint =
            TextView(this).apply {
                text = getString(R.string.login_pairing_hint)
                textSize = 12f
                setTextColor(HINT_COLOR)
                setPadding(dp(16), 0, dp(16), dp(8))
            }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BACKGROUND_COLOR)
            addView(
                header,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            addView(
                hint,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    companion object {
        private const val TAG = "WebViewLogin"

        // 深色主题固定色（原 Compose 版读 MaterialTheme.colorScheme；宿主迁出后按项目深色基线等价取值）
        private val BACKGROUND_COLOR = 0xFF161616.toInt()
        private val TITLE_COLOR = 0xFFE6E6E6.toInt()
        private val HINT_COLOR = 0xFF9E9E9E.toInt()

        private const val EXTRA_DEVICE_SID = "login.deviceSid"
        private const val EXTRA_PASS_HASH = "login.passHash"
        private const val EXTRA_DEVICE_MID = "login.deviceMid"
        private const val EXTRA_DEVICE_NAME = "login.deviceName"
        private const val EXTRA_REMOTE_URL = "login.remoteUrl"
        private const val EXTRA_PAIRED_AT_MS = "login.pairedAtMs"

        /** [PairedDevice] 非 @Serializable：登录流转用简单类型逐字段传输（启动与结果回传同构）。 */
        internal fun createIntent(
            context: Context,
            device: PairedDevice,
        ): Intent = Intent(context, WebViewActivity::class.java).also { putDeviceExtras(it, device) }

        private fun putDeviceExtras(
            intent: Intent,
            device: PairedDevice,
        ) {
            intent.putExtra(EXTRA_DEVICE_SID, device.deviceSid)
            intent.putExtra(EXTRA_PASS_HASH, device.passHash)
            intent.putExtra(EXTRA_DEVICE_MID, device.deviceMid)
            intent.putExtra(EXTRA_DEVICE_NAME, device.deviceName)
            intent.putExtra(EXTRA_REMOTE_URL, device.remoteUrl)
            intent.putExtra(EXTRA_PAIRED_AT_MS, device.pairedAtMs)
        }

        /** 从启动/结果 Intent 还原设备；deviceSid / remoteUrl 缺失（extras 损坏）返回 null。 */
        internal fun deviceFromIntent(intent: Intent?): PairedDevice? {
            val sid = intent?.getStringExtra(EXTRA_DEVICE_SID)?.takeIf { it.isNotBlank() } ?: return null
            val remoteUrl = intent.getStringExtra(EXTRA_REMOTE_URL)?.takeIf { it.isNotBlank() } ?: return null
            return PairedDevice(
                deviceSid = sid,
                passHash = intent.getStringExtra(EXTRA_PASS_HASH),
                deviceMid = intent.getStringExtra(EXTRA_DEVICE_MID),
                deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME),
                remoteUrl = remoteUrl,
                pairedAtMs = intent.getLongExtra(EXTRA_PAIRED_AT_MS, 0L),
            )
        }
    }
}
