import java.util.Properties

// 发布签名（keystore.properties 不入库；storeFile 相对 app-android/ 目录）
val keystoreProps =
    Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }

// 真机基准取证开关（默认关闭 → CI/无 keystore 环境行为不变）：开启且本机有 keystore.properties 时，
// benchmark 变体改用 release 签名——与已装 release 包同签名可直接覆盖安装，免卸载、保配对凭据。
// 用法：gradle :app:assembleBenchmark -PbenchmarkUseReleaseSigning=true（见 HANDOVER §3）。
val benchmarkUseReleaseSigning =
    providers
        .gradleProperty("benchmarkUseReleaseSigning")
        .map { it.toBoolean() }
        .getOrElse(false)

// 真机仪器化取证：debug 变体是否改用 release 签名（默认关闭，见 buildTypes.debug 注释）。
val debugUseReleaseSigning =
    providers
        .gradleProperty("debugUseReleaseSigning")
        .map { it.toBoolean() }
        .getOrElse(false)

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("io.gitlab.arturbosch.detekt")
    id("org.jetbrains.kotlinx.kover")
    id("org.cyclonedx.bom")
    id("org.jlleitschuh.gradle.ktlint")
}

// §11.8 第二层门禁：KtLint（格式）。版本显式钉住——规则键（.editorconfig 的 ktlint_standard_*）
// 与 ktlint 版本强耦合，隐式跟随插件默认版本会在升级插件时静默改变门禁口径。
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

// §11.8：Detekt——默认规则集 + baseline（存量告警登记于 detekt-baseline.xml，新增违规即红）
detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/detekt-config.yml"))
    baseline = file("$rootDir/detekt-baseline.xml")
}

