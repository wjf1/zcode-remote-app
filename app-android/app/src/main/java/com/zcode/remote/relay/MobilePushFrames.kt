package com.zcode.remote.relay

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * 官方离线推送事件 schema 固化（F6 协议准备）。
 *
 * 契约来源：官方 `packages/shared/src/web-remote-relay/contract.ts:41-61`
 * （`mobilePushRunSchema` / `mobilePushEventSchema`，strictObject）+ 官方
 * `docs/web-remote-control/mobile-event-push.md`。
 *
 * **端到端边界（重要，勿当成可直连的能力）**：官方推送链路是
 * `桌面 Main → 官方 HTTP 上报接口 → Go 通知服务 → agent-im inner-send → 手机系统推送`。
 * 中间的通知服务与 agent-im 凭据由**官方部署方**在 Nacos 配置（`mobile_push.enabled` 默认
 * false），且设备注册标识 `zbot_<device_id>_<user_id>` 需官方 agent-im SDK 流程——第三方
 * App 无法自建该链路。本类只固化 schema，供两种场景：
 * 1. 未来官方若开放推送通道 / 官方 KMP 客户端公开参考实现时直接消费同一事件格式；
 * 2. 解析系统通知 `data` Ext（`open_target=zcode_remote_control` 携带的
 *    conversation_id / run_id / event_id / workspace_id），做点击落地路由的前置。
 */
object MobilePushFrames {
    val json = Json { ignoreUnknownKeys = true }

    /** 官方五类推送事件（`kind` 枚举，contract.ts:47-52）。 */
    const val KIND_QUESTION = "question"
    const val KIND_APPROVAL = "approval"
    const val KIND_PLAN = "plan"
    const val KIND_TASK_COMPLETED = "task_completed"
    const val KIND_TASK_FAILED = "task_failed"

    @Serializable
    data class PushEvent(
        val version: Int = 1,
        val locale: String? = null,
        @SerialName("session_title") val sessionTitle: String? = null,
        @SerialName("desktop_platform") val desktopPlatform: String? = null,
        /** 生成方（业务 owner）保证稳定的去重键；同一事实 resolve 时补发同 id、state=resolved。 */
        @SerialName("event_id") val eventId: String,
        val kind: String,
        @SerialName("workspace_id") val workspaceId: String,
        @SerialName("session_id") val sessionId: String,
        @SerialName("run_id") val runId: String,
        @SerialName("interaction_id") val interactionId: String? = null,
        /** 状态 open / resolved（resolve 时补发同 event_id）。 */
        val state: String,
        /** 事件发生时刻（unix 秒，非毫秒——官方 schema 口径）。 */
        @SerialName("occurred_at") val occurredAt: Long,
    ) {
        val isOpen: Boolean get() = state == "open"
        val isKnownKind: Boolean
            get() =
                kind in
                    setOf(
                        KIND_QUESTION,
                        KIND_APPROVAL,
                        KIND_PLAN,
                        KIND_TASK_COMPLETED,
                        KIND_TASK_FAILED,
                    )
    }

    /** 系统通知 `data` Ext 的可解析字段（官方文档 170-177 行；不信任 URL，只取定位键）。 */
    @Serializable
    data class PushIntent(
        @SerialName("open_target") val openTarget: String? = null,
        @SerialName("source_device_id") val sourceDeviceId: String? = null,
        @SerialName("conversation_id") val conversationId: String? = null,
        @SerialName("run_id") val runId: String? = null,
        @SerialName("event_id") val eventId: String? = null,
        @SerialName("workspace_id") val workspaceId: String? = null,
    ) {
        /** 是否是远控落地意图（`open_target == zcode_remote_control`）。 */
        val isRemoteControlTarget: Boolean get() = openTarget == "zcode_remote_control"
    }

    /** 解析事件 JSON（宽容：未知字段忽略）。失败返回 null，调用方静默丢弃即可。 */
    fun parseEvent(raw: String): PushEvent? = runCatching { json.decodeFromString(PushEvent.serializer(), raw) }.getOrNull()

    /** 解析事件 JSON（JsonObject 形态，同口径）。 */
    fun parseEvent(raw: JsonObject): PushEvent? = runCatching { json.decodeFromJsonElement(PushEvent.serializer(), raw) }.getOrNull()

    /** 解析通知 data Ext（宽容）。 */
    fun parseIntent(raw: String): PushIntent? = runCatching { json.decodeFromString(PushIntent.serializer(), raw) }.getOrNull()
}
