# 变更记录 / Changelog

## 未发布（2026-10-16）· 走查第八批：切换会话时会话级状态未复位（同类缺陷扫描）

> 承接第七批的**同类扫描**（第七批的模式 = 会话级共享状态在切换路径上漏清）。
> 在 `ConversationChannel` 中又发现同源实例，且其中一条是**持久性**缺陷。

### 问题

- `ConversationChannel.subscribe()`（切换会话时调用）原先只复位 `_status` / `_interactions` /
  `_snapshotAligned` **三个**流，而 `reset()` 复位 **七个**——漏掉的 `_meta` / `_earlier` /
  `_sessionState` / `_elicitations` 会**跨会话沿用**：
  - **`_earlier`（向上翻页门控）——持久性**：它**不被快照重写**（快照处理只写 `_meta` /
    `_sessionState` / `_interactions` / `_elicitations`，从无 `_earlier`）。上个会话拉到头后的
    `(pulled=true, hasMore=false)` 会带给下一个会话，使 UI 门控 `(hasMore || !pulled)` **恒假**
    —— 新会话**永远无法向上翻页**，且不会自愈。
  - **`_meta`（标题 / 相位 / 行数 / 模型 / lastError）**：切会话的握手窗口内顶栏显示
    **上一个会话的标题与模型**；且因快照写入大量使用 `?: _meta.value.x` 回退，
    旧会话的 `lastError` / `logEpoch` 可能串进新会话（快照字段缺失时）。
  - **`foregroundExecutionId`（防误停令牌）**：`stop` 命令把它作为 `expectedForegroundExecutionId`
    下发（服务端校验「停的是不是当前前台任务」）；它只在 `control` 非空时被赋值、**从不回 null**，
    跨会话残留会让新会话的停止请求带上旧执行 id → 服务端校验不过、**停止失效**。

### 修复

- `ConversationChannel.subscribe()` 中，当且仅当**切换到不同会话**时（在改写 `sessionId` 前判定
  `session != sessionId`）复位上述会话级状态，并在复位清单中加入 `foregroundExecutionId = null`。
- **同会话重订不复位**：A-2 自动重订 / 用户手动重试走的是同一 `subscribe()`，若一并复位会让
  用户正在看的标题与相位**闪一下再回来**，故用 `switchingSession` 闸门区分。

### 验证

- 新增仪器化 `ConversationChannelStateResetInstrumentedTest`（2 项，**不依赖 Espresso**，
  HANDOVER 记录的「API 37 真机可用」套路）：
  ① 会话 A 翻页拉到头后切到 B，断言 **B 的翻页门控已复位**（`!pulled`，修复前必失败——
     `_earlier` 不重置则 `(hasMore || !pulled)` 恒假）；
  ② **同会话重订保留 `_earlier`**（防标题/相位闪烁的回归护栏）。
  用例不依赖中继：桥未就绪时 `loadEarlier` 走同步错误分支，恰好把 `_earlier` 置成
  `(loading=false, hasMore=false, pulled=true)`，可在无网络下稳定复现。
- JVM 门禁：单测 **313/313**；`ktlintCheck` 0 违规；`detekt` 通过；
  `lintDebug`/`lintRelease` 0 error；`assembleDebug`/`assembleRelease`/
  `assembleDebugAndroidTest` 通过。
- **真机红-绿验证（2026-10-16，小米 15 Pro / Android 17，`am instrument` 直跑，不依赖 Espresso）**：
  - **修复前版本 → 失败**：`Tests run: 2, Failures: 1`，失败断言正是
    「切到 B 后不得继承 A 的 pulled」（`_earlier` 未复位）。**证明用例能捕获该缺陷，非假阳性**。
  - **修复后版本 → 通过**：`OK (4 tests)`（含 `ConversationOfflineSwitchInstrumentedTest` 2 项）。
  - 注：本机为 release 签名包（非 debuggable），故用 `adb install -r` + `am instrument` 直跑
    （绕开 Gradle 的 UTP 安装流程，其在 MIUI 上会被 `INSTALL_FAILED_USER_RESTRICTED` 拦）。
- 仪器化用例同时进入 CI 的 API 35 emulator 门禁（`.github/workflows/ci.yml` 的
  `:app:connectedDebugAndroidTest`），防后续回归。

## 未发布（2026-10-16）· 走查第七批：断开态下切换会话不再显示上一个会话的内容

> 真机走查附带发现并定性。**用户可见**：断开连接后点开会话卡片，会话页显示的是
> **上一个会话**的标题与内容行——点 A 看到 B 的内容。

### 问题（三处代码共同作用）

- `ConversationRepository.subscribeConversation()` 原以 `val conv = conversation() ?: return`
  开头：**断开连接**（`disconnect()` 已把 `conversation` 置 null）或未定位到工作区时，
  整个方法**提前返回**，跳过其后的 `rowStore.clear()`。
- `rows` / `conversationMeta` 是**单一共享实例**（`ConversationRepository.kt:41-42`），
  非按会话隔离；`disconnect()` 也不清它们。
- `ConnectionCoordinator.kt:359-360`：桥就绪时会**自动订阅**运行中的会话，
  使「上一次订阅的会话」有实际内容可残留。

结果：断开态下点任意卡片，`title = conversationMeta.title ?: target.title` 取到旧标题、
`rows` 取到旧行，界面呈现的其实是**别的会话**的遗留视图。

### 修复

- `ConversationRepository.subscribeConversation()` 重构为「**视图切换始终执行**」：
  草稿切换 / `subscribedSessionId` / 清行 / 读本会话离线缓存一律照做，
  仅在**无法发起网络订阅**（`conv == null` 或 `ws == null`）时跳过 `conv.subscribe()`，
  并把派生状态（meta / earlier / sessionState / snapshotAligned / status）清空——
  断开态下这些无人再刷新，不清会一直停留在上一个会话。
- 修复后与既有产品契约一致：README 的「会话**离线秒开**——点击任意会话卡片立即呈现
  该会话最近 200 行历史消息」在断开态下同样成立（显示的是**该会话自己**的缓存行）。
- 保留原有早退时不改 `subscribedSessionId` 之外的行为不变性：草稿/附件/回显气泡的清理
  时序与在线路径完全一致。

### 验证

- 新增仪器化 `ConversationOfflineSwitchInstrumentedTest`（2 项，复用
  `ConversationDraftInstrumentedTest` 的 `conversation = { null }` 断连套路）：
  ① 断开态下先渲染会话 B 的行、再点开 A，断言 **A 不残留 B 的内容行**（修复前必失败）；
  ② 断开态切换会话时草稿键跟随——不得把 A 的草稿带进 B。
- JVM 门禁：单测 **313/313**；`ktlintCheck` 0 违规；`detekt` 通过；
  `lintDebug`/`lintRelease` 0 error；`assembleDebug`/`assembleRelease`/
  `assembleDebugAndroidTest` 通过。
- **仪器化用例在 CI 的 API 35 emulator 上执行**（本机真机为 API 37，测试库受限，见 HANDOFF）。
- **真机走查（2026-10-16，小米 15 Pro / Android 17，修复后包覆盖安装、配对保留）**：
  精确复现原缺陷场景——冷启动 → 等桥就绪自动订阅 → 断开 → 点开一个**不同于**自动订阅目标
  的会话。修复后会话页正确显示**所点会话自己的标题**、**无残留内容行**，并由 #11 空态接管
  （「建立连接中… · 已等待 N 秒」且 N 逐秒递增：7s → 21s）；`logcat -b crash` 无 FATAL。
  对比修复前同一操作显示上一个会话内容行的现象，**修复得到真机确认**。

## 未发布（2026-10-16）· 走查清单第六批：首页连接失败态不再误报「正在连接」

> 承接第五批（会话空态）。走查 #11 的**后半**：首页会话列表空态的占位文案此前只覆盖两种状态，
> 其余全落 `else -> 「正在建立安全连接…」`，导致 `RelayState.Failed` 也被显示成「正在连接」。

### 问题

- `HomeScreen` 的 `sessions.isEmpty()` 空态用 `when (state)` 只匹配 `WaitingPeer` / `Paired`，
  **其余全部回落到 `else -> "正在建立安全连接…"`**。
  于是 `RelayState.Failed` 也显示「正在连接」——其中 `KICKED` / `AUTH_FAILED` /
  `PROTOCOL_MISMATCH` / `INVALID_ENDPOINT` 是**终态失败**（`RelayClient.fail()` 明确置
  `terminalFailed=true`、不参与自动重连），界面会永久停在「正在建立安全连接…」：
  用户以为还在连、实则永远不会成功，也无从知道要去设置页重新配对。

### 修复

- 新增 `ui/screens/HomeConnectionInfo.kt`：
  - 纯函数 `connectionEmptyStateText(state)`——逐状态映射文案：连接类三态合一、`WaitingPeer` /
    `Paired` 保留原文案、`Failed` 按 `FailureReason` 分派到可操作文案（接管/配对失效/协议升级/
    地址不安全/通用重试中）。
  - 纯函数 `isTerminalFailure(state)`——与 `RelayClient.fail()` 的终态判定**同源**（那四处
    不自动重连），决定是否给出「前往设置」引导。
- `ui/screens/HomeScreen.kt` 空态改用上述函数：失败态文案用 `error` 配色；终态失败在文案下方
  增设「前往设置」按钮（非终态失败不给，它们会自行退避重连，给了反而误导）。
- `res/values/strings.xml` 新增 8 条 `home_conn_*` 文案。

### 验证

- 新增 `HomeConnectionInfoTest`（9 项）：逐状态文案、**「失败态绝不再显示为正在连接」的
  全枚举回归**、终态判定与 `RelayClient` 同源、非失败态不误判终态。
- 单测 **313/313**（含新增 9 项）；`python tools/check_test_count.py --min 264` 通过。
- `ktlintCheck` 0 违规；`detekt` 通过；`lintDebug`/`lintRelease` 0 error（新增字符串均被引用，
  未引入新的 `UnusedResources`）；`assembleDebug`/`assembleRelease` 通过。
- **真机走查（2026-10-16，小米 15 Pro / Android 17 / API 37）**：
  - **已通过（冒烟 + 无回归）**：同上，带本批修复的 release 签名包覆盖安装后冷启动无 FATAL，
    首页 / 会话页 / 设置页 / 新建弹窗渲染正常；`aapt2 dump resources` 确认 `home_conn_connecting` /
    `home_conn_failed_auth` / `home_conn_failed_kicked` 已进 APK。
  - **失败态未走到（环境不可达，非缺陷）**：首页空态需 `sessions.isEmpty()`，
    而 `disconnect()` **有意保留**会话缓存供离线浏览（列表清空只发生在 `forget()` 清除配对）。
    在真机触发需清掉配对再重扫码，代价过高，未做。文案分派与终态判定由 `HomeConnectionInfoTest`
    （9 项，含「失败态绝不再显示为正在连接」全枚举回归）覆盖。
  - **过程中一处已恢复的操作**：为尝试触发失败态点过设置页「断开」，随后 force-stop 重启 App，
    状态回到「就绪」，配对凭据未受影响。

## 未发布（2026-10-16）· 走查清单第五批：会话空态显示同步阶段与已等待秒数（任务书 §7）

> 承接走查清单 P2 #11。任务书 §7 验收标准要求「空态须显示当前阶段与已等待秒数」，
> C-3 条目把它记为「Status 细粒度化，留待需要时立项」——本轮落地该项。**仅空态文案增强，
> 不碰协议、不改状态机。**

### 问题

- 会话页在无行可渲染时只显示一句静态的「等待会话同步…」：
  ① 说不出**当前卡在哪个阶段**（连接 / 握手 / 订阅），用户无法判断是正常建连还是已卡死；
  ② 不给**已等待时长**，用户没法据此决定要不要点顶栏的「重试」；
  ③ 「已订阅但会话确实为空」与「还在同步」这两种语义完全不同的情况，显示同一句话。

### 修复

- 新增 `ui/screens/ConversationSyncInfo.kt`：
  - 纯函数 `syncHeadline(status, isDraft, elapsedSeconds)`——按 `Status` 映射阶段文案，
    等待态带「· 已等待 N 秒」；`isDraft`（新会话草稿）优先于一切；`Live`（已订阅无行）
    呈现为「会话暂无内容」而非「还在同步」；`Failed` 指引点顶部「重试」。
  - 纯函数 `syncSubtext(status, isDraft)`——草稿态给发送引导、等待态解释在同步什么、
    失败/空会话态返回 null（主标题已说清，不重复）。
  - 纯函数 `isWaitingForSync(status)`——计时开关（仅 Idle/Hello/Initialized 计时），
    供计时协程与单测共用同一判定。
  - `EmptySyncHeadline` 组件——唯一的计时驱动点（`LaunchedEffect` 每秒推进），
    `waiting` 由 false 变化时协程结束、秒数冻结；阶段迁移（Idle→Hello→Initialized）不重启计时，
    显示的是**累计等待时长**而非分阶段时长。
- `ui/screens/ConversationScreen.kt` 空态区块改用上述组件（主标题 `EmptySyncHeadline` +
  副文案 `syncSubtext`，原硬编码中文串删除）。
- `res/values/strings.xml` 新增 8 条 `sync_*` 文案（沿用 i18n 第 1 批的 key 命名与 `%1$d` 占位符约定）。

### 验证

- 新增 `ConversationSyncInfoTest`（14 项）：各阶段文案、草稿优先、失败/空会话不给秒数、
  秒数进入占位符、计时开关边界。单测 **304/304**（含新增 14 项）。
- `python tools/check_test_count.py --min 264` 通过（声明 304 = 执行 304）。
- `ktlintCheck` 0 违规；`detekt` 通过；`lintDebug`/`lintRelease` 0 error；
  `assembleDebug`/`assembleRelease` 通过。
- **真机走查（2026-10-16，小米 15 Pro / Android 17 / API 37）**：
  - **已通过（冒烟 + 无回归）**：带本批修复的 **release 签名包**覆盖安装（`apksigner` 指纹
    `1d46e9e8…` 与官方签名一致，配对凭据保留）；冷启动 `logcat -b crash` 无 FATAL；
    首页（98 会话列表）、会话页（166 行完整流式渲染：工具行 / 子智能体 / 流式态）、设置页、
    新建会话弹窗**全部正常渲染，无回归**；`aapt2 dump resources` 确认新增的 `sync_wait_handshake` /
    `sync_empty_session` 等字符串已进 APK。
  - **空态三态未走到（环境不可达，非缺陷）**：需**零行会话**才能渲染，
    而「立即创建」要求非空 prompt（会真实建会话并消耗额度），未在真机构造。
    文案逻辑由 `ConversationSyncInfoTest`（14 项）+ 全量单测覆盖。

## 未发布（2026-10-15）· 走查清单第四批：GitHub Token 明文暴露面收敛

> 承接同日三轮走查修复。**核实发现真实问题比原描述更广**（原描述只提内存驻留，实际更直接的是
> UI 明文铺屏）。无功能变化，仅收敛暴露面。

### 核实（原描述不完整）

- 走查 #10 称「`TokenStore.read()` 明文 String 长驻内存」——成立，但**漏了更直接的一处**：
  `UpdateRepository` 构造时 `var githubToken by mutableStateOf(settings.githubToken)` 解密一次后，
  明文一路传到设置页并被 `tokenDraft` **预填**进输入框——用户一打开设置页，**Token 明文就铺在屏幕上**
  （且输入框无掩码、键盘为明文类型）。

### 修复

- **不再预填、不再明文显示（UI 层，主要修复）**：
  - `SettingsTab` 的 `tokenDraft` 改为空白起手（原 `remember(githubToken){ mutableStateOf(githubToken) }`
    会在进入设置页时载入真实 Token）；
  - 输入框加 `PasswordVisualTransformation` + `KeyboardType.Password`（圆点掩码）；
  - 参数 `githubToken: String` → `hasGithubToken: Boolean`，UI 只据「是否已配置」显示状态；
  - 保存后清空草稿；占位文案提示「已配置（留空保存 = 清除）」，`保存` 时空草稿即清除并提示
    「Token 已清除」。
- **不再长期持有明文（状态层）**：`UpdateRepository` 的 `var githubToken`（明文串驻留 Compose 状态）
  改为 `hasGithubToken: Boolean`；真正的明文只在 `checkForUpdate()` 内按需读取（`settings.githubToken`），
  随局部变量释放。`AppViewModel` 同步改暴露 `hasGithubToken`。

### 验证

- 单测 **291/291**；`ktlintCheck` 0 违规；`detekt` 通过；`lintDebug`/`lintRelease` 0 error；
  `assembleDebug`/`assembleRelease` 通过。
- 本轮无新增单测：改动集中在 Compose UI 与设置状态，仓库无 Robolectric/Compose 单测基建。
  **真机走查建议（下一轮）**：打开设置页确认输入框为掩码且为空、占位提示正确；
  配置 Token → 收起再展开确认不回显；留空保存确认清除且提示正确。

## 未发布（2026-10-15）· 走查清单第三批：连接韧性调度任务的作废缺口

> 承接同日两轮走查修复。**核实推翻了原描述**并修掉其中的真实缺口。无用户可见行为变化。

### 核实（原描述与代码不符）

- 走查 #6 称「`RelayClient`、`RemoteConnectionManager` 的心跳/重连 Job 不取消，全靠手动 `close()`」
  ——**不成立**：`RelayClient.close()` 内部已 `io.cancel()`（`heartbeatJob`/`pumpJob`/`reconnectJob`
  三者的启动点全部在该作用域内），`RemoteConnectionManager.shutdown()` 也已 `scope.cancel()`。

### 修复（真实缺口）

- **`ConnectionCoordinator` 的韧性调度任务在连接重建时不被作废（#6 的真实版本）**：
  `handshakeRetryJob`（握手自动重订）与 `bridgeReopenJob`（桥自动重开）跑在**进程级
  `RemoteConnectionManager.scope`** 上——这是 §11.4 阶段③ 的刻意设计（使 ViewModel 被清后
  自愈仍生效），代价是它们**不在** `cancelCollectors()` 的管理范围内。
  而 `connect()` 重建连接栈时只调用了 `cancelCollectors()`，于是旧连接栈遗留的退避任务
  到点后会继续执行——`scheduleBridgeReopen` 内部读的是 `ConnectionScope.channel`（当前静态字段
  = **新**实例），因此会对着新连接栈重复 `openBridge` / 重复订阅，进而触发协议违例与重同步。
  - 新增 `ConnectionCoordinator.cancelResilienceJobs()`：统一作废上述两个任务并把
    `handshakeRetryCount` / `bridgeReopenCount` 归零；`resetHandshakeRetry()` 保持原语义
    （仅重置握手重订），供「新订阅 / 删会话」这类**只该重置握手预算**的调用点继续使用。
  - 调用点：`connect()` 重建连接栈前（紧随 `cancelCollectors()`）、`disconnect()` 中
    （原 `resetHandshakeRetry()` 升级为 `cancelResilienceJobs()`）。

### 验证

- 单测 **291/291**（本轮无新增：`ConnectionCoordinator` 需 `Context` + 多个 repo 注入，
  仓库无 Robolectric，无法 JVM 单测覆盖——靠逐行代码审计 + 全门禁兜底，此处如实标注）；
  `ktlintCheck` 0 违规；`detekt` 通过；`lintDebug`/`lintRelease` 0 error；
  `assembleDebug`/`assembleRelease` 通过。
- **真机验证建议**（下一轮）：连接重建后观察 logcat 中 `bridge re-open` 与
  `remote.rpcFrame.*` 违例计数，确认无「重连后立刻重复开桥」序列。

## 未发布（2026-10-15）· 走查清单第二批：HTTP 客户端复用 + 网络安全策略显式化

> 承接同日「走查清单核实与修复轮」，处理剩余 P1 中风险最低、收益明确的两项。
> 无用户可见行为变化，不改版本号、不触发 tag/Release。

### 修复（资源管理）

- **`OkHttpClient` 未复用（P1-4）**：`RelayClient`（每次 `connect()` 新建实例）与
  `repo/UpdateRepository` 原先各自 `OkHttpClient.Builder().build()`，每次重连都新建一套
  连接池 / 线程池 / DNS 缓存，且旧实例从不 `shutdown()`，长期运行持续累积。
  新增 `util/HttpClientProvider`（进程级单例，**显式钉死** `ConnectionPool(5, 5min)` 与
  `Dispatcher(64/5)`，避免 OkHttp 版本默认值漂移），调用方一律 `newBuilder()` 派生——
  **共享底层资源、超时各自独立**，两处既有超时参数（中继 20s ping/10s connect；
  更新检查 10s connect/15s read）**逐字保留**，行为不变。
  单例**不主动 shutdown**（中继与检查更新随时可能发起请求，关停连接池只会让后续请求重建）。

### 修复（安全配置可审计化）

- **网络安全策略显式化（P1-7）**：此前明文策略只写在 Manifest 属性与代码注释里，现固化为
  `res/xml/network_security_config.xml`（主源集：禁明文 + 仅信任系统 CA，显式写出以免
  未来误加 `src="user"` 静默放宽）；debug 源集同名资源覆盖（放开明文，供局域网 `ws://` 调试
  ——同时声明时 **networkSecurityConfig 优先于 usesCleartextTraffic**，故必须在 debug 显式放开）。
  Manifest 增 `android:networkSecurityConfig`，**保留 `usesCleartextTraffic="false"`**
  （CI 对 release APK 的二进制断言依赖该属性，已确认不破坏）。

  **⚠️ 本轮刻意未做证书固定（CertificatePinner / pin-set），理由（取证）**：
  - `zcode.z.ai` 使用 **`*.z.ai` 通配符证书**，Sectigo DV 签发，**2026-11-27 到期**（每年轮换）；
    链为 `*.z.ai` → Sectigo Public Server Authentication CA DV R36 → Root R46。
  - 本项目为**侧载分发、无自动更新渠道**：硬钉叶证书或中间 CA，一旦官方轮换/更换 CA，
    App 将**彻底无法连接（含配对）**，用户只能等新 APK 手动安装。
  - 钉根 CA 同样受「官方可能更换 CA」影响，且根证书被大量站点共用，对 MITM 边际防护有限。
  - 综合判定：**不以可用性换取此处有限的防护增益**；既有防线（release 强制 wss://、明文关闭、
    HMAC 握手 proof、日志脱敏）不变。若后续官方给出证书轮换承诺，可在该文件 `<domain-config>`
    补 `<pin-set>`（支持同域多 pin，任一匹配即通过，便于灰度切换）。

### 验证

- 单测 **291/291**（本轮无新增测试：改动为配置与依赖注入，行为等价）；`ktlintCheck` 0 违规；
  `detekt` 通过；`lintDebug`/`lintRelease` 0 error；`assembleDebug`/`assembleRelease` 通过。
- CI 侧 release 产物明文断言（`usesCleartextTraffic=false`）不受影响（属性保留）。

## 未发布（2026-10-15）· 第三方走查清单核实与修复轮：`!!` 隐患 + 重连抖动 + 旧明文缓存回写迁移

> 来源：对 `HANDOFF.md` §5.1「2026-10-14 第三方全仓走查」15 项的**逐条代码核实**。
> 核实结论：**11 项成立、1 项已不成立、1 项大部分失效、2 项描述漂移**（详见 `HANDOFF.md` §5.1 核实段）。
> 本轮按优先级修复其中 3 项（均为小改动、收益明确）；已失效/漂移项在文档中就地标注。

### 修复（正确性）

- **`ConversationChannel` 附件上传读循环 `input!!`（P0）**：`input` 是回调闭包内被重赋值的可变局部变量，
  无法智能转换；在 `abort` 重入路径下 `input` 可能已被置空，`input!!.read(...)` 可 NPE。
  改为取不可变快照 `val stream = input` + 显式判空失败（`fail("附件读取流不可用")`）。
- **`HomeScreen` 会话异常原因 `s.lastError!!` 连用两次（P0）**：改为局部非空值
  `val errorReason = s.lastError?.takeIf { it.isNotBlank() }`，条件与文本同时消费同一值，
  消除两次 `!!` 与「判空后取值」之间的不一致窗口。

### 修复（连接韧性）

- **重连退避加抖动（P1）**：新增纯函数 `jitteredBackoffMs(attempt, factor)`
  —— 在 `reconnectBackoffMs` 基准上按 `0.5 + 0.5 * factor` 缩放，结果落在 `[base/2, base]`。
  **只缩短不延长**，不破坏既有「48s 封顶」承诺；越界 factor 夹取。调用点由
  `reconnectBackoffMs` 改为 `jitteredBackoffMs(reconnectAttempt, Math.random())`。
  动机：多端/多实例在同一确定性退避值到点重连会形成「重连雷群」压垮中继。
  原 `reconnectBackoffMs` 与其既有断言**保持不变**（纯函数语义未动）。

### 修复（安全，§11.6 敏感存储治理补完）

- **旧明文缓存无回写迁移（P1）**：beta44 的「新旧双读」只让旧明文继续可读，但只有文件**再次被写入**
  才转密文——**长期不再打开的旧会话文件会一直留明文**。本轮补上读侧主动迁移：
  - 新增纯函数 `legacyRewriteTarget(raw, encrypted)`：仅在「确为旧明文」且「加密成功」时返回回写目标；
    **加密失败返回 null（绝不回写明文）**——避免把「加密」做成「原样重写」的假迁移。
  - `SessionCacheStore.readDecoded` 命中旧明文时回调上层；`load`/`loadRows` 两条读路径均接入
    `rewriteLegacyAsEncrypted`，经独立 `migrationScope`(IO) + 既有 `writeMutex` **原子回写**。
  - **保留原 mtime**：迁移是格式转换而非内容更新，刷新时间戳会让过期文件被 TTL 误判为新鲜
    （`load`/`loadRows` 都会因 `isCacheStale` 删文件）。
  - **竞态防护**：迁移前在锁内**重新比对文件内容**，若已被 `save()` 写入更新数据则放弃迁移
    ——否则会用读取时捕获的旧明文覆盖刚写入的新数据。
  - 迁移幂等（已是密文则 `legacyRewriteTarget` 返回 null），失败静默仅告警（读路径不因迁移失败而失败）。

### 测试

- `PureFunctionsTest` 新增 `jitteredBackoffStaysWithinHalfToFull`：边界值（factor=0/1）、越界夹取、
  以及 6×21 组合的区间断言 `[base/2, base]`。
- `CacheCryptoTest` 新增 3 项：旧明文触发回写、已是密文不重复迁移、**加密失败绝不回写明文**。

### 验证

- 单测 **291/291**（+4；`check_test_count.py --min 260` 门禁仍满足）；`ktlintCheck` 0 违规；
  `detekt` 通过；`lintDebug`/`lintRelease` 0 error；`assembleDebug`/`assembleRelease` 通过。
- 本机工具链：`F:\AndroidTools`（JDK 17.0.20.1 + Gradle 8.11.1，AGP 8.5.2 兼容）。

### 说明

- **无用户可见行为变化**，不改版本号、不触发新 tag/Release。
- 走查清单其余项（`OkHttpClient` 复用、自建 `CoroutineScope` 生命周期、证书固定、
  `TokenStore` 明文驻留、无障碍、空态等）**本轮未动**，已在 `HANDOFF.md` §5.1 标注现状待后续立项。

## 未发布（进行中）· 国际化资源化（分批次推进）

> ⚠️ **状态：进行中，已完成第 1 批。** 默认语言仍为中文，**行为零变化**；本项是「把散落的
> 中文字面量抽到 `strings.xml`」的工程地基重构，**不是**要做英文界面（用户已明确无英文需求）。

### 背景与规模（实测数据）

全库用户可见中文字面量共 **561 条**（`ui/` 365、`relay/` 138、`repo/` 62、`notify/` 30、其余 ~20），
分布特点决定了实施方式：

- **仅 41 条在集中式对象里**（`ToolKindLabels` / `StatusPanelLabels` / `UserFacingError`），
  其余 **486 条散落在 30+ 文件**；资源化的收益主要在「文案集中管理 + 未来可翻译 + 可加门禁防硬编码」。
- **约 140 条在纯函数层**（标签/映射类），而 `stringResource()` 只能在 `@Composable` 调用，
  项目又刻意靠 **JVM 单测（无 Robolectric）** 保证质量 → 必须引入 `UiText` 抽象（返回资源 id + 参数，
  资源 id 是 Int 常量，单测仍可断言）。
- **107 条含 `$` 插值**（如 `"${sec}s 后自动决议"`），需逐条改签名/调用点传参，不能直接搬运。
- **日志（96 条）与协议常量/诊断码不进 `strings.xml`** —— 它们不可也不应本地化，保持原样。

### 已完成（第 1 批，2026-10-14）

- 新增 `util/UiText.kt`：`UiText.Res(资源id, 参数)` / `UiText.Raw(运行期文本)`，
  Composable 侧 `asString()`、非 Compose 侧 `resolve(context)`。
- 新增 `res/values/strings.xml`（公共文案 + 工具家族标签；默认语言 = 中文，与原子面量逐字一致）。
- `ToolKindLabels` 的 `label` / `runningLabel` / `statusLabel` / `primaryText` 改返回 `UiText`；
  消费点 `ConversationTimeline` 经 `asString()` 解析；`primaryText` 顺带拆出 `searchPrimaryText` 降复杂度。
- `detekt-baseline.xml` 更新 `label` 条目签名（`: String` → `: UiText`），条数不变（无新增抑制）。

**验证**：单测 **287/287**；lint 双变体 0 error；detekt、ktlint 0 违规；`assembleDebug`/`assembleRelease` 通过。

### 待做（后续批次，按文件域分批提交，每批独立保绿）

| 批次 | 范围 | 条数 | 说明 |
|---|---|---|---|
| 2 | `StatusPanelLabels` | 24 | 纯函数，同上模式 |
| 3 | `UserFacingError` | 17 | **最微妙**：映射表改为「英文键 → 资源 id」，测试断言同步改 |
| 4 | `ui/screens/` 各屏 | 365 | 最大头，可按屏再分（HomeScreen 59 / SettingsTab 66 / ConversationTopBar 27 …） |
| 5 | `notify/` + `repo/` + `widget/` | ~93 | 有 `Context`，用 `resolve(context)` |
| 6 | `relay/` 用户可见错误文案 | ~46 | **需逐条甄别**用户可见 vs 日志/协议，只搬前者 |

**说明**：全部做完预计 8–12 批提交。若后续决定不做英文，批次 2–3（纯函数标签层）仍有
「集中管理 + 可加防硬编码门禁」的确定收益；批次 4–6 属可选深水区。

## v0.5.0-beta46（2026-10-14）· 弃用 API 清理轮：编译警告归零

### 工程改进（无用户可见行为变化）

- **图标迁移到 `Icons.AutoMirrored`（8 处）**：`Icons.Filled.Send` / `List` / `KeyboardArrowRight`
  在 RTL（从右到左）语言下应镜像显示，Material 已将其标记弃用并迁移到
  `Icons.AutoMirrored.Filled.*`。本项目界面为中文（LTR）当前不受影响，但迁移后 RTL 语言
  将正确镜像，且清掉了编译警告。涉及会话页导航、输入栏发送/停止、状态面板、折叠行箭头、
  工具家族「待办」图标。
- **通知 `setPriority` / `PRIORITY_HIGH` 死代码移除（4 处）**：自 Android 8 起通知优先级由
  **通知渠道**决定，`setPriority` 已失效——本项目 minSdk 31，且审批/表单两个渠道均为
  `IMPORTANCE_HIGH`，故这两行是纯死代码（高优先级由渠道保证，行为不变），移除以免误导。
- **描边规范化（2 处）**：`ButtonDefaults.outlinedButtonBorder`（已弃用，且语义不符——那是给
  按钮用的）被两处**装饰性 `Surface`** 误用；改为显式 `BorderStroke(1.dp, …)` 与本页统一的
  `CardDefaults.outlinedCardBorder()`，语义更清晰。

### 验证

- **编译期 deprecation 警告归零**（14 → 0，`compileDebugKotlin --rerun-tasks` 实测）。
- JVM 单测 **287/287**；`assembleDebug` / `assembleDebugAndroidTest` / `assembleRelease` 通过；
  `lintDebug` + `lintRelease` 0 error；`detekt` 通过；`ktlintCheck` 0 违规。
- **release 体积零增长**：5065543 字节（纯 API 迁移，无新增资源）。

## v0.5.0-beta45（2026-10-14）· 工程清债轮：界面预览、脚本索引与文档可移植化

### 工程改进（无用户可见行为变化）

- **关键界面补 `@Preview`（布局预览）**：本项目本机**跑不了模拟器**（兆芯 CPU 无虚拟化），
  UI 改动原先只能编译装机到真机看效果，样式迭代慢。本轮新增 `ui/preview/` 两个预览文件
  （时间线全部行类型、折叠行展开态、浅/深色主题、审批卡（含/不含倒计时）、输入栏三态、
  自动决议倒计时三档），配 fixture 数据，Android Studio 里可直接看到真实渲染。
  **架构要点**：预览放在 **`src/debug/` 源集**（非 main），并补 `debugImplementation("ui-tooling")`
  渲染器——实测 **release APK 体积零增长**（5065543 字节，与改动前逐字节一致），预览代码
  完全不进发布包。真机验收仍是最终门禁。
