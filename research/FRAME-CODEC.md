# ZCode 远程控制：`rpc-frame` 内层 Payload 二进制编码规格（逆向分析报告）

> ⚠️ **2026-09-16 更正（真机实测，已跑通）**
> 本文 §4.2、§7 及多处把会话方法的 **通道名写成 `zcode-session`、帧类型写成 `102 EventListen`**，两者都是**错的**：
> - 会话方法在 **`zcode-agent`** 通道上（`zcode-session` 上是 `createZCodeSessionService`，不含 `*V4` 会话方法）；
> - `subscribeConversationV4` 是 **`100 Promise` → `201`**，不是 102；102 只用于 `onDynamicConversationFrame`。
> - 另需先 `helloConversationV4` → `initializeConversationV4(clientHello)` 完成握手，否则报 `fault.connection.handshakeRequired`。
>
> **以 `research/CONVERSATION-PROTOCOL.md` 为准。** 本文仅 **§1–§3（Vql 二进制编解码）** 仍然有效；
> 读那些字节布局示例时，请把 `zcode-session` 读作 `zcode-agent`、`type=102` 读作 `type=100`。


> 只读逆向分析产物。所有结论都标注了证据（文件 + 字节偏移 + 原文片段）与置信度。
> 分析对象：
> - 手机端（官方 Web 端 bundle）：`F:/AI/Zcode/zcode-remote-app/research/index-nOVzQNKW.js`（4.8 MB，未混淆符号名，仅变量名被压缩）
> - PC 端（Electron asar 解包）：
>   - `F:/AI/Zcode/zcode-remote-app/research/asar/out/main/chunk-X2DDW7XG.js`（**RPC 编解码器本体，带可读函数名，最有价值**）
>   - `F:/AI/Zcode/zcode-remote-app/research/asar/out/main/chunk-WR3FEWGO.js`（`rpc-frame` 信封/schema/CRC32/base64/分片编码器/组装器）
>   - `F:/AI/Zcode/zcode-remote-app/research/asar/out/main/chunk-NHZHAM44.js`（AcknowledgedRelayProtocol：seq/ack/重传）
>   - `F:/AI/Zcode/zcode-remote-app/research/asar/out/main/index.js`（开桥流程、relay↔host 接线）
>   - `F:/AI/Zcode/zcode-remote-app/research/asar/out/host/chunk-RWMCBKS2.js`（中继线格式的 zod 权威校验表）
>   - `F:/AI/Zcode/zcode-remote-app/research/asar/out/host/chunk-BG4MS6RN.js`（v4 会话协议方法名、logical frame schema）

---

## 1. 结论摘要（TL;DR）

1. **`dataBase64` 不是 protobuf，也不是 JSON 文本。** 它是 **ZCode 自有 RPC 序列化器（`serialize`/`deserialize`）输出的二进制消息**，按 1 MiB 外层信封预算切成 1..64 片，**每片单独 base64**。
   - 判定依据（强）：存在完整的 tag 化二进制编解码器 + ULEB128 varint 读写，且**没有**任何 protobuf 痕迹（无 field number、无 wire type、无 protobuf 依赖）；同时存在显式的「长整数字节长度估算函数」`Qc` / `vqlByteLength` 与 `base64Length = 4*ceil(n/3)`。
2. 内层字节格式 = **`tag(1 字节)` + `varint(ULEB128) 长度` + payload**，tag 取值 `Undefined=0 / String=1 / Buffer=2 / VSBuffer=3 / Array=4 / Object(JSON)=5 / Int=6`。
3. RPC 消息 = 两个序列化值顺序拼接：
   - `serialize([type, id, channelName, method]) ++ serialize(args)`（客户端 → 服务端）
   - 服务端出方向（应答/事件）为 `serialize([type, id]) ++ serialize(data)`（置信中，见 §8-Q4）
4. **`seq` / `messageSeq` 均从 1 开始**、单向单调；`seq` 是「物理帧序号」（每片 +1，全局连续**不允许跳号**），`messageSeq` 是「逻辑消息序号」（同一消息的所有分片共用，每消息 +1）。
5. `dataBase64` 与 `logical frame` 里的 `dataBase64` 是**两个不同层的字段**：前者是传输层 RPC 报文的字节分片，后者是应用层 logical frame 的 **JSON 文本**字节分片（组装后要 `TextDecoder` + `JSON.parse`）。
6. 桥的身份三元组 `bridgeSessionId / bridgeGeneration / recoveryId` 由**手机端生成**，经 `workspace-bridge-open` 上行，由 PC 端在 `workspace-bridge-ready` 里回显；PC 端在发出 ready 之前不会发送任何 rpc-frame。

---

## 2. 分层模型

```
┌─ WebSocket 中继（JSON 文本帧） ─────────────────────────────────────────────┐
│ {"type":"data","payload":<P>,"client_ts":<ms>}                              │
│  P = rpc-frame 信封（JSON） | rpc-frame-ack（JSON） | workspace-* 等控制帧   │
└─────────────────────────────────────────────────────────────────────────────┘
                     │ P.dataBase64 → base64 解码
                     ▼
┌─ 传输层消息（二进制，本报告的主角） ────────────────────────────────────────┐
│ ZCode RPC 序列化报文 = serialize([type,id,channelName,method]) ++ serialize(args)
│ （当 host 是「Electron MessagePort/Socket host」时；见 §3.7 的 NDJSON 变体） │
└─────────────────────────────────────────────────────────────────────────────┘
                     │ ChannelClient.onBuffer()
                     ▼
┌─ RPC 语义层 ───────────────────────────────────────────────────────────────┐
│ 100 Promise / 101 PromiseCancel / 102 EventListen / 103 EventDispose        │
│ 200 Initialize / 201 PromiseSuccess / 202 PromiseError / 203 PromiseErrorObj│
│ 204 EventFire                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
                     │ EventFire.data（订阅流）
                     ▼
┌─ 应用层 logical frame（JSON := wireVersion:3,kind:complete|fragment,...）───┐
│ fragment 变体的 dataBase64 = **JSON 文本**的字节分片（crc32 + logicalBytes） │
│ 组装：concat → crc32 → UTF-8 decode → JSON.parse → frameSchema 校验 → .frame │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. 字节级格式定义

### 3.1 中继外层（已确认，置信高）

PC 端权威 zod 表（`asar/out/host/chunk-RWMCBKS2.js` 约 472800 处，函数名 `CF`/`aF` 附近）：

```js
aF = t.object({type:t.literal("data"),payload:JR,client_ts:nn.optional(),server_ts:nn.optional()}).strict()
// JR = t.union([xp, kp])  = rpc-frame | rpc-frame-ack
```

手机端构造处（`index-nOVzQNKW.js` 偏移 4698362 附近）：

```js
var d2t=class{cache=new WeakMap;prepare(e,t=Date.now()){...
  let r={type:`data`,payload:e,client_ts:t},i=JSON.stringify(r),
      a=Object.freeze({message:r,json:i,bytes:new TextEncoder().encode(i).byteLength});
  return this.cache.set(e,a),a}
  isOversize(e){return e.bytes>Eo.maxPhysicalFrameBytes}}
```

→ 手机端上行只带 `client_ts`；`server_ts` 由服务端/中继补。`1 MiB` 上限作用于**整条 JSON 的 UTF-8 字节数**。

### 3.2 `rpc-frame` 信封字段表（置信高）

PC 端权威 schema（两处内容一致：`asar/out/main/chunk-WR3FEWGO.js` 偏移 471900 附近 `qo`；`asar/out/host/chunk-RWMCBKS2.js` 偏移 472400 附近 `xp`）：

```js
var le={maxPhysicalFrameBytes:jo.maxFrameBytes, /*1 MiB*/
        maxMessageBytes:16*1024*1024, maxFragments:64,
        assemblyTimeoutMs:3e4, transportIdMaxChars:jo.transportEnvelopeIdMaxChars /*256*/},
