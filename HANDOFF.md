# 接力开发文档（HANDOFF）

> 本文件是**新会话/新 Agent 接手的第一入口**，按「项目概况 / 技术栈与基线 / 架构与拓扑 /
> 最近一轮变更 / 待办与接力指引 / 避坑约束」六大模块组织，做到不读全仓即可上手。
>
> **单一事实来源说明**：本仓库的历史档案命名沿用 **`HANDOVER.md`**（848 行，含逐轮交付、
> 真机验收证据、坑清单与历史状态块）。为满足「接力文档统一命名为 `HANDOFF.md`」的规范，
> 本文件作为**精简入口**存在，详细内容与全部历史证据请配合阅读
> 👉 [HANDOVER.md](HANDOVER.md)、[CHANGELOG.md](CHANGELOG.md)、[README.md](README.md)、[PROTOCOL.md](PROTOCOL.md)。
> 若两者出现口径冲突，以 `HANDOVER.md` 的**文首权威覆盖层**与本文件的「最近一轮变更」为准。

---

## 1. 项目概况与当前状态

- **定位**：ZCode 官方远程控制（`zcode.z.ai/remote`）的**非官方原生安卓增强客户端**
  （Kotlin + Jetpack Compose）。逆向复用官方中继与配对协议，提供更强的通知、锁屏审批、
  流式会话与移动端操控体验。
- **仓库**：<https://github.com/wjf1/zcode-remote-app>（owner：wjf1）
- **当前版本**：`0.5.0-beta55`（versionCode 67，T9 设置页「粘贴令牌」入口，单测声明 383 / 执行 421）。
- **上一发布版**：`0.5.0-beta54`（versionCode 66，T10 令牌失败分层 + T11 心跳单向失活）。
- **更早发布版**：`0.5.0-beta53`（versionCode 65，commit `7ec3e6c`，tag `v0.5.0-beta53`，
  Release 含签名 APK/SHA256SUMS/证书指纹/SBOM，prerelease；CI/CodeQL/Release 三工作流该提交全绿）。
- **✅ 推送已恢复（2026-10-25）**：GitHub 代理 7900 恢复监听，`v0.5.0-beta49/50/52/53` 各 tag
  与 master 已全部推送；Release 由 CI 发布链自动产出并经人工核验（标题与 tag subject 一致、
  正文与 CHANGELOG 对齐、prerelease、四资产齐全）。
- **✅ beta53（2026-10-26/27）**：**V2 Controller 通道适配——会话列表权威源切换（真机验收通过）**。
  ZCode ≥3.16 的 bootstrap 不再下发 `tasks[]`，会话列表改由 `controller/tasks-index` +
  `controller/workspaces` 双 topic 提供。新增 `relay/ControllerChannel.kt`（含无 IO 的
  `ControllerReducer` 纯状态机）、`storage/PairedDevice.ProtocolStack`（全仓唯一 passHash 判据）、
  `ui/screens/HomeEmptyState.kt`（空列表三态 + 6 张骨架卡）；**会话列表由原位 `MutableList`
  改为不可变快照 `StateFlow<List<SessionItem>>`**（消除后台 collector 写 / 主线程读的竞态）。
  真机首跑暴露并修复两处协议误判：Controller 通道实为 **`window-controller`**（非 zcode-agent）、
  **连接级握手每桥只认一个 clientId**（三通道统一共用 `RpcChannel.v4ClientId`）。
  复测：probe 落帧 50 帧（2 snapshot + 43 deltas）实测定案；T3 验收矩阵 ①②③⑤⑥ 通过
  （列表 120 条与快照 221-101 逐项对账、PC 新建任务 120→122 实时反映、杀进程重启恢复、
  V1 以 protocolStack 隔离）；会话页 612 行实时流式正常。单测声明 383 = 执行 421 全绿。
  详见 PROTOCOL.md §11.6/§11.6.7、CHANGELOG beta53 条目。
- **✅ beta52（2026-10-25）**：**V2 直连打通（真机走查③）**——推翻 beta51 的「terminal token
  仍收 challenge」误判（此前连的是 V1 端点 `/ws`）；**V2 中继端点是 `/api/v1/remote/ws2`**
  （3.16.3 桌面端代码实证）。App 原生「JWT→relay token→ws2 直连」：auth_init 无 role 一帧
  auth_ack matched（无 challenge、不需要 passHash）；他桥广播过滤 + V4 连接级握手前置 +
  bootstrap V2 `workspaces[]` 解析。真机实证 paired → bridge-ready → RPC 正常（桌面日志
  22:45:39 `paired`）。**凭据形态定案：登录回调 URL 不带 hash，凭据=localStorage JWT→token**
  （expires_in 60s 现取现用）。会话列表条目在 V2 用全新 Controller 通道
  （subscribeControllerV4），适配列入 §5 待办。详见 PROTOCOL.md §11.3/§11.5。
- **✅ beta51（2026-10-25）**：**ZCode 3.16.3 V2 认证配对适配（协议双栈）**——桌面端 3.16.3 将
  远程控制凭据体系 V1→V2 重构（QR 去 hash、删 passHash、账号登录 + relay token）。本版本完成
  双侧逆向（3.16.3 asar + 官方 Web 新 bundle）+ 中继实测 + App 双栈适配（V1 直连保留；V2 新码
  走 WebView 登录配对；JWT 加密存储；V2AccountApi + 设置页账号设备卡片；心跳 30s→10s）。
  **业务层经双侧 diff 确认零变化**。协议全记录见 PROTOCOL.md **§11**；真机统一验证清单 §11.5。
- **✅ beta50（2026-10-16）**：**附件上传流水线重构**（走查 5.1 清单**最后一项 #2**）——递归回调 →
  顺序 suspend 流水线 + 可定位（seekable）内容源（续传 O(1) 定位）+ 阻塞读下放 IO 线程；
  上传编排**首次进入 JVM 单测**（`AttachmentUploadPipelineTest` 11 项）。**5.1 清单至此全部闭环**。
- **✅ beta49（2026-10-16）**：**修复 beta48 的走查 #13「Markdown 图片兜底」为死代码**——兜底挂在了
  永不触发的 `components.image` 上，改挂**段落/引用块级**，并修掉「URL 误取整篇文档」与「180dp 空白占位」。
  CI 的 `instrumentation` job 已由红转绿（本机 API 35 模拟器复现并验证全量 `OK (24 tests)`）。
- **本仓库无 iOS / 桌面端**，仅 Android 原生客户端（`app-android/`）。
- **许可**：[MIT](LICENSE)；安全披露见 [SECURITY.md](SECURITY.md)。
- **部署形态**：侧滑安装的签名 Release APK（无 Play 分发）；自用 + 受控灰度。
- **主分支**：`master`。

## 2. 技术栈与运行基线

- **语言/框架**：Kotlin 2.0.20、Jetpack Compose（BOM 2024.09.02）、Material 3。
- **构建**：AGP 8.5.2 + Gradle 8.7（**仓库内 `toolchain/`，未用 wrapper**）、JDK 17、`compileSdk/targetSdk 35`、`minSdk 31`。
- **关键依赖约束（勿动）**：Kotlin 生态整体冻结在 2.0.20；`multiplatform-markdown-renderer 0.27.0`
  为其最后一个 Kotlin 2.0.x 编译版——**升级 Kotlin 前不得上调该库**（详见 HANDOVER 坑 14）。
- **核心命令**（仓库根或 `app-android/`）：

  ```bash
  # 构建 / 装机 / 抓日志（工具链自动推导，clone 到任意目录可用）
  ./build.sh            # 构建 debug APK
  ./build.sh install    # 构建 + 安装 + 启动
  ./build.sh log        # 抓取中继日志

  # 门禁（CI 等价；在 app-android/ 下执行，需 JAVA_HOME 指向 JDK 17）
  gradle testDebugUnitTest            # JVM 单测
  gradle lintDebug lintRelease        # 0 error 门禁
  gradle detekt                       # 静态分析（带 baseline）
  gradle ktlintCheck                  # 格式（0 违规门禁）
  gradle assembleDebug assembleRelease
  python tools/check_test_count.py --min 264   # 声明的 @Test 全部执行 + 数量下限
  ```

- **真机验收**：本机（兆芯 CPU 无虚拟化）**跑不了模拟器**，UI 一律真机（小米 15 Pro）验收；
  CI 侧由 ubuntu runner 自带 KVM 跑仪器化与 Macrobenchmark。

## 3. 核心架构与文件拓扑

```
app-android/app/src/main/java/com/zcode/remote/
├── relay/         外层中继 + 桥 RPC + 会话流（协议层）
│   RelayClient（状态机/心跳/重连）· NetworkGate（网络感知重连闸门）
│   RpcChannel（桥 RPC/分片/ack/代次键控）· ProtocolLimits（协议预算单一事实来源）
│   ConversationChannel（订阅/会话流/审批应答）· ConversationFrames（快照/增量解析 + RowStore）
│   RelayProtocol（握手/proof）· BridgeFrames（data 信封/bootstrap/任务事件）· Vql（服务端对象编码）
├── service/       ConnectionService（specialUse 前台服务保活）
│                  RemoteConnectionManager（进程级连接栈持有方 + 网络闸门登记）
│                  ConnectionScope（读写代理 facade）· ConnectionCoordinator（connect 编排 + 自愈调度）
├── repo/          ConversationRepository · ApprovalRepository · AttachmentUploader
│                  ModelConfigRepository · UpdateRepository · NotificationCoordinator
├── storage/       MultiDeviceStore + CredentialCrypto（Keystore AES-GCM）· TokenStore
│                  SettingsStore · SessionCacheStore（离线缓存/秒开）· DevicesCodec · PairedDevice(QrParser)
├── notify/        ApprovalNotifier / ElicitationNotifier / TerminalNotifier / NotificationPermission
├── ui/screens/    HomeScreen · ConversationScreen（+Timeline/TopBar/Composer/PendingCards/…）
│                  ApprovalsTab · SettingsTab · ScanScreen · KeepAliveGuideScreen · DiagnosticsSection
├── ui/components/ MarkdownView · DiffView · CollapsibleRow · SessionStatusPanel · TurnChangesCard …
├── ui/theme/      Theme（色板/Token）· Typography（ZCodeType）· Dimens
├── util/          ZLog（日志门）· LogRedactor（脱敏）· UiText（用户文案载体，i18n 地基）· Format · diag/（诊断环形缓冲）
└── widget/        PendingWidgetProvider（桌面待处理角标）
```

- **数据流**：配对（二维码/深链）→ `MultiDeviceStore` 加密存凭据 → `RelayClient` 建连并握手
  → `bootstrap` 取会话列表 → 开 workspace 桥（`RpcChannel`）→ 订阅会话（`ConversationChannel`）
  → 会话行经 `RowStore` 落到 `ConversationRepository.rows` → UI 派生渲染。
- **协议单一事实来源**：所有上界/常量集中在 `relay/ProtocolLimits.kt`；协议细节见 `PROTOCOL.md`
  与 `research/FRAME-CODEC.md`、`research/CONVERSATION-PROTOCOL.md`。

## 4. 最近一轮变更与交付成果
- **2026-11-14：对标 v3.18.1 Gap 全量落地（G1-G6 + P2，除真机验证外执行完毕）**——① 新增 7 个 relay 层纯逻辑模块：`V2RetryPolicy`（打散 1.5s / 退避 500ms×2ⁿ max 10s ±20% / KICKED 2~5s 自愈 + 60s 二连终态 / auth_ack 不清 KICKED 窗口）、`BridgeCapabilities`（`=== true` 严格口径，显式排除字符串 "true"——kotlinx `booleanOrNull` 语义与 zod 的差异已修）、`PendingCommandLedger`（24h TTL + ≤64 批 + 绝不自动重放）、`MobileTaskViewPublisher`（epoch/seq 单调 + send 失败保 dirty + 新桥重置 + matches 过滤迟到回调）、`UnreadStore`（unreadAt 时间戳 + expectedUnreadAt CAS）、`InputPredictionPolicy`（枚举校验 + 候选过滤后取前 3 + 防抖 700ms）、`FailureGuidance`（每终态 2 条「下一步」）；② 连接线：RelayClient 全面对齐官方重连/心跳口径（心跳 5s±1s 每轮重抽、watchdog 30s→15s——旧「官方 30s」系 v3.11 bundle 逆向误记已勘误）、KICKED 从直接终态改为官方自愈路径、ConversationChannel 接入 hello 能力位 / `queryConversationCommandsV4`（订阅恢复自动收账）/ `conversationInputPredictionV4`、RPC 暴露 `currentBridgeIdentity`、coordinator 分发 `mobile-task-view-request`、AppViewModel 接 publisher（进/离会话页 + epoch 轮换）；③ UI：HomeScreen 终态引导行、会话页正文/工具截断提示（G1 补拉方法不在桥上，如实标注）、ghost hint 行（点击整行采用）、CLI 重启丢弃输入的定向文案（`CommandFailureException.uiText`）；④ **两项纪律性降级（有据）**：P2④ suspended raw 帧准入不适用（原生 FGS 无浏览器 hidden/freeze 断流）、G2 单 owner 过滤暂缓（官方 V4 会话帧无 owner 信号、官方 UI 消费端零命中），文案已预留；⑤ 门禁：**536 项单测全绿 + ktlint 0 违规 + detekt 0 违规**（lint 后台跑）。真机验证（ghost hint 端到端 / 对账收尾 / epoch 轮换）待 USB，走查点见 §5。
- **2026-11-14：全面对标官方 v3.18.1（协议核查轮，无代码变更）**——三方材料对标（线上 `/remote/v4` 构建逆向 + 官方源码 v3.18.1 浅克隆 `_tmp/zcode-upstream`（含 `docs/web-remote-control/` 17 篇设计文档，后已 `fetch --unshallow` 补全历史）+ 本 App beta56），产出全面对标报告与 Gap 清单 G1-G6（落卡见 §5）。`tools/protocol_sync_check.py` 对 v3.18.1 全量 diff：11 契约文件仅 3 变化（`relay-v2-pairing.md` / `web-remote-control-architecture.md` 稳定性细化文档 + `webRemoteControlEntry.ts` 会话竞态修复——JWT+session revision 双重匹配防账号切换后旧响应污染新会话，App 侧 single-flight+401 终态已覆盖同类面，列为参考项），**wire 契约与 `zcode_type` 消息集合零漂移**。专项核验：`packages/shared/src/web-remote-control.ts`（不在 WATCHED）v3.17.2→v3.18.1 diff 为空，官方 bootstrap wire 早已不发 `displayStatus`（App 有任务事件+phase 运行时推导兜底，`BridgeFrames.kt:225/244`），pinned 走 V2 Controller `membership.pinned`（`BridgeFrames.kt:174` 设计已回避）。基线 `research/protocol-sync/snapshot.json` 已刷至 v3.18.1（HEAD `c5904686ea83`），人工复核通过。
- **2026-11-13：1.0 发布硬门①②双双收官**——① 出站大 RPC 物理分片（RpcChannel，官方等长切分算法经入站对拍 fixture 实证，outboundSlices 纯函数 7 项单测）；② **A-5 逻辑分帧重组**（WireReassembler，**从官方镜像 wire-reassembly.ts 逐规则转译**——推翻「需等真实样本」的旧判断，官方发送端已开源可直接核对；15 项单测逐规则覆盖，错误码 proto.frameAssembly* 全对齐）。ConversationChannel 的 fragment 分支从「显式失同步死循环」改为正常重组，**根治大会话同步不上问题**。真机走查出站分片待 USB（传 >1MiB 附件）。门禁：单测 495 全绿。
- **2026-11-12 发布 v0.5.0-beta56**（versionCode 68）——对标官方 v3.17.2 收官版本。含协议对齐（旧 SID 自动接续 / 终态 SSOT）+ 三项能力（置顶 / 未读 / 连接分段进度）+ 六项扩展（查找 / 刷新 / 工作区 / 平板双栏 / 终端 / 推送 schema）；真机走查通过并修出 3 处只在真机可见的布局缺陷。门禁：单测 473、ktlint 0、detekt 0、lint 0 error。
- **2026-11-12：对标后续 6 项功能全落地**（未发版）——会话内查找 / 下拉刷新 / 新建工作区 / 平板双栏 / 交互式终端 / 推送 schema 固化。工程门禁：JVM 单测 **473 全绿**、ktlint 0 违规、detekt 0 issue、lint **0 error**、assembleDebug 通过。**6 项均未真机走查**（USB 未连接），走查卡见 §5。
- **2026-11-09/10：全面对标官方 zai-org/ZCode v3.17.2，落地 P0 协议对齐 + P1 三项能力**——旧 SID 自动接续（含 DEVICE_OFFLINE 15s 有界宽限）、task-pin 置顶、会话未读标记、连接过程 4 段进度、官方协议契约 diff 跟踪脚本；`build.sh` Gradle 版本探测修复。**2026-11-10 真机实测（Xiaomi 15 Pro / USB）通过置顶与未读，并修出两处缺陷**：V2 下未读改走 Controller updatedAt（V1 事件流路径在 V2 无帧）、设置页主按钮恒「断开」无法重连。
- **2026-11-08：依赖升级阶段 1 落地**——按评估报告（docs 评审结论）执行最小风险批次：Gradle 8.13 / AGP 8.13.0 / compileSdk 36 / activity 1.13 / lifecycle 2.11 / uiautomator 2.4.1 / CI gradle-version 三处同步。阶段 2（Kotlin 2.1.x + BOM/OkHttp/CameraX）与阶段 3（等 detekt 2.0 stable）按报告排期。

