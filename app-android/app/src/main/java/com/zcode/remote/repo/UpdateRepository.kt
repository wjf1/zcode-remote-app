package com.zcode.remote.repo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.zcode.remote.BuildConfig
import com.zcode.remote.relay.UserFacingError
import com.zcode.remote.storage.SettingsStore
import com.zcode.remote.util.LogRedactor
import com.zcode.remote.util.ZLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 软件版本与在线更新（§11.7 结构性拆分：自 `AppViewModel` 原样搬出，只移动不改行为）。
 * 设置面板的「检查更新」数据源：GitHub Releases API + 本机 GitHub Token（TokenStore 加密存储）。
 */
class UpdateRepository(
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
) {
    data class UpdateInfo(
        val tagName: String,
        val downloadUrl: String?,
        val body: String?,
        val hasNew: Boolean,
    )

    sealed interface UpdateState {
        data object Idle : UpdateState

        data object Checking : UpdateState

        data class Success(
            val info: UpdateInfo,
        ) : UpdateState

        data class Error(
            val message: String,
        ) : UpdateState
    }

    var updateState by androidx.compose.runtime.mutableStateOf<UpdateState>(UpdateState.Idle)
        private set
    var githubToken by androidx.compose.runtime.mutableStateOf(settings.githubToken)
        private set

    fun updateGithubToken(token: String) {
        settings.githubToken = token
        githubToken = token
    }

    fun checkForUpdate() {
        if (updateState is UpdateState.Checking) return
        updateState = UpdateState.Checking
        scope.launch(Dispatchers.IO) {
            runCatching {
                val client =
                    okhttp3.OkHttpClient
                        .Builder()
                        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                val reqBuilder =
                    okhttp3.Request
                        .Builder()
                        .url("https://api.github.com/repos/wjf1/zcode-remote-app/releases/latest")
                        .header("Accept", "application/vnd.github+json")
                        .header("User-Agent", "ZCodeRemote-Android")

                val token = settings.githubToken.trim()
                if (token.isNotEmpty()) {
                    reqBuilder.header("Authorization", "Bearer $token")
                }

                val response = client.newCall(reqBuilder.build()).execute()
                val bodyStr = response.body?.string()
                if (!response.isSuccessful || bodyStr == null) {
                    val code = response.code
                    val errMsg =
                        when (code) {
                            404, 401 -> if (token.isEmpty()) "私有仓库需配置 GitHub Token 后拉取更新" else "未找到 Release 或 Token 无权限 ($code)"
                            else -> "检查更新失败: HTTP $code"
                        }
                    updateState = UpdateState.Error(errMsg)
                    return@launch
                }

                val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                val root = json.parseToJsonElement(bodyStr).jsonObject
                val tagName = root["tag_name"]?.jsonPrimitive?.content ?: ""
                val releaseBody = root["body"]?.jsonPrimitive?.content ?: ""
                val assets = root["assets"]?.jsonArray ?: kotlinx.serialization.json.JsonArray(emptyList())
                val downloadUrl =
                    assets.firstNotNullOfOrNull { el ->
                        val asset = el.jsonObject
                        val name = asset["name"]?.jsonPrimitive?.content.orEmpty()
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            asset["browser_download_url"]?.jsonPrimitive?.content
                        } else {
                            null
                        }
                    }

                val curVer = BuildConfig.VERSION_NAME.removePrefix("v").trim()
                val remoteVer = tagName.removePrefix("v").trim()
                val hasNew = remoteVer.isNotBlank() && isNewerVersion(curVer, remoteVer)

                updateState =
                    UpdateState.Success(
                        UpdateInfo(
                            tagName = tagName.ifBlank { "最新版" },
                            downloadUrl = downloadUrl,
                            body = releaseBody,
                            hasNew = hasNew,
                        ),
                    )
            }.onFailure { err ->
                ZLog.w(TAG, "checkForUpdate failed ${LogRedactor.exceptionLabel(err)}")
                updateState = UpdateState.Error("网络连接失败：${UserFacingError.map(err.message ?: "未知错误")}")
            }
        }
    }

    companion object {
        private const val TAG = "UpdateRepo"

        /**
         * 语义化版本比较（检查更新用，2026-10-05 修）：原实现用字符串不等判断——
         * 远端旧版本（0.4.0 < 0.5.0-beta5）会误报"有新版本"。规则按 semver 直觉：
         * 数字段逐位比较；核心版本相等时，remote 无 prerelease 且 current 有 → newer；
         * prerelease 之间按「字母前缀 + 数字」比较（beta10 > beta9，字典序会错）。
         */
        internal fun isNewerVersion(
            current: String,
            remote: String,
        ): Boolean {
            fun coreOf(v: String): List<Int> = v.substringBefore('-').split('.').map { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }

            fun preOf(v: String): String = v.substringAfter('-', "")

            fun preKey(p: String): Pair<String, Int> {
                val digits = p.takeLastWhile { it.isDigit() }
                return p.dropLast(digits.length) to (digits.toIntOrNull() ?: 0)
            }
            val cCore = coreOf(current)
            val rCore = coreOf(remote)
            for (i in 0 until maxOf(cCore.size, rCore.size)) {
                val c = cCore.getOrElse(i) { 0 }
                val r = rCore.getOrElse(i) { 0 }
                if (r != c) return r > c
            }
            val cPre = preOf(current)
            val rPre = preOf(remote)
            return when {
                rPre.isEmpty() && cPre.isNotEmpty() -> true // 同核心版本，remote 是正式版、current 是预发布
                rPre.isNotEmpty() && cPre.isEmpty() -> false
                rPre.isNotEmpty() && cPre.isNotEmpty() -> {
                    val rk = preKey(rPre)
                    val ck = preKey(cPre)
                    if (rk.first != ck.first) rk.first > ck.first else rk.second > ck.second
                }
                else -> false
            }
        }
    }
}