- **`tools/` 脚本索引**：新增 `tools/README.md`，把脚本按「当前使用（CI 门禁 / 日常）」与
  「历史一次性验证脚本（`_` 前缀）」分类，并约定命名规则。此前 26 个脚本无任何索引，
  新接手者需逐个读头部猜用途；`_` 前缀脚本被 CHANGELOG 广泛引用作历史证据，故**就地保留 +
  建索引**（不物理移动，避免破坏引用链）。
- **文档硬编码本机路径可移植化**：`HANDOVER.md` 中 4 处构建命令硬编码了 `F:/AI/Zcode/…`
  绝对路径（新会话复制即失败），改为 `$(cd .. && pwd)/toolchain/…` 相对推导；
  `tools/enumerate_rpc.py` 的探针回退路径同样去本机化。

### 验证

- JVM 单测 **287/287**；`assembleDebug` / `assembleDebugAndroidTest` / `assembleRelease` 通过；
  `lintDebug` + `lintRelease` 0 error；`detekt` 通过；`ktlintCheck`（含新 debug 源集）0 违规。
- **release 体积零增长**：5065543 字节（预览移入 debug 源集后与改动前完全一致）。

## v0.5.0-beta44（2026-10-14）· 会话正文离线缓存加密（§11.6 安全门）

### 安全加固

- **会话正文缓存由明文改为 AES-256-GCM 加密落盘**：此前配对凭据、GitHub Token 都已用
  Android Keystore 加密，唯独占比最大、最敏感的**会话正文**（每个会话最近 200 行消息与
  会话列表标题）以**明文 JSON** 写入 `filesDir/rows_<sid>.json` 与 `sessions_cache.json` ——
  设备被 root / 备份提取 / 同沙箱读取即可明文检索。本轮补齐（任务书 §11.6「敏感缓存与
  存储治理」的 P1 安全门）：
  - **复用既有 Keystore 能力**（遵循 §11.6 「禁止引入 EncryptedSharedPreferences」，沿用
    `CredentialCrypto` 的 AES-256-GCM 实现），但使用**独立密钥别名** `zcode_remote_cache_key`
    ——与配对凭据的主密钥隔离，二者互不牵连；
  - **版本化信封 + 新旧双读**：文件以 `ZCE1:<ivB64>:<dataB64>` 前缀标识加密格式，
    读侧据前缀分流——旧明文缓存**照常可读**（迁移期不丢用户离线数据），新写入一律加密；
  - **安全失败**：加密器不可用（极少数设备 Keystore 异常）时**跳过落盘、绝不回退明文**；
    密文损坏/篡改/密钥不可用 → 读侧返回空缓存（不崩溃、不落明文）。
  - 字节预算仍按**明文**口径判定（加密后因 base64 膨胀约 1/3，按明文算更保守），行数/TTL/LRU 策略不变。

### 验证

- JVM 单测 **287/287**（+8：`CacheCryptoTest` 覆盖信封往返、旧明文不误判、畸形信封安全失败、
  双读编排、加解密失败返回 null）。
- 新增仪器化 `CacheEncryptionInstrumentedTest`（真实 Keystore，随 CI `instrumentation` job）：
  写入含 canary 正文的缓存后，断言**落盘文件字节里搜不到 canary 明文**且以加密信封前缀开头，
  同时读回能正确还原——直接对应 §11.6 的验收「应用数据目录不能搜索到 canary 正文」。
- `assembleDebug` / `assembleRelease` 通过；`lintDebug` + `lintRelease` 0 error；`detekt` 通过；
  `ktlintCheck` 0 违规。

## v0.5.0-beta43（2026-10-14）· 交互修复轮：会话草稿隔离与审批倒计时实时化

### 修复

- **会话草稿按 taskId 隔离（防串会话错发）**：`promptDraft` 原是 `AppViewModel` 的**单一全局
  字段**，切会话时不清空——在会话 A 打了半句话切到 B，输入框仍是那句话，此时点发送会把半句
  **错发到 B 会话**（附件与会话绑定、草稿却没有，两者不一致）。现在草稿存储移入
  `ConversationRepository` 并按 `taskId` 保存/恢复（切走时存回旧会话键、切到时恢复目标会话
  草稿），上限 LRU 20 个会话防无界增长；断开/忘记设备时全部清空。`AppViewModel.promptDraft`
  改为转发，UI 调用签名不变。切换时序抽成纯函数 `switchDraft`（存旧必须在改
  `subscribedSessionId` **之前**，杜绝写错键）。
- **审批自动决议倒计时逐秒刷新**：此前两处审批卡（待办 Tab / 会话内联卡）在**组合期**用
  `System.currentTimeMillis()` 算一个静态秒数并渲染——数字只在别处触发重组时才跳变，
  用户盯着看往往是一个**不动的秒数**。新增 `ui/components/AutoResolveCountdown`（`LaunchedEffect`
  每秒推进，到点停止）替换两处；文案规则抽成纯函数 `countdownLabel`（剩余不足 1s 向上取整为
  「1s」、到点显示「即将自动决议」，不出现「0s 后自动决议」这类像出错的文案）。

### 工程

- **debug 变体可选 release 签名开关**：新增 `-PdebugUseReleaseSigning=true`（默认关闭，与既有
  `benchmarkUseReleaseSigning` 同一模式）——开启且本机有 `keystore.properties` 时 debug 变体改用
  release 签名，可**同签名覆盖安装**已装 release 包（免卸载、保配对凭据），从而在真机上跑
  `connectedDebugAndroidTest`/手动 instrument。已验证默认关闭时 debug 仍为 debug 签名、CI 行为不变。

### 验证

- JVM 单测 **279/279**（+15：`DraftStoreTest` 10 项含 5 项切换时序、`CountdownLabelTest` 5 项）。
- 新增仪器化测试两类（`ConversationDraftInstrumentedTest`、`AutoResolveCountdownTest`），
  随 CI `instrumentation` job（API 35 emulator）运行。
- `assembleDebug` / `assembleRelease` 通过；`lintDebug` + `lintRelease` 0 error；`detekt` 通过；
  `ktlintCheck` 0 违规。
- **真机走查（小米 15 Pro / Android 17 / HyperOS）**：草稿隔离仪器化测试 **3/3 通过**——用真实
  `ConversationRepository` 实例验证「A 存草稿 → 切 B 为空 → 切回 A 恢复」与「清当前会话草稿
  不影响其它会话」，补上 ADB 无法向 Compose 输入框注入文本的端到端缺口（`input text` /
  `keyevent` 对该设备的中文候选输入法均不生效，属 UI 自动化已知限制）。
- **环境说明（既有，非本轮引入）**：设备为 **API 37（Android 17）**，其上 Espresso/Compose
  测试库的 `InputManagerEventInjectionStrategy` 因 `InputManager.getInstance()` 被移除而失败——
  既有的 `MarkdownRenderTest` 在同一设备**同样失败**，确证为测试库与 Android 17 的兼容问题，
  与业务代码无关；CI 的 API 35 emulator 不受影响。

## v0.5.0-beta42（2026-10-14）· 用户体验与生命周期修复轮：通知权限、网络回调与旋转状态

### 修复

- **通知权限运行时申请（P0）**：Android 13 起 `POST_NOTIFICATIONS` 为运行时权限且**默认拒绝**，
  而此前 Manifest 虽已声明却**全工程没有一处申请它**——装机后锁屏审批通知被系统静默丢弃，
  招牌功能直接失效且界面无任何提示。本轮新增 `notify/NotificationPermission`（纯判定：
  运行时权限 + 系统通知总开关双判），并在两处补齐：① **配对成功后申请一次**
  （仅尝试一次，不重复弹框、不打断既有操作流）；② **设置页新增「系统通知权限」状态行**，
  未授予时以错误色提示并给出「去开启」按钮（Android 13+ 走运行时申请，低版本直接跳系统
  通知设置页），状态在 `ON_RESUME` 刷新（从系统设置返回即更新）。新增 `NotificationPermissionTest`
  4 项纯逻辑单测（API 边界、低版本恒不申请、权限常量与 Manifest 一致）。
- **网络回调泄漏（P1）**：`NetworkGate` 每次 `connect()` 都 `registerDefaultNetworkCallback`
  却**从不注销**——切线路/换设备/重连会持续累积系统回调，旧回调闭包还持有已 `close()` 的
  旧 `RelayClient`。本轮新增 `NetworkGate.stopWatch()`（注销回调 + 清挂起等待者 + 置空回调，
  幂等且注销后不可再 `startWatch`），并由 `RemoteConnectionManager` 统一登记/释放闸门：
  连接栈重建前与 `disconnect()`/`shutdown()` 时均释放。
- **旋转 / 进程重建丢失会话页（P1）**：`MainActivity` 的 `opened`（当前打开的会话）用
  `remember` 保存，横竖屏切换或进程回收重建后被清空——用户从会话页被踢回列表，正在看的
  流式会话卸载。改为存 `openedTaskId`（`rememberSaveable`）+ 条目缓存，重建后按 taskId
  从会话列表回查元数据，旋转后仍停留在原会话。

### 改进

- **自适应图标与冷启动启动屏（观感）**：此前仅有一张 48dp drawable 充当应用图标，Android 8+
  下会以遗留形态被塞进圆形/方形蒙版（四周留白、观感偏旧）。本轮新增 `mipmap-anydpi-v26`
  自适应图标（前景/背景矢量 + 主题化 monochrome）与 Android 12+ **原生启动屏**
  （深色底 + 品牌图标），冷启动由「纯黑块直接闪出界面」变为「深色底 + 图标平滑过渡」。
  **零新增依赖**（用平台原生 `windowSplashScreen*` 属性，符合本项目依赖治理从严的既有策略）。
- **清理 `HomeScreen` 7 个死参数**：`bridgeState`/`rpcEvents`/`devices`/`activeSid`/`events`/
  `onDisconnect`/`onRescan` 在 `HomeScreen` 中从未被使用，而 `MainActivity` 每次重组仍为其
  执行 `vm.events.toList()`、`vm.rpcEvents.toList()` 等整表拷贝。已删除参数与对应实参/import。

### 治理

- 新增 **`LICENSE`（MIT）**：此前仓库无许可证，默认保留全部权利——他人无法合法 fork/使用/贡献，
  且 `dependency-review` 无法对本仓库做依赖合规判断。
- 新增 **`SECURITY.md`**：明确划分「客户端自身缺陷（负责修）」「上游协议固有性质（只做兜底）」
  「需向官方报告」三类，给出私下报告渠道与支持范围。
- **README 免责声明**：显式声明本项目为非官方第三方客户端、与 ZCode 官方无隶属/授权/背书关系、
  逆向复用协议的 ToS 风险与协议变更风险由使用者自负；同步修正测试数漂移（153 → 264 项）。
- **`HANDOFF.md` 由一句话指针升级为六大模块接力文档**（项目概况/技术栈基线/架构拓扑/最近变更/
  待办指引/避坑约束），作为新会话接手的第一入口；详尽历史证据仍以 `HANDOVER.md` 为准。
- **`app-android/README.md`** 版本标题由 `v0.5.0-beta6` 更正为「模块构建说明 + 指向根 README」，
  消除版本漂移。

### 验证

- JVM 单测 **264/264**（+4 新增 `NotificationPermissionTest`；`check_test_count.py` 核对声明的
  `@Test` 全部执行）。
- `assembleDebug` / `assembleRelease` 通过；`lintDebug` + `lintRelease` 0 error；`detekt` 通过；
  `ktlintCheck` 0 违规。
- **真机走查（小米 15 Pro / Android 17 / HyperOS，release beta42 覆盖安装、配对凭据保留）**：
  1. **旋转状态保持 ✅**——进入会话页（「分析 zcode-remote-app 仓库优化空间 / 已连接 · 运行中 · 共 424 行」）
     后强制横屏，**仍停留在该会话页**（顶栏返回/标题/状态齐全），修复前会被踢回列表。
  2. **通知权限申请 ✅**——撤销 `POST_NOTIFICATIONS` 后冷启动，系统弹出
     「允许"ZCode Remote"发送通知？」（uiautomator 抓到文本证据），P0 的主动申请真实生效；
     授予后设置页状态行显示「已开启，锁屏可收到审批推送」。
  3. **会话流渲染正常 ✅**——release 包在改动后流式画面、工具折叠行（终端/已执行/展开）、
     顶栏模式与模型胶囊均正常；`logcat -b crash` 与 `AndroidRuntime` 无 FATAL。
  4. **自适应图标 ✅**——`aapt2 dump badging` 确认 APK 已含 `application-icon`（编译后的
     adaptive-icon 资源），由 `@mipmap/ic_launcher` 装载。
- **待补**：自适应图标在启动器内的最终观感、冷启动启动屏过渡帧（启动屏一闪而过，
  属预期快速过渡；`windowSplashScreen*` 已由编译与无 inflate 崩溃间接验证）。
- **说明**：本轮 release 包在真机上的 `d/i/w` 日志已被 R8 剥离（预期行为），
  故走查以 UI 层（uiautomator dump + 截图）与中国大陆 ROM 行为为准。

## v0.5.0-beta41（2026-10-14）· 依赖治理轮（第三批）：二维码与 SBOM 工具升级 + AGP 跳跃拒绝

### 修复（Dependabot 第三批 3 个 PR，2 合并 / 1 拒绝）

**合并（本地门禁实测通过，6m1s）**：
- `com.google.zxing:core 3.5.3→3.5.4`（二维码解析，配对扫码路径）、`org.cyclonedx.bom 2.2.0→2.4.1`（发布链 SBOM 生成）——验证含 `cyclonedxBom` 实际产出。

**拒绝**：
- **`com.android.application (AGP) 8.5.2→8.13.2`（PR#21）**：跨 8 个 minor 的 AGP 大跳跃，且**AGP 与 Gradle 版本强耦合**——本项目 Gradle 钉 8.7（CI 五处 + `toolchain/gradle-8.7`），AGP 8.13 需更高 Gradle；单独升 AGP 会波及全部构建配置、发布链、lint/detekt/ktlint 门禁行为与 benchmark 模块。须作为专项协调升级（AGP + Gradle + wrapper 同步 + 通读 breaking changes + 全量回归）。

### 规则固化（追加）
- `.github/dependabot.yml` 再增三条 ignore：`com.android.application` / `com.android.library` / `com.android.tools.build:gradle`（AGP 系与 Gradle 强耦合，禁止单独升级）。

### 验证
- 单测 **260/260**（`check_test_count.py` 核对）；`assembleDebug`/`assembleRelease`/`cyclonedxBom` 通过。

## v0.5.0-beta40（2026-10-14）· 依赖治理轮（第二批）：专项库升级与漂移拒绝

### 修复（Dependabot 新一批 5 个 PR，3 合并 / 2 拒绝）

**合并（均经本地门禁实测后合入）**：
- `androidx.test:runner 1.6.2→1.7.0`（仪器化 runner）、`androidx.test.uiautomator:uiautomator 2.3.0→2.4.0`（基准测试 UI 定位）、`io.gitlab.arturbosch.detekt 1.23.7→1.23.8`（静态分析）——验证：`detekt` + `testDebugUnitTest` + `assembleDebugAndroidTest` + `:benchmark:assembleBenchmark` 全通过（3m29s）。

**拒绝（附机理/证据）**：
- **`com.mikepenz:multiplatform-markdown-renderer 0.27.0→0.45.0`（PR#17）**：同 HANDOVER 坑 14 硬约束——0.30.0+ 起用 Kotlin 2.1+ 编译，本项目 Kotlin 2.0.20 读不了其元数据；`0.27.0→0.45.0` 在 semver 上属 minor（0.x 语义）绕过 major 过滤。
- **`gradle-wrapper 8.7→8.14.6`（PR#18）**：**本项目的 Gradle wrapper 未被使用**——CI 五处显式 `gradle-version: "8.7"`、本地用 `toolchain/gradle-8.7`，无人调 `./gradlew`；只升 wrapper 文件会造成「仓库声明 8.14.6 vs 实际执行 8.7」的死配置漂移（且 Gradle 8.14 需 AGP 兼容评估，当前 AGP 8.5.2）。如需升级须与 toolchain/ + CI 的版本同步改。

### 规则固化（追加）
- `.github/dependabot.yml` 再增两条 ignore：`com.mikepenz:multiplatform-markdown-renderer*`（Kotlin 版本绑定）与 `gradle-wrapper`（未使用的死配置）——覆盖本轮实测的两类「会被 minor 语义放过」的升级。

### 验证
- 单测 **260/260**（`check_test_count.py` 核对）；`detekt` 通过；`assembleDebugAndroidTest`/`:benchmark:assembleBenchmark` 通过。

## v0.5.0-beta39（2026-10-13）· 依赖治理轮：Dependabot 积压处置 + Kotlin 生态升级冻结规则固化

### 新增（工程化；无用户可见行为变化）
- **Dependabot 升级冻结规则固化**（`.github/dependabot.yml`）：新增 `org.jetbrains.kotlin*` 与 `org.jetbrains.kotlinx*` 两条 ignore 规则。此前仅忽略 `semver-major`，但 **Kotlin 生态的 minor 升级同样会破坏构建**（详见下方修复），且 Dependabot 每周会重新开出此类 PR——把硬约束从文档注释固化为配置，消除持续噪音。

### 修复（依赖积压处置：10 个 Dependabot PR，7 合并 / 3 拒绝）

**合并（均经本地门禁实测后合入）**：
- **CI Actions**（5 项，零生产代码影响）：`actions/attest-build-provenance 2.2.3→2.4.0`（发布链 provenance 生成）、`actions/checkout 4.2.2→4.4.0`、`actions/setup-java 4.7.1→4.9.1`、`gradle/actions/setup-gradle 4.4.0→4.4.4`、`reactivecircus/android-emulator-runner 2.34.0→2.38.0`。
- **Gradle 依赖**（3 项，逐项本地实测）：`androidx.test.ext:junit 1.2.1→1.3.0`（含 androidTest APK 构建验证）、`androidx.graphics:graphics-path 1.0.1→1.1.0`、`org.jetbrains.kotlinx.kover 0.9.1→0.9.11`（含 koverHtmlReport 验证）。

**拒绝（Kotlin 版本硬约束，均附实测/机理证据）**：
- **`org.jetbrains.kotlin.plugin.serialization 2.0.20→2.4.21`（PR#11）**：① 只升单个 Kotlin 插件会让三个官方插件（android/compose/serialization）版本不一致 → Gradle 配置期冲突；② 触碰 HANDOVER 坑 14 硬约束（markdown 渲染库 0.27.0 锁死 Kotlin 2.0.x）；③ Dependabot 判其为 minor（4>0）绕过了 semver-major 过滤。
- **`kotlinx-coroutines-android 1.8.1→1.11.0`（PR#3）**：**本地实测编译直接失败**——1.11.0 用 Kotlin 2.2.20 编译，`kotlinx-coroutines-core.kotlin_module` 元数据版本为 **2.2.0**，而本项目 Kotlin 编译器（2.0.20）可读上限为 **2.0.0** → `compileDebugKotlin`/`compileReleaseKotlin` 双双 FAILED（`Module was compiled with an incompatible version of Kotlin`）。这是与坑 14 同源的「依赖用更高 Kotlin 编译 → 元数据不可读」机理。

### 验证
- 单测 **260/260**（`check_test_count.py` 核对）；`detekt` 通过；`ktlintCheck` 0 违规；`assembleDebug`/`assembleDebugAndroidTest`/`assembleRelease` 通过。
- 合并后工作区全门禁 **BUILD SUCCESSFUL in 3m59s**（144 tasks）。

### 遗留（登记，非遗漏）
- Kotlin 生态若需真正升级（如为用上新版 coroutines / markdown 渲染库），须**单独立项**：同步全部 Kotlin 插件 + 评估 `multiplatform-markdown-renderer` 替代方案 + 全量回归；不由 Dependabot 驱动。
- 两项硬门（A-5 逻辑 fragment / 出站物理分片）与 §11.10 `1.0.0` 发布门不变。

## v0.5.0-beta38（2026-10-13）· 真机验收轮：四项走查全通过 + benchmark 库真机兼容修复

### 真机验收（小米 15 Pro / Android 17 / HyperOS；release 装机、覆盖升级完整保留配对凭据）

- **协议灰度（beta33）✅ 通过**：运行中会话实时流式渲染（dump 实测 533 → 587 行持续增长，消息流内容与 PC 端操作实时同步）；**诊断导出 19 条事件零 `remote.rpcFrame.*` 违例**（无重同步/无入站溢出/无协议违例）；**切线路不串单**——切「官方备线」（端点不可达，属服务端事实）触发重连状态机逐级正确推进（Connecting×2 → Authenticating → Paired → bridge.open → ready），切回「自动判定」**5 秒恢复就绪**，恢复后**自动重订阅**、会话流式立即正常（无旧连接残留串帧）。
- **诊断导出（beta35）✅ 通过**：系统 SAF 保存对话框两轮真实落盘（`zcode-diagnostics-*.txt`，551B，时间戳命名规范）；`diag.export_written bytes=551` 埋点与文件精确对应；**内容脱敏达标**（仅事件码/状态名/耗时，无会话标题/正文/SID/凭据）；事件链完整（app.start → relay 状态机 → bridge → subscribe → export）。
- **界面回归（beta36）✅ 通过**：首页（设备 `就绪` + 84 会话）、会话页（标题/状态行/模式与模型胶囊/工具行与思考行渲染正常）、设置页（版本显示 `v0.5.0-beta37 (Build 49)`）、待办页（空态文案）全部正常。
- **长会话基准真机取证（beta37）✅ 通过**：`gradle :benchmark:connectedBenchmarkAndroidTest` 三场景 **3/3 全过（7m20s，CompilationMode.Full 口径）**——scroll 1407 帧 **0.36% janky**、streaming 11493 帧 **1.78% janky**、open 启动首帧 median **177.7ms**（与真机历史口径「1336 帧 0 janky」同量级）；**模拟器（Android 15）回归 3/3 保持通过**（升级无口径回归）。

### 修复（真机取证轮暴露，均已修）

- **Macrobenchmark 库升级 `1.3.4 → 1.5.0`：修复 Android 16+ 真机恒报 `Unable to confirm activity launch completion []`**。根因（三层证据链定位）：库 1.3.4 用 `pgrep -l -f <pkg>` 枚举被测进程并按「空格 split 位置解构」解析进程名；**Android 16+（SDK≥36）起 toybox 将 >15 字符进程名截断**——本包名 `com.zcode.remote` 恰 **16 字符**，实测被截为 `om.zcode.remote`（丢失首字符）→ 名称过滤全灭 → 进程列表恒空 → 库对空列表轮询 10s 后抛错。**证据**：① 真机 `od -c` 对比 `pgrep -l`（截断）与 `pgrep -l -a`（完整）；② 上游修复 aosp/3507001（SDK≥36 时追加 `-a`）；③ Python 逐字复刻 `FrameStatsResult.parse` 对真机样本全部解析成功 → 排除解析层、锁定进程枚举层。1.5.0 字节码确认含修复（`SDK_INT >= 36 → "-a"`）。**该缺陷与 App/benchmark 工程配置无关**（宿主渲染、fixture、签名均正常）；模拟器（Android 15）不受影响——精确解释「CI 全绿、真机必挂」。
- **benchmark 变体新增真机取证签名开关 `-PbenchmarkUseReleaseSigning=true`**（`app/build.gradle.kts`）：开启且本机有 keystore 时 benchmark 变体改用 release 签名 → 可直接**覆盖安装已装正式包（免卸载、保配对凭据）**；默认仍 debug 签名，**CI/无 keystore 环境行为不变**。

### 验证
- 单测 **260/260**（`check_test_count.py` 核对）；`ktlintCheck` 0 违规；`detekt` 通过；`lintDebug`/`lintRelease` 0 error；`assembleDebug`/`assembleRelease`/`:benchmark:assembleBenchmark` 通过。
- 真机与模拟器双双 3/3 实跑通过（见上）；baseline 比对沿用 10× 阈值（CI 噪声口径）。

### 遗留（登记，非遗漏）
- **真机走查清单四项已全部闭环**（协议灰度/诊断导出/界面回归/基准取证，见上）。剩余硬门不变：A-5 逻辑 fragment 与出站物理分片（无真实帧样本不实现）；§11.10 `1.0.0` 发布门（依赖连续两个 Beta 周期无严重回归）。
- 真机基准数（8 核 / Android 17）与模拟器基线（`bench_baseline_emulator.json`）分属不同口径，暂不并入同一比对基线文件（回退探测按同环境历史比对）。

## v0.5.0-beta37（2026-10-12）· §11.8 第二层门禁收尾：长会话 Macrobenchmark + 基线回退探测

### 新增（工程化/性能基线；无用户可见行为变化）
- **`:benchmark` 模块（Macrobenchmark）**：`com.android.test` 模块，针对 `:app` 的 `benchmark` 变体（`initWith(release)` 的 R8 产物 + **debug 签名**（CI/本机无 release keystore 也可构建）+ **`isDebuggable=false`**（macrobenchmark 硬要求））；`app/src/benchmark/AndroidManifest.xml` 声明 `<profileable android:shell="true">`。
- **宿主 Activity + 确定性 fixture**（`app/src/benchmark/java/com/zcode/remote/bench/`，**只进 benchmark 变体，不进 debug/release 包**）：`BenchmarkConversationActivity` 用 1200 行 fixture 渲染**真实 `ConversationScreen`**（行构成贴近真机会话：每 10 行 2 用户 + 4 助手 + 2 思考 + 2 工具，助手行含标题/列表/围栏代码块/GFM 表格等真实 Markdown），无中继、无配对、无网络即可量测；参数经 `am start -W "<intent-uri>"` 传入（rows / appendIntervalMs），`testTagsAsResourceId` 把 testTag 暴露给 UiAutomator（仅 benchmark 变体开启）。
- **三场景基准**（`benchmark/src/main/java/com/zcode/remote/benchmark/LongConversationBenchmark.kt`）：① **打开长会话**（1200 行冷启动，`StartupTimingMetric` + `FrameTimingMetric`）；② **上下来回滚动**（启动放 `setupBlock` 不计入测量，fling 上/下各一次）；③ **流式追加**（初始 600 行 + 每 33ms 追加一行、2s 采样窗口）。janky 口径 = `frameOverrunMs > 0`，与真机实测「1336 帧 0 janky」同源；`CompilationMode.Full` 每轮完整 AOT 编译后测量（口径 = 已编译启动，偏乐观；**绝对值不与真机「部分编译 + JIT」的日常状态直接比较**，只做同口径趋势比对——原因见下方修复段）。
- **`tools/bench_check.py`（结果解析 + 门禁）**：解析 `*BenchmarkData.json`（启动中位数 / 帧数 / `frameDurationCpuMs` 与 `frameOverrunMs` 的 P50·P90·P99 / janky 帧比例）→ 打印表格（可写 `$GITHUB_STEP_SUMMARY`）；**结构性硬门禁**：三场景齐备、帧数非零（防「空跑假绿」）、数值有限；**基线比对**：与 `--baseline` 文件按倍数阈值（默认 2.5×）比对，超限即失败；`--record` 生成基线。正/负向自测均通过（正常 0、缺场景 1、超基线 1、空目录 1）。
- **CI 新增 `benchmark` job**（`ci.yml`）：ubuntu runner + KVM 权限步骤 + android-emulator-runner，跑 `:benchmark:connectedBenchmarkAndroidTest`（`benchmark` 变体与 `com.android.test` 模块同名变体匹配；组装产物在 runner 上先完成），随后跑 `bench_check.py`（`--baseline tools/bench_baseline_emulator.json --factor 3.0`，`if: always()` 保证结果解析失败也可见）。
- **生产代码最小接线**：`ConversationTimeline` 的消息流 `LazyColumn` 增加 `testTag("conv-timeline-list")`（供基准用 UiAutomator 定位；生产包不开启 `testTagsAsResourceId`，该 tag 不可见，无行为影响）。

### 修复（CI 首跑实测踩坑，已修）
- **`connectedDebugAndroidTest` 跨模块波及新模块**：CI 的 instrumentation job 用不带模块前缀的 `gradle connectedDebugAndroidTest`，新增 `:benchmark` 模块后该命令把其 **debug 变体**的基准测试也一并执行——macrobenchmark 在 debuggable 包上直接拒绝（`ERRORS (not suppressed): EMULATOR DEBUGGABLE`），instrumentation 从绿转红。修法双保险：① `benchmark/build.gradle.kts` 用 `androidComponents.beforeVariants` **只保留 `benchmark` 变体**（debug 变体不再生成，`connectedDebugAndroidTest` 在根项目不再匹配到它）；② CI 命令改为显式 `gradle :app:connectedDebugAndroidTest`。
- **模拟器错误阻断基准**：macrobenchmark 默认把「在模拟器上跑」列为未抑制错误（`ERRORS (not suppressed): EMULATOR`）并拒绝测量——CI 环境只能跑模拟器。按官方推荐方式（`Errors.kt` 示例）在模块 `defaultConfig` 设置 `testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"`（AGP 经 `am instrument -e` 传递，不写进 APK manifest），并在 CI 命令上以 `-Pandroid.testInstrumentationRunnerArguments...` 再注入一次（覆盖路径不同、双保险）。判读口径不变：模拟器数值只做趋势信号。
- 顺带把宿主等待窗口从 10s 放宽到 30s（模拟器上未 AOT 编译的首帧与 UiAutomator 首次连接明显更慢）。
- **`CompilationMode.None` 在模拟器上三场景全部报 `Unable to confirm activity launch completion []`**（**⚠️ 归因修正（2026-10-13）：当时推断为「未 AOT 编译首帧超出确认窗口」，本地模拟器复现后证实真根因是 fixture 模板崩溃——见下「第五轮·真根因」；`Full` 编译模式因每轮状态一致仍保留为测量口径**）：库在 `am start -W` 后用 `dumpsys gfxinfo framestats` 轮询（硬编码约 10s）确认「出现带新帧的新窗口」（`[]` 指连窗口记录都没有，**不是** Activity 不存在——变体匹配经 `--dry-run` 证实装的是含宿主 Activity 的 `app-benchmark.apk`）。改用 `CompilationMode.Full()`：每轮 reset 后完整 AOT 编译（`cmd package compile` 在 emulator shell 可用——reset 命令同源成功可证），启动在窗口内可确认且每轮状态一致。**口径随之明确为「已编译启动（偏乐观），只做同口径趋势比对」**（代码 KDoc / README / HANDOVER / 任务书已同步）。官方正路是给 App 加 baseline profile 后用 `Partial`，登记为后续待办。CI script 另加无条件诊断输出（被测包/宿主 Activity 存在性计数、crash buffer、ActivityTaskManager 记录），基准失败时当轮日志即可定位根因。
- **（2026-10-13 第五轮·真根因）fixture 模板的字面 `%` 未转义 → 宿主首帧崩溃**：`BenchmarkLongConversation.ASSISTANT_PARAGRAPH` 文案含「增加 1.8%，…」，而该常量是 `String.format` 的模板（`.format(i)` 填 `%d`）——`%，` 被 Java Formatter 当作格式说明符 → `UnknownFormatConversionException: Conversion = '，'` → 宿主 Activity 首帧渲染抛异常、进程崩溃 → 库的 gfxinfo 轮询永远等不到完成帧 → `Unable to confirm activity launch completion []`（**`Full` 模式同样崩**——此前「未编译首帧超窗」的归因是误判，崩溃掩盖了真实原因）。修复：`1.8%` → `1.8%%`（`String.format` 语义转义，渲染仍为 `1.8%`）+ 防复发注释（改动该段文案先自查 `%`）。**定位路径**：本机模拟器（AEHD 2.2 实测可用）手动安装 `app-benchmark.apk` 后 `am start` 即复现，crash buffer 直出异常类型与崩点。
- **运行时探针 `contains` 判定错误（上轮引入）**：`requireHostInstalled` 断言 `cmd package resolve-activity --brief` 输出包含**全点分全限定名**——但该命令输出为「字段行 + 组件**短名**行」（短名把与包名重复的前缀压缩为 `/.`，如 `com.zcode.remote/.bench.BenchmarkConversationActivity`，AOSP `printResolveInfo` 行为），断言恒为 false，把「可解析」误报成「宿主 Activity 不存在」。已改为断言类简单名并补注释。
- **`bench_check.py` glob 大小写不敏感缺失（2026-10-13，CI 首次全跑到解析步骤时暴露）**：宏基准官方产出文件名为小写 b 的 `*-benchmarkData.json`，而脚本用 `*BenchmarkData.json` 做 glob——**Windows 本地 FS 不区分大小写故本地全绿，Linux/CI 严格区分 → 解析步骤报「找不到结果文件」直接红**（此时 emulator 上测试已 3/3 全过、结果文件已由 AGP 拉回，纯粹是解析脚本没找到）。修复：改 `os.walk` + `name.lower().endswith("benchmarkdata.json")`，跨平台一致；本地四用例验证（正常解析 / 两种大小写命名均命中 / 无关文件不误收 / 空目录负向保持）。
- **CI 基准比对阈值 3× → 10×（2026-10-13，CI 噪声实测驱动）**：CI 首次全绿后连续两轮同代码实测暴露**共享 runner 的模拟器帧时序跨轮噪声极大**（open P50 **37.3 → 552.1 ms**、scroll P99 96.6 → 172.0 ms，达 ~6.6×），3× 硬阈值必然误报（曾于推文档轮误红一次）。CI 侧阈值放宽为 **10×（只判「数量级回退」）**并注明理由；`bench_check.py` 默认 2.5×/3× 精确阈值保留给本地 AVD 与真机的同口径运行。
- **`android-emulator-runner` 的 `script` 多行块未被正确执行**：第二轮 CI 把 benchmark job 的 script 写成多行块（`cd app-android` + 反斜杠续行 + gradle），实测 `cd` 未生效、gradle 在仓库根执行并在 2 秒内报 `Run gradle init to create a new Gradle build in this directory`。修法：script 改回单行 `cd app-android && gradle ... --console=plain`（与 instrumentation job 同形式，后者一直有效）。

