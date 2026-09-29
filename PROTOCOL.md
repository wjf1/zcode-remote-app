# ZCode Web Remote Control 协议文档（PROTOCOL.md v1.0）

> M0 产出物 · 2026-09-13
> 来源：① PC 端静态分析（`E:\ZCode\resources\app.asar`，ZCode 3.11.2 win-x64）
> ② 手机端静态分析（官方托管 Web App `https://zcode.z.ai/remote/v4` 的 JS bundle，已存 `research/index-nOVzQNKW.js`，4.8MB）
> 两侧代码交叉印证，主链路已闭环；少量实现细节标注【待验证】，不影响 M1 开发。

## 1. 端点

| 端点 | 用途 |
|---|---|
| `wss://zcode.z.ai/ws` | 主中继（手机端代码实证：`endpointOrigin:"https://zcode.z.ai"`） |
| `wss://zcode.chatglm.site/ws` | 备用中继（PC 端按 `endpointOrigin` 切换） |
| `https://zcode.z.ai/api/server-info` | 服务发现 |
| `.../api/rpc-host-capability` | 主机能力协商 |
| `https://zcode.z.ai/remote/v4` | 官方手机端 Web App（QR 落地页） |

环境变量覆盖：`ZCODE_WEB_REMOTE_CONTROL_RELAY_WS_URL`、`ZCODE_WEB_REMOTE_CONTROL_URL`。

## 2. 配对与凭据

- QR（`buildWebRemoteControlExternalQrUrl`）：
  `<remoteUrl>?sid=<deviceSid>&hash=<passHash>&t=<ms>&mid=<机器ID>&name=<PC名>&app_version=<版本>&theme=..`
- **passHash 是共享密钥**：只出现在二维码里，永不上送线路。
- PC 端持久化 `deviceSid` + `passHash`（设置项 `webRemoteControlExternalRelayDevice` + 凭据库 AES-256-GCM）；"刷新二维码" = rotate 换密钥，旧码失效。
- PC 与手机使用**同一个 deviceSid** 连接中继，由中继按 sid 配对两端。

## 3. 握手与认证（两侧代码交叉实证 + **真机实测验证**，可硬编码）

> ⚠️ 本节含 4 处静态分析无法发现、只有真机联调才暴露的关键细节（见 3.1），已在 M1 联调中修正并通过实测。

```
[建连] WSS wss://zcode.z.ai/ws?mid=<机器ID>     ← mid 为必需查询参数（缺失即 AUTH_FAILED）
       HTTP 头必须带 Origin: https://zcode.z.ai  ← 缺失即 AUTH_FAILED（中继校验来源）
任一端 → 中继   {"type":"auth_init","role":"device|terminal","device_sid":"<sid>",
                 "meta":{...},"client_ts":<ms>}
                  role="device"（PC）：meta【待验证】
                  role="terminal"（手机/App）：meta={"platform":"web","version":"web","name":"mobile-browser"}
中继 → 端       {"type":"auth_challenge","nonce":"<随机串>","server_ts":<秒>}
端 → 中继       {"type":"auth_response","device_sid":"<sid>","proof":"<见下>","client_ts":<ms>}
中继 → 端       {"type":"auth_ack","device_sid":"<sid>","terminal_sid":"t_xxxx","pair_status":"matched"}
                {"type":"pair_status_ack","pair_status":"waiting|matched","terminal_sid":""}
```

- 证明算法（**实测通过**）：
  `proof = base64url_no_padding(HMAC-SHA256(key=passHash, msg="${nonce}|${role}|${device_sid}"))`
  即官方 `btoa(...)` 后把 `+`→`-`、`/`→`_`、去掉尾部 `=`（43 字符）。**用标准 base64 会 AUTH_FAILED。**
- 配对状态：`waiting`（PC 未开远程控制）→ `matched`（PC 在线）→ 客户端置 `paired`。心跳帧 `{"type":"pair_status_query","device_sid","client_ts"}`。

### 3.1 真机联调暴露的 4 个关键点（M1 已实测修正）