> 完整逐轮记录见 [CHANGELOG.md](CHANGELOG.md) 与 [HANDOVER.md](HANDOVER.md) 文首覆盖层。

### 2026-11-09：对标官方 v3.17.2 的协议对齐与新能力（未发版）

**调研依据**：官方镜像 `git clone --depth 1` 到 `.tmp/zai-zcode-mirror`（main @ `a9fe401e`，v3.17.2）；
官方远控契约为 `docs/web-remote-control/relay-v2-pairing.md` + `web-remote-control-architecture.md` +
`packages/shared/src/web-remote-relay/{contract.ts,domain/webRemoteControlActions.ts}`。

| 交付 | 内容 |
|---|---|
| **旧 SID 自动接续**（P0） | `relay/SidContinuation.kt`（新，落点纯函数，对齐官方 `resolveAutoConnectSid`：旧 SID 在线→keep／同 MID 唯一其他在线 SID→switch／0 或 ≥2→none）+ `ConnectionCoordinator.trySidContinuation`（挂进程级 scope、JWT 查一次设备列表、`sidContinuationUsed` 有界、`Paired` 复位）+ `RelayClient` 的 `DEVICE_OFFLINE_GRACE_MS=15_000` 与 `FailureReason.DESKTOP_DISCONNECTED` |
| **task-pin 置顶**（P1） | `BridgeFrames.taskPinRequest`（schema 核验自官方 `validation.ts`）+ `SessionItem.pinned` + `ControllerReducer.visibleSessions` 置顶组优先 + 长按动作菜单/📌 徽标 + `hostOnPinAck` 回滚 |
| **会话未读标记**（P1） | `AppViewModel.unreadBySession` + `ConnectionHost.hostMarkUnread`；任务事件流（prompt_sent/resumed/streaming/updated/completed/error）标记、`openSession` 清除、断开清空；卡片 7dp 蓝点 + TalkBack 语义 |
| **连接分段进度**（P1） | `connectionPhaseIndex()` 纯函数映射官方 `STEP_INDEX`（0/1/2/3，Idle 与终态 -1）+ 空态区 4 段条（文案与官方中文一致） |
| **协议契约 diff 跟踪** | `tools/protocol_sync_check.py`（11 个契约文件 SHA-256 + `zcode_type` 集合 diff，基线 `research/protocol-sync/snapshot.json`，首跑 `exit=0`）+ `tools/README.md` 登记 |
| **构建修复** | `build.sh` 原硬编码 `toolchain/gradle-8.7`，升级后加载 AGP 8.13 直接「Plugin not found」（本机实证）；改为读 wrapper `distributionUrl` 版本 + `toolchain/gradle-*` 倒序回退 |

**P0 免改结论（有据）**：官方 2026-10-27「重放缓存 8→32 MiB」是**发送端本地未确认重放缓存**的
实现参数（`packages/client/src/webRemoteControlAcknowledgedRelayProtocolTypes.ts:13-16`，
`2 * maxMessageBytes`），**不进 wire**；协议常量（1 MiB 物理帧 / 16 MiB 单消息 / 64 片 / 30s 组装）
全部未变，App 的 `ProtocolLimits.kt` 精确对齐、且出站是 fire-and-forget 无重放缓存。

**新增测试**：`SidContinuationTest`（7）、`HomeConnectionPhasesTest`（4）、`ControllerChannelTest`
新增置顶排序与透传（2）。

### 2026-11-08：新增根目录 ARCHITECTURE.md（未发版，纯文档）

按「代码超 1 万行必维护架构文档」规范补齐五要素（鸟瞰 / codemap / 架构不变量与刻意不做 /
App↔ws2 中继↔宿主边界 / 横切关注点），139 行；SSOT 指向 `relay/ProtocolLimits.kt` 与
`PROTOCOL.md`。本机同步快进 7 个提交至远端最新（`7d221d0`）。

### 2026-11-01：Controller 通道 androidTest 覆盖补齐（未发版，纯加测试）

**背景**：模块 5「CI 覆盖缺口」项——Controller 通道「订阅 → 快照 → 增量 → resync」全链路
此前只靠 `tools/probe.py ctrl` + 真机人工走查，T5 空列表三态无仪器化取证。本轮闭环该缺口，
`relay/` 生产代码零改动。

| 交付 | 内容 |
|---|---|
| `ControllerReducerFullChainInstrumentedTest.kt`（新，androidTest/relay） | 4 项：① 订阅侧（V2 接管判据四组合 + `subscribeControllerV4` ack 双形态解析）；② 全链路场景（快照建基线 → 连续 deltas 推进不误报 → `logEpoch` 变化失同步 → 水位作 resync base → 恢复快照重建状态 → 增量回到 Applied 稳态）；③ `seq` 缺口失同步与恢复；④ tasks/workspaces 双 topic 独立水位互不干扰 |
| `HomeEmptyStateInstrumentedTest.kt`（新，androidTest/ui/screens） | 8 项：T5 三态判定 / 三态文案 / 过滤语义与 `HomeEmptyStateTest` 同口径复验；仪器化增强断言——真实 APK 资源表解析 `home_syncing_sessions` / `home_no_sessions` 非空且可区分（JVM 单测只有 R 常量，解析不了资源） |

**取证差异点**：reducer 用例以**默认构造**走生产 `ZLog`（→ `android.util.Log`）日志路径
（JVM 单测因无 android.util.Log 必须注入空日志器，仪器化环境补上这条真实路径的证据）。
两文件均为纯函数驱动（假帧序列断言状态迁移，不触网），不依赖 Espresso 注入 /
`createComposeRule`（规避 API 37 真机 Espresso 缺失），**CI API 35 emulator 的
`connectedDebugAndroidTest` 直接执行**——真机取证不再必要，常态回归靠 CI emulator；
`tools/probe.py ctrl` 保留作协议实测取证手段。

**门禁**：`assembleDebugAndroidTest` 编译通过（androidTest 源集经 AGP friend paths 直接
访问 main internal，无需反射）；`testDebugUnitTest` **431 全绿**（0 failures / 0 errors，
无增减，≥ CI 下限 410）；`ktlintCheck` / `detekt` 0 issue；`assembleDebug` 通过。
构建环境同 T8 轮：`toolchain/gradle-8.7` + JDK 17（`F:\Android\jdk-17`）。

### 2026-11-01：T8 登录 WebView 宿主独立 Activity 化（未发版）

**背景**：beta54 主线卡 T8——HyperOS + WebView 153 上 Compose AndroidView 内嵌 WebView
登录按钮点不动（beta51 起 5 轮渲染层修复未根治的结构性问题）。T9 粘贴令牌只是旁路，
本轮从结构上根治：WebView 宿主脱离 Compose 布局，竞态面（attach 到无效 surface /
窗口测量时序）不再存在。

| 交付 | 内容 |
|---|---|
| `WebViewActivity.kt`（新） | 传统 View 层级登录页（编程构建标题栏 + 提示 + WebView，无 layout XML）：登录契约原样迁移（URL `hash` 回调补齐凭据 / `localStorage zcodejwttoken` 持久化 / UA 去 `; wv` / Cookie 持久化 / 不拦截导航）；onPageFinished 与 jwt 异步求值的完成守护抽为 `LoginCompletionGuard`；onPause/onResume/destroy 跟随 Activity 转发 + Cookie flush |
| `WebViewLogin.kt`（新） | 纯逻辑层：`landingUrl` / `parseCallbackParams` / `augmentFromUrl`（android.net.Uri → java.net 等价实现，JVM 可测）+ `LoginCompletionGuard` 状态机 |
| `MainActivity.kt` | `pendingLogin` 改瞬时启动标记：`StartActivityForResult` launcher 启动 WebViewActivity，RESULT_OK 回传设备 extras 后 `vm.pair`（Toast 文案不变）；BackHandler 移除 pendingLogin 分支（返回键由登录 Activity 自行处理）；触发点不变（扫码 `NeedsLogin` / 深链） |
| `AndroidManifest.xml` | 注册 `.WebViewActivity`（`exported=false`，沿用应用主题 `Theme.ZCodeRemote`） |
| `res/drawable/ic_close.xml`（新） | 传统 View 标题栏关闭按钮图标（原用 material-icons Compose 版） |
| 删除 `ui/screens/LoginWebViewDialog.kt` | 唯一调用点（MainActivity）已迁移，全源码 grep 复核无代码死引用 |

**登录行为契约不变**：JWT 校验链路（`normalizePastedJwt` / `pastedJwtRejection`，T9）、
Debug 构建 `DebugPairReceiver --es jwt` 注入、relay/ 会话流与 Controller 通道均未触碰；
结果回传为 `RESULT_OK` + 设备字段 extras（`PairedDevice` 非 @Serializable，逐字段
Bundle 传输），取消路径 RESULT_CANCELED。进程被杀重建：intent extras 恢复 + Cookie 续登。

**测试**：新增 `WebViewLoginTest` 10 项（路由构造 3 / 回调解析 5——含字面 `+` 凭据保真 /
完成守护 2）。单测 **声明 393 / 执行 431** 全绿（421 → 431）。

**门禁**：`ktlintFormat` + `ktlintCheck` 0 违规、`detekt` 0 issue、
`assembleDebug` 通过（本轮未跑 assembleRelease / lintDebug / 仪器化测试——本机无设备）。
构建环境同工程债轮：`toolchain/gradle-8.7` + JDK 17（`F:\Android\jdk-17`）+ 阿里云镜像
init script（`-I toolchain/mirror-init.gradle`）。

**真机验收待做（发版前必做）**：小米 15 Pro（HyperOS + WebView 153）扫码 → 登录 → 配对
全链路走查：登录页可见可点击、OAuth 跳转正常、登录完成自动回连、切后台回来不白屏。
通过后可在下方「App 内 WebView 登录卡死」条目闭环。

### 2026-10-31：工程债清理轮（未发版）

**背景**：外部分析遗留的三组工程债——tools/ 一次性脚本堆积、detekt 基线陈旧条目、
P0 附件上传重构验收收尾——本轮集中清偿。无功能性变更，不发版、不打 tag。

| 交付 | 内容 |
|---|---|
| `tools/`（11 文件删除） | 删除 `_16k_check` / `_apk_cert_fp` / `_dump_frame` / `_e1_verify` / `_e2e_send` / `_e2e_tap_approve` / `_p01_async` / `_p11_probe` / `_p11_verify` / `_p13_probe` / `_p13_send_verify`，验收证据归档 git 历史（引入提交 `f4af4c6`），追溯：`git log --follow -- tools/_<name>.py` |
| `tools/README.md` | 索引重写：移除已删条目、登记 git 追溯方式；仍在工作区的三个历史脚本（`t0_analyze` / `render_screenshots` / `wsclient`）单列表述；新增约定「一次性取证脚本不再入工作区」 |
| `tools/probe.py` / `research/CONVERSATION-PROTOCOL.md` | `_dump_frame` 活引用改指 git 历史（含 `git show f4af4c6:...` 取回命令） |
| `ConversationChannel.kt` / `SessionsIndexChannel.kt` | 注释里的「实证（tools/_xxx.py）」改为注明脚本已归档（注释级改动，无逻辑变更） |
| `app-android/detekt-baseline.xml` | 基线清理 **90 → 86**：`detektBaseline` 重新生成，删 4 条陈旧条目（均为 beta50 重构后签名漂移），纯删除零新增 |

**P0 附件上传流水线复验**：走查 5.1 #2 已于 **beta50 完成**（递归回调 → 顺序 suspend +
`openAt(offset)` 定位流），本轮**无代码改动**，仅核对测试覆盖（`AttachmentUploadPipelineTest`
11 项：分片顺序 / 续传不重传已确认片 / 取消传播）并重跑门禁。

**门禁**：`detekt` / `ktlintCheck` 0 issue；`:app:testDebugUnitTest` **421 用例全绿**；
`check_test_count.py --min 410` 声明/执行集合比对通过（421 ≥ 410）。
构建环境备注：本机无 gradle，本轮经 `toolchain/gradle-8.7`（gitignore 内）+ JDK 17
（`F:\Android\jdk-17`）+ 阿里云镜像 init script（`toolchain/mirror-init.gradle`，-I 注入，
maven.google.com 直连不通）完成全部构建验证；未跑 assembleRelease / lintRelease / 仪器化测试。

### v0.5.0-beta55：设置页「粘贴令牌」入口（T9，2026-10-28）

**背景**：App 内 WebView 登录在 HyperOS + WebView 153 上点「登录」无响应（beta51 起记录、
5 轮未根治）。用户在配对阶段就彻底卡死——连不上后面的功能全不可用。修复方向（WebView 渲染
竞态）风险高，故先给旁路。

| 交付 | 内容 |
|---|---|
| `ui/screens/SettingsTab.kt` | 「ZCode 账号」卡片未登录态新增 `PasteTokenEntry`：折叠按钮 → 说明 + 多行输入 + 保存/取消；保存后切已登录态、清设备缓存、Toast 提示 |
| `ui/screens/SettingsTab.kt` | `normalizePastedJwt` 纯函数：去 BOM / trim / 剥成对引号 / 剥 `Bearer ` / 去换行空格。**刻意不按 `a.b.c` 分段校验**（JWT 形态由服务端定义） |
| `ui/screens/SettingsTab.kt` | `pastedJwtRejection` 纯函数：只判非空 + 长度 ≥ 16；`isQuoted` 独立函数压 detekt ComplexCondition |
| `res/values/strings.xml` | 新增 8 条 `account_token_paste_*` |

**顺带**：`AccountDevicesSection` 内两处 `TokenStore(context)` 收敛为一个 `remember` 实例。

