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
        // macrobenchmark 默认把「模拟器」列为未抑制错误（`ERRORS (not suppressed): EMULATOR`）
        // 并直接拒绝测量；CI 只有模拟器可跑（本机兆芯 CPU 无虚拟化）。按官方推荐方式抑制
        // （Errors.kt 给出的示例写法）。判读口径不变：模拟器数值只做趋势信号，真实性能验收
        // = 人工在真机跑同一任务（见 README / HANDOVER §3）。
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
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
    // 被测变体由 AGP 的变体匹配决定（「与 test 模块同名 buildType」是默认行为；
    // `targetVariant` 在本 AGP 版本已弃用——提示「all variants are now tested」——故不设置）。
    // 测试已在运行时断言宿主 Activity 是否真的装上（LongConversationBenchmark.requireHostInstalled）。
    // 本模块自带测试 APK（不自 instrument 其它模块的测试源集）
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

// `com.android.test` 默认还会创建 debug 变体，而它会被不带模块前缀的 `connectedDebugAndroidTest`
// 一并执行（2026-10-12 首跑实测：CI 的 instrumentation job 因此在 debuggable 包上跑到本模块的
// 基准测试，报 `ERRORS (not suppressed): EMULATOR DEBUGGABLE` 而整单变红）。本模块只保留
// `benchmark` 变体；CI 侧同时把命令改为显式 `:app:connectedDebugAndroidTest`（双保险）。
androidComponents {
    beforeVariants { variantBuilder ->
        variantBuilder.enable = variantBuilder.buildType == "benchmark"
    }
}

dependencies {
    implementation("androidx.test.ext:junit:1.3.0")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    // 1.3.4 → 1.5.0：修复「Android 16+（SDK>=36）上 toybox pgrep -l 将 >15 字符进程名
    // 截断，而库的进程枚举未加 -a 导致被测包（com.zcode.remote 恰 16 字符）匹配失败、
    // getRunningProcessesForPackage 返回空 → 恒报 Unable to confirm activity launch
    // completion []」的兼容性缺陷（上游 aosp/3507001 以 SDK>=36 时追加 -a 修复，
    // 1.3.4 未包含）。2026-10-13 小米 15 Pro（Android 17）真机实测定位。
    implementation("androidx.benchmark:benchmark-macro-junit4:1.5.0")
}
