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
- **当前版本**：`0.5.0-beta46`（versionCode 58）；上一个发布 tag `v0.5.0-beta45`。
- **⚠️ 进行中的工作（未发布）**：**国际化资源化**——已完成**第 1 批**（`UiText` 地基 + `ToolKindLabels`），
  仍在 `master` 上（未打 tag，**默认语言仍为中文、行为零变化**）。后续 5 个批次与详细进度见
  §5「进行中的工作」与 [CHANGELOG.md](CHANGELOG.md) 的「未发布（进行中）」段。**接手请先读该段。**
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

> 完整逐轮记录见 [CHANGELOG.md](CHANGELOG.md) 与 [HANDOVER.md](HANDOVER.md) 文首覆盖层。
> 最近五轮均为质量修复：**v0.5.0-beta46（弃用 API 清理，编译警告归零）**、
> **v0.5.0-beta45（工程清债：@Preview / 脚本索引 / 文档可移植化）**、
> **v0.5.0-beta44（会话正文缓存加密）**、**v0.5.0-beta43（草稿隔离 + 倒计时实时化）**、
> **v0.5.0-beta42（通知权限 / 网络回调 / 旋转状态）**。

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

### 🔄 进行中的工作：国际化资源化（第 1/6 批已完成）

**目标**：把散落的中文字面量抽到 `res/values/strings.xml`，实现「文案集中管理 + 未来可翻译 +
可加防硬编码门禁」。**不是**做英文界面（用户已明确无英文需求）；**默认语言仍为中文，行为零变化**。

**规模实测**：用户可见中文串 **561 条**（`ui/` 365、`relay/` 138、`repo/` 62、`notify/` 30）；
其中 **~140 条在纯函数层**、**107 条含 `$` 插值**（需改签名/传参）、仅 **41 条**在集中式对象里。
日志（96 条）与协议常量/诊断码**不进** strings.xml。

**已完成（第 1 批）**
- `app-android/app/src/main/java/com/zcode/remote/util/UiText.kt` — 文案载体抽象
  （`Res(资源id, 参数)` / `Raw(运行期文本)`；`asString()` 用于 Composable、`resolve(context)` 用于通知/Widget）。
- `app-android/app/src/main/res/values/strings.xml` — 公共文案 + 工具家族标签（中文）。
- `ui/components/ToolKindLabels.kt` — `label`/`runningLabel`/`statusLabel`/`primaryText` 返回 `UiText`。
- `ui/screens/ConversationTimeline.kt` — 消费点经 `asString()`；`primaryText` 拆出 `searchPrimaryText`。
- `detekt-baseline.xml` — `label` 条目签名更新（`: String`→`: UiText`），条数不变。

**后续批次（每批独立提交 + 全门禁，可单独回退）**

| 批次 | 范围 | 条数 | 要点 |
|---|---|---|---|
| 2 | `ui/components/StatusPanelLabels.kt` | 24 | 纯函数，照第 1 批模式（返回 `UiText`，测试断言改资源 id） |
| 3 | `relay/UserFacingError.kt` | 17 | **最微妙**：`LOCAL_ERRORS`/`FAULT_ERRORS` 映射表改为「英文键 → 资源 id」；`UserFacingErrorTest` 同步改 |
| 4 | `ui/screens/` 各屏 | 365 | 最大头，建议按屏分（HomeScreen 59 / SettingsTab 66 / ConversationTopBar 27 / KeepAliveGuideScreen 20 …） |
| 5 | `notify/` + `repo/` + `widget/` | ~93 | 有 `Context`，用 `UiText.resolve(context)`（注意通知构建在非 Compose 上下文） |
| 6 | `relay/` 用户可见错误文案 | ~46 | **逐条甄别**：`ConversationChannel` 里同文件混有用户可见串与日志/协议串，只搬前者 |

**实施注意**（踩过的坑）
- 纯函数返回类型改 `UiText` 会**连带改测试断言**（本项目无 Robolectric，这是必要的代价，不是回归）。
- 容器类 `when` 加 `res(...)` 包装会抬高 **detekt CyclomaticComplexity**；把子分支抽成私有函数
  （如 `searchPrimaryText`），而不是加 `@Suppress`。
- `UiText.asString()/resolve()` 的 `*args` 展开触发 **detekt SpreadOperator**，已就地 `@Suppress` 并注明理由。
- import 顺序：`ktlintFormat` 会自动修正（`com.zcode.remote.R` 的位置）。
- **每批做完必须**：`ktlintCheck detekt testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease`
  全绿 + `python tools/check_test_count.py --min 260`（现为 287）。

**决策待定**：全量做（6 批，为「可能永远不来的英文需求」做全量工程）vs 只做批次 2–3（纯函数标签层，
收益是集中管理 + 可加门禁，风险最低）。用户倾向与后续取舍见 CHANGELOG「未发布（进行中）」段。