### 验证
- 单测 **260/260**（无 JVM 用例变化；`check_test_count.py` 核对声明 = 实际执行）；`ktlintCheck` 0 违规（含新增 benchmark 源集与 `:benchmark` 模块）；`detekt` 通过（`:benchmark` 模块不挂 baseline——新代码零告警）；`lintDebug`/`lintRelease`/`lintBenchmark` 0 error；`assembleDebug`/`assembleDebugAndroidTest`/`assembleRelease`/`assembleBenchmark` 通过；`:benchmark:assembleBenchmark`（测试 APK）通过。
- 产物核验：`app-benchmark.apk` 合并 Manifest 含 `<profileable>` 与宿主 Activity、无 `debuggable=true`。
- **本地 AVD 实跑（2026-10-13，apkrev35 / Android 15 / x86_64 / 4 核 / AEHD 2.2 硬件加速）**：`gradle :benchmark:connectedBenchmarkAndroidTest` **3/3 全过（0 失败，2m26s）**——macrobenchmark 首次完整跑通；宿主手动 `am start` 复验（crash buffer 空 + 标题「起始 1200 行」与消息流列表 `conv-timeline-list` 渲染断言通过）。**基线已按实况回填** `tools/bench_baseline_emulator.json`（`bench_check.py --record`，自比对复验通过）：open 启动首帧 median **1466.6ms**、scroll `frameDurationCpuMs` P50/P90/P99 **32.3/36.0/45.8ms**、streaming **31.7/34.2/43.9ms**（模拟器软件渲染口径，仅作趋势信号）。
- 门禁脚本正/负向自测通过（见上）；测试结果解析在 CI 上以真实产出复核（本轮新增 job 首跑）。
- **本轮为无真机批次**：真机灰度（beta33 协议改动 + beta35 诊断导出落盘）仍为下一轮第一件事；基准真机口径 = 人工在真机跑同一 gradle 任务后用同一脚本解析。

### 遗留（登记，非遗漏）
- ~~CI 模拟器首跑后需据实况回填基线文件~~ → **已回填（2026-10-13，本地 AVD 首跑记录，见验证段）**；注意 CI runner 模拟器为 2 核、本地 AVD 为 4 核，若 CI 基线比对超阈值（`--factor 3.0`，单向比对=仅报变慢）先核对是否环境差异再定夺。模拟器帧时序噪声大，真实性能验收以真机人工跑为准（README/HANDOVER 已写明判读口径）。
- 真机灰度（beta33 协议路径 / beta35 诊断导出落盘 / beta36 格式化后界面回归）与 A-5 逻辑 fragment、出站物理分片硬门不变；§11.10 `1.0.0` 发布门依赖上述项。

## 未发布（2026-10-15）· 仓库门面与展示素材专项：README 信息架构 + 锁屏审批头图 + 素材缺陷修复

### 新增（面向外部可见度与转化，无 App 运行时行为变化）
- **README 顶部信息架构重构**：中文/英文两版均新增 Hero 区——一句话定位 + **锁屏审批动图**；紧随其后新增「与官方网页版的关键差别」对照表（锁屏审批 / 网络抖动 / 离线秒开 / 后台保活四行）。此前「锁屏通知栏审批（核心差异点）」只是功能列表里的一行，现提升为首页首屏卖点。
- **隐私与数据声明区块前置（中英双语）**：在爆雷舆情背景下新增独立小节，明确四件事——① 客户端不向任何服务器上传数据（无遥测/埋点/账号/广告）；② 配对凭据只存本机（AES-256-GCM + Android Keystore，日志脱敏）；③ 诊断日志仅由用户主动导出（512 条环形缓冲 + 熵掩码，无自动上传通道）；④ **边界如实说明**（通信仍经官方中继，与官方网页版同链路，中继可见内容；本项目不额外留存）。与仓库既有 `SECURITY.md`、beta35 诊断缓冲的隐私边界一致。
- **锁屏审批展示素材（新增）**：`tools/render_screenshots.py` 新增 `render_lockscreen_approval()`，用既有设计系统（zai-dark 色板 + Material 几何图元）渲染锁屏通知栏审批三态（折叠通知 → 展开可决策「允许一次/总是允许/拒绝」+ 免解锁内联回复 → 已允许），产出静态图 `docs/screenshots/app-lockscreen-approval.png` 与动图 `docs/screenshots/lockscreen-approval.gif`（4 帧、520px 宽、约 125 KB）。
- **README 英文版补齐**：补 `### Screenshots` 区（此前英文段缺少界面一览）；补 `Visible Session Error Reasons` 功能条目（中文有、英文缺失）；修复英文 Milestones 表末尾因空行导致的**表格断裂**（`also includes bridge self-healing ... reads |` 与 `| Format Gate ...` 之间多了一个空行，渲染成两张表）。
- **仓库元数据（GitHub 侧）**：补齐仓库 description（中英双语一句话）与 8 个 topics（`zcode` / `android` / `kotlin` / `jetpack-compose` / `remote-control` / `ai-agent` / `ai-coding` / `glm`）——此前均为空，是外部「搜不到、点进来没信息」的直接原因。

### 修复（展示素材的真实缺陷）
- **标签文字被同色块吞掉**：`render_screenshots.py` 里三处标签（审批页右上「2 项待决议」、卡片内「工具调用 · Bash」与「实施方案待批准」）此前用半透明强调色直接作 `fill`，而 `ImageDraw` 在 RGBA 上**不做 alpha 混合**，导致底色与同色系文字叠加、文字完全不可读。新增 `chip_bg()`（把强调色按 alpha 混到卡片底色，得**不透明**背景）+ `draw_chip()`（居中排版），三处改为深底 + 强调色文字/描边。像素化验：修复后文字为 `(255,138,48)`/`(245,158,11)` 对深底 `(82,56,41)`/`(80,61,33)`，对比度 ≈ 4.5:1（WCAG AA）。
- **emoji 全部渲染成缺字方块**：msyh 字体不含 emoji 字形，实测 `⏳ 🔍 🎤 💻 ➕ ➤ ✓` 七类符号均渲染为 tofu 方块（如审批页「⏳ 28s 后自动决议」显示为「□ 28s…」）。改用**几何图元绘制**（`draw_magnifier` / `draw_mic` / `draw_monitor` / `draw_send`）与字体内可用字形（`＋` / `√`）；新增注释说明原因，避免后续回退。
- **锁屏卡回复行箭头缺字**：`➤` 同为缺字块，改为直接 `draw.polygon` 画三角形。
- **导航角标压住图标**：底部导航「待办」角标此前与图标重叠，改为加一圈背景色描边并偏移。
- **锁屏卡留白过大 / 文案重复**：卡片高度由 `1240–2150` 收紧到 `1240–1900`；折叠态第三行文案由「工具调用 · Bash 请求执行 · 下拉可内联处理」改为「工具调用 · Bash 请求执行 · 下拉展开」，与展开态正文去除重复。

### 验证
- `python tools/render_screenshots.py` 全量生成 5 张 PNG + 1 张 GIF 成功；README 引用的 10 处图片路径**全部存在**（脚本化校验，0 缺失）。
- 像素级化验：三处标签的强调色文字像素存在且底色为深色（修复前为同色叠加）；锁屏回复行 `y=1820` 扫描在 `x932–960` 命中主色蓝 `(124,158,255)`（三角发送符）。
- 未触碰任何 App 源码（`app-android/`）、未改动协议与测试，故不涉及单测/lint/detekt 门禁，亦不影响发布链产物。

### 说明
- 本页所有界面图为**同一套设计系统渲染的界面预览**（`tools/render_screenshots.py`），非真机录制；README 已就地标注此点，并给出动图不可播放时的静态图链接。
- 本次为展示层与仓库门面专项，**App 版本号与发布链不变**（远端最新为 `v0.5.0-beta46`），不触发新 tag/Release。

## v0.5.0-beta36（2026-10-12）· §11.8 第二层门禁补完：KtLint 格式门禁 + 依赖变更审查

### 新增（工程化；纯格式对齐 + CI 门禁，无用户可见行为变化）
- **KtLint 接入并纳入门禁**：新增 `org.jlleitschuh.gradle.ktlint`（插件 13.1.0 + **ktlint 核心 1.5.0 显式钉版**——规则键与版本强耦合，隐式跟随插件默认版本会在升级时静默改变门禁口径）；`ci.yml` 新增 `KtLint 格式检查（0 违规门禁）` 步骤。此前插件与 `.editorconfig` 只在文档里登记、**从未真正执行过**（CHANGELOG beta32 自述「不入门禁」）。
- **风格基线修正**：`.editorconfig` 里 `ktlint_function_naming_ignore_annotated` 是 ktlint 0.4x 旧键名（1.x 静默忽略 → 每个 `@Composable` 都会报命名违规），改为 `ktlint_function_naming_ignore_when_annotated_with`；并显式声明 `ktlint_code_style = ktlint_official`。**理由**：本项目既有风格本就属 official 系（处处尾随逗号、多行签名每参一行）；实测 `android_studio` 风格会**反向删除**尾随逗号、把多行签名压平（97 文件 +2517/−3346 的破坏性 churn），故不采用。
- **存量格式专项完成**：迭代 `ktlintFormat` 至收敛后，61 个文件纯格式对齐、**全源集 0 违规**（chain-method-continuation / class-signature / indent / trailing-comma / argument-list-wrapping / condition-wrapping / import-ordering 等）；
- **顺带清理（真实缺陷，非格式）**：① 3 处**悬空 KDoc**——同一函数上堆叠两/三段历史文档块（`ConversationChannel.resolve`、`uploadAttachment` 与一处重复单行注释），已合并为一段并挂回对应声明；② `HomeScreen` 的「新建会话弹窗」文档从孤儿位置迁到 `CreateSessionDialog` 声明上（此前文件里没有任何声明引它）；③ 命名与仓库常量惯例对齐：`NOOP_UPLOAD_HANDLE` → `noopUploadHandle`、`ZCodeDimens.BubbleWidthFraction` → `BUBBLE_WIDTH_FRACTION`。
- **依赖变更审查**（`.github/workflows/dependency-review.yml`）：PR 触发的依赖审查（`fail-on-severity: high`，失败时把结论评论到 PR）；与既有 Dependabot（**升级可见**）互补，补齐「升级有把关」。**首跑即抓出真实前置缺失**：仓库 **Dependency Graph 未开启**，action 直接报 `Dependency review is not supported on this repository`——已通过 API 开启 Dependabot 告警（连带启用依赖图），并在真实 Dependabot PR 上复验通过（`Dependency Review` 9s success）。验证方式：临时关闭/重开一个机器人 PR 触发 `pull_request` 事件（可逆，PR 内容未动）。

### 验证
- 单测 **260/260**（纯格式改动零语义变化）；KtLint **0 违规**；`lintDebug`/`lintRelease` 0 error；`detekt` 通过；`assembleDebug`/`assembleDebugAndroidTest`/`assembleRelease` 通过。
- 落地过程自查（教训已记入 HANDOVER）：批量文本改写用非唯一锚点曾误删区域，靠**编译 + 全门禁**兜住并逐行审计被删内容后修复。

### 遗留（登记，非遗漏）
- §11.8 余项仅剩 Macrobenchmark（长会话基准，需 emulator 稳定跑）；真机灰度（beta33 协议改动 + beta35 诊断导出落盘）与 A-5/物理分片硬门不变。

## v0.5.0-beta35（2026-10-12）· §11.8 可观测性：本地脱敏诊断缓冲 + 用户主动导出

### 新增（本地可观测性；无自动上传、无行为变化）
- **`util/diag/DiagnosticsRing.kt`**：容量有界（默认 512 条）的诊断环形缓冲 + **固定事件码**枚举（`DiagEventCode`）+ 机械脱敏：detail 一律截断到 120 字符，并把长度 ≥16 的连续 base64/hex/标识符样式串掩码为 `<masked>`（SID、token、分片数据不会落入缓冲）。满员覆盖最旧，`totalRecorded` 单独计数。
- **`util/diag/Diagnostics.kt`**：进程级极薄入口（`Diagnostics.record`）+ 纯函数 `exportText`（头部 = 应用版本/版本码/条数/总量；正文 = 事件行 `[-]elapsed  code  detail`）+ `recordAppStart`（进程内仅一次）。**不做任何自动上传**。
- **埋点（12 处，全部只记码/计数/状态名/耗时）**：应用启动（版本/版本码）、relay 状态迁移、桥 open/ready/failed/reopen、**协议违例**（`remote.rpcFrame.*` 固定码）、**入站队列溢出**（丢帧计数）、**重同步触发/节流**、会话失同步（reason）、订阅成功/失败（retryable）、握手自动重订（次数/退避）。全部落在 `service/ConnectionCoordinator`——连接栈零改动。
- **设置页「诊断与排障」区块**（`ui/screens/DiagnosticsSection.kt`）：展示当前条数/容量/累计，**导出诊断日志**按钮走系统 SAF（`ACTION_CREATE_DOCUMENT`，由用户选落盘位置；取消不写盘），成功/失败都记事件（失败只记异常类名）。文件名 `zcode-diagnostics-<yyyyMMdd-HHmmss>.txt`，不含设备/会话信息。
- **测试**：JVM `DiagnosticsRingTest` 7 项（有界与覆盖最旧、计数、清空、截断、长标识符掩码、导出结构与掩码、快照副本、容量下限）；仪器化 `DiagnosticsSectionTest` 3 项（区块可见可点、**真实点击**后导出请求事件入缓冲且请求的时间戳文件名形态正确、计数文案与缓冲一致）——UI 变更的真实点击走查在 CI 模拟器上执行。

### 隐私边界（硬约束）
- 只记录固定事件码、计数、耗时、状态名；**不记录**会话正文/提示词/文件路径/凭据/SID/完整 URL/RPC payload。
- 导出**仅由用户主动触发**（系统保存对话框），App 不接入任何默认上传；缓冲常驻内存 ≤ 512 条（约几十 KB）。

### 验证
- 单测 **260/260**（253 → 260，新增 7 项；`check_test_count.py` 核对声明 = 实际执行，CI 下限提到 260）。
- `lintDebug`/`lintRelease` 0 error；`detekt` 通过；`assembleDebug`/`assembleDebugAndroidTest`/`assembleRelease` 通过。
- 仪器化 `DiagnosticsSectionTest`（3 项）随 CI emulator job 执行。

### 遗留（登记，非遗漏）
- 真机走查：SAF 保存对话框的真实落盘、Toast 反馈与「导出后文件内容不含正文」在真机确认（CI 模拟器覆盖点击与事件链路；纯逻辑与脱敏由 JVM 单测覆盖）。
- §11.8 余项：KtLint 存量格式专项、Macrobenchmark、`dependency review`；真机灰度（beta33 协议改动）与 A-5/物理分片硬门不变。

## v0.5.0-beta34（2026-10-12）· §11.8 第二层门禁：协议帧回放 + 资源预算模糊/压力测试 + CodeQL

### 新增（验证网与静态分析；无用户可见行为变化）
- **协议帧回放网**：`tools/gen_rpc_frame_fixtures.py` 以**独立 Python 实现**（复用 `tools/probe.py` 的 VQL/CRC32）按官方等长切分算法构造完整 rpc-frame 信封（整条消息 CRC32、`fragmentCount ≤ 64`、标准 base64 带 padding；算法依据 `research/FRAME-CODEC.md` §3.6/§6），产出 20 用例 / 94 帧的 `rpc_frame_fixtures.json`；`RpcFrameReplayTest` 用同一组帧回放**纯逻辑层**（身份判定 / base64 规范性 / 元数据校验 / 分片组装结果序列），并用官方 §4.4 文档示例报文对拍 Kotlin `Vql` 编码字节——两份独立实现互为校验，上游协议漂移即红。
- **资源预算模糊测试**：`ProtocolFuzzTest`（确定性种子，可复现）——3 万轮组装（含 `Int.MIN/MAX` 级畸形元数据、2 MB 级分片、随机时钟与 sweep）+ 5 千轮解析器 + 2 万轮 base64 规范性性质 + 5 万轮入站准入；断言：**无异常逃逸、拒绝码全部命中固定枚举、在途字节/条数/准入深度任意时刻不越界**。
- **30 分钟压力测试**：`ProtocolStressTest`（`ZCODE_STRESS_SECONDS=1800` 启用，默认跳过、不占 CI 门禁时长）——混合流量（正常多分片 + 5% 篡改片 + 5% 重复片 + 3% 跳号 + 周期超时淘汰）持续灌入，断言在途分片字节/条数、入站深度始终受预算约束，并新增 **GC 后驻留内存**量测（比裸堆数字更能说明无泄漏）。**实跑证据（最终版本，2026-10-12，30m14s，failures=0）**：**64,749,568 帧**（约 3.59 万帧/秒）｜完成 1,222,922 / 拒绝 5,844,155（码分布 `fragmentGap` 4,499,316、`checksumMismatch` 752,613、`fragmentLimitExceeded` 444,399、`conflictingDuplicate` 147,827，全为固定枚举码）｜**在途分片字节峰值 65,555 B（预算 16 MiB 的 0.39%）**、在途消息条数峰值 1（预算 4）｜入站准入 峰值=容量=1024，准入 4,533 万 / 丢弃 1,942 万（过载路径被充分压到）｜堆内存 起点 62 MiB → 裸堆 148 MiB → **GC 后驻留 16 MiB**（远低于起点，无泄漏迹象）。
- **入站队列策略可测化**：抽出纯逻辑 `relay/InboundAdmission.kt`（容量/峰值/准入/丢弃记账），`RelayClient` 改为经它记账——过载路径此前只能靠读代码，现在被单测与压测直接驱动。
- **CodeQL 接入**（`.github/workflows/codeql.yml`）：`java-kotlin` + `build-mode: manual`（Gradle 构建提取）+ `security-and-quality` 查询集；最小权限（仅 `security-events: write`）、action 固定 SHA、`timeout-minutes`、`concurrency`、`persist-credentials=false`、周一定时。补上「Detekt 只看风格/复杂度、不看数据流安全缺陷」的空洞。

### 修复（模糊测试当场抓到的真实缺陷）
- **base64 规范性判定不完整**：原「形状校验」只查字母表/长度/填充位置，会放过**尾部悬空位非零**的串（如 `+C/=`——能解码、但重编码得 `+C8=`，官方 `isCanonicalBase64` 会拒）。现 `isCanonicalBase64Shape` 为完整规范性判定（末字符低 2/4 位必须为零），生产侧解码-重编码往返校验保留作纵深防御；新增 fixture 用例与 4 项单测钉死该行为。

### 验证
- 单测 **253/253**（244 → 253，新增 9 项；`check_test_count.py` 核对声明 = 实际执行，CI 下限提到 253）。
- `lintDebug`/`lintRelease` 0 error；`detekt` 通过；`assembleDebug`/`assembleRelease` 通过。
- 30 分钟压力测试与模糊测试证据见上（结构性边界为硬断言；裸堆数字仅作观测）。
- 行程说明：本轮为**无真机批次**（任务书允许的独立项），协议路径真机灰度仍为下一轮第一件事。

### 遗留（登记，非遗漏）
- §11.8 余项：KtLint 存量格式专项（test 661 + main 118 条，需独立 PR）、Macrobenchmark（长会话基准）、`dependency review`、脱敏诊断环形缓冲（用户主动导出；纯逻辑部分可先做，UI 导出入口须真机走查）。
- 真机灰度（beta33 起的协议改动）与出站物理分片 / A-5 逻辑 fragment 重组（硬门：无真实样本不实现）不变。

## v0.5.0-beta33（2026-10-12）· §11.3.3 集中协议预算 + §11.5.2 RPC 代次键控与分片完整性

### 修复（CI：仪器化回归网此前从未真正跑通）
- **instrumentation job 补 KVM 组权限步骤**：beta32 引入的仪器化任务在 GitHub-hosted runner 上**每次必失败**（master 自 beta32 起 CI 连续红，均红在此 job；`test-and-build` 为绿）：action 探测到 runner 用户不在 `kvm` 组 → `disable-linux-hw-accel: auto` 判 true → 以 `-accel off`（纯软件模拟）启动 → adb 永远等不到设备。修法 = android-emulator-runner README 的官方前置步骤（udev 规则放开 `/dev/kvm` 权限后 reload+trigger）。证据：失败日志 `ProbeKVM: This user doesn't have permissions to use KVM (/dev/kvm)`。**修复后实测**：CI run `38209635995` 的 instrumentation **4m3s 通过**（master 自 beta32 以来首次全绿）——emulator 上的 C-14 布局与 Markdown 渲染回归网现在真正进 CI 了。

### 修复（协议一致性——堵「连接正常却静默停在旧状态 / 串单」这一类故障的结构性口子）
- **请求位点绑桥代次**（`relay/PendingRequests.kt`）：挂起请求 key 由 `requestId` 改为 **`(桥代次, requestId)`**；桥就绪/复位时代次递增，id 计数归零后**不可能**让旧连接/旧桥的迟到应答「恰好」完成新请求。请求 id 改由 `AtomicInteger` 生成并保持非负（线格式 uint32）。
- **入站身份三元组校验**：帧身份与当前桥不一致（旧桥迟到帧）→ 丢弃且不回 ack（官方 `identityMismatch` 非终态语义）。**与官方的唯一偏差是宽容方向**：`bridgeGeneration`/`recoveryId` 在官方 schema 里可选，出现才比较、缺席不比较——官方严格三全等在「PC 端省略任一可选键」时会误拒**全部**入站帧（无真机可验时不可逆）；跨桥串帧仍由必带的 `bridgeSessionId` 与代次键控双保险（偏差有据，注释与单测均写明）。
- **单/多分片统一校验**（`relay/RpcFrameAssembler.kt`）：`messageBytes` 声明长度 + **整条消息 CRC32** 对单分片与多分片一视同仁（旧实现单分片**完全不校验**）；分片按官方语义要求严格顺序（跳号 → `fragmentGap`、首片缺失 → 拒绝）、同指纹重复分片幂等忽略、指纹冲突 → `conflictingDuplicate`、在途元数据漂移 → `metadataMismatch`/`checksumMismatch`；全部违例返回**固定脱敏错误码**、**不 ack**、触发重同步。
- **解码成功后才 ack**：旧实现先 ack 再解码，等于对「无法处理的帧」确认收货；现在 VQL 解码失败 → `decodeFailed` + 重同步 + 不 ack（服务端可重放）。
- **桥就绪/reset 的序列空间与残留状态**：新桥 = 新 seq 空间（官方每桥各方向从 1 起、组装器 `expectedPhysicalSeq` 默认 1，见 `research/FRAME-CODEC.md` §6）——旧实现下**桥自动重开后出站 seq 沿用旧值，PC 侧会判 `physicalGap`（终态）**，这是 beta20「桥降级自愈」的潜在隐藏缺陷；`reset()` 同时补齐**看门狗令牌失效**与**身份字段清理**（旧实现 reset 后旧看门狗仍可能用旧 wsKey 重开桥、出站帧会带旧桥身份）。
- **未知逻辑 fragment 不再静默**（§11.5.3）：`kind:"fragment"` 逻辑帧（≤1024 片/16 MiB，无真实样本前**不实现**重组）命中即**显式失同步 → 重订阅权威快照**（旧实现只写一行 WARN，会话会停在旧状态）。

### 新增（§11.3.3 集中资源预算：官方实证上界 + 固定脱敏错误码）
- **`relay/ProtocolLimits.kt`**（集中唯一事实来源）：官方常量 `1 MiB` 物理帧 / `16 MiB` 单条消息 / `64` 分片 / `30s` 组装超时（来源 = host asar 常量对象 + 官方 bundle zod schema，见 `PROTOCOL.md` §4.1、`research/FRAME-CODEC.md` §3.6）；App 本地预算：在途分片 4 条 / 16 MiB、入站队列 1024 帧、事件缓冲 512、重同步节流 5s、上传预算 20 MiB / 64 片（上传常量收敛到此）。`ProtocolViolation` 错误码与官方 fault code 同名，日志**只记码与计数**。
- **入站队列有界 + 溢出可见**：`RelayClient` 帧队列由 `UNLIMITED` 收敛为 1024，溢出**计数 + 回调**（掉帧 = 失同步信号 → 节流重订阅；被丢帧未 ack，服务端可重放），杜绝突发流量无限缓存。
- **出站预算本地拒绝**：报文 >16 MiB 或 `data` 信封 >1 MiB 一律**不发**（官方 `messageTooLarge`/`envelopeTooLarge` 同一口径，以可见失败收场）。官方发送端在此按 ≤1 MiB 物理分片——App 侧物理分片按任务书硬门**待真实帧样本后实现**，本轮先拒绝而非发超限帧。
- **重同步节流**（`ResyncThrottle`）：违例/丢帧/未知逻辑帧触发重订阅的最小间隔 5s，并计数被抑制次数——每次重订阅都会复位 A-2 重订预算，不节流会把帧错误放大成订阅风暴。
- **异常规模帧健全性护栏**：快照 >2 万行、单帧增量 >5 千条视为异常帧 → 显式失同步（真机最长会话 1100+ 行、缓存上限 200 行，护栏远离正常量级，**不做静默截断**）。

### 验证
- 单测 **244/244**（201 → 244，新增 43 项；`check_test_count.py` 核对声明 = 实际执行）：官方常量钉死、元数据边界（非正 seq、分片数 0/65、负数索引、16 MiB 边界、checksum 形状）、单/多分片 CRC 与声明长度、重复/冲突/缺片/元数据漂移、超时淘汰、在途条数与字节上限、代次键控（**旧代次不得完成新请求**）、节流抑制与放行。
- `lintDebug`/`lintRelease` 0 error；`detekt` 通过；`assembleDebug`/`assembleRelease` 通过。
- 常量与官方实现逐字对照：`research/FRAME-CODEC.md` §3.6/§5/§6（crc32 标准反射多项式、整条消息 CRC、≤64 片、30s 组装超时、指纹去重）。

### 遗留（登记，非遗漏）
- **真机灰度未做**（本轮无设备连接）：协议路径按任务书要求须真机灰度——重点回归「正常会话流式无违例误报」「桥降级自愈后出站 seq 归 1 被 PC 接受」「切换线路/重连不串单」。误报风险已用官方常量 + 宽容方向压低，但**真机证据是下一轮第一件事**。
- **出站物理分片**（官方：等长片 ≤1 MiB、`fragmentCount ≤64`、整条 CRC）待真实多分片样本建 fixture 后实现——同 A-5 硬门：无样本不写伪实现。
- §11.8 余项（CodeQL、协议帧回放、Macrobenchmark、诊断环形缓冲、KtLint 存量格式专项）与 §11.5.3 A-5 逻辑 fragment 重组仍按任务书 §11 排队。

## v0.5.0-beta32（2026-10-11）· §11.8 阶段 6：CI 加固 + 静态分析 + 全自动发布链

### 新增（工程化，无用户可见行为变化）
- **CI 加固**（`ci.yml`）：最小权限（`contents: read`）、按 ref 的 `concurrency` 取消、job 级 `timeout-minutes`、全部 action 固定 commit SHA。
- **静态分析门禁**：Detekt（`buildUponDefaultConfig` + `detekt-config.yml` + 105 条存量 baseline——新增违规即红）；Kover 覆盖率报告。
- **仪器化回归网进 CI**（`instrumentation` job）：ubuntu runner 自带 KVM，用 `android-emulator-runner` 跑 `connectedDebugAndroidTest`（C-14 顶栏布局 + Markdown 渲染两张网）——补上「本机兆芯 CPU 无虚拟化扩展、仪器化只能真机跑」的空洞。
- **全自动发布链**（`release.yml`，tag 驱动）：① 五处版本一致性门禁 `tools/check_release_consistency.py`（tag = versionName = APK versionName = CHANGELOG 版本 = Release 标题版本）；② Secrets 解码签名材料 → 签名 Release APK + CycloneDX SBOM；③ 资产 = 规范化 APK + `SHA256SUMS.txt` + apksigner 证书指纹 + `sbom.cdx.json`；④ `attest-build-provenance` 生成 SLSA provenance；⑤ `gh release create --prerelease`，标题由 CHANGELOG 头条生成（杜绝裸标题/提交信息当标题）。
- **Dependabot**（gradle + github-actions 周检）；**`.editorconfig`**（Compose PascalCase 函数名等三条规则有据豁免）。

### 验证
- 单测 **201/201**；Detekt / `lintDebug` / `lintRelease` 0 error；`assembleDebug` / `assembleRelease` 通过；SBOM 与 Kover 报告正常产出。
- 本版本由**新发布链自动发布**（推 tag 即触发），即对整条链路（一致性门禁 → 签名 → 校验资产 → provenance → prerelease）的端到端验证。

### 遗留（登记，非遗漏）
- KtLint 存量格式噪音（test 661 条 + main 118 条，多为历史 KDoc 连排/命名惯例）本轮不入门禁——Detekt 已覆盖静态分析；KtLint 留专项治理轮次（文件级 format 会改写大量历史文件，需独立 PR + 回归）。

## v0.5.0-beta31（2026-10-11）· §11.7 收官：connect() 编排与连接韧性调度器迁 ConnectionCoordinator

### 拆分（纯代码组织，行为不变；`AppViewModel` 1057 → 765 行，三轮累计 1945 → 765）
- **`service/ConnectionCoordinator`**（~410 行）：RelayClient/RpcChannel/ConversationChannel/SessionsIndexChannel/WorkspaceConfigChannel/NetworkGate 装配 + 全部状态流 collector（relay/桥/rpc 事件/会话状态/审批与表单通知链路）+ **A-2 握手自动重订与桥自动重开两个连接韧性调度器**（状态随迁）+ bootstrap 工作区解析。
- 对 VM 状态的写入收敛到 **`ConnectionHost` 接口**（AppViewModel 实现，12 成员）；职责域操作直接调用注入的 convRepo/approvalRepo/modelRepo/notifyCoordinator——coordinator 不感知 ViewModel 类型，无新增跨层依赖。
- `bridgeReopenDelayMs` 纯函数随迁（单测引用 6 处改指向）；`deleteSession`/`disconnect` 的调度复位改 `resetHandshakeRetry()`；VM 保留 `connect()` 一行转发（init/pair/switchDevice/removeDevice/setEndpoint 调用点零改动）。

### 验证
- 单测 **201/201**（声明 = 实际执行）；`lintDebug`/`lintRelease` 0 error；`assembleDebug`/`assembleRelease` 通过。
- **真机回归通过**（2410DPN6CC，WiFi adb 同签名覆盖装机）：启动连接就绪（72 会话，bootstrap→开桥→订阅全链路活）、首页「正在查看」角标（视图上报链路实证）、前台服务通知在（dumpsys notification）、logcat **FATAL=0**。

### §11.7 全部完结
任务书阶段 5「结构性拆分」九个提交全部落地：会话页 2210 → 494 行（六槽位 + UiState/Action 契约）、`AppViewModel` 1945 → 765 行（repo/ 六域类 + service/ 编排器）。剩余会话 CRUD 编排（createNewSession/deleteSession/stopSession/sendPrompt）属 VM 的 UI intent 本职，按任务书语义保留。

## v0.5.0-beta30（2026-10-11）· §11.7 第二/三批拆分：审批/表单 + 通知协调 + 会话流域入 repo