| # | 问题 | 现象 | 修正 |
|---|---|---|---|
| 1 | WS URL 缺 `?mid=<deviceMid>` | `AUTH_FAILED`（连 challenge 都不下发） | 从二维码解析的 `mid` 追加为查询参数 |
| 2 | 缺 `Origin: https://zcode.z.ai` 请求头 | `AUTH_FAILED` | OkHttp 显式设置该头（浏览器自带，原生客户端必须手加） |
| 3 | proof 用标准 base64 | `AUTH_FAILED` | 改 base64url 无填充 |
| 4 | 业务负载缺 data 信封 / `client_ts` | 认证成功后收 `WRONG_PARAM` | 必须发 `{"type":"data","payload":{...},"client_ts":<ms>}` |

**排查顺序建议**：先补 Origin + mid（AUTH_FAILED 阶段），再查 proof 编码，最后查信封（WRONG_PARAM 阶段）。

### 3.2 单 terminal 槽位与互踢（M2 实测）

同一 `deviceSid` 在中继上**只有一个 terminal 位**：第二个连接会立刻踢掉第一个，被踢端收到 `{"type":"error","code":"KICKED"}`。

实测复现：Android App 与 `tools/probe.py` 探针同时连（同 sid）→ 探针先认证成功、随后被 KICKED。

**产品含义**：App、官方 Web 版、调试探针三者互斥，同一时刻只能有一个在线。App 端已把 KICKED 映射为「会话已在别处打开」提示。

### 3.3 调试工具：`tools/probe.py`

脱离 App/模拟器的协议探针（纯 Python 标准库，自带 WebSocket 实现，无需 pip）：

```bash
python tools/probe.py auth            # 验证中继认证
python tools/probe.py boot            # + bootstrap（拉会话列表）
python tools/probe.py bridge          # + 开桥（等 workspace-bridge-ready）
python tools/probe.py chan            # 在候选通道上试打 helloConversationV4，定位对话门面所在通道
python tools/probe.py sub <会话ID前缀> [帧落盘路径]   # + 完整会话流（链路/协商/监听/订阅）
```

- `sub` 已跑通完整会话流，并会把收到的 204 帧落盘；配套 `tools/_dump_frame.py` 输出结构骨架。
- 凭据自动读取：`~/.zcode/v2/setting.json`（deviceSid）+ `~/.zcode/v2/credentials.json`（解密 passHash，密钥算法见 3.1 节同款 `zcode-credential-fallback:<platform>:<home>:<user>` 的 sha256）。`mid` 目前需用环境变量 `ZCODE_MID` 传入（不会自动推导）。
- **跑 probe 必须用后台模式**（工具的 `run_in_background`），不要用 `nohup ... &`：
  后者会留下脱离父进程的常驻子进程，导致 ZCode 回收进程树失败并杀掉 agent host（见工作区 AGENTS.md）。
- 同 sid 与 App 互踢，调试时需先停 App。
- 用途：RPC/帧层调试可秒级迭代，不受模拟器与 UI 干扰；同时作为协议的**第二份独立实现**，用于校验 Kotlin 端行为一致。

## 4. 中继错误码与客户端恢复策略（手机端实证）

| code | 客户端行为 |
|---|---|
| `KICKED` | 终态：会话冲突（同 sid 重复连接被踢）→ 提示"已在别处打开" |
| `DEVICE_OFFLINE` | 进入等待重连（desktop-offline 计时器）|
| `AUTH_FAILED` / `WRONG_PARAM` | 终态：`invalid-mobile-connection`（二维码失效/错误） |
| `INTERNAL` | 已配对过则回到 waiting 等设备回来，否则重连 |

其他关闭/失败类别（PC 端枚举）：`disconnected`、`desktop-bootstrap-timeout`、`connection-recovery-timeout`、`relay-unavailable`、`unsupported-action`、`unexpected-error`。
断线缓冲：出站队列上限 50 条，溢出整批丢弃，**重连不回放**。帧大小上限存在（`maxPhysicalFrameBytes`，字面量未定位【待验证】）。

