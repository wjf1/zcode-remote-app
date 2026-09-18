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

### 6.3 权限审批（P0 核心功能，机制已明）

- 权限请求以事件 `permission_request` 推送（ elicitation 为表单类请求），携带**文本选项**。
- 选项归类（手机端原文字符串集）：
  - `allowOnce` ← "allow"、"allow once"、"approve"
  - `allowAlways` ← "always allow"、"allow always"、"approve always"
  - `rejectOnce` ← "deny"、"deny once"、"reject"、"reject once"
  - `rejectAlways` ← "always deny"、"deny always"、"always reject"
  - 其余 → `custom`
- 应答 = 回传所选**选项序号**（映射 0/1/…），非布尔。App 可按上面四类渲染成规范的双排按钮 + 自定义输入。
- 【待验证】审批应答的精确 rpc 帧格式（在 conversation frame 层内）。

## 7. Bot Channel（辅路）

微信（`ilinkai.weixin.qq.com` 轮询）/飞书/Lark/Telegram；命令集 `status/new/workspace/model/mode/thoughtLevel/reply`；任务流 `createTask → prompt_sent → sendPrompt → completed/error`。与远程控制共用任务模型——M4 的 VPS Runner 可复用此任务 API 形态。

## 8. 对 M1 开发的结论

> **2026-09-16 里程碑更新**：M2 的会话流已**打通并在真机（模拟器）验证通过**。
> App 端可订阅会话、拿快照、并按增量实时渲染（实测：标题栏「已订阅 · 运行中 · 共 363 行」，
> 行按 reasoning / toolCall / assistantText 分类渲染，流式内容实时更新）。
> 关键结论与踩坑记录见 `research/CONVERSATION-PROTOCOL.md`。
> 尚未完成：向上翻页拉更早历史（`conversationRowsRangeV4`）、发送指令
> （`sendConversationCommandV4`）、权限审批应答、逻辑帧分片重组。

1. **可以开工**：握手（HMAC proof）、心跳、状态机、错误恢复、workspace/session RPC 方法面全部齐备，Kotlin 实现无未知阻塞。
2. 遗留 4 个【待验证】项（PC 侧 meta 字段、心跳间隔分配、maxPhysicalFrameBytes 值、conversation frame 二进制细节 + 审批应答帧）——前三者可用"容错实现 + 运行时日志"兜底；第 4 项在 M1 联调时以真机+一次受控抓包解决（届时再申请装 CA）。
3. 互踢风险确认存在（KICKED/session-conflict）：App 在线时官方 Web 版会被踢（反之亦然），M1 按"单端在线"设计，产品上做提示。

## 9. 版本基线

PC：ZCode 3.11.2 win-x64（`@zcodedesktop-updater`）。手机端：`index-nOVzQNKW.js`（2026-09-13 拉取）。升级后需 diff：`research/` 内留存了两侧原始 bundle 供比对。
