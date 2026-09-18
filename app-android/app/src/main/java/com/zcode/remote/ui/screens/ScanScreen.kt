package com.zcode.remote.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.zcode.remote.storage.PairedDevice
import com.zcode.remote.storage.QrParser
import java.util.concurrent.Executors

/** 扫码配对（解析官方二维码：sid/hash/mid/name）。失败可手动粘贴链接。 */
@Composable
fun ScanScreen(onPaired: (PairedDevice) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasCamera by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var manualText by remember { mutableStateOf("") }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasCamera = it
    }

    LaunchedEffect(Unit) { if (!hasCamera) permissionLauncher.launch(Manifest.permission.CAMERA) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("扫码配对", style = MaterialTheme.typography.headlineSmall)
        Text("在 PC 端 ZCode 打开「移动端远程控制」，用本机扫描二维码。", style = MaterialTheme.typography.bodySmall)

        if (hasCamera) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val previewView = PreviewView(ctx)
                        val executor = Executors.newSingleThreadExecutor()
                        val providerFuture = ProcessCameraProvider.getInstance(ctx)
                        providerFuture.addListener({
                            val provider = providerFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }
                            val reader = MultiFormatReader().apply {
                                setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
                            }
                            val analysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                            analysis.setAnalyzer(executor) { proxy ->
                                val text = decodeQr(reader, proxy)
                                proxy.close()
                                if (text != null) {
                                    QrParser.parse(text)?.let { dev ->
                                        provider.unbindAll()
                                        executor.shutdown()
                                        onPaired(dev)
                                    }
                                }
                            }
                            runCatching {
                                provider.unbindAll()
                                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                            }
                        }, ContextCompat.getMainExecutor(ctx))
                        previewView
                    }
                )
            }
        } else {
            Card { Text("需要相机权限用于扫码", Modifier.padding(16.dp)) }
        }

        OutlinedTextField(
            value = manualText, onValueChange = { manualText = it },
            label = { Text("无法扫码？粘贴完整配对链接") },
            modifier = Modifier.fillMaxWidth(), minLines = 2,
        )
        Button(
            onClick = { QrParser.parse(manualText)?.let(onPaired) },
            enabled = manualText.isNotBlank(),
            modifier = Modifier.align(Alignment.End),
        ) { Text("手动配对") }
    }
}

private fun decodeQr(reader: MultiFormatReader, proxy: androidx.camera.core.ImageProxy): String? =
    runCatching {
        val plane = proxy.planes[0]
        val w = proxy.width
        val h = proxy.height
        val rowStride = plane.rowStride
        // YUV_420_888 的 rowStride 常大于 width（如 1280x720 -> 1536），
        // PlanarYUVLuminanceSource 需要紧凑行数据，必须逐行拷贝去 padding
        val bytes: ByteArray = if (rowStride == w) {
            val buf = plane.buffer
            ByteArray(buf.remaining()).also { buf.get(it) }
        } else {
            val buf = plane.buffer
            ByteArray(w * h).also { out ->
                for (row in 0 until h) {
                    buf.position(row * rowStride)
                    buf.get(out, row * w, w)
                }
            }
        }
        val source = PlanarYUVLuminanceSource(bytes, w, h, 0, 0, w, h, false)
        val result: com.google.zxing.Result = reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
        reader.reset()
        result.text
    }.getOrNull()