## 5. 内层负载（`data.payload`，`zcode_type` 路由，两侧一致）

| zcode_type | 方向 | 说明 |
|---|---|---|
| `bootstrap-request/response` | 手机↔PC | 会话桥初始化，`{requestId, success, result}` || `platform-request/response` | 双向 | 平台级请求【子命令待验证】 |
| `workspace-list-response/-updated` | PC→手机 | 工作区列表（响应+变更推送） |
| `workspace-bridge-open` → `workspace-bridge-ready/error` | 手机↔PC | 打开工作区会话桥（`bridgeSessionId`、`bridgeGeneration`、`recoveryId`） |
| `workspace-reconnect-request/response` | 手机↔PC | 断线恢复会话桥 |
| `rpc-frame` / `rpc-frame-ack` | 双向 | 业务 RPC。字段（zod `.strict()`，缺一即拒）：`rpc-frame` = `{zcode_type, bridgeSessionId, bridgeGeneration?, recoveryId?, seq>0, messageSeq>0, fragmentIndex(0..63), fragmentCount(1..64), messageBytes, checksum{algorithm:"crc32",value:<hex8>}, dataBase64}`；`rpc-frame-ack` = `{zcode_type, bridgeSessionId, bridgeGeneration?, recoveryId?, ackMessageSeq}`。**身份三元组全等校验，任一身份键出现就必须三个都相等**——ack 漏 `bridgeGeneration` 会被静默丢弃并导致服务端无限重放（见 CONVERSATION-PROTOCOL.md §8） |
| `mobile-view-state-update` | 手机→PC | 视图状态 + deviceInfo |
| `mobile-diagnostic` | 手机→PC | 诊断上报（state/closeCode/online/visibilityState 等） |
| `app-error` / `bridge-degraded` | PC→手机 | 错误 / 桥降级 |

**所有出站业务帧必须包 `data` 信封**：`{"type":"data","payload":{...},"client_ts":<ms>}`（3.1 节实测）。

### 5.1 bootstrap-response 实测结构（M1 已跑通并渲染）

```jsonc
{"zcode_type":"bootstrap-response","requestId":"boot-<ms>","result":{
  "initialViewState":{"activeTaskId":"sess_xxx","activeWorkspaceKey":"F:\\AI\\Zcode","updatedAt":1789...},
  "tasks":[{
    "taskId":"sess_85338537-...", "title":"Zcode远程控制安卓APP方案设计",
    "displayStatus":"running|completed",      // running/streaming/completed/error/...
    "workspaceKind":"local", "workspaceLabel":"Zcode", "workspacePath":"F:\\AI\\Zcode",
    "provider":"glm", "createdAt":1789..., "updatedAt":1789..., "archived":true(可选)
  }, ...]}}
```

- App 端据此渲染会话列表（实测：32 个会话、标题与状态全部正确）。归档项（`archived:true`）应过滤。

## 6. 业务层

> ⚠️ **本节 §6.1 的通道/方法名结论已被证伪，请以 `research/CONVERSATION-PROTOCOL.md` 为准。**
> 会话（对话）方法在 **`zcode-agent`** 通道上，方法名是服务对象的 camelCase 属性名
> （`subscribeConversationV4`、`onDynamicConversationFrame` …），**不是** `zcode-session` 通道、
> 也不是 `v4/conversation/subscribe` 这种字符串（那是 host→CLI 内部层，另一回事）。
> 完整调用序列、204 逻辑帧信封、快照/增量与行模型见 `research/CONVERSATION-PROTOCOL.md`。

### 6.1 RPC 方法（PC host 端枚举，24+12+3 个）

`session/*`：create、send、stop、resume、list、messages、read、events、event、subscribe、fork、compact、close、usage、subagents、goal、setModel、setMode、setThoughtLevel、set_model、cancelBackgroundTask、updateRuntimeModelConfig、requestRuntimePreferences
`workspace/*`：readState、generateText、cancelGenerateText、setDefaultModel、set_default_model、setDefaultMode、setDefaultThoughtLevel、upsertModelProvider、removeModelProvider、updateProviderRegistry、updateModelIoPreferences、updateInteractionPreferences
其他：`mcp/list`、`usage/stats`、`skills/referenceCatalog`
内层最深处方法名：`v4/conversation/frame`（会话帧，varint 变长编码的封装层【二进制细节待验证】）。