Nu=e.number().int().positive().max(Number.MAX_SAFE_INTEGER),
Fo=e.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER),
Qt=e.string().min(1).max(le.transportIdMaxChars).regex(/^[A-Za-z0-9._~-]+$/u),
qo=e.object({zcode_type:e.literal("rpc-frame"),
  bridgeSessionId:Qt, bridgeGeneration:Fo.optional(), recoveryId:Qt.optional(),
  seq:Nu, messageSeq:Nu,
  fragmentIndex:e.number().int().nonnegative().max(le.maxFragments-1),
  fragmentCount:e.number().int().positive().max(le.maxFragments),
  messageBytes:e.number().int().positive().max(le.maxMessageBytes),
  checksum:NI /*{algorithm:"crc32",value:/^[0-9a-f]{8}$/}*/,
  dataBase64:e.string().min(4).max(le.maxPhysicalFrameBytes).refine(Nf /*isCanonicalBase64*/)
}).strict()
// + superRefine: fragmentIndex < fragmentCount; fragmentCount <= messageBytes
```

| 字段 | 类型 | 必需 | 说明 |
|---|---|---|---|
| `zcode_type` | 字面量 `"rpc-frame"` | ✅ | |
| `bridgeSessionId` | string, 1..256, `^[A-Za-z0-9._~-]+$` | ✅ | 桥身份 |
| `bridgeGeneration` | int ≥0 ≤2^53-1 | ❌ | 桥代次 |
| `recoveryId` | string, 1..256, 同上正则 | ❌ | 仅恢复连接时出现 |
| `seq` | int >0 ≤2^53-1 | ✅ | 物理帧序号，见 §6 |
| `messageSeq` | int >0 ≤2^53-1 | ✅ | 逻辑消息序号，见 §6 |
| `fragmentIndex` | int 0..63 | ✅ | 本消息内第几片，从 0 起 |
| `fragmentCount` | int 1..64 | ✅ | 本消息总分片数（单片消息=1） |
| `messageBytes` | int >0 ≤16 MiB | ✅ | **整条消息**（拼接后）的字节数 |
| `checksum.algorithm` | `"crc32"` | ✅ | |
| `checksum.value` | `^[0-9a-f]{8}$` | ✅ | **整条消息**字节的 CRC-32，小写 8 位 hex |
| `dataBase64` | 规范 base64 | ✅ | **本片**解码字节；长度 ≥4（即 ≥1 字节） |

`.strict()` → 出现任何未列出的键即判非法。JSON 键顺序（编码侧 `Lf` 生成顺序）：

```js
function Lf(t){return{zcode_type:"rpc-frame",...t.identity,seq:t.seq,messageSeq:t.messageSeq,
  fragmentIndex:t.fragmentIndex,fragmentCount:t.fragmentCount,messageBytes:t.messageBytes,
  checksum:t.checksum,dataBase64:t.dataBase64}}
```
（`identity` = `{bridgeSessionId, bridgeGeneration?, recoveryId?}`，由 `jf` 经 zod `.strict()` 规范化，顺序即 schema 顺序。）

### 3.3 内层 = ZCode RPC 序列化二进制（置信高）

权威实现：`asar/out/main/chunk-X2DDW7XG.js`（**带原始函数名**）。手机端同一实现的压缩版在 `index-nOVzQNKW.js` 偏移 261399–264500（符号 `ju`/`Nu`/`Pu`/`Fu`/`Iu`/`Lu`/`Bu`/`Vu`）。

**varint（ULEB128，little-endian 7-bit 组，高位=续接）**

```js
// chunk-X2DDW7XG.js
function R(t){let e=0;for(let n=0;;n+=7){let s=t.read(1);if(e|=(s.buffer[0]&127)<<n,!(s.buffer[0]&128))return e}}  // readIntVQL
var Z=C(0);
function E(t,e){if(e===0){t.write(Z);return}                 // writeInt32VQL
  let n=0;for(let r=e;r!==0;r=r>>>7)n++;
  let s=h.alloc(n);
  for(let r=0;e!==0;r++)s.buffer[r]=e&127,e=e>>>7,e>0&&(s.buffer[r]|=128);
  t.write(s)}
```
- `0` 编码为单字节 `0x00`。
- 因为用 `<<`/`>>>`（32 位 JS 位运算）实现，**varint 承载的是 uint32**（函数名即 `writeInt32VQL`）。`rpc-frame` 信封里那些最大到 2^53-1 的 `seq` 等字段是 **JSON 里的数字，不走 varint**。
- 手机端同款函数即题目里给的 `Qc`（仅用于长度估算）与写入器 `Pu`：

```js
// index-nOVzQNKW.js @117346
function Qc(e){let t=1;for(let n=e>>>7;n>0;n>>>=7)t+=1;return t}
```

**tag 表**

```js
var w={Undefined:C(0),String:C(1),Buffer:C(2),VSBuffer:C(3),Array:C(4),Object:C(5),Int:C(6)},
    z="__zcode_rpc_nested_uint8array_v1", L="base64";
```

**编码规则（`serialize`）**

```js
function v(t,e){
  if(typeof e>"u") t.write(w.Undefined);                                  // 0x00，无 payload
  else if(typeof e=="string"){let n=h.fromString(e);t.write(w.String);E(t,n.byteLength);t.write(n);}   // 0x01 varint(len) utf8
  else if(e instanceof h) {t.write(w.VSBuffer);E(t,e.byteLength);t.write(e);}                            // 0x03 varint(len) raw
  else if(e instanceof Uint8Array){let n=h.wrap(e);t.write(w.Buffer);E(t,n.byteLength);t.write(n);}      // 0x02 varint(len) raw
  else if(Array.isArray(e)){t.write(w.Array);E(t,e.length);for(let n of e)v(t,n);}                       // 0x04 varint(count) elems
  else if(typeof e=="number"&&(e|0)===e){t.write(w.Int);E(t,e);}                                          // 0x06 varint(int32)
  else {let n=h.fromString(JSON.stringify(e,X));t.write(w.Object);E(t,n.byteLength);t.write(n);}          // 0x05 varint(len) json-utf8
}
```

**解码规则（`deserialize`）**

```js
function x(t){switch(t.read(1).readUInt8(0)){
  case 0:return;                                   // undefined
  case 1:return t.read(R(t)).toString();           // string
  case 2:return t.read(R(t)).buffer;               // Uint8Array
  case 3:return t.read(R(t));                      // VSBuffer
  case 4:{let n=R(t),s=[];for(let r=0;r<n;r++)s.push(x(t));return s}
  case 5:return JSON.parse(t.read(R(t)).toString(),ee);   // JSON
  case 6:return R(t)}}                             // int
```

- **JSON 里嵌套 `Uint8Array` 的专门约定**：`encodeRpcJsonValue` 把 `Uint8Array` 变成 `{"__zcode_rpc_nested_uint8array_v1":true,"base64":"<std-b64>"}`，解码侧 `decodeRpcJsonValue` 还原为 `Uint8Array`。所以 `Object` tag 的 JSON 里可能出现 base64 包裹的二进制。
- `null` / `true` / `false` / 浮点数 / 大整数都不走 `Int`，而是走 `Object` tag 的 JSON 文本。

### 3.4 RPC 消息类型枚举（置信高）

```js
// index-nOVzQNKW.js @266400 附近；同 chunk-X2DDW7XG.js
Uu={Promise:100,PromiseCancel:101,EventListen:102,EventDispose:103}          // 客户端 →
Wu={Initialize:200,PromiseSuccess:201,PromiseError:202,PromiseErrorObj:203,EventFire:204}  // 服务端 →
```

`ChannelClient`（`chunk-X2DDW7XG.js` 类 `D`；手机端 `$pe`）收发核心：

```js
sendRequest(e,n,s,r,i){let a=new b; v(a,[e,n,s,r]); v(a,i); this.protocol.send(a.buffer)}
sendCancelOrDispose(e,n){let s=new b; v(s,[e,n]); v(s,void 0); this.protocol.send(s.buffer)}
onBuffer(e){let n=new T(e),s=x(n),r=x(n),i=s[0];
  switch(i){case 200:this.onResponse({type:200});return;
            case 201:case 202:case 204:case 203:this.onResponse({type:i,id:s[1],data:r});return}}