### 拆分（纯代码组织，行为不变；`AppViewModel` 1181 → 1057 行，两轮累计 1945 → 1057）
- **死参数清理**：`ConversationAction.onSwitchModelCustom`（无调用点）全链删除——Contract 字段、MainActivity 传值、VM 转发、`ModelConfigRepository.switchCurrentSessionModelCustom` 及专属 getter。
- **`repo/ApprovalRepository`**（~300 行）：审批/表单双来源合并（会话流 pendingInteractions + 任务事件流）、`resolve`/`resolveById`/`answer` 系列/通知栏快捷应答（D-3）整体迁出；任务事件流 collector 改调 `ingestTaskApproval` 等入口；失败回滚、乐观消除逻辑原样保留。
- **`repo/NotificationCoordinator`**（~105 行，任务书 NotificationCoordinator 职责）：多会话看板计数（E-1 权威角标优先 + 事件流回退）、桌面 Widget 推送（P2-3）、「手机正在看哪个会话」视图上报（P1-2）、连接终态文案。
- **`repo/ConversationRepository`**（~210 行）：rows/rowStore/派生快照（C-5③）、meta/earlier/snapshotAligned/sessionState、订阅（含 C-5⑤ 离线缓存秒开与 C-5⑤ 竞态判定纯函数）、重订、翻页、C-6 本地 pending 回显气泡（`PendingUserMessage` 类型随迁）；connect 的状态 collector 改直写 repo（internal var——通道在 connect 时才创建，repo 无法自挂 collector）；重订预算复位与附件清理经回调注入。
- 连接栈（conversation/channel/activeWorkspaceKey 等）全部经 getter 注入，flash 统一为 `(文案, 失败语义)` 回调；单测引用随纯函数迁移 15 处，**用例数 201 不变**。

### 验证
- 单测 **201/201**（`check_test_count.py` 核对）；`lintDebug`/`lintRelease` 0 error；`assembleDebug`/`assembleRelease` 通过。
- **真机回归通过**（2410DPN6CC，WiFi adb 同签名覆盖装机）：启动连接就绪（72 会话）、会话页顶栏单行 + 时间线全行型渲染（思考/终端/读取/工具行）+ 快捷胶囊 + 输入栏停止态（会话流 repo 渲染链路）、待办 Tab 空态正确（审批 repo + 看板）、设置页版本与检查更新链路正常、logcat **FATAL=0** 且无新 repo 告警。

### §11.7 剩余（下一轮）
- `connect()` 编排与握手/桥重开调度器迁 ConnectionCoordinator（A-4/§11.4 刚稳定路径，须独立灰度）。

## v0.5.0-beta29（2026-10-11）· C-12 大文件拆分（§11.7）：会话页槽位化 + UiState/Action 契约 + 三域 repository

### 拆分（纯代码组织，行为不变、不加功能）
- **ConversationScreen 2210 → 494 行**（目标 300–500 达成），按任务书槽位图拆出 7 个文件：
  - `ConversationTimeline.kt`：行渲染器群（RowItem 分发 + UserBubble/PendingUserBubble/AssistantText/ReasoningBlock/ToolCallRow/TurnHeaderRow/SubagentRow/TimelineMarkerRow/HookInvocationRow/PlainRow）+ 消息流容器（LazyColumn + 悬浮「回到底部」胶囊）。
  - `ConversationTopBar.kt`：顶栏整体（返回/标题元数据/A-2 重试胶囊/溢出菜单/模型两级菜单/ModeChip/statusLabel/phaseLabel）；弹窗开关转 `onOpenFiles`/`onOpenStatus` 回调。
  - `ConversationPendingCards.kt`：ApprovalCard + ElicitationCard。
  - `ConversationAttachmentTray.kt`：inspectAttachment + AttachmentBar + humanBytes。
  - `ConversationComposer.kt`：ActionChipsBar + InputBar。
  - `ConversationDialogs.kt`：SessionFilesPanel。
  - `ConversationContract.kt`：**`ConversationUiState`（25 数据字段）+ `ConversationAction`（22 回调）** 替代原 46 个散落参数；主入口以 `with(ui)/with(actions)` 解构，函数体逐行不变；MainActivity 单向装配。
- **AppViewModel 1945 → 1428 行**，最自包含三域抽为 `repo/` 包（ViewModel 保留同名转发属性/方法，UI 调用签名零变化）：
  - `UpdateRepository`：检查更新 + `isNewerVersion` 语义比较（原样迁出）。
  - `AttachmentUploader`：上传全链路（C-1 取消/重试、A-1 会话归属、`uploadBelongsTo`/`uploadIdForAttempt` 纯函数原样迁出）；连接栈经 getter 注入。
  - `ModelConfigRepository`：模型目录三路探测 + 思考档位注册表 + 会话内切模型；执行模式回写经 `onWorkspaceMode` 回调。
- 嵌套类型引用随迁：SettingsTab → `UpdateRepository.UpdateState`（5 处）；Contract/AttachmentTray → `AttachmentUploader.FailedUpload/AttachmentFeedback`；单测引用随纯函数迁移（AppViewModelTest 4 处、PureFunctionsTest 14 处），**用例数不变**。

### 修复
- **TimelineArea 恢复 `weight(1f)`**：拆分④把 else 分支 Box 换成独立组件时丢了布局权重，LazyColumn `fillMaxSize` 无界把附件条/快捷胶囊/输入栏挤出屏幕（JVM 单测不渲染布局未覆盖；真机冒烟截图实证后修复，复验逐像素恢复）。

### 验证
- 单测 **201/201**（`check_test_count.py` 核对声明 = 实际执行）；`lintDebug`/`lintRelease` 0 error；`assembleDebug`/`assembleRelease` 通过。
- 真机（2410DPN6CC，WiFi adb，release 包同签名覆盖装机）：启动连接就绪（71 会话）、顶栏单行（C-14 阴影回归无复发）、时间线全行型渲染（思考/终端/子智能体/Markdown）、快捷胶囊 + 输入栏（停止态）、模型两级菜单数据完整、溢出菜单 → 会话状态面板（上下文 48k/1M、缓存命中 79.8%）、检查更新链路无异常、logcat FATAL=0。

### §11.7 后续轮次（本轮不做，已具门槛）
- AppViewModel 剩余域：审批/表单 Repository、Session/会话流 Repository、`connect()` 编排迁 ConnectionCoordinator（与 A-4/§11.4 刚稳定的连接路径纠缠，需独立灰度）。
- 死参数 `onSwitchModelCustom` 清理（历史遗留无调用点，已在 Contract 注明）。

## v0.5.0-beta28（2026-10-11）· 缓存治理（§11.6）：C-9 凭据入 Keystore + 缓存 TTL/上限/串行化

### 安全（C-9）
- 新增 `storage/TokenStore`（AES-256-GCM，密钥入 Android Keystore，**复用 `CredentialCrypto` 主密钥**；按任务书禁用 `EncryptedSharedPreferences`）。
- GitHub Token 由**明文 SharedPreferences** 迁移为加密存储；旧明文首次读取即迁移并删除（迁移失败保留明文待重试，不阻断）。

### 缓存治理
- **写串行化**：`SessionCacheStore` 写侧加 `Mutex` —— 流式期同一会话并发保存时「tmp 写 + rename」序列不交错。
- **TTL 7 天**：会话列表与行缓存同治，过期即弃（换设备/长期未用后不回放陈旧会话）。
- **读侧行数上限 200**（与写侧 `takeLast(200)` 对齐，防旧文件超限）。
- **单文件字节上限 2MiB**：折半丢行纯函数 `trimRowsToBytes`（病态单行超限原样返回，不死循环）。
- **删设备即清全部会话缓存**（`removeDevice → clearAll`，避免残留上一设备的会话正文）。

### 验证
- 单测 **201/201**（新增 TTL 判定 / 字节裁剪 / 病态单行共 7 项保护性测试，含 `--min 201` 门禁提升）。
- 真机（2410DPN6CC，WiFi adb）：装机 Success、启动 FATAL=0、连接/会话列表正常。

## v0.5.0-beta27（2026-10-11）· A-4 失同步自愈（缺口触发重订阅 + SUSPEND 单泵）

### 修复（任务书 A-4，高危项）
- **入站缓冲 `256/DROP_OLDEST` → `512/SUSPEND` + 单泵**：慢消费时由发射端（`onRpcFrame`，跑在入站泵协程）背压挂起，不再丢帧——丢帧 = seq 缺口 = 会话静默停在旧状态；容量对齐 `RelayClient._inbound`。`tryEmit → emit`，`onRpcFrame/handleMessage` 改 suspend（调用点本就在泵协程）。
- **缺口不再只 WARN**：新增 `RpcChannel.onSequenceGap` 回调，`connect()` 挂 `retrySubscribe()` —— 缺口帧已 ack、服务端不重发，**重取权威快照是唯一自愈路径**。

### 验证（真机 2410DPN6CC，WiFi adb）
- 单测 **194/194**（含 `--min 194` 阈值门禁；`hasSeqGap` 纯函数保护网既有）。
- 装机 Success、启动 **FATAL=0**、**事件流缺口 0 条**；会话列表与**流式内容正常渲染**（SUSPEND 泵在真实流量下工作）。

### 工程
- 真机调试链路切换为 **adb over WiFi**（HyperOS USB 调试传输自掉线问题的绕行；已配对 192.168.0.102）。

## v0.5.0-beta26（2026-10-11）· §11.4 阶段③ 连接韧性流整体迁入进程级

### 变更（架构，任务书 §11.4 完结）
- **四条连接韧性流全部由 `viewModelScope` 迁入 `RemoteConnectionManager`**：relay 状态流（终态通知 KICKED/配对失效等、引导触发、Widget 刷新）、桥状态流（降级自动重开）、会话状态流（A-2 握手自动重订）、角标汇总流。
- **两个重试调度器改挂进程级作用域**：`scheduleHandshakeRetry` / `scheduleBridgeReopen` —— VM 被清后桥自动重开与握手重订仍生效（此前会静默失效）。
- 纯 UI 映射流（`conv.meta/snapshotAligned/sessionState/earlier`）**留在** `viewModelScope`（随 VM 生灭是正确语义，不迁）。
- **至此 §11.4 的行为目标达成**：连接的「事件 + 通知 + 韧性」全部不依赖 VM 存活；连接编排的**入口**仍由 VM 触发（用户动作语义正确）。剩余的「connect() 编排整体物理搬迁」属纯代码组织优化，列入后续清理（不影响任何行为）。

### 验证
- 单测 **194/194**（含 `--min 194` 阈值门禁）。
- 真机（2410DPN6CC / HyperOS）：装机 Success、启动 FATAL=0、连接/引导/会话列表正常渲染。

## v0.5.0-beta25（2026-10-11）· 连接生命周期 §11.4（①②）+ 通知应答修复

### 变更（架构，任务书 §11.4）
- **阶段① 旁路 manager**：新增进程级 `RemoteConnectionManager`（连接栈字段迁入 + 进程级协程作用域 `SupervisorJob+Default`）；`ConnectionScope` 变为读写代理（调用点零改动、可整体回退）；`ConnectionService.onDestroy` 统一收口 `shutdown()`（此前服务止后连接对象挂在静态字段上无人回收）。
- **阶段② 事件流迁移（首批，通知关键路径）**：`ch.events.collect`（会话流/角标/配置三通道唯一入口）与 `conv.interactions`/`conv.elicitations`（审批/表单**通知链路**）由 `viewModelScope` 迁入进程级作用域 —— **Activity 销毁、VM 被清后通知仍可发出**。配套 `launchCollector/cancelCollectors`（重连前取消旧流，防叠加/重复订阅）。
- 阶段③（connect() 编排整体迁入、VM 只留 UI 映射）待后续提交；其余流（relayState/bridge/conv.*）逐条迁移中。

### 修复
- **表单应答失败（`invalid_value @ answer.action`）第二处同源缺陷**：通知栏 RemoteInput 内联回复（`ElicitationReceiver` 的 `ACTION_REPLY_TEXT`）此前只发 `{"freeText":…}` 缺必填枚举 `action`；补 `action:"accept"`（与 `answerElicitationFreeText` 同源同修）。**全仓 freeText 构造点清零**。

### 验证（真机 2410DPN6CC / HyperOS，端到端金标准）
- **锁屏存活**：`always_finish_activities=1` + HOME 销毁 UI（顶层 Activity=桌面、进程与前台服务存活）→ 触发提问 → **通知正常弹出** → **在手机通知上直接作答 → 桌面端收到答案**（通知→RemoteInput→resolveInteraction→桌面 全链路闭环）。旧版在该场景下 collector 已死、通知不弹。
- 单测 **194/194**（含 `--min 194` 阈值门禁）；启动 FATAL=0；连接/引导/会话列表正常。

## v0.5.0-beta24（2026-10-11）· 顶栏溢出菜单 + 表单应答修复 + 发布签名归一

### 修复
- **C-14 顶栏空间预算（溢出菜单）**：真机实测——仅加 `maxLines` 兜底后，右侧四个定宽胶囊（文件数 / 状态 / 模式 / 模型）仍把标题列挤到约 **11–16px**，标题与元数据被省略成空、肉眼不可见。把「📁文件数」「状态」从顶栏直排收进右侧「⋮」二级菜单，释放约 460px；真机实测标题/元数据节点由 16px 恢复到 **254px**、可见（用户确认 A：`← 标题… ⋮ build 模型`）。竖向撑高修复（元数据 `maxLines=1`）与仪器化断言见 v0.5.0-beta23。
- **表单应答失败（`invalid_value @ answer.action`）**：全仓穷举 `"action"` 仅 5 处，四处为合法字面量；唯一**不带 `action`** 的分支是 `AppViewModel.answerElicitationFreeText`（只发 `{freeText}`）。Zod v4 的 `z.enum` 对 `undefined` 亦报 `Invalid option: expected one of …`，故缺字段即命中该错。已改为 `{action:"accept", freeText:text}`。**待真机复测**；若仍失败将临时放开该处日志脱敏、抓实际 wire 载荷精确定位。

### 发布治理（重要）
- **签名归一到本机 keystore**：历史发布签名 `e1571a49…` 的私钥**已不可得**（用户确认无备份）。自本版起以本机 keystore 为**唯一官方签名**：`toolchain/keys/zcode-remote.keystore`（SHA-256 `1d46e9e8:6748:A2B1:9846:6FFA:C2B5:8F9F:F4BD:BD37:FB68:C35E:1A50:4C65:ED24:BE55`）。**已装历史版本（e1571a49 签名）的设备升级本版需先卸载一次**；此后所有版本可正常覆盖升级。**务必备份 keystore 与口令**（丢失即再次触发全量卸载）。
- 补齐此前只本地构建、未随 Release 分发的改动（溢出菜单、表单应答修复）。

### 验证状态
- `./build.sh testDebugUnitTest` → **194/194**；`lintDebug`/`lintRelease` 0 error；`assembleRelease` 通过。
- 真机（2410DPN6CC / HyperOS）实测：顶栏 y=184–252（修复前 y≈520）、标题/元数据宽 **254px**（修复前 16px）、会话流首行 y=308。

## v0.5.0-beta23（2026-10-10）· C-14 顶栏布局修复 + 传输/日志安全止血 + CI/发布门禁

### 修复
- **C-14 会话页顶部布局错乱（P0 发布阻断）—— 根因定位并修复**：顶栏 `Row` 中部标题列 `Column(Modifier.weight(1f))` 被右侧四个定宽胶囊（文件数 / 状态 / 模式 / 模型）压到约 20dp；该列内**元数据 `Text` 缺 `maxLines` 兜底**，在近零宽下按 CJK 逐字符换行成 20+ 行，把整条顶栏 `Row` 撑到约 800–1000px、内容垂直居中 → 表现为「顶栏下移 + 上下双空白 + 会话流被压扁裁切」（真机顶栏恒在 y≈578）。触发条件：长模型名（`cn:deepseek-v4.1-flash-…`）+ 大字体，故此前未暴露。**修复**：元数据 `Text` 加 `maxLines = 1` + `TextOverflow.Ellipsis`，硬锁单行，杜绝零宽换行撑高整行。**验证**：新增仪器化断言 `ConversationTopBarLayoutTest`（复现压力条件：长模型名 + fontScale 1.3），模拟器实测——**修复前顶栏 554.29dp 断言失败、修复后 < 96dp 通过**（负向对照成立）。
- **Vql.kt API 33 崩溃修复（由新增 lint 门禁抓出）**：VQL 编码使用 `ByteArrayOutputStream#writeBytes`（Android 13 / API 33 才引入），而 `minSdk = 31` —— 在 Android 12/12L 上会 `NoSuchMethodError` 崩溃（协议编码主路径）。改用语义等价的 `write(byte[])`（API 1）。

### 安全加固（P0 传输与日志止血，任务书 §11.3）
- **中继端点强校验**：新增 `relay/RelayEndpointValidator.kt`（`java.net.URI` 结构化解析）——Release 仅接受绝对 `wss://`，拒绝相对路径 / 无 scheme / 非 ws·wss scheme / userinfo / 无 host / 端口越界；移除旧 `startsWith("ws")` 弱校验。接入 `RelayClient.connect()`（不合规即拒绝连接、不降级重试，新增终态 `INVALID_ENDPOINT`）与 `AppViewModel.relayOverride()`（不合规回退官方 wss）。
- **明文流量关闭**：主 Manifest `android:usesCleartextTraffic="false"`（minSdk 31、无 networkSecurityConfig，不误伤既有能力）；debug 变体单独 `tools:replace` 放开局域网 `ws://` 调试。
- **日志脱敏**：新增纯函数 `util/LogRedactor.kt`（`maskId` / `pathLabel` / `payloadLabel` / `endpointLabel` / `exceptionLabel`）；改写 `RelayClient` / `RpcChannel` / `ConversationChannel` / `AppViewModel` 的高危日志点（整帧、`sid=` 原文、payload、工作区路径、文件路径、异常 message），保证**二维码 / SID / hash / 会话正文 / 文件路径 / payload / 中继 URL 不落日志**。
- **调试组件令牌门**：新增 `debug/DebugInjectionGuard.kt`，两个 debug receiver 强制「严格 action + 一次性令牌」双校验，无令牌丢弃；保留 `exported=true`（adb shell 无法广播到 `exported=false`，改用令牌门）；Release 变体结构上不含（已用 aapt2 对 release APK 验证 0 命中）。

### 新增（CI / 发布门禁）
- **CI 第一层门禁补强**（`.github/workflows/ci.yml`）：新增 `gradle lintDebug lintRelease`（0 error 门禁，首个真实收益即抓出上面的 Vql API 33 缺陷）、`gradle assembleRelease` 组装、对 release APK **二进制 AndroidManifest** 的安全断言（明文关停 / 无 debug 组件 / 不可调试，规避 merged-manifest 路径随 AGP 漂移）、单测数量下限 `tools/check_test_count.py --min 194`（`--min/--expected` 阈值兜底，挡住「整文件/整类被删」的数量级回退）。
- 单测 162 → **194 项**：新增 `RelayEndpointValidatorTest`（24 例）、`LogRedactorTest`（8 例，canary 输入零命中断言）。

### 顶栏空间预算修复（溢出菜单，同日追加）
- 真机实测：仅加 `maxLines` 兜底后，四个定宽胶囊仍把标题列挤到约 **11–16px**，标题与元数据被省略成空、肉眼不可见。
- 把「📁文件数」「状态」从顶栏直排收进右侧「⋮」二级菜单（`overflowEntries`），释放约 460px；真机实测标题/元数据节点由 16px 恢复到 **254px**、可见，内容首行 y=308。**用户真机确认（选项 A：`← 标题… ⋮ build 模型`）通过。**

### 问题登记（新复现，纳入整改）· 表单应答失败
- **2026-10-10 用户在当前 beta23 上复现**（此前 HANDOVER 记为"待复现/疑似异源旧包"，结论更正）：应答 elicitation 时服务端拒收——
  `{"code":"invalid_value","values":["accept","decline","cancel"],"path":["answer","action"],"message":"Invalid option: expected one of \"accept\"|\"decline\"|\"cancel\""}`。
- **已定位并修复（初步）**：全仓穷举 `"action"` 仅 5 处、写的都是 `accept`/`decline` 字面量；唯一**不带 action** 的分支是 `AppViewModel.answerElicitationFreeText`（只发 `{freeText}`）。Zod v4 的 `z.enum` 对 `undefined` 同样报 "Invalid option: expected one of …"，故若 host 将 `action` 视为必填枚举，该分支即命中此错。修复：改为 `{action:"accept", freeText:text}`。**待真机复验**（重试一次"输入自定义回答"提交）。
- 代码侧四个构造点（`AppViewModel.answerElicitation` / `declineElicitation` / `approveElicitationPlan`、`ElicitationNotifier`）写的都是 `accept`/`decline` 字面量，**越界值来源不在这些字面量**；嫌疑指向载荷外层结构或交互类型分支。待办：抓 `resolveElicitation` 实际 wire 载荷与桌面端 schema 对拍定位。

### 验证状态
- `./build.sh testDebugUnitTest` → BUILD SUCCESSFUL；单测 **194/194** 全绿。
- `lintDebug` + `lintRelease` → 0 error；`assembleRelease` → BUILD SUCCESSFUL。
- 仪器化 `ConversationTopBarLayoutTest`（模拟器 test35 / SDK 35）：带修复 **PASS**；临时去修复 **FAIL（554.29dp）** —— 负向对照成立，断言确能捕获该缺陷。
- **真机复验通过（2026-10-10，小米 2410DPN6CC / HyperOS，release beta23，versionName=0.5.0-beta23）**：uiautomator 实测——顶栏（返回键/标题/元数据/胶囊）由修复前 **y≈520、行高约 855px** 回到 **y=161–251、单行**，标题「帮我下载CODEX」与元数据「已连接 · 运行中 · 共 164 行」均恢复可见，会话流首行从 y≈940 提前到 y=308。证据截图 `docs/screenshots/conv-layout-fixed-phone.png`。另：卸载重装（跨签名）后配对凭据保留、无需重扫码。

## 历史登记（已于 v0.5.0-beta23 修复）· 会话页顶部布局错乱（用户截图反馈，2026-10-10）

**现象**（用户截图 + 真机复现，稳定）：会话页顶栏下移至屏幕 1/3 处（真机量测 y=[578,713]，正常 ~[143,278]），其上方 435px 空白、与会话流之间再 450px 空白；会话流顶部行被裁切（截图里「任务」行文字上半缺失）、底部行被快捷胶囊行遮挡。**退出重进不恢复；列表页正常 → 会话页特有**；键盘弹出时同一现象（C-3 排查期间已见同一量测值，当时误判为键盘瞬态）。

**真机线索**：键盘已收起，但系统 **IME insets 仍报 `contentTopInsets=1353` / `touchableRegion(0,1353,1080,2400)`（未清零）**；头号嫌疑为 `imePadding()` 在 insets 异常/残留时的分量处理（若 ime insets 的 top 分量 >0 会被加到顶部 → 顶栏整体下移；会话流 `weight(1f)` 的分配被压缩后出现裁切）。会话页根布局：`Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(14.dp, 8.dp)` + `Arrangement.spacedBy(8.dp)`（`ConversationScreen.kt:297-305`）。

**证据**：`docs/screenshots/conv-layout-bug-report.jpg`（用户截图）+ 量测 dump（顶栏 [578,713] / 会话流 [1163,2054] / 退重进不恢复 / 列表页对照正常）。

**诊断进展（2026-10-10 晚，真机构建实验 4 轮：only(Bottom) 候选修复 / 全去 inset padding / 三标记定位）**：
- ✅ **确切机制定位**：「标记二分法」（在根 Column 首个子元素、顶栏 Row 后、会话流后插入可见标记 M-A/B/C）证明——根 Column 内容从屏幕顶部开始（M-A 在 y=23），但**顶栏 Row 在布局中被撑到 ~988px 高**（M-A→M-B 间距 1036px；顶栏内容 135px 在 Row 内垂直居中、返回键恰在 Row 垂直中心）→ 这就是「顶栏下移 + 双空白 + 会话流被压」的直接原因。
- ✅ **已排除**（各自单变量构建 + 真机量测）：① `imePadding()` 的 top 分量（`only(WindowInsetsSides.Bottom)` 无效果）；② 全部 inset padding（statusBars/navigationBars/ime 三者同去后顶栏仍被撑高、只上移 127px）；③ 顶栏 Row 内部含纵向撑满元素（grep 无 `fillMaxHeight`/`verticalScroll`，仅横向 spacer 与横向 weight）；④ 会话流 weight 丢失（去 padding 后其高度同步增长，weight 正常）。
- ⏭️ **下一步（需更强工具）**：Layout Inspector 看「顶栏 Row 的测量约束链」（哪个父级/修饰符授予大高度约束），或二分删除法（Row 内容逐个换单行 Text）。**高价值候选实验**：顶栏 Row 前加 `Spacer(Modifier.weight(1f))`——若它吃掉空间、顶栏回到顶部，则问题在 Column 的权重组装。

**状态**：**待立项**（用户指示「分析后加入后续工作」；诊断已推进到「机制定位 + 排除清单」）。会话页核心布局，改动须独立 commit + 真机回归。**诊断构建已全部回滚**（手机上恢复为 beta22 正式包）。

## v0.5.0-beta22（2026-10-10）· C-3 输入栏并入容器与图标体系（+ 键盘 inset 量测定论）

### 新增 / 变更（C-3）
- **附件条并入容器：已尝试并回滚（附布局回归记录）**：`AttachmentBar` 移入 `InputBar` 的 Surface（顶部内嵌区）后**真机发现布局回归**——键盘弹出 + 附件 chip 同时存在时，输入栏容器高度被限制在「不含 chip 的测量值」186px，chip 挤入后输入框可见高度压到 ~55px（附件按钮压到 27px），且顶栏 Row 下移至 y≈578、会话流滚动节点从 uiautomator dump 消失；「收键盘再弹出」稳定复现。疑似 `Modifier.fillMaxSize()`（根 Column）+ `imePadding()` + 会话流 `weight(1f)` + Material3 `Surface`（内部 Box 传播 minConstraints）在「非 weight 子元素高度变化 + IME inset 变化」组合下的测量交互，未完全定位即回滚（工程判断：不为视觉优化承担布局回归）。**回滚后**附件 chip 维持容器外独立行（现状可用），`AttachmentBar` 获得 `modifier` 参数（无害保留）。**回滚后真机复验：键盘弹出 + chip 时输入栏不再压缩（见验证状态）**。
- **Material 图标替换 emoji**：语音输入按钮的 🎤/🔴 改为 `Icons.Default.Mic`（聆听中改用错误色，语义＝点击停止拾音）；附件 chip 图标 `Share` → `AttachFile`（输入栏与会话流用户消息 chip 两处同步替换，原先注释「本工程只有 icons-core」已更新）。新增依赖 `androidx.compose.material:material-icons-extended`——**release 包体增量实测 216 字节**（4,968,857 → 4,969,073 B；R8 只保留被引用的 ImageVector）。

### 量测结论文档化（C-3 前置，推翻任务书假设）
- **「键盘弹出多一条空隙」真机不成立**：IME `touchableRegion` 顶边 1353、键盘可见顶边 ≈1478（微信输入法窗口顶部有 ~125px 透明区——`contentTopInsets` 是窗口边界、不等于可见像素）、Composer 上移 920px = 键盘可见高度（922px）→ `imePadding()` 正确消费系统 IME inset、`navigationBarsPadding()` 无双计、输入栏与键盘视觉间距 21px（≈7dp）正常。**结论：inset 链无需改动、不上 edge-to-edge**；可复用的量测方法与「dump 字符串搜索被消息文本污染」的坑见 HANDOVER 坑 30。

### 布局回归线索（供后续定位，未完全定根因）
- 键盘弹出时容器高度 = **186px**（= 键盘未弹、无 chip 时的输入行自然高度）——容器被限制在「不含 chip 的测量值」，chip 挤占后输入行被压缩；
- 同状态下顶栏 Row 下移至 y≈578（正常 ~150）、会话流 scrollable 节点从 uiautomator dump 消失；
- 量测矩阵：无 chip 键盘弹 [1271,1457]（h=186，正常）｜无 chip 键盘收 [2185,2377]（h=192）｜**有 chip 键盘收 [2060,2377]（h=317，正常）**｜**有 chip 键盘弹 [1271,1457]（h=186，异常）**；收键盘再弹出稳定复现（非时序抖动）；
- 疑似 `Modifier.fillMaxSize()`（根 Column）+ `imePadding()` + 会话流 `weight(1f)` + Material3 `Surface`（内部 Box `propagateMinConstraints=true`）在「非 weight 子元素高度变化 + IME inset 变化」组合下的测量交互问题。后续若重做此优化，建议先用 Layout Inspector 看测量约束链。

### 验证状态
- `assembleDebug` + `testDebugUnitTest` BUILD SUCCESSFUL；`tools/check_test_count.py` 核对 **162/162 ✓**；`assembleRelease` 通过（新签名 `E1:57:1A:49:…`）。
- ✅ **回滚后真机复验（2026-10-10，小米 15 Pro / release 包覆盖安装）**：① 附件 chip 恢复容器外独立行、输入栏容器 h=192（原状）；② 键盘弹出 + chip 时输入栏正常（附件按钮 h=135、EditText h=158——与改动前基线一致；对比未回滚版附件按钮被压至 27px / 输入框可见 ~55px 的严重回归，问题消除）；③ 附件选择 / 移除流程正常、无崩溃。
- ⚠️ **既有小瑕疵（非本次引入，回滚前后一致；留档）**：键盘弹出 + 附件 chip 同时存在时，输入框语义边界（dump bounds）比容器底边低 ~37px——截图像素显示实际渲染正常（输入框底 ≈1455，键盘可见顶 1478，无遮挡），疑似 uiautomator 语义边界与渲染边界的差异；与「并入容器」重做一起用 Layout Inspector 复核。

## v0.5.0-beta21（2026-10-10）· C-5③④ 落地（会话行派生快照 + 派生解析缓存化）· 观察期移除

### 变更
- **观察期（P0-2 三天日常使用观察）经用户决策移除**（2026-10-10）——不再作为 v1.0 判停的阻塞项；v1.0 里程碑的发布时机由用户拍板。

### 性能（C-5 执行卡 ③④，每项独立 commit）
- **③ 会话行派生快照（`AppViewModel.rowsSnapshot`）**：会话页此前每次重组都 `rows.toList()`——整表拷贝（长会话 1100+ 行）之外，新实例还会让 `remember(rows)` 的派生（SessionFiles / TurnChanges 逐行 JSON 解析）一起失效重算，输入框、滚动等无关重组也在付 O(n) 解析代价。改为 `derivedStateOf` 派生快照：仅行内容变化时产生新实例；契约单测钉住「内容不变→同一实例 / 内容变化→新实例」。
- **④ 会话页派生解析缓存化（`SessionFiles.PathCache` / `TurnChanges.DiffCache`）**：以 rowId 为键、「toolName + inputText 的 hash/长度」为内容指纹；行对象被流式更新替换时指纹变化即重新解析，未变化的行复用——`ToolDiffParser.parse` 是 JSON 解析 + Myers diff，流式期从 O(n)/token 全量重解析降为 O(增量)。缓存实例由会话页 `remember` 持有，随会话页销毁（切会话即重置）。
- 执行卡内的 ⑤ 已于 beta20 落地；⑥⑦ 明确不在本轮（有依据，见《体验提升任务书 v2》§10）。

### 验证状态
- `assembleDebug` + `testDebugUnitTest` **BUILD SUCCESSFUL**；`tools/check_test_count.py` 核对 **162/162 ✓**（beta20 的 155 + ③ 契约 1 项 + `ParseCacheTest` 6 项）。
- release 包（新签名 `E1:57:1A:49:…`）构建通过并**覆盖安装**（新旧包同签名，无需卸载、配对凭据保留；机身 `versionCode=31 / versionName=0.5.0-beta21` 已核验）。
- ✅ **真机交互回归通过（2026-10-10，小米 15 Pro / beta21 release 包）**：① **长会话滚动 + 流式期**（本会话 1100+ 行、操作期间持续产生入站帧）**1336 帧 0 janky（0.00%）**、99th 帧耗时 8ms（帧预算 16.7ms）；② **流式跟随**——发送的消息与服务端事件实时渲染到会话页；③ **会话切换** A→B→A 正常，各自派生独立渲染；④ **派生输出正确**——「最近文件」面板列出本会话文件（含 Edit/Read 工具标注），④ 缓存路径输出无回归；⑤ logcat 无 FATAL / Compose 异常。

## v0.5.0-beta20（2026-10-10）· 桥降级自愈 + 主线程缓存线程化 + v1.1 首批体验补强（含云端 beta18/beta19 合并）

