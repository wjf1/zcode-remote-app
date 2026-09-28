# ZCode Remote（M1/M2 已完成并在模拟器实测通过）

小米 15 Pro 上的 ZCode 官方远程控制原生增强客户端。
方案见 [../ZCode远程控制安卓APP方案.md](./ZCode远程控制安卓APP方案.md)（V0.2），协议见 [../PROTOCOL.md](./PROTOCOL.md)（v1.0，含真机实测修正）。

## 实测验证状态（M2 端到端验收通过，2026-09-28）

在 Android 15 模拟器（Pixel 7 / google_apis x86_64 / API 35）完整闭环：

```
桌面端会话切 build 模式 → agent 工具调用触发 permission_request
 → 锁屏状态下 App 收到审批推送（会话帧 pendingInteractions + 任务事件流双源）
 → 高优先级通知「需要审批：Bash」+「允许一次/总是允许/拒绝」按钮
 → 通知栏点击「允许一次」 → sendConversationCommandV4{resolveInteraction}
 → 桌面端 ack Accepted(status=accepted) → 被批准的命令实际执行 ✅
```

App 侧关键日志证据：

```
ConvChannel: pendingInteractions → 1 条 Bash#perm_2354aa58
ConvChannel: resolve interaction=perm_2354aa58… option=allowOnce
AppViewModel: resolve result=Accepted(status=accepted)
```

## 实测验证状态（M1 验收通过）

同模拟器实测跑通握手与会话列表：

```
WSS 建连(?mid=…, Origin: https://zcode.z.ai)
 → auth_init(role=terminal) → auth_challenge(nonce)
 → auth_response(proof=base64url(HMAC-SHA256)) → auth_ack + pair_status:"matched"
 → heartbeat(pair_status_query) → pair_status_ack:"matched"
 → data{ bootstrap-request } → data{ bootstrap-response } ✅ 真实会话列表渲染成功
```

## 功能（M1）

- **扫码配对**：CameraX + ZXing 解析官方二维码（`sid/hash/mid/name`），支持手动粘贴链接兜底；凭据 AES-256-GCM + Android Keystore 加密存储。
- **中继连接**：完整握手（含 4 处实测修正：`mid` 查询参数、`Origin` 头、base64url proof、`data` 信封 + `client_ts`）、`waiting/matched` 状态机、30s 心跳、指数退避重连、KICKED/AUTH_FAILED 等错误分类提示。
- **会话列表**：bootstrap-response 解析，按运行状态高亮，显示工作区/模型/状态；过滤归档项。
- **事件流**：11 种任务事件（含权限审批 `permission_request`）实时渲染；审批选项分类器已内置。
- Material 3 深色主题，对齐官方 remote/v4 观感。

## 功能（M2）

- **会话流实时渲染**：订阅会话（`subscribeConversationV4` 全序列：listen → hello → initialize → subscribe）、快照 + 增量 deltas；行按 reasoning / toolCall / assistantText 分类渲染，流式内容实时更新（实测标题栏「已订阅 · 运行中 · 共 N 行」）。
- **权限审批**：pendingInteractions 双源接收（会话帧整组替换 + 任务事件流 `permission_request`/`permission_resolved`），会话内审批条（批准/拒绝）+ **通知栏直接批准/拒绝**（最多 3 按钮，按 kind 排序）。
- **审批应答**：`resolveInteraction` 信封（`clientId` 硬约束、`optionId` 原文透传）；断线时明确提示「没发出去」，不做自动重放（官方 sensitive 命令语义）。
- debug 构建内置 `DebugApprovalReceiver`（注入假审批验证通知渲染，release 不含此组件）。

## 构建与运行（无需 Android Studio）

本机工具链已就绪于 `../toolchain/`（JDK 17 + Gradle 8.7 + Android SDK 35 + platform-tools + emulator）。

```bash
# 构建
./build.sh

# 构建 + 安装到设备/模拟器 + 启动
./build.sh install

# 查看中继握手日志
./build.sh log
```

启动模拟器（无头，需先装 AEHD 驱动，已装）：

```bash
export ANDROID_AVD_HOME="F:\\AI\\Zcode\\zcode-remote-app\\toolchain\\avd"
"../toolchain/android-sdk/emulator/emulator.exe" -avd test35 -no-window -no-audio \
  -no-boot-anim -gpu swiftshader_indirect -no-snapshot &
```

> 模拟器测试注意：**熄屏会触发 Doze 切断 App 网络**（`SocketException: connection abort`，重连持续失败）。
> 测试机需 `adb shell dumpsys deviceidle disable` + 保持充电状态；真机对应 HyperOS 省电白名单引导（M3）。
>
> 若用 Android Studio：直接打开 `app-android/` 目录即可（`local.properties` 已指向本机 SDK）。
> ⚠️ 构建时**务必确认 BUILD SUCCESSFUL**，只看 `tail` 会吞掉编译错误（`^e:` 开头的 Kotlin 错误行）。

## 已知限制

- ~~向上翻页、分片重组~~ 已实现（v0.2.1）：滚到顶部自动 `conversationRowsRangeV4` 前插；rpc-frame 多分片收齐校验后重组。
- 与官方 Web 版互踢（同 deviceSid 单端在线），使用本 App 时请关闭官方网页端；`tools/probe.py` 探针同理。
- 会话内部对话流（`rpc-frame` 内层 `v4/conversation/frame` 的二进制解码细节）以实测行为为准，仍有待穷举字段。
- elicitation（表单类交互）只读不答（应答形态不同，暂不处理）。

## 工程结构

```
app/src/main/java/com/zcode/remote/
├── relay/          RelayProtocol（握手/proof）· RelayClient（状态机/心跳/重连）
│                   BridgeFrames（data 信封/bootstrap 解析/SessionItem/任务事件）
│                   RpcChannel（桥 RPC/分帧/ack）· ConversationChannel（订阅/会话流/审批应答）
│                   ConversationFrames（快照/增量解析）· Interactions（PendingApproval 模型）
│                   Vql（服务端对象编码）
├── notify/         ApprovalNotifier（通知栏同步/按钮）· ApprovalBridge（连接级应答桥）
├── storage/        QR 解析 + Keystore 凭据存储
├── service/        ConnectionService（前台服务）
├── ui/screens/     ScanScreen（扫码/手动配对）· HomeScreen（状态+会话列表+事件）
│                   ConversationScreen（会话流渲染+审批条）
└── AppViewModel    状态中枢（连接/会话/审批合并与通知同步）
```
