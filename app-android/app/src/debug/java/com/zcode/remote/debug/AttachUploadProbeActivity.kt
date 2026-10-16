package com.zcode.remote.debug

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.zcode.remote.service.RemoteConnectionManager
import com.zcode.remote.util.ZLog
import java.io.ByteArrayInputStream

/**
 * 仅 debug 变体的**附件上传端到端探针**（真机走查用，**不进 release 包**）。
 *
 * 存在理由：MIUI 的 SAF 文件选择器要求指纹解锁（`com.miui.applicationlock.AppLockActivity`），
 * adb 走查无法进入，故无法用「真人点选文件」跑通上传（而真机是本项目唯一可用的 UI 验收环境）。
 * 本探针改从**进程内的真实连接栈**（`RemoteConnectionManager.conversation`）发起一次真上传：
 * 走查 #2 重构后的 `uploadAttachment` 四步（begin → chunk → commit）全程真实 RPC，
 * 仅**内容源**用内存字节（不经 SAF）——协议路径、分片、校验、ack 处理都是真的。
 *
 * 用法（先确保 App 已连接并已进入某会话，使其完成订阅）：
 * `adb shell am start -n com.zcode.remote/.debug.AttachUploadProbeActivity --ei bytes 300000`
 * 结果写 logcat（tag `AttachProbe`）并 Toast 提示。
 */
class AttachUploadProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val size = intent.getIntExtra("bytes", 300_000).coerceAtLeast(1)
        val bytes = ByteArray(size) { (it % 251).toByte() }
        val conv = RemoteConnectionManager.conversation
        if (conv == null) {
            report("FAIL 连接栈不可用（未连接 / 未订阅）")
            return
        }
        report("START size=$size")
        val started = System.currentTimeMillis()
        // ⚠️ scope 必须给 Main：`onResult` 在传入 scope 的上下文回调（生产侧传 viewModelScope =
        // Main.immediate，回调天然在主线程）。探针若传 Dispatchers.Default，会在 IO 线程上
        // 执行 Toast/finish → "Can't toast on a thread that has not called Looper.prepare()"。
        val mainScope = lifecycleScope
        conv.uploadAttachment(
            uploadId = "probe-${System.currentTimeMillis()}",
            fileName = "probe-$size.bin",
            mime = "application/octet-stream",
            totalBytes = size.toLong(),
            scope = mainScope,
            openAt = { offset -> ByteArrayInputStream(bytes, offset.toInt(), bytes.size - offset.toInt()) },
            onProgress = { up, total -> ZLog.i(TAG, "progress $up/$total") },
        ) { r ->
            val ms = System.currentTimeMillis() - started
            r.fold(
                onSuccess = { report("OK ref=${it.ref} name=${it.fileName} bytes=${it.bytes} ms=$ms") },
                onFailure = { report("FAIL ${it.message} ms=$ms") },
            )
            finish()
        }
    }

    private fun report(msg: String) {
        // ZLog.e 在 release 也输出，但本类只在 debug 源集；用 println + Toast 便于 adb 抓取
        ZLog.e(TAG, msg)
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        println("[AttachProbe] $msg")
    }

    companion object {
        private const val TAG = "AttachProbe"
    }
}
