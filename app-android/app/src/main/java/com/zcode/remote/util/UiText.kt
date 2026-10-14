package com.zcode.remote.util

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * 用户可见文案的统一载体（国际化地基，任务书衍生项）。
 *
 * **为什么需要它**：本项目大量用户可见文案由**纯函数**产出
 * （[com.zcode.remote.ui.components.ToolKindLabels]、[com.zcode.remote.ui.components.StatusPanelLabels]、
 * [com.zcode.remote.relay.UserFacingError] 等），而 `stringResource()` 只能在 `@Composable`
 * 上下文调用；这些纯函数又刻意不依赖 Android（项目靠 JVM 单测而非 Robolectric 保证质量），
 * 强行注入 `Context` 会破坏既有测试策略。
 *
 * [UiText] 用「资源 id + 参数」表达文案，把「取字符串」推迟到有 Context 的地方
 * （Composable / 通知栏 / 桌面 Widget）。资源 id 是生成期 Int 常量，**JVM 单测可直接断言**，
 * 无需 Android 运行时。
 *
 * 两种形态：
 * - [Res]：`strings.xml` 里的文案（可本地化）；
 * - [Raw]：服务端下发 / 运行期拼接、无法资源化的文本（如错误 message、会话标题），原样展示。
 *
 * 设计取舍：默认语言为中文（`values/strings.xml` 即中文），故本次资源化**不改变任何可见文案**，
 * 现有仪器化测试的中文断言无需改动。
 */
sealed interface UiText {
    /** `strings.xml` 文案；[args] 为占位符参数（`%1$s` / `%1$d` 顺序对应）。 */
    data class Res(
        @StringRes val id: Int,
        val args: List<Any> = emptyList(),
    ) : UiText

    /** 非资源文本（服务端下发 / 运行期拼接）：原样展示，不参与本地化。 */
    data class Raw(
        val value: String,
    ) : UiText

    companion object {
        fun res(
            @StringRes id: Int,
            vararg args: Any,
        ): UiText = Res(id, args.toList())
    }
}

/** Composable 侧解析（唯一能调用 `stringResource` 处）。 */
@Suppress("SpreadOperator") // varargs 透传：args 是 List，只能经 toTypedArray() 展开；调用点极少且参数个位数
@Composable
fun UiText.asString(): String =
    when (this) {
        is UiText.Res -> if (args.isEmpty()) stringResource(id) else stringResource(id, *args.toTypedArray())
        is UiText.Raw -> value
    }

/** 非 Compose 侧解析（通知栏 / Widget / Service 等有 Context 处）。 */
@Suppress("SpreadOperator") // 同上
fun UiText.resolve(context: Context): String =
    when (this) {
        is UiText.Res -> if (args.isEmpty()) context.getString(id) else context.getString(id, *args.toTypedArray())
        is UiText.Raw -> value
    }

/** 便捷：把非空 String 包装为 [UiText.Raw]（空白视为 null）。 */
fun String?.toUiTextOrNull(): UiText? = this?.takeIf { it.isNotBlank() }?.let { UiText.Raw(it) }