**测试**：新增 `PastedJwtTest`（9 项）；单测 **声明 383 / 执行 421** 全绿；
红-绿反证：下限 16 临时改 5 → `tooShort_isRejected` 立即失败（已恢复）。

**未做**：T8 登录迁移独立 `WebViewActivity`（架构改动 + 需真机验收，单独一轮）。

### v0.5.0-beta54：连接层韧性（T10 令牌失败分层 + T11 心跳单向失活，2026-10-28）

**背景**：beta53 收官后 beta54 主线四张卡（T8–T11）中，T10/T11 属「连接层可靠性」——
两个都是**用户能直接感知的静默失效**：登录态过期后界面无限重连转圈（不知道要重新登录）、
WS 半死时状态停在「已连接」却什么都收不到（不自动重连）。

| 卡 | 交付 | 关键文件 |
|---|---|---|
| T10 | `fetchRelayToken` 对 **401/403 抛具名 `TokenAuthException`**（原先 `check(code==200)` 压成通用 `IllegalStateException("HTTP 401")`）；新增 **`fetchRelayTokenSingleFlight`**：按 `baseUrl\|role\|jwt` 键控、`async(start=LAZY)` + `ConcurrentHashMap` 共享同一次网络往返，**失败不进缓存**、jwt 变更不复用旧 in-flight | `relay/V2AccountApi.kt` |
| T10 | `acquireV2Token` 改走单飞；新增纯函数 **`isAuthFailure`** 分类：401/403 与业务码拒绝（`api code=`）→ `AUTH_FAILED`（终态不重连，指引重新登录），其余（DNS/超时/连接重置）→ `NETWORK`（值得重连）。抽 `reportTokenFailure` 压住 detekt 圈复杂度 | `relay/RelayClient.kt` |
| T11 | `startHeartbeat` 每轮标记「首次发出但未被 ack 覆盖」的时刻；`pair_status_ack`/`auth_ack` 调 `markHeartbeatAcked()` 清零；超过 **`HEARTBEAT_STALE_MS`=30s**（3 个心跳周期，对齐 PROTOCOL §4.1 服务端 ack 超时口径，不抢在服务端判定前误判）→ `fail(NETWORK)` 触发退避重连。判据抽纯函数 `isHeartbeatStale`；`startHeartbeat` 入口清零防继承残留 | `relay/RelayClient.kt` |

**测试与门禁**：新增 `V2TokenAuthTest`（4 项）、`HeartbeatStaleTest`（6 项，含阈值边界）。
单测 **声明 374 / 实际执行 412** 全绿；`ktlintCheck` 0 违规、`detekt` 0 issue、
`lintDebug` 0 error、`assembleDebug` + `assembleRelease` 均通过。
**红-绿反证**：把 `isHeartbeatStale` 的 `>=` 临时改为 `>` 后 `exactlyAtThreshold_isStale` 立即失败，
证明用例真能捕获缺陷（已恢复）。

**未做（同属 beta54）**：T8 登录迁移独立 `WebViewActivity`、T9 设置页「粘贴令牌」入口。

### v0.5.0-beta53：V2 Controller 通道适配（真机验收通过，2026-10-26/27）

**背景**：beta52 打通 V2 直连后会话列表仍为空——V2 host 的 `bootstrap.result` 只有
`workspaces[] / mobileViewState / desktopAppVersion / windowControlSessionId`，**没有 `tasks[]`**。
桌面端 web 端订阅 `controller/tasks-index` 与 `controller/workspaces` 两个 topic，App 需同通道。

| 卡 | 交付 |
|---|---|
| T1 | `tools/probe.py` 新增 `ctrl` 子命令：JWT → relay token → ws2 → V4 握手 → 订阅双 topic → 落帧 `_tmp/ctrl_frames.json`（实测 50 帧 = 2 snapshot + 43 deltas） |
| T2 | `relay/ControllerChannel.kt`（新）：订阅编排（hello → initialize → listen → 两次 subscribe）+ `ResyncThrottle` 节流 + 退订重订兜底；`ControllerReducer` 无 IO 纯状态机（快照全量替换 / upserted 覆盖 / removed 删除 / 未知 op 宽容） |
| T3 | `service/ConnectionCoordinator.kt`：`ctrl.onEvent` 与 conv/sidx/wcfg 并列；`ctrl.tasks` → `visibleSessions` → 列表 → 离线缓存。`AppViewModel`/`ConnectionHost` 的 `hostSessions` 改**不可变快照**（`hostReplaceSessions`/`hostUpdateSessions`），消除写读竞态 |
| T4 | `storage/PairedDevice.kt` 新增 `ProtocolStack`（V1/V2）+ `protocolStack`/`usesControllerChannel`，作为**全仓唯一 passHash 判据** |
| T5 | `ui/screens/HomeEmptyState.kt`（新）：空列表三态（未连接 / 已连接未同步 / 已同步且为空）+ 6 张骨架卡 + 引导 |
| T6 | 13 处 UI 中文硬编码资源化（账号卡片、设备列表、自定义中继、登录配对页） |
| — | `util/diag/DiagnosticsRing.kt` 新增 `CONTROLLER_LIST` 诊断码；`PROTOCOL.md` §11.6 升级为完整字段表 |

**真机首跑暴露并修复的两处协议误判**（值得记住）：

1. **Controller 通道在 `window-controller`**，不在 `zcode-agent` / `zcode-task`（后两者均
   `Method not found`）。官方 web 端 `windowControllerTaskListRegistry` 同名佐证。
2. **连接级握手每桥只认一个 clientId**：三通道各自 initialize 互踢（`fault.connection.clientChanged`），
   会话通道被拒后中止订阅 → 会话页「同步失败」。修复 = 三通道统一共用 `RpcChannel.v4ClientId`
   （每连接一份）+ 容忍 clientChanged。**该修复同时保障审批命令信封的 clientId 与连接绑定一致。**
3. resync 形状对齐官方 web 端：`{subscriptionId, base:{logEpoch, seq}, forceSnapshot:true}`；
   失同步判定改为 deltas 帧 `fromSeq !== 上次 toSeq`（缺口与回退都算），快照帧不检查。

**验收矩阵**：① 列表对账（快照 221 条 / archived 101 → 可见 120 = 手机「全部 (120)」，
running 3 = 「运行中 (3)」）② PC→手机增量 120→122 实时 ③ 运行态实时 ⑤ 杀进程重启恢复
⑥ V1 路径以 protocolStack 隔离零改动。④（PC 重启 logEpoch 重同步）未实测，见 §5 遗留。

### v0.5.0-beta52：V2 直连打通（真机走查③，2026-10-25）

**核心纠偏**：beta51 记录的「terminal token 仍收 challenge」是**连错端点**——`wss://zcode.z.ai/ws`
是 V1 中继；**V2 中继端点是 `wss://<origin>/api/v1/remote/ws2`**（3.16.3 桌面端代码
`relayWsUrl: ${origin}/api/v1/remote/ws2` 实证）。据此 App 原生 V2 直连全链路打通。

| 模块 | 交付 |
|---|---|
| 协议实证 | PROTOCOL.md §11.2/§11.3 全面改写：登录回调 URL 无 hash（凭据=localStorage JWT→token，expires_in 60s）；ws2 握手帧样本（auth_init 无 role/秒级 ts → auth_ack 直达回 terminal_sid）；V2 zod strictObject schema（心跳无 device_sid）；`_tmp/v2_terminal_test.py` 参考实现；官方 Web bundle 按 app_version 版本化（`/remote/v4/<ver>/assets/`） |
| `relay/RelayProtocol.kt` | `authInitV2`（无 role、client_ts 秒）、`heartbeatV2`（无 device_sid）、`v2RelayWsUrl`（origin→ws2） |
| `relay/RelayClient.kt` | V2 分支：`jwtProvider`（构造注入）→ `acquireV2Token` → `?token=&mid=` 连 ws2；V2 收 challenge=PROTOCOL_MISMATCH；无 JWT 指引登录 |
| `relay/RpcChannel.kt` | **他桥广播过滤**：桌面端把 `bridge-degraded{terminal-owner-ended}`（官方 web 关页后的旧桥）周期广播给所有 terminal，按 bridgeSessionId 过滤（`BridgeState.Opening` 携带 sid） |
| `relay/SessionsIndexChannel.kt` | 订阅前补 V4 连接级握手（hello→initialize；V2 host 强制，实测 `fault.connection.handshakeRequired`） |
| `service/ConnectionCoordinator.kt` | 注入 jwtProvider；`activeWorkspaceKeyOf` 支持 V2 `result.workspaces[]`（workspaceKey=workspacePath） |
| `relay/V2AccountApi.kt` | `Envelope.data` 改 `JsonElement`（token 接口 data 为对象，String 声明解析失败——真机实测） |
| `debug/DebugPairReceiver.kt` | `--es jwt` 注入（真机 V2 验收） |
| 单测 | `V2HandshakeTest` 4 项；detekt 复杂度返工（connect/onBridgeFrame/ScanScreen/AccountDevicesSection 抽函数） |

- **真机验证**（小米/HyperOS，无线调试）：走查②手机系统浏览器登录→URL 无 hash→官方 web 三次
  paired；走查③ App 原生 V2 直连：`ws open v2=true` → auth_ack matched（桌面日志 22:45:39
  paired）→ bootstrap → bridge-ready → sessions-index subscribed。门禁：单测+detekt+ktlint+lint 全绿。
- **边界**：V2 会话列表条目走新 Controller 通道（`subscribeControllerV4`，`task.upserted` 增量），
  App 首页列表暂空（通道已通、数据源适配列入 §5 待办）；App 内 WebView 登录在 HyperOS+WebView 153
  卡死（独立 bug；系统浏览器登录可完成流程，Debug 构建可 `--es jwt` 注入验收）。

### v0.5.0-beta51：ZCode 3.16.3 V2 认证配对适配（协议双栈，2026-10-25）

**背景**：桌面端 3.16.3 把远程控制凭据体系 V1→V2 重构（新 QR 不带 hash、PC 删除 passHash、
账号登录 + `/api/v1/remote/token`），旧配对链路在 3.16.3 上断裂。**业务层（§5/§6）双侧 diff
确认零变化**——本版本只动认证配对层。

| 模块 | 交付 |
|---|---|
| 协议逆向 | PROTOCOL.md **§11**：V2 全链路（设备注册 / token / QR 变更 / BigModel 互信登录跳转）+ 中继实测表（terminal 带/不带 token 均收 challenge；token 仅豁免 device 角色；device list 可用）。3.16.3 asar 提取件在 `_tmp/asar-3.16.3/`，官方 Web 新 bundle 在 `_tmp/web-*.js` |
| `storage/PairedDevice.kt` | `passHash` 改可空；`QrParser.parseResult()` 双路（`Ready`/`NeedsLogin`）；`DevicesCodec` 存储兼容 |
| `relay/RelayClient.kt` | challenge 无凭据 → `Failed(AUTH_FAILED)` 明确指引登录配对（不再算错误 proof）；心跳 30s→**10s** 对齐官方 §4.1 |
| `ui/screens/LoginWebViewDialog.kt`（新增） | WebView 登录配对：跨域跳转全放行（BigModel 互信）、Cookie/localStorage 持久化；完成条件双兜底（回调 URL `hash=` / localStorage `zcodejwttoken`） |
| `storage/TokenStore.kt` | `zcode_jwt_enc`（复用 CredentialCrypto AES-256-GCM + Keystore） |
| `relay/V2AccountApi.kt`（新增） | `fetchRelayToken` / `listDevices`（`{code,msg,data}` 包络；data 兼容数组与 `{devices}` 两形态） |
| `ui/screens/SettingsTab.kt` | 「ZCode 账号（新版协议）」卡片：登录态 + 账号设备列表（name/platform/version/online） |
| `tools/probe.py` | 凭据双栈（V2 设置项 + mid 自动推导 + `zcodejwttoken` 解密）；新命令 `token` / `devices` / `loginurl`（**本机实测通过**：token 200、列出 2 台设备） |

- **接线**：相机扫码 / 手输链接 / 深链三条配对入口全部分流（V1→确认弹窗直连；V2→登录配对）。
- **验证状态**：JVM 编译 + 存量单测通过；协议层本机实测通过。**真机统一走查未做**
  （清单见 PROTOCOL.md §11.5：WebView 登录全流程 → 回调凭据形态 → 原生直连 → 账号卡片渲染）。

### v0.5.0-beta50：附件上传流水线重构（走查 5.1 清单最后一项 #2，2026-10-16）

**5.1 清单至此全部闭环**（#1–#15 全部处理完毕）。

| 问题（旧实现） | 修法 |
|---|---|
| 递归回调金字塔（`uploadChunks`→`next()`→`rpc.call{}→next()`），埋在依赖 Looper 的类里、**无法单测** | 编排外提到 `relay/AttachmentUploadPipeline.kt`：四步流程改**顺序 `while` + `suspend`**；依赖（内容源 / RPC / IO 调度器 / 日志）全部注入 → 纯逻辑，可 JVM 单测 |
| 续传 `openStream()` + `skip(N*chunkBytes)` = **O(offset) IO** | `AttachmentUploader.openAttachmentStream`：优先 `openFileDescriptor` + `FileChannel.position()` 做 **O(1) 定位**；不可 seek 时回退 `openInputStream + skip` |
| 整文件哈希 + 逐片读都在**主线程**（调用方是 `viewModelScope`） | 阻塞读全部 `withContext(ioDispatcher)`；RPC 与进度回调仍在调用方上下文（Main），UI 状态写入线程不变 |
| 取消靠独立 `aborted` 标记 | `UploadHandle.abort()` = 取消协程；流水线在 `CancellationException` 路径以 `NonCancellable` **补发 `attachmentAbortV4`**（与旧 `abort()` 等价） |

- **行为等价（刻意保持）**：帧序列/参数、幂等短路（`state=="committed"` 直接复用 ref）、
  进度口径（`index*chunkBytes` 封顶 total）、失败文案、以及「begin 被拒/读取失败**不发** abort；
  分片失败/非法进度**发** abort」的细分语义。
- **日志出口改为注入式** `UploadLogger`：沿用「被 JVM 单测覆盖的纯逻辑类零 `ZLog`」约定
  （`ZLog` 会落 `android.util.Log`，无 Robolectric 时抛 "not mocked"）；生产侧由
  `ConversationChannel` 的 `init` 注入 `ZLog` 实现（带 `LogRedactor.pathLabel` 脱敏）。
- **验证**：JVM **358/358**（+11 `AttachmentUploadPipelineTest`：四步顺序与分片字节逐字节比对、
  begin 参数、幂等短路不传分片、**续传按绝对偏移开流且只传剩余分片**、进度封顶、分片失败/ack 异常
  → 发 abort 不 commit、短读可读失败、非法进度不入分片阶段、**取消补发 abort**）；
  `ktlintCheck` 0 违规；`detekt` 通过；lint 双变体 0 error；双装配通过。
