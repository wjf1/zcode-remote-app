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
        versionCode = 15
        versionName = "0.5.0-beta5"
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

    // 扫码配对：CameraX 预览 + ZXing 解码（无 Google 服务依赖，国内可用）
    // CameraX 1.4.x 起原生库按 16 KB 页对齐编译（Android 15 兼容性要求）
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("com.google.zxing:core:3.5.3")

    // 显式钉住 16 KB 对齐修复版，覆盖 compose BOM 传递的旧版 graphics-path
    implementation("androidx.graphics:graphics-path:1.0.1")
}
