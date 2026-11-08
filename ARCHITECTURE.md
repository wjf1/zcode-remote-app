# ARCHITECTURE

> 只写不常变的内容：回答「X 在哪 / 这个模块干嘛的」与系统边界，不追踪逐次实现细节。
> 动态状态（最近变更、待办）见 `HANDOFF.md`；协议细节见 `PROTOCOL.md`；构建/避坑见 `HANDOVER.md`。
> 每半年复审一次；架构级变更（层边界、SSOT、协议栈）发生时才更新本文件。

## 1. 鸟瞰：这个系统解决什么问题

ZCode 是运行在 PC 上的 AI Agent 宿主；官方远程控制（`zcode.z.ai/remote`）只提供 Web 页面，
锁屏审批、后台保活、断线恢复在手机浏览器上先天残缺。本项目是它的**非官方原生 Android 客户端**
（Kotlin + Jetpack Compose，单模块 `app-android/`）：通过逆向实证复用官方中继与配对协议
（V1 proof 配对 / V2 账号 JWT 配对，双栈并存），把 PC 宿主上的会话流、工具审批、附件、
模型配置完整搬到手机上。核心差异化能力：锁屏通知栏直接批准/拒绝、断网自动重连、
会话离线缓存秒开、前台服务保活。App 不直连 PC——所有通信经官方中继转发，
客户端本身无遥测、无账号体系、凭据只存本机加密。

### 数据流向（主链路）

```
配对入口（相机扫码 / 手输链接 / 深链 / 设置页粘贴令牌 / Debug --es jwt 注入）
  → QrParser 分流（V1 Ready 直连确认 / V2 NeedsLogin → WebViewActivity 登录）
  → MultiDeviceStore 加密落盘（ProtocolStack 标记栈别）
  → RelayClient 建连：V1 proof 握手 ｜ V2 jwtProvider→relay token→ws2 auth_init
  → bootstrap（V1 tasks[] ｜ V2 workspaces[]）
  → ConnectionCoordinator 开 workspace 桥（RpcChannel，V4 握手）
  → 三通道并行订阅：ConversationChannel（会话流）/ SessionsIndexChannel / ControllerChannel（V2 列表）
  → 帧解析（ConversationFrames/ControllerReducer）→ RowStore / 不可变快照 StateFlow
  → ConversationRepository → UI 派生渲染；行缓存异步落盘（加密）供离线秒开
审批反向链路：中继推送 interaction → ApprovalNotifier（锁屏通知）→ 用户决策
  → ApprovalRepository → ConversationChannel 应答 → 桥 RPC 回宿主
```

## 2. Codemap（X 在哪）

Gradle 模块：`:app`（全部业务）+ `:benchmark`（Macrobenchmark）。源码根
`app-android/app/src/main/java/com/zcode/remote/`，包按「协议 / 编排 / 数据 / 展示」分层：

