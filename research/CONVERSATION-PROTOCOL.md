# 会话流协议（CONVERSATION-PROTOCOL.md）

> M2 产出物 · 2026-09-16
> 来源：host 侧 asar 解包源码 + 官方 mobile web bundle + **真机实测（tools/probe.py 已跑通）**
> 本文只写**已验证**的结论；未验证的标注【待验证】。
> ⚠️ 本文修正了 PROTOCOL.md / FRAME-CODEC.md 中两处**已被证伪**的结论，见 §7。

## 1. 通道与分层（关键更正）

会话（对话）方法**不在 `zcode-session` 通道上**，而在 **`zcode-agent`**。

| 通道名 | 服务 | 有的方法（例） |
|---|---|---|
| `zcode-session` | `createZCodeSessionService` | `createSession`、`getWorkspaceRuntimeIdentity`、`readWorkspaceState`、`setModel`、`setThoughtLevel`、`readSession`、`closeSession`、`updateProviderRegistry` |
| **`zcode-agent`** | `createZCodeAgentService` | **`helloConversationV4`、`initializeConversationV4`、`subscribeConversationV4`、`onDynamicConversationFrame`、`conversationRowsRangeV4`、`sendConversationCommandV4`、`unsubscribeConversationV4`、`resyncConversationV4`** … |

实测：把 `subscribeConversationV4` 发到 `zcode-session` →
`202 {"message":"Method not found: subscribeConversationV4"}`；发到 `zcode-agent` → 正常。

## 2. 方法名规则（两层别混）

- **线上方法名 = 服务对象上的 JS 属性名，原样使用，不做任何变换。**
  `fromService` 的实现就是 `serviceObject[methodName]`，取不到才抛 `Method not found:`。
  → 所以是 camelCase 的 `subscribeConversationV4`，**不是** `v4/conversation/subscribe`。
- `v4/conversation/subscribe`（下划线风格那一套）是 **host→CLI/agent 内部协议**的方法字符串枚举，与远程 RPC 通道不是同一层。把它发到通道上必然 `Method not found`。

## 3. 帧类型

| type | 名称 | 说明 |
|---|---|---|
| 100 | Promise | 普通请求；参数段是**参数数组** `serialize([arg0, arg1, …])` |
| 101 | PromiseCancel | 取消一次 Promise |
| **102** | **EventListen** | 注册事件监听；参数段是**单个裸值** `serialize(arg)`，**不是数组** |
| 103 | EventDispose | 移除事件监听 |
| 200 | Initialize | 通道就绪。head 只有 type（`[200]`），无 id；每个 ChannelServer 实例发一次 |
| 201 | PromiseSuccess | 应答成功，`data` = 返回值 |
| 202 | PromiseError | 应答失败，`data` = `{message, name, stack[], code?, …}` |
| 203 | PromiseErrorObj | 非 Error 的失败值 |
| 204 | EventFire | 事件推送，`data` = 事件负载 |

- 编码：客户端→服务端 `serialize([type,id,channelName,method]) ++ <参数段>`；
  服务端→客户端 `serialize([type,id]) ++ serialize(data)`（200 除外，只有 `[type]`）。
- **请求必须在收到 200 之后发**，否则被服务端丢弃。
- 方法名不存在时：100 会被包成 **202**（消息含 `Method not found: X`）；
  但 **102 走的是没有 try/catch 的路径，异常直接冒泡、不回任何响应**（表现为静默）——排查时别把「102 无响应」误判成网络问题。

## 4. 建立会话流的完整调用序列（实测通过）

前提：已认证、已开桥（`workspace-bridge-open` → `workspace-bridge-ready`）、已收到 200 Initialize。
设 `W = {workspacePath, workspaceIdentity?}`，会话 id 为 `S`。

```
1) 102  zcode-agent  onDynamicConversationFrame   arg = W                ← 裸对象，注册会话流监听
2) 100  zcode-agent  helloConversationV4          args = []              → 201 hello
3) 100  zcode-agent  initializeConversationV4     args = [clientHello]   → 201
4) 100  zcode-agent  subscribeConversationV4      args = [{...W, sessionId:S}]
                                                                         → 201 {ack:{subscriptionId, mode, logEpoch, openTiming}}
5) 持续收 204（事件 id = 第 1 步的请求 id）
```

**第 2、3 步不可省**：跳过握手直接 subscribe 会得到
`202 fault.connection.handshakeRequired`。

### 4.1 hello 应答（201 data）