- **⚠️ 真机验收为「协议层」而非「SAF 全链路」**：MIUI 的 SAF 文件选择器要求**指纹解锁**
  （`com.miui.applicationlock.AppLockActivity`，adb 走查进不去），故无法用真人点选文件跑通。
  改用 **debug-only 探针 `AttachUploadProbeActivity`**：从**进程内真实连接栈**
  （`RemoteConnectionManager.conversation`）发起真上传，只有**内容源**用内存字节（不经 SAF），
  **协议四步全程真实 RPC**。实测（小米 15 Pro / Android 17 / API 37，PC host 在线）：
  - 1.2 MB（4 片）：`chunk=4` → 4 次进度 → `附件已提交 ref=zcode-artifact://…` 用时 **2022 ms**。
  - 2.5 MB（7 片）：7 次进度（393216…2500000 封顶）→ `附件已提交 ref=…` 用时 **4189 ms**，无 FATAL。
  - **线程契约实测**：探针传 Main scope 时，progress/onResult 全在主线程（PID=TID）；
    传 `Dispatchers.Default` 时回调落在 IO 线程（探针初版即因此段 `Toast` 抛
    「Can't toast on a thread that has not called Looper.prepare()」——**该崩溃在探针里、不在生产代码**；
    生产 `AttachmentUploader` 传 `viewModelScope`（Main.immediate），回调天然在主线程）。
  - **未覆盖**：SAF 真开流（`openFileDescriptor`/`FileChannel.position` O(1) 定位路径）未在真机验证
    ——需要指纹解锁的真人操作；该路径有 JVM 单测覆盖「按偏移开流」的编排语义，IO 实现本身待补真机。

### v0.5.0-beta49：修复 beta48 的「图片兜底」死代码（2026-10-16）

**根因**：走查 #13 把兜底挂在 `markdownComponents(image = { … })`，但库
（`multiplatform-markdown-renderer 0.27.0`）的 `Markdown.kt` **只对 `MARKDOWN_FILE` 顶层子节点**
调 `handleElement`，`PARAGRAPH` 被 `components.paragraph` 消费后**不再下钻**；用库自带解析器实测
`![alt](url)` 恒为 `MARKDOWN_FILE > PARAGRAPH > IMAGE` → **`components.image` 永不触发**，兜底是死代码。

| 缺陷 | 修法 |
|---|---|
| 兜底挂错回调 | 改在 **`paragraph` / `blockQuote`** 组件内注入（先渲染正文，再对本子树内图片逐个渲染兜底行） |
| URL 误取整篇文档 | 新增 `imageUrlsIn(node, content)`：用 `org.intellij.markdown` 公开 API 自遍历 `IMAGE→INLINE_LINK→LINK_DESTINATION`，按 span 切片取 URL |
| 180dp 空白占位 | 新增 `ZeroSizeImageTransformer`（`transform` 恒 null → **零出站请求不变**；`placeholderConfig` 归零） |

- **过程教训（务必记住）**：#12–#15 合并时**前一次 CI 被并发取消**，提交信息只记「androidTest 编译通过」，
  而本机跑不了模拟器 → 仪器化用例**从未真正执行**。直到发布提交 `ca7dbb6` 触发 CI 才首次失败。
  **「编译通过」≠「测试通过」；新增仪器化断言必须在本机模拟器（`apkrev35`）或 CI 真跑一次。**
- **验证**：仪器化**全量 OK (24 tests)**（本机 API 35 模拟器，CI 同路径）；JVM 单测 **347/347**（+8 `MarkdownImageUrlsTest`）；
  `ktlintCheck` 0 违规；`detekt` 通过；`lintDebug`/`lintRelease` 0 error；双装配通过；
  APK 实测 `versionCode=61 / versionName=0.5.0-beta49`。
- **新增测试资产**：`MarkdownImageUrlsTest`（JVM，钉死 `IMAGE.parent==PARAGRAPH` 根因）、
  `MarkdownImageFallbackInstrumentedTest`（4 项，**不依赖 Espresso**，`runComposeUiTest`）、
  `MarkdownProbeActivity`（仅 debug 源集的真机渲染探针）。
- **重要环境事实（本轮实测确认）**：本机 **AEHD 虚拟机监控程序可跑 API 35 模拟器**（`apkrev35`，`emulator-5554`），
  仪器化用例可在本地通过 CI 同路径（`am instrument`）真跑——**不必只依赖 CI**；API 37 真机则受
  Espresso `InputManager` 移除限制（`createComposeRule` 与 `runComposeUiTest` 均失败，后者因内部注册
  `EspressoLink`）。

### v0.5.0-beta48：走查清单 P2 剩余项闭环（2026-10-16，已发布）

**4 项体验修复**，全部来自 §5.1 第三方走查清单，**5.1 清单至此仅剩 #2**。

| 项 | 提交 | 内容 |
|---|---|---|
| #12 无障碍 | `8725bad` | 会话卡补 `mergeDescendants` 语义（「会话 {标题}，{状态}，长按可删除」）、`StatusDot` 走 `clearAndSetSemantics`、设置入口补 `Role.Button` |
| #13 Markdown 图片兜底 | `c7862fd` | 图片占位由灰字死胡同改为「复制链接」（所有图片）+「打开」（仅 `http(s)`，走 `isAllowedLinkScheme` 白名单）。**安全决策不变**（仍 `NoOpImageTransformer`，零出站请求） |
| #14 会话流日期分隔 | `2a9efdd` | 按本地时区**日历日**插入「今天/昨天/年月日」分隔行。**顺带修一真缺陷**：原拟用 `YEAR*366+DAY_OF_YEAR` 比较日历日，平/闰年起点不同会跨年错位 |
| #15 布局回归断言 | `d906da0` | `InputBar` 加 `testTag("conv-inputbar")`；新增「1.3x 字体 + 长草稿下输入栏高度有界 + 会话流未被挤压」断言 |

- **两项原判被代码实证推翻**：#13 原判「库 API 不可达」→ 不成立（`MarkdownComponentModel.getContent()`
  即图片 URL，不可达的是 alt；#14 原判「协议无绝对时间戳」→ 用错字段（`ConversationRow.createdAt` 是行级绝对时间戳）。
- **验证**：JVM 单测 **339/339**（+10 `FormatDateDividerTest`）；`ktlintCheck` 0 违规；`detekt` 通过；
  `lintDebug`/`lintRelease` 0 error；`assembleDebug`/`assembleRelease`/`assembleDebugAndroidTest` 通过；
  APK 实测 `versionCode=60 / versionName=0.5.0-beta48`。
- **#13 的仪器化断言**（`MarkdownRenderTest` +2 项）依赖 `createComposeRule`，只在 CI 的 API 35 emulator 执行
  （API 37 真机受 Espresso 限制，见 §5「已知测试基建限制」）。

### v0.5.0-beta47：走查修复第二至第八批 + 国际化资源化 6 批（2026-10-16，已发布）

**两件事**：① 走查清单修复 10 项；② i18n 资源化 6 批完成。**用户可见文案零变化**。

**① 走查修复（第二至第八批）**

| 批次 | 提交 | 内容 |
|---|---|---|
| 核实轮 | `e3b5dc1` | `!!` 隐患（读循环改不可变快照）、重连抖动（`jitteredBackoffMs`）、旧明文缓存异步回写迁移 |
| 第二 | `639ecc8` | `HttpClientProvider`（OkHttp 连接池/调度器复用）+ `network_security_config.xml` 显式化 |
| 第三 | `f536a04` | `ConnectionCoordinator.cancelResilienceJobs()`（连接重建时作废遗留自愈任务） |
| 第四 | `f95d6bf` | GitHub Token 明文暴露面收敛（设置页不再预填 + 圆点掩码 + 布尔状态） |
| 第五 | `567b797` | 会话空态显示同步阶段 + 已等待秒数（任务书 §7；`ConversationSyncInfo.kt`） |
| 第六 | `e95a32e` | 首页连接失败不再误报「正在连接」+ 终态失败「前往设置」引导（`HomeConnectionInfo.kt`） |
| 第七 | `8a385b2` | 断开态切换会话不再显示上一个会话的内容（`subscribeConversation` 视图切换始终执行） |
| 第八 | `886ef96` | 切会话复位 `_earlier`/`_meta`/防误停令牌（**`_earlier` 不被快照重写 → 不修则新会话永远无法上翻**） |

- 真机红-绿验证（第七/八批）：修复前版本 `Tests run: 2, Failures: 1`，修复后 `OK (4 tests)`
  ——证明仪器化用例真能捕获缺陷（详见 §5「已修复」段与 CHANGELOG）。
- 新增仪器化测试：`ConversationOfflineSwitchInstrumentedTest`、`ConversationChannelStateResetInstrumentedTest`。

**② i18n 资源化 6 批**（详见 §5 的「已完成」段）
- `strings.xml` **67 → 452 条**；`ui/`、`notify/`、`widget/` 用户可见中文字面量**清零**。
- 核心：`util/UiText.kt`（`Res`/`Raw`，支持嵌套）——纯函数返回 `UiText` 而非 `String`，
  把取字符串推迟到有 Context 处，**无需 Robolectric 即可在 JVM 单测断言资源 id**。
- **架构约束**：`relay/` 层 24 条错误文案绑在 `IllegalStateException(String)` 上（Java 异常
  API 只能携带 String），**刻意不资源化**（详见 §5 该段）。

**验证**：JVM 单测 **329/329**；`ktlintCheck` 0 违规；`detekt` 通过；`lintDebug`/`lintRelease`
0 error；`assembleDebug`/`assembleRelease`/`assembleDebugAndroidTest` 通过；
APK 实测 `versionCode=59 / versionName=0.5.0-beta47`。

### v0.5.0-beta46：弃用 API 清理轮

| 项 | 做法 |
|---|---|
| **图标 RTL 迁移** | `Icons.Filled.Send/List/KeyboardArrowRight`（8 处）→ `Icons.AutoMirrored.Filled.*`（RTL 语言下正确镜像） |
| **通知死代码** | 移除 `setPriority(PRIORITY_HIGH)`（4 处）——Android 8+ 由通知渠道决定优先级，渠道已 `IMPORTANCE_HIGH`，行为不变 |
| **描边规范化** | 两处装饰性 `Surface` 误用 `ButtonDefaults.outlinedButtonBorder`（已弃用）→ 显式 `BorderStroke` / `CardDefaults.outlinedCardBorder()` |

**验证**：**编译期 deprecation 警告 14 → 0**；单测 287/287；debug/androidTest/release 装配通过；
lint 双变体 0 error、detekt、ktlint 0 违规；release 体积零增长（5065543 字节）。

### v0.5.0-beta45：工程清债轮

| 项 | 做法 |
|---|---|
| **界面预览** | 新增 `src/debug/.../ui/preview/`（时间线全行类型 / 折叠展开态 / 深浅色 / 审批卡 / 输入栏 / 倒计时）+ `debugImplementation("ui-tooling")`；**release 零增长**（预览在 debug 源集） |
| **脚本索引** | 新增 `tools/README.md`：按「当前使用 / 历史一次性（`_` 前缀）」分类，全部 26 个脚本已登记（不物理移动，保 CHANGELOG 引用链） |
| **文档可移植化** | `HANDOVER.md` 4 处构建命令的 `F:/AI/Zcode/…` 绝对路径 → `$(cd .. && pwd)/toolchain/…`；`tools/enumerate_rpc.py` 探针回退路径去本机化 |

**验证**：单测 287/287；debug/androidTest/release 装配通过；lint 双变体 0 error、detekt、ktlint（含 debug 源集）0 违规；**release 体积 5065543 字节零增长**。

### v0.5.0-beta44：会话正文离线缓存加密（§11.6 安全门）

| 项 | 问题 | 修法 |
|---|---|---|
| **会话正文明文落盘** | 凭据/Token 已加密，唯独最敏感的**会话正文**（行缓存 + 列表标题）以明文 JSON 写入 `filesDir` | 复用 `CredentialCrypto`（AES-256-GCM + Keystore）但用**独立密钥别名**；文件加 `ZCE1:` 版本化信封，**新旧双读**（旧明文照读、新写必加密）；加密器不可用则跳过落盘、**绝不回退明文**；损坏/篡改 → 读为空 |

**验证**：JVM 单测 **287/287**（+8 `CacheCryptoTest`）；仪器化 `CacheEncryptionInstrumentedTest`
断言落盘文件**搜不到 canary 明文**（直接对应 §11.6 验收）；lint 双变体 0 error、detekt、ktlint 0 违规。

### v0.5.0-beta43：交互修复轮

| 项 | 问题 | 修法 |
|---|---|---|
| **会话草稿串会话** | 草稿是单一全局字段，切会话不清空 → 在 A 打的半句切到 B 后仍在输入框，**点发送错发到 B** | 草稿移入 `ConversationRepository` 按 `taskId` 隔离（`switchDraftTo` 存旧/恢复新，LRU 20），断开清空；时序抽纯函数 `switchDraft` |
| **审批倒计时不动** | 两处审批卡在组合期算静态秒数，用户盯着的数字不跳变 | 新增 `AutoResolveCountdown`（`LaunchedEffect` 每秒推进，到点停），文案抽纯函数 `countdownLabel` |
| 工程 | 真机跑仪器化测试需 debug 包、但与已装 release 签名冲突 | 新增 `-PdebugUseReleaseSigning=true`（默认关闭，同 benchmark 开关模式），可同签名覆盖安装 |

**验证**：JVM 单测 **279/279**（+15）；仪器化 `ConversationDraftInstrumentedTest` 真机 **3/3 通过**；
lint 双变体 0 error、detekt、ktlint 0 违规、assembleDebug/Release 通过。

### v0.5.0-beta42：通知权限 / 网络回调 / 旋转状态

**本轮修复（不影响既有交互路径，均为「补上缺失能力」或「消除泄漏」）**

| 项 | 问题 | 修法 | 关键文件 |
|---|---|---|---|
| **P0 通知权限** | Manifest 声明了 `POST_NOTIFICATIONS` 但**全工程无运行时申请**——Android 13+ 默认拒绝，装机后锁屏审批通知全部静默失效（招牌功能直接不可用） | 新增 `notify/NotificationPermission`（纯判定）+ 设置页状态行（含「去开启」，`ON_RESUME` 刷新）+ 配对后申请一次 | `notify/NotificationPermission.kt`、`ui/screens/SettingsTab.kt`、`MainActivity.kt` |
| **P1 网络回调泄漏** | 每次 `connect()` 都 `registerDefaultNetworkCallback`，**从不注销**——切线路/换设备/重连持续累积系统回调（旧回调持有已关闭的 RelayClient） | `NetworkGate.stopWatch()` 注销；`RemoteConnectionManager` 登记/释放闸门，连接重建与 `disconnect()`/`shutdown()` 时释放 | `relay/NetworkGate.kt`、`service/RemoteConnectionManager.kt`、`service/ConnectionCoordinator.kt`、`AppViewModel.kt` |
| **P1 旋转丢失会话页** | `MainActivity.opened` 用 `remember`（非 saveable），横竖屏/进程重建后被踢回会话列表、流式会话卸载 | 改存 `openedTaskId`（`rememberSaveable`）+ 条目缓存，重建后按 id 回查 | `MainActivity.kt` |
| **P2 图标与启动观感** | 仅一张 48dp drawable 当图标（遗留形态被塞进蒙版、四周留白）；冷启动纯黑块无品牌图标 | 新增自适应图标（`mipmap-anydpi-v26` + 前景/背景矢量）与 Android 12+ **原生启动屏**（**零新依赖**） | `res/mipmap-anydpi-v26/`、`res/drawable/ic_launcher_foreground.xml`、`res/drawable/ic_splash.xml`、`res/values/themes.xml`、`res/values/colors.xml`、`AndroidManifest.xml` |
| **P2 死参数与无谓拷贝** | `HomeScreen` 有 7 个从未使用的参数（`bridgeState`/`rpcEvents`/`devices`/`activeSid`/`events`/`onDisconnect`/`onRescan`），`MainActivity` 每次重组仍 `vm.events.toList()` 等整表拷贝 | 删除死参数与对应实参/import | `ui/screens/HomeScreen.kt`、`MainActivity.kt` |

