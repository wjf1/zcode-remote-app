package com.zcode.remote.debug

import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.UUID

/**
 * debug 广播注入的一次性令牌门（任务书 §11.3.2）。
 *
 * 仅 debug 构建存在。`DebugPairReceiver` / `DebugApprovalReceiver` 为 debug 专用组件，
 * 但 android:exported=true（否则 adb shell 无法广播）。为使外部无法随意触发，收件人必须
 * 同时满足：**action 严格匹配** + **携带本机令牌**。
 *
 * 令牌在首次 debug 运行时随机生成并持久化（进程重启后仍有效，方便真机脚本复用），
 * 可用 `adb shell run-as com.zcode.remote cat shared_prefs/debug_injection.xml`
 * 或在 debug logcat 里读取。Release 构建不含本文件，也不含这两个 receiver。
 *
 * 用法（在 adb 命令末尾追加 `--es dbg_token <token>`）：
 *   adb shell am broadcast -n com.zcode.remote/.debug.DebugPairReceiver \
 *        -a com.zcode.remote.action.DEBUG_PAIR --es dbg_token <token> --es sid ... --es hash ...
 */
internal object DebugInjectionGuard {

    const val EXTRA_TOKEN = "dbg_token"
    private const val PREFS = "debug_injection"
    private const val KEY_TOKEN = "token"

    private fun token(context: Context): String {
        val sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.getString(KEY_TOKEN, null)?.let { return it }
        val fresh = UUID.randomUUID().toString().replace("-", "")
        sp.edit().putString(KEY_TOKEN, fresh).apply()
        // 首次生成时打印一次，便于开发者取用；debug 构建的 logcat 本就是开发者可见面
        Log.i("DebugInjectionGuard", "已生成调试注入令牌（仅 debug 构建，持久化于共享偏好）：$fresh")
        return fresh
    }

    /** 严格 action 白名单 + 令牌校验；任一不满足即拒绝。 */
    fun allow(context: Context, intent: Intent, allowedActions: Set<String>): Boolean {
        if (intent.action !in allowedActions) return false
        val supplied = intent.getStringExtra(EXTRA_TOKEN) ?: return false
        // 常量时间比较，避免时序侧信道（令牌为低敏感调试值，仍按规范处理）
        return constantTimeEquals(supplied, token(context))
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val ab = a.toByteArray(Charsets.UTF_8)
        val bb = b.toByteArray(Charsets.UTF_8)
        if (ab.size != bb.size) return false
        var r = 0
        for (i in ab.indices) r = r or (ab[i].toInt() xor bb[i].toInt())
        return r == 0
    }
}