| 包 | 职责 | 关键文件 |
|---|---|---|
| `relay/` | 协议层：外层中继连接、桥 RPC、各订阅通道 | `RelayClient.kt`（连接状态机/心跳/重连/退避）· `RelayProtocol.kt`（握手帧构造，V1/V2 双栈）· `RpcChannel.kt`（桥 RPC、分片、ack、代次键控、v4ClientId 持有方）· `ProtocolLimits.kt`（**协议预算单一事实来源**）· `BridgeFrames.kt`（data 信封/bootstrap 解析）· `Vql.kt`（服务端对象编码解码） |
| `relay/`（通道） | 每类数据一个订阅通道 | `ConversationChannel.kt`（会话流订阅/审批应答/上传编排）· `ConversationFrames.kt`（快照/增量帧解析 + `RowStore`）· `ControllerChannel.kt` + `ControllerReducer`（V2 会话列表权威源，`controller/tasks-index` + `controller/workspaces` 双 topic；reducer 为无 IO 纯状态机）· `SessionsIndexChannel.kt` · `WorkspaceConfigChannel.kt` |
| `relay/`（辅助） | 认证 / 上传 / 防护 | `V2AccountApi.kt`（JWT→relay token，single-flight）· `AttachmentUploadPipeline.kt`（顺序 suspend 上传，可 JVM 单测）· `InboundAdmission.kt`（入站帧预算）· `RelayEndpointValidator.kt`（Release 仅接受绝对 `wss://`）· `UserFacingError.kt`（错误键→文案映射） |
| `service/` | 进程级连接编排 | `ConnectionService.kt`（specialUse 前台服务保活）· `RemoteConnectionManager.kt`（连接栈持有方 + 网络闸门登记）· `ConnectionCoordinator.kt`（connect 编排、桥就绪自动订阅、自愈重试调度）· `ConnectionScope.kt`（读写代理 facade） |
| `repo/` | 领域仓库 | `ConversationRepository.kt`（行/元信息/按会话隔离草稿）· `ApprovalRepository.kt`（审批决策）· `AttachmentUploader.kt`（内容源/FileDescriptor）· `ModelConfigRepository.kt` · `UpdateRepository.kt`（GitHub Release 检查）· `NotificationCoordinator.kt` |
| `storage/` | 本地持久化（全部加密敏感项） | `MultiDeviceStore.kt` + `CredentialCrypto.kt`（AES-256-GCM + Keystore）· `TokenStore.kt`（JWT）· `SessionCacheStore.kt`（离线缓存，独立密钥别名加密）· `PairedDevice.kt`（QR 解析 + `ProtocolStack`，**全仓唯一 passHash 判据**）· `SettingsStore.kt` · `DevicesCodec.kt` |
| `notify/` | 通知面 | `ApprovalNotifier.kt`（锁屏审批，含内联回复）· `ElicitationNotifier.kt` · `TerminalNotifier.kt` · `NotificationPermission.kt`（纯判定） |
| `ui/screens/` | Compose 页面 | `HomeScreen.kt`（列表 + `HomeEmptyState`/`HomeConnectionInfo` 纯函数三态）· `ConversationScreen.kt` 及同前缀拆分件（Timeline/Composer/TopBar/PendingCards 等）· `ApprovalsTab.kt` · `SettingsTab.kt` · `ScanScreen.kt` · `KeepAliveGuideScreen.kt` · `DiagnosticsSection.kt` |
| `ui/components/` | 通用组件 | `MarkdownView.kt`（图片零出站请求兜底）· `DiffView.kt` · `SessionStatusPanel.kt` · `TurnChangesCard.kt` · `SafeUriHandler.kt`（链接白名单）· `AutoResolveCountdown.kt` |
| `ui/theme/` `ui/voice/` | 视觉与语音输入 | `Theme/Typography/Dimens` · `VoiceInput.kt` |
| `util/` | 横切工具 | `ZLog.kt`（日志门）· `LogRedactor.kt`（脱敏）· `UiText.kt`（文案载体，i18n 地基）· `HttpClientProvider.kt`（进程级 OkHttp 池）· `Format.kt` · `diag/DiagnosticsRing.kt`（诊断环形缓冲） |
| `widget/` | 桌面部件 | `PendingWidgetProvider.kt`（待处理角标） |
| `debug/`（debug 源集） | 仅 debug 构建的取证件 | `DebugPairReceiver`（adb 注入配对/JWT）· `AttachUploadProbeActivity` 等探针（从进程内真实连接栈直发协议，绕开 MIUI SAF 限制） |
| 仓库根 | 协议与流程文档 | `PROTOCOL.md`（协议 SSOT，§11 为 V2 体系）· `research/`（逆向工作件）· `tools/`（probe 等取证脚本）· `toolchain/`（构建工具链，gitignore） |

## 3. 架构不变量（含刻意不做）

**结构性不变量**