**治理与文档**
- 新增 [LICENSE](LICENSE)（MIT）与 [SECURITY.md](SECURITY.md)（含「非官方/逆向/ToS 风险」边界说明与私下报告渠道）。
- README 增补**免责声明**（非官方、无隶属、协议可能变更、风险自负），修正测试数漂移（153 → 264 项）。
- `app-android/README.md` 版本标题由 `beta6` 更正为「模块构建说明 + 指向根 README」，消除版本漂移。

**验证**：JVM 单测 **264/264**（+4 新增 `NotificationPermissionTest`）；`assembleDebug`/`assembleRelease`
通过；lint 双变体 0 error；detekt 通过；ktlintCheck 0 违规。

**真机走查（小米 15 Pro / Android 17 / HyperOS，release beta42 覆盖安装、配对凭据保留）**：
- ✅ **旋转状态保持**：进入会话页后强制横屏仍停留在该会话（修复前被踢回列表）。
- ✅ **通知权限申请**：撤销权限后冷启动弹出系统授权框「允许"ZCode Remote"发送通知？」，
  授予后设置页状态行显示「已开启」。
- ✅ **会话流渲染**：release 包流式画面/工具折叠行/顶栏胶囊正常，`logcat -b crash` 无 FATAL。
- ✅ **自适应图标**：`aapt2 dump badging` 确认 APK 含 `application-icon`（adaptive-icon 资源）。
- ⏳ **待补**：启动器内图标观感、启动屏过渡帧（启动屏一闪而过属预期；`windowSplashScreen*`
  已由编译 + 无 inflate 崩溃间接验证）。
- ⚠️ **测试基建限制（既有，非本轮引入）**：真机为 **API 37（Android 17）**，Espresso/Compose
  测试库的 `InputManager.getInstance()` 在该版本被移除 → 依赖 `createComposeRule` 的仪器化测试
  （含既有的 `MarkdownRenderTest`）在 Android 17 真机上必失败；**CI 的 API 35 emulator 不受影响**。
  故真机侧改用不依赖 Espresso 注入的仪器化测试（如 `ConversationDraftInstrumentedTest`）取证。

## 5. 接力开发指引与待办（Next Steps / Backlog）

### 对标官方 v3.18.1 的 Gap 待办卡（2026-11-14 全面核查产出，全部经 App 源码 grep 验证 0 命中）

> **落地状态（2026-11-14 更新，当日全部执行完毕、除真机验证）**：
> G1 ✅ 裁剪容忍+提示（补拉方法不在桥上，如实标注）｜G2 ⏸ 暂缓（官方 V4 帧无 owner 信号、官方消费端未落地，文案预留）｜
> G3 ✅ 策略层+RPC+ghost hint（能力位门禁）｜G4 ✅ 账本+对账收尾+定向文案｜G5 ✅ BridgeCapabilities 严格口径｜
> G6 ✅ CAS + 发布器接线｜P2①②③ ✅ 重连/心跳/含 KICKED 语义全对齐｜P2④ ⏸ 不适用（原生 FGS 无浏览器冻结，依据归档）｜
> P2⑤ ✅ 每 reason 2 条引导。真机待验：ghost hint 端到端（需桌面 v3.18.1 且输入预测开启）、对账收尾（重启 CLI 后重连）、
> epoch 轮换、KICKED 自愈路径、新退避曲线观感。门禁：536 单测全绿 / ktlint 0 / detekt 0。

| # | 项 | 建议档期 | 要点与依据 |
|---|---|---|---|
| **G1** | 快照 200KB 预算 + bodyRefs 按需补拉 | 下个迭代（首项） | 官方 `docs/web-remote-control/snapshot-byte-budget.md` 即实现规格书：`messageLimit=10` + `byteBudget=200KB`，字段级裁剪（assistant content/thought 预览 `min(32KB, max(2KB, budget/3))`；tool input>1KB→256B、output>4KB→512B、raw>2KB→256B；fileChanges>8KB→4KB），`bodyRefs` 携带字段名+完整正文 hash+持久化原始下标，`getTaskSnapshotBody({refId})` hash 校验通过后替换预览。不做预算，大回复直接撞 1MiB 物理帧墙；做了可把本地「最近 200 行缓存」升级为「预览+点按补全」 |
| **G2** | 单 owner 流渲染过滤 | 下个迭代（次项） | 官方 `single-owner-stream-rendering.md`：prompt 发送端带 `clientId`，`task_stream_mirror_batch` 携带 `ownerClientId`，非 owner 端过滤正文/thought/tool chunk（UI 显示「另一台设备正在发送消息…」）；**白名单永不过滤**：permission_request / elicitation_request / 对应 response / task_complete / task_error / snapshot invalidation。场景：桌面与 App 同时看同一会话时的交错尾块 |
| **G3** | 输入预测（ghost hint） | 体验档 | 官方 v3.18.1 默认开启；协议 `packages/shared/src/zcode-protocol-v4/input-prediction/contract.ts` + `docs/web-remote-control/mobile-input-prediction.md`：`conversationInputPredictionV4`，候选 ≤3 按 confidence 降序、门槛 0.8、text ≤40 UTF-16 单行、draft ≤4000、请求带 revision+requestId 回包不匹配即丢弃；模型 20s / 手机建议 25s 超时、续写防抖 600-800ms；能力位 `hello.capabilities.inputPrediction === true` 严格判断（老桌面缺字段=禁用） |
| **G4** | 任务实时同步对账 | 下个迭代 | 官方 `task-realtime-sync.md`：UI 保留 24h pending-command 记录（敏感 interaction 只存 digest）+ `commands/query` 每批 ≤64、settled LRU 512/session、single-flight；CLI restart 后未入 transcript 的 admitted input 明确返回 `fault.command.inputDiscardedOnRestart`。对接 App 的发送 pending 气泡与 `UserFacingError` 体系 |
| **G5** | 能力协商严格化 | 下个 beta | bridge-ready 显式声明 `capabilities.windowProjectDirectory` 等能力位；**声明支持却缺服务必须报错，禁止静默降级**；`hello.capabilities.* === true` 严格判断。接入点：`RpcChannel` 握手与 `ProtocolLimits.kt` 同级 SSOT |
| **G6** | 未读清除 CAS + mobileTaskView 上报 | 下个 beta | 官方未读清除为原子 compare-and-clear（快照 `unreadAt` 必须携带 `expectedUnreadAt`，迟到旧点击不得覆盖新未读，落点参考 `webRemoteControlTaskRead.ts`）；`mobileTaskView` epoch/seq 单调发布者、传输恢复后重申（`webRemoteControlTaskView.ts`）。App 多入口（通知/列表/详情）并发清未读时防误清 |
| P2 | 稳定性参数对齐 | 随任一 beta | ① 重连打散窗口：首连 0~1.5s 随机（两端共用，防同时醒来打爆中继，`packages/shared/src/web-remote-relay/domain/retry.ts`）；② KICKED 首次 2~5s 随机重连、60s 内第二次→终态 `session-conflict`；③ 心跳口径：官方 5s±1s + ACK watchdog 15s（App 现为 30s 单向失活）；④ suspended 期 raw 帧准入（hidden 后同 socket rpc-frame 只交当前 bridge adapter，出站只放行 ACK，防回前台序号断档重建桥）；⑤ 失败页 11 种 reason 各配 2 条「下一步」引导（官方 `webRemoteControlFailureScreen.tsx` tone 分级）可升级 App 终态通知为结构化卡片 |
| 参考 | 官方会话竞态修复 | 无需跟进 | `webRemoteControlEntry.ts` v3.18.1 新增 `getSessionRevision()`+`subscribeSessionChange()`（JWT+revision 双匹配）；App 侧 single-flight 令牌（按 `baseUrl\|role\|jwt` 键控）+ 401→AUTH_FAILED 终态已覆盖同类竞态面，V2 登录宿主为原生 WebView 与 Web 页面生命周期不同 |

**素材索引**：官方源码完整克隆在 `F:\AI\Zcode\_tmp\zcode-upstream`（main @ v3.18.1，已 unshallow，可作长期 diff 基线）；本轮对标报告（逐维度表格 + 官方设计哲学 8 条）见 2026-11-14 会话记录；官方三篇精读要点（snapshot-byte-budget / task-realtime-sync / mobile-stability-analysis）已提炼进上表对应行。

### 六项新功能真机走查结果（2026-11-12，Xiaomi 15 Pro / USB）

| 项 | 结果 | 实测证据 |
|---|---|---|
| **F1 会话内查找** | ✅ 面板双栏下渲染 + 键盘遮挡已修 | 溢出菜单「查找」正常；面板显示输入框/上一个/下一个/提示行/关闭。**双栏+软键盘下提示行原被裁切**（截图实证）→ 已加 `imePadding()` 修复并二次确认完整可见。（关键词输入受机型 IME 限制未测命中跳转，逻辑由 6 项单测覆盖） |
| **F2 下拉刷新** | ✅ **完整通过** | 下拉后 spinner 转动、列表下移，会话数 143 → **144**（PC 端新会话被拉取） |
| **F3 新建工作区** | ✅ 校验层通过 | 入口图标与弹层正常；**空名称点「创建」无任何网络请求、弹层保持**（`enabled = reason == null && name.isNotEmpty()` 生效）。（真实创建受机型 IME 限制未测） |
| **F4 平板双栏** | ✅ **完整通过**（`wm size 2400x1080 @240dpi` = 1000dp 模拟 Expanded） | 左列表（142 会话，标题/搜索/筛选/卡片/时间戳全正常）+ 右详情（顶栏返回/⋮/build-yolo 胶囊/模型胶囊 + 消息流 + 输入栏 + 附件/语音/发送）同屏；**底部导航栏正确消失**；双栏下溢出菜单与查找面板均可用 |
| **F5 交互式终端** | ✅ **核心链路通过，并修出 2 个布局缺陷** | ① 终端真实连上：桌面返回 PowerShell 提示符 `PS F:\AI\Zcode>` + 启动横幅；② **owner 认领成功**（`terminalClientSessionId` 在 bootstrap 与 bridge-open 双写，官方校验通过）；③ **ANSI 剥离正确**（彩色转义剥除、正文完整无语码）；④ 状态行「终端已就绪」。**未验**：命令输入与输出（受机型 IME + Compose TextField 兼容问题） |
| **F6 推送 schema** | ✅ 单测通过 | `MobilePushFramesTest` 4 项（端到端依赖官方部署方，本就不做） |

**走查中修复的终端布局缺陷（F5，两轮）**：
1. 输出区固定 `height(340.dp)` 在 `ModalBottomSheet` 内被压扁到约 30dp、输入框被挤出屏幕（截图实证）→ 改 `weight(1f)` + `heightIn(120..260dp)`。
2. `fillMaxHeight(0.85f)` 使总高超出可用空间，键盘弹出时输入框被推离屏幕 → 降到 `0.62f` 并加 `imePadding()`。
两轮修复后输出区、输入框、状态行均完整可见（截图二次确认）。

### 六项新功能真机走查卡（原计划，保留备查）

| 项 | 走查路径 | 判定标准 |
|---|---|---|
| **F1 会话内查找** | 进会话 → 顶栏「⋮」→ 查找 → 输入会话里出现过的词 | 显示「第 N / M 处」；点「下一个」列表滚到命中行且该行有浅色高亮；无匹配显示「无匹配内容」 |
| **F2 下拉刷新** | 首页列表下拉 | 出现刷新指示器；松手后约 1-3s 收尾；列表/状态更新（PC 端新建会话后可拉到） |
| **F3 新建工作区** | 首页顶栏「新建文件夹」图标 → 输入名称 → 创建 | 非法名（空/含 `/`）本地即报错不发请求；合法名创建后提示路径，PC 端 `~/ZCodeProject/<name>` 出现目录 |
| **F4 平板双栏** | 平板或横屏（≥840dp）打开会话 | 左列表右详情同屏；返回键不退出会话；手机竖屏行为不变（仍全屏） |
| **F5 交互式终端** | 进会话 → 「⋮」→ 终端 → 输入 `dir` 或 `ls` 回车 | 面板显示输出正文（无 ANSI 乱码）；状态行显示「终端已就绪」；关闭面板后 PC 端无孤儿 PTY 进程 |
| **F6 推送 schema** | 无 UI，仅单测 | `MobilePushFramesTest` 4 项通过即达成本轮目标（端到端依赖官方部署方，不做） |

### 真机批次执行卡（2026-11-10 更新：本轮四项已走查三项）

**2026-11-10 真机实测结果（Xiaomi 15 Pro / serial 9f6241b4 / USB）**：

| 项 | 状态 | 实测证据 |
|---|---|---|
| **task-pin 置顶** | ✅ **通过** | 长按「检索主流PPT插件工具」→ 动作菜单（置顶/删除会话/取消）→ 点「置顶」→ 会话浮到列表**最顶部**且带「📌 置顶」徽标；**重启 App 后仍置顶**（Controller 快照回填） |
| **未读蓝点** | ✅ **通过** | 两个运行中会话出现「有未读内容」语义（a11y 文案可断言）；置顶会话不误标；进入会话后返回，该会话未读已清除 |
| **连接分段进度** | ⚠️ **代码级通过，可视化未验** | 纯函数 `HomeConnectionPhasesTest` 4 项通过。真机可视验证受阻：本机有 140 条离线缓存，`sessions.isEmpty()` 才渲染空态，进度条不出现；清缓存会丢登录凭据（代价过高）。**待有空闲设备或不介意重登时补验** |
| **旧 SID 自动接续** | ⚠️ **未验** | 需桌面端重启换 SID 才能触发，本轮未构造该场景 |
| **设置页连接/断开切换** | ✅ **通过**（本轮新修） | 断开后按钮变「连接」，点击可重连成功（140 会话恢复、置顶保持） |

**同时发现并修复的两个缺陷**（详见 CHANGELOG 修复段）：
1. **V2 未读检测路径错误**：V1 的任务事件流在 V2 下零帧（实测），改走 Controller `updatedAt` 前进检测。
2. **设置页主按钮恒为「断开」**：断开后无法从设置页重连（存量缺陷）。

**走查记录手法（可复用）**：`adb shell "cd /data/local/tmp && uiautomator dump ui.xml && cat ui.xml"` —— 直接把 XML 输出到 stdout 再解析（本机 uiautomator 版本会把绝对路径当相对路径，`adb pull` 取不到）；文本用 UTF-8 解码（Git Bash 默认 GBK 会乱码）。

### 真机批次执行卡（2026-11-09 制定，以下为原计划）


**本轮（2026-11-09）代码已全绿但**未真机验证**的项，接手请优先走查：

| 项 | 走查路径 | 判定标准 |
|---|---|---|
| **旧 SID 自动接续** | 手机连上 → 桌面端重启 ZCode（换 SID）→ 观察手机 | 不弹「配对失效」终态，≤15s 宽限后自动重建连；`adb logcat -s RelayClient` 见「接续命中：切换到新 SID」 |
| **task-pin 置顶** | 长按会话卡 → 置顶 → 返回列表 | 卡片出现「📌 置顶」徽标且浮到列表顶部；重启 App 后仍置顶（Controller 快照回填）；PC 端侧栏同步置顶 |
| **未读蓝点** | 手机停在首页 → PC 端在某会话发消息/跑任务 | 该会话卡出现蓝点；进会话后蓝点消失；TalkBack 读「有未读内容」 |
| **连接 4 段进度** | 冷启动 / 断网恢复 | 空态区显示 4 段条并按「中转→鉴权→配对→同步」推进；终态失败时不显示进度条 |

> 若某台桌面端是 V1 协议（≤3.15），接续与置顶均不生效（V1 无 JWT / 无 pinned 字段）——属预期。

### 真机批次执行卡（2026-11-08 制定）

