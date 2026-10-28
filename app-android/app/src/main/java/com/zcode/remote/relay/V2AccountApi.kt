package com.zcode.remote.relay

import com.zcode.remote.util.HttpClientProvider
import com.zcode.remote.util.ZLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * V2 远程控制账号 API（PROTOCOL.md §11，ZCode ≥3.16）。
 *
 * - `POST /api/v1/remote/token {role:"terminal"}` —— 用登录 JWT 换中继 relay token
 *   （实测 data={"token","expires_in":60}，token 短时效、须连接前现取）。
 *   App V2 直连：`wss://<origin>/api/v1/remote/ws2?token=&mid=` + 无 role 的 auth_init
 *   （2026-10-25 probe 实测 auth_ack matched 一帧直达，PROTOCOL.md §11.3）。
 * - `GET /api/v1/remote/device` —— 账号绑定设备列表（多设备管理数据源）。
 * - `POST /api/v1/remote/device {device_mid}` / `.../delete {device_sid}` —— 注册/删除（PC 侧语义，App 不用）。
 *
 * 响应为统一包络 `{code, msg, data, logid}`，`code==0` 为成功。
 * 纯 JVM 可测：HTTP 层通过 [fetch] 注入，单测传假实现。
 */
object V2AccountApi {
    private const val TAG = "V2AccountApi"

    /** 官方主端点；线路覆盖时由调用方换 [baseUrl]。 */
    const val DEFAULT_BASE_URL = "https://zcode.z.ai"

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    internal data class Envelope(
        val code: Int = -1,
        val msg: String? = null,
        // data 形态随接口而异：token 接口为对象 {"token","expires_in"}、device 列表为数组
        // （2026-10-25 真机实测），用 JsonElement 延迟取字段
        val data: JsonElement? = null,
    )

    @Serializable
    data class RemoteDevice(
        // API 字段为 snake_case（probe 实测 2026-10-25：{"device_sid","device_mid","created_at"}）
        @kotlinx.serialization.SerialName("device_sid") val deviceSid: String,
        @kotlinx.serialization.SerialName("device_mid") val deviceMid: String,
        val online: Boolean = false,
        @kotlinx.serialization.SerialName("created_at") val createdAt: Long = 0,
        val meta: Meta? = null,
    ) {
        @Serializable
        data class Meta(
            val name: String? = null,
            val platform: String? = null,
            val version: String? = null,
        )
    }

    /** 换 relay token。role ∈ {device, terminal}。 */
    suspend fun fetchRelayToken(
        jwt: String,
        role: String = "terminal",
        baseUrl: String = DEFAULT_BASE_URL,
        client: OkHttpClient = defaultClient(),
    ): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body =
                    """{"role":"$role"}"""
                        .toRequestBody("application/json".toMediaType())
                val req =
                    Request
                        .Builder()
                        .url("$baseUrl/api/v1/remote/token")
                        .header("Authorization", "Bearer $jwt")
                        .post(body)
                        .build()
                client.newCall(req).execute().use { resp ->
                    // 401/403 是「登录态失效」，与网络故障必须区分：前者重试多少次都不会好，
                    // 重连反而会把终态失败误报成 NETWORK，界面反复转圈（beta54/T10）。
                    if (resp.code == 401 || resp.code == 403) {
                        throw TokenAuthException(resp.code)
                    }
                    check(resp.code == 200) { "HTTP ${resp.code}" }
                    val env = json.decodeFromString(Envelope.serializer(), resp.body?.string().orEmpty())
                    check(env.code == 0) { "api code=${env.code} msg=${env.msg}" }
                    val token =
                        (env.data as? JsonObject)?.get("token")?.jsonPrimitive?.content
                    checkNotNull(token) { "响应无 token 字段" }
                    token
                }
            }.onFailure { ZLog.w(TAG, "fetchRelayToken failed: ${it.message}") }
        }

    /**
     * 单飞取 token（T10）：并发调用方共享同一次网络往返，避免多窗口/重连风暴各打一次。
     *
     * 复用判据是 **(jwt, role, baseUrl)** 三元组——jwt 换了说明登录态已变，必须重新取，
     * 不能复用旧 in-flight 结果（那会把过期 token 交给新连接）。
     * 失败不进缓存：下一个调用方应立即重试而不是共享一次失败的 Result。
     */
    suspend fun fetchRelayTokenSingleFlight(
        jwt: String,
        role: String = "terminal",
        baseUrl: String = DEFAULT_BASE_URL,
        client: OkHttpClient = defaultClient(),
    ): Result<String> {
        val key = "$baseUrl|$role|$jwt"
        val flight =
            synchronized(inFlight) {
                // 已完成的（异常/正常都算结束）先清掉，避免拿到上一次的陈旧结果
                inFlight[key]?.takeIf { it.isActive } ?: run {
                    inFlight[key] =
                        singleFlightScope.async(start = CoroutineStart.LAZY) {
                            try {
                                fetchRelayToken(jwt, role, baseUrl, client)
                            } finally {
                                synchronized(inFlight) { inFlight.remove(key) }
                            }
                        }
                    inFlight[key]!!
                }
            }
        flight.start()
        return flight.await()
    }

    /** token 接口鉴权失败（401/403）——语义上等同「需要重新登录」。 */
    class TokenAuthException(
        val httpCode: Int,
    ) : IOException("token 接口鉴权失败 HTTP $httpCode")

    // 单飞状态：key → 进行中的取 token 任务。进程级 scope 保证调用方协程取消不会连坐他人。
    private val inFlight = ConcurrentHashMap<String, Deferred<Result<String>>>()
    private val singleFlightScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 账号绑定的设备列表（多设备管理）。 */
    suspend fun listDevices(
        jwt: String,
        baseUrl: String = DEFAULT_BASE_URL,
        client: OkHttpClient = defaultClient(),
    ): Result<List<RemoteDevice>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req =
                    Request
                        .Builder()
                        .url("$baseUrl/api/v1/remote/device")
                        .header("Authorization", "Bearer $jwt")
                        .get()
                        .build()
                client.newCall(req).execute().use { resp ->
                    check(resp.code == 200) { "HTTP ${resp.code}" }
                    val env = json.decodeFromString(Envelope.serializer(), resp.body?.string().orEmpty())
                    check(env.code == 0) { "api code=${env.code} msg=${env.msg}" }
                    // data 可能是数组或 {"devices":[...]}，两种都兼容
                    val data = env.data
                    if (data == null || data is JsonNull) return@runCatching emptyList()
                    when (data) {
                        is JsonArray ->
                            json.decodeFromString(
                                kotlinx.serialization.builtins.ListSerializer(RemoteDevice.serializer()),
                                data.toString(),
                            )
                        is JsonObject -> {
                            @Serializable
                            data class Wrapper(
                                val devices: List<RemoteDevice> = emptyList(),
                            )
                            json.decodeFromString(Wrapper.serializer(), data.toString()).devices
                        }
                        else -> emptyList()
                    }
                }
            }.onFailure { ZLog.w(TAG, "listDevices failed: ${it.message}") }
        }

    internal fun defaultClient(): OkHttpClient =
        HttpClientProvider
            .newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
}
