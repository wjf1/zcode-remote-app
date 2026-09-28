# 变更记录 / Changelog

## v0.2.2-m3a（2026-09-28）

**里程碑：M3 开始 —— 设置能力（线路切换 + 主题）**

### 新增
- **协议线路切换**（设置面板）：自动（按配对二维码推断）/ 主线 `wss://zcode.z.ai/ws` / 备线 `wss://zcode.chatglm.site/ws` / 自定义中继（配合桌面端 `ZCODE_WEB_REMOTE_CONTROL_RELAY_WS_URL`）；切换即时重连，Origin 按线路同源推导（不再硬编码 z.ai）。
- **主题三模式**：深色（默认）/ 跟随系统 / 浅色（新增 LightScheme）；根视图铺 `Surface` 背景，浅色下不再露出黑底。
- `SettingsStore`（SharedPreferences）承载设置项，与凭据存储分离。

### 实测（模拟器）
- 备线切换：连接 `wss://zcode.chatglm.site/ws`，握手认证 + `pair_status matched` 成功；切回自动恢复主线「已配对」。
- 主题：浅色全 UI 生效（卡片/背景/文字），chip 选中态正确，设置随重启保持。

### 工程调整
- Gradle daemon 堆降至 `-Xmx1024m`（与模拟器共存的内存现实约束）。

## v0.2.1-m2b（2026-09-28）

**里程碑：M2 遗留项收尾 —— 历史翻页与分片重组**

### 新增
- **历史翻页**：`conversationRowsRangeV4`（`beforeRowId` + `limit=60`），会话页滚到顶部自动拉取；`atLogEpoch` 不匹配整批丢弃；拉回行按 rowId 升序前插（RowStore.prepend），视口锚定原首行不跳变；顶部状态提示（加载更早… / 已到最早 / 上滑加载更早）。
- **接收侧分片重组**：rpc-frame 多分片按 `messageSeq` 缓冲、收齐按 `fragmentIndex` 升序拼接，CRC32 + messageBytes 双校验通过才解码与 ack（失败不 ack，交给服务端按 seq 重放）；缓冲上限 8 条防泄漏。

### 实测（模拟器，本会话 510 行）
- 连续翻页 2 页各 +60 行（beforeRowId 451→391），hasMore 正确传递，历史行渲染正常。
- 全程 190 帧 rpc-frame 全部单分片（fragmentCount=1），直通路径无回归；多分片路径为协议兜底（实环境中暂未出现）。

### 遗留
- elicitation（表单类交互）只读不答（M3+）。

## v0.2.0-m2（2026-09-28）

**里程碑：M2 审批与推送 —— 端到端验收通过**

### 新增（App）
- 会话流实时渲染：`subscribeConversationV4` 全订阅序列（listen → hello → initialize → subscribe）、快照 + 增量 deltas，行按 reasoning / toolCall / assistantText 分类渲染。
- 权限审批 UI：会话内审批条（批准/拒绝）+ 通知栏直接批准/拒绝（高优先级通知，最多 3 个按钮，按 kind 排序）。
- 审批双源接收：会话帧 `pendingInteractions`（snapshot / `state.updated` patch 整组替换）+ 任务事件流 `permission_request` / `permission_resolved`（host 源码实证路径），`AppViewModel` 合并对齐通知栏。
- 审批应答：`sendConversationCommandV4{resolveInteraction}`，`clientId` 硬约束、`optionId` 原文透传；连接断开时明确提示「没发出去」，不做自动重放。
- 前台服务 `ConnectionService`、`POST_NOTIFICATIONS` 运行时授权。
- debug 构建内置 `DebugApprovalReceiver`（注入假审批验证通知渲染；release 不含，无对外攻击面）。

### 新增（工具链）
- `tools/setmode.py`：经中继桥切换会话权限模式（yolo ↔ build），用于审批端到端验证。
- `tools/_e2e_send.py`：以手机端身份向会话发送排队消息（`sendPrompt`）。
- `tools/_e2e_tap_approve.py`：模拟器验收辅助（等待审批通知 → 解锁 → 展开通知栏 → uiautomator 点击「允许一次」→ 回读 logcat 证据）。

### 实测结论（写入 PROTOCOL.md §6.3 / §8）
- 桌面端审批推送双路并存：会话帧 `pendingInteractions`（实测确认）+ 任务事件 `permission_request`（`requestId` 即 `interactionId`，选项元素无 `label` 用 `name`）。
- `setMode` 只对新 agent turn 生效，运行中 turn 的权限上下文不变。
- 模拟器熄屏触发 Doze 会切断 App 网络（`SocketException`），测试机需 `dumpsys deviceidle disable` + 保持充电；真机对应 HyperOS 白名单引导（M3）。

### 端到端验收记录
切 build → 触发工具调用 → 锁屏收到审批推送 → 通知栏「允许一次」自动批准 → 桌面端 `Accepted(status=accepted)` → 命令实际执行。证据：App 日志 `ConvChannel: resolve interaction=…` / `AppViewModel: resolve result=Accepted`。

### 已知遗留
- 向上翻页拉更早历史（`conversationRowsRangeV4`）、逻辑帧分片重组未实现。
- elicitation（表单类交互）只读不答。

## v0.1.0-m1（2026-09-16）

**里程碑：M1 App 骨架 + 配对 + 会话 —— 模拟器验收通过**

- 扫码配对（CameraX + ZXing）、手动粘贴兜底、AES-256-GCM + Android Keystore 凭据存储。
- 完整官方中继握手（`mid` 查询参数、`Origin` 头、base64url proof、`data` 信封 + `client_ts` 四处实测修正）、`waiting/matched` 状态机、30s 心跳、指数退避重连、KICKED/AUTH_FAILED 错误分类。
- 会话列表（bootstrap-response，过滤归档）、11 种任务事件实时渲染、Material 3 深色主题。

## v0.1.0-m0（2026-09-13）

**里程碑：M0 协议逆向**

- PC 端 `app.asar`（3.11.2）与手机端 Web bundle 双侧静态分析交叉印证。
- 产出 PROTOCOL.md：端点、配对与凭据、握手与 HMAC proof 算法、错误码、RPC 方法面、任务事件集。
- `tools/probe.py` 纯标准库协议探针（第二份独立实现，用于交叉校验）。