- **环境变化（依赖升级阶段 1 已落地，CI 全绿 run 42490983989）**：Gradle 8.13 / AGP 8.13.0 / compileSdk 36 / lifecycle 2.9.3 / activity 1.11.0。本机构建：`JAVA_HOME=F:\Android\jdk-17` + gradle 8.13（`F:\AI\Zcode\_tmp\gradle-8.13\`），SDK 需 platforms/android-36（联网自动装，走代理 `-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7901`，端口会漂移以 netstat 为准）。
  **2026-11-09 补充**：`build.sh` 已改为读 wrapper `distributionUrl` 版本（不再硬编码 8.7，见 CHANGELOG）；`toolchain/gradle-8.13` 与 `toolchain/android-sdk/platforms/android-36` 已就位，本机可直接 `./build.sh`。
- **步骤**：T8 WebViewActivity 登录迁移真机验收 + beta53 两项（PC 重启重同步、V2 审批链路）+ 阶段 1 升级后的回归走查（重点：连接链路 / 设置页 / 扫码 CameraX）→ 结果回写本 HANDOFF。
- 完成后：评估 beta56 或 1.0.0 发布门进度（A-5 重组与大 RPC 分片仍为硬门，见 §6 既有决策）。

### 官方协议跟踪（2026-11-09 新增例行项）

官方仓库是**快照式 sync**（无 CHANGELOG、中继服务端闭源、协议由官方单方面演进），每次官方发版后执行：

```bash
rm -rf ../.tmp/zai-zcode-mirror
git clone --depth 1 https://github.com/zai-org/ZCode ../.tmp/zai-zcode-mirror
python -I tools/protocol_sync_check.py        # exit 0=无变化，1=有增量，2=镜像缺失
```

有增量时按脚本输出逐项判断：`TYPE-REMOVED` 最高优先级（排查 App 是否在用）；`TYPE-ADDED` 评估
能力位（当前官方契约里 App 尚未接入的还有 `workspace-create`、`mobile-log*`、`mobilePushEventSchema`
推送事件）。复核通过后提交 `research/protocol-sync/snapshot.json` 作为新基线。

### 后续可跟进（对标结论，按性价比排序）

1. **会话内查找**（官方有独立查找 overlay，长会话价值高于桌面）
2. **交互式终端**（App 现只有 `SessionStatusPanel` 只读展示；官方手机端可 create/输入/resize/dispose）
3. **新建工作区**（`workspace-create-request/response`，官方固定根 `~/ZCodeProject`）
4. **横屏/平板适配**（全库无 `WindowSizeClass`；官方宽屏有侧栏布局）
5. **下拉刷新**（现依赖指数退避自动重连，无用户主动刷新）
6. **推送唤醒**（官方 `mobilePushEventSchema` v1 五类事件已预留，接上后可去掉常驻 FGS 省电）


### ✅ 已完成：国际化资源化（6 批全部完成，2026-10-16）

**目标**：把散落的中文字面量抽到 `res/values/strings.xml`，实现「文案集中管理 + 未来可翻译 +
可加防硬编码门禁」。**不是**做英文界面（用户已明确无英文需求）；**默认语言仍为中文，行为零变化**。

**成果**：`strings.xml` 由 **67 条 → 452 条**（+385）。`ui/` 目录用户可见中文字面量**清零**
（仅剩 `ZLog` 日志）；`notify/` 与 `widget/` 清零。剩余 78 处全部是**正确保留**的
日志 / 注释 / 协议常量 / 诊断导出头 / 异常消息（见下方架构约束）。

**批次与提交**（每批独立提交 + 全门禁）

| 批次 | 范围 | 提交 | 要点 |
|---|---|---|---|
| 1 | `ToolKindLabels` + 公共文案 | （已发布） | 确立 `UiText` 模式（`Res` / `Raw`），`asString()` / `resolve(context)` |
| 2 | `ui/components/StatusPanelLabels.kt` | `8ac2dab` | 队列整句三态改完整句式（片段无法翻译）；新增 `StatusPanelLabelsTest`（该类原无测试） |
| 3 | `relay/UserFacingError.kt` + flash 管道 | `283db43` | 映射表「英文键→资源 id」；**`UiText` 增加嵌套解析**；`flash`/`commandFeedback` 全链路改 `UiText` |
| 4 | `ui/screens/` 各屏（7 子提交） | `15d9c23`…`fb0872d` | HomeScreen / SettingsTab / ApprovalsTab / TopBar / KeepAlive / 会话流三组件 / 诊断等；含 `ui/components` 与 `ui/voice` |
| 5 | `notify/` + `repo/` + `widget/` | `afdd222` | 通知经 `context.getString`；`UpdateRepository` 新增 `context` 构造参数（原无 Context） |
| 6 | `relay/` 用户可见文案（**缩减范围**） | `bbf732f` | 仅 `handshakeFailureReason` + `Status.Failed.reason`（见下） |

**⚠️ 批次 6 的架构约束（重要，勿重复踩坑）**
`relay/` 的 46 条中，**24 条绑在 `Result.failure(IllegalStateException/IllegalArgumentException)`**
上——Java 异常只能携带 `String`，资源化需换成自定义类型（跨 relay/VM/repo 三层重构），
风险与收益不成比例；且这些中文串经 `UserFacingError.map()` 后落为 `UiText.Raw`（原文透出），
**功能完全正常**，只是不参与未来翻译。
`ResolveResult.Failed.message` 同样受限：它同时服务 **UI 出口**（经 `UserFacingError.map`）
与 **异常出口**（`parseCommandAck` → `sendPrompt`/`switchModelConfig` 的 `IllegalStateException`），
改 `UiText` 需给协议层注入 Context 或拆分支——故**批次 6 只做了 `handshakeFailureReason`（纯函数）
与 `Status.Failed.reason`**（各有独立测试覆盖），其余保持原文透出。
**结论：relay 层剩余文案不建议继续资源化**，除非将来真要做多语言且接受该重构成本。

**剩余未资源化清单（有意保留，非遗漏）**
- `util/Format.kt` 的时长文案（`$h 小时 $m 分` 等，纯数字+单位拼接，含在多个格式化函数内）
- `util/diag/Diagnostics.kt` 的**导出文件头**（诊断导出件标题，非 UI）
- `service/ConnectionService.kt` 已补（`c9fa3e3`）
- 日志（`ZLog.*`）、协议常量、诊断事件码——本就不该本地化

**实施注意**（踩过的坑，供后续参考）
- 纯函数返回类型改 `UiText` 会**连带改测试断言**（本项目无 Robolectric，这是必要的代价，不是回归）。
- 容器类 `when` 加 `res(...)` 包装会抬高 **detekt CyclomaticComplexity**；把子分支抽成私有函数，
  而不是加 `@Suppress`。**签名变化会失配 `detekt-baseline.xml`**（需同步更新基线条目签名）。
- `UiText.asString()/resolve()` 的 `*args` 展开触发 **detekt SpreadOperator**，已就地 `@Suppress`。
- **`strings.xml` 是 CRLF 行尾**：脚本化改资源时必须用 `newline=''` 的二进制安全替换，否则匹配失败。
- **非 Composable 上下文**（通知/Toast/Widget/Service）用 `context.getString(...)` 或 `UiText.resolve(context)`，
  **不能**用 `stringResource`。
- 每批做完跑：`ktlintCheck detekt testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease`
  + `python tools/check_test_count.py`（现为 **329**）。
- import 顺序由 `ktlintFormat` 自动修正。

### 其他待办

- **✅ V2 会话列表 Controller 通道（已于 beta53 完成并真机验收）**：原「beta52 遗留、下一版本
  主工作项」已于 beta53 落地（`relay/ControllerChannel.kt` + `ControllerReducer` + 三通道 clientId
  统一 + T5 空列表三态）。验收矩阵 ①②③⑤⑥ 通过，会话页 612 行实时流式正常。**勿再按「未做」处理。**
  详情见 §4 beta53 与 PROTOCOL.md §11.6/§11.6.7。
- **beta54 主线（按任务书顺序）**：
  - **T10 `fetchRelayToken` 401/网络失败区分 + single-flight** —— ✅ **已完成（2026-10-28）**：
    `V2AccountApi.fetchRelayToken` 对 401/403 抛具名 `TokenAuthException`（原先压成通用
    `IllegalStateException("HTTP 401")`）；新增 `fetchRelayTokenSingleFlight`（按
    `baseUrl|role|jwt` 键控、LAZY async + `ConcurrentHashMap`；失败不进缓存，jwt 变更不复用
    旧 in-flight）；`RelayClient.acquireV2Token` 改走单飞并新增纯函数 `isAuthFailure` 分类
    —— 401/业务码拒绝 → `AUTH_FAILED`（终态，指引重新登录），其余 → `NETWORK`（可重连）。
    此前**登录态失效被当成网络抖动**，界面无限重连转圈。测试 `V2TokenAuthTest` 4 项。
  - **T11 心跳「单向失活」检测** —— ✅ **已完成（2026-10-28）**：
    `startHeartbeat` 每轮记录「首次发出但尚未被 ack 覆盖」的时刻（`@Volatile heartbeatSentAtMs`），
    `pair_status_ack`/`auth_ack` 分支调 `markHeartbeatAcked()` 清零；超过 `HEARTBEAT_STALE_MS`
    （30s = 3 个心跳周期，与 PROTOCOL §4.1 服务端 ack 超时口径一致，刻意不抢在服务端判定前
    误判）即 `fail(NETWORK)` 触发退避重连。判据抽为纯函数 `isHeartbeatStale(sentAtMs, nowMs, staleMs)`；
    `startHeartbeat` 入口清零，避免继承上一条连接的「未 ack」残留而秒判失活。
    测试 `HeartbeatStaleTest` 6 项（含阈值边界；已做红-绿反证：把 `>=` 改 `>` 即失败）。
  - **T9 设置页「粘贴令牌」入口** —— ✅ **已完成（2026-10-28，随 beta55 交付）**：
    「ZCode 账号」卡片未登录态新增折叠式粘贴入口，`normalizePastedJwt` / `pastedJwtRejection`
    纯函数做清理与下限校验；测试 `PastedJwtTest` 9 项。绕过 App 内 WebView 登录在
    HyperOS + WebView 153 上点不动的问题。
  - **T8 登录迁移独立 `WebViewActivity`** —— ✅ **已完成（2026-11-01，未发版）**：
    WebView 宿主从 Compose AndroidView 内嵌拆为传统 View 层级的独立 Activity，从结构上
    消除渲染竞态（HyperOS + WebView 153 登录点不动的根治轮）；登录行为契约不变（hash 回补 /
    zcodejwttoken 持久化 / Debug `--es jwt` 注入 / T9 粘贴令牌入口均保留）。纯逻辑抽
    `WebViewLogin.kt` + `WebViewLoginTest` 10 项，单测 421 → 431 全绿。
    **结构性修复已落地，待真机确认**：小米 15 Pro 扫码 → 登录 → 配对全链路走查（见模块 4
    本轮条目的真机验收清单）。
  **T16–T19 结构卡必须等 T2/T3 稳定一个版本后再动。**
- **beta53 遗留的两项真机验证（不阻塞发版）**：① PC 重启（`logEpoch` 变化）后的重同步未实测
  ——需重启桌面端、会打断在线会话，后续 PC 自然重启时顺带核对；② V2 下审批链路
  （`resolveInteraction`）未实测——本轮无待审批交互，下一次真实审批时顺带核对。
- **CI 覆盖缺口（✅ 已闭环，2026-11-01，见模块 4 本轮条目）**：Controller 通道「订阅 → 快照 →
  增量 → resync」与 T5 空列表三态的仪器化取证已补齐——`ControllerReducerFullChainInstrumentedTest`
  4 项 + `HomeEmptyStateInstrumentedTest` 8 项（纯函数驱动、无 Espresso 依赖），由 CI API 35
  emulator 的 `connectedDebugAndroidTest` 常态执行；`tools/probe.py ctrl` 保留作协议实测取证
  手段。CI 单测下限原写 `--min 260`，**beta54 已上调至 410**（beta55 实测 421，2026-11-01 实测 431）。
- **App 内 WebView 登录卡死（独立 bug）**：HyperOS + WebView 153 点登录按钮无响应（进程存活、
  无 ANR）；根因为 Compose AndroidView 内嵌 WebView 的渲染竞态（5 轮渲染层修复未根治，
  见 beta51 记录）。**结构性修复已落地（2026-11-01 T8）：WebView 宿主拆为独立
  `WebViewActivity`，待真机确认**；过渡缓解（系统浏览器登录 / Debug `DebugPairReceiver
  --es jwt` 注入 / T9 粘贴令牌入口）继续保留作为兜底。
- **最高优先（已闭环）**：通知权限、网络回调泄漏、旋转状态保持、草稿串会话、倒计时不动——
  均已完成；前四项已通过 beta42 真机走查，草稿隔离已通过 beta43 真机仪器化测试（3/3）。
- **待补真机项（非阻塞）**：① 自适应图标在启动器内的观感（圆形/方形蒙版）；
  ② 冷启动启动屏过渡帧观感（启动屏一闪而过，属预期的快速过渡）；
  ③ 倒计时组件在真机的可视刷新（真机 API 37 因 Espresso 限制跑不了 `createComposeRule` 用例，
  由纯函数单测 + CI API 35 emulator 覆盖）；
  ④ **T8 登录 `WebViewActivity` 全链路真机走查（发版前必做，清单见模块 4 本轮条目）**——
  登录页渲染 / 可点击、OAuth 跳转、登录完成自动回连、切后台回来不白屏；通过后闭环
  「App 内 WebView 登录卡死」条目。
- **已知测试基建限制（既有）**：API 37（Android 17）真机上 Espresso/Compose 测试库
  的 `InputManager.getInstance()` 缺失 → 依赖 `createComposeRule` 的仪器化用例在该真机必失败
  （既有 `MarkdownRenderTest` 同样失败）；CI 的 API 35 emulator 不受影响。真机取证请改用不依赖
  Espresso 注入的用例。
- **观测记录（非缺陷）**：一次 `force-stop` 后重启的**过渡残帧**里曾见到首页卡片「PC焦点」
  徽章短暂出现在搜索框上方；稳定态（0.8s 与 5s 两次复测）均正常、未复现，判定为 Activity
  恢复期的中间帧，非稳定缺陷。
- **已修复（2026-10-16 真机走查发现 → 只读排查定性 → 实施修复）**：原先在**断开连接后**
  点开会话卡片，会话页显示的是**上一次订阅会话的标题与内容行**（状态栏「未订阅」）。
  **不是跨会话串台**（标题与行属于同一会话），机制为三处代码共同作用：
  1. `ConnectionCoordinator.kt:359-360`——桥就绪时**自动订阅** `hostSessions.firstOrNull { it.isRunning } ?: firstOrNull()`；
  2. `ConversationRepository.subscribeConversation()` 原以 `conversation() ?: return` 开头，
     断开态下**早退并跳过 `rowStore.clear()`**；
  3. `disconnect()` 不清 `rowStore`/`conversationMeta`，且二者为**单一共享实例**。
  - **修复**：`subscribeConversation()` 改为「视图切换始终执行」——草稿/`subscribedSessionId`/
    清行/读本会话离线缓存一律照做，仅在无法发起网络订阅时跳过 `conv.subscribe()` 并清空派生状态。
    与 README「会话离线秒开（点击任意卡片呈现该会话缓存）」契约一致。
  - **验证**：新增仪器化 `ConversationOfflineSwitchInstrumentedTest`（2 项，含修复前必失败的
    「A 不残留 B 内容行」断言）；JVM 单测 313/313、ktlint/detekt/lint 双变体 0 error、
    双变体 + androidTest 装配全通过。**仪器化用例在 CI 的 API 35 emulator 执行**。
  - 注：本次真机走查**未**覆盖该修复的真机回归（修复在走查之后实施），CI emulator 覆盖为准。
- **已修复 · 同类扫描第二例（2026-10-16）**：按第七批的模式（会话级共享状态在切换路径漏清）
  扫描，在 `ConversationChannel.subscribe()` 又发现同源实例——它只复位 3 个流，而 `reset()` 复位 7 个：
  - **`_earlier` 翻页门控（持久性）**：**不被快照重写**，上个会话的 `(pulled=true, hasMore=false)`
    会让新会话的 UI 门控 `(hasMore || !pulled)` **恒假 → 永远无法向上翻页且不自愈**。
  - **`_meta`**：切会话握手窗口内顶栏显示上一会话标题/模型；`lastError`/`logEpoch` 可能串号。
  - **`foregroundExecutionId`（防误停令牌）**：从不回 null，跨会话残留会让 stop 请求带上旧
    执行 id → 服务端校验不过、**停止失效**。
  - **修复**：`subscribe()` 中仅当**切换到不同会话**时复位上述项（`session != sessionId` 闸门），
    同会话重订（A-2 自动重订 / 用户重试）不清，避免标题/相位闪烁。
  - **验证**：新增仪器化 `ConversationChannelStateResetInstrumentedTest`（2 项，不依赖 Espresso；
    复用「桥未就绪时 loadEarlier 走同步错误分支 → `_earlier` 置 `pulled=true`」的复现路径，
    含修复前必失败断言 + 同会话重订不闪回归护栏）。JVM 门禁全绿（313/313、lint 双变体 0 error、
    三套装配通过）。
  - **真机红-绿验证（2026-10-16，小米 15 Pro / API 37）**：修复前版本 `Tests run: 2, Failures: 1`
    （失败断言 = `_earlier` 未复位）；修复后 `OK (4 tests)`。**证明用例真能捕获缺陷**。
    跑法：release 签名包（非 debuggable）配 `adb install -r` + `am instrument` 直跑——Gradle 的
    UTP 安装流程在 MIUI 上被 `INSTALL_FAILED_USER_RESTRICTED` 拦，故绕开。
  - 仪器化用例同时进 CI 的 API 35 emulator 门禁（防回归）。
- **既有硬门（沿用历史决策，勿当遗漏）**：
  1. **§11.5.3 A-5 逻辑 fragment 重组**——无真实帧样本不实现（「未知 fragment 显式失同步」已落地）；
  2. **出站大 RPC 物理分片**——同硬门（官方算法已实证，App 现为本地拒绝超限帧）；
  3. **§11.10 `1.0.0` 发布门**——依赖上述两项 + 连续两个 Beta 周期无严重回归。
- **未做（历史用户决策）**：C-5⑥⑦、Sprint 7 生物识别/自建中继 E2EE、P2-3 余项。
- **工程债（随手可清，非阻塞）**：`detekt-baseline.xml` 存量告警（HANDOFF 旧文写 105 条，
  实测 90 条 `<ID>`）；`tools/` 下 11 个 `_` 前缀一次性脚本；~~CI 单测下限 `--min 260` 偏松~~（**beta54 已上调至 410**，实测 412）；
  `release.yml` 建议加 `gh release view ... && exit 0` 幂等短路（同一 tag 触发两个 Release 工作流时后者必失败）。
  ~~会话正文缓存明文~~（beta44 已加密）、~~全库 0 个 `@Preview`~~（beta45 已补 debug 源集预览）、
  ~~界面文案硬编码中文、无 `strings.xml`~~（**i18n 资源化 6 批已全部完成并随 beta47 发布**，见上方「已完成」段）。
- **若其他会话接手**：先读本文件 → `HANDOVER.md` 文首覆盖层 → `CHANGELOG.md` 最新条目（beta53），
  再按「§2 核心命令」跑一次 `testDebugUnitTest` 建立基线（当前应为**声明 383 / 实际执行 421**）。

### 5.1 外部分析待办清单（2026-10-14 第三方全仓走查）

> 来源：对仓库（beta46 基线）的一次独立代码 + UX 全面走查，以下为走查新发现、
> 未被任务书 v2 / HANDOVER 覆盖的增量项；作者任务书已标记或已立项推进的存量项（B-2、A-4、
> C-3、C-5、C-9、C-12 残余、全局 Typography、横屏/平板适配、国际化资源化）不重复列出。
>
> **⚠️ 2026-10-15 逐项代码核实（务必先读）**：本清单写在 beta46 基线，此后代码已有推进，
> 下列条目与当前 `master` **不完全一致**，接手前请勿照单全收：
> - **#1 已大部分失效**：`MainActivity` 已传 `vm.rowsSnapshot`（C-5③，非 `rows.toList()`）；
>   `extract`/`aggregate` 已有按 `rowId + 内容指纹` 的增量解析缓存（C-5④）。
>   **残留**仅为 `remember(rows)` 每次仍全量**遍历**（O(n) 查表，非 O(n) 解析），收益远小于原文描述。
> - **#8 已不成立**：`AndroidManifest.xml` 已有 `android:allowBackup="false"`。
> - **#9 描述偏重且函数名漂移**：函数在 `CacheCrypto.kt`（非 `SessionCacheStore`）；
>   `save`/`saveRows` 覆写同一文件，故活跃会话会自然转密文；真实问题是**长期不再打开的旧文件**
>   一直留明文。**已于本次修复**（见下方「本次已修」）。
>
> **本次已修（2026-10-15，#3/#4/#5/#6/#7/#9）**
> - **#3 两处 `!!`**：`ConversationChannel` 的读循环改取不可变快照 `stream`（原 `input!!` 在 abort
>   重入路径可 NPE）；`HomeScreen` 的 `s.lastError!!` 改 `errorReason` 局部非空值。
> - **#4 OkHttp 复用**：新增 `util/HttpClientProvider`（进程级单例 + 显式钉池/调度器），
>   两处调用改 `newBuilder()` 派生——共享底层资源、超时各自保留。
> - **#5 重连抖动**：新增纯函数 `jitteredBackoffMs(attempt, factor)`（只缩短不延长，区间
>   `[base/2, base]`，不破坏 48s 封顶承诺），调用点改用之；补 `PureFunctionsTest` 断言。
> - **#6 韧性任务作废**：原文「心跳/重连 Job 不取消」不成立（`close()` 已 `io.cancel()`）；
>   真实缺口是 `ConnectionCoordinator` 的 `handshakeRetryJob`/`bridgeReopenJob` 挂在进程级 scope
>   上、`connect()` 重建时不取消 → 遗留任务对新连接栈重复开桥。已新增 `cancelResilienceJobs()`
>   并在 `connect()` 重建前与 `disconnect()` 调用。
> - **#7 网络安全策略**：固化为 `res/xml/network_security_config.xml`（主源集禁明文 + 仅系统 CA；
>   debug 同名资源放开明文）。**证书固定刻意未做**——`*.z.ai` 通配符证书每年轮换、侧载无自动
>   更新渠道，硬钉会导致官方轮换后彻底失联；理由与取证详见 CHANGELOG。
> - **#9 旧明文回写迁移**：新增纯函数 `legacyRewriteTarget`，`SessionCacheStore` 读路径命中旧明文时
>   经 `migrationScope`（IO）+ `writeMutex` **异步原子回写**为密文，并**保留原 mtime**
>   （刷新时间戳会让过期文件被 TTL 误判为新鲜）；加密失败绝不回写明文。
>   **含竞态防护**：迁移前在锁内重新比对文件内容，若已被 `save()` 更新则放弃迁移
>   （否则会用读取时捕获的旧明文覆盖刚写入的新数据）。补 `CacheCryptoTest` 3 项。
> - **#10 GitHub Token 暴露面**：核实发现不只是内存驻留——明文被预填进设置输入框（打开设置页即铺屏）。
>   已改：设置页不预填 + 圆点掩码 + 参数改 `hasGithubToken: Boolean`，`UpdateRepository` 明文状态字段
>   亦改布尔、明文只在 `checkForUpdate()` 按需读取。

> **本次已修（2026-10-16，#11 会话空态）**
> - 新增 `ui/screens/ConversationSyncInfo.kt`：纯函数 `syncHeadline` / `syncSubtext` /
>   `isWaitingForSync`（JVM 可测）+ 计时组件 `EmptySyncHeadline`（`LaunchedEffect` 每秒推进，
>   阶段迁移不重启计时 = 累计等待时长）。空态按状态显示「建立连接中 / 握手建立中 / 订阅会话中
>   · 已等待 N 秒」；`Live`（已订阅但无行）呈现「会话暂无内容」而非「还在同步」；`Failed`
>   指引点顶部「重试」；草稿态显示发送引导。`ConversationScreen` 空态区块改用之，
>   `strings.xml` 增 8 条 `sync_*`。
> - **#11 后半（2026-10-16 同日）**：首页会话列表空态原只匹配 `WaitingPeer`/`Paired`，
>   其余落 `else → 「正在建立安全连接…」`——`RelayState.Failed` 被误报为「正在连接」，
>   其中 `KICKED`/`AUTH_FAILED`/`PROTOCOL_MISMATCH`/`INVALID_ENDPOINT` 是终态（不自动重连），
>   界面会永久停在「正在连接」。新增 `ui/screens/HomeConnectionInfo.kt`：纯函数
>   `connectionEmptyStateText` / `isTerminalFailure`（终态判定与 `RelayClient.fail()` 同源）；
>   首页失败态改用之并给终态失败增设「前往设置」引导，`strings.xml` 增 8 条 `home_conn_*`。
>   **验证**：单测 **313/313**（+14 `ConversationSyncInfoTest` +9 `HomeConnectionInfoTest`）；
>   ktlint 0 违规 / detekt / lint 双变体 0 error / assembleDebug+Release 全通过。
>   **真机走查（2026-10-16，小米 15 Pro / Android 17 / API 37）**：带两批修复的 release 签名包
>   覆盖安装（指纹 `1d46e9e8…` 一致，配对保留），冷启动无 FATAL，首页/会话页（166 行流式）/
>   设置页/新建弹窗渲染正常无回归，新增字符串经 `aapt2` 确认已打包。
>   **但两处修复的目标态均未在真机走到**（环境不可达，非缺陷）：① 会话空态需零行会话，
>   而「立即创建」要求非空 prompt（会真建会话耗额度）；② 首页失败态需 `sessions.isEmpty()`，
>   而 `disconnect()` 有意保留会话缓存（清空仅在 `forget()` 清配对）。两处文案逻辑均由
>   单测覆盖，**视觉呈现待后续在零行会话 / 空列表环境下补走查**。
>   **方案 A 尝试（同日）**：请用户在 PC 端建空白会话后，手机端重启 App 拉取列表
>   始终无新增（PC 端「新建任务」草稿页**不发消息不产生 task**，故不进会话列表）；
>   「断开后点开会话」反倒暴露了一个遗留缺陷（已定性并修复，**见下方「已修复」**）。
>   **空态视觉验收已随修复补齐（2026-10-16 真机）**：断开态点开会话即命中空态，
>   实测显示「建立连接中… · 已等待 7 秒」并逐秒递增（4s 后为 21s），**#11 视觉呈现确认通过**。

**P0 性能与正确性**

1. ~~**流式期每 token 双 O(n) 解析**~~（**已大部分失效，见上方核实说明**）：`ui/screens/ConversationScreen.kt:64/70` 的 `remember(rows)`
   内对全量行执行 `SessionFiles.extract` + `TurnChanges.aggregate`，流式输出每个 token 触发两遍；
   `MainActivity.kt:89` 每次重组 `vm.rows.toList()` 整表拷贝。长会话 + 快流式时是主要掉帧源。
   建议：派生子集改 `derivedStateOf`、行存储外提为 `SnapshotStateList`（C-5 线程模型项的同族债）。
2. **附件上传流水线重构**：`relay/ConversationChannel.kt` `uploadChunks` 递归回调 + 每 256KB 分片
   一次串行 RPC；失败重试每次重新 `openStream()` + `skip()`（O(n) IO）。建议改 suspend/flow 顺序
   上传 + seekable 流。
3. ~~**两处 `!!` 隐患**~~（**✅ 2026-10-15 已修**）：`ConversationChannel.kt:1341` `input!!.read(...)`（读循环内可变局部变量，
   abort 重入可 NPE）；`ui/screens/HomeScreen.kt:465` `s.lastError!!` 连用两次。改 `?.let{}` 安全路径。

**P1 连接层资源管理（任务书未覆盖）**

4. ~~**`OkHttpClient` 未复用**~~（**✅ 2026-10-15 已修**）：新增进程级 `util/HttpClientProvider`
   （显式钉 ConnectionPool/Dispatcher），`RelayClient` 与 `UpdateRepository` 改 `newBuilder()` 派生
   ——共享连接池/调度器、超时各自保留，行为不变。
5. ~~**重连退避无 jitter**~~（**✅ 2026-10-15 已修**）：`RelayClient` 退避封顶 `3s * 2^4 = 48s` 固定值，多设备同断网重连形成
   雷群。建议 `backoff * (0.5 + Random.nextDouble() * 0.5)`。
6. **自建 `CoroutineScope` 未绑定生命周期**（**⚠️ 2026-10-15 核实：原描述不准确，已修真实缺口**）：
   原文称「`RelayClient`、`RemoteConnectionManager` 的心跳/重连 Job 不取消」——**不成立**：
   `RelayClient.close()` 已 `io.cancel()`（心跳/泵/重连三 Job 均在该作用域内），
   `RemoteConnectionManager.shutdown()` 也已 `scope.cancel()`。
   **实际缺口在 `ConnectionCoordinator`**：其 `handshakeRetryJob` / `bridgeReopenJob` 跑在
   **进程级 scope** 上（§11.4 阶段③ 的刻意设计，使 VM 被清后自愈仍生效），因而**不在**
   `cancelCollectors()` 管理范围内；`connect()` 重建连接栈时只取消了 collector，
   遗留任务会在退避到点后对着**重建后的新连接栈**执行 `openBridge` → 重复开桥/重复订阅。
   **已修**：新增 `cancelResilienceJobs()`（统一作废两个任务 + 预算归零），在
   `connect()` 重建前与 `disconnect()` 中调用。**无 JVM 单测覆盖**（该类需 Context + 多个 repo，
   仓库无 Robolectric），靠代码审计与门禁兜底。

**P1 安全补齐（beta44 加密轮后的残余缺口）**

7. **无证书固定**（**⚠️ 2026-10-15 部分处理，证书固定刻意未做**）：`wss://zcode.z.ai/ws` 承载配对
   proof，当前完全依赖系统 CA。已把它固化为**显式可审计的** `res/xml/network_security_config.xml`
   （主源集禁明文 + 仅系统 CA；debug 同名资源放开明文）。**未做 pin**：取证显示 `zcode.z.ai` 用
   `*.z.ai` 通配符证书（Sectigo DV，2026-11-27 到期，每年轮换），本项目侧载分发无自动更新渠道，
   硬钉会导致官方轮换后彻底失联（含配对）。详见 CHANGELOG 该条。
