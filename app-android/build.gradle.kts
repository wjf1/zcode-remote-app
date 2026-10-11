plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false
    // §11.8 第二层门禁：静态分析与覆盖率（baseline 策略——存量登记、新增即红）
    id("io.gitlab.arturbosch.detekt") version "1.23.7" apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.1" apply false
    id("org.cyclonedx.bom") version "2.2.0" apply false
}
