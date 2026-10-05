package com.zcode.remote.relay

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.zcode.remote.util.ZLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.random.Random

/**
 * 网络感知重连闸门（Sprint 1 / B 组，真机验收 2026-10-05 迭代）。
 *
 * 真机实证的两个事实驱动的设计：
 * ① OkHttp 对「网络层整体丢失」（飞行模式/关 Wi-Fi）的失败回调会**延迟到网络恢复时**
 *    才冒出（EOF on write）——断网期间连接静默死亡，心跳停止，用户毫无感知；
 * ② 若放任重连循环在断网期空转，退避计数会被快速失败烧到 48s 封顶，网络恢复后
 *    还要再等一整轮退避才重连。
 *
 * 因此本类做**常驻**网络监控（registerDefaultNetworkCallback）：
 * - onLost → 立即回调 [onLost]（RelayClient 马上断 socket + 调度重连，attempt 归零）；
 * - waitBeforeReconnect：退避后仍无网则挂起，onAvailable 即刻放行——恢复到重连零等待。
 */
class NetworkGate(private val cm: ConnectivityManager) {

    /** 网络丢失回调（由 RelayClient 注入：清理 socket 并调度重连）。 */
    @Volatile
    var onLost: (() -> Unit)? = null

    @Volatile
    private var available: Boolean = probeNow()

    @Volatile
    private var watcherRegistered = false

    private val waiterLock = Any()
    private var waiter: (() -> Unit)? = null

    private val watcher = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            ZLog.i(TAG, "gate: 网络恢复 onAvailable")
            available = true
            val w = synchronized(waiterLock) { waiter }
            if (w != null) {
                synchronized(waiterLock) { waiter = null }
                w()
            }
        }

        override fun onLost(network: Network) {
            // 双网并存时 onLost 只表示其中一条网没了；此时 activeNetwork 仍可用则不误报
            if (!probeNow()) {
                ZLog.i(TAG, "gate: 网络丢失 onLost")
                available = false
                onLost?.invoke()
            } else {
                ZLog.i(TAG, "gate: 某条网络 lost，但仍有可用网络，忽略")
            }
        }
    }

    /** 常驻监控（幂等）：App 连接期间持续感知网络变化。 */
    fun startWatch() {
        if (watcherRegistered) return
        val r = runCatching { cm.registerDefaultNetworkCallback(watcher) }
        watcherRegistered = r.isSuccess
        if (r.isFailure) ZLog.w(TAG, "gate: registerDefaultNetworkCallback 失败，退回纯退避语义")
    }

    /** RelayClient.networkWait 注入点：退避 → 无网挂起等恢复 → 返回即重连。 */
    suspend fun waitBeforeReconnect(delayMs: Long) {
        delay(delayMs + Random.nextLong(0, 1000))
        if (available || probeNow()) return
        ZLog.i(TAG, "gate: 无网，挂起等待恢复")
        suspendCancellableCoroutine { cont ->
            synchronized(waiterLock) {
                waiter = { if (cont.isActive) cont.resume(Unit) }
            }
            cont.invokeOnCancellation {
                synchronized(waiterLock) { waiter = null }
            }
            // 注册 waiter 与挂起之间网络可能已恢复（竞态兜底）
            if (available && cont.isActive) {
                synchronized(waiterLock) { waiter = null }
                cont.resume(Unit)
            }
        }
        ZLog.i(TAG, "gate: 网络恢复，放行重连")
    }

    private fun probeNow(): Boolean {
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    companion object { private const val TAG = "NetworkGate" }
}