1. **`relay/` 是协议唯一出入口**：UI/repo 层不直接接触 WebSocket 帧；协议常量与上界全部
   收敛在 `ProtocolLimits.kt`，任何层不得散落魔法数。
2. **V1/V2 双栈以 `PairedDevice.ProtocolStack` 判据隔离**：它是全仓唯一 passHash 判据，
   两栈互不渗透。认证差异（稳定事实，详见 `PROTOCOL.md`）：

   | | V1（旧，<3.16 配对仍可用） | V2（ZCode ≥3.16） |
   |---|---|---|
   | 中继端点 | `wss://zcode.z.ai/ws` | `wss://<origin>/api/v1/remote/ws2` |
   | 凭据 | QR 内含 hash，proof 应答 challenge | 账号 JWT → relay token（60s 有效现取现用），auth_ack 直达无 challenge |
   | 会话列表 | bootstrap `tasks[]` | Controller 通道双 topic（bootstrap 无 `tasks[]`） |
   | 配对入口 | 确认弹窗直连 | WebViewActivity 登录 / 粘贴令牌 / Debug 注入 |
3. **连接级握手每桥只认一个 clientId**：三通道（会话/会话索引/Controller）统一共用
   `RpcChannel.v4ClientId`，各自 initialize 会互踢（`fault.connection.clientChanged`）。
4. **V2 会话列表权威源是 Controller 通道**（`controller/tasks-index` + `controller/workspaces`），
   V2 bootstrap 不再下发 `tasks[]`；会话列表以不可变快照 `StateFlow` 下发，禁止原位 `MutableList`。
5. **纯逻辑零 Android 依赖**：被 JVM 单测覆盖的类（reducer、pipeline、纯函数）零 `ZLog` 调用、
   零 `android.util.*`（无 Robolectric，否则 "not mocked"）；日志走注入式出口。
6. **会话正文/凭据落盘必加密**：`CredentialCrypto`（Keystore AES-GCM）；加密器不可用则跳过
   落盘，绝不回退明文；`AndroidManifest` `allowBackup=false`。

**刻意不做（历史决策，勿当遗漏）**

- **App 侧永不提供文件写能力**（凭据红线，HANDOVER §8）——只上传用户显式选择的附件。
- **证书固定（pinning）不做**：`*.z.ai` 通配符证书每年轮换，侧载分发无自动更新通道，
  硬钉会导致官方轮换后彻底失联；以 `network_security_config.xml`（禁明文 + 仅系统 CA）显式化。
- **Markdown 图片零出站请求**：`NoOpImageTransformer`，不加载任何远程图片（隐私决策），
  兜底只提供「复制链接 / 白名单 scheme 打开」。
- **出站大 RPC 物理分片不实现**：官方算法已实证，App 现为本地拒绝超限帧（硬门之一）。
- **A-5 逻辑 fragment 重组**：无真实帧样本不实现（「未知 fragment 显式失同步」已落地）。
- **生物识别锁 / 自建中继 E2EE 不做**（Sprint 7 历史决策）。
- **`relay/` 层异常文案不再资源化**：24 条绑在 `IllegalStateException(String)` 上，改 `UiText`
  需跨三层重构，收益不成比例（其余 UI 层文案已全部走 `strings.xml` + `UiText`）。
- **Kotlin 生态冻结在 2.0.20**：`multiplatform-markdown-renderer 0.27.0` 是其最后兼容版，
  升级 Kotlin 前不得上调该库（Dependabot 已 ignore）。

## 4. 层与系统边界