```

### 3.5 消息布局

**A. 客户端 → 服务端（高置信）**

```
serialize([ type, id, channelName, method ])  ++  serialize(args)
```

- `type` ∈ {100,101,102,103}（`requestPromise` 用 100，`requestEvent` 用 102，dispose 用 101/103）
- `id`（`requestId`）= 该 ChannelClient 上自增的 `lastRequestId`（**从 0 开始**，`let i=this.lastRequestId++`）
- `channelName` = `{channelName}` 描述符的取值，取自固定枚举（`asar/out/host/chunk-RWMCBKS2.js` @499200 附近的 `h0`）：

```
File:"file", MediaPreview:"media-preview", System:"system", Terminal:"terminal", Git:"git",
GitCheckpoint:"git-checkpoint", Setting:"setting", Credential:"credential",
CuaPermission:"cua-permission", CuaPipSession:"cua-pip-session", Broadcast:"broadcast",
ZCodeTask:"zcode-task", WindowController:"window-controller", ZCodeAgent:"zcode-agent",
ZCodeSession:"zcode-session", FileWatcher:"file-watcher", OAuth:"oauth",
ModelProvider:"model-provider", UsageStats:"usage-stats", ... 
```

- `method` = 代理属性名（`toService` 的 Proxy 用属性名当方法名）：

```js
function e(s,r){return new Proxy({},{get(i,a,l){ ...
  return F(a)?c=>s.listen(a,c) : N(a)?s.listen(a)
       : async(...c)=>{let d=r?.context===void 0?c:[r.context,...c];return s.call(a,d)}}})}
```
- `args` 是 **调用参数数组**（因此 `serialize(args)` 以 `0x04 varint(count)` 开头）。

**B. 服务端 → 客户端（置信中）**

出方向只承载「类型 + 路由键（id）+ 数据」，channelName/method 不再重复。证据是长度估算器对每帧的建模（`1+he(2)+1+he(204)+i+1+he(n)+n`：Array tag + varint(2) + Int type=204 + 一个 ≤16 字节字符串字段 + JSON 载荷），与 `onBuffer` 只取 `s[0]`、`s[1]` 一致：

```
serialize([ type, id ])  ++  serialize(data)
```
（`type`=201/202/203/204；`data` 为应答值或事件载荷。注意二进制的 `id` 是 uint32，而估算器里的「16 字节字符串」只是对 id 长度的保守上界，不是真实布局 —— 见 §9。）

### 3.6 分片与 base64 预算算法（置信高，逐字对应代码）

```js
// chunk-WR3FEWGO.js
function Gr(t){let n=4294967295;for(let r of t){n^=r;
  for(let a=0;a<8;a+=1)n=n>>>1^(n&1?3988292384:0)}
  return((n^4294967295)>>>0).toString(16).padStart(8,"0")}   // crc32WireBytes：标准 CRC-32(反射, poly 0xEDB88320)
var et="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
function $u(t){...}   // encodeWireBytesBase64：标准字母表 + '=' 填充
function Uu(t){...}   // decodeWireBase64
function Nf(t){if(t.length<4||t.length>le.maxPhysicalFrameBytes)return!1;
  let n=Uu(t);return n!==null&&n.byteLength>0&&$u(n)===t}    // isCanonicalBase64：必须能原样往返

function LI(t){return 4*Math.ceil(t/3)}                      // base64Length
function BI(t){ // findDecodedBudget：二分出「单片最大解码字节数」
  let n=Lf({identity:t.identity,seq:t.endSeq,messageSeq:t.messageSeq,
            fragmentIndex:t.fragmentCount-1,fragmentCount:t.fragmentCount,
            messageBytes:t.messageBytes,checksum:t.checksum,dataBase64:""}),
      r=ju(n), a=0,o=t.messageBytes;
  for(;a<o;){let i=Math.ceil((a+o)/2); r+LI(i)<=t.maxPhysicalFrameBytes?a=i:o=i-1}
  return a}
function ju(t,n){return ZI(JSON.stringify(jI(t,n)))}          // 外层信封的 UTF-8 字节数
function jI(t,n={}){...return{type:"data",payload:t,...}}     // client_ts/server_ts 取 MAX_SAFE_INTEGER 做最坏估算

function xB(t,n){ // encodeWebRemoteControlRpcTransportMessage
  let r=jf(n), ...;
  if(t.byteLength===0)throw ...("remote.rpcFrame.emptyMessage");
  if(t.byteLength>o)throw ...("remote.rpcFrame.messageTooLarge");
  let s=Object.freeze({algorithm:"crc32",value:Gr(t)});       // ← 对整条消息算 CRC32
  let l=1,p=0;
  for(;;){ let h=n.firstPhysicalSeq+l-1; ...;
    p=BI({identity:r,endSeq:h,messageSeq:n.messageSeq,fragmentCount:l,
          messageBytes:t.byteLength,checksum:s,maxPhysicalFrameBytes:a});
    if(p<1)throw ...("remote.rpcFrame.envelopeTooLarge");
    let b=Math.ceil(t.byteLength/p);
    if(b>i)throw ...("remote.rpcFrame.fragmentLimitExceeded");
    if(b<=l)break; l=b }
  let u=[];
  for(let h=0;h<l;h+=1){ let b=h*p, v=Math.min(t.byteLength,b+p),
      _=Lf({identity:r,seq:n.firstPhysicalSeq+h,messageSeq:n.messageSeq,
            fragmentIndex:h,fragmentCount:l,messageBytes:t.byteLength,checksum:s,
            dataBase64:$u(t.subarray(b,v))});                 // ← 每片独立 base64
    ... u.push(Object.freeze(_))}
  return Object.freeze(u)}
```

要点：
- 分片是**等长**的（每片 `p` 字节，最后一片可能更短/等长），`p` 取「使最坏 envelope（`client_ts`/`server_ts` 取 2^53-1、`fragmentIndex` 取 `fragmentCount-1`）仍不超过 1 MiB」的二分上界。
- `fragmentCount` 会被**回代重算**（因为 `fragmentCount` 的十进制位数会影响 envelope 长度），直到 `ceil(messageBytes/p) <= l`。
- `checksum` 只算一次，覆盖**整条消息**（不是分片）。
- `dataBase64` 长度上限按字符数检查（≤1 MiB 字符），但二进制上限通过 `Uu` 解码后 >0 校验。

### 3.7 CLI 变体：`dataBase64` 也可能是 NDJSON 行（置信中）

同一份代码里同时估算三种承载（`index-nOVzQNKW.js` @117586 附近 `mue`，`asar/out/host/chunk-BG4MS6RN.js` @19700 附近 `ze` 内容完全一致）：

```js
function ze(r){let t=_e({method:"v4/conversation/frame",params:r})+1,   // ← cliNdjsonBytes：JSON + "\n"
  n=_e(r), o=String(Number.MAX_SAFE_INTEGER).length=16,
  i=1+he(o)+o, f=1+he(2)+1+he(Bo)+i+1+he(n)+n,                        // Bo=204(EventFire)
  p=f+Oo,                                                             // Oo=13(SocketProtocol 头)
  y=4*Math.ceil(f/3),
  l="x".repeat(w.transportEnvelopeIdMaxChars),
  S=_e({type:"data",payload:{zcode_type:"rpc-frame",bridgeSessionId:l,
        bridgeGeneration:Number.MAX_SAFE_INTEGER,recoveryId:l,
        seq:Number.MAX_SAFE_INTEGER,dataBase64:""},client_ts:...,server_ts:...})+y;
  return{cliNdjsonBytes:t,channelSocketBytes:p,mobileRelayBytes:S,maxBytes:Math.max(t,p,S)}}