### 6.2 任务事件流（手机端校验集实证）

事件类型全集：`created, prompt_sent, resumed, streaming, permission_request, permission_resolved, elicitation_request, elicitation_resolved, updated, completed, error`
事件字段：`{type, workspacePath, taskId, updatedAt, workspaceIdentity, ...}`
状态映射：`permission_request/elicitation_*` → UI 态 `streaming`；`updated` → `ready`；`error` → `failed`。

### 6.3 权限审批（P0 核心功能，2026-09-18 已实现手机端应答；2026-09-28 端到端验收通过）

> **2026-09-28 实测补充（本节为准）**：桌面端推给手机端的审批走**两条并存的路**，App 端已双源合并：
> ① **会话帧路径**：`snapshot.pendingInteractions[]` 与 `state.updated` patch 里的 `pendingInteractions`
> （整组替换语义）——**实测确认桌面端确实推送**（App 日志 `pendingInteractions → N 条`）；
> ② **任务事件流路径**（host 源码 `permissionRequestToStreamEvent` 实证）：
> `{type:"permission_request", taskId, requestId, description: reason||toolName, kind: toolName,
> title: toolName, options:[{optionId, kind, name?, response?}], raw}`——注意此路径**没有
> interactionId/payload 包裹**，`requestId` 即应答时的 `interactionId`；选项元素**没有 label**
> （显示名由 kind 生成：允许/始终允许/拒绝/始终拒绝，custom 用 name）。消解推送
> `{type:"permission_resolved", requestId}`。
> 端到端验收记录：锁屏状态下 App 收到审批 → 通知栏「允许一次」自动点击 → `resolveInteraction`
> → 桌面端回 `Accepted(status=accepted)` → 被批准的 Bash 实际执行。

- 请求的到达方式不是独立 RPC，而是**会话流里的状态**：`snapshot.pendingInteractions[]`
  与 `deltas` 中 `state.updated.patch.pendingInteractions`（**整组替换**语义，不是逐条增删）。
  任务事件 `permission_request` 只用于列表页角标，选项详情仍要从会话流取。
- 一条 pending 的关键字段：`interactionId`、`kind`（`permission` / `elicitation`）、
  `payload{toolName, toolCallId, summary, detail, options[]}`、`anchorRowId`、
  `autoResolution.deadlineAt`（桌面端倒计时，到点后手机再答会得到 `noop`）。
- `options[]` 每项含 `optionId`、`label`、`kind`、`response.decision`。归类：
  - `allowOnce` / `allowAlways` / `rejectOnce` / `rejectAlways` / `custom`
- **应答 = `sendConversationCommandV4`，回传 `optionId` 原文，不是序号。**
  早期文档写的"映射 0/1/… 序号"是错的：官方 UI 的显示顺序是按 `kind` 排序后的位次，
  与数组下标不一致，按序号回传会答错选项。证据（两条独立链路互相印证）：
  - 手机端 web bundle `research/index-nOVzQNKW.js` @536751（envelope 工厂）、
    @546972（`sendCommand`）、@129232（answer 的 zod schema 只认 `{optionId}`）、
    @2295576（`requestId === interactionId`）、@2300846（`onRespond`）；
  - PC host `asar/out/host/index.js` @456048（`respondPermission`）。
- 请求 envelope：`{workspacePath, workspaceIdentity?, envelope:{commandId, clientId, sessionId,
  type:'resolveInteraction', payload:{interactionId, answer:{optionId}}, issuedAt}}`。
- 成功判据：ack `status ∈ {accepted, duplicate, noop}`（`duplicate`/`noop` 表示他处已消解，
  不是失败）。硬约束：`clientId` 必须等于本次连接 `initializeConversationV4` 用的那个，
  否则 host 抛 `fault.command.clientMismatch`（`host/chunk-BG4MS6RN.js` @54591）。
