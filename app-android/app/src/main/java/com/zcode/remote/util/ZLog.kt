package com.zcode.remote.util

import android.util.Log
import com.zcode.remote.BuildConfig

/**
 * 统一日志门（Sprint 1 / P0-C）：debug 全量，release 静默。
 *
 * relay / rpc 层一律走这里，禁止直接调 android.util.Log —— 中继帧含全部会话正文与
 * HMAC 握手证明，明文进 logcat 等于把对话与凭据写给任何能 adb logcat / 读 bugreport 的人
 * （HANDOVER §8 凭据红线）。release 的 Log.i/d/v/w 另由 proguard-rules.pro 的
 * assumenosideeffects 做编译期剥离，与本门控形成双保险。
 */
object ZLog {
    @JvmStatic fun d(tag: String, msg: String) { if (BuildConfig.DEBUG) Log.d(tag, msg) }
    @JvmStatic fun i(tag: String, msg: String) { if (BuildConfig.DEBUG) Log.i(tag, msg) }
    @JvmStatic fun w(tag: String, msg: String) { if (BuildConfig.DEBUG) Log.w(tag, msg) }
    @JvmStatic fun w(tag: String, msg: String, tr: Throwable) { if (BuildConfig.DEBUG) Log.w(tag, msg, tr) }

    /** 错误在 release 也输出 —— 调用点必须保证 msg 只含元信息（状态码/计数），不含正文与凭据。 */
    @JvmStatic fun e(tag: String, msg: String) { Log.e(tag, msg) }
    @JvmStatic fun e(tag: String, msg: String, tr: Throwable) { Log.e(tag, msg, tr) }
}