```

- `p = f + 13` 里的 **13** = `SocketProtocol` 的定长头：`type(1) + id(u32BE) + ack(u32BE) + length(u32BE) = 13`（`chunk-X2DDW7XG.js`：`var S=13; get byteLength(){return S+this.data.byteLength}`）。
- 因此**内层字节的语义取决于 PC 端 host 的接入方式**：
  - Electron 工作区 host（MessagePort）→ 内层 = §3.3 的二进制 RPC 报文（**这是本项目的实际路径**，见 §5 的接线证据，置信高）；
  - CLI 型 host → 内层 = `{"method":"v4/...","params":{...}}\n` 这样的 NDJSON 文本行（置信中，仅由估算器命名与 `cliNdjsonBytes` 推断）。
- CLI 侧方法名表（`asar/out/main/chunk-WR3FEWGO.js` @467800 附近 `Fr`，含题目里提到的 `session/*`）：

```
computerUseOperationEvent:"computer-use/operation-event",
sessionCreate:"session/create", sessionResume:"session/resume", sessionList:"session/list",
sessionSubagents:"session/subagents", sessionRequestRuntimePreferences:"session/requestRuntimePreferences",
sessionRead:"session/read", sessionMessages:"session/messages", sessionEvents:"session/events",
sessionSubscribe:"session/subscribe", sessionSend:"session/send", sessionStop:"session/stop",
sessionCancelBackgroundTask:"session/cancelBackgroundTask", sessionFork:"session/fork",
sessionCompact:"session/compact", sessionGoal:"session/goal", sessionClose:"session/close",
sessionSetModel:"session/setModel", sessionSetThoughtLevel:"session/setThoughtLevel",
sessionSetMode:"session/setMode", workspaceReadState:"workspace/readState", ...
```
v4 会话协议方法名表（`asar/out/host/chunk-BG4MS6RN.js` @11470 附近 `si` / @19805 附近 `di`）：

```
conversationFlow / controllerSubscribe:"v4/controller/subscribe" / controllerResync / controllerUnsubscribe,
conversationSubscribe:"v4/conversation/subscribe", conversationResync:"v4/conversation/resync",
conversationUnsubscribe:"v4/conversation/unsubscribe", conversationRowsRange:"v4/conversation/rowsRange",
conversationPlans:"v4/conversation/plans", conversationFileChanges:"v4/conversation/fileChanges",
conversationFileRewindPreview:"v4/conversation/fileRewindPreview",
attachmentBegin/Chunk/Commit/Abort/Read/PreviewSource:"v4/attachment/*",
commandsQuery:"v4/commands/query", command:"v4/command",
conversationFrame:"v4/conversation/frame", conversationTelemetryFact:"v4/telemetry/event",
cuaPermissionObservation:"v4/cua/..." 
```

### 3.8 `rpc-frame-ack`（置信高）

```js
kp=e.object({zcode_type:e.literal("rpc-frame-ack"),
  bridgeSessionId:so,bridgeGeneration:nn.optional(),recoveryId:so.optional(),
  ackMessageSeq:_p /*positive int ≤2^53-1*/}).strict()
```

### 3.9 常量与上限总表

| 常量 | 值 | 出处 |
|---|---|---|
| 外层物理帧上限 `maxPhysicalFrameBytes` | 1 MiB | `jo.maxFrameBytes:1024*1024` |
| 单条消息上限 `maxMessageBytes` | 16 MiB | `le.maxMessageBytes` |
| 最大分片数 `maxFragments` | 64 | `le.maxFragments` |
| 组装超时 `assemblyTimeoutMs` | 30000 ms | `le.assemblyTimeoutMs` |
| 身份串上限 | 256 字符 | `transportEnvelopeIdMaxChars` |
| logical frame 组装 | maxBytes 16 MiB / maxFragments 1024 / maxConcurrent 32 / maxStaged 32 MiB / timeout 30 s | `jo.logicalFrameAssembly*` |
| 重放缓冲上限 | 8 MiB；饱和水位 1 MiB / 回落 256 KiB；宽限 45 s | `T9` = `{saturationHighWaterMarkBytes:1MiB,saturationLowWaterMarkBytes:256KiB,replayBufferMaxBytes:8MiB,replayBufferGraceMs:45e3}` |
| SocketProtocol 头 | 13 字节（type u8 / id u32BE / ack u32BE / len u32BE） | `chunk-X2DDW7XG.js` |

---

## 4. 请求构造示例

### 4.0 伪代码框架（手机端语义）

```js
// ---- 1. 开桥（先发 open，等 ready）----
const bridgeSessionId = `bridge-${crypto.randomUUID()}`;   // X9("bridge")
let bridgeGeneration = ++gen;                              // 每次 open +1
let recoveryId;                                            // 仅在连接恢复时 = `recovery-${uuid}`
ws.send(JSON.stringify({type:"data", client_ts:Date.now(), payload:{
  zcode_type:"workspace-bridge-open", requestId:`workspace-bridge-${uuid}`,
  bridgeSessionId, bridgeGeneration, ...(recoveryId?{recoveryId}:{}), workspaceKey, ...(taskId?{taskId}:{})}}));
// 等 payload.zcode_type==="workspace-bridge-ready" && payload.bridgeSessionId===bridgeSessionId
const identity = {bridgeSessionId, bridgeGeneration, recoveryId};   // 取自 ready.bridge

// ---- 2. 建立 ChannelClient（RPC 层）----
// relayProtocol = AcknowledgedRelayProtocol(identity, sendFrame, measureFrameBytes)
// channelClient = new ChannelClient(relayProtocol.protocol)
// 之后 service.method(args) 会自动: serialize([type,id,channel,method]) ++ serialize(args)
//   → reserveMessage → xB 切片 → sendFrame → ws.send({type:"data",payload:<rpc-frame>,client_ts})
```

关键点：**`seq`/`messageSeq` 的取值不由业务层决定**，由 AcknowledgedRelayProtocol 内部状态决定：

```js
// index-nOVzQNKW.js @4691380 附近 / chunk-NHZHAM44.js 类 Ie
nextPhysicalSeq=1; nextMessageSeq=1; highestFullySentMessageSeq=0; lastAckedMessageSeq=0;
...
reserveMessage(e){ let t=Koe(e,{...this.identity,
    firstPhysicalSeq:this.nextPhysicalSeq, messageSeq:this.nextMessageSeq});   // ← 切片
  ... let r=this.nextMessageSeq, i=t.at(-1);
  this.outboundBatches.append({messageSeq:r,frames:t,...});
  this.nextMessageSeq+=1, this.nextPhysicalSeq=i.seq+1, ...}
```

### 4.1 示例 A：开桥（`workspace-bridge-open` / `ready` / `error`）

手机端（`index-nOVzQNKW.js` @4769400 附近，函数 `P`）：

```js
let n=X9(`bridge`), r=++u, i=await k(()=>O({zcode_type:`workspace-bridge-open`,
    requestId:X9(`workspace-bridge`), bridgeSessionId:n, bridgeGeneration:r,
    ...l?{recoveryId:l}:{}, workspaceKey:e, ...t?.taskId?{taskId:t.taskId}:{}},
    e=>e.zcode_type===`workspace-bridge-ready`&&e.bridgeSessionId===n));
l=void 0;                                    // recoveryId 用后即清
let a=u2t({bridgeSessionId:i.bridge.bridgeSessionId, bridgeGeneration:i.bridge.bridgeGeneration,
           recoveryId:i.bridge.recoveryId, measureFrameBytes:e=>E.measurePayloadBytes(e),
           sendFrame:e=>{let t=E.sendPayloadResult(e);
             if(t.kind===`oversize`)throw Error(`remote.rpcFrame.envelopeTooLarge`);
             return t.kind===`sent`}});
```

PC 端应答（`asar/out/main/index.js` @1168900 附近 `respondToWorkspaceBridgeOpen`）：

```js
async function $n(v,b){try{let T=await ut(v,b); xe(v,{zcode_type:"workspace-bridge-ready",
  requestId:b.requestId, bridgeSessionId:b.bridgeSessionId,
  ...b.bridgeGeneration!==void 0?{bridgeGeneration:b.bridgeGeneration}:{},
  ...b.recoveryId?{recoveryId:b.recoveryId}:{}, bridge:T});
  let J=v.currentBridge; J?.bridgeSessionId===b.bridgeSessionId&&J.bridgeGeneration===b.bridgeGeneration
    &&(J.readyAnnounced=!0, J.relayProtocol?.flushPendingFrames())}
 catch(T){xe(v,{zcode_type:"workspace-bridge-error",requestId:b.requestId,
   bridgeSessionId:b.bridgeSessionId, ..., reason:phe(T), error:J})}}
