package com.zcode.remote.ui.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * 语音输入按钮（P1-3）：系统 SpeechRecognizer 转文字，结果交给 [onFinalText] 追加进输入草稿。
 *
 * 官方 web 无语音功能（research/index-web.js 里的 speech chunk 只是 lucide 图标），此为 App 自研；
 * 不涉及中继协议改动（发送仍是 sendPrompt 文本）。识别引擎取系统 RecognitionService
 * （小米 15 Pro 为小爱语音引擎），不可用时按钮整体不渲染。
 *
 * - 点击开始聆听；again 点击 = stopListening（提前出已识别部分）
 * - 识别中的实时部分经 [onStateChange](listening, liveText) 上抛，由调用方展示
 * - 首次点击先请求 RECORD_AUDIO 运行时权限
 */
@Composable
fun VoiceInputButton(
    onFinalText: (String) -> Unit,
    onStateChange: (listening: Boolean, liveText: String?) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    if (!SpeechRecognizer.isRecognitionAvailable(context)) return

    var hasPermission by remember { mutableStateOf(hasRecordAudio(context)) }
    var listening by remember { mutableStateOf(false) }

    // 主线程创建；DisposableEffect 统一销毁，避免泄漏 RecognitionService 绑定
    val recognizer = remember { SpeechRecognizer.createSpeechRecognizer(context) }
    DisposableEffect(Unit) {
        onDispose { recognizer.destroy() }
    }

    fun report(listeningNow: Boolean, live: String?) {
        listening = listeningNow
        onStateChange(listeningNow, live)
    }

    fun start() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        report(true, null)
        recognizer.startListening(intent)
    }

    recognizer.setRecognitionListener(object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull() ?: return
            report(true, text)
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            report(false, null)
            if (!text.isNullOrBlank()) onFinalText(text.trim())
        }

        override fun onError(error: Int) {
            report(false, null)
            onStateChange(false, errorText(error))
        }
    })

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) start()
        else onStateChange(false, "⚠️ 需要麦克风权限才能语音输入")
    }

    TextButton(
        onClick = {
            when {
                listening -> recognizer.stopListening()   // onResults 随后回调收尾
                hasPermission -> start()
                else -> permLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
        contentPadding = PaddingValues(horizontal = 8.dp),
    ) {
        // C-3：Material 图标替换 emoji（原 🎤/🔴）——emoji 观感随系统字体漂移，
        // 与输入栏其余图标体系不一致；聆听中改用错误色（点击即停止拾音）。
        Icon(
            imageVector = Icons.Default.Mic,
            contentDescription = if (listening) "停止语音输入" else "语音输入",
            tint = if (listening) MaterialTheme.colorScheme.error else LocalContentColor.current,
        )
    }
}

private fun hasRecordAudio(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

private fun errorText(error: Int): String = "⚠️ " + when (error) {
    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "未识别到语音"
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少麦克风权限"
    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "识别服务网络错误"
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别服务忙，稍后再试"
    else -> "语音识别出错（$error）"
}
