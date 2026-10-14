plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.20" apply false
    // §11.8 第二层门禁：静态分析与覆盖率（baseline 策略——存量登记、新增即红）
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.11" apply false
    id("org.cyclonedx.bom") version "2.4.1" apply false
    // §11.8 第二层门禁：KtLint 格式门禁（beta36 接线；规则键见仓库根 .editorconfig）
    id("org.jlleitschuh.gradle.ktlint") version "13.1.0" apply false
}
