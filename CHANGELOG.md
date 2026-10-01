# 变更记录 / Changelog

## v0.5.0-beta1（2026-10-01）· 移动端前端架构重构与官方 ZCode 设计系统全面升级

**里程碑：重构为底部 3-Tab 现代移动端架构，移植官方 ZCode 设计系统规范，集中式待办看板与独立设置中心**

### 新增（架构重构、新建会话与会话内动态切换模型）
- **会话内实时动态切换模型（In-Session Model Switching）**：
  - 基于官方 V4 原生命令信封机制（`zcode-agent::sendConversationCommandV4`，`type = "switchModelConfig"`），带 `baseRevision` CAS 防冲突校验；
  - 沉浸式会话详情页顶部 AppBar 新增**模型状态胶囊（如 `gemini-3.8-flash-high ▾`）**，点击可展开切换当前工作区可用模型列表；
  - 切换成功后服务端实时广播 `state.updated` 增量帧更新本地会话快照，下一轮对话指令直接以新模型驱动推理与执行。
- **支持移动端远程拉取桌面端模型目录并自主选择（Remote Model Catalog Fetching）**：
  - 修复 `readWorkspaceState` 请求通道为 `zcode-session` 官方服务，从桌面端直接拉取包含当前模型与全部可用模型列表的工作区目录；
  - **双路模型数据保障**：结合 `WorkspaceConfigChannel` 配置流订阅与实时会话快照提取，保证任何情况下均有模型数据显示；
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
