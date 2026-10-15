package com.zcode.remote.util

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 进程级共享 HTTP 客户端（P1-4：连接池与调度器复用）。
 *
 * 背景：`RelayClient`（每次 `connect()` 新建实例）与 `repo/UpdateRepository` 原先各自
 * `OkHttpClient.Builder().build()` —— 每次重连都新建一套连接池、线程池与 DNS 缓存，
 * 旧实例又从不 `shutdown()`，长期运行会持续累积。这里提供单一底座，
 * 调用方用 [newBuilder] 派生**各自超时**的客户端（保持既有行为不变，只共享底层资源）。
 *
 * 生命周期：进程级单例，随进程存活，**不主动 shutdown** —— 中继长连接与检查更新
 * 随时可能发起请求；关停连接池会让后续请求重建，得不偿失。
 *
 * `Dispatcher` / `ConnectionPool` 的容量取默认值的显式写法：避免不同 OkHttp 版本
 * 默认值漂移导致行为随之改变（共享资源的口径必须是钉死的）。
 */
internal object HttpClientProvider {
    /** 连接池：默认 5 空闲 / 5 分钟。中继 + 更新检查的并发远低于此。 */
    private val connectionPool =
        ConnectionPool(
            maxIdleConnections = 5,
            keepAliveDuration = 5,
            timeUnit = TimeUnit.MINUTES,
        )

    /** 调度器：默认 maxRequests=64 / maxRequestsPerHost=5，显式钉住。 */
    private val dispatcher =
        Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 5
        }

    /**
     * 共享底座。调用方**必须**在此基础上 `newBuilder()` 派生自己的客户端，
     * 不要直接使用或修改本实例（派生客户端共享连接池与调度器，但超时各自独立）。
     */
    private val base: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectionPool(connectionPool)
            .dispatcher(dispatcher)
            .build()

    /**
     * 派生一个新客户端：共享连接池 / 调度器 / DNS 缓存，超时等参数由调用方设置。
     * 返回 `Builder` 而非 `OkHttpClient`，便于各调用点继续用链式写法声明自身参数。
     */
    fun newBuilder(): OkHttpClient.Builder = base.newBuilder()
}
