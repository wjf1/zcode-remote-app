// Macrobenchmark（§11.8 第二层门禁最后一项：长会话基准）。
//
// 独立 `com.android.test` 模块，针对 `:app` 的 `benchmark` 变体（与 release 同优化等级
// 的 R8 产物 + debug 签名）在设备/模拟器上以真实启动、滚动、流式路径量测帧时；
// 宿主 Activity 与 fixture 在 `:app` 的 `src/benchmark` 源集，测试代码在本模块。
//
// 运行：
//   gradle :benchmark:connectedBenchmarkAndroidTest --console=plain   # 设备/模拟器
//   python tools/bench_check.py --dir <结果目录>                       # 解析/比对
plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
    id("io.gitlab.arturbosch.detekt")
    id("org.jlleitschuh.gradle.ktlint")
}

// 与 :app 同口径（版本显式钉住，理由见 app/build.gradle.kts）
ktlint {
    version.set("1.5.0")
    android.set(true)
    ignoreFailures.set(false)
    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
    }
    filter {
        exclude("**/generated/**")
        exclude("**/build/**")
    }
}

// 与 :app 同一份规则文件；本模块不挂 baseline——新代码零告警。
detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/detekt-config.yml"))
}

android {
    namespace = "com.zcode.remote.benchmark"
    compileSdk = 35
    // 与 :app 显式对齐（AGP 默认的 34.0.0 在本机/CI 的精简 SDK 里可能不存在，
    // 缺失时会去远程仓库拉取——离线/受限网络下直接失败）
    buildToolsVersion = "35.0.0"

    defaultConfig {
        minSdk = 31
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildTypes {
        // 变体名必须与被测 :app 的 `benchmark` 变体对齐（AGP 按名匹配）。
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    targetProjectPath = ":app"
    // 本模块自带测试 APK（不自 instrument 其它模块的测试源集）
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

dependencies {
    implementation("androidx.test.ext:junit:1.2.1")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.3.4")
}
