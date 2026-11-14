package com.zcode.remote.relay

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * 桥/握手能力位（G5 能力协商，2026-11-14 对齐官方铁律「能力协商而非版本分支」）。
 *
 * 官方出处：
 * - bridge-ready 的 `capabilities?: { windowProjectDirectory?: true }`
 *   （`packages/shared/src/web-remote-control.ts:403`）；
 * - hello 应答的 `capabilities.inputPrediction`（输入预测，v3.18.1 默认开启，
 *   `docs/web-remote-control/mobile-input-prediction.md`）。
 *
 * 严格判断口径（官方两条铁律，缺一不可）：
 * 1. **显式 `true` 才算支持**——官方 `hello.capabilities.inputPrediction === true`：
 *    缺字段 / 非布尔 / 字符串 `"true"` 一律 false（老桌面端缺字段 = 禁用，绝不当有）；
 * 2. **声明支持却缺服务必须报错，禁止静默降级**——消费侧拿到 `true` 后若对应 RPC 不可用，
 *    必须显式失败而不是悄悄跳过（`docs/web-remote-control/wide-screen-task-list.md` MRO02）。
 *    本类只负责解析；「声明了就要用」的义务由各消费点（G3 输入预测、后续文件树能力）承担。
 */
data class BridgeCapabilities(
    /** 输入预测（G3 消费）：`conversationInputPredictionV4` 是否可用。 */
    val inputPrediction: Boolean = false,
    /** 窗口目录能力组（宽屏文件树，官方 `window-project / window-remote-projects / window-project-changes`）。 */
    val windowProjectDirectory: Boolean = false,
) {
    /** 是否声明了任一能力（诊断日志用，只记布尔不记内容）。 */
    val anyDeclared: Boolean get() = inputPrediction || windowProjectDirectory

    companion object {
        /**
         * 官方 `=== true` 口径的严格判定（纯函数，单测钉住）：值必须是 JSON 布尔 `true`。
         * ⚠️ 必须显式排除字符串原语——kotlinx 的 `booleanOrNull` 对字符串 `"true"` 也会返回
         * true（`content.toBooleanStrictOrNull()`），而官方 zod `z.boolean()` 下字符串必然
         * 校验失败，等价口径是「非字符串原语且 booleanOrNull == true」。
         */
        internal fun boolTrue(
            obj: JsonObject?,
            key: String,
        ): Boolean =
            obj?.get(key)?.let { el ->
                el is JsonPrimitive && !el.isString && el.booleanOrNull == true
            } == true

        /** 从 capabilities 对象解析（缺对象/缺键 = 全 false）。 */
        fun fromCapabilities(capabilities: JsonObject?): BridgeCapabilities =
            BridgeCapabilities(
                inputPrediction = boolTrue(capabilities, "inputPrediction"),
                windowProjectDirectory = boolTrue(capabilities, "windowProjectDirectory"),
            )

        /** `workspace-bridge-ready` payload → 能力位（缺 `capabilities` 键 = 全 false）。 */
        fun fromBridgeReady(payload: JsonObject): BridgeCapabilities = fromCapabilities(payload["capabilities"] as? JsonObject)

        /** V4 hello 应答 payload → 能力位（同口径）。 */
        fun fromHello(payload: JsonObject): BridgeCapabilities = fromCapabilities(payload["capabilities"] as? JsonObject)
    }
}
