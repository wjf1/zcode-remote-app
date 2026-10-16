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
- **当前版本**：`0.5.0-beta50`（versionCode 62），tag `v0.5.0-beta50`（本地已打，**未推送**——推送时本机代理 7900 无监听）；上一个发布 tag `v0.5.0-beta48`。
- **⚠️ 待推送（本地已提交+打 tag）**：`v0.5.0-beta49` 与 `v0.5.0-beta50` 两个 tag 已在本地产出，
  **因 GitHub 不可达（代理 7900 无监听、直连 github.com:443 被拒）未能推送**；恢复网络后
  `git push origin master && git push origin v0.5.0-beta49 && git push origin v0.5.0-beta50` 即可
  （见 §7「推送受阻」条）。
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

> 完整逐轮记录见 [CHANGELOG.md](CHANGELOG.md) 与 [HANDOVER.md](HANDOVER.md) 文首覆盖层。

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
- **工程债（随手可清，非阻塞）**：`detekt-baseline.xml` 105 条存量告警；`tools/` 下 11 个 `_` 前缀一次性脚本。
  ~~会话正文缓存明文~~（beta44 已加密）、~~全库 0 个 `@Preview`~~（beta45 已补 debug 源集预览）、
  ~~界面文案硬编码中文、无 `strings.xml`~~（**i18n 资源化 6 批已全部完成并随 beta47 发布**，见上方「已完成」段）。
- **若其他会话接手**：先读本文件 → `HANDOVER.md` 文首覆盖层 → `CHANGELOG.md` 最新条目（beta49），
  再按「§2 核心命令」跑一次 `testDebugUnitTest` 建立基线（当前应为 **347/347**）。

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
| #2 附件上传流水线重构（递归回调 → suspend/flow） | ✅ **已完成**（beta50）：顺序 suspend 流水线 + 可定位源（O(1) 续传）+ 阻塞读下放 IO 线程 + 11 项 JVM 单测。**真机按协议层验收**（SAF 开流路径待补，见 §4 beta50） |
| #12 无障碍 | ✅ 已修（`8725bad`）：会话卡语义 / `StatusDot` / 设置入口 Role |
| #13 Markdown 图片兜底 | ✅ 已修（`c7862fd` 首次；**beta48 版本实为死代码，`beta49` 重修为真修**）：复制链接 / 打开（**原判「库 API 不可达」不成立**——URL 可得，不可达的是 alt 文本）。**beta49 更正**：兜底改挂段落/引用块级（`components.image` 在库中永不触发），URL 取自 AST 节点 |
| #14 会话流日期分隔 | ✅ 已修（`2a9efdd`）：按本地时区日历日（**原判「协议无绝对时间戳」用错字段**——`createdAt` 是行级绝对时间戳） |
| #15 布局回归断言 | ✅ 扩展（`d906da0`）：输入栏高度 + 会话流未被挤压 |

**已闭环**：#1–#15 全部闭环（含 #7 证书固定为作者刻意不做、#8 不成立）。
**发布状态**：#12–#15 随 **v0.5.0-beta48 发布**；#13 的死代码由 **v0.5.0-beta49 修复**；
#2 由 **v0.5.0-beta50 完成**。beta49/beta50 两个 tag **本地已打、因 GitHub 不可达暂未推送**（见 §1 与 §7）。

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
