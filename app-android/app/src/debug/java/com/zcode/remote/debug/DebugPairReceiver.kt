package com.zcode.remote.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zcode.remote.storage.MultiDeviceStore
import com.zcode.remote.storage.PairedDevice

/**
 * 仅 debug 构建存在：注入配对凭据，跳过扫码/粘贴链接（真机验收用，
 * 免去 adb input text 被 IME 打乱的问题）。注入后 force-stop 并重启 App
 * 即按「启动时恢复活跃设备」路径自动连接。
 *
 *   adb shell am broadcast -n com.zcode.remote/.debug.DebugPairReceiver \
 *        -a com.zcode.remote.action.DEBUG_PAIR --es dbg_token <token> \
 *        --es sid <deviceSid> --es hash <passHash> --es mid <deviceMid> [--es name <名>] [--es url <配对链接前缀>]
 *
 * 安全门（任务书 §11.3.2）：必须携带 DebugInjectionGuard 令牌，否则丢弃。
 */
class DebugPairReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!DebugInjectionGuard.allow(context, intent, setOf(ACTION))) return
        val sid = intent.getStringExtra(EXTRA_SID)?.takeIf { it.isNotBlank() } ?: return
        val hash = intent.getStringExtra(EXTRA_HASH)?.takeIf { it.isNotBlank() } ?: return
        val dev = PairedDevice(
            deviceSid = sid,
            passHash = hash,
            deviceMid = intent.getStringExtra(EXTRA_MID),
            deviceName = intent.getStringExtra(EXTRA_NAME) ?: "Debug注入",
            remoteUrl = intent.getStringExtra(EXTRA_URL) ?: "https://zcode.z.ai/remote/v4",
        )
        MultiDeviceStore(context).upsertActive(dev)
    }

    companion object {
        const val ACTION = "com.zcode.remote.action.DEBUG_PAIR"
        const val EXTRA_SID = "sid"
        const val EXTRA_HASH = "hash"
        const val EXTRA_MID = "mid"
        const val EXTRA_NAME = "name"
        const val EXTRA_URL = "url"
    }
}