android {
    namespace = "com.zcode.remote"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.zcode.remote"
        minSdk = 31
        targetSdk = 35
        // 0.5.0-beta22 = beta21 + C-3（输入栏：Material 图标替换 emoji；
        // 键盘 inset 真机量测结论 = 现有链条正确、无需改动，详见 HANDOVER 坑 30）
        // versionCode 44：beta32 —— §11.8 阶段 6（CI 加固 + 静态分析 + SBOM/指纹/provenance 发布链）
        // versionCode 45：beta33 —— §11.3.3 集中协议预算 + §11.5.2 RPC 代次键控/分片完整性
        // （请求 key 绑 (桥代次, requestId)、入站身份校验、单/多分片统一 CRC+声明长度校验、
        //  decode-then-ack、在途分片与入站队列有界、出站超限本地拒绝）
        // versionCode 46：beta34 —— §11.8 协议帧回放网 + 资源预算模糊/压力测试 + CodeQL
        // versionCode 47：beta35 —— §11.8 可观测性（本地脱敏诊断环形缓冲 + 用户主动导出）
        // versionCode 48：beta36 —— §11.8 第二层门禁补完（KtLint 存量格式专项接线 + 依赖变更审查）
        // versionCode 49：beta37 —— §11.8 第二层门禁收尾（长会话 Macrobenchmark + 基线回退探测）
        // versionCode 50：beta38 —— 真机验收轮：协议灰度/诊断导出/界面回归通过 +
        //                           benchmark 库 1.3.4→1.5.0 真机兼容修复（pgrep 截断）
        // versionCode 51：beta39 —— 依赖治理轮：Dependabot 积压处置（7 合并 / 3 拒绝）
        //                           + Kotlin 生态升级冻结规则固化
        // versionCode 52：beta40 —— 依赖治理轮（第二批）：专项库升级（test:runner /
        //                           uiautomator / detekt）+ markdown 渲染库与 gradle-wrapper 漂移拒绝
        // versionCode 53：beta41 —— 依赖治理轮（第三批）：zxing / cyclonedx 升级 + AGP 跳跃拒绝
        // versionCode 54：beta42 —— 用户体验与生命周期修复轮：通知权限运行时申请 +
        //                           网络回调注销 + 旋转状态保持 + 自适应图标/启动屏 + 死参数清理
        // versionCode 55：beta43 —— 交互修复轮：会话草稿按 taskId 隔离（防串会话错发）+
        //                           审批自动决议倒计时逐秒刷新 + debug 变体可选 release 签名开关
        // versionCode 56：beta44 —— 会话正文离线缓存加密（§11.6 安全门：AES-256-GCM +
        //                           独立 Keystore 密钥 + 版本化信封 + 新旧双读，落盘不可检索明文）
        // versionCode 57：beta45 —— 工程清债轮：关键界面 @Preview（debug 源集，release 零影响）
        //                           + tools/ 脚本索引 + 文档硬编码本机路径可移植化
        // versionCode 58：beta46 —— 弃用 API 清理轮：Icons.AutoMirrored 迁移 + 通知死代码 +
        //                           描边规范化，编译期 deprecation 警告归零
        // versionCode 59：beta47 —— 走查修复（第二至第八批）+ 国际化资源化 6 批完成
        //                           （strings.xml 67→452 条，ui/notify/widget 用户可见中文清零；
        //                            含断开态切换会话显示上一会话内容、切会话会话级状态未复位等修复）
        // versionCode 60：beta48 —— 走查清单 P2 剩余项闭环（#12 无障碍 / #13 Markdown 图片兜底 /
        //                           #14 会话流日期分隔 / #15 布局回归断言扩展），5.1 清单仅剩 #2
        // versionCode 61：beta49 —— 修复 beta48 的 #13「图片兜底」为死代码（CI 首次真正执行
        //                           仪器化用例时暴露）：库只在顶层分发组件，`components.image`
        //                           对段落内图片永不触发 → 改挂段落/引用块级；顺带修「URL 误取
        //                           整篇文档」与 180dp 空白占位
        // versionCode 62：beta50 —— 附件上传流水线重构（走查 5.1 清单最后一项 #2）：
        //                           递归回调 → 顺序 suspend 流水线 + 可定位内容源（O(1) 续传）
        //                           + 阻塞读下放 IO 线程 + 上传编排首次进入 JVM 单测
        // versionCode 63：beta51 —— V2 认证配对适配（PROTOCOL §11）：QR 双路分流 + 登录配对 +
        //                           V2AccountApi + TokenStore JWT；真机走查②通过
        // versionCode 64：beta52 —— V2 直连打通（真机走查③）：RelayClient token→ws2 直连
        //                           （auth_init 无 role）+ 他桥广播过滤 + V4 连接级握手前置 +
        //                           bootstrap V2 workspaces 解析；paired/bridge-ready 实证
        versionCode = 64
        versionName = "0.5.0-beta52"
        // 仪器化渲染回归网（src/androidTest）用 androidx.test 默认 runner。
        // 只在模拟器/真机上跑；CI runner 无设备，故 ci.yml 不接（见 HANDOVER §6.1 回归网）。
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    testOptions {
        // 渲染断言要求稳定帧：关掉系统的窗口/转场动画，避免捕获到中间态
        animationsDisabled = true
        unitTests.all {
            // 压力测试时长由环境变量注入（默认空 = 跳过；见 ProtocolStressTest——§11.3.3 的
            // 「30 分钟压力测试」不走常规门禁，本地取证运行：ZCODE_STRESS_SECONDS=1800 gradle ...）
            it.environment("ZCODE_STRESS_SECONDS", System.getenv("ZCODE_STRESS_SECONDS") ?: "")
        }
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
        // 真机仪器化取证开关（默认关闭 → CI/无 keystore 环境行为不变）：开启且本机有
        // keystore.properties 时 debug 变体改用 release 签名——可**同签名覆盖安装**已装 release 包
        // （免卸载、保配对凭据），从而在真机上跑 connectedDebugAndroidTest / 手动 instrument。
        // 用法：gradle :app:assembleDebug -PdebugUseReleaseSigning=true
        debug {
            if (debugUseReleaseSigning && keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
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
        // Macrobenchmark 被测变体（§11.8 长会话基准）：与 release 同优化等级（R8 + 资源裁剪，
        // 量的是真实发布形态的性能），但用 debug 签名——CI/本机没有 release keystore 也要能
        // 构建安装。isDebuggable=false 是 macrobenchmark 的硬要求（debuggable 包会被拒绝测量）。
        // 宿主 Activity 与 fixture 在 src/benchmark/（只进本变体，不进 debug/release 包）。
        create("benchmark") {
            initWith(getByName("release"))
            isDebuggable = false
            // 默认 debug 签名（CI/无 keystore 可构建）；-PbenchmarkUseReleaseSigning=true 时切
            // release 签名供真机取证覆盖安装（见文件头开关注释）。
            signingConfig =
                if (benchmarkUseReleaseSigning && keystoreProps.isNotEmpty()) {
                    signingConfigs.getByName("release")
                } else {
                    signingConfigs.getByName("debug")
                }
            matchingFallbacks += listOf("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true // 用 BuildConfig.DEBUG 隔离调试观测面板（HANDOVER 技术债）
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    // C-3：Material 图标替换 emoji（Mic / AttachFile 等不在 icons-core 小图标集里）。
    // 包体影响：R8 只保留被引用的 ImageVector（每图标约 1KB 级），实测增量记入 CHANGELOG。
    implementation("androidx.compose.material:material-icons-extended")
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
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    // 布局预览（@Preview）：只在 debug 变体需要（Android Studio 预览面板渲染器的实现）。
    // ui-tooling-preview（注解）已在 implementation 中；此处补渲染器本体，release 不打包。
    debugImplementation("androidx.compose.ui:ui-tooling")

    // 扫码配对：CameraX 预览 + ZXing 解码（无 Google 服务依赖，国内可用）
    // CameraX 1.4.x 起原生库按 16 KB 页对齐编译（Android 15 兼容性要求）
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("com.google.zxing:core:3.5.4")

    // 显式钉住 16 KB 对齐修复版，覆盖 compose BOM 传递的旧版 graphics-path
    implementation("androidx.graphics:graphics-path:1.1.0")

    // 会话页 Markdown 渲染：对齐桌面端的 GFM 表格/任务列表/代码块语法高亮。
    // 版本上限说明：本项目 Kotlin 插件为 2.0.20，而 Kotlin 元数据不向后兼容——
    // 该库 0.30.0 起已改用 Kotlin 2.1+ 编译，2.0.20 编译器读不了其元数据（硬报错）。
    // 0.27.0 是最后一个 Kotlin 2.0.x 编译的版本，故锁死在此；升级 Kotlin 插件前不得上调。
    // code 模块自带 dev.snipme:highlights 1.x（纯 JVM，无原生 .so）。
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.27.0")
    implementation("com.mikepenz:multiplatform-markdown-renderer-code:0.27.0")
}