```

zod 表（`asar/out/host/chunk-RWMCBKS2.js` @473300/@473520）：

```js
{workspace-bridge-open, requestId, bridgeSessionId, bridgeGeneration?, recoveryId?, workspaceKey, taskId?}
{workspace-bridge-ready, requestId, bridgeSessionId, bridgeGeneration?, recoveryId?, bridge:QR}
{workspace-bridge-error, requestId, bridgeSessionId?, bridgeGeneration?, recoveryId?, reason, error}
QR = discriminatedUnion("kind",[
  {bridgeSessionId, bridgeGeneration?, recoveryId?, kind:"local",  workspaceKey, workspacePath, initialTaskId?},
  {bridgeSessionId, bridgeGeneration?, recoveryId?, kind:"remote", workspaceKey, workspacePath,
   workspaceIdentity, remoteSessionId, initialTaskId?}])
reason ∈ [session-not-found, session-expired, session-conflict, workspace-closed, desktop-disconnected,
          invalid-mobile-connection, desktop-bootstrap-timeout, connection-recovery-timeout,
          relay-unavailable, unsupported-action, unexpected-error]
```

`sendFrame` 的 ready 门禁（`asar/out/main/index.js` @1167839）：

```js
sendFrame:s(It=>{if(!se.readyAnnounced||se.degraded)return!1;
  let Wl=me(v,It); if(Wl.kind==="oversize")throw new Error("remote.rpcFrame.envelopeTooLarge");
  return Wl.kind==="sent"},"sendFrame")
```

### 4.2 示例 B：拉取某会话的消息列表

手机端真实路径是「订阅 + 流式帧」（`index-nOVzQNKW.js` @545900 附近）：

```js
// service: zcodeAgentService  (channel = "zcode-agent")   // ← 原写 zcode-session，已更正
subscribe(t){let i=Zc(t.topic);                    // "conversation/<sessionId>" → sessionId
  ...
  let c=await e.subscribeConversationV4({...n, sessionId:i,
     ...t.base?{base:t.base}:{}, ...t.visibility?{visibility:t.visibility}:{}});
  l.bind(o, c.ack.subscriptionId);                 // 把 subscriptionId 绑定到 topic
  u.set(c.ack.subscriptionId, t.topic); ...}
```

对应线格式（逐层）：

```
① 业务调用
   zcodeSessionService.subscribeConversationV4({workspacePath, workspaceIdentity?, sessionId, base?, visibility?})
② ChannelClient → RPC 报文（type=100 Promise, id=<自增>）   // ← 原写 102，已更正
   serialize([100, id, "zcode-agent", "subscribeConversationV4"]) ++ serialize([{...params}])
③ 切片（小报文 = 1 片）
   {"zcode_type":"rpc-frame","bridgeSessionId":"bridge-<uuid>","bridgeGeneration":1,
    "seq":<nextPhysicalSeq>,"messageSeq":<nextMessageSeq>,
    "fragmentIndex":0,"fragmentCount":1,"messageBytes":<报文长度>,
    "checksum":{"algorithm":"crc32","value":"<crc32(报文)>"},"dataBase64":"<base64(报文)>"}
④ 中继外层
   {"type":"data","payload":<③>,"client_ts":<ms>}
```

若需要**一次性**取历史行列表，另有 `conversationRowsRangeV4({sessionId, beforeRowId?, limit})`、`conversationPlansV4`、`conversationFileChangesV4`（同上通道 `zcode-agent`）；CLI 侧对应 `session/messages` / `session/read`。

响应：`201 PromiseSuccess`，`data` = `{ack:{subscriptionId, ...}}`（`subscribeConversationV4` 的返回见 host 侧 `...Rr.ack.subscriptionId` 与手机端 `c.ack.subscriptionId`）。

### 4.3 示例 C：发送一条消息

```js
// 手机端 (@549208)
async sendCommand(t){await r(); return e.sendConversationCommandV4({...n, envelope:t})}
async queryCommands(t){await r(); return e.queryConversationCommandsV4({...n, commands:t.commands})}
```

PC/host 侧对 envelope 的解析（`asar/out/host/index.js` @386200 附近）：

```js
async sendConversationCommandV4(m){let M=await E(m),
  Z=ei(m)?.clientMode??m.clientMode??"desktop-continuous";
  m.envelope.type==="createSession"&&await In({client:M,reason:"v4_command_create_session",workspace:m});
  let A=await Yo(m),L,K;
  if(A.type==="sendText"&&A.sessionId){
    let Xe=A.sessionId, bt=Iv.sendText.parse(A.payload), ...}}
```

→ envelope 形状：`{type:"createSession"|"sendText"|..., sessionId, payload:{...}}`（置信中，host 侧片段）。线格式与 §4.2 同构，只是 `method="sendConversationCommandV4"`、`args=[{...base, envelope}]`，响应是 `201` 带命令结果。

### 4.4 逐字节手算示例（校验用）

设 `channelName="zcode-agent"`(12B)、`method="subscribeConversationV4"`(20B)、`type=100`、`id=0`、`args=[{"workspacePath":"/w","sessionId":"abc"}]`：

```
04                              Array tag
04                              varint 元素个数 = 4
06 64                           Int tag + varint(100)
06 00                           Int tag + varint(0)
01 0C "zcode-agent"             String tag + varint(12) + 12B
01 14 "subscribeConversationV4" String tag + varint(20) + 20B
04 01                           args: Array tag + 元素个数 1
05 28 {"workspacePath":"/w","sessionId":"abc"}   Object tag + varint(40) + 40B JSON
```
合计 `1+1+2+2+14+22+2+2+40 = 86` 字节 → `messageBytes=86`、`checksum.value=crc32(这 87B)`、
`dataBase64` 长度 `4*ceil(87/3)=116` 字符、`fragmentIndex=0/fragmentCount=1/seq=messageSeq=<下一个序号>`。

> 注意：`args` 的 JSON 由 `JSON.stringify(value, replacer)` 产出，键序 = 对象字面量顺序；若参数里有 `Uint8Array`，会被替换成 `{"__zcode_rpc_nested_uint8array_v1":true,"base64":"..."}`。

---

## 5. 响应解析步骤

### 5.1 PC 端接线证据（证明"内层就是 RPC 报文"的关键）

`asar/out/main/index.js` @1167600 附近：

```js
let ct=new _w(fhe(Ue.port)),                    // host 协议（Electron MessagePort，_w = MessagePortProtocol）
    Mt=_N({bridgeSessionId:se.bridgeSessionId, bridgeGeneration:se.bridgeGeneration,
           recoveryId:se.recoveryId,
           measureFrameBytes:It=>v.transport.measurePayloadBytes?.(It)??N$(It),
           sendFrame:It=>{...}}),               // _N = createAcknowledgedWebRemoteControlRelayProtocol
    Za=ct.onMessage(It=>{Mt.protocol.send(It)}),// host → relay：RPC 报文原样进切片器
    tt=Mt.protocol.onMessage(It=>{ct.send(It)}); // relay → host：组装后的字节原样回灌 host 协议
```

`fhe` 把 Electron `MessagePort` 包成 `{addEventListener/postMessage/start/close}`，对应 `chunk-X2DDW7XG.js` 的 `MessagePortProtocol`：

```js
class V{constructor(e){this.port=e; this.handler=n=>{ if(re(n.data)){this._onFlowState.fire(n.data.state);return}
    n.data instanceof Uint8Array&&this._onMessage.fire(h.wrap(n.data))} ...}
  send(e){this.port.postMessage(e.buffer)}}