- `resolveInteraction` 被官方归类为 sensitive 命令：**断线后不自动重放**，
  重连要用 `queryConversationCommandsV4` 回查。本 App 的做法是撤下通知并提示用户重新点。
- 同一会话可能有多条并行 pending（subagent 场景），必须按 `interactionId` 精确匹配。

### 6.4 发送与停止（P0-1，2026-09-29 实证 + 端到端实测）

- **发送**：`zcode-agent` 通道 `sendPrompt`，args=`{workspacePath, sessionId, inputId, content}`，
  201 应答 `{sessionId, accepted:true, stateRevision}`。消息进会话队列（autoDrain），
  userInput 行由服务端推回会话流（客户端无需本地 append）。M2 已实测，P0-1 复用。
- **停止是 envelope 命令，不是裸 RPC**（host asar 官方 web 版实证）：
  官方调用 `br('stop', payload, sessionId)`，即 `sendConversationCommandV4` envelope
  `{commandId, clientId, sessionId, type:'stop', payload, issuedAt}`；
  payload zod schema = `stop:{expectedForegroundExecutionId: string.min(1).optional()}`——
  `control.activeWorks[]` 里有 `foregroundExecutionId` 就带上（防误停），否则发空 `{}`。
  注意 `sessionStop:"session/stop"` 是 host→CLI 内部层枚举，与远程通道方法无关（§6.1 同类）。
  ack 与 resolveInteraction 同判据：`{status:"accepted", revisionAtDecision}`。
- **停止按钮显示条件**：快照/`state.updated` patch 的 `control.canStop`（服务端算好下发），
  `control.stopState ∈ {idle, stoppable, stopping}` 可用于「停止中…」态。
- **端到端实测记录**（tools/_p01_async.py，桌面端在线）：sendPrompt 201 `accepted:true` →
  turn 开始，增量推 `control: {phase:"running", canStop:true, stopState:"stoppable"}` →
  stop ack `status:"accepted"` → 相位 `running → completedInterrupted`、`canStop:false`。
  四项 PASS，帧证据 `_tmp/p01_frames_*.json`。
- 桌面端「移动端远程控制」面板打开期间 device 在线（waiting→有 terminal 连接即 matched）；
  关闭面板或 terminal 断开后回到 waiting。`webRemoteControlLastEnabledContext`（setting.json）
  是桌面端启动时自动恢复远程控制的持久化上下文。

### 6.5 表单交互 elicitation（P1-1，2026-09-29 实证 + 端到端实测）

- **请求的两条到达路径**（与审批 §6.3 完全对称）：
  ① 会话帧 `pendingInteractions[]` 里 kind 为 **"userInput"** 的条目（不是 "elicitation"）：
  `{interactionId, kind:"userInput", anchorRowId, autoResolution{deadlineAt}, payload:{
  prompt, freeText, toolName, toolCallId, traceId, input, schema{toolName, interaction?, plan?},
  questions:[{question, header, options:[{value,label,description}], multiSelect}]}}`（真实帧实测）；
  ② 任务事件流 `elicitation_request`（host `userInputRequestToElicitationStreamEvent`）：
  `{taskId, requestId, message, header, options[{value,label,description}], multiSelect?, questions?, schema?}`。
- **应答 = 同一个 `resolveInteraction` envelope**（与审批同管道），answer 键按形态选择
  （host answer zod：`{optionId?, freeText?, action?(accept/decline/cancel), content?(Record<string,unknown>)}`；
  官方 web v4 `onRespond → T(interactionId, {action:n, ...content})`）：
  - 带 questions 的表单（AskUserQuestion/plan_approval）：`{action:"accept", content:{answer: 首选值}}`
    （单题）/ `{content:{answer_0:…, answer_1:…, answers:{问题文本:"合并答案"}}}`（多题，官方 rut 构造）；
    拒绝 → `{action:"decline"}`；plan_approval 批准即 `{action:"accept"}`（无 content）。
  - 无 questions 的确认/文本条目：点选项 → `{optionId}`；自由文本 → `{freeText}`。
