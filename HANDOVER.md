# 交接开发计划（HANDOVER）

> **面向接手的 AI agent**：本文自包含。拿到本仓库 + 本文档即可直接开工，无需原会话上下文。
> 文中所有路径相对仓库根 `F:\AI\Zcode\zcode-remote-app`（Windows，Git Bash）。
> 配套必读文档见 §2，环境与命令见 §3，剩余任务见 §6（按 P0/P1/P2 排序，每项含验收标准）。

---

## 1. 项目一句话与现状

**ZCode Remote**：ZCode 官方远程控制（`wss://zcode.z.ai/ws` 中继）的原生安卓增强客户端，Kotlin + Jetpack Compose。目标机型小米 15 Pro（HyperOS 2 / Android 15），纯自用暂不分发。

- 协议已完整逆向并实测（M0），App 的配对/会话/审批/多机/设置全部打通并验证（M1 ✅ M2 ✅ M3 主体完成）。
- 已发布签名 Release：[v0.3.0-m3](https://github.com/wjf1/zcode-remote-app/releases/tag/v0.3.0-m3)（私有仓库 `wjf1/zcode-remote-app`，`gh` CLI 已登录账号 wjf1）。
- **2026-09-29 增量（本轮）**：P0-1 发送/停止 ✅、P1-1 表单应答 ✅、P1-2 多会话看板 ✅、
  P1-4 协议常量结清 ✅、技术债清理 ✅——均已构建通过并推送（提交见 `git log`）。
- **2026-09-29 二轮**：**X-1 keystore 结清**（实测与发布 APK 同指纹，见下）、
  D-2 贴底索引修复 ✅、D-3 elicitation 通知栏快捷应答 ✅、**P1-3 附件上传**
  （协议实机验证 + Kotlin 客户端 + UI）✅ 主体完成；
  PROTOCOL.md §6.6 / CHANGELOG / README 已同步。
- **2026-09-30 三轮（本次接力）**：**P1-3 附件发送侧实证修复**——对照实测发现
  `sendPrompt` RPC 的 attachments 被 zod strip（201 但附件不到模型侧），改走官方
  `sendText` envelope（六项验收全 PASS，见 PROTOCOL.md §6.6 与 CHANGELOG），
  App 已切换路径 ✅；**P1-3 语音输入** ✅ 主体完成（`ui/voice/VoiceInput.kt`，
  SpeechRecognizer → 输入草稿，两轮构建通过）。UI 交互待真机（并入 X-2）。
- **2026-09-30 四轮**：**E-1 权威角标完成**——`relay/SessionsIndexChannel.kt`
  （workspace 级 sessions-index 订阅，服务端 `pendingInteractionSummary`），
  `recomputeSessionPending` 改权威优先/事件流回退；端到端实测通过（触发 0→1、消解回落）；
  构建通过。剩余全部为硬件依赖项（X-2/P0-2）与需决策的 P2 系列。
- **⚠️ 2026-09-30 范围决策（用户拍板）**：**M4（P2-1）/M5（P2-2）移出开发计划**，
  VPS 不再采购——剩余工作仅 X-2/P0-2 真机验收与发版收官；方案文档中 M4/M5 章节仅作历史参考。
  另：按需池已完成桌面 Widget、附件流式化、会话搜索（详见 CHANGELOG 五/六轮）。
- **2026-09-30 五轮：v0.4.0-beta1 发版完成**（commit 1dde59e，tag + GitHub Release 附签名 APK，
  见 releases/tag/v0.4.0-beta1）。版本号 0.4.0-beta1/versionCode 5（M4/M5 已取消，弃用 mN 后缀）；
  签名与 v0.3.0-m3 同指纹，可覆盖升级（`tools/_apk_cert_fp.py` 核验）。CHANGELOG 七轮未发布段
  归版、README 双语 APK 文件名同步。
- **2026-09-30 真机验收进行中（X-2/P0-2）**：小米 15 Pro（haotian，serial 9f6241b4）已到位并授权调试。
  release APK HyperOS「USB 安装」确认弹窗需手机端人工点一次（首次安装被拒 INSTALL_FAILED_USER_RESTRICTED，
  重试 + 手机确认即 Success）。debug 变体新增 **DebugPairReceiver**（`app/src/debug/`，
  广播 `com.zcode.remote.action.DEBUG_PAIR` 注入 sid/hash/mid/name，免扫码/免 input text——
  粘贴路径 input text 会被中文 IME 打乱字符）。已验：App 启动、设置面板（协议线路/主题/保活入口）、
  首页骨架（设备卡/搜索框/断开）、错误态文案（连接失败/配对失效）渲染正常。
  **当前卡点：桌面端「移动端远程控制」面板未开 → auth AUTH_FAILED（probe 复现一致，
  已知 pair_status 语义，非 App 缺陷）；面板开启后继续会话流/审批/通知/锁屏验收。**
- **2026-09-30 真机验收（用户扫码配对后，中继链路全通）**：
  - **PASS**：扫码配对（相机路径，用户人工完成）→ 桥就绪；首页会话列表（24 会话、状态行
    「Zcode · glm · 已完成」、「当前」标记）；多机管理卡（其他设备/切换/移除）；设置面板；
    会话页会话流渲染（历史 turn 思考过程/工具行/详情）；输入栏 + 发送按钮 + 📎 附件按钮；
    **停止按钮**（turn running 时 canStop=true 显示，实测 ConvChannel 日志
    `control 更新 phase=running canStop=true stopState=stoppable`）。
  - **PASS（端到端）**：手机 UI 发送消息（sendText envelope，turn 激活 running）；手机附件全链路：
    📎 → 小米 SAF（com.android.fileexplorer）选 Download/attach-test.txt → chip 显示 → 发送 →
    **桌面端宿主日志实测 `attachmentBeginV4/ChunkV4/CommitV4` 全 OK**（21:31:34）。
  - **语音输入结论（HANDOVER 遗留项 2 的答案）**：小米 15 Pro 国行系统裁剪
    `com.google.android.googlequicksearchbox`（enabled=0，未提供可用 RecognitionService），
    小爱不实现系统 RecognitionService → `SpeechRecognizer.isRecognitionAvailable=false` →
    **按钮按设计不渲染，降级路径正确**。若需启用可安装提供 RecognitionService 的引擎。
  - **自动化限制**：微信输入法（com.tencent.wetype）把 `input text` 与 keyevent 逐键注入都转成
    拼音联想，UI 自动化输入中文/英文均失真（消息「还from怕还而且么」仍成功发送，端到端不受影响）；
    会话搜索过滤、主题切换人工补验即可。
  - **probe 侧观察**：手机配对成功后 probe auth 从 AUTH_FAILED 变为通过，但 auth_ack 返回的
    device_sid（d_UZTW…）与 setting.json/配对链接的 sid（d_Utf…）不同且 pair_status=waiting →
    probe 的 bootstrap 开桥卡住，setmode.py list/set 暂不可用（不阻塞验收：模式切换改由桌面端 UI 完成）。
    **待办**：弄清双 sid 差异来源（疑 credentials.json 9-28 旧 hash 与服务端注册的 device_sid 映射）。
- **2026-09-30 真机验收发现的问题与结论（三）**：
  - **App bug（待修，P2）**：**发送无超时兜底**——桥断开瞬间点发送，`sendText` RPC 挂死桥上
    等不到 ack，App「发送中」状态永久卡住（2026-09-30 21:5x 实测复现，App 重启才恢复）。
    修法建议：sendText/sendPrompt 加 RPC 超时（如 15s）→ 失败 flash + sending 复位；重连后丢弃挂起请求。
  - **真机键盘不弹（环境问题，非 App 缺陷）**：两层原因叠加——① 小米手环 9 蓝牙 HID 键盘
    （`Xiaomi Smart Band 9`，`Classes: KEYBOARD|EXTERNAL`）被系统识别为外接键盘抑制软键盘；
    ② 断开后 IME 状态机卡死（`mInputShown=true` 残留）+ 小米 AI 键盘（`com.xiaomi.type`，依赖
    `com.xiaomi.aicr:cognitionService`）接管 startInput 不渲染。切搜狗 + ime reset 后恢复。
    App 侧 `InsetsController show(ime())` 请求全程正确。
  - **审批链路 UI 验收受阻**：桌面端会话模式两次切换未生效（工具调用仍直执行，无审批推送）；
    改用 DebugApprovalReceiver 注入完成 App 侧审批 UI 验收（通知/卡片/角标），真实端到端放行
    待模式切换成功后补验（协议层已由 P0-1 `tools/_p01_async.py` 四项 PASS 覆盖）。
- ⚠️ **两条重要现状**（接手先读）：
  1. **release keystore 并未丢失（X-1 已结清，可直接发布）**：`toolchain/keys/zcode-remote.keystore`
     与 `app-android/keystore.properties` 都在本机，其证书 SHA-256 指纹
     `1D:46:E9:…:24:BE:55` 与已发布 v0.3.0-m3 的 APK 签名指纹**完全一致**
     （核验脚本 `tools/_apk_cert_fp.py`，只取 APK 尾部解析 Signing Block，无需整包）。
     本机 `assembleRelease` 通过且签名一致 → 新版本可直接覆盖升级已装设备。
     **旧文档中「toolchain 丢失、需回退 F:/AndroidTools」的说法作废**：本机 `toolchain/` 完整
     （jdk17 + gradle 8.7 + android-sdk platform-35/build-tools-35），`F:/AndroidTools` 不存在。
  2. **本机无法跑模拟器**：CPU 是兆芯 KX-7000（无 Intel/AMD 虚拟化扩展），emulator 的 qemu 静默退出；
     ZCode 自带 android-emulator 插件底层同为该 qemu，同样不可用。**App UI 层验收一律待真机或 Intel/AMD 机器**。
- **已知外部事件**：桌面端 rotate 凭据后旧 sid 失效；桌面端「移动端远程控制」面板关闭/超时后 `pair_status` 回到 `waiting`（面板打开期间才 matched）。App 侧均已适配。

## 2. 必读文档索引（按此顺序读）

| 顺序 | 文档 | 内容 |
|---|---|---|
| 1 | `PROTOCOL.md` | 中继协议总纲：端点/配对/握手(HMAC proof)/错误码/RPC 面/审批（§6.3 含双路实证与端到端验收记录） |
| 2 | `research/CONVERSATION-PROTOCOL.md` | 会话流协议：订阅四步序列、204 逻辑帧、快照/增量/行模型、翻页、ack 身份三元组校验（§8 是大坑） |
| 3 | `ZCode远程控制安卓APP方案.md` | 产品方案：竞品/路线决策/功能清单/里程碑/风险（⚠️ 其中的 M4/M5 已于 2026-09-30 用户决策取消，该文档仅作历史设计参考） |
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
10. **附件上传**（P1-3，PROTOCOL.md §6.6）：`zcode-agent` 通道四件套 `attachmentBeginV4/ChunkV4/CommitV4/AbortV4`，
    **begin 不需要 connectionId**；分片 384KiB（host 上限 512KiB），`dataBase64` 标准 base64，
    `checksum="sha256:<hex>"`；chunk 应答 `nextChunkIndex` 必须等于已发 index+1；
    commit 返回 `ref=zcode-artifact://…`，随 `sendPrompt.attachments=[{ref,fileName,mime,bytes}]` 发出。

## 6. 剩余任务（P0 → P2，含验收标准）

### 6.0 剩余工作总览（截至 2026-09-29，接手先看这张表）

**已完成（勿重做）**：M0 协议逆向、M1 App 骨架+配对+会话列表、M2 会话流+审批端到端、
M3 主体（多机/主题/线路/保活引导/历史翻页/分片重组）、**P0-1 发送+停止**、**P1-1 表单应答**、
**P1-2 多会话看板**、**P1-4 协议常量结清**、技术债 3 项（移除确认弹窗/debug 面板隔离/日志降级）。

**未完成项（全部）**：

| 编号 | 任务 | 状态 | 阻塞 / 前置 | 预估 |
|---|---|---|---|---|
| **X-1** | **恢复 release 签名 keystore** | ✅ **已结清** | 本机 keystore 与 v0.3.0-m3 发布 APK 同指纹（`tools/_apk_cert_fp.py`），发布链路可用 | — |
| **X-2** | **App UI 层验收**（P0-1 输入栏/停止、P1-1 表单卡、P1-2 角标与横幅、D-3 通知、P1-3 附件条） | 🚧 **进行中**（2026-09-30 真机到位，设置页/首页骨架/错误态已验 PASS；会话流类待桌面端面板开启） | 小米 15 Pro 已连；中继验收需桌面端面板在线 | 0.5d |
| X-3 | README 双语同步本轮功能 | ✅ 已完成 | — | — |
| **P0-2** | 真机验收（小米 15 Pro 日常可用） | 🚧 **进行中**（USB 安装确认、覆盖升级路径已验；auth 待面板开启） | 桌面端面板在线 + 人工配合（锁屏/杀后台场景） | 0.5d + 3d 观察 |
| **P1-3** | 文件上传 / 语音输入 | ✅ 附件全链路完成（发送侧 sendText envelope 实测六项 PASS，2026-09-30）；✅ 语音输入主体完成（SpeechRecognizer→草稿，构建通过）；UI 交互待真机（并入 X-2） | 真机验收 | — |
| **P2-1** | ~~M4 VPS 备用 Runner~~ | ❌ **已取消**（2026-09-30 用户决策：M4/M5 移出开发计划，VPS 不再采购；下方小节保留作历史参考） | — | — |
| **P2-2** | ~~M5 高级模式（自建 bridge + NaCl E2E + 自建中继）~~ | ❌ **已取消**（同上） | — | — |
| P2-3 | 其他 P2：文件/diff/Git 浏览、Wear OS 快捷审批、桌面 Widget、可选小米推送 | 🚧 **桌面 Widget ✅ 完成**（RemoteViews 零依赖，App 内推送更新，2026-09-30，渲染待真机）；其余未开始。注：文件浏览经 bundle 核实**无中继协议支持**（workspace-file 仅桌面端本地 MIME），且 M4/M5 已取消——**文件浏览在官方协议下无落点，就此搁置**；小米推送需开发者账号；Wear OS 可开发但验收卡硬件 | 按需 |
| D-1 | 会话流 `v4/conversation/frame` 二进制细节穷举 | ⬜ 未开始 | 需一次受控抓包（装 CA）；不影响当前功能 | 0.5d |
| D-2 | 「earlier-head」占位项与贴底索引偏移 | ✅ 已完成 | 贴底滚动漏算占位项（停在倒数第二行），已修 | — |
| D-3 | elicitation 通知栏快捷应答 | ✅ 已完成 | plan 批准/拒绝、单题单选选项按钮；复杂表单引导进 App | — |
| E-1 | 会话级**权威**角标（订阅 `sessions-index/<workspaceId>` topic 取 `pendingInteractionSummary`） | ✅ 2026-09-30 完成（`SessionsIndexChannel` + 权威优先合并；端到端实测：触发 userInputCount 0→1、消解回落，PROTOCOL.md §6.7） | — | — |

> 优先级建议：**X-2/P0-2（验收补齐，打通真机后一并做）→ 发新版内测 APK 收官**。
> D 类已清空；P1-3 全链路、E-1、桌面 Widget 均完成（2026-09-30）；
> **M4/M5 已取消（2026-09-30 用户决策）**，剩余全部为硬件依赖验收。

---

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

### P1-2 多会话并行看板 + 控制权切换提示（✅ 2026-09-29 完成）

- **已完成**：`AppViewModel.sessionPending`（每会话待处理条数，任务事件流覆盖所有会话 +
  订阅会话以会话流覆盖）→ HomeScreen 会话卡片「⏳ 待处理 N」角标 + errorContainer 醒目底色；
  「当前」标记（订阅中）与「PC 在看」标记（桌面端 activeTaskId）；`KICKED` 时首页顶部
  「控制权已在别处接管」横幅；`BridgeFrames.mobileViewStateUpdate()` 在接管（Paired）与
  会话切换时上报手机视图状态。
- **验收**：两条会话并行审批不串台（interactionId 精确匹配已保证）——协议层可验证；
  UI 层（角标/横幅渲染）同 P0-1/P1-1 待真机或 Intel/AMD 机器补验。
- **可选增强**：会话级权威角标来源 `pendingInteractionSummary{permissionCount, userInputCount}`
  在 conversation 的 `sessions-index/<workspaceId>` overlay 里（需订阅该 topic，当前用任务事件流推导）。

### P1-3 文件上传 / 语音输入（✅ 2026-09-30 全链路完成，UI 待真机）

- **已完成（附件上传）**：
  - **协议实机验证**（`tools/_p13_probe.py`）：四件套 `attachmentBeginV4 → ChunkV4 × N → CommitV4`
    全通；单分片与 900KiB 多分片（3 片）均成功；commit 返回 `ref = zcode-artifact://<sessionId>/<artifactId>`；
    **begin 不需要 connectionId**。常量/编码（20MiB 上限、512KiB host 片上限、官方 384KiB 分片、
    `sha256:` 校验和、`state=="committed"` 幂等）回写 PROTOCOL.md §6.6。
  - **Kotlin**：`ConversationChannel.uploadAttachment`（含进度回调、失败自动 abort）+ `AttachmentRef`。
  - **UI**：会话页 📎 → SAF 文件选择器 → 进度条 → 已上传 chip（可移除）；发送按钮支持"仅附件"发送。
- **已完成（发送侧，2026-09-30）**：对照实测发现 `sendPrompt` RPC 的 `attachments` 被 zod strip
  （201 accepted 但附件不到模型侧），**改走官方 `sendText` envelope**（`sendConversationCommandV4`，
  payload `{text, attachments}`）——`tools/_p13_send_verify.py` 六项验收全 PASS（含桌面端 assistant
  原样复述附件内容），`ConversationChannel.sendPrompt` 已切路径，PROTOCOL.md §6.6 已修正。
- **已完成（语音输入，2026-09-30）**：`ui/voice/VoiceInput.kt`——系统 `SpeechRecognizer` 转文字
  （官方 web 无语音功能，App 自研；零协议改动），🎤 按钮点击开始/提前结束，partial 实时上屏，
  最终文本追加输入草稿；RECORD_AUDIO 运行时权限按需请求；不可用设备按钮不渲染。
- **遗留**：
  1. UI 交互验收（🎤 按压反馈 / 附件 chip / 语音状态条）待真机或 Intel/AMD 机器（并入 X-2）。
  2. 真机验证设备端识别引擎可用性（小米 15 Pro 为小爱语音引擎）。
  3. ~~大文件整块读进内存~~ → **已流式化（2026-09-30）**：`uploadAttachment` 改
     `openStream + totalBytes` 签名，两遍流（sha256 → 分片），内存峰值一倍分片；
     选中后文件被移删会在上传时报错（行为变化见 CHANGELOG 六轮）。

### P1-4 协议【待验证】项补全（PROTOCOL.md §8 列表）

- `maxPhysicalFrameBytes` 阈值、PC 侧 meta 字段、心跳间隔分配。方法：`tools/probe.py` 受控实验 + host-index 搜索定位常量，结论回写 PROTOCOL.md。
- **预估**：0.5 天。

### P2-1 M4：VPS 备用 Runner（❌ 2026-09-30 用户决策取消）

> 以下为原设计记录，仅作历史参考，不再开发。取消原因：实用收益（PC 关机场景）与安全收益
> （E2E）对单人自用不迫切，不值得 VPS 持续成本 + bridge/双协议维护成本。

- **目标**：PC 关机时任务落到 VPS 执行（同一中继协议接入）。
- **要点**：VPS（¥10~40/月）部署 `zcode` CLI + ACP server，作为第二台"设备"生成配对；App 端设备列表天然支持（多机管理已就绪）；任务需基于 Git 仓库（提供 Git 同步引导页）；会话服务端可恢复。
- **验收**：PC 关机，手机新建任务并在 VPS 跑完，结果可见。
- **预估**：1.5 周（含 VPS 采购部署）。

### P2-2 M5：高级模式（❌ 2026-09-30 用户决策取消，原设计留档）

- **要点**：桌面端环境变量 `ZCODE_WEB_REMOTE_CONTROL_RELAY_WS_URL` / `ZCODE_WEB_REMOTE_CONTROL_URL` 可指到自建服务器（PROTOCOL §1）；自建中继（Node/Go，纯转发）+ NaCl 端到端加密层（App 与 bridge 共享密钥，中继只见密文）。
- **验收**：E2E 链路演示（中继抓包全密文）。
- **预估**：2~3 周。

### P2-3 其他 P2

文件/diff/Git 浏览（参考 CloudCLI）、Wear OS 快捷审批、桌面 Widget、可选小米推送通道。

- **桌面 Widget（✅ 2026-09-30）**：`widget/PendingWidgetProvider.kt` + `res/layout/widget_pending.xml` +
  `res/xml/widget_pending_info.xml`——待处理角标（RemoteViews 零依赖），App 内三处推送更新
  （计数变化 / 连接状态 / 断开），`updatePeriodMillis=0` 无轮询；点按打开 App。渲染样式待真机。
  文件/diff/Git 浏览经 bundle 核实无中继协议支持（workspace-file 仅桌面端本地 MIME），
  完整版等 M4/M5 自建通道（**已取消**）→ 文件浏览就此搁置；小米推送需开发者账号；Wear OS 可开发但验收卡硬件。

### 技术债（随手清）

**2026-09-29 已清**：
- ✅ ~~`ConversationScreen` 移除设备/危险操作加确认弹窗~~ → HomeScreen「移除设备」已加二次确认弹窗。
- ✅ ~~`HomeScreen` 的 rpcEvents 调试面板移到 debug 构建~~ → 已用 `BuildConfig.DEBUG` 隔离（`buildConfig=true`）。
- ✅ ~~logcat 日志量大（每个 delta 一条 Info）~~ → `AppViewModel: rpc event` 已降为 `Log.d`。

**2026-09-29 二轮已清**：
- ✅ ~~「earlier-head」占位项与贴底索引偏移~~ → `ConversationScreen` 贴底滚动改用显式 `headerCount`
  修正（原 `rows.lastIndex` 漏算占位项，停在倒数第二行）；0 行时会话有 `rows.isNotEmpty()` 守卫。
- ✅ ~~elicitation 仅能在会话页应答~~ → D-3 通知栏快捷应答已实现。

**仍待处理**：
- 会话流 `v4/conversation/frame` 二进制细节字段未穷举（不影响当前功能，未知 op 已有 Unknown 分支兜底）——见 D-1。

### P1-4 协议【待验证】项补全（✅ 2026-09-29 完成）

- 已从 host asar 与官方 web bundle 定位并回写 PROTOCOL.md §4.1：`maxPhysicalFrameBytes = 1 MiB`、
  `maxMessageBytes = 16 MiB`、`maxFragments = 64`、重组超时 30s、PC 端 auth meta 实参、两侧心跳
  默认 10s / ack 超时 30s。PROTOCOL.md §8 的【待验证】清单已逐项结清（仅剩 conversation frame
  二进制细节，见 D-1）。

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
