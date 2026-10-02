package com.zcode.remote.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import com.zcode.remote.util.ZLog
import android.util.Size
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.zcode.remote.storage.PairedDevice
import com.zcode.remote.storage.QrParser
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "ScanScreen"

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
        Text("在 PC 端 ZCode 打开「移动端远程控制」，将二维码置于框内扫描，支持点按屏幕对焦。", style = MaterialTheme.typography.bodySmall)

        if (hasCamera) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                @SuppressLint("ClickableViewAccessibility")
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val previewView = PreviewView(ctx)
                        val executor = Executors.newSingleThreadExecutor()
                        val pairedHandled = AtomicBoolean(false)
                        val providerFuture = ProcessCameraProvider.getInstance(ctx)

                        providerFuture.addListener({
                            val provider = providerFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }

                            val reader = MultiFormatReader().apply {
                                setHints(
                                    mapOf(
                                        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                                        DecodeHintType.TRY_HARDER to true,
                                        DecodeHintType.CHARACTER_SET to "UTF-8",
                                    )
                                )
                            }

                            // 优先选用 1280x720 分辨率，保证长字符高密度二维码的清晰度
                            val resolutionSelector = ResolutionSelector.Builder()
                                .setResolutionStrategy(
                                    ResolutionStrategy(
                                        Size(1280, 720),
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                    )
                                )
                                .build()

                            val analysis = ImageAnalysis.Builder()
                                .setResolutionSelector(resolutionSelector)
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()

                            analysis.setAnalyzer(executor) { proxy ->
                                if (pairedHandled.get()) {
                                    proxy.close()
                                    return@setAnalyzer
                                }
                                val text = decodeQr(reader, proxy)
                                proxy.close()
                                if (text != null && pairedHandled.compareAndSet(false, true)) {
                                    ZLog.i(TAG, "二维码扫描成功: ${text.take(60)}...")
                                    QrParser.parse(text)?.let { dev ->
                                        ContextCompat.getMainExecutor(ctx).execute {
                                            runCatching {
                                                provider.unbindAll()
                                                executor.shutdown()
                                            }
                                            onPaired(dev)
                                        }
                                    } ?: run {
                                        ZLog.w(TAG, "扫描成功但 QrParser 解析失败: $text")
                                        pairedHandled.set(false)
                                    }
                                }
                            }

                            runCatching {
                                provider.unbindAll()
                                val camera = provider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    analysis
                                )
                                // 点击屏幕对焦
                                previewView.setOnTouchListener { _, event ->
                                    if (event.action == MotionEvent.ACTION_UP) {
                                        val factory = previewView.meteringPointFactory
                                        val point = factory.createPoint(event.x, event.y)
                                        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                                            .setAutoCancelDuration(3, TimeUnit.SECONDS)
                                            .build()
                                        camera.cameraControl.startFocusAndMetering(action)
                                    }
                                    true
                                }
                            }.onFailure { ZLog.e(TAG, "相机绑定失败", it) }
                        }, ContextCompat.getMainExecutor(ctx))
                        previewView
                    }
                )

                // 取景框辅助框线
                Box(
                    modifier = Modifier
                        .fillMaxSize(0.72f)
                        .align(Alignment.Center)
                        .border(2.dp, Color(0xFF4CAF50), RoundedCornerShape(12.dp))
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

/**
 * 从 CameraX 图像帧中解析二维码：
 * 1. 抽取 Y 灰度平面数据并去除 rowStride 填充；
 * 2. 根据 [ImageInfo.rotationDegrees] 将画面旋转至正向（避免竖屏手持时图像逆向 90 度导致 ZXing 漏检）；
 * 3. 优先使用 [HybridBinarizer]；若未检出则回退使用 [GlobalHistogramBinarizer] 抵抗屏幕摩尔纹；
 * 4. 无论成功与否，finally 块强制 [MultiFormatReader.reset]，保证解析器状态机正确。
 */
private fun decodeQr(reader: MultiFormatReader, proxy: ImageProxy): String? {
    return try {
        val plane = proxy.planes[0]
        val w = proxy.width
        val h = proxy.height
        val rowStride = plane.rowStride
        val buf = plane.buffer

        val rawY = ByteArray(w * h)
        if (rowStride == w) {
            buf.get(rawY, 0, w * h)
        } else {
            for (row in 0 until h) {
                buf.position(row * rowStride)
                buf.get(rawY, row * w, w)
            }
        }

        val rotation = proxy.imageInfo.rotationDegrees
        val (rotatedY, dims) = rotateY(rawY, w, h, rotation)
        val finalW = dims.first
        val finalH = dims.second

        val source = PlanarYUVLuminanceSource(
            rotatedY, finalW, finalH, 0, 0, finalW, finalH, false
        )

        // 尝试 HybridBinarizer，失败则回退 GlobalHistogramBinarizer（对屏幕反光与点阵纹理更鲁棒）
        try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (_: Exception) {
            try {
                reader.decodeWithState(BinaryBitmap(GlobalHistogramBinarizer(source))).text
            } catch (_: Exception) {
                null
            }
        }
    } catch (e: Exception) {
        null
    } finally {
        reader.reset()
    }
}

/**
 * 灰度 Y 平面旋转：
 * 传感器通常为横屏安装（rotation = 90 或 270）。
 * 顺时针旋转 Y 数组至当前手机屏幕物理正方向。
 */
private fun rotateY(src: ByteArray, w: Int, h: Int, rotation: Int): Pair<ByteArray, Pair<Int, Int>> {
    return when (rotation) {
        90 -> {
            val dst = ByteArray(w * h)
            var i = 0
            for (x in 0 until w) {
                for (y in h - 1 downTo 0) {
                    dst[i++] = src[y * w + x]
                }
            }
            dst to (h to w)
        }
        180 -> {
            val dst = ByteArray(w * h)
            val total = w * h
            for (i in 0 until total) {
                dst[i] = src[total - 1 - i]
            }
            dst to (w to h)
        }
        270 -> {
            val dst = ByteArray(w * h)
            var i = 0
            for (x in w - 1 downTo 0) {
                for (y in 0 until h) {
                    dst[i++] = src[y * w + x]
                }
            }
            dst to (h to w)
        }
        else -> src to (w to h)
    }
}
