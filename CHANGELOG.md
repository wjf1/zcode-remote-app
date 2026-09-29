# 变更记录 / Changelog

## 未发布（P0-1 会话页发送 + 停止）

**里程碑：P0-1 完成 —— 会话页输入栏（sendPrompt）+ 运行中停止（stop envelope）**

### 新增
- **会话页底部输入栏**：TextField + 发送按钮（草稿跨重组保存在 ViewModel，发送成功才清空；
  发送走 `zcode-agent` 通道 `sendPrompt`，args=`{workspacePath, sessionId, inputId, content}`，
  成功后 userInput 行由服务端推回会话流，不做本地 append）；`imePadding`+`navigationBarsPadding`
  避开键盘与手势条（targetSdk 35 强制 edge-to-edge，insets 正常分发）。
- **停止按钮**：显示条件为快照/增量 `control.canStop`（官方 web 同款互斥逻辑——输入框有草稿
  显示发送，空草稿且 canStop 显示停止；`stopState=="stopping"` 显示「停止中…」并禁用）。
  命令 = `sendConversationCommandV4` envelope `type:'stop'`，payload 按官方行为带上
  `control.activeWorks` 里的 `foregroundExecutionId`（无则空 payload）；ack 判据与审批一致
  （status accepted/duplicate/noop）。
- **control 状态解析**：`ConversationFrames.parseControl`（phase/canStop/stopState/
  foregroundExecutionId），快照与 `state.updated` patch 双路更新到 `ConversationMeta`。
- **操作反馈条**：发送/停止结果经 `commandFeedback` 在会话页显示（4s 自动清除）。
- `tools/_p01_async.py`：P0-1 协议层端到端验收脚本（asyncio + websockets 库版探针）。

### 构建环境
- `app/build.gradle.kts`：release 签名条件化——keystore.properties 缺失时不再在配置期抛异常
  （原先连 assembleDebug 都过不去）。⚠️ 正式 keystore 随原 toolchain 丢失，发布前必须恢复。
- `build.sh`：仓库内 toolchain/ 缺失（换机/重新克隆）时自动回退系统路径
  （F:/AndroidTools 的 JDK17/SDK/Gradle 8.11.1）。

### 协议实证（写入 PROTOCOL.md §6.4）
- **stop 是 envelope 命令而非裸 RPC**：host asar 官方 web 版 `br('stop', {expectedForegroundExecutionId?}, sessionId)`，
  payload zod schema `stop:{expectedForegroundExecutionId: string.min(1).optional()}`；
  `sessionStop:"session/stop"` 只是 host→CLI 内部层枚举，与远程通道无关。
- 端到端实测（桌面端在线，probe 身份）：sendPrompt 201 `accepted:true` → turn running 且
  `control.canStop=true/stopState=stoppable` → stop ack `status=accepted` → 桌面端相位
  `running → completedInterrupted`、canStop 清零。四项全 PASS，帧落盘 `_tmp/p01_frames_*.json`。
- 桌面端远程控制面板打开期间 device 在线（等待连接即 matched 可达）；`webRemoteControlLastEnabledContext`
  是桌面端启动恢复远程控制的持久化上下文。

### 已知环境限制
- 本机（兆芯 KX-7000 / UNICOMPute）无 Android 模拟器硬件加速：emulator 的 qemu 在该 CPU
  静默退出（需 Intel/AMD），且 sdkmanager 大文件下载固定断连（33% 处）——系统镜像改由
  腾讯镜像 curl 断点续传获取。App UI 层模拟器验收待 Intel/AMD 机器或 P0-2 真机补验；
  协议层与 App 实现同参数同路径，已由探针实测闭环。


## v0.3.0-m3（2026-09-28）· 首个签名 Release

**里程碑：M2 完成 + M3 主体功能——首个可安装的签名发布包**

汇总 v0.2.0-m2 ~ v0.2.3-m3b 全部内容：扫码配对、中继连接、会话列表与流式渲染、
权限审批（会话内 + 通知栏，双源接收 + resolveInteraction 应答）、历史翻页、分片重组、
多机管理、线路切换、主题三模式、保活引导、协议变更兜底。

- `versionName 0.3.0-m3` / `versionCode 4`；release APK 由专用 keystore 签名
  （`app-android/keystore.properties` 本地保存，不入库；PKCS12 约束 key 密码与 store 密码一致）。
- 下载：GitHub Releases 页 `ZCodeRemote-0.3.0-m3.apk`（minSdk 31 / targetSdk 35）。

## v0.2.3-m3b（2026-09-28）

**里程碑：M3 第二批 —— 多机管理 + 保活引导 + 协议兜底**

### 新增
- **多机管理**：`MultiDeviceStore`（v2 加密格式，多台凭据 + 活跃设备；旧单条格式自动迁移）；主页设备卡显示其余已配对设备，支持「切换」（断旧连新，各自独立 mid）与「移除」；「添加设备」不再清空现有凭据（修复按钮残留的 forget 语义）。
- **保活引导页**（小米/HyperOS）：通知/自启动/省电策略/锁定后台/常驻通知/锁屏显示六步图文，设置卡入口。
- **协议变更兜底**：`auth_ack` 结构未知（pair_status 缺失/未知）不再静默忽略，报 `PROTOCOL_MISMATCH` 提示官方协议升级。
- **QrParser 修复**：配对链接里字面 `+` 会被 `Uri.getQueryParameter` 解码成空格——含 `+` 的 base64 hash 配对必失败；先统一 `%2B` 转义再解析。
- 页面统一 `statusBarsPadding()`，顶部控件避开状态栏/挖孔区（修复模拟器上顶部按钮不可点）。

### 实测（模拟器）
- v1→v2 凭据自动迁移：升级安装后直接恢复「已配对」。
- 多机全流程：手动粘贴真实配对链接（169 字符含 `+`/`=` 的 hash）配对成功 → 添加假设备（列表 2 台、活跃互换、假凭据正确提示 AUTH_FAILED）→ 切换/移除入口可用 → 冷启动后列表持久化正确。
- 保活引导页渲染完整；`+` 修复后真凭据配对链路打通。

### 环境事件记录
- 桌面端 `credentials.json` 的 `web-remote-control:external-relay:pass_hash` 键中途消失（远程控制模块被重置），导致所有 terminal 认证 AUTH_FAILED——需在桌面端重新开启远程控制生成新二维码，App 扫码即可重新配对（多机管理已就绪）。
  已解决（当日）：桌面端 rotate 出新 sid/hash，App 手动粘贴新链接重新配对成功（已配对 + 会话桥就绪），
  两个失效条目（旧 sid、假设备）经多机管理「移除」清理，会话流实时订阅恢复（快照 60/848 行）。
- 模拟器网络随宿主直连链路波动（PARTIAL_CONNECTIVITY），radio 级切换无效需冷启动；与 App 代码无关。

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
