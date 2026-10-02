package com.zcode.remote.relay

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.random.Random

/**
 * 网络感知重连闸门（Sprint 1 / B 组）。
 *
 * 原实现是裸 Thread.sleep 退避：飞行模式/切 Wi-Fi 期间盲目空转耗电，且网络恢复后要等
 * 下一轮退避（最长 48s）才重连。现在：先退避（含 jitter），届时若仍无可用网络则挂起，
 * 由 NetworkCallback.onAvailable 即刻放行重连。
 */
class NetworkGate(private val cm: ConnectivityManager) {

    /** RelayClient.networkWait 的注入实现：退避 → 无网则等网络恢复 → 返回即重连。 */
    suspend fun waitBeforeReconnect(delayMs: Long) {
        delay(delayMs + Random.nextLong(0, 1000))
        if (hasNetwork()) return
        suspendCancellableCoroutine { cont ->
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    runCatching { cm.unregisterNetworkCallback(this) }
                    if (cont.isActive) cont.resume(Unit)
                }
            }
            runCatching { cm.registerNetworkCallback(request, callback) }
                .onFailure { if (cont.isActive) cont.resume(Unit) }  // 注册失败退回纯退避语义
            cont.invokeOnCancellation { runCatching { cm.unregisterNetworkCallback(callback) } }
        }
    }

    private fun hasNetwork(): Boolean {
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