8. ~~**备份策略未声明**~~（**❌ 已不成立**：Manifest 已有 `android:allowBackup="false"`）：Manifest 未见 `android:allowBackup="false"` 或 `dataExtractionRules`。
   密文 + Keystore 密钥别名被备份还原到他机后无法解密——安全但用户会「安全地丢数据」。
   建议显式配置备份排除规则并写入文档。
9. ~~**旧明文缓存无回写迁移**~~（**✅ 2026-10-15 已修**）：`storage/SessionCacheStore.decodeCacheText` 对 beta44 前的旧明文
   **永远原样返回**，没有「读出后重写为密文」的回写路径，旧文件不删则明文无限期存在。
   建议首次成功双读后立即以密文重写。
10. ~~**`TokenStore.read()` 明文 String 长驻内存**~~（**✅ 2026-10-15 已修，且核实发现更广问题**）：
    原描述只提内存驻留；核实发现更直接的是 **UI 明文铺屏**——`UpdateRepository` 构造时解密一次后，
    明文一路传到设置页被 `tokenDraft` 预填，打开设置页即见完整 Token（输入框无掩码）。
    **已修**：设置页不再预填、输入框改圆点掩码（`PasswordVisualTransformation` + `KeyboardType.Password`）、
    参数由 `githubToken: String` 改 `hasGithubToken: Boolean`；`UpdateRepository` 的明文状态字段
    亦改为布尔，明文只在 `checkForUpdate()` 内按需读取。建议改 `CharArray` 的进一步优化**未做**
    （`Cipher.doFinal` 与 OkHttp header 均为 String 接口，收益有限）。