```

→ **中继上 `rpc-frame` 的 `dataBase64` 解码后就是 RPC 报文本身**（不带 13 字节头）；手机端同理，直接把 relayProtocol 塞进 `ChannelClient`：

```js
// index-nOVzQNKW.js @4687077
function e2t(e){return new Z0t(new $pe(e))}     // $pe = ChannelClient, Z0t = 服务容器
...
let o=e2t(a.protocol)                            // a = AcknowledgedRelayProtocol
```

### 5.2 完整解析步骤

1. 收到 WS 文本 → `JSON.parse` → 校验 `type==="data"` 且 `payload` 为对象。
2. 分流（手机端 `onPayload`，@4767590 附近）：
   ```js
   if(e.zcode_type===`rpc-frame`||e.zcode_type===`rpc-frame-ack`){d?.acceptPayload(e);return}
   ```
3. `acceptPayload`：先做身份匹配，再排空队列：
   ```js
   function a2t(e,t){return t.bridgeSessionId===e.bridgeSessionId
     &&t.bridgeGeneration===e.bridgeGeneration&&t.recoveryId===e.recoveryId}   // 三个字段全等（含 undefined）
   acceptPayload(e){return this.disposed||this.degraded||!o2t(e)||!a2t(this.identity,e)?!1: ...}
   ```
4. `processInbound`：`rpc-frame-ack` → `processAck(ackMessageSeq)`；`rpc-frame` → `assembler.accept(frame)`。
5. 组装器（PC 权威实现 `WebRemoteControlRpcTransportAssembler.accept`，`chunk-WR3FEWGO.js` @480180）：
   ```js
   if(FI(this.identity,n))return this.fault("remote.rpcFrame.identityMismatch",!1);
   if(typeof o=="string"&&(o.length>this.maxPhysicalFrameBytes||!Nf(o)))
       return this.fault("remote.rpcFrame.invalidBase64",!0);
   let i=qo.safeParse(n); if(!i.success)return this.fault("remote.rpcFrame.invalidMetadata",!0);
   ... if(s.messageBytes>this.maxMessageBytes) fault messageTooLarge
   ... if(s.fragmentCount>this.maxFragments)  fault fragmentLimitExceeded
   ... if(ju(s)>this.maxPhysicalFrameBytes)  fault envelopeTooLarge
   let l=Uu(s.dataBase64); if(!l||l.byteLength===0) fault invalidBase64
   // 去重：指纹 = JSON.stringify([identity..., seq, messageSeq, fragmentIndex, fragmentCount,
   //        messageBytes, checksum.algorithm, checksum.value, sliceBytes, crc32(sliceBytes)])
   if(u){ return $f(u,p)?{kind:"duplicate",ackMessageSeq:...}:this.fault("...conflictingDuplicate",!0,s) }
   if(s.seq<this.expectedPhysicalSeq) return {kind:"duplicate",ackMessageSeq:...}
   if(s.seq>this.expectedPhysicalSeq) return this.fault("remote.rpcFrame.physicalGap",!0,s);
   if(s.messageSeq!==this.expectedMessageSeq) return this.fault("remote.rpcFrame.messageGap",!0,s);
   if(s.fragmentIndex!==(this.active?.fragments.length??0)) return this.fault("remote.rpcFrame.fragmentGap",!0,s);
   if(this.active&&(s.fragmentCount!==this.active.fragmentCount||s.messageBytes!==this.active.messageBytes))
       return this.fault("remote.rpcFrame.metadataMismatch",!0,s);
   if(this.active&&s.checksum.value!==this.active.checksum.value)
       return this.fault("remote.rpcFrame.checksumMismatch",!0,s);
   if(this.active.stagedBytes+l.byteLength>this.active.messageBytes)
       return this.fault("remote.rpcFrame.lengthMismatch",!0,s);
   this.active.fragments.push(l); this.expectedPhysicalSeq+=1;
   if(fragments.length<fragmentCount) return {kind:"incomplete",...};
   if(stagedBytes!==messageBytes) fault lengthMismatch
   v=new Uint8Array(messageBytes); 逐片 set 拼接;
   if(Gr(v)!==checksum.value) fault checksumMismatch
   return {kind:"complete",messageSeq:U,bytes:v}      // ← 拼好的 RPC 报文字节
   ```
6. 手机端拿到 `bytes` 后：
   ```js
   this.onMessageEmitter.fire(ju.wrap(n.bytes))     // ju = VSBuffer 包装
   this.queueAck(n.messageSeq)                       // 触发 rpc-frame-ack
   ```
7. 字节进入 `ChannelClient.onBuffer`：
   - 第 1 个 `deserialize` = `[type, id, ...]`；第 2 个 = `data`。
   - `200 Initialize` → 标记 ready（`whenInitialized`）。
   - `201 PromiseSuccess` → resolve 对应 `id` 的 pending Promise，值为 `data`。
   - `202 PromiseError` → reject，`Error(data.message)` 并透传 `code/data/detail/details/taskId/traceId`。
   - `203 PromiseErrorObj` → reject 原始 `data`。
   - `204 EventFire` → `handlers.get(id)({type,id,data})` → 事件监听器收到 `data`。
   ```js
   let g=y=>{switch(y.type){case 201:...c(y.data);return;
     case 202:{...let B=new Error(y.data.message);B.name=y.data.name;...d(B);return}
     case 203:...d(y.data);return}};
   ```
8. **流式文本增量**：`EventFire` 的 `data` 是应用层 logical frame（JSON）；按 §7 组装/校验后取 `.frame`，增量字段在该 `frame` 内（本报告未完整还原 `frame` 的 schema，见 §9）。

---

## 6. `seq` / `messageSeq` / ack / 重传语义（置信高）

- 初值：`nextPhysicalSeq=1`、`nextMessageSeq=1`（类 `Ie`/`l2t` 字段初始化；组装器 `expectedPhysicalSeq=n.initialPhysicalSeq??1`、`expectedMessageSeq=n.initialMessageSeq??1`）。**方向独立**：每个方向各自维护一套 seq（手机→PC、PC→手机 各从 1 开始）。
- `messageSeq`：一条 RPC 报文 = 一个 messageSeq（其所有分片共用），发完 +1。
- `seq`：分片级，`seq = firstPhysicalSeq + fragmentIndex`，`firstPhysicalSeq = 上一条消息最后一片的 seq + 1` → **全局连续、不允许跳号**。解码端严格 `seq === expectedPhysicalSeq`，否则 `remote.rpcFrame.physicalGap`（terminal）。
- 去重/重传：指纹（identity+seq+messageSeq+fragmentIndex+fragmentCount+messageBytes+checksum+分片长度+分片 crc32）完全一致 → `kind:"duplicate"`，回 ack 上次完成的 `messageSeq`；指纹冲突 → `remote.rpcFrame.conflictingDuplicate`（terminal）。
- ack：接收方把「已完整收到的最大 messageSeq」排队，作为 `rpc-frame-ack {..., ackMessageSeq}` 发出（**累计确认**，一次一个，`pendingAckMessageSeq` 取更大者）：
  ```js
  queueAck(e){...this.pendingAckMessageSeq===null?(this.pendingAckMessageSeq=e,...):
    e>this.pendingAckMessageSeq&&(this.pendingAckMessageSeq=e),this.flushPendingFrames()}
  processAck(e){if(e<=this.lastAckedMessageSeq)return;
    if(e>this.highestFullySentMessageSeq){this.enterDegraded({reasonCode:`remote.rpcFrame.futureAck`,terminal:!0,...});return}
    let{releasedBytes:t}=this.outboundBatches.releaseThrough(e); ...}
  ```
- 重传：`onSendReady` / `onSendReady:()=>{d?.isDegraded()||d?.replayUnacknowledged()}` → `resetReplay()` 把未确认批次的 `nextFrameIndex` 归零后重发全部未确认帧（**幂等重发**，靠指纹去重）。
- 降级：45 s 宽限（`replayBufferGraceMs`）内既没收到数据 ack、也没收到要发的 ack → `remote.rpcFrame.ackGraceExceeded` / `replayGraceExceeded`（terminal）→ PC 端发 `bridge-degraded`：
  ```js
  {zcode_type:"bridge-degraded",bridgeSessionId,bridgeGeneration?,recoveryId?,
   reason:["rpc-transport-fault","rpc-frame-gap","buffer-overflow","buffer-timeout"],seq?,expectedSeq?,droppedCount?}
  ```
  手机端收到后 `markDegraded()` 并走 `T(reason)` 恢复流程（重新 open + 带 `recoveryId`）。
- 流控：`saturated/drained`（`sendFlowState`）与 `1 MiB/256 KiB` 高低水位、8 MiB 重放上限。

---

## 7. 订阅事件流与 logical frame（置信高）

**怎么做实时订阅**：`zcodeSessionService.subscribeConversationV4({...base, sessionId, base?, visibility?})`（channel `zcode-agent`，RPC type=100 Promise）→ 返回 `{ack:{subscriptionId, ...}}`。之后服务端用 `204 EventFire`（同一个 RPC `id`）推送数据帧。退订 `unsubscribeConversationV4({...base, subscriptionId})`（type=103 EventDispose），重同步 `resyncConversationV4`。

**topic 与会话 id 的关系**（`index-nOVzQNKW.js` @117420）：

```js
function du(e){return`conversation/${e}`}
function Zc(e){if(!e.startsWith(`conversation/`))return null;let t=e.slice(13);return t.length>0?t:null}
```
→ `topic === "conversation/" + sessionId`。手机端 `subscribe({topic})` 会先 `Zc(topic)` 取出 sessionId 再作为 RPC 参数 `sessionId` 发出去。

**logical frame 信封**（两种形态，`kind` 判别；schema 见 `asar/out/host/chunk-BG4MS6RN.js` @4633 与手机端 @105000/@122400）：

```
{wireVersion:3, kind:"complete", deliveryKind:"initial"|"online"|"recovery",
 logicalFrameId, logicalFrameOrdinal:int>0, topic:"conversation/<id>", subscriptionId, frame:<JSON>}
{wireVersion:3, kind:"fragment",  deliveryKind, logicalFrameId, logicalFrameOrdinal,
 topic, subscriptionId, fragmentIndex:int≥0, fragmentCount:int>0(≤1024),
 logicalBytes:int>0, checksum:{algorithm:"crc32",value:/^[0-9a-f]{8}$/}, dataBase64}