**授权与范围**：用户拍板推进 v1.1 候选里「用户可感知收益」的条目（此前全部 `[需立项]`）。**A-3 / A-4 / C-3 / C-5 本轮不做**——A-3 的 103 帧 payload 无字段规格（须真机 + 桌面端在线探测）、A-4 与 C-5 改动 `RpcChannel`/状态机并发路径（自标高危，须独立灰度 + 独立真机回归）、C-3 的键盘 inset 根因须先真机量测（emoji 图标替换另需先评估 `material-icons-extended` 的包体影响），均以真机实测为前置（详见 `HANDOVER.md` §6.1 与《体验提升任务书 v2》§5）。

### 新增
- **发送本地回显（C-6）**：发送瞬间在消息流末尾插入半透明「发送中…」气泡，服务端回显同文本 `userInput` 行后自动移除，发送失败立即撤回。解决「按了没反应」的空窗期——`sendText` 的 ack 只是排队成功，turn 结束前消息不会出现在会话流里（长任务下可隔数分钟）。
- **上传失败可重试 / 在途可取消（C-1）**：上传失败进「失败态行」（文件名 + 原因 + 「重试」「取消」两个入口）；在途上传新增「取消」按钮（发 `attachmentAbortV4`）。**关键修复：重试与重选同一文件复用原 `uploadId`** —— 服务端按 uploadId 幂等（`state=="committed"` 直接返回 ref，已传分片不重传），原实现每次 `UUID.randomUUID()` 使该分支永不命中、失败即整文件重传（任务书 §5 C-1 纠正点）。
- **深链配对确认弹窗（C-10）**：协议链接（`zcode://pair` / `zcode.z.ai/remote` 等）不再静默替换当前设备连接，先弹确认框（设备名 + 「当前设备连接会被替换」说明）。

### 修复
- **审批 Tab 反馈文案滞留（C-11）**：`approvalFeedback` 与 `commandFeedback` 双轨反馈合并为单队列（由 `flash()` 统一管理显示与自动消退）。原实现 `ApprovalsTab` 从不消费 `approvalFeedback`（`consumeApprovalFeedback` 只在会话页被调），在审批 Tab 产生的反馈文案会永久滞留；横幅配色同时改由显式失败语义（`feedbackIsFailure`）决定，取代按文案前缀猜的白名单。
- **错误文案裸英文直出（C-2）**：新增 `relay/UserFacingError.kt` 映射层（本地通道自造英文 + 服务端 fault code + 网络栈英文 → 中文），覆盖 14 处用户可感知失败路径（发送 / 停止 / 上传 / 审批应答 / 表单应答 / 切换模型 / 删除会话 / 文件预览 / 新建会话 / 检查更新 / 执行模式切换 / 会话顶栏异常）。`bridge not ready`、`timeout after 15000ms`、`bridge re-established`、`channel reset`、`Unable to resolve host` 等不再直出 UI；服务端 fault code（如 `fault.connection.handshakeRequired`）复用 `RpcReply.Err` 第二参解析（无需改协议层，任务书 §5 C-2 纠正点）。
- **浅色主题对比度不达标（C-4，全部静态可算 + 新增 CI 断言）**：
  - 次要文本 `onSurfaceVariant` `#64748B → #556074`（surfaceVariant 卡内 4.28:1 → 5.71:1）；
  - 工具轨迹色浅色版 `ToolCallTrajectoryLight` `#D97706 → #B45309`（2.88:1 → 4.73:1）；
  - 待处理橙新增浅色专用 `StatusPendingLight #C2410C`（白底 2.35:1 → 5.18:1），**14 处引用全部改为主题感知**（`statusPendingTone()`）——原实现只有亮橙一个值，浅色下不可读；
  - `ApprovalsTab` / `HomeScreen` 两处**硬编码深色轨迹色**改主题感知（`toolCallTrajectoryTone()`，浅色下原为 2.15:1）；
  - Diff 增删前景色浅色版加深（`#66BB6A→#2E7D32` / `#EF5350→#C62828`）；Diff 行号栏去掉 50% 透明度（2.63:1 → 5.71:1）。
- **缓存文件无清理（C-7）**：删除会话时同步删除其 `rows_<safeId>.json`（原只从列表移除，缓存文件永久残留）；新增行缓存 LRU 裁剪（上限 30 个文件，每次写入后自动收敛）。

### 可读性
- 思考折叠行补**内容摘要**（原折叠态零摘要、只有「思考 · 持续了 N 秒」，无法判断值不值得展开）；最近文件面板的文件路径字号 10sp → 11sp。

### 新增单测（+27 项，全仓 107 → 134 项）
- `relay/UserFacingErrorTest.kt`（8 项）：本地英文 / 服务端 fault（含嵌套 `fault` 键）映射、中文透传、空值兜底、未知 fault 保留原 code、**「不得泄漏裸英文」断言**。
- `storage/SessionCacheStoreTest.kt`（5 项）：LRU 淘汰选择（超限删最旧、同毫秒写入的确定性、keep=0、空目录）。
- `AppViewModelTest.kt`（8 项）：C-6 pending 回显按文本匹配移除（含空文本 / 不匹配保留 / 只删最先插入）；C-1 `uploadId` 复用判定（同文件复用 / 换名或用同名不同内容新 id / 无失败态新 id）。
- `ui/theme/ContrastTest.kt`（6 项）：两套主题关键色对 WCAG ≥4.5:1 静态断言 + 两条**反向断言**（旧低对比色值不得回流）。
- `tools/check_test_count.py` 动态比对：**声明 134 = 实际执行 134**。

### 无设备可验证项（同日第二批，用户指示「按建议执行」）

> 本批全部为「无需真机/模拟器即可完成并验证」的工作项（本机模拟器实测不可用：CPU 为兆芯 `CentaurHauls`，x86_64 镜像强制要求硬件加速，emulator 直接退出——证据已入档）。

- **C-8 多机凭据持久化改结构化序列化**：`MultiDeviceStore` 原手拼 JSON（转义只覆盖 `\` 与 `"`）并以正则 + `split` 解析——设备名含换行、制表或其它控制字符时会写出损坏 JSON。现抽出纯函数 `storage/DevicesCodec.kt`（kotlinx.serialization 结构化对象 + `ignoreUnknownKeys`），**保留旧格式解析分支并在读取成功后一次性迁移**（旧数据内嵌 0x01 控制字符未转义、属非法 JSON，kotlinx 读不了，必须双路径兼容）。
- **A-3 前置：103 EventDispose 就绪（默认关闭）**：新增 `RpcChannel.encodeEventDispose`（纯函数，`[103, id]` 最简形态）、`disposeEvent` 发送入口（fire-and-forget）、`ConversationChannel.disposeOldListener` 在切会话 / 重置路径上的调用点；由 `SEND_EVENT_DISPOSE = false` 门控——协议文档只有帧码表一行、**payload 字段规格未实测**，经真机 / `tools/probe.py` 探测确认后再置 true 即启用（退路：服务端不认 103 时仅靠代次守卫丢弃旧应答）。
- **A-4 前置：事件流缺口检测（可观测）**：新增纯函数 `RpcChannel.hasSeqGap`（连续 / 跳号 / 重复 / 回退四类）+ 收帧路径 WARN 日志，用于真机现场诊断丢帧。**「触发重订阅」与「buffer 策略改 SUSPEND + 单泵」仍按高危处理**，须独立 commit + 独立真机回归后才可启用。
- **C-2 收尾：CI 断言**：`.github/workflows/ci.yml` 新增「UI 层不得出现底层英文错误串」grep 步骤（`bridge not ready` / `timeout after` / `bridge re-established` / `channel reset` 四个字面量只允许存在于 relay 协议层与映射表），与 `UserFacingErrorTest` 的行为断言配套。
- **新增单测（+8 项，134 → 142）**：`storage/DevicesCodecTest.kt`（6 项：特殊字符往返、多设备保序、active 缺省、旧格式 0x01 解析、损坏 JSON 兜底、旧格式空列表）+ `PureFunctionsTest` +2（缺口四类判定、103 编码形态）。

### 第三批（C-5 静态部分 + 看门狗可测试化，同日续做）

- **C-5① release 热点修复**：`AppViewModel` 的 events collector 里 `ev.data.toString()`（**每个 delta 一次全量 JSON 序列化**，主线程 collector 内）此前无任何门控——`ZLog.d` 虽被 R8 的 `-assumenosideeffects` 剥离，但**实参求值不会被剥离**，release 每个 delta 都在付这份成本（任务书 §5 C-5① 纠正 v1 后认定的真凶）。现整段观测代码（toString + 两次 `take` 拷贝 + `rpcEvents` 写入）移入 `BuildConfig.DEBUG` 门控，release 主路径只剩三路事件分发。
  - ⚠️ **C-5 其余部分本轮不做**：③ `rows.toList()` 整表拷贝、④ `remember(rows)` 里的逐行 JSON 解析缓存化、⑤ 线程与 buffer 策略、⑦ 快照差分——它们改 Compose 缓存语义与并发路径，必须在真机上观察流畅度与列表行为，按任务书要求独立灰度 + 独立回归。
- **桥看门狗「重开用尽 → 可见失败」可测试化**：把到点决策抽成纯函数 `RpcChannel.watchdogDecision`（`Noop` / `Retry(nextAttempt)` / `Fail`），`scheduleBridgeWatchdog` 改为消费该决策。beta15 遗留的验收缺口（该路径在真机上**无法构造**——需人为丢弃 `workspace-bridge-ready`，App 外部制造不了，当时只有代码推理覆盖）由此被单测钉死。单测 +2。
- **CI 断言补强**：新增「`ZLog.e` 第二参数必须是字符串字面量」断言——P0-C 红线要求 `e`（release 仍输出）只写元信息，禁止携带 payload / 正文 / 凭据（任务书 §5 决策项建议）。

### 第四批（A-3 EventDispose 实测确认并启用，同日）

> 前置：用户打开桌面端「移动端远程控制」面板（探针需面板在线才 `pair_status=matched`）。

- **字段规格双确认**：官方 web bundle 静态实证 + 动态实测。bundle 里 `sendCancelOrDispose(e,t){ zu(n,[e,t]); zu(n,void 0) }` 给出完整规格——头部数组 `[103, 原监听请求 id]`（**两元素**，区别于 102 的四元素）+ **undefined 参数段**；据此修正了本仓库的 `encodeEventDispose`（原实现只写头部数组、缺参数段）。
- **动态实测（`tools/probe.py dispose`）**：同一活跃会话（本对话会话，期间持续产生工具调用事件）——dispose 前 12s 收到 **8 帧 204**（1 快照 + 7 在线增量），发 103 后 12s **0 帧**（期间会话仍在产生事件）；服务端对 103 **静默接受**（无 201/202/203）。
- **压力验证（`tools/probe.py dispose-stress 10`）**：`listen→subscribe→dispose` 循环 10 次后保留 1 个监听，观察窗内 204 帧的 listen_id 分布 = `{末次id: 2}`——**旧监听全部释放、无 N 倍放大**，直接对应任务书 A-3 的验收口径（「切 N 次会话入站量不随 N 增长」）。
- **启用**：`ConversationChannel.SEND_EVENT_DISPOSE` 由 `false` 置 **`true`**（切会话 / 重置时对旧 listenId 发 103，fire-and-forget）。
- **探针工具修复（实测踩坑）**：① websockets 17 的 `proxy` 默认 `True` = 读「操作系统代理」（Windows 注册表），本机系统代理指向未运行的 Clash 端口 → `ConnectionRefused`，probe 已显式 `proxy=None`（只读环境变量）直连；② 探针**必须带 `ZCODE_MID`**——缺失时 auth 返回 `pair_status=waiting`（配对绑定含机器 ID），补齐后 `matched`；③ 新增 `dispose` / `dispose-stress` 子命令。
- **真机待办**：App 侧端到端（连续切 20 次会话，入站流量不随 N 增长）并入真机验收清单；探针上线会把桌面端短暂 KICKED（后者自动重连），属同 deviceSid 单 terminal 槽的预期行为。

### 第五批（2026-10-10 真机故障修复：桥降级后无自愈）

**现象（用户真机报告）**：桌面端显示「已连接」，手机端同时显示「未连接/异常」，连接反复断开又恢复。

**诊断（真机 logcat + 桌面端日志双向取证）**：
- **两层状态被混看**：中继层（device↔terminal 配对，桌面端 UI 显示的 `paired`）与会话桥层（RPC 通道）相互独立——「异常」来自后者，桌面端 `paired` 全程稳定。
- **桥的完整生命周期**：`11:17:54 Ready(e9ec2790)` → 11:19:45 起 **App 停止回 ack（约 2 分钟）** → 服务端重放未确认帧（seq 274+）→ `11:20:25` 判定 **`rpc-transport-fault`** 主动下发 `bridge-degraded` → `11:21:34` 桥重建 → `11:21:35 Ready(4f7a6a3e)` 恢复。
- **App 侧处理停摆的证据**：745 条 `ws recv` 到达 `RelayClient`，但 `RpcChannel` 解码日志、会话帧处理与 ack **全部缺席**——主线程处理被压住（长会话 1100+ 行 × 高频入站帧；高强度验收操作放大）。与任务书 **C-5「性能与线程模型」**指出的主线程热点吻合，是该卡的**首个真机实证**（观测期故障，非猜测）。
- **暴露的缺陷**：桥置 `Failed`（服务端降级 / 看门狗用尽）后**没有任何自愈路径**——只有 relay 重新 Paired 或用户手动操作才会重开桥，故障窗口被拉长（本次靠上层重连撞回来）。

**修复（低风险防呆）**：`AppViewModel.scheduleBridgeReopen()`——桥失败后自动重开，**退避 1s/2s/4s、上限 3 次**（`bridgeReopenDelayMs` 纯函数 + 单测钉死），桥就绪即归零；用尽后停失败态交上层兜底，避免把「服务端持续降级」放大成重开风暴。新增单测 +1（153 → 154）。

**发布准备（2026-10-10）**：原 release keystore（`1D:46:E9:…`）经全盘搜索确认丢失（本机仅存 debug keystore）→ 经用户拍板**生成新 release keystore**（RSA 2048 / PKCS12 / 10000 天，指纹 **`E1:57:1A:49:…:2B:3F:DD`**），配置于 `toolchain/keys/` + `app-android/keystore.properties`（**均不入库**，密码另存 `toolchain/keys/README-keystore.txt`）。**v0.5.0-beta20 为首个新签名版本**；已装设备（含 debug 包）需**卸载重装一次**（丢配对、重新扫码）。

**其余进展**：C-6 补验（发送瞬间气泡 <1s 窗口）经连拍 6 帧仍未捕获——标记「实机未直接观察（低于采样粒度）」；副作用累计 3 条测试消息进入服务端队列（协议无撤回）。

### 验证状态
- `testDebugUnitTest` + `assembleDebug` **BUILD SUCCESSFUL**；`tools/check_test_count.py` 核对 **155/155 ✓**（本地四批 107 → 144；与云端 beta18/beta19 合并后 = 153；第五批桥自愈 +1 = 154；C-5⑤ +1 = 155）；`assembleRelease`（R8 + 资源收缩）在本批代码上 BUILD SUCCESSFUL（签名 release 包随本版发布）。
- **发布（2026-10-10）**：tag `v0.5.0-beta20` @ 提交 `5be5634` 已推送（SSH 通道）；Release <https://github.com/wjf1/zcode-remote-app/releases/tag/v0.5.0-beta20>（附 `ZCodeRemote-0.5.0-beta20.apk`，versionCode 30，**新 release 签名** `E1:57:1A:49:…:2B:3F:DD`）；CI run `38030230595` **success**。
- **新签名装机验证（2026-10-10）**：release 包装机 `Success`（小米 15 Pro；`_tmp/hyperos_install_release.py` 自动确认 HyperOS「USB 安装」弹窗）；**实测卸载重装后配对凭据未丢**——App 启动直接「就绪」、会话列表完整、无需重扫码（App 侧 `allowBackup=false`，疑为 HyperOS 系统级数据保留；机制未深入区分）；会话页实时渲染、logcat 无 FATAL / 无桥降级。留档 `docs/screenshots/beta20-installed.png`。
- ✅ **真机验收全部执行完毕（2026-10-09 首轮 + 2026-10-10 补完，装于 beta20 包）**：**7 项通过、1 项部分通过** —— A-3 退订 ✅（20 次切会话 20 条 `rpc dispose`）、C-1 上传 ✅（全链路 begin/14 分片/commit/ref + **取消中止** `attachmentAbort sent`）、C-2 文案 ✅（`bridge not ready` → 「连接通道尚未就绪，请稍后重试」，英文未直出）、C-4 浅色 ✅、C-7 缓存 ✅（LRU 精确收敛 30）、C-10 深链 ✅、C-11 横幅 ✅（出现 + 自动消退）、C-6 回显部分通过（回显移除 + 队列条；发送瞬间 <1s 窗口未取证）。逐项证据与副作用见 `HANDOVER.md` 顶部验收清单。
- ⚠️ **验收环境两个坑（2026-10-10 实测）**：① 手机给 PC 开热点时 `cmd connectivity airplane-mode enable` 会被系统自动恢复（HyperOS 保热点），断网窗口只够抓一次失败横幅（发送后 2–6 秒内连续 dump）；② UI 自动化坐标会漂移、且会话流里的消息文本会污染字符串检索（老坑重演）——一律用「`content-desc` 精确匹配 + 短文本节点」判读。
- ⚠️ **签名与发布路径（2026-10-09 实测修正）**：本机 debug keystore（`89:45:76…`）与手机历史包的签名（`3A:B5:8F…`，另一执行环境所签）**不同**，覆盖安装必被拒（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`），只能卸载重装（丢配对凭据，已获用户同意并执行）；`adb install --user 999`（XSpace 分身）**同样被拒**——Android 签名校验是**设备级**的，同包名无法在任意用户空间共存。本机**缺 release keystore**；云端的 beta18/beta19 签名 Release 由另一执行环境产出（其持有 `3A:B5:8F` debug 与 `1D:46:E9` release）。**（2026-10-10 更新：新 release keystore 已生成并随 beta20 启用——见上方「发布准备」段；此后以新指纹 `E1:57:1A:49:…` 为准。）**
## v0.5.0-beta19（2026-10-09）· 待处理项跨会话泄漏修复（在别的会话弹出本会话的审批/提问）

**起因（观测期真机反馈）**：用户在会话 B 的页面上看到了**属于会话 A** 的提问卡 —— 截图是一个还没有任何消息行的会话（`重试 / 状态 12% / cn:deepseek-…`），输入栏上方却铺着一张「提问 · 需要你的回答：本地 9 处含这两个名字的位置，实际删除范围定哪个？」（该问题属于另一个会话）。

### 修复
- **会话页内联的审批卡 / 提问卡改为按当前会话过滤**。待处理项有两路来源：会话流（只含当前订阅会话）与**任务事件流（覆盖整个 workspace，不限当前订阅会话）**，两路合并后是一个全局列表；会话页此前把整份列表直接铺在输入栏上方（`ConversationScreen` 的 `approvals.forEach` / `elicitations.forEach`），于是 A 会话的卡会出现在 B 会话上。
  - 新增纯函数 `approvalsForSession(list, sessionId)` / `elicitationsForSession(list, sessionId)`（`relay/Interactions.kt`）；调用点 `MainActivity` 的会话页改传**按 `target.taskId` 过滤后**的列表（含顶栏「待审批 N」角标，随之自动收敛）。
  - **归属未知（`sessionId == null`）按可见处理**（fail-open）：未知归属 ≠ 属于别的会话 —— 宁可多显示一条，也不能把当前会话的卡藏掉。若写成严格过滤，会把「会话流快照没带 sessionId」的正常条目一并隐藏，比原缺陷更难排查；这条已由单测钉住。
  - **`待办` 页与通知栏保持全局语义不变**：它们的设计就是跨会话聚合（`ApprovalsTab` 已按会话分组、通知栏本就该提醒任意会话的待处理项），只修被误用的会话页。
- 同一轮附带的排查记录：用户同晚报告的「应答失败：`answer.action` invalid_value」经核对 **host schema**（`research/asar/out/host/chunk-BG4MS6RN.js`：`action?: enum(["accept","decline","cancel"])`）与全仓 4 处 `action` 产出点，**当前代码不存在能产出非法 action 的路径**；该失败发生在 21:02，当时机上装的是来源不明的异源签名包（已卸载）。结论与复现步骤见 HANDOVER §6.0「观测期反馈」。

### 测试
- 新增 `relay/PendingScopeTest.kt`（5 项）：本会话保留 / 他会话剔除 / 归属未知 fail-open / 未选中会话时带归属者不放行 / 空输入。单测 111 → **116 项全绿**。

### 验证状态
- `assembleDebug` / `testDebugUnitTest` / `assembleRelease`（R8 + 资源收缩）三条构建均 BUILD SUCCESSFUL；`tools/check_test_count.py` 核对 **声明 116 = 实际执行 116**。
- **真机装机冒烟通过**（2026-10-09，小米 15 Pro / Android 17 · HyperOS，debug 包 `versionCode 29`）：升级安装 → 启动 → 打开会话，消息行正常渲染、无待处理项时无卡片、`logcat` 无 `FATAL`。
- ⚠️ **跨会话 A/B 的真机证据待补**：当前工作区**没有任何 pending 项**，无法当场构造「A 会话挂起 + 看 B 会话」的对照；仓库的 `DebugApprovalReceiver` 只作用于**通知层**（`ApprovalNotifier.sync`），注入不到 App 内存里的待处理列表，已确认无法替代。下次自然出现待处理项时按该场景核对即可。
- 本版本为**观测期（P0-2）内抓出并修复**的缺陷，按仓库规则观察窗口自本次装机日 **2026-10-09** 重新计时。

## v0.5.0-beta18（2026-10-09）· 代码块高亮「吞字」缺陷修复 + 模拟器仪器化渲染回归网

**起因（本地验证抓出，非用户报障）**：beta17 的验收留下一个空洞 —— 抽样会话视口内没有出现围栏代码块与 GFM 表格，这两个组件当时只能标注「未取到真机样本」。本轮在**模拟器**上补一条仪器化渲染测试来堵这个洞，测试第一次运行就把一个**用户可见缺陷**照出来了。

### 修复
- **代码块里被高亮的字符整个消失（缺陷）**：`dev.snipme highlights` 的 `ColorHighlight.rgb` 是**纯 RGB**（如 `0x2BBAC5`，不含 alpha 位），而 Compose 的 `Color(Int)` 按 **ARGB** 解释 —— 直接把 `rgb` 传进去得到的是 `alpha=0x00` 的**全透明**色。症状不是「高亮没生效」，而是**关键字 / 字符串 / 注释被画成透明、肉眼看不见**，代码块只剩标识符与标点（例如 `fun main() { val message = "hello zcode" }` 会渲染成 `main`、`message`、`println message` 三行残句）。
  - 修法：新增纯函数 `opaqueHighlightArgb(rgb)`（`rgb or 0xFF000000`）并在 `MarkdownView` 的高亮 span 处统一使用，见 `ui/components/MarkdownView.kt`。
  - 证据（模拟器实测位图，已归档）：修复前 `docs/screenshots/render-code-block-before-fix.png`、修复后 `docs/screenshots/render-code-block.png`；主题 token 色命中像素 **0 → 1789**。
  - **影响范围**：beta17 发布的 APK 含此缺陷；任何走 `MarkdownView` 的代码块（会话流里的代码卡片）都受影响。GFM 表格、行内代码、列表、链接不受影响（它们不走这套彩色 span）。

### 新增（验证能力，不新增 App 功能）
- **模拟器仪器化渲染回归网**（`app/src/androidTest/`，project 首次有 `androidTest` 源集）：用 fixture Markdown 直接渲染真实的 `MarkdownView`，不依赖中继与配对。
  - `MarkdownRenderTest.gfmTableAndInlineMarkupRender`：表格单元格 / 标题 / 行内代码 / 链接 / 任务列表逐一存在，并对捕获位图断言**着墨像素**（证明真的画到了屏幕上，而不只是语义树里有节点）。
  - `MarkdownRenderTest.fencedCodeBlockGetsSyntaxHighlighting`：围栏语言名解析 + **主题 token 色真的被画到屏幕上**（按 `#2BBAC5/#D55FDE/#89CA78` 逐像素比对）。**上面那条缺陷就是被这条断言抓出来的**。
  - 捕获位图留证：`app files/render-evidence/*.png`（`adb exec-out run-as com.zcode.remote cat ...` 取出）。
- **纯 JVM 高亮单测 `CodeHighlightTest`（4 项）**：把高亮流水线的三层分别钉住 —— 语言名解析、kotlin 代码产出 ≥3 段 ≥3 色高亮、**RGB→ARGB 必须补不透明 alpha**（缺陷成因的针对性回归），以及一条反直觉实测：**语言名不可识别时不是「不亮」，而是回落 DEFAULT 泛化高亮**（原先「未知语言 = 无高亮」的对照组假设因此作废）。
- `app/build.gradle.kts`：`testInstrumentationRunner` + `animationsDisabled`（渲染断言要求稳定帧）+ androidTest 依赖（`ui-test-junit4` / `ui-test-manifest` / `androidx.test:*`，均为 test/debug 作用域，**不进 release 包**）。

### 验证状态
- `bash build.sh`（assembleDebug）**BUILD SUCCESSFUL**；`bash build.sh testDebugUnitTest` **BUILD SUCCESSFUL**；`bash build.sh assembleRelease`（R8 + 资源收缩）**BUILD SUCCESSFUL**。
- 单测：**声明 111 = 实际执行 111 ✓**（beta17 的 107 项 + `CodeHighlightTest` 4 项，`tools/check_test_count.py` 核对）。
- 仪器化：**模拟器 `apkrev35`（Android 15 / x86_64，AEHD 加速）上 2/2 PASS**（`connectedDebugAndroidTest`）。
- 修复前后证据：见上文两条截图与 token 色命中像素 0 → 1789。
- ✅ **真机复核通过（2026-10-09，小米 15 Pro / Android 17 · HyperOS，debug 包 `versionCode 28`）**：装机 → 重新扫码配对 → 打开会话「整理 commandcode-proxy 发行版」→ 屏幕上的 ` ```bash ` 代码卡**完整渲染且高亮分色**（注释灰、`-a` / `--tags` 青、字符串绿、数字橙红），**token 色命中 2365 像素**、肉眼可见 `git tag -a v4.23.0 -m "…"` 逐段着色，**未再出现 beta17 的「吞字」**（截图归档 `docs/screenshots/render-code-block-phone.png`）。即本轮为**双轨验收**：模拟器仪器化测试（可重复）+ 真机目视/像素复核。
- ⚠️ **两个装机坑（真机复核时踩到，已记入 HANDOVER 避坑清单）**：① 手机上原有包是**异源 debug 密钥**签的（SHA-256 `89:45:76:B3…`，与仓库 debug `3A:B5:8F…`、release `1D:46:E9…` 都不同），`install -r` 必然 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，只能**卸载重装**（配对凭据随卸载清空 → 需重新扫码）；② HyperOS 首装会由「USB 安装」确认弹窗拦截（`INSTALL_FAILED_USER_RESTRICTED`），且本机 USB 调试接口会自行掉线（表现为只剩 WPD 设备、adb 里 `offline`/消失），需切「传输文件」+ 重新允许调试或拔插数据线。
- 版本：`versionName 0.5.0-beta18` / `versionCode 28`。

## v0.5.0-beta17（2026-10-08）· 会话页排版全面对齐桌面端 + 会话级状态面板（含「运行中」状态回填修复）

**起因（用户真机反馈）**：① 会话在运行中，退出到主界面却看不到「运行中」标记；② 「APP 会话里面的显示逻辑和排版能否按桌面端进行设计」。

**对齐结论（先说边界）**：桌面端主消息流本身就是**单列窄列**（`max-w-4xl` = 896px、行距 20px、内边距 16/24px），没有分栏、没有头像、没有轨迹竖线。手机 384dp 与它方向一致，所以「按桌面端设计」= 对齐**组件形态 + 信息层级 + 数值规范**，不是搬宽屏三栏布局。

### 新增
- **会话级状态面板（顶栏「状态」入口 + 底部弹层）**：纯只读，把快照里已有的会话级状态一次性呈现出来。分区与显示条件（**无数据自动隐藏**）：上下文容量（占比条 + `已用/上限` + 分类明细降序 + 缓存命中率）、目标（objective/summaryTitle + 状态 + 耗时 + 预算）、进程（`plan.items` 待办，默认按桌面阈值 6 项折叠，可展开全部）、终端（后台 `bash` 任务）、智能体（`running` / `endedTotal`）、排队输入。**默认折叠**的只有终端与智能体 —— 它们动辄一串 id，平时只需要计数。
  - 缓存命中率**仅 ≥78% 才显示整行**（桌面阈值 `0.78`，低于阈值桌面直接不渲染该行，不是显示成 0%）；百分比保留 **1 位小数**（桌面显示 `78.2%`，这里刻意不复用取整的 `Format.percent` —— 78% 附近取整会把阈值判定的差异抹平）。
  - 目标耗时的**走秒逻辑照抄桌面 `JW`**：`status ∈ {active, verifying, notSatisfied}` 属「还在跑」，此时显示值 = 落库的 `timeUsedSeconds` + `(now - activeRunStartedAtMs)/1000`（服务端只在里程碑打点落库，不加这一段的话目标跑到一半显示的是冻结值）。仅在运行中才投递每秒定时器。
  - **「计划」分区本轮不做**：桌面端该分区的数据来自**另一条 RPC**（`transport.plans({sessionId})` → `conversationPlansV4`），本 App 没有订阅它；本轮边界是「不新增任何查询命令」，故不为了凑版式去开一条新 RPC。面板里的「进程」分区用的是快照自带的 `plan.items`。
- **待发送队列条（只读）**：`queue.items` 非空时在输入栏上方显示「待发送 N 条 · 自动发送/暂停发送」。**只做告知**，不提供立即发送 / 取消排队 —— 那属于队列控制命令，超出「纯只读面板」的边界。
- **补齐 3 种此前完全没有渲染的会话行类型**：`subagent`（`{类型} · {状态} — {摘要}` 单行）、`timelineMarker`（居中标签 + 两侧横线）、`hookInvocation`（复用统一折叠行）。
- **统一折叠行骨架 `CollapsibleRow`**：思考块、工具调用、Hook 调用此前是三套各写各的形态，现在是同一个渲染器。**有意偏离桌面**：桌面的 chevron 常态 `opacity-0`、仅 hover 时淡入，手机没有悬停态，照搬会让「可折叠」完全不可发现，故 chevron 常显（代码注释写明）。
- **设计基座**：`ui/theme/Typography.kt`（字号档 10/12/13/14/16/18sp，基准 14）、`ui/theme/Dimens.kt`（圆角 2/4/6/8/12/16dp、4px 间距网格）、`Theme.kt` **纯加法**补叠加层/细边框/卡片与标签底色（不动任何既有 Material3 槽位）、`ShimmerText`（运行中渐变流光）。
- **`ToolKindLabels`**：`toolName → 家族 → 中文类型标签` 映射表，数值与文案逐条取自桌面渲染器（`jr({toolName}).family` 的精确匹配表 + 小写索引），保证手机与桌面看到同一个词。同一套状态语义 **6 态**（等待中/执行中/已执行/执行失败/已拒绝/已停止）同时接受桌面两条命名链路的别名。

### 修复
- **「会话在运行中，退出到主界面却看不到运行中」**（用户反馈的直接根因）：会话页的「运行中」此前只由会话通道的相位回调驱动，**主界面列表没有消费这个信号**，于是退出到列表后该会话退化成普通状态。修法：`AppViewModel` 订阅相位回调并把结果回填进首页列表项，列表与详情页对同一会话的状态判定从此一致。
- **`ConversationChannel` 增量补丁的覆盖缺口**：`state.updated{patch}` 此前只合并 `pendingInteractions` / `elicitations`，其余会话级键被直接忽略 —— 后果是**即使快照解析补齐了 `usage`/`goal`/`plan`，这些数据也只在首帧正确、之后永不更新**。现在按桌面语义合并：patch 里出现的键整块替换（出现且值为 null → 服务端在清除该块），未出现的键原样保留。
- **`plan` 的解析形状错误**：此前按「计划的一轮」（`iteration`/`title`/`items`/`completedCount`/`verificationOutcome`）解析，本次从桌面包实证到 `plan.items[]` 的元素其实是**待办项** `{id, content, status}`。已整块重写为 `PlanTodo` + `PlanState`（派生 `totalCount`/`completedCount`/`allCompleted` + `display(limit)` 折叠），并同步替换单测。**教训记在代码注释里：解析形状必须回溯到消费该数据的渲染代码，不能只凭字段名推断。**
- **`RowStore` 增量追加丢 `summaryText`**：`row.delta` 的分支只追加正文，`summaryText` 被丢掉。
- **Diff 视图行号错位**：改为**单列 48dp 左贴边**（新增行用新行号、删除行用旧行号，右对齐等宽），左侧 3dp inset 竖条。
- **链接点击的越权跳转风险**：会话正文来自模型输出，内容不受我们控制。此前直接交给系统 `ACTION_VIEW`，`file://`、`content://`、`intent://`、`javascript:` 这类 scheme 可能被本机已安装应用注册的 intent-filter 接走（包括启动其它 App 的内部页面）。现在收敛为 **http/https 白名单**，其余拦下并提示。