- **端到端实测**（tools/_p11_verify.py，桌面端在线）：AskUserQuestion 真实触发 →
  pendingInteractions 出现 userInput 条目（perm_…，questions 3 选项）→
  resolveInteraction `{action:"accept", content:{answer:"A=提交验收报告"}}` →
  ack `status:"accepted"` → 条目从 pendingInteractions 消解。三项 PASS。
- 桌面端 pending 条目带 `autoResolution.deadlineAt`（约 5 分钟倒计时），超时后应答得 noop。

## 7. Bot Channel（辅路）

微信（`ilinkai.weixin.qq.com` 轮询）/飞书/Lark/Telegram；命令集 `status/new/workspace/model/mode/thoughtLevel/reply`；任务流 `createTask → prompt_sent → sendPrompt → completed/error`。与远程控制共用任务模型——M4 的 VPS Runner 可复用此任务 API 形态。

## 8. 对 M1 开发的结论

> **2026-09-16 里程碑更新**：M2 的会话流已**打通并在真机（模拟器）验证通过**。
> App 端可订阅会话、拿快照、并按增量实时渲染（实测：标题栏「已订阅 · 运行中 · 共 363 行」，
> 行按 reasoning / toolCall / assistantText 分类渲染，流式内容实时更新）。
> 关键结论与踩坑记录见 `research/CONVERSATION-PROTOCOL.md`。
>
> **2026-09-18 更新**：`sendConversationCommandV4` 的权限审批应答已按 §6.3 实现
> （会话内卡片 + 通知栏按钮，见 `ConversationChannel.resolve()`），
> 并在模拟器上通过 debug 注入验证了通知渲染与"连接已断时不假装批准"。
> **2026-09-28 更新**：**M2 端到端验收通过**——切 build 模式 → 触发工具调用 → 锁屏收到审批推送
> → 通知栏「允许一次」自动批准 → `resolveInteraction` → 桌面端 `Accepted` → 命令放行执行，全链路闭环。
> 验收过程中另获两个工程结论：① 模拟器**熄屏触发 Doze 会切断 App 网络**（`SocketException:
> connection abort`，重连持续失败），测试机需 `adb shell dumpsys deviceidle disable` + 保持充电；
> 真机对应 HyperOS 省电白名单引导（M3）。② **setMode 只对新 turn 生效**：对运行中的 agent
> turn 切模式不改变其权限上下文（实测两次"验收失败"均由此产生，非协议问题）。
> **待真实审批端到端验收**：~~桌面端会话默认是 `yolo` 模式~~（已完成，见上）。
> **2026-09-28 补**：向上翻页（`conversationRowsRangeV4`，atLogEpoch 校验 + 前插）与接收侧
> 逻辑帧分片重组（按 messageSeq 缓冲、CRC32+messageBytes 双校验）均已实现并实测通过
> （连续翻页 +60 行×2；190 帧单分片直通无回归）。

1. **可以开工**：握手（HMAC proof）、心跳、状态机、错误恢复、workspace/session RPC 方法面全部齐备，Kotlin 实现无未知阻塞。
2. 遗留 4 个【待验证】项（PC 侧 meta 字段、心跳间隔分配、maxPhysicalFrameBytes 值、conversation frame 二进制细节 + 审批应答帧）——前三者可用"容错实现 + 运行时日志"兜底；第 4 项在 M1 联调时以真机+一次受控抓包解决（届时再申请装 CA）。
3. 互踢风险确认存在（KICKED/session-conflict）：App 在线时官方 Web 版会被踢（反之亦然），M1 按"单端在线"设计，产品上做提示。

## 9. 版本基线

PC：ZCode 3.11.2 win-x64（`@zcodedesktop-updater`）。手机端：`index-nOVzQNKW.js`（2026-09-13 拉取）。升级后需 diff：`research/` 内留存了两侧原始 bundle 供比对。