```
┌───────────────┐   wss://zcode.z.ai/ws (V1)          ┌──────────────────┐
│  本 App        │   wss://<origin>/api/v1/remote/ws2  │  官方 ws2 中继    │
│  (Android)    │ ◄──────────────────────────────────► │  (zcode.z.ai)    │
└───────────────┘   外层帧: auth/pair/heartbeat       └────────┬─────────┘
        │  信任边界①：中继可见通信内容（与官方 web 同链路，不额外做端到端加密）  │
        │                                                    ▼
        │ 内层帧(data.payload, zcode_type 路由)      ┌──────────────────┐
        └─────────────────────────────────────────► │  ZCode 桌面宿主    │
              V4 连接级握手(每桥一个 clientId)        │  (PC, ≥3.16 V2)  │
              桥 RPC: 会话流/审批/附件/配置           └──────────────────┘
```

- **信任边界①（App ↔ 中继）**：中继是官方基础设施，可信传输但可见内容；凭据（配对 hash /
  JWT / relay token）只存本机 Keystore 加密，永不上传第三方；配对二维码/深链等同 PC 控制权。
- **协议边界（外层 vs 内层）**：外层中继帧只管认证/配对/心跳/路由（`RelayProtocol`）；
  内层 `data.payload` 才承载业务（`BridgeFrames` 按 `zcode_type` 路由，桥 RPC 在 `RpcChannel`）。
- **进程内层边界**：`relay/`（协议）→ `service/`（编排）→ `repo/`（领域状态）→ `ui/`（派生渲染），
  依赖单向向下；UI 只消费 StateFlow 快照，不回写协议层内部状态。
- **审批命令信封的 clientId 必须与连接绑定一致**（不变量 3 的安全面）。

## 5. 横切关注点

- **日志**：统一走 `ZLog` 门——release 构建剥离 d/i/w 级；`ZLog.e` 只写元信息不写 payload
  （CI 有 grep 门禁）；诊断走 `util/diag/` 环形缓冲（固定事件码/计数/耗时），用户主动导出，
  导出前经 `LogRedactor` 熵启发式掩码；**App 内不存在自动上传通道**。
- **错误处理**：连接失败分「终态」（AUTH_FAILED/KICKED/PROTOCOL_MISMATCH 等，不自动重连，
  指引重新登录）与「网络态」（退避 + jitter 重连，`NetworkGate` 感知断网）；心跳含单向失活
  检测（发出未被 ack 超时即判死重连）；用户可见错误经 `UserFacingError.map` 统一转 `UiText`。
- **令牌与安全**：V2 token 获取走 single-flight（按 `baseUrl|role|jwt` 键控，失败不进缓存）；
  401/403 → `TokenAuthException` → `AUTH_FAILED` 终态，不与网络错误混同；GitHub token 在
  UI 只显示布尔状态与掩码；明文 `ws://` 仅 debug 放行（`RelayEndpointValidator`）。
- **构建与发布**：工具链在仓库内 `toolchain/`（gitignore），`./build.sh` 一键构建；
  CI（`ci.yml`）三 job：JVM 单测 + lint/detekt/ktlint + 安全断言、API 35 模拟器仪器化、
  Macrobenchmark 基线比对；发布（`release.yml`）tag 驱动，`tools/check_release_consistency.py`
  强制五处一致（tag = versionName = APK = CHANGELOG 头条 = Release 标题），产物含签名 APK、
  SHA256SUMS、证书指纹、SBOM（CycloneDX）与 provenance；Beta 一律 prerelease。
- **测试取向**：协议逻辑全部下沉为 JVM 可测纯函数/纯状态机（无 Robolectric）；仪器化测试
  不依赖 Espresso 注入（API 37 真机限制）；协议实测取证用 `tools/probe.py`。
- **存储布局**：敏感项全在 `filesDir` 下经 `CredentialCrypto` 加密（凭据/JWT/会话缓存三个
  独立密钥别名）；设置项走 `SettingsStore`；`allowBackup=false` 且无 `dataExtractionRules`
  放开——密文离机不可解，属预期行为。
- **i18n 取向**：UI/notify/widget 层用户可见文案零硬编码（`strings.xml` + `UiText`），
  默认语言中文、不做英文界面（用户决策）；`relay/` 层异常文案为唯一有意保留的原文透出（见 §3）。