### 排版对齐（阶段 B 逐项）
- **用户气泡**：蓝底主色改为**中性半透明表面 + 1px 细边框 + 12dp 圆角（右上角 2dp）**，上限 340dp、14sp。
- **助手正文**：**去掉卡片外壳**，改为全宽 Markdown（与上一行间距 20dp）—— 这是本轮视觉变化最大的一处。
- **工具调用**：由「卡片外框 + 琥珀色徽章」改为**无外框的内联折叠行**（图标 + 中文类型标签 + 主文案 + 增删徽标 + chevron）；展开体是「Parameters」（JSON 美化）/「Result」/「Error」三段，各限高 240dp。**折叠状态按 `toolCallId` 记忆并提升到屏幕层** —— LazyColumn 会回收滚出视野的行，行内 `remember` 随之销毁，表现为「滚上去再滚回来，刚展开的详情自己合上了」。
- **思考块**：折叠行「思考 · 持续了 N 秒」/ 流式「正在思考」；展开体用中性 1px 竖线（`drawBehind` 画 —— LazyColumn 项内高度约束无限，`fillMaxHeight` 会退化成 0 高，`IntrinsicSize.Min` 与 `verticalScroll` 不兼容）。
- **轮次头**：`轮次 · 来源 · 状态` + 独立时长行；**Markdown 渲染升级为完整 GFM**（表格 / 任务列表 / 嵌套列表 / 链接 / 行内 HTML）+ 代码块真语法高亮。
- **代码块**：圆角 12dp、页面底色、头部（文件图标 + 语言名小写 + 复制）、可选行号。

### 依赖变更（含安全评估）
- 新增 `com.mikepenz:multiplatform-markdown-renderer-m3:0.27.0` 与 `-code:0.27.0`（Apache-2.0）。
  - **版本上限原因**：本项目 Kotlin 插件为 2.0.20，而 Kotlin 元数据不向后兼容 —— 该库 0.30.0 起改用 Kotlin 2.1+ 编译，2.0.20 编译器读不了其元数据（硬报错）。0.27.0 是最后一个 Kotlin 2.0.x 编译的版本，故锁死在此，**升级 Kotlin 插件前不得上调**。
  - **安全边界**：`-code` 传递依赖 `dev.snipme:highlights` 1.x（**纯 JVM，无原生 `.so`**）；**不加载外链图片**（显式 `NoOpImageTransformerImpl` + 全工程无图片加载库）；链接走 http/https 白名单；**不含**数学公式与 Mermaid（本轮范围红线）。

### 新增单测（+60 项，全仓 47 → 107 项）
- 新增 `relay/SessionStateTest.kt`（21 项）：会话级状态块的解析边界（缺块 / 空对象 / 字段形态漂移 / 裸字符串项 / 无正文脏项）、`SessionState.merge` 的增量语义、`PlanState.display` 的三组折叠、目标走秒（含时钟回拨）。这些块的形状是从真机骨架与桌面包反解出来的，**没有官方 schema，所以边界用例比正常路径更重要** —— 一条畸形数据不得让整块状态崩掉，也不得让面板顶出一个空分区。
- 新增 `ui/components/ToolKindLabelsTest.kt`（20 项）：家族映射精确命中、大小写不敏感、别名、未知工具兜底；两套状态命名链路的归一。
- 新增 `util/FormatTest.kt`（16 项）：紧凑数字、百分比、时长、目标耗时的格式化。
- `relay/PureFunctionsTest.kt` +3 项：任务事件在途状态映射为运行中、仅显式终态判定为结束、仅相位不足以确认运行中。
- `tools/check_test_count.py` 动态比对（脚本无需改）：**声明 107 = 实际执行 107**。

### 验证状态
- `bash build.sh`（assembleDebug）**BUILD SUCCESSFUL**；`bash build.sh assembleRelease`（R8 + 资源收缩，验证新依赖可被正确压缩）**BUILD SUCCESSFUL**；`bash build.sh testDebugUnitTest` **BUILD SUCCESSFUL**；`tools/check_test_count.py` 核对 **107/107 ✓**。
- ✅ **真机验收通过**（小米 15 Pro / Android 17 · HyperOS，装机件 `versionCode 27` / `versionName 0.5.0-beta17`）。仓库**没有 `androidTest/`、没有 Compose UI 测试**，UI 回归无自动化网，只能人工核对；本轮以 **`uiautomator` 层级取证 + 截图像素断言**（区域主色统计、定点取色）双轨核对 —— 颜色与圆角一类视觉令牌在层级里看不到，必须走像素断言。结论：
  - **「运行中」修复（原始缺陷场景）**：进入会话后再退出到首页，列表卡片仍显示 `Zcode · glm · 运行中` 与「正在查看」，「运行中 (1)」筛选计数同步；重新进入会话顶栏为「已连接 · 运行中 · 共 3481 行」。**这正是报障场景，已闭环**。
  - **排版对齐（逐项像素实证）**：工具行为**无外框内联折叠行** —— 行带主色即页面底色 `#202024`（无卡片底），全图**琥珀色 `#F59E0B` 采样 0 像素**（旧的琥珀徽章已彻底移除）；增删徽标为深绿 `#253730` / 深红 `#41292D` 底；思考行取到推理紫 `#A78BFA`；**用户气泡底色 `#37373A`（中性，非旧蓝底）**，左边缘取到 1px 独立边框像素；助手正文 `x39..1041` 全宽且带内主色为页面底（**无卡片外壳**）；工具行三段展开可见 `Parameters` 段；状态文案中文化一致（`已执行` / `执行中` / `正在执行`）。
  - **会话级状态面板**：顶栏「状态 NN%」入口正常；六分区全部渲染且无数据分区自动隐藏 —— 上下文 `78.9k / 150k` + 缓存命中 `97.6%`（≥ `0.78` 阈值内，按预期显示）+ 来源明细降序（系统工具 38% > MCP 工具 34.3% > 消息 21.3% > 技能 2.2% > 系统提示词 2.1% > 其他 2%）、目标（`paused` + 已运行 1 小时 17 分，走秒逻辑生效）、进程 `6/6`、终端「1 个后台运行」、智能体「0 运行 · 4 已结束」、排队输入分区因无数据隐藏。
  - **C1 增量补丁缺口已修复（关键实证）**：同一会话内顶栏「状态」百分比随流实时递增（`53% → 56% → 63% → 66%`），证明 `state.updated` 的会话级块不再只首帧正确。
  - **稳定性**：`logcat` 无异常、无 `FATAL`、**无 Compose 嵌套滚动告警**（面板滚动落在最外层）。
  - 如实标注未直接观察项：本轮抽样视口内**未出现围栏代码块与 GFM 表格**，故语法高亮与表格渲染未在真机直接取到样本（实现已接库并有单测覆盖，此处属「实机未直接观察」而非「已验」）。
  - ⚠️ **2026-10-09 追记（beta18）**：上述空洞已由模拟器仪器化测试补上 —— 结果是**表格渲染正常**，而**代码块语法高亮当时是坏的**（高亮字符因 alpha=0 全透明而不可见，见 beta18 条目）。即：这条「未取到样本」如实标注救了一次误判，但缺陷本身确实漏到了发布包里。
- 版本：`versionName 0.5.0-beta17` / `versionCode 27`。

## v0.5.0-beta16（2026-10-07）· 会话异常原因可见、标题解包与输入栏对齐（附顶栏标签中文化）

**起因（用户真机反馈 + 截图）**：① 会话列表只显示「异常」二字，看不到原因（桌面端有）；② 会话页顶栏标题显示成原始 JSON（`{"title":"分…`）；③ 输入栏文字与按钮错位（「难看」）。

### 新增
- **会话页错误原因横幅**：解析快照 `control.lastError`（**协议保证存在**，`research/CONVERSATION-PROTOCOL.md:159`；host 侧 task meta 同名字段，桌面端显示的就是它——真机取证样本：`{code:'3009', detail:'Turn execution failed\nprovider=… reason=rate_limited status=429 retryable=false'}`）。横幅带「复制」按钮（沿用既有剪贴板模式），错误原文可直接报障。
- **首页列表补齐原因**：`SessionItem` 新增宽容解析的 `lastError` 字段；VM 在收到会话快照时把原因回填进列表。⚠️ **数据边界如实标注**：`PROTOCOL.md` 记录的 bootstrap `tasks[]` 形状**不含**该字段（文档样本取自健康会话），故「没打开过的会话」可能仍只有「异常」二字——完整原因一定在会话页横幅里。

### 修复
- **标题双重序列化解包**：host 曾把会话标题存成字面量 `{"title":"…"}`（`tasks-index` 实锤：`sess_98e11ba2` 的 title 就是这串 JSON，与正常标题的 `sess_05f98803` 并存）。现对 `SessionItem.title` 与快照 `meta.title` 做防御性解包（能解析出内层 `title` 字符串就用之；其余原样，不抛异常）。**这是 host 侧数据瑕疵，App 端只是不再把它原样显示给用户。**
- **输入栏对齐（任务书 B-2）**：`Alignment.Bottom` → `CenterVertically`——文本框 56dp 高、文字垂直居中（距顶 ~28dp），按钮贴底则中心在 ~38dp，错位 ~10dp（即用户截图里「文字偏上、按钮偏下」）；附件/发送/停止三控件统一到 **48dp 触控档**（图标视觉 18dp，与语音按钮同档）。**按任务书红线未动 inset 顺序**（`statusBarsPadding().navigationBarsPadding().imePadding()` 换序零效果）。
- **会话页顶栏 phase 标签补中文映射**：host 对失败轮次直接下发 `phase="error"`（而非 `completedError`），此前落到 `phaseLabel` 的 `else -> p` 分支，顶栏原样显示英文 `error`，与列表对同一会话显示的「异常」自相矛盾。补 `"error" -> "异常"`。

### 新增单测（+4 项，全仓 43 → 47 项）
- `errorTextPrefersMessageThenDetail`（形状取自桌面端 tasks-index 实锤样本）、`unwrapJsonTitleHandlesDoubleSerializedTitle`、`sessionItemParsesLastErrorAndUnwrapsTitle`、`parseControlReadsLastError`。

### 验证状态
- `gradle testDebugUnitTest assembleDebug` **BUILD SUCCESSFUL**；`tools/check_test_count.py` 核对 **声明 47 项 = 实际执行 47 项**。
- ✅ **真机验收通过**（小米 15 Pro / 1080×2400 / 450dpi，beta16 装机实测，四屏取证 `_tmp/s8s.png`、UI dump `_tmp/u9.xml`）：
  - ① **错误原因横幅**：打开 `修改插件，使其在DSH desktop桌面端安装不再报这个错误…`（`已连接 · error · 共 219 行`）→ 横幅逐字显示 `Start Plan is busy and automatic model stream recovery reached the maximum retry count.`，与 host `tasks-index` 中 `sess_ed1b88ff-…` 的 `meta_json.lastError.message` **逐字一致**；点「复制」弹 Toast「错误原因已复制」。
  - ② **标题解包**：列表与顶栏均不再出现 `{"title":` 原始串；两个同名会话（`sess_98e11ba2` 原始 JSON vs `sess_05f98803` 洁净）顶栏均显示干净的 `分析 ZCode STREAM_IDLE_TIMEOUT 报错原因`。
  - ③ **输入栏对齐**：`uiautomator` 量得附件 `[62,197]–[197,2349]`、EditText `[208,2202]–[872,2360]`、发送 `[883,2214]–[1018,2349]`，三者垂直中心同为 **y=2281（偏差 0px）**；控件高 135px = 48dp，符合预期触控档。
  - ④ **顶栏 phase 标签**：同一 error 会话顶栏由英文 `error` 变为 `已连接 · 异常 · 共 219 行`（修复生效复验）。
- `versionName 0.5.0-beta16` / `versionCode 26`。

## v0.5.0-beta15（2026-10-07）· 修复「已连接却永久卡在会话报错」与网络恢复慢（两个连接层缺陷）

**起因（用户真机反馈）**：「设置里显示已连接，但会话页持续报 `异常：hello: bridge not ready`，永不恢复」；以及此前实测的「断网 90s 后恢复耗时 ~47s，而基线是 `<9s`」。两个问题都在连接层，一并修掉。

### 修复
- **桥握手无超时 → 永久卡在 `Opening`（用户反馈的直接根因）**：`RpcChannel.openBridge` 此前只把状态置为 `Opening` 并发出 `workspace-bridge-open`，**没有任何超时或重开机制**；一旦 `workspace-bridge-ready` 帧丢失，桥会**永久**停在 `Opening`，此时 `call`/`listen` 一律立刻回 `bridge not ready` —— 于是「中继/设置显示已连接，会话页却一直报错且永不恢复」。
  修法：新增**桥握手看门狗**——`Opening` 超过 6s 未就绪即重开，最多 3 次（约 18s）；用尽后显式置 `BridgeState.Failed("工作区桥未就绪（已重开 N 次）")`，把「静默卡死」变成「可见失败」。看门狗用令牌（`bridgeOpenToken`）保证只有最新一次 open 的看门狗有效，避免 workspace-list 帧连发时多个看门狗互相触发形成放大风暴。
- **`bridge not ready` 未被判定为可重试 → 不自动恢复**：A-2 引入的自动重订此前只认 `timeout`，而桥未就绪属**瞬态**（`Opening` 期间的回错）。新增 `ConversationChannel.isTransientHandshakeError()`（覆盖 `timeout` / `bridge not ready` / `bridge re-established`）用于 `Status.Failed.retryable`；配合「桥变 Ready 时自动重订会话」的既有事件路径，此类失败现在会自愈。
- **网络恢复要白等一整轮退避（实测 ~47s）**：`NetworkGate.waitBeforeReconnect` 的实现是**先 `delay(退避)` 再判可用性**——于是断网期开烧的退避（可达 48s 封顶）在网络已恢复后仍要被烧完，与本节注释宣称的设计意图（「无网时挂起而非空烧退避」）不符。
  修法两处：① `waitBeforeReconnect` 改为**无网时直接挂起等待恢复、完全不烧退避**（有网才正常退避）；② 新增 `NetworkGate.onAvailable` 回调 + `RelayClient.onNetworkAvailable()`，在网络恢复的瞬间**重置退避计数、取消已排队的退避等待并立即重连**（已处于 `Paired`/正在连接时不打断健康连接）。VM 侧接线 `gate.onAvailable = c::onNetworkAvailable`。
- **退避公式抽为纯函数 `reconnectBackoffMs(attempt)`**（3s 起指数翻倍、48s 封顶），使「退避语义」首次可被 JVM 单测守住。

### 新增单测（+2 项，全仓 41 → 43 项）
- `transientHandshakeErrorCoversBridgeNotReady`：超时 / 桥未就绪 / 桥重建均判瞬态；协议层硬性拒绝（`handshakeRequired`）不得判瞬态。
- `reconnectBackoffIsExponentialAndCapped`：3/6/12/24/48s 序列与封顶。

### 真机验证（2026-10-07 · 小米 15 Pro `9f6241b4`，Android 17 / HyperOS）

| 项 | 结果 | 证据 |
|---|---|---|
| 健康路径非回归（看门狗不误触发） | ✅ PASS | 冷启动 `bridge=Closed → Opening → bridge-open attempt=1 → Ready` 仅 **127ms**；**全程无「未就绪/重开」日志** → 6s 看门狗无假阳性（约为实测值的 47 倍余量） |
| **断网 80s 后恢复延迟** | ✅ PASS（修复前 3 个版本均 ~47s） | `恢复延迟 = 5 秒` |
| **断网 150s 后恢复延迟** | ✅ PASS（同上） | `恢复延迟 = 3 秒`；日志顺序 `网络恢复 onAvailable → ws open`（236ms）→ `net-watch: 网络恢复，重置退避并立即重连` |
| 恢复后会话重新对齐 | ✅ PASS | 恢复后 `subscribed sub=…` 且桥重新握手至 `Ready` |
| 桥重开 3 次用尽后的可见失败（③） | ⚠️ **未能构造** | 需人为丢弃 `workspace-bridge-ready` 帧，从 App 外部无法制造；仅代码推理 + 健康路径非回归覆盖 |
| 会话页不再出现永久 `bridge not ready`（①） | ⚠️ **间接覆盖** | 同一根因（桥无超时）已由看门狗 + `bridge not ready` 判可重试 + 桥 Ready 事件重订三重覆盖；未能在真机复现原始故障 |

> **⚠️ 本轮修复被真机测量推翻过一次，记录如下（避免后人重犯）**
>
> 首版修复（「无网不烧退避」+「网络恢复重置退避」）在**断网 80s 时 PASS（5s）**，但**断网 150s 时 FAIL（46s）**。第二次测量暴露了两个我没预料到的前提：
> 1. **`probeNow()` 假阳性**：进入飞行模式的瞬间 `activeNetwork` 仍短暂报告可用，导致 `NetworkGate.onLost` 走进「某条网络 lost，但仍有可用网络，忽略」分支 → **`available` 残留为 true** → 「无网不烧退避」的分支根本没走，退避照旧烧到 48s 封顶。
> 2. **我自己的守卫挡了修复**：我原本在 `onNetworkAvailable` 里写 `Connecting -> return`（怕打断正在进行的连接），但 **`RelayClient.onFailure` 只调度重连、不改 `_state`**，于是状态会残留为 `Connecting` → 该守卫让「掐断退避」永远不生效。日志证据：恢复后仍多等 42s（恰好是那轮 48s 退避的剩余时间），且**完全没有** `net-watch:` 日志。
>
> 最终修法：`onNetworkAvailable` 只对 **`Paired` / `WaitingPeer`**（真正有 live socket 的状态）提前返回；其余一律「重置退避 → `cancel()` 掉正在 sleep 的退避 → 立即 `connect()`」，并给重连协程加 `if (!isActive) return@launch` 确保被取消的调度**不会**再 connect（否则双连接）。**教训：恢复路径不能依赖「失败路径并不维护」的状态标志。**
>
> 残留（已登记，未改）：`onLost` 里的 `probeNow()` 去抖守卫仍会在断网瞬间误判，使断网期继续空烧退避（只影响日志/耗电，**不再影响恢复延迟**，因为恢复已由 `onAvailable` 直接掐断）。改动它需先验证其原始理由（双网并存去抖），故本轮未动。

### 验证状态
- `gradle testDebugUnitTest assembleDebug` **BUILD SUCCESSFUL**；`tools/check_test_count.py` 核对 **声明 43 项 = 实际执行 43 项**，0 失败。
- 恢复延迟实测脚本：`tools/net_recover_test.sh`（断网指定秒数 → 测量到 `ws open` 的延迟）。
- `versionName 0.5.0-beta15` / `versionCode 25`。

## v0.5.0-beta14（2026-10-07）· 会话流渲染用户消息的附件 chip

**新增功能（用户直接提出，超出《体验提升任务书 v2》档 A/B 的缺陷修复范围，经用户拍板立项）**：此前在会话里发送带附件的消息后，附件在会话流中**不可见**——只能看到文本气泡，用户无从确认文件是否真的带上了。现在与官方客户端一致：附件以 chip 形式显示在消息气泡上方。

### 新增
- **`ConversationRow.attachments`**：解析 `userInput` 行的 `attachments` 字段（服务端回显）。元素类型 `RowAttachment(ref, fileName, mime, bytes)` 与官方 web `attachmentRef` 同形，字段可空、畸形数据容错（非数组 / 非法元素一律跳过，保证单条坏行不会让整帧解析失败）。
- **会话流附件 chip**：`ConversationScreen` 新增 `UserBubble`，在用户消息气泡上方右对齐渲染附件 chip（图标 + 文件名 + 体积），与输入栏附件条共用同一套视觉语言与 `humanBytes` 格式化。历史消息只展示不可移除（`ref` 是 host 侧暂存引用，非工作区路径）。
- **离线可见**：`attachments` 是 `ConversationRow` 的可序列化字段，随行缓存一起持久化，故冷启动秒开时也能显示附件（此前写入的旧缓存无此字段，会按空列表处理）。
- **新工具 `tools/check_test_count.py` + CI 断言**：核对「声明的 `@Test`」与「实际执行的用例」是否一致，任何声明了却没跑（注解被注释掉/粘连、方法非 public）都会让 CI 变红。起因见下方踩坑第一条。

### 依据与边界
- **协议依据**：`PROTOCOL.md` §6.6 记录 `userInput` 行回显 `attachments`（2026-09-30 实测六项 PASS）；`research/CONVERSATION-PROTOCOL.md:182` 记录行 schema 含 `attachments?`。**本次以真机缓存中的真实行数据复核了该结论**（样本：`{ref: zcode-artifact://sess_…/tool-result-…, fileName: master-plan-v1.1.md, mime: text/plain, bytes: 39733}`），非仅依据文档。
- **不需要改协议**：服务端已经把附件随行推回，本次纯客户端渲染。
- **不做点击预览**：`attachmentRead` / `attachmentPreviewSource` 虽存在于 host 的 RPC 路由表（`research/FRAME-CODEC.md:353`），但协议字段未逆向、`ref` 也不是工作区路径，故本轮仅展示。若要支持点击查看，需单独立项。
- **图标复用**：本工程仅依赖 material-icons-core（无 `Description` / `AttachFile` 等扩展图标），故与输入栏附件 chip 共用 `Icons.Default.Share`；`C-3` 已登记统一替换该图标。

### 真机验证（2026-10-07 · 小米 15 Pro，Android 17 / HyperOS）
完整走通用户真实路径（**非构造数据**）：推送测试文件 → App 内点附件按钮 → 系统选择器选中 → `attachmentBegin/Commit` 成功（`附件已提交 ref=zcode-artifact://…`，附件条显示 `zcode-att-verify.md` + `89B`）→ 输入文本并点发送 → 快照/增量返回该行。

按 **y 坐标**断言 chip 落在会话流而非输入栏（输入栏 y≈2200+）：

| 文本 | y | 归属 |
|---|---|---|
| `zcode-att-verify.md` | 1083 | 会话流内（chip） |
| `89B` | 1083 | 同一 chip 的体积 |
| `attachment-render-check` | 1361 | 紧邻下方的消息气泡 |

即：chip 在气泡**上方**、右对齐，发送后输入栏不再残留 chip（附件被消费），与官方客户端呈现一致。

新增单测 2 项（真实样本解析 + 畸形数据容错），全仓 **41 项**全绿。

### 验证副作用（如实记录）
验证时向真实会话 `sess_0f00b96b`（「zcode-dotfiles 优化方案可行性确认」）发送了一条测试消息（文本 `attachment-render-check` + 89 字节测试文件）。核对桌面端 `tasks-index`：该会话 `status=completed`、`updated_at` 未变化 → **未调度 agent 轮次、未消耗额度**。协议无「删除消息」操作，该行无法程序化清理。

### 验证方法论补充（新增三条踩坑）
- **`@Test` 被注释掉会导致测试静默消失（最严重的一条）**：beta13 轮次中，一次「删空行」的编辑把
  `// ---------- 注释 ----------` 与 `@Test` 挤到同一行，注解落进行注释 → 方法从此没有注解 →
  JUnit **静默跳过**、`BUILD SUCCESSFUL` 照常，测试数从 39 悄悄变成 38。**只有逐用例集合比对才能发现**
  （按数量报警最容易漏，因为总数量级在那里）。已修复，并把集合比对固化为 `tools/check_test_count.py`
  + CI 必跑步骤。**教训：任何「N 项单测全绿」的结论，必须同时确认 N == 实际执行的用例数。**
- **`grep` 的 `.` 是任意字符**：用 `Icons.Default.Description` 作模式去搜现有用法，匹配到的其实全是 `contentDescription = …`，据此误判「该图标已被使用」。检索代码请用 `grep -F`。
- **`adb shell` 侧的 `grep` 与引号转义不可靠**：`run-as … grep '"kind":"userInput"'` 因转义丢失而返回 0（假阴性），一度让人以为缓存里没有 `attachments`。**结论：跨 adb 做文本检索一律先把文件 pull 到本地再解析**（本次正是 pull 后才发现真实附件行）。

### 验证状态
- `gradle testDebugUnitTest assembleDebug` **BUILD SUCCESSFUL**；`tools/check_test_count.py` 核对 **声明 41 项 = 实际执行 41 项**（PureFunctions 24 + Vql 4 + ToolDiff 13），0 失败。
- `versionName 0.5.0-beta14` / `versionCode 24`。

## v0.5.0-beta13（2026-10-07）· 正确性缺陷修复（上传跨会话注入 / 握手超时 / 贴底回归）+ 触觉补漏

**里程碑：按《体验提升任务书 v2》档 A/B 落地四项——修掉一个数据正确性与隐私缺陷（上传跨会话附件注入）、一个可永久卡死的握手缺陷（三跳无超时）、一个 beta11/12 引入的贴底功能回归，并补齐两处遗漏触觉。全部改动为维护与缺陷修复性质，不含新增功能。**

> ⚠️ **验收状态：JVM 单测与构建全绿；真机验收已完成 A-2 / B-1 两项（含新增缺陷修复），B-3 与 A-1 的部分需人工复核** —— 见文末「真机验证记录（2026-10-07）」。真机验收全部通过前不打 tag、不推送。

### 修复
- **A-1 上传跨会话附件注入（数据正确性 + 隐私）**：会话 A 的在途上传完成后，文件会被塞进用户已切过去的会话 B 的附件条，用户可能在 B 里误发出去。修法三处：① 上传发起时捕获「发起会话」，`onProgress` 与完成回调统一用新抽出的纯函数 `AppViewModel.uploadBelongsTo` 校验归属，不匹配即丢弃回调；② `ConversationChannel.uploadAttachment` 改为返回可中止句柄 `UploadHandle`，内部以 `aborted` 标记短路一切在途回调（begin/chunk/commit 应答）与后续分片，且不再回写 `onResult`；③ `clearAttachments()` 终止在途上传（此前只清状态，在途上传会继续跑完）。
- **A-2 握手三跳无超时 → 可永久卡死**：`helloConversationV4` / `initializeConversationV4` / `subscribeConversationV4` 三处此前均未传 `timeoutMs`（`RpcChannel` 默认 `null` = 无限等待），连接健康但某个 201 应答丢失时会话页**永久**停在「握手中…」。三跳现分别传 4s / 4s / 5s；失败原因经 `handshakeFailureReason` 映射为可读中文（不再透出 `timeout after 4000ms` 等裸英文串），并新增**一次**自动重订（退避 1s）。重订与切会话由新增的握手 `generation` 代次守卫丢弃迟到应答（`isStaleReply`，沿用 `RelayClient.generation` 既有写法）；自动重订用尽后停在失败态，顶栏给出「重试」入口（`AppViewModel.retrySubscribe`）。
  - **阈值由 T0 真机实测校准**（原为 8s/8s/10s 占位）：6 次冷启动实测 hello 132–211ms、initialize 127–198ms、subscribe 127–186ms、ack→快照 99–141ms，整链路 512–740ms；取实测最大值的约 19 倍余量。采样脚本与统计见 `tools/t0_sample.sh`、`tools/t0_analyze.py`，原始样本 `_tmp/t0_samples.txt`。
- **B-1 贴底回归（beta11/12 引入）**：自动贴底的唯一触发器是 `LaunchedEffect(rows.size)`，而 beta11/12 的缓存预加载 + 快照 `replaceAll` 在**缓存行数等于快照行数**时 `rows.size` 完全不变 → 连残缺贴底也不跑（比 beta8 更严重）。修法：① `MainActivity` 用 `key(taskId)` 包住 `ConversationScreen`，listState 与翻页锚点不再跨会话复用；② `ConversationChannel` 新增可扩展形状的「快照已对齐」信号 `SnapshotAligned(sessionId, rowCount, atElapsedMs, seq)`；③ 进会话首帧与对齐完成时无条件定位（不带动画）；④ 流式跟随改为以「末行 rowId + 文本长度」为键（流式 `appendText` 只替换单行、size 不变）；⑤ 定位条件抽为纯函数 `ConversationScrollPolicy.shouldPinToLatest`。
- **B-1 追加修复：显式「回到底部」被翻页锚点恢复覆盖（真机验收中新发现）**：在列表**顶部**点「回到底部」时，翻页逻辑同时触发并把锚点记为「列表首行」，加载更早历史后锚点恢复把视口从中途拽回，**显式跳转被覆盖**、用户停在会话中段。修法：新增 `jumpingToBottom` 状态，跳转期间禁止翻页触发，跳转结束再丢弃可能残留的锚点。该缺陷同时存在于修复前版本（此前通过只是时序侥幸）。
- **B-1 判定模型改为「意图锁」**：原用「距末尾 ≤2 项」判断要不要贴底（几何距离），改为显式意图锁 `userScrolledAway`——只有用户**主动拖动**才置位（程序化滚动不产生 `DragInteraction`），拖回底部或点「回到底部」即复位。同时补一条 debug 级滚动决策日志（`ConvScreen: scroll决策 …`，release 被 R8 剥离），作为 B-1 类失效在真机上的唯一现场观测手段。
- **B-3 触觉补漏与失败时长档位**：补附件上传成/败触觉（经 `AttachmentFeedback` 信号桥接 VM→Compose，因上传完成是异步的）与会话内 `ApprovalCard` 按钮触觉（与 `ApprovalsTab` 对齐）。`flash()` 驻留时长由**显式类型参数** `FlashKind` 决定，取代原先「按字符串前缀猜」的白名单 —— 「附件上传失败」此前一直错落在 4s 档，现为 8s。

### 变更
- `ConversationChannel.Status.Failed` 新增 `retryable` 参数（默认 `false`，仅瞬态超时为 `true`），供上层决定是否退避重订。
- 失败类反馈（发送失败 / 应答失败 / 连接已断开 / 停止失败 / 模型切换失败 / 删除失败 / 附件上传失败）统一 8s；成功与普通提示 4s。

### 新增单测（声明 +10 项，全仓 29 → 39 项声明）
> ⚠️ **勘误（beta14 轮次发现）**：其中 `uploadBelongsToSession` 因一次编辑把注释与 `@Test` 挤到同一行、
> 注解落进行注释而**从未被执行**（JUnit 静默跳过，构建照样全绿）——本版实际执行为 38 项，非 39 项。
> 已在 beta14 轮次修复，并新增 `tools/check_test_count.py` + CI 断言防复发。
- `uploadBelongsToSession`：同会话 / 跨会话 / 发起端为空 / 当前端为空 四类归属判定。
- `flashDurationByKind`：失败 8s、提示 4s 的时长契约。
- `handshakeFailureReasonIsReadableChinese` / `isTimeoutErrorOnlyMatchesTimeout` / `staleReplyDetection`：可读文案、瞬态失败判定、迟到应答丢弃。
- `scrollPolicy*` 5 项：进会话定位（含「无缓存」与「已定位不重复」）/ 快照对齐（含「行数不变」失效路径）/ 增量仅在已定位且近底部时跟随 / 翻页中全部阻断 / 用户上翻全部阻断。

### 明确不做（沿用仓库既有决策）
- **A-3 订阅监听泄漏（103 EventDispose 零调用）**：`research/CONVERSATION-PROTOCOL.md:34` 仅一行表项、**无 payload 字段规格**，属协议逆向；必须先真机 + 桌面端在线实测确认服务端是否认 103，本次未做。
- **A-4 事件流缺口检测与自愈**：需改 `RpcChannel` 并发 buffer 策略（`DROP_OLDEST` → `SUSPEND`），任务书自标高危回归，且需真机复现「UI 长期落后」验证，本次未做。
- **档 C（C-1~C-12 体验与性能重构）**：按 `HANDOVER.md` 授权边界全部 `[需立项]`，未获用户拍板不得开工。

### 验证状态
- `gradle testDebugUnitTest assembleDebug` **BUILD SUCCESSFUL**（声明 39 项 / 实际执行 38 项，0 失败；缺失原因见上方勘误，已于 beta14 修复）。
- 安全核查：未引入 App 侧文件写路径（`HANDOVER §8` 红线）；UI 层 grep 断言无 `timeout after` / `bridge not ready` / `bridge re-established` 裸英文串；`proguard-rules.pro` 对 `ZLog` d/i/w 的 `-assumenosideeffects` 未变（release 仍剥离）。
- `versionName 0.5.0-beta13` / `versionCode 23`。

### 真机验证记录（2026-10-07 · 小米 15 Pro `9f6241b4`，Android 17 / HyperOS）

