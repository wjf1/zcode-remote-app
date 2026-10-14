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
- **当前版本**：`0.5.0-beta43`（versionCode 55）；上一个发布 tag `v0.5.0-beta42`。
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
├── util/          ZLog（日志门）· LogRedactor（脱敏）· Format · diag/（诊断环形缓冲）
└── widget/        PendingWidgetProvider（桌面待处理角标）
```

- **数据流**：配对（二维码/深链）→ `MultiDeviceStore` 加密存凭据 → `RelayClient` 建连并握手
  → `bootstrap` 取会话列表 → 开 workspace 桥（`RpcChannel`）→ 订阅会话（`ConversationChannel`）
  → 会话行经 `RowStore` 落到 `ConversationRepository.rows` → UI 派生渲染。
- **协议单一事实来源**：所有上界/常量集中在 `relay/ProtocolLimits.kt`；协议细节见 `PROTOCOL.md`
  与 `research/FRAME-CODEC.md`、`research/CONVERSATION-PROTOCOL.md`。

## 4. 最近一轮变更与交付成果

> 完整逐轮记录见 [CHANGELOG.md](CHANGELOG.md) 与 [HANDOVER.md](HANDOVER.md) 文首覆盖层。
> 最近两轮均为用户体验缺陷修复：**v0.5.0-beta43（草稿隔离 + 倒计时实时化）**、
> **v0.5.0-beta42（通知权限 / 网络回调 / 旋转状态）**。

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

- **最高优先（已闭环）**：通知权限、网络回调泄漏、旋转状态保持、草稿串会话、倒计时不动——
  均已完成；前四项已通过 beta42 真机走查，草稿隔离已通过 beta43 真机仪器化测试（3/3）。
- **待补真机项（非阻塞）**：① 自适应图标在启动器内的观感（圆形/方形蒙版）；
  ② 冷启动启动屏过渡帧观感（启动屏一闪而过，属预期的快速过渡）；
  ③ 倒计时组件在真机的可视刷新（真机 API 37 因 Espresso 限制跑不了 `createComposeRule` 用例，
  由纯函数单测 + CI API 35 emulator 覆盖）。
- **已知测试基建限制（既有）**：API 37（Android 17）真机上 Espresso/Compose 测试库
  的 `InputManager.getInstance()` 缺失 → 依赖 `createComposeRule` 的仪器化用例在该真机必失败
  （既有 `MarkdownRenderTest` 同样失败）；CI 的 API 35 emulator 不受影响。真机取证请改用不依赖
  Espresso 注入的用例。
- **观测记录（非缺陷）**：一次 `force-stop` 后重启的**过渡残帧**里曾见到首页卡片「PC焦点」
  徽章短暂出现在搜索框上方；稳定态（0.8s 与 5s 两次复测）均正常、未复现，判定为 Activity
  恢复期的中间帧，非稳定缺陷。
- **既有硬门（沿用历史决策，勿当遗漏）**：
  1. **§11.5.3 A-5 逻辑 fragment 重组**——无真实帧样本不实现（「未知 fragment 显式失同步」已落地）；
  2. **出站大 RPC 物理分片**——同硬门（官方算法已实证，App 现为本地拒绝超限帧）；
  3. **§11.10 `1.0.0` 发布门**——依赖上述两项 + 连续两个 Beta 周期无严重回归。
- **未做（历史用户决策）**：C-5⑥⑦、Sprint 7 生物识别/自建中继 E2EE、P2-3 余项。
- **工程债（随手可清，非阻塞）**：`sessionCacheStore` 会话正文缓存仍是明文（任务书 §11.6 目标为加密，
  属「0.6 安全门」遗留）；`detekt-baseline.xml` 105 条存量告警；`tools/` 下 11 个 `_` 前缀一次性脚本；
  全库 0 个 `@Preview`；界面文案硬编码中文、无 `strings.xml`（国际化缺失）。
- **若其他会话接手**：先读本文件 → `HANDOVER.md` 文首覆盖层 → `CHANGELOG.md` 最新条目，
  再按「§2 核心命令」跑一次 `testDebugUnitTest` 建立基线。

## 6. 关键避坑与运行约束

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