```jsonc
{"kind":"hello","protocolVersion":3,"connectionId":"host-rpc-<uuid>",
 "clientMode":"web-remote-replayable",          // 中继/远程客户端
 "deliveryProfile":"replayable",
 "serverTime":1789...,
 "capabilities":{"nativeDialogs":false,"localTerminal":false,
                 "binaryFrames":false,"compression":"none","workspaceHookReview":true},
 "auth":{}}
```

### 4.2 clientHello 入参（zod `.strict()`，字段必须精确）

```jsonc
{"kind":"clientHello",
 "protocolVersion":3,                 // 回显 hello 里的版本
 "clientId":"<稳定客户端 id>",
 "clientKind":"mobileApp",            // desktop|web|mobileRemote|mobileApp，可选
 "appVersion":"1.0.0"}
```

`clientId` 会被服务端记住（换值报 `fault.connection.clientChanged`），后续 `sendConversationCommandV4` 会校验它。

### 4.3 subscribe 入参 / 应答

入参（数组包一个对象）：

```jsonc
[{ "workspacePath": "...", "workspaceIdentity": "...?", "sessionId": "sess_...",
   "base": {"logEpoch":"...","seq":123}? ,        // 带上才可能拿 mode:resume
   "visibility": "foreground|background"? }]
```

应答：

```jsonc
{"ack":{"subscriptionId":"sub-<...>",
        "mode":"snapshot"|"resume",
        "logEpoch":"<...>",
        "openTiming":{...}?}}
```

- 首次订阅不带 `base` → `mode:"snapshot"`。
- `subscriptionId` 是后续帧的**关联键**：帧必须同时匹配 `topic` 与 `subscriptionId`，否则丢弃（含旧订阅的迟到帧）。

## 5. 204 事件负载：逻辑帧信封

```jsonc
{"wireVersion":3,
 "kind":"complete"|"fragment",
 "deliveryKind":"initial"|"online"|"recovery",
 "logicalFrameId":"sub-...-lf-1",
 "logicalFrameOrdinal":1,
 "topic":"conversation/<sessionId>",
 "subscriptionId":"sub-...",
 "frame":{ …topic 帧… }          // kind=complete 时
 // kind=fragment 时改为 fragmentIndex/fragmentCount/logicalBytes/checksum/dataBase64，需先重组
}
```

`frame`（topic 帧）：

```jsonc
{"topic":"conversation/<sessionId>","subscriptionId":"sub-...",
 "fromSeq":0,"toSeq":2691,"sentAt":1789...,
 "payload":{"kind":"snapshot","snapshot":{...}}      // 首帧
        |  {"kind":"deltas","deltas":[...]}          // 增量
}
```

- 首帧：`deliveryKind:"initial"`、`payload.kind:"snapshot"`、`fromSeq===0`。
- 之后：`deliveryKind:"online"`、`payload.kind:"deltas"`，`fromSeq`/`toSeq` 单调推进。
- **空 `deltas:[]` 是合法的**（实测大量存在）：只推 seq，无内容变更，当作心跳忽略即可。

### 5.1 增量操作（op）

```jsonc
{"op":"row.appended","row":{...}}
{"op":"row.upserted","row":{...}}
{"op":"row.removed","fromRowId":123}
{"op":"row.delta","rowId":123,"path":"text|inputText|output.text|summaryText","append":"<增量片段>"}
{"op":"state.updated","patch":{...}}
```

流式文本 = `row.delta` 的 `append` 累加到 `rowId` 对应行的 `path` 字段上。

## 6. 快照与行模型

### 6.1 snapshot 顶层

```jsonc
{"protocolVersion":1,"sessionId":"sess_...","logEpoch":"...","seq":2691,"revision":2195,
 "control":{"phase":"completedInterrupted","sessionEnded":true,"canStop":false,
            "stopState":"idle","stopTargetKind":"unknown","activeWorks":[],
            "lastError":null,"apiRetry":null},
 "availability":{...},"inputRouting":{"mode":"startNow"},
 "meta":{"title":"...","titleSource":"generated"},
 "config":{"provider":"...","model":"deepseek/...","thought":"high",
           "thoughtLevels":[...],"followupMode":"queue","mode":"yolo"},
 "modelTransition":null,"usage":{"contextWindow":{},"cumulative":{}},
 "queue":{"items":[],"autoDrain":true},
 "pendingInteractions":[],"pendingCommands":[],"backgroundWorks":[],
 "subagents":{"revision":1,"childSessionIds":[...],"running":[],"endedTotal":3},
 "goal":null,"plan":{"items":[...],"updatedAt":...},"workspaceHookAdmission":null,
 "rows":{"window":[ …最多 60 行… ],"totalCount":893,"firstRowId":1}}
```