**环境提示**：本机实测系统为 **Android 17**，而 `HANDOVER` 记录为 Android 15 —— 跨两个大版本。另：设备接入时需把 USB 用途切到「传输文件」才会暴露 ADB 接口（否则 Windows 只枚举为通用 `USBDevice`、adb 看不到设备）。

| 项 | 结果 | 证据 |
|---|---|---|
| 握手正常路径回归（A-2 改动未破坏） | ✅ PASS | 冷启动三跳全成功 + `subscribed sub=…-N` + 快照落地；6 次采样整链路 512–740ms |
| **A-2** 弱网触发超时 | ✅ PASS | 飞行模式 + 切会话 → `rpc timeout … helloConversationV4 after=4000ms`，**4.5s** 出可读失败（优于「10s 内」要求） |
| **A-2** 可读中文原因 | ✅ PASS | 状态栏 `异常：握手超时，请检查网络后重试`（无裸英文） |
| **A-2** 一次自动重订 | ✅ PASS | `握手失败，1000ms 后自动重订（第 1 次）` → `自动重订 session=…`（新 id，代次递增） |
| **A-2** 重订上限与人工入口 | ✅ PASS | 重订后仍超时 → `握手自动重订已用尽（1 次），停在失败态等用户重试`，顶栏出现「重试」 |
| **A-2** 网络恢复自动对齐 | ✅ PASS | 关飞行模式 → `ws open` → `auth_ack(matched)` → `subscribed sub=…-N` → `snapshot 60 行`，无需人工干预 |
| **B-1** 进会话定位最新行（含「缓存行数==快照行数」失效路径） | ✅ PASS | 进会话后「回到底部」胶囊不可见（= 已在底部）；该会话缓存 60 行、快照 60 行，正是旧触发器完全不跑的路径 |
| **B-1** 上翻不被强拉 | ✅ PASS | 上翻后胶囊出现；流式 delta 持续到达 15s 后胶囊仍在；诊断日志显示该期间 `away=true` → 增量跟随被正确阻断 |
| **B-1** A 停顶部 → 进 B | ✅ PASS | A 停在顶部（胶囊可见）时切到 B → B 的胶囊不可见（`key(taskId)` 未复用 A 的偏移） |
| **B-1** 点「回到底部」到达且不跳变 | ✅ PASS（含新增修复） | 修复前诊断日志：跳转瞬间 `pagination=true` → 视口被锚点恢复拽回中段；修复后 `jumping=true` 期间 `pagination=false`、`nearBottom=true`，12s 后仍保持底部 |
| 单测/构建 | ✅ PASS（含勘误） | 声明 39 项、实际执行 38 项全绿（1 项注解被注释掉未执行，beta14 轮次发现并修复）、BUILD SUCCESSFUL |

**待人工复核（自动化不可靠，见下）**
- **B-3 触觉**：附件上传成/败、会话内审批按钮的震动反馈（触觉无外部可读接口，只能人工感知）。
- **B-3 失败横幅 8s 停留**：时长契约已由 `flashDurationByKind` 单测锁定（Failure=8000/Info=4000）、调用点经 `FlashKind.Failure` 编译期检查；「肉眼停留 8s」建议人工确认一次。
- **A-1 双会话切换不误注入**：需要真实选择本地文件（SAF 文件选择器），自动化脆弱；归属判定 `uploadBelongsTo` 已单测覆盖四类场景，但端到端建议人工走一遍。

**顺带发现的既有缺陷（非本次改动引入，已登记 HANDOVER 待办）**
- **重连退避在网络恢复时不被重置**：实测退避序列 3s→6s→12s→24s→48s（封顶 48s）。约 90 秒的飞行模式往返后，网络恢复时仍要等 48s 的退避到期才重连（实测恢复耗时 ~47s），而 `HANDOVER` 的基线是 `<9s`。直接影响 P0-2 观察清单第 4 项「飞行模式往返自动恢复（预期 <9s）」。

**验证方法论教训（写下来避免重犯）**
- 用「在整份 UI dump 里搜字符串」判断界面状态会**假阳性**：本会话的消息正文与 toolCall 的 `inputText`（即我们自己命令的源码）都会被渲染进会话行，里面自然包含「回到底部」「content-desc="回到底部"」等字面量。本次因此**误判两次**（一度以为存在两个不存在的 bug），直到改用「节点 text **整体**等于目标串」的判据才可靠。该方法已固化为 `tools/ui_check.py`。

### 新增工具
- `tools/t0_sample.sh` / `tools/t0_analyze.py`：T0 握手打点采样与按请求 id 精确配对统计。
- `tools/ui_check.py`：B-1 真机断言助手（用整体相等判据规避自污染，见上）。

## v0.5.0-beta12（2026-10-05）· 会话消息流离线持久化（点进会话瞬间秒开）

**里程碑：把离线缓存从「首页会话列表」延伸到「单个会话消息流」—— 点击任意会话卡片，首帧立即呈现该会话最近 200 行历史消息，无需等待订阅握手与快照回包；网络快照到达后由 `replaceAll` 平滑对齐权威状态。**

### 新增
- **单会话消息流本地缓存（`SessionCacheStore.loadRows/saveRows`）**：`ConversationRow` 全字段支持 `@Serializable` 编解码；按 `sessionId` 独立文件存储（文件名做安全字符转义），保留最近 **200 行**防止体积膨胀；沿用原子文件替换写入。
- **会话详情首帧秒开**：`AppViewModel.subscribeConversation` 在发起订阅前先同步读取本地缓存并 `rowStore.replaceAll(cached)`，点击会话卡片即刻渲染历史对话。
- **行变更自动持久化钩子**：`ConversationChannel.onRowsUpdated` 在快照到达与历史翻页（`loadEarlier`）时回调，由 `AppViewModel` 异步写回最新消息行；订阅时不再强制 `clear()`，改由快照 `replaceAll` 平滑对齐。
- **独立回归单测**：补充 `conversationRowSerializationAndRowStoreSnapshot`，覆盖 `ConversationRow` 序列化回环与 `RowStore.snapshot()`，全仓单测增至 26 项全绿。

### 变更与验证状态
- **真机端到端实测 PASS（小米 15 Pro / HyperOS）**：点进会话首帧立即呈现本地缓存历史消息行；网络快照到达后无缝对齐，无闪烁或重复行。
- 26 项 JVM 单元测试与 Release 构建全绿。
- `versionName 0.5.0-beta12` / `versionCode 22`。

### 📌 项目状态（2026-10-05 用户决策）
- **Sprint 0–6 全部完成并真机验收；后续全部开发计划取消**（含 Sprint 7 生物识别/凭据生命周期、自建中继 + E2EE，以及 P2-3 余项）。
- 仓库转入「维护 + 3 天 P0-2 日常观察」阶段；观察通过即达 v1.0 判停线，可直接发布 v1.0。
- 详见 `HANDOVER.md` 顶部状态横幅与 §6.0 待办总览。**此后不再规划新功能，如需演进须重新拍板立项。**

## v0.5.0-beta11（2026-10-05）· 会话离线持久化与冷启动秒开（Offline First）

**里程碑：实现 Sprint 5 最终项闭环 —— 引入 `SessionCacheStore` 本地持久化缓存，冷启动首帧直接渲染上一轮 25+ 会话（0ms 瞬间秒开），彻底杜绝白屏与等待握手回包的滞后感；后台静默增量刷新并原子写回。**

### 新增
- **会话离线持久化缓存（`SessionCacheStore.kt`）**：纯客户端零外部重依赖，采用原子文件替换写入机制（`.tmp` → `renameTo`）防止闪退损坏；冷启动 `AppViewModel` 初始化时立即读取本地缓存，首帧直接呈现完整会话列表与运行状态。
- **会话变更动态同步与增量写回**：`SessionItem` 全面支持 `@Serializable` 编解码；`bootstrap-response` 网络回包、新建会话（前插）与删除会话（移除）均自动异步写回本地缓存。
- **独立序列化回环单测**：补充 `sessionItemSerializationRoundtrip` 回归测试，全仓纯函数单测增至 25 项全绿。

### 变更与验证状态
- **真机端到端实测 PASS（小米 15 Pro / HyperOS）**：
  1. 模拟强制停止进程（`am force-stop`）后重新拉起冷启动，首屏瞬间渲染 25 条历史会话，日志捕获 `SessionCache: 成功持久化 25 条会话缓存`。
  2. 25 项 JVM 单元测试与 Release 构建全绿。
- `versionName 0.5.0-beta11` / `versionCode 21`。

## v0.5.0-beta10（2026-10-05）· 配对链接 Deep Link 唤起与交互触觉反馈

**里程碑：实现 Sprint 5 核心扩展 —— 支持通过 `zcode://pair` 与官方 `https://zcode.z.ai/remote` 链接一键唤起 App 自动配对；全面接入 Android 系统级触觉反馈（Haptic Feedback），提供极佳的操作确认与机械回馈感。**

### 新增
- **配对链接 VIEW Deep Link 一键唤起（Sprint 5 核心）**：`AndroidManifest.xml` 与 `MainActivity` 注册 `zcode://pair` 以及官方 `https://zcode.z.ai/remote` / `https://zcode.chatglm.site/remote` 的 VIEW 意图过滤器，点击外部链接直接拉起 App 并由 `QrParser` 解析参数完成无感自动配对，免除扫码或复制粘贴。
- **系统级触觉震动反馈（Haptic Feedback）**：
  - 审批决议（允许一次 / 总是允许 / 拒绝）触发长震动确认（`HapticFeedbackType.LongPress`）；
  - 消息发送与会话中断触发重触感反馈；
  - 代码 Diff 差异一键复制触发触觉确认；
  - 点击常用快捷指令胶囊（Action Chips）与「回到底部」悬浮按钮触发轻触感回馈（`HapticFeedbackType.TextHandleMove`）。

### 变更与验证状态
- **真机端到端实测 PASS（小米 15 Pro / HyperOS）**：
  1. 模拟触发 `zcode://pair` Deep Link，App 成功捕获意图并完成设备连接与快照订阅。
  2. 关键交互均伴随细腻舒适的系统振动反馈。
  3. 24 项 JVM 单元测试与 Release 构建全绿。
- `versionName 0.5.0-beta10` / `versionCode 20`。

## v0.5.0-beta9（2026-10-05）· 单轮 Turn 变更文件聚合面板（Turn Diff Summary）

**里程碑：实现 Sprint 3 第三步收官 —— 会话流内单轮 Turn 文件变更一站式聚合 Review（对标 GitHub PR Files Changed），彻底免除逐个翻找与展开工具行的繁琐操作。**

### 新增
- **单轮 Turn 变更聚合算法（`TurnChanges.kt`）**：纯客户端零协议零 RPC，按会话 Turn 边界自动归集该轮执行的所有 Edit / Write 工具行，聚合统计修改文件总数、总新增行、总删除行，并挂载于该轮最后一个写操作之后。配齐独立纯函数单测（24 项单测全绿）。
- **本轮变更 Review 卡片组件（`TurnChangesCard.kt`）**：会话内自动呈现 `📦 本轮变更 · 共 N 个文件 (+A −B) [查看变更]`，支持一键平铺该轮修改的全部文件 DiffBlock，快速对比各文件红绿差异与一键复制 unified diff。

### 变更与验证状态
- **真机端到端实测 PASS（小米 15 Pro / HyperOS）**：
  1. 动态写操作执行后，会话流精准挂载 `📦 本轮变更` 卡片，准确统计并高亮多文件变更摘要。
  2. 点击「查看变更」平滑展开多文件差异比对与复制功能。
  3. 24 项 JVM 单元测试与 Release 构建全绿。
- `versionName 0.5.0-beta9` / `versionCode 19`。

## v0.5.0-beta8（2026-10-05）· 系统通知栏 RemoteInput 内联快捷回复与 Share Sheet 接收

**里程碑：打通 Android 原生核心能力 —— 支持在系统通知栏直接拉开打字回复并一键提交（RemoteInput）；支持接收系统级分享（Share Sheet）的外部文本与文件附件，无缝填入输入草稿。**

### 新增
- **系统通知栏 RemoteInput 内联回复（Sprint 4 核心）**：`ElicitationNotifier` 为支持自由文本应答的提问与交互装配 `android.app.RemoteInput` 动作；用户可在下拉通知栏直接打字回复并通过广播一键发送至桌面端，全程无需解锁进入 App。
- **系统级 Share Sheet 分享接入（Sprint 5 核心）**：`AndroidManifest.xml` 与 `MainActivity` 注册并处理 `android.intent.action.SEND` 意图，支持接收外部应用分享的文本（自动填充为输入草稿）与文件附件（自动解析加入待发附件）。

### 变更与验证状态
- **真机端到端实测 PASS（小米 15 Pro / HyperOS）**：
  1. 模拟系统文本分享意图（`android.intent.action.SEND`），会话输入框精准捕获并填充分享内容。
  2. 注入表单交互广播，通知栏成功弹出携带 `RemoteInput` 的高优先级常驻通知。
  3. 23 项 JVM 单元测试与构建全绿。
- `versionName 0.5.0-beta8` / `versionCode 18`。

## v0.5.0-beta7（2026-10-05）· 会话流智能贴底与输入栏快捷指令胶囊

**里程碑：解决真机实测反馈的高频交互痛点 —— 会话流上翻浏览不再被新行强拉贴底、悬浮「回到底部」一键平滑定位；新增输入栏快捷指令胶囊栏，大幅降低移动端打字成本。**

### 新增
- **会话流智能贴底（Smart Auto-Scroll）**：基于 `listState.layoutInfo` 动态计算 `isNearBottom` 视口状态 —— 仅当用户原本处于底部附近时随新行平滑贴底；当用户主动上翻查阅历史记录、思考过程或代码 Diff 时，彻底避免被新帧强制拉回底部打断阅读。
- **悬浮「回到底部」胶囊按钮**：当用户处于上翻阅读状态且会话有内容时，右下角优雅弹出带阴影与动画的悬浮胶囊按钮「↓ 回到底部」；点击即平滑滚动到底部并自动隐藏。
- **常用快捷指令胶囊栏（Action Chips）**：输入栏与附件条之间新增横向滚动快捷栏（「继续」、「运行测试验证」、「修复该问题」、「检查 Git 状态」、「整理并提交」），单指轻触即可快速填入输入框或追加到草稿，大幅提升移动端交互效率。

### 变更与验证状态
- **真机端到端实测 PASS（小米 15 Pro / HyperOS）**：
  1. 上翻查阅历史时，新消息到达保持当前阅读视口不被打断；悬浮「回到底部」按钮准时显示，点击平滑滚动并自动消隐。
  2. 点击「继续」快捷胶囊，输入框瞬间填入指令并点亮发送按钮，端到端交互链路正常。
  3. 23 项 JVM 单元测试与构建全绿。
- `versionName 0.5.0-beta7` / `versionCode 17`。

## v0.5.0-beta6（2026-10-05）· P0缺陷结清、最近文件只读预览与工具调用Diff视图

**里程碑：完成外部审查计划（HANDOVER §6.1）的 Sprint 0–3 —— 修复三个实证级 P0 缺陷、
连接生命周期与界面解耦、执行模式默认安全化、工具调用渲染红绿 diff；
新增 Sprint 3「最近文件」面板与远程只读预览全链路（真机端到端验收全通）；
补齐 Sprint 6 回归网（23 项 JVM 测试全绿 + CI + 构建脚本可移植化）。**

### 新增
- **「最近文件」面板与只读预览（Sprint 3 第二步·剧本 B）**：`relay/SessionFiles.kt` 从会话流工具调用行（Edit/Write/Read/MultiEdit）纯客户端抽取文件路径去重，最近操作优先。会话页顶栏新增 `📁` 入口（动态展示文件数），点开为 `ModalBottomSheet` 列表（短文件名 + 完整路径 + 工具标签），点击条目通过 `file.readTextFile` RPC 读取远程文件正文（`~` 路径经 `resolvePath` 展开），等宽代码块展示、横向滚动、20k 截断，纯只读无写路径。4 项纯函数单测覆盖。
- **VQL 金标准对拍测试（Sprint 6）**：新增 `tools/gen_vql_fixtures.py`（Python 侧独立编解码实现）生成 20 个共享向量 + 2 个多值流（`app/src/test/resources/vql_fixtures.json`），Kotlin `Vql.kt` 对每个向量做「编码字节级一致 + 解码语义一致 + 偏移推进一致」双向对拍——协议上游一变，CI 先红。
- **纯函数单测（Sprint 6）**：`ToolDiffParser`/Myers diff（含交换型修改回溯、空文件 `"".lines()` 规范化、超 4000 行降级、重建校验）、`ConversationRow.from`（字段映射 + raw 整包保留 + 缺 rowId/kind 拒解）、`ApprovalOption.sortKey`（渲染顺序全分支）、`splitModelValue`（$variant 剥离 + 无斜杠回退）、`PairedDevice.relayWsUrl`（线路分派）。23 项测试全绿；QrParser 依赖 `android.net.Uri` 不入 JVM 测试（真机验收覆盖）。
- **GitHub Actions CI（Sprint 6）**：`.github/workflows/ci.yml` —— Ubuntu runner 上跑 `gen_vql_fixtures.py → testDebugUnitTest → assembleDebug`，纯 JVM 无需真机。
- **执行模式选择器（Sprint 2 / P0-B）**：新建会话弹窗新增「执行模式」选择（规划 plan / 构建 build / 全自动 yolo），**默认 build**；选 yolo 时显示醒目风险文案。会话页顶栏新增执行模式胶囊，常驻显示当前模式（yolo 红底警示），点击切换（走 `setMode` RPC，菜单内如实提示「仅对下一轮对话生效」——协议语义见 HANDOVER §5.4）。
- **工具调用 diff 视图（Sprint 3 第一步）**：Edit / Write / MultiEdit 工具行从 `inputText` 纯客户端解析 `old_string` / `new_string`（`ui/components/DiffView.kt`），渲染行号红绿 diff（行级 Myers 算法、长段未变更自动折叠、横向滚动、一键复制 unified 文本）；折叠态摘要直接显示「文件名 +N −M」。零 RPC、零协议改动；超 4000 行自动降级整段替换显示，不阻塞审批渲染。
- **连接终态常驻通知（Sprint 2 / A 组）**：KICKED（控制权被接管）/ AUTH_FAILED（配对失效）/ PROTOCOL_MISMATCH（协议升级）三种终态由「仅 App 内横幅」升级为系统常驻通知（`notify/TerminalNotifier.kt`，点按打开 App，恢复正常连接自动撤除）——手机在兜里不再静默失去审批能力。
- **网络感知重连（Sprint 1 / B 组）**：重连闸门 `relay/NetworkGate.kt` 注入 `ConnectivityManager.NetworkCallback`——飞行模式/切 Wi-Fi 期间不再空转退避，网络恢复即刻重连（原实现最长多等一轮 48s）；退避加 jitter。
- **连接前台服务真正启用（Sprint 1 / P0-A）**：`ConnectionService` 从占位死代码变为真实前台服务（LOW 重要性常驻通知保进程），连接栈迁入进程级 `ConnectionScope` 单例——锁屏/切后台/划掉界面后连接与审批通知继续存活，不再随 Activity 生灭。服务类型改 `specialUse`（Android 15 对 dataSync 型有 24h 内累计 6h 硬超时，specialUse 不受限）并实现 `onTimeout` 兜底。

### 修复
- **diff 空行规范化（Sprint 6 测试发现的真实缺陷）**：`diffLines` 入口把 `[""]`（Kotlin `"".lines()` 的产物，即空文件/纯插入场景）规范化为空列表——修复 Write 全新文件时 diff 不再是纯新增、`removed` 虚高的问题。
- **新建会话不再默认 YOLO（Sprint 2 / P0-B）**：`createNewSession` 硬编码的 `mode = "yolo"`（AppViewModel）与 `?: "yolo"` 兜底（ConversationChannel）均改为参数化 / `?: "build"`——此前手机端创建的所有会话全部免审批（既是安全洞，也让锁屏审批对这些会话永不触发）。成因追溯：v0.5.0-beta2 为绕 Gemini 校验显式下发 `mode:"yolo"`，后续修了 `thought` 漏了 `mode`。
- **入站帧不再静默丢弃（Sprint 1 / B 组）**：`RelayClient` 入站流由 `extraBufferCapacity=64 + DROP_OLDEST`（长流式输出突发时静默丢最旧帧、会话行悄悄错位）改为 Channel 队列 + 单泵协程顺序 emit（缓冲 512、挂起而非丢弃、严格保序）。
- **自报版本号真实化（Sprint 1 / B 组）**：`RelayClient` 构造改传 `BuildConfig.VERSION_NAME`——此前永远向中继自报 0.1.0。
- **心跳与重连协程化（Sprint 1 / B 组）**：裸 `Thread.sleep` 守护线程并入协程作用域统一治理，`close()` 时全部收敛取消。

### 安全加固
- **release 日志全面治理（Sprint 1 / P0-C）**：新增 `util/ZLog` 统一日志门（debug 全量、release 静默），relay/rpc/storage/UI 全部 30+ 处 `android.util.Log` 调用迁移完毕；`RelayClient` 的 HMAC proof 与 deviceSid 日志连 debug 也不再输出 proof 本体。直接修复「release 包把会话正文、审批详情与握手凭据明文写进 logcat」的凭据红线违规（HANDOVER §8）。
- **release 开启 R8 + 资源收缩**：补建 `proguard-rules.pro`（`assumenosideeffects` 编译期剥离 `Log.v/d/i/w` 调用含字符串参数，与 ZLog 门控双保险）；自家代码本轮保守 keep（混淆改名待真机冒烟后放开）。

### 变更与验证状态
- **《八周计划》评审吸收（2026-10-05）**：经甄别并入 9 项增量——RPC 能力探测系统方法与剧本 B「最近文件」面板并入 Sprint 3 第二步；分片重组/水位 ack 交互审查与迟到 onFailure 竞态立为 P1 待办；「文件写能力永不提供」写入 §8 红线；v1.0 判停线入里程碑定义。随即落地：修复检查更新版本比较 bug（字符串不等→语义化比较 + 8 项单测，远端旧版本曾误报"有新版本"）；删除三个零引用依赖（navigation-compose/datastore-preferences/security-crypto，import 级验证）与 CredentialStore 死代码；HANDOVER 文档卫生（P1-4 重复标题、§4 版本序列滞后）。不建议吸收部分（W4 epoch 整改/W5 拆文档/W8.4 AGP 升级等）已留档 HANDOVER §6.1。
- **真机验收（2026-10-05，小米 15 Pro / Android 17）**：连接链路 matched、P0-A 前台服务（specialUse 位实证）、② 断网感知重连事件链 <9s、④ 审批推送端到端（通知→审批卡→允许一次→RPC 放行→乐观消解）、⑤ 模式胶囊常驻+切换、⑥ KICKED 终态通知、P0-C debug 日志——**全部 PASS**（明细见 HANDOVER §6.1 验收表）。新增 **Sprint 3 真机端到端验收全通**：
  - **「最近文件」面板与只读预览（剧本 B）PASS**：动态捕获会话流工具调用行文件（实测 8 个），顶栏 `📁 8` 实时渲染；ModalBottomSheet 列表点选通过 `file.readTextFile` 实时获取并等宽渲染代码正文，横向滚动与「← 文件列表」/「关闭」交互正常。
  - **工具调用 Diff 视图直接实证 PASS**：实测会话流中的真实 `Edit` 调用行（`zd_test.txt`），`ToolDiffParser` 解析出 `+1 −1`，真机直接渲染折叠摘要 `📄 F:/AI/Zcode/zd_test.txt +1 −1`，展开后行级 Myers 红绿 diff 与 `⧉ 复制` unified 文本功能验证通过。
  - **审批广播与通知栏复验 PASS**：`DebugApprovalReceiver` 广播注入双审批测试项，系统通知栏 `approvals` 渠道 HIGH（importance=4）横幅与 actions 按钮正常，`DEBUG_APPROVAL_CLEAR` 正常撤销；`ApprovalsTab` 集中看板空状态展示正常。
  release logcat 为静态已验跳过；划掉任务 30min 观察归入 P0-2。
- **真机验收驱动的修复**：① `sessionModeOverride` 初始化顺序 NPE（启动即崩）；② 补 `ACCESS_NETWORK_STATE` 权限（缺失时 NetworkGate 静默失效）；③ NetworkGate 重构为常驻网络监控（OkHttp 断网回调延迟到恢复才冒出 + 退避烧满导致的"假死"，onLost 立即断 socket 重连 / onAvailable 即刻放行）；④ 执行模式与订阅模式撞名修正（ModeChip 曾错显 snapshot，改用 readWorkspaceState 的 settings.mode.current）；⑤ RelayClient 重连防重入守卫与跳过原因日志。
- **build.sh 可移植化（Sprint 6）**：仓库根改由 `${BASH_SOURCE[0]}` 推导、JDK/SDK/Gradle 依次回退到「仓库 toolchain/ → 环境变量 → PATH」——clone 到任意目录、任意机器 `./build.sh` 直接可构建（此前必须放在 `F:/AI/Zcode/zcode-remote-app` 才行）。
- 文档：HANDOVER §4 / §6.0 失效结论勘误（前台服务死代码、文件浏览「无落点」被实测推翻、乐观消解已实现）；新增 §6.1 计划评审结论与 Sprint 0–7 排期 + 真机验收结果表 + 接手必读环境注意事项（注入 sid 教训 / Clash fake-ip 掐 TLS / svc 断网验收法）；§8 新增「协议做不到类结论须标注静态分析/实测依据」红线。
- 验收状态：debug/release 构建 BUILD SUCCESSFUL；23 项 JVM 测试全绿；release dex 日志字符串剥离复检通过；**UI 与链路验收已真机完成（见上）**，划掉任务 30min 日常观察归入 P0-2。

## v0.5.0-beta5（2026-10-01）· 会话内切换模型支持选档位 + 修复新会话草稿残留

**里程碑：把思考档位选择延伸到会话内切换模型；修复首轮指令发送后输入框仍残留同一段文字**

### 新增（会话内切换模型可选思考档位）
- **会话页顶栏模型胶囊改为两级菜单（`ConversationScreen`）**：
  - 第一级列出可用模型，带推理档位的模型右侧显示 `▸` 提示；
  - 点选后进入第二级，列出该模型的合法档位（中文：`关闭/低/中/高/最高`），选择即以「模型 + 档位」一起下发；
  - 无推理档位的模型保持一级点选、直接切换；第二级顶部提供 `← 模型名` 返回项；
  - 切换成功后浮层提示带上档位（如「模型已切换为 deepseek/deepseek-v4.1-flash · high」）。

### 修复（新建会话后输入框残留首轮指令）
- **首轮指令重复出现在输入框**：
  - 根因：`createNewSession` 成功后会把首轮指令回填到输入草稿（原本作为网络失败兜底），但 `firstInput` 实际已随 `createSession` 成功发送，
    导致进入会话后输入框仍留着同一段文字，看起来像「没发出去」；
  - 修复：创建成功后清空 `promptDraft` 与待发附件，输入框保持空白。

- `versionName 0.5.0-beta5` / `versionCode 15`。

## v0.5.0-beta4（2026-10-01）· 新建会话可手动选择思考档位

**里程碑：把 PC 端权威的模型思考档位表搬进新建会话弹窗，支持按需选择推理强度**

### 新增（思考档位选择器）
- **新建会话弹窗新增「思考档位」选择器（`CreateSessionDialog`）**：
  - 数据源为 `model-selection::getView` 解析出的各模型合法档位表（如 deepseek `关闭/低/高/最高`、gemini `关闭/中`）；
  - 切换模型时自动重置档位选择，档位随所选模型动态刷新；无推理档位的模型不展示该选择器；
  - 档位值做中文映射（`disabled→关闭`、`low→低`、`medium/enabled→中`、`high→高`、`max/xhigh→最高`），并提示「档位越高推理越深入，但耗时与消耗也更大」；
  - 未手动选择时沿用自动策略（取首个非 `disabled` 档位），手动指定模型 ID 时档位交回自动挑选；
  - 选中的档位随 `createSession` 的 `config.thought` 下发，避免再出现非法档位导致的创建失败。

- `versionName 0.5.0-beta4` / `versionCode 14`。

## v0.5.0-beta3（2026-10-01）· 修复推理模型思考档位校验失败（Reasoning level is required）

**里程碑：彻底解决新建会话/切换模型时因思考档位（reasoning level）非法导致的「Model creation failed」**

### 修复（推理模型思考档位必须按模型真实合法值下发）
- **根因定位（真机 + 官方 Host 源码交叉实证）**：
  - 官方 registry 对带推理能力的模型强制校验 `modelSelection.options.reasoningLevel`，其值必须落在该模型 `config.optionSpecs.reasoningLevel.values` 内；
  - 旧实现硬编码下发 `thought: "enabled"`，而该值仅对 Gemini / Claude 等部分模型合法；
  - DeepSeek 系列合法值为 `[disabled, low, high, max]`、GLM 为 `[low, high, max]`，`"enabled"` 直接触发
    `Reasoning level is required for <provider>/<model>` → 会话创建即失败（task_status=error 并归档），
    手机端表现为停在 0 行 draft 且无任何提示。
- **修复实现**：
  - 新增 `model-selection::getView` 调用（远程桥实测可达），解析出**每个模型的合法思考档位表**并缓存；
  - 新建会话与「会话内切换模型」均改为按所选模型自己的合法档位下发（优先启用推理：取首个非 `disabled` 档位；
    例如 deepseek→`low`、gemini→`enabled`、GLM→`low`）；
  - 拿不到档位信息时**不下发** `thought`，交由 PC 端按模型默认档位决定，避免再次误发非法值；
  - `createSession` 日志补充 `thought` / `mode` 输出，便于后续排查。

- `versionName 0.5.0-beta3` / `versionCode 13`。

## v0.5.0-beta2（2026-10-01）· 修复新建会话推理模型校验失败与审批点击会话串台问题

**里程碑：修复新建会话带模型时校验失败变成 draft/error 的缺陷，彻底解决待办审批跨会话点击无反应问题**

### 修复（核心交互稳定性）
- **修复审批与表单交互应答会话 ID 串台（`ConversationChannel.kt`）**：
  - 根因：原 `resolve` 优先使用了当前打开的 `sessionId`，当用户在待办 Tab 或切换到新会话后点击其他会话的审批时，将错误的 session 传给服务端，导致服务端判定 `no pending interaction` 静默忽略，且界面无反馈；
  - 修复：严格优先绑定 `approval.sessionId` / `el.sessionId`，杜绝跨会话串台；当未进入具体会话时，自动基于当前活动工作区构建发送目标，确保在任何 Tab 点击审批均能成功提交；
  - **接入乐观消除与即时反馈**：点击允许/拒绝瞬间卡片立即消除并同步系统通知栏，失败自动回滚并弹出原因，待办页与会话页均能实时显示操作反馈。
- **修复新建会话推理模型（如 Gemini）校验失败报错（`ConversationChannel.kt`）**：
  - 根因：官方 Host 对 Gemini 等带有推理能力的模型硬性校验必须提供 reasoning level，此前只传了 `provider` 和 `model`，服务端抛出 `Reasoning level is required`，导致首轮输入未执行、会话刚创建就直接失败进入 draft/error 态；
  - 修复：显式提供 `thought: "enabled"` 与 `mode: "yolo"`，并预填指令草稿，首轮会话稳定启动推进。
- **优化会话空状态与发送按钮常驻视觉引导（`ConversationScreen.kt`）**：
  - 修复空会话时死板显示“正在同步会话内容…”的问题，草稿态优雅展示“新会话已就绪，请输入指令开始”；
  - 输入栏右侧常驻发送图标占位（未输入时浅灰禁用，输入文字立即高亮激活），清晰指示发送入口。

- `versionName 0.5.0-beta2` / `versionCode 12`。

## v0.5.0-beta1（2026-10-01）· 移动端前端架构重构与官方 ZCode 设计系统全面升级

**里程碑：重构为底部 3-Tab 现代移动端架构，移植官方 ZCode 设计系统规范，集中式待办看板与独立设置中心**

### 新增（架构重构、新建会话与会话内动态切换模型）
- **会话内实时动态切换模型（In-Session Model Switching）**：
  - 基于官方 V4 原生命令信封机制（`zcode-agent::sendConversationCommandV4`，`type = "switchModelConfig"`），带 `baseRevision` CAS 防冲突校验；
  - 沉浸式会话详情页顶部 AppBar 新增**模型状态胶囊（如 `gemini-3.8-flash-high ▾`）**，点击可展开切换当前工作区可用模型列表；
  - 切换成功后服务端实时广播 `state.updated` 增量帧更新本地会话快照，下一轮对话指令直接以新模型驱动推理与执行。
