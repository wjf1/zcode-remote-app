import java.util.Properties

// 发布签名（keystore.properties 不入库；storeFile 相对 app-android/ 目录）
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.zcode.remote"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.zcode.remote"
        minSdk = 31
        targetSdk = 35
        versionCode = 28
        versionName = "0.5.0-beta18"
        // 仪器化渲染回归网（src/androidTest）用 androidx.test 默认 runner。
        // 只在模拟器/真机上跑；CI runner 无设备，故 ci.yml 不接（见 HANDOVER §6.1 回归网）。
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    testOptions {
        // 渲染断言要求稳定帧：关掉系统的窗口/转场动画，避免捕获到中间态
        animationsDisabled = true
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            // P0-C：开 R8 做日志剥离与库裁剪（proguard-rules.pro；自家代码本轮保守 keep，
            // 混淆改名待真机冒烟后再放开）。release 构建后必须真机冒烟验证。
            isMinifyEnabled = true
            isShrinkResources = true
            // keystore.properties 缺失时不挂 release 签名（配置期 getByName 会抛异常，
            // 连 assembleDebug 都过不去）；发布前必须恢复正式 keystore。
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true   // 用 BuildConfig.DEBUG 隔离调试观测面板（HANDOVER 技术债）
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.2")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Sprint 6：JVM 单元测试（VQL 金标准对拍 + 纯函数；不需要模拟器/真机）
    testImplementation("junit:junit:4.13.2")

    // 仪器化渲染回归网（模拟器上跑；用 fixture 行直接渲染真实 Composable，不需要中继/配对）。
    // 覆盖纯单测够不着的部分：GFM 表格、围栏代码块与语法高亮、折叠行交互。
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // 扫码配对：CameraX 预览 + ZXing 解码（无 Google 服务依赖，国内可用）
    // CameraX 1.4.x 起原生库按 16 KB 页对齐编译（Android 15 兼容性要求）
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("com.google.zxing:core:3.5.3")

    // 显式钉住 16 KB 对齐修复版，覆盖 compose BOM 传递的旧版 graphics-path
    implementation("androidx.graphics:graphics-path:1.0.1")

    // 会话页 Markdown 渲染：对齐桌面端的 GFM 表格/任务列表/代码块语法高亮。
    // 版本上限说明：本项目 Kotlin 插件为 2.0.20，而 Kotlin 元数据不向后兼容——
    // 该库 0.30.0 起已改用 Kotlin 2.1+ 编译，2.0.20 编译器读不了其元数据（硬报错）。
    // 0.27.0 是最后一个 Kotlin 2.0.x 编译的版本，故锁死在此；升级 Kotlin 插件前不得上调。
    // code 模块自带 dev.snipme:highlights 1.x（纯 JVM，无原生 .so）。
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.27.0")
    implementation("com.mikepenz:multiplatform-markdown-renderer-code:0.27.0")
}
