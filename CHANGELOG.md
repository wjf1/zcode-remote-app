# 变更记录 / Changelog

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