- **支持移动端远程拉取桌面端模型目录并自主选择（Remote Model Catalog Fetching）**：
  - **核心突破**：通过远程桥必开的 `system::info` 获取真实桌面用户主目录（`homedir`），结合 `file::readTextFile` 直接读取 `~/.zcode/v2/provider_config.json`，彻底攻克远程 RPC 作用域限制，实机成功解析全部可用模型目录（涵盖 commandcode、Gemini、DeepSeek 等 7 个配置模型）；
  - **三路模型数据保障**：结合配置文件直读、`WorkspaceConfigChannel` 配置流订阅与实时会话快照提取，保证任何情况下均有模型数据显示；
  - **支持手动指定模型 ID**：新建会话弹窗支持手动输入任意模型 ID（如 `deepseek-r1` / `glm-4.5` / `claude-3-5-sonnet`）显式指定，留空则智能跟随 PC 默认。
- **支持移动端原生发起新会话（New Session）**：
- **底部 3-Tab 移动端架构（对标 GitHub Mobile / Claude Mobile）**：
  - 彻底淘汰此前单页垂直杂乱堆叠的形式，建立清晰的 3 大专属视窗（`MainActivity.kt`）：
    1. **会话 (SessionsTab)**：纯粹的会话流与工作区列表，顶部紧凑设备指示，支持标题/路径搜索与状态快速筛选；
    2. **待办 (ApprovalsTab)**：专属集中式审批与表单问答看板，底部导航栏实时待办徽章（Badge），卡片展示等宽命令预览与倒计时进度条；
    3. **设置 (SettingsTab)**：独立的设备管理与控制中心，彻底剥离所有低频配置项。
- **全局物理返回键拦截（`BackHandler`）**：
  - 在会话详情页、扫码页、保活教程页中拦截系统侧滑返回手势，平滑退回主屏列表，彻底杜绝手势误杀退出的问题。
- **状态持久化**：
  - 使用 `rememberSaveable` 保持 Tab 状态与会话状态，屏幕旋转或分屏时不再重置。

### 优化（对标官方 ZCode 工业级设计规范）
- **官方色彩体系与微边框（`ui/theme/Theme.kt`）**：
  - 完整接入官方 `theme-zai-dark`（`#161616`）与 `theme-zai-light`（`#F8F8FA`）配色体系；
  - 引入官方标准的 15% 微边框（Hairline Border）替代粗糙厚重阴影，视觉层次通透分明；
  - 引入官方 Trajectory 四色轨迹语义：用户消息蓝、助手青、思考紫、工具琥珀橙。
- **全面淘汰 Emoji，接入 Material 矢量图标**：
  - 移除原界面中充斥的 `⚠️`、`🔧`、`🧠`、`⏳`、`📎`、`🎤`、`✕` 等 Emoji，全部替换为规范的 Material 矢量图标，统一 1.5dp 线宽。
- **官方同款深度思考组件（`ReasoningBlock`）**：
  - 采用紫色思考轨迹胶囊 + `AnimatedVisibility` 平滑展开高度动画 + 官方标志性左侧纵向弱导向竖线，支持一键复制。
- **官方紧凑工具卡片（`ToolCallCard`）**：
  - 紧凑工具类型徽标 + 成功/失败/运行中状态徽标，支持终端框格式化展开。
- **代码块与 Markdown 渲染优化（`MarkdownView.kt`）**：
  - 修复代码块浅色模式下写死纯黑背景的问题，自动适配浅灰色/深灰色底色；
  - 修复引用块（Quote）左侧竖线高度无法撑满文本的问题；
  - 增加代码一键复制的成功绿色勾选状态反馈。
- **全面屏边缘防遮挡（Edge-to-Edge）**：
  - 补全 `navigationBarsPadding`，手势导航横条不再遮挡输入框与底部导航。

- `versionName 0.5.0-beta1` / `versionCode 11`。

## v0.4.0-beta6（2026-09-30）· 会话排版对齐桌面版 + 设置内版本展示与更新闭环

**里程碑：全新 Markdown 富文本渲染、深度思考折叠美化、状态全中文，以及设置内版本信息与检查更新闭环**

### 优化（会话排版彻底对齐桌面版体验）
- **纯 Compose 原生 Markdown 富文本渲染**（`ui/components/MarkdownView.kt`）：
  - 助手消息不再是简陋的纯文本或受限于 300dp 狭窄细条，放宽至宽幅卡片布局，排版彻底对齐桌面版；
  - 完整解析并渲染**各级标题（#~####）**、**粗体（**bold**）**、*斜体*、`行内代码`与~~删除线~~；
  - **代码块卡片**：深色背景、顶部语言标签、横向平滑滚动（`horizontalScroll`）、等宽字体，并配备**一键复制代码到剪贴板**功能（带 Toast 提示）；
  - **列表与引用**：无序列表圆点与有序列表数字悬挂缩进；引用块左侧带主色强调竖条与柔和浅色背景。
- **深度思考过程（Reasoning）重构**：
  - 收起时为精致优雅的胶囊折叠条：`▾/▸ 🧠 深度思考 (N 字符)`；
  - 展开后接入 Markdown 排版分层渲染，并支持一键复制完整思考过程，彻底解决此前挤在小灰块里密密麻麻英文排版糟糕的问题。
- **消除空白工具调用大卡片**：
  - 工具调用改用紧凑芯片展示：`🔧 工具名 · 状态 [详情]`，仅在工具名或内容非空时展示，收起时高度极小，不再出现空白突兀大灰框。
- **状态枚举全中文本地化**：
  - 完善 `completedSuccess` -> `已完成`、`completedError` -> `执行出错`、`completedInterrupted` -> `已中断`、`running` -> `运行中`、`idle` -> `空闲` 等全部状态枚举，消除生硬英文。

### 新增（设置面板软件版本与更新闭环）
- **软件版本明确展示**：
  - 设置面板中清晰展示当前已安装版本：`v0.4.0-beta6 (Build 10)`。
- **在线拉取与检查更新**：
  - 提供「检查更新」按钮，对接 GitHub Releases API；
  - 支持配置私有仓库 GitHub 访问 Token（保存在本地加密设置中，免受私有仓库权限限制）；
  - 发现新版本时弹出新版本详情卡片，显示最新 tag、更新日志摘要，并提供**「浏览器下载 APK」**与**「复制下载链接」**；
  - 若已是最新，显示绿色勾选提示。
- **电脑端推送更新指南**：
  - 附带电脑端快速更新说明：手机连接电脑后在仓库根目录执行 `./build.sh install` 即可一键编译推送覆盖升级。

- `versionName 0.4.0-beta6` / `versionCode 10`；签名与 v0.3.0-m3 同指纹，可直接覆盖安装。

## v0.4.0-beta5（2026-09-30）· 相机扫码全面重构与修复

**里程碑：重构 ScanScreen，修复二维码扫描完全无效问题**

### 修复（相机扫码识别完全无效）
- **根因分析**：
  1. **图像物理朝向未校准**：手机竖屏手持时，后置摄像头传感器硬件安装方向为横向（`rotationDegrees = 90` 或 `270`）。原实现直接将未旋转的 Raw Y 平面输入 ZXing，画面呈 90 度倒置状态；`PlanarYUVLuminanceSource` 不支持旋转，ZXing 无法解析高密度屏幕二维码。
  2. **缺少屏幕摩尔纹兜底**：原实现仅使用 `HybridBinarizer`，未开启 `TRY_HARDER`，面对 PC 液晶屏幕反光和像素网格条纹时极易漏检；`decodeWithState` 抛异常后未在 finally 块中复位 `MultiFormatReader.reset()`，导致解析器内部状态污染。
  3. **分析器目标分辨率缺失**：CameraX 默认选择低分辨率，导致 152 字符高密度二维码（QR Code Version 7~8）中的关键矩阵点模糊不清。
- **重构与优化**（`ui/screens/ScanScreen.kt`）：
  - **像素级顺时针旋转校准**：提取 Y 灰度平面并去除 rowStride padding 后，依据 `proxy.imageInfo.rotationDegrees` 进行轻量级顺时针旋转变换（0/90/180/270），送入 ZXing 的始终为物理正向画面。
  - **双重二值化器兜底与 Hint 增强**：添加 `TRY_HARDER` 与 `UTF-8` 编码提示；优先 `HybridBinarizer`，失败时立即回退 `GlobalHistogramBinarizer`，在 finally 块中始终强制调用 `reader.reset()`。
  - **高分辨率配置**：使用 `ResolutionSelector` 钉住 1280x720 目标分辨率，确保复杂密集二维码的边缘清晰。
  - **交互与对焦**：支持点击取景框任意区域对焦/测光（CAF + 触摸对焦）；叠加高亮绿色对焦辅助框线；加入 `AtomicBoolean` 防重复触发。

- `versionName 0.4.0-beta5` / `versionCode 9`；签名与 v0.3.0-m3 同指纹，可直接覆盖安装。

## v0.4.0-beta4（2026-09-30）· 互踢死循环修复

**里程碑：App 不再参与终端抢占循环，被踢后停在横幅等用户手动恢复**

### 修复（KICKED 后自动重连引发互踢死循环）
- **现象**：App 与桌面端「移动端远程控制」面板（内嵌官方 Web 页，同为 terminal）反复互踢——
  App 被踢 3s 后自动重连抢回名额，又把面板踢掉，形成 3s 级死循环，
  直至面板关闭才终止（2026-09-30 23:30–23:40 桌面端日志全程还原）。
- **根因**：`RelayClient.fail()` 对 KICKED 置 `Failed` 态不重连，但服务端踢人后随即关闭 WS，
  `onClosed` 回调**无条件 `scheduleReconnect()`**，绕过了终态语义。
- **修复**（`relay/RelayClient.kt`）：
  - 新增 `terminalFailed` 标志；`fail()` 对 **KICKED / AUTH_FAILED / PROTOCOL_MISMATCH**
    三类终态失败置位，`scheduleReconnect()` 见标志即放弃；
  - `connect()`（手动重连/切设备）清除标志，横幅指引「断开→重新连接」路径不受影响；
  - `DEVICE_OFFLINE` / 网络失败仍保持自动重连（等待对端语义不变）。
- **行为变化**：被踢后 App 停留在「控制权已在别处接管」横幅（不再自动回抢），
  关闭桌面端面板后手动点「重新连接」即可恢复。
- **使用建议**：手机 App 与桌面端远程控制面板避免同时开启——两者都是同账号 terminal，
  中继只允许一个在线（官方协议设计）。

- `versionName 0.4.0-beta4` / `versionCode 8`；签名同指纹可覆盖升级。

## v0.4.0-beta3（2026-09-30）· 16 KB 页对齐修复

**里程碑：消除 Android 15「应用兼容性」警告（HyperOS 弹窗）**

### 修复（16 KB 页对齐，Android 15 / HyperOS 弹窗）
- **现象**：真机（小米 15 Pro / HyperOS 2 / Android 15）安装后弹「Android 应用兼容性——
  此应用不符合 16 KB 对齐要求」，列 `libimage_processing_util_jni.so`（LOAD 区段未对齐）、
  `libdatastore_shared_counter.so`、`libandroidx.graphics.path.so`（未知错误）。
- **根因**：三个原生库来自旧版 AndroidX 依赖（camera 1.3.4 / datastore 1.1.1 /
  compose BOM 2024.09.02 传递的 graphics-path），编译时未按 16 KB 页对齐。
  另经 `tools/_16k_check.py` 核实：zip 条目层 AGP 8.5.2 已做 -P 16 对齐（全 PASS），
  camera 的 so 是 ELF LOAD 段 4096 对齐——唯一真问题。
- **修复**（`app/build.gradle.kts` 依赖升级，API 兼容无代码改动）：
  - `androidx.camera:*` 1.3.4 → **1.4.2**（1.4.x 原生库 16 KB 对齐编译）
  - `androidx.datastore:datastore-preferences` 1.1.1 → **1.1.7**
  - 显式钉住 `androidx.graphics:graphics-path:**1.0.1**`（覆盖 compose BOM 传递的旧版）
- **验证**（`tools/_16k_check.py`，检查每个 `lib/*.so` 的 ELF `PT_LOAD.p_align` 与
  zip 数据区偏移双重 16 KB 对齐）：修复前 4 FAIL（camera 三 ABI + x86_64），
  修复后 **16/16 全 PASS**（含 camera 1.4.2 新增的 `libsurface_util_jni.so`）。
- 说明：该弹窗仅在**可调试应用**上显示（release 不弹），但按规范修复以消除告警。

- **真机回归 PASS**（2026-09-30 补验）：覆盖安装后数据完整保留，「Android 应用兼容性」弹窗
  **不再出现**；扫码页（CameraX 1.4.2）相机预览正常、无相机错误日志。
- `versionName 0.4.0-beta3` / `versionCode 7`；签名同指纹可覆盖升级。

## v0.4.0-beta2（2026-09-30）· 真机验收问题修复

**里程碑：修复真机验收（2026-09-30）发现的两处 App 缺陷**

### 修复（P2：发送永久卡死——真机实测复现）
- **根因**：桥断开后 `RpcChannel` 的 `_bridge` 仍停留在 `Ready`（陈旧状态，WS 关闭时无人复位），
  此时发送的 `sendConversationCommandV4` 被投进已死的连接、永远等不到 ack；而
  `pendingResponses` 只在 `reset()` 里被静默清空（且 `reset()` 无调用方），调用方回调永不触发
  → App「发送中」状态永久卡住，只能重启（真机 21:5x 实测复现）。
- **修复**（`relay/RpcChannel.kt`）：
  1. `call()` 新增 `timeoutMs` 参数，主线程 `Handler.postDelayed` 兜底，超时以
     `RpcReply.Err("timeout after Nms")` 收场；
  2. `pendingResponses` 改 `ConcurrentHashMap`（WS 线程收响应、主线程跑超时，两侧竞争 remove）；
  3. 新增 `failPending(reason)`：**桥重建（`bridge-ready`）时**把旧桥上的挂起请求逐个以错误收场
     （新桥 ack 序列空间全新，旧请求永远不可能有应答）；`reset()` 同样走 `failPending`；
  4. 成功/错误应答路径补 `cancelTimeout`，避免超时任务残留。
- **调用侧**（`relay/ConversationChannel.kt`）：发送（`sendText`）、审批/表单应答
  （`resolveInteraction`）、停止（`stop`）三处命令统一传 `SEND_ACK_TIMEOUT_MS = 15s`。
- **真机验证 PASS**（2026-09-30 22:5x，飞行模式断网复现）：日志实证
  `sendText session=… chars=2` → `rpc timeout id=12 method=sendConversationCommandV4 after=15000ms`
  → UI 自动复位（`sending=false`，输入栏恢复）；修复前此处永久卡死。

### 优化（失败提示可见性）
- 失败类 flash 提示（`发送失败` / `应答失败` / `连接已断开`）停留 **8s**（原 4s 在真机上易被错过，
  验收时用户反馈「未出现发送失败」实为提示一闪而过）；成功类提示保持 4s 免打扰。

- `versionName 0.4.0-beta2` / `versionCode 6`；签名与 v0.3.0-m3 同指纹，可覆盖升级。

## v0.4.0-beta1（2026-09-30）· 发版收官内测（beta）

**里程碑：远程控制全功能内测包——发送/表单/多会话/附件/语音/权威角标/Widget 全齐，待真机日常验收后转正式**

汇总 2026-09-29 ~ 09-30 七轮增量：P0-1 发送与停止、P1-1 表单应答、P1-2 多会话看板、
P1-3 附件全链路与语音输入、P1-4 协议常量结清、D-2 贴底修复、D-3 通知快捷应答、
E-1 权威角标、P2-3 桌面 Widget 与会话搜索、附件流式化、X-1 keystore 结清；
以及范围决策：M4/M5 移出开发计划。各轮详情见下方分节。

- `versionName 0.4.0-beta1` / `versionCode 5`；签名 keystore 与 v0.3.0-m3 同指纹
  （SHA-256 `1D:46:E9:…:24:BE:55`，`tools/_apk_cert_fp.py` 核验），已装设备可直接覆盖升级。
- 下载：GitHub Releases 页 `ZCodeRemote-0.4.0-beta1.apk`（minSdk 31 / targetSdk 35）。
- 待补：X-2 UI 层真机验收（输入栏/表单卡/角标横幅/通知/附件条/语音/Widget）与
  P0-2 小米 15 Pro 日常使用观察（2026-09-30 真机已到位，验收进行中）。

### 2026-09-30 七轮：计划范围调整——M4/M5 取消

#### 决策（用户拍板：M4/M5 移出开发计划）
- **M4（P2-1）VPS 备用 Runner** 与 **M5（P2-2）高级模式（自建 bridge + NaCl E2E + 自建中继）**
  不再开发，VPS 不再采购。原因：单人自用场景下，PC 关机场景的实用收益与 E2E 安全收益
  不值得「VPS 持续成本（¥10–40/月）+ bridge 常驻维护 + 双协议维护」的代价。
- **连带影响**：文件/diff/Git 浏览依赖自建通道（中继协议本身无文件 RPC，已 bundle 核实），
  M4/M5 取消后**就此搁置**；方案文档（ZCode远程控制安卓APP方案.md）中 M4/M5 章节仅作历史
  设计参考，HANDOVER 已在文档索引标注。
- **调整后剩余工作**：仅 X-2（App UI 层验收）/P0-2（小米 15 Pro 真机验收）——待硬件/设备，
  完成后发新版内测 APK 即收官。

### （2026-09-30 六轮：附件流式化 + 会话搜索）

#### 优化（P1-3 附件分片流式读，技术债清偿）
- **内存峰值 20MiB → 一倍分片（384KiB）+ 64KiB hash 缓冲**：选附件不再整块读进内存——
  UI 层 `readAttachment` → `inspectAttachment`（只取 name/mime/size，query 报不出大小时
  流式计数）；`ConversationChannel.uploadAttachment` 签名 `data: ByteArray` →
  `openStream: () -> InputStream + totalBytes`，内部第一遍流式算 sha256 → begin →
  单次开流顺序读满分片逐片传（续传 skip 前部）；`AppViewModel.addAttachment` 持 uri，
  上传时经 contentResolver 开流。
- **行为变化**：选中后文件被移动/删除 → 上传时报「无法打开所选文件」（原先选中即读，
  后续改文件不影响上传）；20MiB 超限提示从静默忽略改为 flash 明示。

#### 新增（P2-3 首页会话搜索）
- 首页「会话」区搜索框（P2-3 池外小项）：按标题/工作区路径过滤，忽略大小写，关键字跨
  重组保留（`AppViewModel.sessionQuery`）；无匹配时显示「没有匹配」提示；「N 运行中 / M」
  计数随过滤更新。
- 构建 BUILD SUCCESSFUL（两轮：先修 `var` 自动 setter 与手写 setter 的 JVM 签名冲突）。

### （2026-09-30 五轮：P2-3 桌面 Widget）

#### 新增（桌面 Widget：待处理角标）
- **`widget/PendingWidgetProvider.kt`**（RemoteViews，零新依赖）：桌面卡片显示待处理总数
  （审批 + 表单交互，E-1 权威角标汇总值）——已连接无待处理显示「运行正常」、有积压显示
  「N 项待处理 · 点按处理」、断线显示「未连接」；点按即打开 App。
- **更新时机**：App 内计数变化（`recomputeSessionPending` 尾部）、连接状态变化（relayState
  collect）、主动断开（disconnect）三处调用 `syncWidget` → `PendingWidgetProvider.sync` 推送；
  `updatePeriodMillis=0` 不轮询不耗电，App 未运行时显示最后一次状态。
- **安全**：receiver `exported=false`（系统 APPWIDGET_UPDATE 广播不受限，第三方不可触发）；
  PendingIntent `IMMUTABLE` 且只打开 MainActivity，无 extras 注入面；Widget 仅显示计数，
  不含会话内容/凭据。
- 构建 BUILD SUCCESSFUL；渲染样式待真机查看（X-2 一并）。

### （2026-09-30 四轮：E-1 会话权威角标）

#### 新增（E-1 sessions-index 权威角标）
- **协议实证（bundle 源码定位，research/index-web.js）**：topic `sessions-index/<workspaceId>`，
  独立 RPC `subscribeSessionsIndexV4`（args `{workspacePath, runtimePolicy:'existing-only'}`）+
  listen `onDynamicSessionsIndexFrame`；snapshot `sessions[]`（键 `sessionId`）含
  `pendingInteractionSummary {permissionCount, userInputCount}`；增量 op
  `session.upserted` / `session.removed`。记载 PROTOCOL.md §6.7。
- **`relay/SessionsIndexChannel.kt`**：workspace 级订阅（开桥后一次，会话切换不重订），
  snapshot 全量 / upserted 单条 / removed 删除三路维护，`summaries: StateFlow<Map<sessionId, PendingSummary>>`。
- **`AppViewModel.recomputeSessionPending` 升级**：服务端权威计数优先（覆盖全部会话、
  消解即清零、不怕事件流漏帧）；任务事件流推导降级为回退（只补权威索引缺失的会话）；
  当前订阅会话仍以会话流明细覆盖（明细 0 时信权威）。
- **端到端实测** `tools/_e1_verify.py` 面板在线补跑（2026-09-30）：订阅 ack
  `six-…/mode=snapshot` + snapshot 到达 → sendText 触发 AskUserQuestion → 权威角标
  `{permissionCount:0, userInputCount:1}` → resolveInteraction accepted → 字段回落消失。
  三项 PASS + 无待处理时字段缺省（optional，App 按 0/0）确认。E-1 闭环 ✅。
- 构建 BUILD SUCCESSFUL。

### （2026-09-30 三轮：P1-3 附件发送侧实证修复 + 语音输入）

#### 关键发现：sendPrompt RPC 会静默丢弃附件（发送侧修复）
- **现象**（tools/_p13_send_verify.py 对照实测）：`sendPrompt` RPC args 带 `attachments`
  → 201 accepted、消息入流，但桌面端模型明确回答「没有收到任何附件」，userInput 行无
  attachments 字段——`sendPrompt` args schema 只有 `{workspacePath, sessionId, inputId, content}`，
  多传的 attachments 被 zod strip。上轮 CHANGELOG 里「host 包装 sendPrompt 透传附件」的
  asar 考证结论不成立（§6.6 已修正）。
- **正确路径（官方 web 远程页唯一发送路径）**：`sendConversationCommandV4` envelope
  `type:'sendText'`，payload `{text, attachments: [{ref, fileName, mime, bytes}]}` →
  ack `accepted` + `result={type:"inputAccepted", delivery:"startNow"}`，userInput 行回显
  attachments，模型可直接读到附件内容（assistant 原样复述附件首行，六项验收全 PASS）。
- **App 修复**：`ConversationChannel.sendPrompt` 改走 sendText envelope（方法签名/调用方
  不变；ack 判据 status ∈ accepted/duplicate/noop 复用 parseCommandAck）。纯文本与带附件
  同路径，与官方 web 完全同构。

#### 新增（P1-3 语音输入）
- **`ui/voice/VoiceInput.kt`**：系统 `SpeechRecognizer` 转文字（官方 web 无语音功能——
  bundle 里的 speech chunk 只是 lucide 图标，属 App 自研；零协议改动，发送仍是文本）。
  点击开始聆听（再次点击提前出结果），partial 实时上屏，最终文本追加进输入草稿；
  RECORD_AUDIO 运行时权限按需请求，拒绝则降级提示；识别不可用的设备按钮整体不渲染。
- **InputBar 集成**：🎤 按钮（📎 旁），输入栏上方语音状态条（聆听中实时文本 / 错误提示 4s 自清）。
- **Manifest**：`RECORD_AUDIO` 权限 + `queries` 声明 `android.speech.RecognitionService`
  （Android 11+ package visibility）；`microphone` uses-feature `required=false`。

#### 构建
- 两轮 `./build.sh`（语音输入初版 / sendText 修复后）均 BUILD SUCCESSFUL。

#### 遗留
- App UI 层验收（语音按钮交互 / 附件 chip / 输入栏布局）仍待真机或 Intel/AMD 机器（X-2 一并）。
- 语音识别引擎依赖设备端 RecognitionService（小米 15 Pro 为小爱语音引擎），真机需验一次。

### （2026-09-29 二轮：X-1 keystore 核验 + D-2/D-3 + P1-3 附件上传）

#### 关键结论：release 签名 keystore 并未丢失（X-1 结清）
HANDOVER 里「开发机已换、release keystore 丢失」的说法**对本机不成立**：
- `toolchain/keys/zcode-remote.keystore` 与 `app-android/keystore.properties` 均在，keytool 可正常加载；
- 其证书 SHA-256 指纹
  `1D:46:E9:E8:67:48:A2:B1:98:46:6F:FA:C2:B5:8F:9F:F4:BD:BD:37:FB:68:C3:5E:1A:50:4C:65:ED:24:BE:55`
  与**已发布的 v0.3.0-m3 release APK 完全一致**（比对证据：v0.3.0-m3 的 APK 尾部
  APK Signing Block 解析出的 v2 证书指纹，脚本 `tools/_apk_cert_fp.py`）；
- 本机 `assembleRelease` 通过，产物签名指纹一致 → **后续发布可直接覆盖升级，不必重造 keystore**。
- 顺带修正：本机 `toolchain/`（jdk17 + gradle 8.7 + android-sdk platform-35/build-tools-35）
  完整，`build.sh` 走仓库内工具链即可，`F:/AndroidTools` 回退路径实际不存在。

#### 修复（D-2 会话页贴底索引偏移）
- `ConversationScreen` 的贴底滚动 `animateScrollToItem(rows.lastIndex)` 未计入列表第 0 位的
  「加载更早」占位项，实际停在**倒数第二行**；改为显式 `headerCount`（有行则 1）计算下标，
  锚定滚动（`idx + headerCount`）同步修正。`rows` 为空时占位项与滚动均被跳过。

#### 新增（D-3 elicitation 通知栏快捷应答）
- `notify/ElicitationNotifier.kt`：表单类交互（AskUserQuestion / 计划批准）的**通知栏快捷应答**，
  与审批通知分列两套（独立通道 `elicitations`、独立 ID 区间 300000+，互不 cancel）。
- 只为「一个按钮能表达完整答案」的形态给动作：plan_approval → 批准计划/拒绝；
  单题单选且选项 ≤3 → 每选项一个按钮；其余（多题/多选/需自由文本）只给「打开 App 处理」。
- 答案 JSON 由通知动作直接携带，`ElicitationReceiver` → `ElicitationBridge` →
  `AppViewModel.resolveElicitationById` 复用同一条 resolveInteraction 管道；无连接时明确提示未发出。

#### 新增（P1-3 附件上传：协议实证 + 客户端 + UI）
- **协议实证**（tools/_p13_probe.py，实机）：四件套 `attachmentBeginV4/ChunkV4/CommitV4/AbortV4`
  打通，单分片与 900KiB 多分片均成功，commit 返回 `ref = zcode-artifact://…`；
  结论回写 PROTOCOL.md §6.6（含 host 常量 20MiB / 512KiB / 64 片、官方 384KiB 分片、
  `sha256:` 校验和、**begin 不需要 connectionId**）。
- **Kotlin 客户端**：`ConversationChannel.uploadAttachment`（begin→chunk 循环→commit，
  失败自动 abort；进度回调；`state=="committed"` 幂等复用 ref）+ `AttachmentRef`。
- **发消息携带附件**：`ConversationChannel.sendPrompt` 增加可选 `attachments`
  （元素 `{ref, fileName, mime, bytes}`，与官方 web `attachmentRef` 同形）。
- **UI**：会话页 📎 按钮 → SAF 文件选择器（`OpenDocument`）→ 上传进度条 → 已上传 chip（可移除）；
  「发送」按钮改为 `有草稿或已有附件` 即可用。
- ⚠️ **待验证**：发送侧「附件随 sendPrompt 到桌面端会话」尚未做一次真实发送的端到端验收；
  UI 层整体（D-3/P1-3）同前几轮，待真机或 Intel/AMD 机器补验（本机兆芯 CPU 起不了模拟器）。

#### 文档
- `README.md` 中英双语同步：功能一览补发送/停止、表单应答、多会话看板；里程碑表 M3/M3+ 状态更新。

---

### （P0-1 发送/停止 + P1-1 表单应答 + P1-2 多会话看板）

**里程碑：P0-1 完成 —— 会话页输入栏（sendPrompt）+ 运行中停止（stop envelope）；
P1-1 完成 —— elicitation 表单类交互应答（AskUserQuestion / 计划批准 / 确认框）；
P1-2 完成 —— 多会话并行看板 + 控制权切换提示**

#### 新增（P1-2 多会话看板与控制权提示）
- **会话待办角标**：`AppViewModel.sessionPending`（taskId → 待处理条数，来源＝任务事件流
  覆盖所有会话；当前订阅会话以会话流数据覆盖），HomeScreen 会话卡片显示「⏳ 待处理 N」，
  有未处理交互的卡片用 errorContainer 醒目底色。
- **当前会话指示**：订阅中的会话标「当前」（secondaryContainer 高亮）；桌面端正打开的会话标
  「PC 在看」（来自 `workspace-list` 的 `activeTaskId`）。
- **控制权切换提示**：被官方 Web 版/另一终端接管（`KICKED`）时首页顶部显示醒目横幅
  「控制权已在别处接管」并说明处理方式。
- **视图状态上报**：`BridgeFrames.mobileViewStateUpdate()`（PC schema
  `{zcode_type:"mobile-view-state-update", viewState:{activeWorkspaceKey?, activeTaskId?, updatedAt}}`），
  接管成功（Paired）与会话切换时上报，PC 端据此知道手机在看哪个工作区/会话。
- 协议侧另定位到会话级 `pendingInteractionSummary{permissionCount, userInputCount}`（在
  conversation 的 `sessions-index/<workspaceId>` overlay 里），可作为后续更精准的服务端权威角标来源。



#### 新增（P1-1 表单应答）
- **PendingElicitation 模型**（Interactions.kt）：会话帧 `pendingInteractions` 里 kind=="userInput"
  条目与任务事件流 `elicitation_request` 双源解析（questions/plan/freeText/autoResolution）。
- **应答**：与审批共用 `resolveInteraction` envelope（`sendResolveInteraction` 公共出口），
  answer 按官方 web 形态构造——表单 `{action:"accept", content:{answer/answer_N/answers}}`、
  拒绝 `{action:"decline"}`、计划批准 `{action:"accept"}`、自由文本 `{freeText}`。
- **会话页 ElicitationCard**：计划批准（plan 文本+批准/拒绝）、逐题选项（单选即选、多选可累加、
  自定义文本并入）、freeText 输入框、待审倒计时。
- **双源合并**：`AppViewModel.elicitations`（会话帧整组替换 + 任务事件流 elicitation_request/
  elicitation_resolved，interactionId 去重，会话帧优先）。
- **技术债**：`AppViewModel: rpc event` 日志 Info → Debug（HANDOVER 技术债清单）。
- `tools/_p11_probe.py`（真实帧观测）、`tools/_p11_verify.py`（端到端验收）。

#### 协议实证（写入 PROTOCOL.md §6.5）
- pendingInteractions 表单条目 kind 是 **"userInput"**（不是 "elicitation"）；host answer zod
  `{optionId?, freeText?, action?(accept/decline/cancel), content?(Record)}`；官方 web v4
  `onRespond → {action, content}`（表单 content 由 rut 构造 answer/answer_N/answers）。
- **端到端实测**：AskUserQuestion 真实触发 → userInput 条目观察 →
  `{action:"accept", content:{answer:"A=提交验收报告"}}` → ack accepted → 条目消解。三项 PASS。

#### 新增（P0-1 发送/停止）
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

#### 构建环境
- `app/build.gradle.kts`：release 签名条件化——keystore.properties 缺失时不再在配置期抛异常
  （原先连 assembleDebug 都过不去）。⚠️ 正式 keystore 随原 toolchain 丢失，发布前必须恢复。
- `build.sh`：仓库内 toolchain/ 缺失（换机/重新克隆）时自动回退系统路径
  （F:/AndroidTools 的 JDK17/SDK/Gradle 8.11.1）。

#### 协议实证（写入 PROTOCOL.md §6.4）
- **stop 是 envelope 命令而非裸 RPC**：host asar 官方 web 版 `br('stop', {expectedForegroundExecutionId?}, sessionId)`，
  payload zod schema `stop:{expectedForegroundExecutionId: string.min(1).optional()}`；
  `sessionStop:"session/stop"` 只是 host→CLI 内部层枚举，与远程通道无关。
- 端到端实测（桌面端在线，probe 身份）：sendPrompt 201 `accepted:true` → turn running 且
  `control.canStop=true/stopState=stoppable` → stop ack `status=accepted` → 桌面端相位
  `running → completedInterrupted`、canStop 清零。四项全 PASS，帧落盘 `_tmp/p01_frames_*.json`。
- 桌面端远程控制面板打开期间 device 在线（等待连接即 matched 可达）；`webRemoteControlLastEnabledContext`
  是桌面端启动恢复远程控制的持久化上下文。

#### 已知环境限制
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