```

组装（手机端 `Sue` 类 @119235/@124850；PC 端 `chunk-BG4MS6RN.js` 同构）：

```js
let u=new Uint8Array(c.decodedBytes),d=0;
for(let e of c.fragments){ ... u.set(e,d),d+=e.byteLength }
if(Wee(u)!==c.checksum.value) fault frameAssemblyChecksumMismatch;
let f=new TextDecoder(`utf-8`,{fatal:!0}).decode(u);   // ← 组装结果是 UTF-8 JSON 文本
let p=JSON.parse(f);                                     // ← 反序列化成 JSON 对象
if(!xue(p,c)) fault frameAssemblyMetadataMismatch;       //   topic/subscriptionId 必须与 route 一致
let m=this.frameSchema.safeParse(p);                     //   业务帧 schema（按订阅类型传入）
return m.success?{kind:`complete`,frame:m.data,deliveryKind:...}:fault frameAssemblyInvalidPayload
```

**注意两个 `dataBase64` 的区别**：
| 位置 | 解码后内容 | 分片方式 | 校验 |
|---|---|---|---|
| `rpc-frame.dataBase64` | RPC 报文二进制 | 传输层，≤64 片，`messageBytes`+`checksum`（整条消息 crc32） | 拼接后 crc32，直接交给 ChannelClient |
| `logical frame(kind:"fragment").dataBase64` | **JSON 文本**的字节 | 应用层，≤1024 片，`logicalBytes`+`checksum` | 拼接 → crc32 → UTF-8 decode → JSON.parse → frameSchema |

**订阅 ACK 前的帧会先暂存**（`index-nOVzQNKW.js` @539900 `_Te`）：按 `topic` 建 staging 集合，`bind(topic, subscriptionId)` 时若已有 `subscriptionId` 就直接以「已激活路由」投递；`accept(frame)` 时若 `route[topic]===frame.subscriptionId` 立即投递，否则按 topic 暂存（上限 1024 帧 / 32 MiB，超出 → `fault.subscription.initialFrameStagingOverflow`）。`activate(subscriptionId)` 时把暂存帧按序 flush。

---

## 8. 逐条回答任务里的 7 个问题

### Q1 `dataBase64` 里面到底是什么？——**结论：ZCode 自有 RPC 序列化二进制报文（非 protobuf、非 JSON 文本）**

- **置信度：高**（Electron MessagePort host 路径，即本项目实际路径）。
- 证据链：
  1. 编解码器锚点唯一：`chunk-X2DDW7XG.js` 里 `serialize`/`deserialize` + `writeInt32VQL`/`readIntVQL` + tag 表 `{0..6}`；手机端同款压缩实现 @261399–264500。
  2. 中继上的 `dataBase64` 直接喂给 `ChannelClient.onBuffer`（`e2t(a.protocol) → new Z0t(new $pe(protocol))`），而 `onBuffer` 的第一件事就是 `deserialize`。若内层是 JSON 文本，这里必然是 `JSON.parse`。
  3. 反证 protobuf：全仓库无 protobuf 依赖/描述符/field-number 常量；序列化器用的是「1 字节 tag + ULEB128 长度前缀」，与 protobuf 的 `(fieldNumber<<3)|wireType` 完全不同（tag 只有 0..6 六个值，且没有 wire-type 概念）。
  4. 反证「JSON 文本 base64」：长度预算里 base64 只对**二进制报文**做 `4*ceil(n/3)`，同时另有一路把 NDJSON 文本 `+1`（换行）来估算 —— 二者被并列为不同承载（`cliNdjsonBytes` vs `channelSocketBytes`）。
- **补充（置信中）**：当 PC 端的 host 是 CLI 子进程形态时，同一条 `dataBase64` 可能装载 NDJSON 文本行 `{"method":"v4/...","params":{...}}\n`。判定方法：解开 base64 后看首字节是 `0x04`（Array tag，二进制 RPC）还是 `{`（`0x7B`，JSON 文本）。

### Q2 完整编码格式定义 —— 见 §3.3 / §3.6

- 内层为「tag + varint 长度 + payload」，非定长头、无 magic、无字段编号；`varint` 为 ULEB128（LSB 优先、bit7 续接），且以 **uint32** 语义实现（`writeInt32VQL`）。
- 题目里那个 `Qc` = `varintByteLength`，只用于长度估算；真正的写入器是 `writeInt32VQL`(`E`/手机端 `Pu`)，读取器是 `readIntVQL`(`R`/手机端 `Nu`)。
- 字段布局（客户端→服务端）见 §3.5-A；`rpc-frame` 信封字段表见 §3.2。
- 置信度：高（都是逐字代码）。

### Q3 一个完整请求怎么构造 —— 见 §4

- 「拉消息」= `subscribeConversationV4`（channel `zcode-agent`）+ 订阅流；一次性取行另用 `conversationRowsRangeV4` / CLI `session/messages`。
- 「发消息」= `sendConversationCommandV4({...base, envelope:{type:"sendText",sessionId,payload}})`。
- `bridgeSessionId`：手机端生成 `bridge-<uuid>`；`bridgeGeneration`：手机端每次 open 自增（`++u`）；`recoveryId`：仅在连接恢复流程里生成 `recovery-<uuid>` 并在下一次 open 带上、ready 后清空。三者都来自 `workspace-bridge-ready.bridge`；**必填性**：`bridgeSessionId` 必填，`bridgeGeneration`/`recoveryId` 在协议里可选（但本项目手机端总是带 `bridgeGeneration`）。
- `seq`：由协议层内部分配（从 1 起、每物理帧 +1、全局连续）；**不能自己乱编**，乱编会被判 `physicalGap` 并降级。

### Q4 一个完整响应怎么解析 —— 见 §5

- 关键函数名：`deserialize`(`x`/手机端 `Vu`)、`ChannelClient.onBuffer`（手机端 `$pe` @267812）、组装器 `WebRemoteControlRpcTransportAssembler.accept`（PC）/手机端 `qne`（在未下载的 chunk 里，但接收侧包装 `processInbound` 已见 @4695400）。
- 业务数据位置：`201 PromiseSuccess` 的第 2 个序列化值 = 返回值；`204 EventFire` 的第 2 个序列化值 = 事件载荷（订阅流帧）。
- 关键注意：**必须按 §6 严格校验收包顺序**（seq 连续、messageSeq 连续、fragmentIndex 从 0 起连续、CRC32 一致），否则会被对端/本端判定故障。

### Q5 身份三元组来源 —— 见 §4.1

- **必须**先发 `workspace-bridge-open` 并等 `workspace-bridge-ready`（手机端 `P()` 里 `await` 的就是 ready，并且用 `bridgeSessionId` 匹配）。
- PC 端在 ready 发出前 `readyAnnounced=false`，`sendFrame` 一律返回 false（不发任何 rpc-frame）；ready 发出后才会 `flushPendingFrames()`。
- 失败路径：`workspace-bridge-error`（reason 枚举见 §4.1）；运行中身份/序号故障 → `bridge-degraded`。

### Q6 `seq` 语义与 ack —— 见 §6

- 单调递增、**连续无缺口**的物理帧序号（不是"可跳号的序号"）；`messageSeq` 为逻辑消息序号。
- ack 用 `rpc-frame-ack.ackMessageSeq`（**累计**确认最大已完整收到的 messageSeq）。
- 需要重传：`replayUnacknowledged()` 会重放所有未 ack 的帧；接收侧靠指纹识别为 duplicate 并再次 ack（不会重复投递）。

### Q7 订阅事件流 —— 见 §7

- 方法：`subscribeConversationV4`（`v4/conversation/subscribe`），参数含 `workspacePath` + `sessionId` + 可选 `base{logEpoch,seq}|null` + `visibility`；返回 `ack.subscriptionId`。
- 增量数据：`204 EventFire` 的载荷 = logical frame；`kind:"complete"` 取 `.frame`，`kind:"fragment"` 需先按 §7 组装再取 `.frame`。
- `topic:"conversation/<id>"` 的 `<id>` 就是 **sessionId**（`du(e)=\`conversation/${e}\``、`Zc(topic)` 反向切片）。

---

## 9. 仍不确定的点与验证建议

| # | 不确定点 | 置信度 | 建议验证方式 |
|---|---|---|---|
| 1 | 服务端出方向 RPC 数组是否恒为 `[type,id]`（无 channelName/method） | 中 | 抓一条真实的 `204 EventFire` 报文，解 base64 后按 §3.3 手工解析第一个 `Array` 的元素个数与类型 |
| 2 | 长度估算式 `1+he(2)+1+he(204)+i+1+he(n)+n` 中 `i`（`1+varint(16)+16`）到底对应哪个字段 | 低 | 上式是**准入控制的保守上界**（发送前用最大 id/最大时间戳估算），不保证等于真实布局；以 §3.3 的实际序列化器为准，用真机抓包反推 |
| 3 | CLI 型 host 下 `dataBase64` 是否为 NDJSON 文本 | 中 | 解开 base64 看首字节：`0x04` → 二进制 RPC；`0x7B`(`{`) → NDJSON。也可看 `measureFrameBytes` 走哪条分支 |
| 4 | 业务帧 `frame` 的 schema（消息列表结构、流式文本增量字段名） | 低 | 该 schema 随订阅类型注入 `assembler`/`frameSchema`，且定义在未下载的手机端 chunk `src-DHgFesxz.js`；可用真机抓一帧 `kind:"complete"` 的 `frame`，或设法取到该 chunk |
| 5 | 手机端 `qne`（rpc-frame 组装器）与 PC 端 `Uf` 是否逐字等价 | 高（行为等价）/ 中（实现） | 两端都遵循同一 schema 与同一故障码命名，且 PC 端为权威解码方；差异仅在函数名 |
| 6 | 各 service 具体 method 全集（尤其 `zcode-agent` 通道） | 中 | 已从手机端 @539568–552000 提取到一批（subscribe/send/query/rowsRange/plans/fileChanges/attachment*/`onDynamicConversationFrame`）；完整列表在 `src-DHgFesxz.js` |
| 7 | `204 EventFire` 载荷是「`Object` tag 的 JSON」还是「`String` tag 的 JSON 文本」 | 中 | 抓包看第二个值的 tag 字节：`0x05` vs `0x01` |
| 8 | `rpc-frame-ack` 是否需要 `client_ts` | 高（需要） | 手机端 `sendFrame` 统一走 `{type:"data",payload,client_ts}` 序列化器（`d2t.prepare`） |

**最小验证实验（推荐）**：在浏览器 `Remote` 页面注入一层 WebSocket 代理，把每一帧 `{"type":"data"}` 的 `payload.dataBase64` 落地；然后：
1. 用 Node 的 `Buffer.from(b64,"base64")` 解出字节，打印 hex；
2. 按 §3.3 写 30 行解析器（tag/varint）→ 应看到 `04 04 06 <type> 06 <id> 01 <len> <channel> 01 <len> <method> ...`；
3. 对 `seq/messageSeq` 做连续性断言 → 验证 §6。

---

## 10. 关键代码片段索引（便于复核）

| 主题 | 文件 | 偏移 |
|---|---|---|
| RPC 序列化器（可读版，权威） | `asar/out/main/chunk-X2DDW7XG.js` | 全文（13 KB，`serialize`=`v`, `deserialize`=`x`, `writeInt32VQL`=`E`, `readIntVQL`=`R`） |
| SocketProtocol 13 字节头 | `asar/out/main/chunk-X2DDW7XG.js` | `var S=13` … `writeProtocolMessage` |
| MessagePortProtocol（中继路径） | `asar/out/main/chunk-X2DDW7XG.js` | 类 `V` |
| ChannelClient（`getChannel`/`requestPromise`/`requestEvent`/`sendRequest`/`onBuffer`） | `asar/out/main/chunk-X2DDW7XG.js` | 类 `D` |
| CRC32 / base64 / rpc-frame schema / 上限 | `asar/out/main/chunk-WR3FEWGO.js` | 470800–472600 |
| 分片编码器 `findDecodedBudget` / `encodeWebRemoteControlRpcTransportMessage` | `asar/out/main/chunk-WR3FEWGO.js` | 475049–477300 |
| 组装器 `WebRemoteControlRpcTransportAssembler.accept` | `asar/out/main/chunk-WR3FEWGO.js` | 477800–483200 |
| CLI 方法名表（`session/*`） | `asar/out/main/chunk-WR3FEWGO.js` | 467800–468300 |
| AcknowledgedRelayProtocol（seq/ack/重放/降级） | `asar/out/main/chunk-NHZHAM44.js` | 37000–49200 |
| 中继↔host 接线、开桥实现、ready 门禁 | `asar/out/main/index.js` | 1158000–1172500 |
| 中继线格式 zod（rpc-frame/-ack/bridge-*） | `asar/out/host/chunk-RWMCBKS2.js` | 468400–475200 |
| 通道名枚举（`zcode-session` 等） | `asar/out/host/chunk-RWMCBKS2.js` | 499200–500600 |
| logical frame schema + v4 方法名表 + `measureTopicNotificationEnvelopeBytes` | `asar/out/host/chunk-BG4MS6RN.js` | 4633–6300 / 10600–11480 / 19700 |
| 手机端：RPC 编解码器压缩版 | `index-nOVzQNKW.js` | 261399–270900 |
| 手机端：varint 长度 `Qc` + 信封长度估算 `mue` | `index-nOVzQNKW.js` | 117346–117900 |
| 手机端：logical frame assembler `Sue` | `index-nOVzQNKW.js` | 119235–125200 |
| 手机端：AcknowledgedRelayProtocol（`l2t`）+ 重放缓冲 `n2t` | `index-nOVzQNKW.js` | 4686000–4697000 |
| 手机端：relay→ChannelClient 包装 `e2t` | `index-nOVzQNKW.js` | 4687077 |
| 手机端：开桥流程 `P()` | `index-nOVzQNKW.js` | 4769400–4770100 |
| 手机端：订阅/发消息/查询封装 | `index-nOVzQNKW.js` | 539568–552000 |
| 手机端：订阅 staging `_Te` | `index-nOVzQNKW.js` | 539900–541000 |