### 其他待办

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
- **工程债（随手可清，非阻塞）**：`detekt-baseline.xml` 105 条存量告警；`tools/` 下 11 个 `_` 前缀一次性脚本。
  ~~会话正文缓存明文~~（beta44 已加密）、~~全库 0 个 `@Preview`~~（beta45 已补 debug 源集预览）、
  ~~界面文案硬编码中文、无 `strings.xml`~~（i18n 资源化第 1/6 批已合入，进行中）。
- **若其他会话接手**：先读本文件 → `HANDOVER.md` 文首覆盖层 → `CHANGELOG.md` 最新条目，
  再按「§2 核心命令」跑一次 `testDebugUnitTest` 建立基线。

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
> **本次已修（2026-10-15，#3/#5/#9）**
> - **#3 两处 `!!`**：`ConversationChannel` 的读循环改取不可变快照 `stream`（原 `input!!` 在 abort
>   重入路径可 NPE）；`HomeScreen` 的 `s.lastError!!` 改 `errorReason` 局部非空值。
> - **#5 重连抖动**：新增纯函数 `jitteredBackoffMs(attempt, factor)`（只缩短不延长，区间
>   `[base/2, base]`，不破坏 48s 封顶承诺），调用点改用之；补 `PureFunctionsTest` 断言。
> - **#9 旧明文回写迁移**：新增纯函数 `legacyRewriteTarget`，`SessionCacheStore` 读路径命中旧明文时
>   经 `migrationScope`（IO）+ `writeMutex` **异步原子回写**为密文，并**保留原 mtime**
>   （刷新时间戳会让过期文件被 TTL 误判为新鲜）；加密失败绝不回写明文。
>   **含竞态防护**：迁移前在锁内重新比对文件内容，若已被 `save()` 更新则放弃迁移
>   （否则会用读取时捕获的旧明文覆盖刚写入的新数据）。补 `CacheCryptoTest` 3 项。

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

4. **`OkHttpClient` 未复用**：`RelayClient`、`repo/UpdateRepository.kt:62` 各自新建实例，无共享
   连接池/调度器。建议单例注入。
5. ~~**重连退避无 jitter**~~（**✅ 2026-10-15 已修**）：`RelayClient` 退避封顶 `3s * 2^4 = 48s` 固定值，多设备同断网重连形成
   雷群。建议 `backoff * (0.5 + Random.nextDouble() * 0.5)`。
6. **自建 `CoroutineScope` 未绑定生命周期**：`RelayClient`、`service/RemoteConnectionManager.kt`
   均 `CoroutineScope(SupervisorJob() + Dispatchers.IO)`，全靠手动 `close()`，泄漏路径上心跳/重连
   Job 不取消。建议注入 ApplicationScope 或审计全部 close 调用点。

**P1 安全补齐（beta44 加密轮后的残余缺口）**

7. **无证书固定**：`wss://zcode.z.ai/ws` 承载配对 proof，当前完全依赖系统 CA，Manifest 仅
   `usesCleartextTraffic=false`、无 `networkSecurityConfig`。建议加 `CertificatePinner` 或显式
   `networkSecurityConfig` 声明（注意与官方证书轮换策略兼容，留后备 pin）。
8. ~~**备份策略未声明**~~（**❌ 已不成立**：Manifest 已有 `android:allowBackup="false"`）：Manifest 未见 `android:allowBackup="false"` 或 `dataExtractionRules`。
   密文 + Keystore 密钥别名被备份还原到他机后无法解密——安全但用户会「安全地丢数据」。
   建议显式配置备份排除规则并写入文档。
9. ~~**旧明文缓存无回写迁移**~~（**✅ 2026-10-15 已修**）：`storage/SessionCacheStore.decodeCacheText` 对 beta44 前的旧明文
   **永远原样返回**，没有「读出后重写为密文」的回写路径，旧文件不删则明文无限期存在。
   建议首次成功双读后立即以密文重写。
10. **`TokenStore.read()` 明文 String 长驻内存**：建议改 `CharArray` 或调用方即用即清。

**P2 体验体系化**

11. **加载/空态信息量不足**：会话页「握手中…/订阅中…」无已等待秒数与阶段进度（任务书 §7
    已要求未落地）；首页错误态仅一行红字（`HomeScreen.kt:462`）无重试按钮；会话为空无引导文案。
12. **无障碍覆盖薄弱**：全 ui/ `contentDescription` 仅 22 处（其中 11 处是 `= null` 装饰图标），
    `semantics` 修饰符几乎不用，TalkBack 下审批按钮/状态胶囊/会话行基本不可达。
13. **Markdown 禁外链图片的兜底体验**：图片类回答仅一行灰字占位，建议加「复制链接 / 浏览器
    打开」快捷操作（可复用现有 `SafeUriHandler`），不改变不加载外链图片的安全决策。
14. **会话流缺日期分隔与未读标记**：长会话无时间锚点，定位困难。
15. **布局回归自动化防线**：C-14 类顶栏布局错乱此前只能靠真机人肉发现，§11.2 仪器化布局断言
    刚起步，建议优先补「关键组件祖先链 + 边界数值」断言（对齐 AGENTS.md「DOM 断言须验证祖先链」
    规范）。

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
