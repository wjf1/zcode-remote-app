# 交接开发计划（HANDOVER）

> **面向接手的 AI agent**：本文自包含。拿到本仓库 + 本文档即可直接开工，无需原会话上下文。
> 文中所有路径相对仓库根 `F:\AI\Zcode\zcode-remote-app`（Windows，Git Bash）。
> 配套必读文档见 §2，环境与命令见 §3，剩余任务见 §6（按 P0/P1/P2 排序，每项含验收标准）。

---

## 1. 项目一句话与现状

**ZCode Remote**：ZCode 官方远程控制（`wss://zcode.z.ai/ws` 中继）的原生安卓增强客户端，Kotlin + Jetpack Compose。目标机型小米 15 Pro（HyperOS 2 / Android 15），纯自用暂不分发。

- 协议已完整逆向并实测（M0），App 的配对/会话/审批/多机/设置全部打通并在模拟器（Pixel 7 / API 35 / AVD `test35`）验证（M1 ✅ M2 ✅，M3 主体完成）。
- 已发布签名 Release：[v0.3.0-m3](https://github.com/wjf1/zcode-remote-app/releases/tag/v0.3.0-m3)（私有仓库 `wjf1/zcode-remote-app`，`gh` CLI 已登录账号 wjf1）。
- **已知外部事件**：桌面端 rotate 凭据后旧 sid 失效；模拟器网络随宿主直连链路波动（冷启动可修）。App 侧均已适配。

## 2. 必读文档索引（按此顺序读）

| 顺序 | 文档 | 内容 |
|---|---|---|
| 1 | `PROTOCOL.md` | 中继协议总纲：端点/配对/握手(HMAC proof)/错误码/RPC 面/审批（§6.3 含双路实证与端到端验收记录） |
| 2 | `research/CONVERSATION-PROTOCOL.md` | 会话流协议：订阅四步序列、204 逻辑帧、快照/增量/行模型、翻页、ack 身份三元组校验（§8 是大坑） |
| 3 | `ZCode远程控制安卓APP方案.md` | 产品方案：竞品/路线决策/功能清单/里程碑/风险（M4 VPS Runner 为正式交付项） |
| 4 | `research/FRAME-CODEC.md` | VQL 二进制编解码规范（`relay/Vql.kt` 与 `tools/probe.py` 是两份独立实现，互为校验） |
| 5 | `CHANGELOG.md` | 版本历史（含踩坑记录，先读再动手） |
| 6 | `app-android/README.md` | 构建与运行 |

## 3. 环境速查

全部工具链在仓库内 `toolchain/`（被 gitignore），**无需 Android Studio**：

```bash
./build.sh            # 构建 debug APK（先看 BUILD SUCCESSFUL 再继续，tail 会吞 ^e: 编译错误）
./build.sh install    # 构建 + 安装到设备/模拟器 + 授权 + 启动
./build.sh log        # 抓 App 中继日志（tag: RelayClient/RpcChannel/ConvChannel/AppViewModel）

# 模拟器（无头，AVD 已建好）
export ANDROID_AVD_HOME="F:\\AI\\Zcode\\zcode-remote-app\\toolchain\\avd"
"toolchain/android-sdk/emulator/emulator.exe" -avd test35 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot &
# ⚠️ 起后台进程一律用工具的 run_in_background，不要裸 &（会杀 agent host，见仓库根 AGENTS.md 若有）
# ⚠️ 模拟器熄屏会触发 Doze 切断 App 网络：测试前 `adb shell dumpsys deviceidle disable` + 保持充电
# ⚠️ 模拟器顶部 ~130px（挖孔区）会吞 input tap：UI 自动化点击 y≥150，页面已做 statusBarsPadding

ADB=toolchain/platform-tools/adb.exe    # adb/模拟器全套在此

# 协议探针（模拟手机端，调试协议秒级迭代；凭据自动读 PC 端 ZCode 配置）
ZCODE_MID=$(python -c "import json;print(json.load(open(r'C:/Users/admin/.zcode/v2/telemetry-state.json'))['deviceMid'])") \
  python tools/probe.py auth|boot|bridge|chan|sub <会话ID前缀>
python tools/setmode.py list|set <taskId前缀> build|yolo   # 切会话权限模式（验收审批用）
python tools/_e2e_send.py                                  # 以手机端身份发排队消息
python tools/_e2e_tap_approve.py                           # 验收自动化：等通知→点「允许一次」→回读证据
```

- **签名**：`toolchain/keys/zcode-remote.keystore` + `app-android/keystore.properties`（密码在此，**不入库、勿丢失**；PKCS12 约束 key 密码=store 密码）。
- **PC 端凭据**（probe/App 配对用）：`~/.zcode/v2/setting.json`（deviceSid）+ `~/.zcode/v2/credentials.json`（pass_hash，解密算法见 `tools/probe.py` load_credentials）+ `~/.zcode/v2/telemetry-state.json`（deviceMid）。桌面端 rotate 后三处同步更新，App 用「添加设备→粘贴链接」重新配对。
- **git push**：`git -c http.proxy=http://127.0.0.1:7897 push ...`（本机代理；直连国际线路不稳定）。

## 4. 已完成基线（不要重做）

| 模块 | 状态 | 关键文件 |
|---|---|---|
| 握手/重连/心跳/KICKED 分类 | ✅ 实测 | `relay/RelayClient.kt` `RelayProtocol.kt` |
| 扫码/手动配对 + 多机管理（列表/切换/移除，v2 加密存储+迁移） | ✅ 实测 | `storage/MultiDeviceStore.kt` `PairedDevice.kt` `ui/screens/ScanScreen.kt` |
| 会话列表 + 11 种任务事件 | ✅ 实测 | `relay/BridgeFrames.kt` `ui/screens/HomeScreen.kt` |
| 会话流（订阅四步/快照/增量/行渲染/翻页 atLogEpoch 校验） | ✅ 实测（848 行会话） | `relay/ConversationChannel.kt` `ConversationFrames.kt` `ui/screens/ConversationScreen.kt` |
| 权限审批（双源接收/通知栏批准/resolveInteraction 应答） | ✅ 端到端验收 | `relay/Interactions.kt` `notify/ApprovalNotifier.kt` `AppViewModel.kt` |
| rpc-frame 分片重组（CRC32+messageBytes 双校验） | ✅（多分片实环境未现，直通路径 190 帧无回归） | `relay/RpcChannel.kt` |
| 线路切换（主线/备线/自定义，Origin 同源推导）+ 主题三模式 | ✅ 实测 | `storage/SettingsStore.kt` `ui/theme/Theme.kt` `HomeScreen.kt` |
| 保活引导页 / debug 注入 receiver / 前台服务 | ✅ | `ui/screens/KeepAliveGuideScreen.kt` `app/src/debug/` `service/ConnectionService.kt` |

版本序列：`v0.2.0-m2` → `v0.2.1-m2b` → `v0.2.2-m3a` → `v0.2.3-m3b` → **`v0.3.0-m3`（当前，versionName 0.3.0-m3 / versionCode 4）**。

## 5. 关键技术结论（浓缩坑清单，动手前必读）

1. **审批到达手机端走双路**：会话帧 `pendingInteractions`（snapshot / `state.updated` patch，整组替换）+ 任务事件流 `permission_request`（host `permissionRequestToStreamEvent`：`requestId` 即应答的 `interactionId`，选项无 `label` 用 `name`）。消解推送 `permission_resolved`。
2. **审批应答** = `sendConversationCommandV4({workspacePath, workspaceIdentity?, envelope})`，envelope=`{commandId, clientId, sessionId, type:'resolveInteraction', payload:{interactionId, answer:{optionId}}, issuedAt}`。三条硬约束：`clientId` 与 initialize 一致；`optionId` 原文透传；**不做断线重放**（sensitive 命令）。
3. **手机端发消息**（会话页输入栏需要）：`zcode-agent` 通道 `sendPrompt`（**不是** `send`），args=`{workspacePath, sessionId, inputId, content}`，消息进会话队列（autoDrain）在当前 turn 结束后执行。
4. **setMode**：`zcode-agent` 通道 camelCase 方法；**只对新 agent turn 生效**，运行中 turn 的权限上下文不变（验收审批时必须用新 turn 触发）。
5. **rpc-frame-ack 必须带齐身份三元组**（bridgeSessionId+bridgeGeneration+recoveryId 全等），漏一个被静默丢弃并导致服务端无限重放。
6. **互踢**：同一 deviceSid 中继只允许一个 terminal 在线（App/官方 Web/probe 三者互斥，`KICKED`）。调试探针用完即退。
7. **QrParser**：配对链接里的字面 `+` 必须先转 `%2B` 再 `Uri.getQueryParameter`（否则 base64 hash 含 `+` 时配对必失败）。
8. **`conversationRowsRangeV4`**：`{workspacePath, sessionId, beforeRowId, limit:60}` → `{rows, atSeq, atLogEpoch, hasMore}`；`atLogEpoch` 不等当前快照则整批丢弃。
9. **中继错误码语义**：`KICKED`=会话冲突（终态提示）；`AUTH_FAILED/WRONG_PARAM`=配对失效（终态）；`DEVICE_OFFLINE`=等待重连。`auth_ack` 结构未知时报 `PROTOCOL_MISMATCH`（协议升级兜底，已实现）。

## 6. 剩余任务（P0 → P2，含验收标准）

### P0-1 会话页「发送消息 + 停止按钮」（✅ 2026-09-29 完成，协议层端到端验收通过）

- **已完成**：`ConversationScreen` 底部输入栏（TextField + 发送，走 `sendPrompt`）与
  「停止」按钮（`control.canStop` 时显示，envelope `type:'stop'`，官方 web 同款）。
  协议实证与端到端实测记录见 PROTOCOL.md §6.4；验收工具 `tools/_p01_async.py`。
- **实测**：桌面端在线时四项全 PASS——sendPrompt 201 `accepted:true` → turn running 且
  `canStop=true` → stop ack `status=accepted` → 桌面端 `completedInterrupted`、canStop 清零。
- **遗留**：App UI 层模拟器验收未做——本机兆芯 CPU 无模拟器硬件加速（qemu 静默退出），
  待 Intel/AMD 机器或 P0-2 真机补验；构建环境见 CHANGELOG「未发布」段
  （toolchain 丢失已回退系统路径，release keystore 需恢复）。

### P0-2 真机验收（需小米 15 Pro 到手）

- **目标**：M3 验收标准「小米 15 Pro 日常可用，发内测 APK」。
- **清单**：
  1. 安装 v0.3.0-m3 Release APK → 扫码配对（验证相机路径）；
  2. 按 App 内「保活引导」页设置 HyperOS 权限（引导文案与真机实际设置路径逐条核实并修订）；
  3. **锁屏审批实测**：切 build 模式（`tools/setmode.py`）触发工具调用 → 锁屏收通知 → 通知栏批准 → 桌面端放行（复用 `_e2e_tap_approve.py` 思路，真机上脚本点击可换人工）；
  4. 内外网各测一次（中继天然穿 NAT，重点验证公司内网出站）；
  5. HyperOS 杀后台场景：锁屏 30min 后审批通知是否可达。
- **验收**：锁屏状态下审批→批准全链路成功；连续 3 天日常使用无失联。
- **预估**：0.5 天（+3 天观察）。

### P1-1 elicitation（表单类交互）应答（✅ 2026-09-29 完成，协议层端到端验收通过）

- **已完成**：`Interactions.kt` 新增 `PendingElicitation` 模型（pendingInteractions kind=="userInput"
  条目 + 任务事件流 elicitation_request 双源解析）；`ConversationChannel` 增加 `elicitations` 流与
  `resolveElicitation`（与审批共用 resolveInteraction 管道，answer 按 §6.5 形态构造）；
  `AppViewModel` 合并双源 + 语义化应答 API（accept 含 content 表单 / decline / freeText / 计划批准）；
  `ConversationScreen` 新增 `ElicitationCard`（计划批准、逐题选项+多选、自由文本、拒绝）。
- **协议实证**：host answer zod `{optionId?, freeText?, action?, content?}`；官方 web v4
  `onRespond → {action, content}`（表单 content 由 rut 构造 answer/answer_N/answers）；真实帧
  确认 pendingInteractions kind=="userInput" 条目全字段。详见 PROTOCOL.md §6.5。
- **实测**：AskUserQuestion 真实触发 → 条目观察 → `{action:'accept', content:{answer:…}}` →
  ack accepted → 条目消解，三项 PASS（tools/_p11_verify.py）。
- **遗留**：App UI 层模拟器验收同 P0-1 待补；通知栏不做 elicitation（表单复杂，会话页内应答）。

### P1-2 多会话并行看板 + 控制权切换提示

- **要点**：HomeScreen 已有会话列表，扩展为多会话角标（`pendingInteractionSummary` 已在 overlay 数据里）+ 会话间快速切换；App 接管时给桌面端发提示（`mobile-view-state-update` 已实现，补 UI 提示「另一端已切换」）。
- **验收**：两条会话并行审批不串台（interactionId 精确匹配已保证）。
- **预估**：1 天。

### P1-3 文件上传 / 语音输入

- **要点**：attachment 四件套 RPC 已枚举（`attachmentBeginV4/ChunkV4/CommitV4/PreviewSourceV4`，见 host-index 搜索）；语音=录音→转文字（系统 IME 语音或本地 Whisper）→ 走 sendPrompt content。
- **验收**：App 端选图片发送，桌面端会话收到附件。
- **预估**：2~3 天。

### P1-4 协议【待验证】项补全（PROTOCOL.md §8 列表）

- `maxPhysicalFrameBytes` 阈值、PC 侧 meta 字段、心跳间隔分配。方法：`tools/probe.py` 受控实验 + host-index 搜索定位常量，结论回写 PROTOCOL.md。
- **预估**：0.5 天。

### P2-1 M4：VPS 备用 Runner（正式交付项，已用户确认排期）

- **目标**：PC 关机时任务落到 VPS 执行（同一中继协议接入）。
- **要点**：VPS（¥10~40/月）部署 `zcode` CLI + ACP server，作为第二台"设备"生成配对；App 端设备列表天然支持（多机管理已就绪）；任务需基于 Git 仓库（提供 Git 同步引导页）；会话服务端可恢复。
- **验收**：PC 关机，手机新建任务并在 VPS 跑完，结果可见。
- **预估**：1.5 周（含 VPS 采购部署）。

### P2-2 M5：高级模式（自建 bridge + NaCl E2E + 自建中继）

- **要点**：桌面端环境变量 `ZCODE_WEB_REMOTE_CONTROL_RELAY_WS_URL` / `ZCODE_WEB_REMOTE_CONTROL_URL` 可指到自建服务器（PROTOCOL §1）；自建中继（Node/Go，纯转发）+ NaCl 端到端加密层（App 与 bridge 共享密钥，中继只见密文）。
- **验收**：E2E 链路演示（中继抓包全密文）。
- **预估**：2~3 周。

### P2-3 其他 P2

文件/diff/Git 浏览（参考 CloudCLI）、Wear OS 快捷审批、桌面 Widget、可选小米推送通道。

### 技术债（随手清）

- `ConversationScreen` 移除设备/危险操作加确认弹窗；「earlier-head」占位项在 0 行会话的显示边界。
- 会话流 `v4/conversation/frame` 二进制细节字段未穷举（不影响当前功能，遇到未知 op 已有 Unknown 分支兜底）。
- `HomeScreen` 的 rpcEvents 调试面板（M2 观测用）内测前可移到 debug 构建。
- logcat 日志量大（每个 delta 一条 Info），内测前把 `AppViewModel: rpc event` 降为 Log.d。

## 7. 工作流程约定（每个任务都走这个循环）

1. **先读** §2 文档对应章节 + §5 坑清单，再用 `tools/probe.py` 验证协议假设（第二份实现互校），**然后**写 Kotlin。
2. 构建：`./build.sh`（必须见 BUILD SUCCESSFUL）；实测：模拟器 + `adb shell uiautomator dump` / `screencap` / `logcat -d -s <TAG>`；UI 自动化注意挖孔区（点击 y≥150）。
3. 涉及审批/模式的验收：`tools/setmode.py` 切模式，注意 §5.4 的 turn 语义。
4. 提交：常规修复/功能直接 commit（中文 message，说明实测结果）；**发布**：升 `versionName`/`versionCode`（`app/build.gradle.kts`）→ tag `vX.Y.Z-mN` → `gh release create` 附签名 APK（`assembleRelease` 产物）。
5. 推送：`git -c http.proxy=http://127.0.0.1:7897 push origin master --tags`。
6. 文档同步：协议新结论回写 `PROTOCOL.md`/`CONVERSATION-PROTOCOL.md`；功能变更更新 `CHANGELOG.md` + 双语 `README.md`。

## 8. 红线

- **暂不开源、不分发**：仅连接用户本人 ZCode 账号/设备（方案 §九.4 合规边界）。仓库私有。
- **keystore 与 keystore.properties 不入库、不外传**（丢失则所有已安装用户无法升级）。
- **resolveInteraction 不做断线自动重放**（官方 sensitive 命令语义，宁可让用户重按）。
- **凭据安全**：passHash 只出现在配对链接里，App 内已加密存储；不要把它写进日志/文档/代码。
- 起后台进程用工具的 run_in_background，禁止裸 `&`/`nohup`（会杀 agent host）。