⚠️ `rows.window` **只是尾部窗口**（实测 60 条，而 `totalCount` 893）。更早历史必须另行翻页（§6.3）。

### 6.2 行（row）模型

公共字段：`rowId`、`turnId`、`entityId`、`productTurnId`、`visibility`、`createdAt`、`createdAtSeq`、`kind`。

**没有统一的 `role` 字段**——角色由 `kind` 表达：

| kind | 角色 | 关键字段 |
|---|---|---|
| `userInput` | 用户 | `text`、`origin`(realUser/backgroundResult/goalContinuation/mailbox/synthetic)、`attachments?` |
| `assistantText` | 助手正文 | `text`、`state`(complete/streaming/interrupted)、`assistantResponseId` |
| `reasoning` | 思考过程 | `text`、`state`、`durationMs?` |
| `toolCall` | 工具调用 | `toolCallId`、`toolName`、`status`(inputStreaming/pendingApproval/running/success/error/cancelled)、`inputText`、`input`(对象)、`output{text}`、`startedAt`、`endedAt` |
| `turnHeader` | 轮次头 | `actions{canFork,canEdit,canRetry,canRewindFiles,editDisposition}`、`origin`、`executionKind`、`activeMs`、`historyRoundCount` |
| `subagent` / `hookInvocation` / `timelineMarker` | 其他 | 同族 |

- 正文文本字段是 `text`（`toolCall` 用 `inputText`）；`input` 是解析后的对象，`inputText` 是它的 JSON 字符串形式（**两者都在**）。
- 时间戳：`createdAt`(ms) + `createdAtSeq`；turn 级另有 `startedAt`/`endedAt`/`activeMs`。
- 行索引就是 `rowId`（数字），不是数组下标。

### 6.3 向上翻页拉历史（100）

`conversationRowsRangeV4`，args 数组包一个对象：

```jsonc
[{ "workspacePath":"...","workspaceIdentity":"...?","sessionId":"sess_...",
   "beforeRowId": window[0].rowId,      // 从当前窗口最早一行往前
   "limit": 60 }]                        // 上限 200
```

应答：`{"rows":[...],"atSeq":...,"atLogEpoch":"...","hasMore":bool}`

- **必须校验 `atLogEpoch === snapshot.logEpoch`**，不等则整批丢弃（说明日志纪元变了，快照已失效）。
- `hasMore` 为 true 就继续用新窗口首行 `rowId` 再拉，直到 false。

## 7. 被本文纠正的错误结论

1. **通道名**：PROTOCOL.md §6.1 / FRAME-CODEC.md §4.2、§7 把会话方法写成在 `zcode-session` 上 → **错**，应是 `zcode-agent`。这是上次开发卡住的直接原因。
2. **subscribe 的帧类型**：FRAME-CODEC.md §4.2/§7 写成 `type=102 EventListen` → **错**，是 `100 Promise` → `201`。102 仅用于 `onDynamicConversationFrame`。
3. **方法名层级**：把 `v4/conversation/subscribe` 与 `subscribeConversationV4` 当成同一个东西 → **错**，是 host→CLI 与远程 RPC 两个不同层。
4. **PROTOCOL.md §5 的 rpc-frame 字段列举不全**：还强制要求 `messageSeq`、`fragmentIndex`、`fragmentCount`、`messageBytes`、`checksum{algorithm,value}`。

## 8. bridge 身份校验（本次踩到的坑）

`rpc-frame` / `rpc-frame-ack` 的身份校验是**三元组全等**：

```js
bridgeSessionId === 期望 && bridgeGeneration === 期望 && recoveryId === 期望
```

规则：**只要帧里带了任一身份键，三个键就必须全部相等**（含 `undefined` 的比较）。

- 实测后果：`rpc-frame-ack` 只带 `bridgeSessionId`、漏掉 `bridgeGeneration` →
  身份不匹配 → **ack 被静默丢弃** → 服务端认为客户端没收到 → 持续重放整个缓冲
  （观察到的现象是同一批 `200/201/202` 每隔几秒循环重现）。
- 修法：凡带 `bridgeSessionId` 的帧，一律同时带上 `bridgeGeneration`。
- `recoveryId` 确实可选，只要两端都缺省即相等。

## 9. 参考实现

`tools/probe.py` 的 `sub` 模式已完整实现 §4 序列（链路 + 协商 + 监听 + 订阅 + 收帧落盘）：

```bash
ZCODE_MID="<机器ID>" python tools/probe.py sub <会话ID前缀> [帧落盘路径]
# 帧结构分析
python tools/_dump_frame.py <帧落盘路径> [骨架输出路径]
```