**P2 体验体系化**（#12–#15 已于本轮修复/扩展，见 §5.2）

11. ~~**加载/空态信息量不足**~~（**✅ 2026-10-16 已修**：会话空态「阶段+秒数」+ 首页连接失败态误报，见下「本次已修」）：
    会话页「握手中…/订阅中…」无已等待秒数与进度（任务书 §7 已要求未落地）；会话为空无引导文案；
    首页「连接失败」被误报为「正在连接」。
    **残余未做**：会话卡片内的异常原因行（`HomeScreen.kt:462` 附近）仍仅一行红字、无重试按钮——
    与上述 relay 级连接空态是不同位置，本轮未动。
12. **无障碍覆盖薄弱**：全 ui/ `contentDescription` 仅 22 处（其中 11 处是 `= null` 装饰图标），
    `semantics` 修饰符几乎不用，TalkBack 下审批按钮/状态胶囊/会话行基本不可达。
13. **Markdown 禁外链图片的兜底体验**：图片类回答仅一行灰字占位，建议加「复制链接 / 浏览器
    打开」快捷操作（可复用现有 `SafeUriHandler`），不改变不加载外链图片的安全决策。
    **注**：`multiplatform-markdown-renderer 0.27.0` 的 alt/url 读取助手是 `internal`，取图片
    链接需另寻实现路径（自遍历 AST 或升级库——后者受 Kotlin 版本冻结约束）。
14. **会话流缺日期分隔与未读标记**：长会话无时间锚点，定位困难。
    **注**：wire 协议只下发 `startedAt`/`endedAt`/`activeMs` 相对量，**无绝对时间戳**——做日期
    分隔需服务端补字段或改用本地接收时刻（后者在离线缓存场景语义不准）。
15. **布局回归自动化防线**：C-14 类顶栏布局错乱此前只能靠真机人肉发现，§11.2 仪器化布局断言
    刚起步（现有 `ConversationTopBarLayoutTest` 单点），建议优先补「关键组件祖先链 + 边界数值」
    断言（对齐 AGENTS.md「DOM 断言须验证祖先链」规范）。

### 5.2 5.1 清单的当前剩余（2026-10-16 收尾，随 beta48/49/50 发布）

**5.1 清单已全部闭环（#1–#15 处理完毕，含 #2）。**

| 项 | 状态 |
|---|---|
| #2 附件上传流水线重构（递归回调 → suspend/flow） | ✅ **已完成**（beta50）：顺序 suspend 流水线 + 可定位源（O(1) 续传）+ 阻塞读下放 IO 线程 + 11 项 JVM 单测。**真机按协议层验收**（SAF 开流路径待补，见 §4 beta50）。**2026-10-31 复验**：测试覆盖核对 + 门禁重跑全绿（detekt/ktlint 0 issue，单测 421 全绿），无代码改动 |
| #12 无障碍 | ✅ 已修（`8725bad`）：会话卡语义 / `StatusDot` / 设置入口 Role |
| #13 Markdown 图片兜底 | ✅ 已修（`c7862fd` 首次；**beta48 版本实为死代码，`beta49` 重修为真修**）：复制链接 / 打开（**原判「库 API 不可达」不成立**——URL 可得，不可达的是 alt 文本）。**beta49 更正**：兜底改挂段落/引用块级（`components.image` 在库中永不触发），URL 取自 AST 节点 |
| #14 会话流日期分隔 | ✅ 已修（`2a9efdd`）：按本地时区日历日（**原判「协议无绝对时间戳」用错字段**——`createdAt` 是行级绝对时间戳） |
| #15 布局回归断言 | ✅ 扩展（`d906da0`）：输入栏高度 + 会话流未被挤压 |

**已闭环**：#1–#15 全部闭环（含 #7 证书固定为作者刻意不做、#8 不成立）。
**发布状态**：#12–#15 随 **v0.5.0-beta48 发布**；#13 的死代码由 **v0.5.0-beta49 修复**；
#2 由 **v0.5.0-beta50 完成**。beta49/beta50 两个 tag **本地已打、因 GitHub 不可达暂未推送**（见 §1 与 §7）。

## 6. 关键避坑与运行约束

### ⚠️ 仓库有一个历史遗留的坏文件名（2026-10-28 发现，未修）

远端 `tree` 里有两个文件的名字被存成**字面引号 + 八进制转义**，而不是真正的中文名：

- `"ZCodeè¿...APPæ¹æ¡.md"`（本应是 `ZCode远程控制安卓APP方案.md`）
- `"ä½éª...任务书-v2.md"`（本应是 `体验提升任务书-v2.md`）

**症状**：任何 `git checkout` / `git reset --hard` / `git read-tree` 都会报
`error: invalid path ...` 并整体失败（Windows 无法创建该路径）。`git status` 里会看到
这两个文件被标成 `R`（重命名），但那是索引自动修复的假象。

**影响**：不影响读写其他文件、不影响提交与推送；但**会让 `git reset --hard origin/master`
无法完成**，本地引用更新只能靠 `git update-ref` 或 `git fetch` 后手工处理。

**本次（beta55）的处理**：改用 GitHub REST API 推送（见下），远端 `master` =
`d0fd2cb`，本地 `master` = `f2eab25`。两者 **7 个变更文件的 blob 哈希逐一比对完全相同**，
差异仅在于本地提交把这两个坏路径文件"改名"成了正确中文名（远端没有这一步）。
即：**内容一致、提交 SHA 不同**。下次会话若发现 `git log` 与远端对不上，属此已知偏差。

**根治方案（待做）**：用 `git filter-branch` / `filter-repo` 把这两个路径改成正常中文名，
或直接改成 ASCII 名（如 `docs/zcode-remote-app-plan.md`）。需重写历史、所有协作者重新克隆，
故未在本轮做。

### HTTPS 推送通道不稳定（2026-10-28 实测）

本机 `http.proxy=127.0.0.1:7900` 的 Clash 未监听、SSH key 无权限、HTTPS 直连
`Recv failure: Connection was reset`（git 自带 schannel 与 openssl 后端均失败，
重试 3–4 次全红）。但 **`gh` 走 `api.github.com` 稳定可用**。

**推送路径**：用 REST API 手工建提交 —— `POST /git/blobs`（base64 内容）→
`POST /git/trees`（`base_tree` + 条目）→ `POST /git/commits`（parents 指向远端当前 head）→
`PATCH /git/refs/heads/master`。打 tag 用 `POST /git/tags`（annotated）+
`POST /git/refs`（`refs/tags/vX.Y.Z`）。**注意**：必须用 `POST /git/tags` 建 annotated tag
对象再指向它，直接 `POST /git/refs` 指向 commit 会得到轻量 tag，不符合发布规范。

**读侧兜底**：`git fetch` 偶尔能成功（间隔性），失败时用
`gh api repos/<owner>/<repo>/compare/<a>...<b>` 核对提交内容，或用
`gh api .../contents/<path>?ref=<sha> --jq .sha` 比对 blob 哈希验证一致性。



- **不要裸跑后台进程**：`adb`/`gradle`/模拟器/logcat 跟随一律后台模式，阻塞式探测包硬超时（详见 `AGENTS.md`）。
- **构建产物体积/工具链**：`toolchain/` 与 `keystore.properties`、`*.keystore` 均被 gitignore，**不入库**；
  `local.properties` 指向本机 SDK，不可提交。
- **协议层硬约束**：Release 仅接受绝对 `wss://`（`RelayEndpointValidator`），明文 `ws://` 只在 debug 放行；
  日志走 `ZLog`（release 剥离 d/i/w），`ZLog.e` 只写元信息（CI 有 grep 门禁）；
  **App 侧永不提供文件写能力**（凭据红线，HANDOVER §8）。
- **Kotlin 版本冻结**：任何 `org.jetbrains.kotlin*` / `kotlinx*` 升级都可能在元数据层硬失败，
  需单独立项（HANDOVER 坑 14）；Dependabot 已加 ignore 规则。
- **签名**：唯一官方签名 = `toolchain/keys/zcode-remote.keystore`（SHA-256 `1d46e9e8…`），
  **必须备份该 keystore 与口令**；历史设备升级需先卸载一次。
- **CI 三坑**：`working-directory` 必须是 YAML 键而非 `run:` 块内命令；`set -euo pipefail` 下 `grep` 失败会静默退出；
  Actions 一律 pin commit SHA + 最小权限。
- **发布流程**：升 `versionName`/`versionCode` → 更新 CHANGELOG/README/HANDOFF → 打 annotated tag
  （标题 `vX.Y.Z: 中文摘要`）→ push → 核验/修正 GitHub Release 标题与正文（见 `AGENTS.md` 四步门禁）。
  - **发版前必跑本地预检**：`python tools/check_release_consistency.py vX.Y.Z`——CI 用同一脚本做
    五处一致性核对，**CHANGELOG 头条必须是 `## vX.Y.Z（日期）· 摘要`**（写「未发布」会直接阻断 CI）。
  - **`git push --tags` 会因历史旧 tag 冲突而报错**（如远端已存在 `v0.5.0-beta1`），属预期；
    用 `git push origin vX.Y.Z` 单独推本次 tag 即可（`--tags` 那次通常已把本次 tag 推上去，先 `git ls-remote --tags origin` 确认）。
- **推送受阻（2026-10-16 实测）**：本机代理端口**会中途失效**——`127.0.0.1:7900`（Clash Verge 的
  `verge_mixed_port`）在一次会话中从「有监听」变成「无监听」（仅剩 `clash-verge-service.exe` 服务助手，
  内核已退出），此时 git 报 `Failed to connect to github.com port 443 via 127.0.0.1`。
  **直连亦不可用**：`github.com:443` 被拒、DNS（默认与 8.8.8.8）解析出的部分 IP 也连不上；
  但 `api.github.com` / 普通网站可直连（`gh` CLI 走 API，故 `gh` 有时仍可用而 `git push` 不可用）。
  判定与处置：`netstat -ano | grep 127.0.0.1:7900` 无 `LISTENING` 即代理已挂；
  启动 Clash Verge（`/e/Clash Verge/clash-verge.exe`）恢复代理后再推，勿反复重试。

## 7. 本次会话（2026-10-16）新增的操作避坑

- **MIUI 的 SAF 文件选择器要指纹解锁**：真机点「添加附件」会弹
  `com.miui.applicationlock.AppLockActivity`（「请用指纹解锁 用于打开文件管理」）——**adb 走查进不去**，
  故「真人点选文件 → 上传」这条链路无法自动化验收。需要真机验证上传时，用 debug-only 探针
  从进程内连接栈直发（见 `AttachUploadProbeActivity`），协议层照样是真 RPC。
- **`android.util.Base64` / `android.util.Log` 在无 Robolectric 的 JVM 单测里会抛 "not mocked"**：
  被 JVM 单测覆盖的类要避开它们——base64 用 `java.util.Base64`（minSdk 31 可用，与 `NO_WRAP` 等价）、
  日志走**注入式出口**（本仓库既有约定：`RpcFrameAssembler`/`ProtocolLimits` 零 `ZLog` 调用）。
  否则整批单测会以 `RuntimeException: Method … not mocked` 全红（本轮初版即踩，11/11 全红）。
- **`suspendCancellableCoroutine` 的 `continuation.resume(value)` 在本项目协程版本需带 `onCancellation`**：
  写 `cont.resume(reply) { _ -> }`（单参 lambda），不是三参。
- **`@file:OptIn(...)` 必须写在 `package` 之前**（Kotlin 语法要求）；且 lambda 体内不能 opt-in
  实验性 API（如 `runComposeUiTest`）——只能文件级/类级。
- **`strings.xml` 等资源文件是 CRLF 行尾**：用脚本批量改资源时必须 `io.open(..., newline='')`
  二进制安全读写并匹配 `\r\n`，否则替换静默失败（Windows 侧 `Write`/`Edit` 工具正常）。
- **非 Composable 上下文不能调 `stringResource`**：通知（`Notification.Builder`）、`Toast`、
  Widget、Service 里用 `context.getString(id)` 或 `UiText.resolve(context)`；只有 `@Composable`
  与 `UiText.asString()` 能用 `stringResource`。
- **`ktlintFormat` 会自动修 import 顺序**，改完大量文件后先跑它再跑 `ktlintCheck`，能省手工排序。
- **改纯函数签名会失配 `detekt-baseline.xml`**（条目签名含参数类型），需同步更新基线条目，
  否则 detekt 报「新违规」。
- **MIUI 会拦 Gradle 的 UTP 安装**（`INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`）——
  真机跑仪器化测试时改用 `adb install -r <apk>` + `adb shell am instrument -w ...` 直跑；
  且 debug/release 需同签名（`-PdebugUseReleaseSigning=true`）才能覆盖安装、保配对数据。
- **本机（兆芯 CPU 无虚拟化）跑不了模拟器**，UI 一律真机验收；仪器化用例请写「不依赖 Espresso
  注入」的形式（API 37 真机的 `InputManager.getInstance()` 已移除，`createComposeRule` 类用例必失败）。
  - **⚠️ 2026-10-16 实测更正**：本机 **API 35 模拟器其实可跑**——`toolchain/android-sdk` 内已装
    **AEHD** 虚拟机监控程序（服务 `aehd` 处于 RUNNING），AVD `apkrev35` 可正常启动（`emulator-5554`）。
    启动命令（后台模式）：
    `export ANDROID_SDK_ROOT=F:/AI/Zcode/zcode-remote-app/toolchain/android-sdk && toolchain/android-sdk/emulator/emulator.exe -avd apkrev35 -no-snapshot-save -no-boot-anim -gpu swiftshader_indirect -no-audio`
    → 等 `adb shell getprop sys.boot_completed` 为 `1`，再
    `adb -s emulator-5554 install -r -t <debug.apk>` + `<androidTest.apk>`（或 `gradle :app:connectedDebugAndroidTest`）。
    **仪器化用例可在本地按 CI 同路径真跑**（本轮实测全量 `OK (24 tests)`），**不必只依赖 CI**。
    注意：`createComposeRule` 与 `runComposeUiTest` 在 API 37 真机**都**失败（后者内部注册 `EspressoLink`），
    故真机走查改用「debug-only 探针 Activity + `uiautomator dump` 可编程断言」（见 `MarkdownProbeActivity`）。
- **工具链（`toolchain/`）被 gitignore、不在 clone 中**：在 `/tmp` 等副本构建前需先从正式副本
  （`F:\AI\Zcode\zcode-remote-app`）`cp` 源码过去，或用正式副本本身构建；本地 SDK 路径见
  `app-android/local.properties`（`sdk.dir`）。
