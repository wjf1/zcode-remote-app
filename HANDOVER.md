# 交接开发计划（HANDOVER）

> ## ✅ 项目状态（2026-10-09）：beta19 已发布 + 本地「未发布四批」已合并入 master（153 项单测全绿）
>
> - **当前版本 `v0.5.0-beta19`（versionCode 29）**：代码完成、debug/release 构建通过、**116 项单测全绿**；修掉观测期真机反馈的「待处理项跨会话泄漏」（会话页内联的审批/提问卡改为按当前会话过滤）。真机冒烟已过，**跨会话 A/B 的真机证据待补**（需一条真实待处理项，见下方观测期反馈）。上一版 beta18（代码块高亮修复）模拟器 + 真机双轨验收均通过。
> - **master 已合并本地未发布工作（2026-10-09，见下方 🆕 四批段）**：A-3 实测启用（103 静态实证 + probe 双验证 + **真机 20 次切会话 20 条 dispose，验收通过**）、C-1/C-2/C-4/C-6/C-7/C-8/C-10/C-11、C-5①、桥看门狗可测试化、CI 断言；**合并后单测 153 项全绿**（本地 144 + 云端 9，`check_test_count.py` 核对 153/153）。合并冲突 4 处（MainActivity 保留本地传参 + 云端会话过滤、三文档双方段落全保留、坑清单重编号 15-19/20-27）已解。**未打 tag、未推送**（等用户指示）。
> - **最近发布**：tag `v0.5.0-beta19` @ 提交 `e7c65d0`；Release <https://github.com/wjf1/zcode-remote-app/releases/tag/v0.5.0-beta19>（附 `ZCodeRemote-0.5.0-beta19.apk`，签名 release 包，versionCode 29）。上一个发布：tag `v0.5.0-beta18` @ 提交 `a82570a`（<https://github.com/wjf1/zcode-remote-app/releases/tag/v0.5.0-beta18>）。
> - **beta18 内容（本地验证驱动，非用户报障）**：为清掉 beta17 验收遗留的「围栏代码块与 GFM 表格没有样本」空洞，在模拟器上补**仪器化渲染回归网**，测试首轮即抓出**用户可见缺陷** —— 高亮库的 `ColorHighlight.rgb` 是纯 RGB，被 Compose `Color(Int)` 按 ARGB 解释后 alpha=0，**代码块里被高亮的字符被画成完全透明**（关键字/字符串/注释整段消失）。修复见 `ui/components/MarkdownView.kt` 的 `opaqueHighlightArgb`；证据 `docs/screenshots/render-code-block-before-fix.png` → `render-code-block.png`（token 色命中 0 → 1789）。**beta17 的 APK 含此缺陷**。
> - **全部后续开发计划仍取消**（用户决策：Sprint 7 生物识别/自建中继 E2EE、P2-3 余项等一律不做）。
> - **唯一剩余事项**：P0-2 三天日常使用观察 → 通过即发 **v1.0**（详见 §6.0 待办总览 + §6.0 P0-2 章节）。
> - 接手者默认职责：**维护与缺陷修复**，不再新增功能；如需演进须用户重新拍板立项。
>
> ### 🩹 补丁与对齐轮次（v0.5.0-beta13 → beta18）
>
> **beta13**（缺陷修复，依据《体验提升任务书 v2》档 A/B）：A-1 上传跨会话附件注入、A-2 握手三跳无超时（阈值由 T0 实测校准为 4s/4s/5s）、B-1 贴底回归与「回到底部被翻页锚点覆盖」新缺陷、B-3 触觉与失败时长档位。
> **beta14**（新增功能，**用户直接提出**、超出档 A/B 范围）：会话流渲染用户消息的附件 chip。
> **beta15**（连接层缺陷修复，**用户真机反馈**驱动）：桥握手无超时导致「已连接却永久卡在会话报错」、`bridge not ready` 未判可重试、网络恢复白等一整轮退避（~47s）。
> **beta16**（用户真机反馈 + 截图）：会话异常原因可见（快照 `control.lastError` + 列表回填）、标题双重序列化解包（host 数据瑕疵，tasks-index 实锤）、输入栏对齐（任务书 B-2）、顶栏 phase 标签中文化（`error` → 「异常」，与列表标签一致）。
> **beta17**（用户直接提出「按桌面端设计会话页显示与排版」）：会话页排版三段式对齐（设计基座 / 行渲染重排 / 会话级状态面板）+ 「运行中」状态回填修复；新增依赖 `multiplatform-markdown-renderer-m3/-code:0.27.0`。
>
> **🆕 未发布轮次（2026-10-09，v1.1 首批「用户可感知收益」）**：用户拍板推进此前 `[需立项]` 的档 C 可感知条目。本轮落地 **C-6** 发送本地回显（pending 气泡）、**C-1** 上传可取消/失败可重试（**uploadId 复用命中服务端幂等**）、**C-10** 深链配对确认弹窗、**C-11** 反馈横幅合并单队列（修 ApprovalsTab 文案滞留）、**C-2** 错误文案映射层（`UserFacingError`，14 处）、**C-4** 浅色主题对比度修正 + 思考折叠摘要（含 CI 对比度断言 `ContrastTest`）、**C-7** 缓存文件删除与 LRU。代码 + **134 项单测全绿**（含 +27 新增）+ debug/release 构建通过。**⏳ 真机验收未做（设备不在线）**；**⚠️ 本机 release keystore 缺失**（`toolchain/` 仅剩 `avd/`，`keystore.properties` 不存在）→ 两项都解决前**不 tag、不推送**。详见 `CHANGELOG.md`「未发布」段。
>
> **🆕 同日第二批（无设备可验证项，用户指示「按建议执行」）**：**C-8** 多机凭据改 `storage/DevicesCodec.kt` 结构化序列化（旧格式读取后一次性迁移）；**A-3 前置**（`encodeEventDispose` 103 编码 + `disposeEvent` 发送入口 + 切会话/重置调用点，`SEND_EVENT_DISPOSE=false` 门控待实测）；**A-4 前置**（`hasSeqGap` 缺口检测 + WARN 日志，重订阅与 buffer 改动仍按高危待真机）；**C-2 收尾**（CI 加「UI 层不得出现裸英文错误串」grep 断言）。
>
> **🆕 同日第三批（C-5 静态部分 + 看门狗可测试化）**：**C-5①** `AppViewModel` 的 `ev.data.toString()` 观测代码整段移入 `BuildConfig.DEBUG` 门控（release 不再为每个 delta 做全量 JSON 序列化——R8 只剥离日志调用、不剥离实参求值）；**桥看门狗**到点决策抽成纯函数 `RpcChannel.watchdogDecision`（Noop/Retry/Fail）+ 单测——闭合 beta15「重开用尽 → 可见失败」真机未能构造的验收缺口；CI 增「`ZLog.e` 只写元信息」断言。三批合计单测 **144 项全绿**（107 → 144）。**C-5 其余（③④⑤⑦）与 C-12 仍未做**：改 Compose 缓存语义 / 并发路径，须真机观察 + 独立灰度。
>
> **🆕 同日第四批（A-3 实测确认并启用）**：用户打开桌面端「移动端远程控制」面板后，用 `tools/probe.py` 完成 A-3 的**静态 + 动态双确认**：① 官方 bundle 实证字段规格 = `[103, id]` + undefined 参数段（已修正 App 侧编码——原实现缺参数段）；② `dispose` 实测：活跃会话 dispose 前 12s 收 8 帧、发 103 后 12s **0 帧**（服务端静默接受）；③ `dispose-stress 10` 实测：10 次 subscribe→dispose 后观察窗只有最后 1 个 listen_id 在收（**无 N 倍放大**，对应验收口径）。`ConversationChannel.SEND_EVENT_DISPOSE` 已置 **true**。探针两处修复（websockets 17 的 proxy 默认读系统代理导致 ConnectionRefused、必须带 `ZCODE_MID`）见 §3。
>
> ⚠️ **本机模拟器实测定论不可用（2026-10-09 复核）**：emulator 二进制 / AVD（test35）/ system-image 齐备，但启动即退出——原文 `ERROR | x86_64 emulation currently requires hardware acceleration! ... Your CPU: 'CentaurHauls'`（兆芯 CPU 无 Intel/AMD 虚拟化扩展）。**UI 类验收仍只能真机**；换 Intel/AMD 机器可解锁「视觉/布局/空态」类验证，但触觉、通知策略、真实会话仍须真机。
>
> **⚠️ 真机验收阻塞（2026-10-09，签名不匹配 · 待用户决策）**：手机（`9f6241b4`）已连上 ADB，但装机被拒——手机上 beta17 的签名指纹 `3A:B5:8F:…:1C:47`，与本机当前 `~/.android/debug.keystore`（`89:45:76:…:CB:B0`，文件时间 9-28）**不一致**（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`）。**已全盘搜索确认本机只有这一个 keystore**；历史记录的 release keystore（`1D:46:E9`，供 Release APK 覆盖升级）同样不在本机。
> **影响**：① 本批（四批合计）改动无法装机验收 → 不能打 tag / 推送 / 发 Release；② 未来发布的签名包也无法覆盖升级已装设备（除非找回 `1D:46:E9`）。
> **可选路径（待用户决策）**：**(a)** 找回旧 keystore（`3A:B5:8F` 或 `1D:46:E9` 任一，可能在其他机器 / 备份盘 / 回收站 / toolchain 备份）→ 覆盖安装、不丢配对凭据（最优）；**(b)** 卸载重装（**破坏性**：丢失 App 配对凭据，需用面板二维码重新扫码配对）；**(c)** 给 debug 构建加 `applicationIdSuffix`（如 `.dev`）→ 改包名并存安装，**不碰旧包**，但仍需重新配对（新包无数据）；**(d)** 暂不装机。
> **附实测**：`adb install --user 999`（小米 XSpace 分身空间）**同样被拒**——Android 的签名一致性校验是**设备级**的，同包名无法在任意用户空间共存不同签名版本；并存安装只能靠改包名。
>
> **真机验收清单（2026-10-09 批 · 2026-10-10 补完，全部执行）**
> 1. **C-6 回显** ⏳ 部分通过：发送成功 → 会话流回显 → **pending 气泡不残留**；「待发送 N 条」队列条正常。发送瞬间（<1s 窗口）未直接取证。
> 2. **C-1 上传** ✅ **通过**：① 全链路 `attachmentBegin → 14 分片 → commit → ref=zcode-artifact://…`（5.2MB / 16s）；② 「上传中 … (%)」进度与「取消」按钮渲染正确；③ **取消中止**实证 `attachmentAbort sent uploadId=…`（3 秒时点取消，无后续 commit）。
> 3. **C-10 深链** ✅ **通过**：确认弹窗（标题 / 设备名 / 影响说明）+ 取消不改变连接。
> 4. **C-2 文案** ✅ **通过**：断网发送 → 横幅「发送失败：**连接通道尚未就绪，请稍后重试**」——底层 `bridge not ready` 英文串未直出 UI（UserFacingError 映射的真机实证）。
> 5. **C-4 浅色主题** ✅ **通过**：切浅色后页面正常渲染，截图留档 `_tmp/verify_light_theme.png`（对比度由 `ContrastTest` 静态断言守）。
> 6. **C-7 缓存** ✅ **通过**：打开会话生成 `rows_<sid>.json`；**LRU 上限实证**——注入 35 个假文件（共 36）后触发一次写入 → 精确收敛 **30** 个（保留最近修改者）。删除会话清缓存未直接验证（避免删真实会话，由单测覆盖）。
> 7. **C-11 横幅** ✅ **通过**（与第 4 项同一次取证）：横幅出现（连续两次 dump 可见）→ **自动消退**（第三次 dump 已消失、无滞留）——单队列改造生效。
> 8. **A-3 退订** ✅ **通过**：交替切会话 20 次 → 20 条 `rpc dispose listenId=…`（47→173 递增），与 probe 服务端双验证形成证据链。
> 9. **副作用记录**：① 经 App 发送 2 条测试消息（「继续」，已进服务端队列、协议无撤回）；② 上传过 1 次 5.2MB 测试文件（1 次 commit 成功未随消息发出、1 次被取消，服务端 artifact 未被引用）；③ 测试文件与假审批通知已清理；④ 手机多次掉线/息屏（验收中途），已恢复。
>
> | 项 | 状态 |
> |---|---|
> | A-1 上传跨会话注入 | ✅ 代码 + 单测 + **用户人工确认无问题** |
> | A-2 握手超时 | ✅ 代码 + 单测 + **真机全通过**（4.5s 出可读失败 / 一次自动重订 / 重试入口 / 恢复自动对齐） |
> | B-1 贴底 | ✅ 代码 + 单测 + **真机四场景全通过** |
> | B-3 触觉 / 横幅时长 | ⏳ 代码 + 单测（时长契约锁定 8s/4s）；触觉与横幅停留**需人工感知确认** |
> | beta14 附件 chip | ✅ 代码 + 单测 + **真机端到端通过**（真实走通「选文件 → 发送 → 流内显示 chip」） |
> | beta15 连接层三修 | ✅ 代码 + 单测；**网络恢复延迟真机 PASS（80s 断网→5s / 150s 断网→3s，旧版 ~47s）**；桥看门狗**健康路径非回归 PASS**（127ms 就绪、无误触发）；「桥重开用尽→可见失败」因需人为丢帧**未能构造**，仅代码推理覆盖 |
> | beta16 四修 | ✅ 代码 + 单测（47 项全绿）+ **真机四项验收全通过**：① 错误原因横幅逐字等于 host `lastError.message` 且「复制」Toast 生效；② 列表/顶栏标题不再泄漏 `{"title":`；③ 输入栏三控件垂直中心偏差 **0px**、控件 135px = 48dp；④ error 会话顶栏由英文 `error` 变「异常」 |
> | beta17 排版对齐 + 状态面板 | ✅ 代码 + **107 项单测全绿** + debug/release 构建通过 + **真机验收全通过**：① 「运行中」退出会话页后仍显示在列表（原始缺陷场景闭环）；② 排版像素实证 —— 工具行为无外框内联行（行带即页面底 `#202024`）、全图**琥珀色 0 像素**、用户气泡 `#37373A` 中性底 + 1px 边框、助手正文全宽无外壳、增删徽标深绿 `#253730`/深红 `#41292D`、思考紫 `#A78BFA`；③ 状态面板六分区全渲染（上下文 `78.9k/150k` + 缓存命中 `97.6%`、目标 `paused` 已运行 1 小时 17 分、进程 `6/6`、终端「1 个后台运行」、智能体「0 运行 · 4 已结束」）；④ C1 增量实证：顶栏状态百分比同会话内 `53%→56%→63%→66%` 递增；⑤ `logcat` 无异常、无 Compose 嵌套滚动告警 |
>
> | beta18 代码块高亮修复 + 渲染回归网 | ✅ 代码 + **111 项单测全绿** + debug/release 构建通过 + **模拟器仪器化测试 2/2 PASS** + **真机复核通过（2026-10-09）**：① 缺陷复现 —— 修复前位图里「着墨正常但主题 token 色命中 0」，截图可见 `fun`/`val`/字符串/注释整段缺失；② 修复后 token 色命中 **1789** 像素（`#2BBAC5` 青 / `#D55FDE` 品红 / `#89CA78` 绿）；③ GFM 表格样本九项文本断言 + 着墨像素断言通过；④ **真机复核**：小米 15 Pro 装机（卸载重装 + 重扫配对）→ 打开会话「整理 commandcode-proxy 发行版」→ ` ```bash ` 代码卡完整渲染且高亮分色，**token 色命中 2365 像素**、肉眼可见逐段着色，未再「吞字」 |
> | beta19 跨会话泄漏修复 | ✅ 代码 + **116 项单测全绿** + debug/release 构建通过 + 真机冒烟通过（装机 `versionCode 29`）；会话页内联卡按当前会话过滤，`sessionId==null` fail-open；待办页/通知栏维持跨会话聚合。**跨会话 A/B 真机证据待补**（当前工作区无 pending 项可复现；已确认 `DebugApprovalReceiver` 只作用于通知层，无法替代） |
>
> - **验证状态（beta18）**：`assembleDebug` / `testDebugUnitTest` / `assembleRelease` 三条 BUILD SUCCESSFUL；`tools/check_test_count.py` 核对 **声明 111 项 = 实际执行 111 项**（含新增 `ui/components/CodeHighlightTest.kt` 4 项）；`connectedDebugAndroidTest` 在模拟器 `apkrev35`（Android 15 / x86_64，AEHD）**2/2 PASS**。仪器化测试**不进 CI**（runner 无设备），只在本地模拟器/真机上跑。
> - ⚠️ **踩坑（勿重犯）**：beta13 轮次中一个 `@Test` 因被挤进行注释而**静默未执行**（构建仍全绿），导致当时「39 项全绿」实际只跑了 38 项。**任何「N 项单测全绿」的结论都必须同时确认 N == 实际执行的用例数**（跑 `tools/check_test_count.py`）。
> - ⚠️ **跨 adb 做文本检索不可靠**（引号转义丢失会返回假阴性），一律先把文件 `pull` 到本地再解析；`grep` 检索代码用 `-F`（`.` 是任意字符）。
> - ⚠️ **release（R8）包与 debug 包签名不同**（debug `3A:B5:8F…` / release `1D:46:E9…`）：手机上若已装 debug 包，`adb install -r` release 包会 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，需先卸载——**会丢失配对凭据**，属破坏性操作，动手前必须取得用户同意。beta16 的 release 包因此**未做真机冒烟**，上机验收用的是同源 debug 包。
> - ⚠️ **观察窗口**：按 §6.0 P0-2 规则，v1.0 判停线自补丁/对齐轮**真机验收通过之日**重新计时。beta19（跨会话泄漏修复）于 **2026-10-09** 装机，窗口自该日起重新计时（预计 2026-10-12 收官）；观察手机已升级到 **beta19**（debug 包，versionCode 29）。
> - ⚠️ **实测系统为 Android 17 / HyperOS**（旧记录为 Android 15）：跨两个大版本，接入时须把 USB 用途切到「传输文件」才暴露 ADB 接口。P0-2 第 2/3 项依赖 HyperOS 后台与通知策略，跨版本升级后务必重新观察。
> - **未做项现状（2026-10-09 更新）**：**档 C 首批 7 项 + A-3 + 无设备可验证项（C-8、A-4 前置、C-5①、看门狗可测试化）已落地**。**仍未做（有依据，勿当遗漏）**：**A-4 自愈与 buffer 策略** 与 **C-5 其余（③ rows 拷贝 / ④ Compose 解析缓存 / ⑤⑦ 并发与快照差分）**（改并发与 Compose 缓存语义，须真机观察 + 独立灰度）、**C-3**（emoji 图标替换需先评估 `material-icons-extended` 包体；键盘 inset 根因须真机量测）、**C-9/C-12**（加密存储需 Android Keystore 运行时验证；拆分为二期）、决策项（ws:// 明文中继、reverseLayout）。
> - 🆕 **验证副作用（需知悉）**：验证附件 chip 时向真实会话 `sess_0f00b96b`（「zcode-dotfiles 优化方案可行性确认」）写入了一条测试消息（`attachment-render-check` + 89B 测试文件）。核对 `tasks-index` 确认**未调度 agent 轮次、未消耗额度**；协议无删除消息操作，无法程序化清理。
>
> **真机验收清单（beta13/beta14/beta15）**
> 1. ✅ **A-1**：**用户已人工确认无问题**（双会话切换不误注入）。
> 2. ✅ **A-2 PASS**：飞行模式 + 切会话 → **4.5s** 出可读中文失败 + 顶栏「重试」；一次自动重订；关飞行模式自动对齐。
> 3. ✅ **B-1 PASS**（四场景 + 修复新缺陷）。
> 4. ⏳ **B-3**（需人工感知）：上传成/败触觉、会话内审批按钮触觉；「附件上传失败」横幅停留 8s。
> 5. ✅ **T0 PASS（已回填）**：整链路 512–740ms，A-2 阈值据此校准。
> 6. ✅ **beta14 附件 chip PASS**：发送带附件的消息后，chip 出现在气泡上方（y=1083）而输入栏已清空。
> 7. ✅ **beta15**：① 「会话页永久 `bridge not ready`」由**三重机制**覆盖（桥握手看门狗 + `bridge not ready` 判可重试 + 桥 Ready 事件重订），健康路径非回归已验（127ms 就绪、看门狗无误触发），**原始故障未能复现**（需人为丢帧）；② **飞行模式往返恢复延迟真机 PASS**：断网 80s → **5s**、断网 150s → **3s**（旧版同场景 ~47s），恢复后会话自动重新订阅；③ 桥重开 3 次用尽后的可见失败**未能构造**（需人为丢弃 `workspace-bridge-ready`，App 外部无法制造）。
>
> ⚠️ **beta15 的修复被真机测量推翻过一次**（首版断网 80s PASS 但 150s FAIL 46s）——根因是 `probeNow()` 假阳性使 `available` 残留 true、以及我自己的状态守卫因 `onFailure` 不改 `_state` 而永远提前 return。完整复盘见 `CHANGELOG.md` 的 beta15 条目。**教训：恢复路径不能依赖「失败路径并不维护」的状态标志。**
>
> **面向接手的 AI agent**：本文自包含。拿到本仓库 + 本文档即可直接开工，无需原会话上下文。
> 文中所有路径相对仓库根 `F:\AI\Zcode\zcode-remote-app`（Windows，Git Bash）。
> 配套必读文档见 §2，环境与命令见 §3。**§6 中的 Sprint/P2 各节为历史存档，仅 P0-2 观察项仍有效。**

---

## 1. 项目一句话与现状

**ZCode Remote**：ZCode 官方远程控制（`wss://zcode.z.ai/ws` 中继）的原生安卓增强客户端，Kotlin + Jetpack Compose。目标机型小米 15 Pro（HyperOS 2 / Android 15），纯自用暂不分发。

- 协议已完整逆向并实测（M0），App 的配对/会话/审批/多机/设置全部打通并验证（M1 ✅ M2 ✅ M3 主体完成）。
- 已发布签名 Release（私有仓库 `wjf1/zcode-remote-app`，`gh` CLI 已登录账号 wjf1）：
  [v0.3.0-m3](https://github.com/wjf1/zcode-remote-app/releases/tag/v0.3.0-m3)（首个签名 Release）→
  [v0.4.0-beta1](https://github.com/wjf1/zcode-remote-app/releases/tag/v0.4.0-beta1)（发版收官内测）→
  [v0.4.0-beta2](https://github.com/wjf1/zcode-remote-app/releases/tag/v0.4.0-beta2)（真机验收问题修复）→
  [v0.4.0-beta3](https://github.com/wjf1/zcode-remote-app/releases/tag/v0.4.0-beta3)（16KB 页对齐修复）→
  [v0.4.0-beta4](https://github.com/wjf1/zcode-remote-app/releases/tag/v0.4.0-beta4)（互踢死循环修复）→
  [v0.4.0-beta5](https://github.com/wjf1/zcode-remote-app/releases/tag/v0.4.0-beta5)（相机扫码重构与修复）→
  [v0.4.0-beta6](https://github.com/wjf1/zcode-remote-app/releases/tag/v0.4.0-beta6)（排版全面对齐桌面版+版本展示与更新闭环，versionCode 10）→
  [v0.5.0-beta1…beta5](https://github.com/wjf1/zcode-remote-app/releases/tag/v0.5.0-beta5)（3-Tab 架构重构 + 模型思考档位链路，versionCode 15）→
  [v0.5.0-beta6](https://github.com/wjf1/zcode-remote-app/releases/tag/v0.5.0-beta6)（三个 P0 缺陷结清 + 最近文件只读预览 + 工具调用 Diff 视图，versionCode 16）→
  [v0.5.0-beta7](https://github.com/wjf1/zcode-remote-app/releases/tag/v0.5.0-beta7)（会话流智能贴底 + 悬浮「回到底部」+ 输入栏快捷指令胶囊，versionCode 17）→
  [v0.5.0-beta8](https://github.com/wjf1/zcode-remote-app/releases/tag/v0.5.0-beta8)（系统通知栏 RemoteInput 内联快捷回复 + 系统级 Share Sheet 分享接入，versionCode 18）→
  [v0.5.0-beta9](https://github.com/wjf1/zcode-remote-app/releases/tag/v0.5.0-beta9)（单轮 Turn 变更文件聚合面板 Turn Diff Summary，versionCode 19）→
  [v0.5.0-beta10](https://github.com/wjf1/zcode-remote-app/releases/tag/v0.5.0-beta10)（配对链接 Deep Link 一键唤起 + 系统交互触觉反馈，versionCode 20）→
  [v0.5.0-beta11](https://github.com/wjf1/zcode-remote-app/releases/tag/v0.5.0-beta11)（会话离线持久化与冷启动秒开 SessionCacheStore，versionCode 21）→
  [v0.5.0-beta12](https://github.com/wjf1/zcode-remote-app/releases/tag/v0.5.0-beta12)（**当前**，会话消息流离线持久化与点进秒开，versionCode 22）。
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
- **2026-10-02 七轮（本轮接力）：外部审查报告评审通过，P0 修复周期启动**——
  对《提升空间分析与开发计划》（基于 v0.5.0-beta5 全量代码审查 + 12 竞品 2026-10 生态扫描）
  做实证抽查：抽验 10 条关键断言（YOLO 硬编码、ConnectionService 死代码、release 日志泄露、
  proguard 文件缺失、零测试、三个千行文件、file 通道读穿工作区外文件、RemoteInput 全仓 0 命中等）
  **全部命中且行号与代码精确一致**，报告可信，采纳其 Sprint 0–3 计划。
  本轮范围：Sprint 0（文档清账）→ Sprint 1（P0-A 前台服务 / P0-C 日志治理 / B 组连接韧性）→
  Sprint 2（P0-B 执行模式选择器 + 终态通知）→ Sprint 3 第一步（diff 视图，纯客户端零协议）。
  详见 §6.1。后续接力按 §6.1 表格继续。
- **⚠️ 2026-09-30 范围决策（用户拍板）**：**M4（P2-1）/M5（P2-2）移出开发计划**，
  VPS 不再采购——剩余工作仅 X-2/P0-2 真机验收与发版收官；方案文档中 M4/M5 章节仅作历史参考。
  另：按需池已完成桌面 Widget、附件流式化、会话搜索（详见 CHANGELOG 五/六轮）。
- **2026-09-30 六轮：v0.4.0-beta2 —— 真机验收问题修复**（见 §6.0 与 CHANGELOG）：修复真机验收发现的
  唯一 App 缺陷「发送/应答/停止 RPC 无超时兜底 → 状态永久卡死」；失败提示 4s→8s。
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
  - **App bug（✅ 已于 v0.4.0-beta2 修复）**：**发送无超时兜底**——桥断开瞬间点发送，
    `sendText` RPC 挂死桥上等不到 ack，App「发送中」状态永久卡住（2026-09-30 21:5x 实测复现，
    App 重启才恢复）。**修复**：`RpcChannel.call()` 加 `timeoutMs`（主线程 Handler 兜底）+
    `failPending()`（桥重建/`reset()` 时把旧桥挂起请求全部以错误收场，`pendingResponses` 改
    `ConcurrentHashMap`）；发送/应答/停止三处命令统一 15s 超时。**真机验证 PASS**：
    飞行模式断网 → `rpc timeout id=12 method=sendConversationCommandV4 after=15000ms` → UI 自动复位。
    附带把失败类 flash 提示从 4s 延长到 8s（用户反馈「未出现发送失败」实为提示一闪而过）。
  - **真机键盘不弹（环境问题，非 App 缺陷）**：两层原因叠加——① 小米手环 9 蓝牙 HID 键盘
    （`Xiaomi Smart Band 9`，`Classes: KEYBOARD|EXTERNAL`）被系统识别为外接键盘抑制软键盘；
    ② 断开后 IME 状态机卡死（`mInputShown=true` 残留）+ 小米 AI 键盘（`com.xiaomi.type`，依赖
    `com.xiaomi.aicr:cognitionService`）接管 startInput 不渲染。切搜狗 + ime reset 后恢复。
    App 侧 `InsetsController show(ime())` 请求全程正确。
  - **审批链路 UI 验收受阻**：桌面端会话模式两次切换未生效（工具调用仍直执行，无审批推送）；
    改用 DebugApprovalReceiver 注入完成 App 侧审批 UI 验收（通知/卡片/角标），真实端到端放行
    待模式切换成功后补验（协议层已由 P0-1 `tools/_p01_async.py` 四项 PASS 覆盖）。
- **2026-09-30 真机验收收官（四）——审批端到端 PASS + 表单卡结论**：
  - **审批端到端 PASS（真机全链路，非注入）**：桌面端 Build 模式实际已生效（前两轮「切换未生效」
    为误判——当时 turn 正在运行，权限上下文按 §5.4 只对新 turn 生效）。真机日志实证完整链路：
    `pendingInteractions → 1 条 Bash#perm_…`（桌面端推送）→ 手机通知「需要审批」→ 用户点
    「Allow once」→ `resolve interaction=… option=allowOnce` → **`resolve result=Accepted`** →
    `pendingInteractions → 0 条`（消解）——两条真实审批（perm_494dc84d / perm_e24566ac）均如此。
  - **体验发现（非阻断）**：① 桌面端权限确认框与手机端审批卡并存（双通道），用户易困惑；
    ② 审批消解有 ~3s 延迟，用户感知「点了没反应」（server_ts 7316→7319 实证）；
    ③ 通知按钮在 HyperOS 默认折叠，需展开通知才可见（用户实操可找到）。
  - **表单卡（AskUserQuestion）结论**：桌面端 GUI 在前台时，AskUserQuestion 走**本地弹框应答**，
    **不作为 userInput 条目推手机**（真机日志 `elicitations → 0 条` 全程未出现条目；手机端
    「看到的表单卡」实为会话流中的问答渲染，非交互卡）。App 表单卡管道本身已由 P1-1
    （_p11_verify.py 构造 userInput 条目→accept）协议实证。**待确认项**：桌面端交互转发的
    触发条件（疑与桌面端 GUI 是否前台/面板状态有关）——记录到 PROTOCOL.md §8 待验证。
  - **debug 注入通道复验 PASS**：DebugApprovalReceiver 注入双假审批 → 通知栏双通知并行展示
    （Bash/WebFetch 文案正确）→ 点通知体正确跳转 App 会话页 → DEBUG_APPROVAL_CLEAR 正常清除。
- ⚠️ **两条重要现状**（接手先读）：
  1. **release keystore 并未丢失（X-1 已结清，可直接发布）**：`toolchain/keys/zcode-remote.keystore`
     与 `app-android/keystore.properties` 都在本机，其证书 SHA-256 指纹
     `1D:46:E9:…:24:BE:55` 与已发布 v0.3.0-m3 的 APK 签名指纹**完全一致**
     （核验脚本 `tools/_apk_cert_fp.py`，只取 APK 尾部解析 Signing Block，无需整包）。
     本机 `assembleRelease` 通过且签名一致 → 新版本可直接覆盖升级已装设备。
     **旧文档中「toolchain 丢失、需回退 F:/AndroidTools」的说法作废**：本机 `toolchain/` 完整
     （jdk17 + gradle 8.7 + android-sdk platform-35/build-tools-35），`F:/AndroidTools` 不存在。
  2. **本机模拟器已可用（2026-10-09 更正，旧结论作废）**：旧记录写「CPU 是兆芯 KX-7000、qemu 静默退出、UI 层验收一律待真机」——**该结论已不成立**。2026-10-09 实测：SDK 在 `F:\Android\Sdk`，`android_preflight` 报 **AEHD 2.2 installed and usable**，AVD `apkrev35`（pixel_6 / Android 15 / x86_64）启动成功（`sys.boot_completed=1`），并跑通 `connectedDebugAndroidTest`（2/2 PASS）。
     **唯二实测脾气**：① **GUI 模式实例可能中途静默退出**（本轮遇到一次：进程消失、adb 里设备一起没了），改 `-no-window -gpu swiftshader_indirect` 无头模式后稳定；② 无头模式仍需 `-no-audio -no-boot-anim`，启动到 boot_completed 约 25–40s。
     **结论**：UI/渲染类验证优先在模拟器上做（快、可重复、不用碰用户手机），**真机复核仍是发布前必走的一步**（见 §8 红线）。
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
# ⚠️ 必须带 ZCODE_MID（telemetry-state.json 的 deviceMid）：缺失时 auth 虽返回 auth_ack，但
#    pair_status=waiting（配对绑定含机器 ID）——2026-10-09 实测踩坑。
# ⚠️ 探针与桌面端/手机互踢（同 deviceSid 单 terminal 槽）：探针上线会把桌面端 KICKED（后者自动
#    重连），用完即退；探测期间桌面端日志可见 `external relay device KICKED, reconnecting`。
ZCODE_MID=$(python -c "import json;print(json.load(open(r'C:/Users/Administrator/.zcode/v2/telemetry-state.json'))['deviceMid'])") \
  python tools/probe.py auth|boot|bridge|chan|sub|dispose|dispose-stress <会话ID前缀>

# A-3 探测子命令（2026-10-09 新增）：
#   dispose <前缀>       订阅 → 12s 基线 → 发 103 EventDispose → 12s 观察（事件流应停止）
#   dispose-stress [N]   N 次 listen→subscribe→dispose 后留一个监听，按 listen_id 统计 204 帧
#                        分布（只有 1 个 id 在收 = 无泄漏；多 id 重复推 = N 倍放大）
# 探针代理坑（2026-10-09）：websockets 17 的 `proxy` 默认 True = 读「操作系统代理」（Windows
# 注册表）——本机系统代理指向未运行的 Clash 端口会 ConnectionRefused；probe.py 已显式
# `proxy=None`（只读环境变量）直连，zcode.z.ai 实测直连可达。
python tools/setmode.py list|set <taskId前缀> build|yolo   # 切会话权限模式（验收审批用）
python tools/_e2e_send.py                                  # 以手机端身份发排队消息
python tools/_e2e_tap_approve.py                           # 验收自动化：等通知→点「允许一次」→回读证据

# 真机验收（debug 包）：注入配对凭据，免扫码 / 免 adb input text 被 IME 打乱
ADB -s <serial> shell am broadcast -n com.zcode.remote/.debug.DebugPairReceiver   -a com.zcode.remote.action.DEBUG_PAIR --es sid <sid> --es hash <hash> --es mid <mid> --es name <名>
ADB -s <serial> shell am broadcast -n com.zcode.remote/.debug.DebugApprovalReceiver   -a com.zcode.remote.action.DEBUG_APPROVAL          # 注入两条假审批，验通知卡片渲染
```

- **签名**：`toolchain/keys/zcode-remote.keystore` + `app-android/keystore.properties`（密码在此，**不入库、勿丢失**；PKCS12 约束 key 密码=store 密码）。
- **PC 端凭据**（probe/App 配对用）：`~/.zcode/v2/setting.json`（deviceSid）+ `~/.zcode/v2/credentials.json`（pass_hash，解密算法见 `tools/probe.py` load_credentials）+ `~/.zcode/v2/telemetry-state.json`（deviceMid）。桌面端 rotate 后三处同步更新，App 用「添加设备→粘贴链接」重新配对。
- **挑验证目标会话（2026-10-09 新增，零成本）**：桌面端把会话文本存进了 `~/.zcode/v2/tasks-index.sqlite` 的 `tasks.searchable_text`。要找「哪个会话最近出现过围栏代码块」不用翻手机列表：
  `select title, updated_at from tasks where workspace_key like 'F:%Zcode%' and searchable_text like '%```%' order by updated_at desc;`
  再按 `len(text) - text.rfind('```')` 判断代码块离最新内容多远（越小越靠底部，打开即见）。本轮真机复核就是靠它锁定「整理 commandcode-proxy 发行版」（末段即代码块）。
- ⚠️ **装机签名陷阱（2026-10-09 实测）**：手机上的包可能是**别的 debug 密钥**签的（本轮实测手机为 `89:45:76:B3…`，而仓库 debug 为 `3A:B5:8F:A9…`、release 为 `1D:46:E9:E8…`）——此时 `adb install -r` 必报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，只能**卸载重装**，而卸载会清掉配对凭据（需重新扫码）。**动手前先比指纹**：`python tools/_apk_cert_fp.py <apk>`（也能对 `adb shell pm path` 拉下来的包做）。
- ⚠️ **HyperOS 装机与 USB 两个脾气（2026-10-09 实测）**：① 首次 `adb install` 大概率被 `INSTALL_FAILED_USER_RESTRICTED` 拦下，**重试 + 手机端点「允许」**即 Success（弹窗只在手机屏上，命令会一直挂着，用后台任务等它）；② USB 调试接口会自行掉线 —— 表现为 adb 里 `offline` 或设备消失、Windows 只剩 WPD「Xiaomi 15 Pro」，解法是手机侧把 USB 用途切「传输文件」+ 重新允许调试（或拔插数据线），必要时再 `adb kill-server` 重新枚举（重启 adb server 属常驻服务操作，须先取得用户确认）。
- **git push**：`git -c http.proxy=http://127.0.0.1:7900 push ...`（本机代理；直连国际线路不稳定。端口随 Clash Verge 混合端口变更过：7897 → **7900**，仓库 `.git/config` 已固化 7900）。

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
| 保活引导页 / debug 注入 receiver | ✅ | `ui/screens/KeepAliveGuideScreen.kt` `app/src/debug/` |
| 前台服务 | ✅ 实测（FGS specialUse + 进程级 ConnectionScope 单例，断网重连与保活全通） | `service/ConnectionService.kt` |
| 会话页排版对齐 + 会话级状态面板（beta17） | ✅ 代码 + 单测 + **真机视觉验收通过** | `ui/components/CollapsibleRow.kt` `ui/components/SessionStatusPanel.kt` `ui/components/ToolKindLabels.kt` `ui/theme/Typography.kt` `ui/theme/Dimens.kt` `ui/screens/ConversationScreen.kt` `relay/ConversationFrames.kt` |
| 代码块高亮「吞字」修复 + 模拟器渲染回归网（beta18） | ✅ 代码 + 单测 + **模拟器仪器化验收通过**（真机复核待办） | `ui/components/MarkdownView.kt`（`opaqueHighlightArgb`）`app/src/androidTest/java/com/zcode/remote/ui/components/MarkdownRenderTest.kt` `app/src/test/java/com/zcode/remote/ui/components/CodeHighlightTest.kt` `app/build.gradle.kts`（androidTest 基建） |
| 体验补强首批 C-1/C-2/C-4/C-6/C-7/C-10/C-11（2026-10-09，**未发布**） | ✅ 代码 + 单测 + debug/release 构建通过；**⏳ 真机验收待做** | `relay/UserFacingError.kt` `relay/ConversationChannel.kt` `AppViewModel.kt` `ui/screens/ConversationScreen.kt` `ui/screens/ApprovalsTab.kt` `ui/theme/Theme.kt` `storage/SessionCacheStore.kt` `MainActivity.kt` |
| 无设备可验证项 + C-5① + 看门狗可测试化 + **A-3 实测启用**（2026-10-09，**未发布**） | ✅ 代码 + 144 项单测 + debug/release 构建通过；**A-3 经 probe 静态 + 动态双确认并启用** | `storage/DevicesCodec.kt` `relay/RpcChannel.kt` `relay/ConversationChannel.kt` `AppViewModel.kt` `tools/probe.py` `.github/workflows/ci.yml` |

版本序列：`v0.2.0-m2` → `v0.2.1-m2b` → `v0.2.2-m3a` → `v0.2.3-m3b` → `v0.3.0-m3` →
`v0.4.0-beta1…beta6`（发版内测 → 真机修复 → 16KB 对齐 → 互踢修复 → 扫码重构 → 排版对齐）→
`v0.5.0-beta1…beta5`（模型档位链路，versionCode 15）→ `v0.5.0-beta6`（P0 结清 + 最近文件 + Diff 视图，versionCode 16）→ `v0.5.0-beta7`（智能贴底 + 快捷胶囊，versionCode 17）→ `v0.5.0-beta8`（通知栏 RemoteInput + Share Sheet，versionCode 18）→ `v0.5.0-beta9`（单轮 Turn 变更聚合，versionCode 19）→ `v0.5.0-beta10`（Deep Link + 触觉反馈，versionCode 20）→ `v0.5.0-beta11`（会话列表离线持久化秒开，versionCode 21）→ `v0.5.0-beta12`（会话消息流离线持久化与点进秒开，versionCode 22）→ `v0.5.0-beta13…beta16`（正确性缺陷修复 → 附件 chip → 连接层修复 → 异常原因与输入栏对齐）→ `v0.5.0-beta17`（会话页排版全面对齐桌面端 + 会话级状态面板，versionCode 27）→ `v0.5.0-beta18`（**当前**，代码块高亮「吞字」修复 + 模拟器仪器化渲染回归网，versionCode 28）。

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
11. **会话快照的会话级状态块形状（beta17 实证，无官方 schema）**：`usage{contextWindow,cumulative}`、`queue{items:list,autoDrain:bool}`、**`backgroundWorks` 是 list（不是 dict）**、`subagents{revision,childSessionIds,running,endedTotal}`、`goal` 可为 `null`、`plan{items,updatedAt}` 而 `plan.items` 元素为 `{id,content,status}`（`status=='completed'` 即完成，**没有** `iteration`/`title`/`verificationOutcome`，`completed/total` 需现场统计）。`rows{window,totalCount,firstRowId}`。**桌面端「计划」分区的数据来自另一条 RPC `transport.plans`，本 App 未订阅，故状态面板不做该分区**（红线：不为面板新开查询命令）。
12. **增量 patch 只合并白名单键**：`ConversationChannel` 的 `state.updated` 处理原仅合并 `pendingInteractions`/`elicitations`，其余会话级键被忽略 → 即使快照补齐，数据也只在首帧正确、之后永不更新。beta17 已补齐缺口；新增会话级块时**必须同步改这里**。
13. **Compose 实测坑（beta17 新增，均已在代码注释留痕）**：① LazyColumn 回收滚出视野的行 → 行内 `remember` 折叠态丢失，**必须提升到屏幕层**，且折叠态用 `List<String>` 而非 `Set`（`rememberSaveable` 写 Bundle 不保证任意 Set 实现可反序列化）；② LazyColumn 项内高度约束无限 → `fillMaxHeight` 退化为 0 高，`IntrinsicSize.Min` 与 `verticalScroll` 不兼容 → 画竖线只能用 `drawBehind`；③ **嵌套同方向 `verticalScroll` 会运行时告警** → 面板把滚动统一放在最外层一层，内部折叠行展开体一律不限高；④ `CollapsibleRow` 的 `label` 槽位**不是 RowScope**（不能挂 `weight`），需要撑开中间时用 `primary = { Spacer(Modifier.weight(1f)) }`。
14. **Markdown/高亮依赖的版本上限**：`com.mikepenz:multiplatform-markdown-renderer-m3/-code:0.27.0` —— **0.30.0 起改用 Kotlin 2.1+ 编译，本项目 Kotlin 插件 2.0.20 读不了其元数据（硬报错）**，0.27.0 是最后一个 Kotlin 2.0.x 编译版，**升级 Kotlin 插件前不得上调**。安全边界：显式传 `NoOpImageTransformerImpl` 不加载外链图片；链接走 http/https 白名单（`ui/components/SafeUriHandler.kt`）。
15. **高亮色是纯 RGB，不是 ARGB（beta18 抓出的真缺陷，勿重犯）**：`dev.snipme highlights` 的 `ColorHighlight.rgb` 形如 `0x2BBAC5`（**无 alpha 位**），而 Compose 的 `Color(Int)` 按 **ARGB** 解释 → 直接 `Color(it.rgb)` 得到 `alpha=0x00` 的**全透明**色，症状是**被高亮的字符整段看不见**（关键字/字符串/注释凭空消失，不是「没颜色」）。必须走 `opaqueHighlightArgb(rgb) = rgb or 0xFF000000`（`ui/components/MarkdownView.kt`，纯函数有单测）。**同类坑适用一切「库给 RGB、Compose 要 ARGB」的转换。**
16. **未知围栏语言不是「不亮」而是「泛化高亮」**：`SyntaxLanguage.getByName("nosuchlang") == null`，但不设 language 时库会用 DEFAULT 规则继续给字符串/注释/数字上色 —— 所以**不能拿「未知语言 = 无高亮」当渲染测试的对照组**（beta18 实测推翻该假设，改为直接断言主题 token 色像素）。
17. **`ColorHighlight.rgb` 主题色实测值**（`SyntaxThemes.atom(dark=true)`）：`#2BBAC5` 青 / `#D55FDE` 品红 / `#89CA78` 绿 / `#5C6370` 灰（注释，饱和度低）。模拟器渲染断言按前三色逐像素比对（容差 10），主题一改就会失败 —— 那是期望行为。
18. **仪器化渲染测试的两个实操坑（beta18）**：① AGP 跑完 `connectedDebugAndroidTest` **默认会把 APK 卸载**，测试里写到应用内部目录的截图会一起消失 —— 要取证据需加 `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`，再用 `adb exec-out run-as com.zcode.remote cat files/render-evidence/*.png` 取出；② 断言文本时用 `onAllNodesWithText(useUnmergedTree = true).onFirst()`：表格/代码块既有叶子又有合并父节点，`onNodeWithText` 会因「命中 2 个节点」直接失败（是断言写法问题，不是渲染问题）。
19. **D-1 核查结论（2026-10-09，有界核查）**：`v4/conversation/frame` 的 **delta op 层三方已对齐、无差集**（桌面 host bundle ↔ `CONVERSATION-PROTOCOL.md:142-146` ↔ `relay/ConversationFrames.kt:515-538`，5 个 op：`row.appended/row.upserted/row.removed/row.delta/state.updated`；`row.delta.path ∈ text|inputText|output.text|summaryText`）。自该帧析出的两个**真缺口**已按「未立项待办」登记在 §6：**① 逻辑帧 `kind:"fragment"` 被静默丢弃**（`ConversationChannel.kt:257-259`，无实测样本，触发即缺帧）；**② 桌面 frame 业务 schema 与完整 row kind 联合仍未还原**（有 `PlainRow` 兜底，最坏样式降级）。未知 op / 未知 payload kind 一律「只记日志、UI 无告警」——**这条本身就是需要知悉的行为**：出问题时要先看 logcat，不要期待界面报错。
20. **上传重试必须复用 uploadId（C-1，2026-10-09）**：服务端按 `uploadId` 幂等——`attachmentBeginV4` 对已 committed 的 id 直接返回 ref（分片不重传）。`uploadId` 现由 `AppViewModel` 生成并保存在失败态（`FailedUpload`）里；**换文件（名字或大小不同）必须换新 id**，否则服务端会把旧文件内容当新文件提交（`uploadIdForAttempt` 纯函数 + 单测钉死）。
21. **主题感知色值必须走 `statusPendingTone()` / `toolCallTrajectoryTone()`（C-4，2026-10-09）**：亮橙 `#FF8A30` 与轨迹 `#F59E0B` 只允许出现在深色主题（浅色下对比度仅 2.15~2.35:1）。`ContrastTest` 有两条反向断言防止低对比色值回流；新增「浅色下要可读的文字/图标」时先看该测试再选色。
22. **用户可见错误必须过 `UserFacingError.map()`（C-2，2026-10-09）**：本地自造英文（`bridge not ready` / `timeout after` / `channel reset`…）与服务端 fault code 不得直出 UI；新增失败路径时在展示点调用它（`UserFacingErrorTest` 有「不得泄漏裸英文」断言）。
23. **本地回显气泡与会话绑定（C-6，2026-10-09）**：`AppViewModel.pendingUserMessages` 在切会话 / 断开时清空；服务端回显按「文本 trim 相等」匹配移除（回显可能是桌面端发的消息，不能误删本机 pending）。
24. **`devices_v2` 凭据文件是新旧双格式（C-8，2026-10-09）**：新格式为 kotlinx.serialization 结构化 JSON；**旧格式（0x01 拼接行、控制字符未转义、按 JSON 规范非法）用 kotlinx 读不出来是正常现象**——`DevicesCodec.decode` 失败后必须走 `decodeLegacy`（读到即迁移）。改存储结构前先看 `DevicesCodecTest` 的双格式用例。
25. **A-3 的 103 已实测启用（2026-10-09）**：字段规格 = 官方 bundle `[103, id]` + **undefined 参数段**（两段式，**勿省参数段**）；服务端静默接受、无 201/202/203 应答（fire-and-forget，勿等回包）。`SEND_EVENT_DISPOSE = true` 已启用；复现脚本 `probe.py dispose`（单次对照）与 `dispose-stress N`（N 次循环后按 listen_id 统计 204 帧分布）。**A-4 仍保守**：「缺口只记 WARN 日志、不触发重订阅」，buffer 策略改动须独立 commit + 独立真机回归。
26. **release 热点在「日志实参」而非日志调用（C-5①，2026-10-09）**：`ZLog.d/i/w` 的**调用**会被 R8 的 `-assumenosideeffects` 剥离，但**实参表达式不会**——`ZLog.d(TAG, ev.data.toString())` 在 release 仍执行 `toString()`。任何「重计算进日志参数」的写法必须自带 `BuildConfig.DEBUG` 门控（例外：`ZLog.e` 在 release 也输出，只允许字符串字面量元信息，CI 有断言守）。
27. **桥看门狗决策是纯函数（2026-10-09）**：`RpcChannel.watchdogDecision`（Noop/Retry/Fail）由单测钉死——改看门狗行为（超时、重开次数）时先改它和对应单测，不要在 `scheduleBridgeWatchdog` 里散写判断（该路径真机无法构造，单测是唯一防线）。
28. **「桌面端已连接、手机显示异常」是两层状态（2026-10-10 真机故障）**：中继层 `paired`（device↔terminal 配对）与会话桥层（RPC 通道）**相互独立**——前者正常不代表后者正常。本次故障链：App 主线程处理停摆（长会话 1100+ 行 × 高频入站帧 → 停止回 ack）→ 服务端重放未确认帧 → 判 `rpc-transport-fault` 下发 `bridge-degraded` → 手机「异常」而桌面端日志仍 `paired`。**App 侧自愈已补**：`AppViewModel.scheduleBridgeReopen`（退避 1/2/4s × 3 次，就绪归零）。诊断要点：`adb logcat -s RelayClient AppViewModel ConvChannel`——**logcat 缓冲会被会话帧大量冲掉，长观察必须先 `logcat -c` 并把输出落盘**；判「App 是否真的在处理帧」看 `RpcChannel` 解码日志与 `ConvChannel: 状态块更新` 是否与 `RelayClient: ws recv` 同步出现（只有 ws recv 在涨 = 处理停摆）。

## 6. 剩余任务（P0 → P2，含验收标准）

### 6.0 剩余工作总览（历史存档 · 已成闭环）

> **📌 2026-10-05 收官**：本节以下内容为历史记录。当前实际剩余工作**仅 P0-2 三天观察**，
> 详见上方「待办总览（2026-10-05 收官·最终版）」。下表的「真机验收结果」为 09-30 记录，
> 最新验收见 §6.1 的 2026-10-05 验收表。

**已完成（勿重做）**：M0 协议逆向、M1 App 骨架+配对+会话列表、M2 会话流+审批端到端、
M3 主体（多机/主题/线路/保活引导/历史翻页/分片重组）、P0-1 发送+停止、P1-1 表单应答、
P1-2 多会话看板、P1-4 协议常量结清、P1-3 附件+语音、E-1 权威角标、P2-3 桌面 Widget、
技术债 3 项；**v0.4.0-beta2 修复发送超时兜底**（真机验收唯一 App 缺陷）。

**真机验收结果（2026-09-30，小米 15 Pro）**：核心项全部 PASS —— 扫码配对、桥接与重连、
会话列表/多机/设置、会话流渲染、发送消息端到端、附件全链路（桌面端 Begin/Chunk/Commit 实证）、
停止按钮、**审批端到端**（真实审批→手机通知→Allow once→accepted→消解，两条实证）、语音按钮降级。

**待办总览（2026-10-05 收官·最终版）—— 开发计划已全部结清，仅剩观察期**：

| 项 | 内容 | 状态 |
|---|---|---|
| **P0-A/B/C** | 前台服务死代码 / 新建会话硬编码 YOLO / release 明文日志（Sprint 1–2） | ✅ 全部结清并通过真机验收 |
| **Sprint 3** | 工具调用 Diff 视图 + 最近文件面板与远程只读代码预览（`file.readTextFile`）+ 单轮 Turn 变更聚合 | ✅ 全部结清并通过真机端到端验收 |
| **Sprint 4** | 通知层升级：RemoteInput 内联回复（真机实测） | ✅ 核心完成（v0.5.0-beta8） |
| **Sprint 5** | 配对 Deep Link + 触觉反馈 + Share Sheet + 快捷指令 chips + 双离线持久化秒开 | ✅ 全部完成（v0.5.0-beta12） |
| **Sprint 6** | 回归网：VQL 金标准对拍 + 26 项纯函数单测 + CI + `build.sh` 可移植化；**beta18 起扩到仪器化渲染层**（`androidTest` 用 fixture 渲染真实 Composable，本地模拟器跑，不进 CI） | ✅ 全部结清，本地与 CI 全绿；仪器化套件模拟器 2/2 通过 |
| **P0-2（唯一剩余项）** | **日常使用观察 3 天**：锁屏通知可达性、HyperOS 杀后台 30min 后审批可达 | ⏳ 待观察（前台服务实测运行中）；窗口因 **beta19**（跨会话泄漏修复）重置，**自 2026-10-09 重新计时**，预计 2026-10-12 收官；观察机已升级 beta19 |
| ⛔ 后续开发计划 | Sprint 7（生物识别/凭据生命周期/自建中继 E2EE）、P2-3 余项、O-1/O-2 增强 | ❌ **2026-10-05 用户决策：全部取消，不再开发** |

> **📌 唯一路径（v1.0 判停线）**：Sprint 0–6 已全部完成，**全部后续开发计划已取消**。
> 仅需完成 **P0-2 三天日常使用观察**；观察期间无新缺陷即达 v1.0 判停线，可直接发 v1.0。
> 此后不再规划新功能（如需演进，须用户重新拍板并单独立项）。

> **M4/M5 已取消（2026-09-30 用户决策）**。
>
> ⚠️ **2026-10-02 勘误**：旧结论「文件浏览在官方协议下无落点就此搁置」**已失效**——该判断依据的是
> bundle 静态分析，而实测已打通 `system.info → file.resolvePath → file.readTextFile` 链路并成功读取
> **工作区之外**的 `~/.zcode/v2/provider_config.json`（见 CHANGELOG「核心突破」条目与
> `AppViewModel.kt` 实现、`RpcChannel.kt` 的 `CHANNEL_FILE/CHANNEL_SYSTEM` 常量）。
> 只读文件浏览与 diff 视图**不需要任何新协议**，已列入 Sprint 3（§6.1）。
>
> **新规则**：任何「协议做不到」的结论必须标注依据是 bundle 静态分析还是真机实测；两者冲突时
> 以实测为准并回改文档（本次教训）。

---

### 6.1 计划评审结论与 Sprint 0–6 交付记录（✅ 已全部结清，2026-10-05 收官）

> **📌 2026-10-05 最终状态**：Sprint 0–6 全部完成并真机验收；**Sprint 7 及全部后续开发计划经用户决策取消**。
> 本仓库转入「维护 + 3 天 P0-2 日常观察」阶段，观察通过即发 v1.0。下方为历史计划记录，仅作存档，**不再执行**。

**评审结论**：外部《提升空间分析与开发计划》经实证抽查后采纳——10 条关键断言逐一对照仓库代码，
全部命中且行号精确一致（抽查项：`AppViewModel.kt:860` 与 `ConversationChannel.kt:567` 的 yolo、
`ConnectionService` 零调用者、`RelayClient.kt:109/116` 与 `RpcChannel.kt:128` 明文日志、
`proguard-rules.pro` 被引用但不存在、无 `src/test`、三个千行文件行数、CHANGELOG「核心突破」、
RemoteInput 全仓 0 命中）。总体判断：**功能面已超出对标官方 Web 的目标，剩余空间不在加功能**，
而在 ① 三个实证级 P0 缺陷；② 被静态分析误判挡住的 diff/文件浏览（移动端最高价值）；
③ 工程卫生为零。战略上**不追功能广度**（同类独立产品 Terragon / Vibe Kanban 已相继关停），
值得投的是官方产品结构性做不到的位置（多供应商非官方端点生态 / 自建中继 / E2EE）。

**三个 P0 缺陷（证据均已在仓库复核）**：

- **P0-A 前台服务是死代码**：`ConnectionService.start()/stop()` 全仓无调用者；WebSocket 在
  `AppViewModel` 构造 → 连接生命周期 = Activity 生命周期，进程被系统回收即失联，锁屏审批靠运气。
  平台约束：targetSdk=35 下 Android 15 对 `dataSync` FGS 施加 24h 内累计 6h 上限（超时回调
  `Service.onTimeout()`，数秒内须 `stopSelf()`）→ 须改 `specialUse` 类型并实现 `onTimeout`。
- **P0-B 新建会话硬编码 YOLO**：`AppViewModel.kt:860` `mode = "yolo"` 写死 + `ConversationChannel.kt:567`
  兜底 `?: "yolo"`，UI 无处显示/选择执行模式 → 手机建的会话全部免审批（既是安全洞，又让锁屏审批
  对这些会话永不触发）。成因：v0.5.0-beta2 为绕 Gemini 校验显式下发 `mode:"yolo"`，后续修了
  `thought` 未修 `mode`（CHANGELOG 有记录）。
- **P0-C release 包明文日志**：`RelayClient.kt:109`（完整入站帧=全部会话正文）、`:116`（HMAC proof
  与 deviceSid）、`RpcChannel.kt:128`（500 字符 payload）均无 `BuildConfig.DEBUG` 门控；
  release `isMinifyEnabled=false` 且引用的 `proguard-rules.pro` 不存在 → 直接违反 §8 凭据红线。

**Sprint 排期总表**：

| Sprint | 内容 | 状态 |
|---|---|---|
| Sprint 0 | 文档清账：修正本文失效结论（§4 / §6.0）+ 新增「静态分析 vs 实测」标注规则 | ✅ 2026-10-02 |
| Sprint 1 | **P0-A** 连接迁入进程级 `ConnectionScope` + FGS `specialUse`/`onTimeout`；**P0-C** `ZLog` 门控 + R8 + 补建 proguard 剥离日志；**B组** `NetworkCallback` / 入站流 `SUSPEND` / `appVersion` 改取 `BuildConfig.VERSION_NAME` | 🔨 本轮 |
| Sprint 2 | **P0-B** 执行模式选择器（plan/build/yolo，默认 build）+ 会话页模式胶囊（`setMode` 仅对新 turn 生效须如实提示）+ KICKED/AUTH_FAILED/PROTOCOL_MISMATCH 三种终态常驻通知 | 🔨 本轮 |
| Sprint 3 第一步 | diff 视图（纯客户端解析写类工具 `inputText`/`raw` 的 old/new → unified diff 红绿渲染，零协议零 RPC 可离线开发） | ✅ 2026-10-05 真机验收完成 |
| Sprint 3 第二步 | 只读文件能力——剧本 B「最近文件」面板（`SessionFiles` 从工具调用行抽路径去重 + 顶栏 📁 入口 + ModalBottomSheet 文件列表 + `file.readTextFile` 预览，零新协议） | ✅ 2026-10-05 真机端到端验收全通 |
| Sprint 4 | 通知层升级：RemoteInput 内联回复（真机实测） | ✅ 完成（v0.5.0-beta8） |
| Sprint 5 | 系统增强：配对 Deep Link 唤起 + 触觉反馈 + Share Sheet + 快捷指令 chips + 双离线持久化秒开 | ✅ 完成（v0.5.0-beta12） |
| Sprint 6 | 回归网：VQL 金标准对拍测试（Kotlin↔Python 共享 fixture）+ 纯函数单测（26 项全绿）+ GitHub Actions + `build.sh` 去硬编码路径 | ✅ 2026-10-02 |
| ~~Sprint 7~~ | ~~凭据生命周期（生物识别等）与自建中继 + E2EE~~ | ❌ **2026-10-05 用户决策取消**（与已取消的 M5 同源；不采购 VPS、不做 bridge/E2EE） |
| **P0-2（唯一剩余）** | **3 天日常使用观察 → v1.0 判停线** | ⏳ 进行中 |

**真机验收清单（并入 P0-2，需小米 15 Pro）**：① 从最近任务划掉 App，30min 后 PC 触发审批手机仍收到
通知；② 飞行模式往返后连接自动恢复；③ release 包 `adb logcat` 抓不到任何会话正文与 proof；
④ 手机新建会话（不手动选 yolo）→ PC 端工具调用触发审批推送到手机；⑤ 模式胶囊常驻且可切换；
⑥ 官方 Web 抢占连接后手机收到「已被接管」系统通知。

**✅ 真机验收结果（2026-10-05，小米 15 Pro / Android 17，debug 包 + DebugPairReceiver 注入）**：

| 项 | 结果 | 实证 |
|---|---|---|
| 连接链路 | ✅ | ws open 101 → auth 挑战应答 → matched → bootstrap 23 会话 + 会话流快照/增量实时到达 |
| P0-A 前台服务 | ✅ | `dumpsys activity services`：`isForeground=true types=0x40000000`（specialUse 位）+ LOW 常驻通知 |
| ② 断网感知重连 | ✅ | 事件链全程 <9s：`onLost`(<1s) → `net-watch 立即断 socket` → 防重入守卫 → 挂起等恢复 → `onAvailable 放行` → 2.7s 重连 matched |
| ④ 审批推送端到端 | ✅ | 手机新建会话（默认 build）→ PC 端 Write → 通知「需要审批：Write」（approvals 渠道 HIGH）→ 审批卡（允许一次/总是允许/拒绝 按 sortKey 排序）→ 点「允许一次」→ resolveInteraction → 卡片乐观消解（实际文件写入因宿主 turn 串行排队，待本 turn 结束后自然完成） |
| ⑤ 模式胶囊 | ✅ | 常驻显示（默认 build 兜底）→ 点击菜单三档 + yolo 警示 + §5.4 语义提示 → 选 plan 胶囊即变（setMode RPC 生效）→ 已切回 build |
| ⑥ 终态通知 | ✅ | 被桌面端面板抢占 → 常驻通知「会话已在别处打开（与官方 Web 版互踢）」（terminal_state 渠道 HIGH） |
| P0-C debug 日志 | ✅ | proof 本体不再输出（`auth proof computed` 仅元信息） |
| diff 视图 | ✅ | DiffBlock 渲染实证（`zd_test.txt` Edit 行解析 Myers diff，摘要 `📄 zd_test.txt +1 −1` 实时展示，行级红绿增删与「⧉ 复制」unified 文本直接实证）；23 项 JVM 单测全绿 |
| 最近文件面板与只读预览 | ✅ | `SessionFiles` 动态抽行内文件（实测 8 个文件），顶栏 `📁 8` 动态更新；ModalBottomSheet 列表点选发起 `file.readTextFile`，返回正文等宽展示+横向滚动；「← 文件列表」/「关闭」交互正常 |
| 审批广播与通知栏 | ✅ | `DebugApprovalReceiver` 注入双测试审批，`approvals` 渠道 HIGH（importance=4）常驻横幅与 actions 按钮正常，`DEBUG_APPROVAL_CLEAR` 正常撤销；`ApprovalsTab` 集中看板渲染正常 |
| ③ release 包 logcat | ⏸ 静态已验 | dex 敏感字符串 5/5 归零（编译期确定性）；真机 release 需卸载重装+重新配对，成本大于收益，跳过 |
| 划掉任务 30min 观察 | ⏳ | 归入 P0-2 日常观察（前台服务已实证运行，预期 PASS） |

**真机实测抓出并已修复的问题（全部在本次验收周期内闭环）**：
1. `sessionModeOverride` 声明在 `init{connect()}` 之后 → 首次状态写入 NPE，**App 启动即崩**（Kotlin 属性初始化顺序坑，已移到 init 前并加警示注释）。
2. **缺 `ACCESS_NETWORK_STATE` 权限** → NetworkGate 全部 ConnectivityManager 调用被 SecurityException 拦截（被 runCatching 吞掉后退化为纯退避——症状是断网后"协程假死"）。已补 Manifest。
3. **OkHttp 对网络整体丢失的失败回调延迟到网络恢复时才冒出**（断网期连接静默死亡、心跳停止）+ 退避计数被快速失败烧到 48s 封顶 → NetworkGate 重构为**常驻监控**：onLost 立即断 socket + attempt 归零调度重连，onAvailable 即刻放行。
4. **两个 mode 撞名**：订阅 ack 的 `mode` 是**订阅模式**（snapshot/live），与**执行模式**（plan/build/yolo）完全不同——ModeChip 曾错显 "snapshot"。执行模式权威来源改为 `readWorkspaceState` 的 `settings.mode.current`（MainActivity 已加警示注释）。
5. ✅（**已于 v0.5.0-beta7 修复**）**会话运行中上翻浏览被新行强拉贴底**——引入 `isNearBottom` 视口状态判定，仅在靠近底部时自动平滑贴底；上翻时保留阅读位置，并弹出悬浮「回到底部 ↓」胶囊按钮平滑定位；同时新增 `ActionChipsBar` 常用快捷指令栏（减少打字成本）。

**验收环境注意事项（接手必读）**：
- **DebugPairReceiver 注入必须用面板「刷新二维码」轮换出的独立 deviceSid**——注入桌面端本体 sid（setting.json 的）会与面板内嵌 terminal 互踢（同 sid 单 terminal 槽），表现为手机反复 KICKED/waiting。正常扫码配对天然规避（新 sid 与桌面端并存）。
- **手机上的 Clash Meta（fake-ip 全局 VPN）会掐断中继 TLS 握手**（`zcode.z.ai` 解析到 198.18.x.x 后握手 EOF）——需在 Clash 里为 `zcode.z.ai` 加 DIRECT 规则或关闭 VPN，否则 App 连不上（不是 App 缺陷，PC 侧同域正常可作对照）。
- HyperOS 的 `cmd connectivity airplane-mode enable` 不一定真断 Wi-Fi（记忆用户偏好）；断网验收用 `svc wifi disable && svc data disable` 才可靠。
- 会话运行中用 uiautomator 浏览历史行会被贴底打断——验收操作要原子化（滚动+定位+点击+验证在一条命令内完成）。

**《八周计划》（千问work，2026-10-05 评审）的吸收项**——该文档与本计划覆盖同一批事实（约 2/3 重叠，
重叠部分已全部完成并真机验收），以下为经甄别后并入的增量：

| 吸收项 | 去向 | 说明 |
|---|---|---|
| RPC 能力探测系统方法：从 `research/asar/` 静态枚举全部 channel/method + 对 file/git/workspace 类方法逐个实测 | 并入 Sprint 3 第二步前置 | 必须回答四个问题：`readTextFile` 路径边界（能否读工作区外/~/.ssh）？有无目录列举方法？有无 git/命令通道（有则 diff 直接走 `git diff`）？有无**写**能力（安全边界决定性事实）？ |
| 剧本 B「最近文件」面板：从会话流工具调用参数抽已读/已写文件路径，做可跳转面板 | **Sprint 3 第二步优先形态** | 比通用目录浏览器更贴"看 Agent 刚改了什么"的场景，且不依赖目录列举能力；剧本 A（目录树浏览器）降为二期 |
| W4.7 分片重组与水位 ack 交互审查：`ack(N)` 隐含 N 以下全收，而 `trimFragmentBuffers` 会丢最小 seq 未完成碎片 | ✅ 已审查（2026-10-05） | **结论：正常路径安全**——WS 帧严格有序，同一时刻最多 1 条未完成分片消息，`fragmentBuffers.size > 8` 触发条件实际不可达，水位 ack 不会覆盖未 ack 消息。理论边界：CRC/size 校验失败的已集齐消息被静默丢弃（水位被后续 ack 覆盖）——传输层损坏才触发，CRC 本身即双保险设计，留档不改。单测需解耦 android.util.Base64，收益低于成本 |
| 迟到 onFailure 竞态 | ✅ 已修复（2026-10-05） | RelayClient 引入连接代次（generation）：connect() 先 cancel 旧 socket + 作废挂起重连调度，所有回调与调度携带并校验代次——旧 socket 迟到回调一律丢弃，不再可能断掉健康连接 |
| 会话内变更面板：聚合"本 turn 改了哪些文件"，点进看 diff | ✅ 已完成（v0.5.0-beta9） | TurnChanges.aggregate + TurnChangesCard 挂载，真机实测 PASS，24 项单测全绿 |
| v1.0 判停线 | 里程碑定义 | 回归网（Sprint 6）+ 三天真机观察通过即可发 v1.0；之后均为 v1.1 增量，不构成发布阻塞 |
| 小 bug：检查更新 `hasNew` 用字符串不等判断 | ✅ 已修 | 远端旧版本会误报"有新版本"；改语义化比较（数字段逐位 + prerelease 规则）+ 8 项单测 |
| HANDOVER 文档卫生：P1-4 重复标题、§4 版本序列滞后于 §1 | ✅ 已修 | |
| 删零引用依赖 navigation-compose / datastore-preferences / security-crypto(alpha) + CredentialStore 死代码 | ✅ 已删 | import 级零引用验证后删除 |

**该文档评审后不建议吸收**（留档备查）：W4 的 epoch/原子化整体重构（ConnectionScope 方案下连接实例
随创建销毁，其要解的问题域大半不存在，仅取上述竞态修复）；W5 文档拆四份（大动作中等收益，可选做
仓库根薄版 AGENTS.md）；W8.4 AGP 升级（有保护网后可做但非必要）；Wear OS / 小米推送（可达性已被
真机验收部分回答：前台服务 + NetworkGate 下断网重连 <9s，继续观察 P0-2 再定）。

**明确不做（沿用审查结论）**：iOS/跨端重写、手机端完整 Git 写操作（只读 diff，写操作交给 Agent）、
FCM/小米推送主通道（IM Bot 通道兜底另议）、追功能广度（多供应商面板/Marketplace/RBAC）。

### P0-1 会话页「发送消息 + 停止按钮」（✅ 2026-09-29 完成，协议层端到端验收通过）



- **已完成**：`ConversationScreen` 底部输入栏（TextField + 发送，走 `sendPrompt`）与
  「停止」按钮（`control.canStop` 时显示，envelope `type:'stop'`，官方 web 同款）。
  协议实证与端到端实测记录见 PROTOCOL.md §6.4；验收工具 `tools/_p01_async.py`。
- **实测**：桌面端在线时四项全 PASS——sendPrompt 201 `accepted:true` → turn running 且
  `canStop=true` → stop ack `status=accepted` → 桌面端 `completedInterrupted`、canStop 清零。
- **遗留**：App UI 层模拟器验收未做——本机兆芯 CPU 无模拟器硬件加速（qemu 静默退出），
  待 Intel/AMD 机器或 P0-2 真机补验；构建环境见 CHANGELOG「未发布」段
  （toolchain 丢失已回退系统路径，release keystore 需恢复）。

### P0-2 真机日常观察（⭐ 唯一剩余项：3 天观察 → v1.0 判停线）

> 🔄 **2026-10-07 更新（v0.5.0-beta13 补丁轮次）**：本次按《体验提升任务书 v2》档 A/B 修复了四项
> 正确性/体验缺陷（A-1 上传跨会话注入、A-2 握手无超时、B-1 贴底回归、B-3 触觉补漏）。
> 按下方「若发现问题」规则，**3 天观察窗口已重置**：起点改为 **beta13 真机验收通过之日**。
> 观察用的 APK 也应换成 beta13；观察清单第 4 项（飞行模式往返 <9s）现在会**预期看到可读中文失败 + 自动重订**，
> 若 10s 内出现「握手超时，请检查网络后重试」+ 自动恢复，属**符合预期**而非新缺陷。
>
> 🔄 **2026-10-08 更新（v0.5.0-beta17 排版对齐轮）**：beta17 已**真机验收全通过**（小米 15 Pro / Android 17 · HyperOS）。
> 按下方「若发现问题」规则，**3 天观察窗口再次重置**：起点改为 **2026-10-08**（预计 2026-10-11 收官）。
> 观察用的 APK 换成 `ZCodeRemote-0.5.0-beta17.apk`（或同源 debug 包）。观察清单第 4 项（飞行模式往返 <9s）沿用 beta15 基线：
> 应看到可读中文失败 + 一次自动重订，属**符合预期**而非新缺陷。beta17 的排版对齐与状态面板**属纯展示层改动**，
> 不应影响连接/审批路径；若观察期内出现卡顿、闪退或嵌套滚动告警，属新缺陷，按同款流程修复并再次重置窗口。
>
> 🔄 **2026-10-09 更新（v0.5.0-beta18 代码块高亮修复轮）**：beta18 修掉一个 beta17 引入的用户可见缺陷
> （代码块里被高亮的字符整段不可见）。**验收已闭环**：模拟器仪器化测试（可重复）+ **真机复核通过（2026-10-09）** ——
> 小米 15 Pro 装机后打开会话「整理 commandcode-proxy 发行版」，` ```bash ` 代码卡完整渲染且高亮分色
> （token 色命中 2365 像素），未再「吞字」。
> 因此按下方「若发现问题」规则，**观察窗口自 2026-10-09 重新计时**（预计 2026-10-12 收官），观察机为 beta18。

- **状态**：功能开发与真机验收已全部完成（v0.5.0-beta12 → beta17 各轮**均已真机验收通过**；**beta18 为模拟器验收，真机复核待办**）。本项是**发布 v1.0 前的最后一步**。
- **目标**：连续 3 天日常使用无失联、审批通知始终可达。
- **观察清单（无需写代码，仅记录）**：
  1. 安装 v0.5.0-beta18 APK（或 debug 包）→ 正常配对与使用；
  2. **锁屏审批可达**：锁屏收通知 → 通知栏批准/直接回复 → 桌面端放行；
  3. **杀后台场景**：从最近任务划掉 App / 锁屏 30min 后，PC 触发审批，手机是否仍收到通知；
  4. **网络往返**：飞行模式/切 Wi-Fi 往返后连接自动恢复（预期 <9s，已有实测基线）；
  5. 日常迭代：新建会话、发送/停止、附件、Diff、最近文件预览等常规路径无异常。
- **判停线**：3 天内无新缺陷 → **达到 v1.0 发布条件**（`HANDOVER §6.1` 里程碑定义）。
- **若发现问题**：按「真机实测抓出并已修复的问题」同款流程修复 → 补丁版本 → 重置 3 天观察窗口。

> 📮 **2026-10-09 观测期反馈（两条）**
>
> **① 待处理项跨会话泄漏 —— 已修（v0.5.0-beta19）**：用户在会话 B 的页面上看到属于会话 A 的提问卡。根因是待处理项两路来源（会话流 / 任务事件流）合并成全局列表后被会话页整份渲染（`ConversationScreen.kt:595/598` 的 `forEach`）。修法：`approvalsForSession` / `elicitationsForSession` 纯函数 + 会话页按 `target.taskId` 过滤（`MainActivity.kt:98-99`），归属未知 fail-open；单测见 `relay/PendingScopeTest.kt`（5 项）。**待补**：真机 A/B 证据 —— 需要一条真实待处理项（本次工作区 pending 为空），下次自然出现时按「A 会话挂起 + 看 B 会话」核对即可，或按下面第 ② 条的探针路径造一条。
>
**② 表单应答失败 —— 待复现，暂不计为缺陷**
>
> **现象**：用户真机截图（21:02）显示「应答失败：[{code:"invalid_value", path:["answer","action"],
> values:["accept","decline","cancel"], message:"Invalid option: expected one of …"}]」，对应一条
> 带选项的表单交互（截图徽标「需要你的输入回答」⇒ `el.questions` 非空，见 `ApprovalsTab.kt:419`）。
>
> **host 侧 schema（权威证据）**：`research/asar/out/host/chunk-BG4MS6RN.js` 内联定义
> `resolveInteraction: answer:{optionId?, freeText?, action?:enum(["accept","decline","cancel"]), content?}` ——
> `action` 是**可选枚举**，报错说明实际发出的 `action` 是**枚举外的值**（`{freeText}` 这种「不带 action」不触发该错）。
>
> **代码侧排查（2026-10-09，全仓穷举）**：能产出 `action` 的位置只有 4 处 ——
> `AppViewModel.kt:958`（accept）、`:976`（decline）、`:991`（accept）、`notify/ElicitationNotifier.kt:179/186`（accept/decline）
> —— **不存在能产出非法 action 的路径**，且 `content` 为 `Record<string,unknown>` 亦合规。
>
> **最可能来源**：失败发生在 21:02，当时机上装的是一个**我们无法签名、来源不明的异源 debug 包**
> （SHA-256 `89:45:76:B3…`，本轮已卸载换成 beta18）。该包代码是否与本仓库一致**未知**。
>
> **待办（复现即定论）**：① 打开桌面端「移动端远程控制」面板（关着时中继回 `pair_status=waiting`，手机与探针都连不上，
> 本轮探针实测）；② 若该交互仍在 pending，从 **beta18** 重新提交一次；③ 同时抓
> `adb logcat -s ConvChannel` 中的 `resolveElicitation … answer=<JSON>` 原始载荷与 host 返回 —— 一次即可判定
> 「beta18 无此问题」或「确有缺陷」，后者按同款流程修复并重置观察窗口。

### v1.1 候选待办（未立项，仅登记 · 2026-10-07；2026-10-09 更新首批落地）

> 来源：《体验提升任务书 v2》。
> **2026-10-09 更新**：用户拍板「用户可感知收益」条目 → **档 C 首批 7 项已落地代码**（下表首行）；其余（A-3/A-4/T0/C-5/C-8/C-9/C-12、决策项）仍**未拍板不得开工**，前置条件见下表。

| ID | 内容 | 开工前置条件 |
|---|---|---|
| ✅ **档 C 首批**（**已落地 · 2026-10-09 · 未发布**） | C-6 发送本地回显、C-1 上传可取消/重试（uploadId 复用）、C-10 深链配对确认、C-11 横幅合并单队列、C-2 错误映射层（14 处）、C-4 浅色对比度 + 折叠摘要 + CI 断言、C-7 缓存删除与 LRU | **待真机验收 + release keystore 恢复后发布**（见顶部 🆕 段） |
| ✅ **无设备可验证项**（**已落地 · 2026-10-09 · 未发布**） | C-8 凭据结构化序列化（含旧格式迁移）；A-3 前置（103 编码 + 释放入口，**开关默认关闭**）；A-4 前置（缺口检测 + WARN 日志）；C-2 的 CI grep 断言 | **A-3 启用前**需真机 / `tools/probe.py` 探测 103 服务端行为；**A-4 重订阅与 buffer 改动**须独立真机回归 |
| ✅ **重连退避不重置**（**已修 · beta15 · 真机 PASS**） | `RelayClient` 退避 3s→6s→12s→24s→48s（封顶 48s），**网络恢复事件不重置退避**：约 90s 飞行模式往返后实测恢复 ~47s（基线 `<9s`）。修法：`NetworkGate.onAvailable` → `RelayClient.onNetworkAvailable()` 重置计数并**掐断正在 sleep 的退避**立即重连（须只对 `Paired`/`WaitingPeer` 提前返回，见 CHANGELOG 复盘）。**真机实测：断网 80s → 5s、150s → 3s** | 残留：`onLost` 的 `probeNow()` 去抖守卫仍会误判导致断网期空烧退避（不影响恢复延迟）——改动前需先验证其双网去抖理由 |
| ✅ **A-3**（**已落地 · 2026-10-09 · 未发布**） | 订阅监听泄漏：`TYPE_EVENT_DISPOSE = 103` 零调用 → 现已在切会话 / 重置时发 103 释放旧监听 | 字段规格（官方 bundle 实证 + `probe` 动态实测）与服务端行为（dispose 后事件流停止、10 次循环无泄漏）**均已确认**，`SEND_EVENT_DISPOSE = true`。**App 端到端（真机切 20 次会话）待并入真机验收** |
| **A-4** | 事件流丢帧无缺口检测 → 会话静默停在旧状态 | 需改 `RpcChannel` buffer 策略（`extraBufferCapacity=256 / DROP_OLDEST` → 照抄 `RelayClient` 的 `512 / SUSPEND` + 单泵），**动并发路径，任务书自标高危**，须独立 commit + 独立真机回归；且与 C-5⑤⑦ 同动 `ConversationFrames`/RowStore 状态机，**排期必须串行** |
| ✅ **T0**（**已 PASS 并回填**） | 握手/快照耗时打点（A-2 阈值的测量基础） | 整链路实测 **512–740ms**（见顶部真机验收清单第 5 条），**A-2 的 `HANDSHAKE_*_TIMEOUT_MS` 已据此校准为 4s/4s/5s**，不再是占位值 |
| **A-5**（新登记 · 2026-10-09 由 D-1 核查析出） | **逻辑帧分片未处理**：wireVersion 3 的 `kind:"fragment"` 信封在 App 侧被静默丢弃（`ConversationChannel.kt:257-259`），若真机出现会**缺帧**且 UI 无提示 | **未立项**。前置：真机 + 桌面端在线抓到一条真实 `fragment` 帧（字段规格见 `FRAME-CODEC.md:681-686`，但无实测样本）；拿到样本前不得盲写重组逻辑。与 A-4 同属「事件流完整性」，若一起做须串行 |
| **档 C 剩余（C-3/C-5/C-9/C-12）** | C-3 输入栏图标与键盘 inset 根因（须先真机量 inset；emoji 图标替换另需评估 `material-icons-extended` 包体）、**C-5 性能与线程模型**（自标高危，须独立灰度；**2026-10-10 已获真机实证**——长会话高频入站帧下主线程处理停摆 → 停 ack → 服务端判 `rpc-transport-fault` 降级桥 → 手机「异常」，见坑清单 28）、C-9 githubToken 加密存储（复用 MultiDeviceStore，勿引入 EncryptedSharedPreferences）、C-12 AppViewModel 拆分（二期） | 未拍板不得开工；**C-5 与 A-4 同动状态机，排期必须串行** |
| **决策项** | `ws://` 明文中继（minSdk 31 + 无 `networkSecurityConfig` → 静态即可定论必失败）：要么删选项，要么显式补配置（削弱安全性，需用户同意）；`reverseLayout` 翻转待 B-1 落地后评估 | 用户拍板 |

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

### P1-3 文件上传 / 语音输入（✅ 全链路完成；附件已真机验收，语音按钮在无识别引擎的设备上按设计不渲染）

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
- **遗留（2026-10-09 校正口径）**：
  1. 附件 chip 的真机端到端验收**已通过**（beta14：📎 → 小米 SAF 选文件 → 流内 chip 显示 → 发送 → userInput 行回显 chip），不再是待验项。
  2. ~~真机验证设备端识别引擎可用性~~ → **已有结论**：小米 15 Pro 国行裁剪了提供 `RecognitionService` 的引擎（Google app `enabled=0`、小爱不实现系统 `RecognitionService`），`SpeechRecognizer.isRecognitionAvailable=false` → 🎤 按钮**按设计不渲染**，降级路径正确（实测见 §1 时间线 2026-09-30）。因此「🎤 按压反馈 / 语音状态条」**在本机无设备可验**：要验需先装一个提供 RecognitionService 的引擎（如 Google app）。
  3. ~~大文件整块读进内存~~ → **已流式化（2026-09-30）**：`uploadAttachment` 改
     `openStream + totalBytes` 签名，两遍流（sha256 → 分片），内存峰值一倍分片；
     选中后文件被移删会在上传时报错（行为变化见 CHANGELOG 六轮）。

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

### P2-3 其他 P2（❌ 2026-10-05 用户决策：全部取消，不再开发）

> 以下项统一取消：文件/diff/Git 浏览、Wear OS 快捷审批、可选小米推送通道。

- **桌面 Widget（✅ 2026-09-30 已完成，保留）**：`widget/PendingWidgetProvider.kt` + `res/layout/widget_pending.xml` +
  `res/xml/widget_pending_info.xml`——待处理角标（RemoteViews 零依赖），App 内三处推送更新
  （计数变化 / 连接状态 / 断开），`updatePeriodMillis=0` 无轮询；点按打开 App。
- ⛔ 取消项说明：文件/diff/Git 浏览完整版依赖自建通道（M4/M5 已取消）；小米推送需开发者账号；
  Wear OS 可开发但验收卡硬件。均不推进。

### 技术债（随手清）

**2026-09-29 已清**：
- ✅ ~~`ConversationScreen` 移除设备/危险操作加确认弹窗~~ → HomeScreen「移除设备」已加二次确认弹窗。
- ✅ ~~`HomeScreen` 的 rpcEvents 调试面板移到 debug 构建~~ → 已用 `BuildConfig.DEBUG` 隔离（`buildConfig=true`）。
- ✅ ~~logcat 日志量大（每个 delta 一条 Info）~~ → `AppViewModel: rpc event` 已降为 `Log.d`。

**2026-09-29 二轮已清**：
- ✅ ~~「earlier-head」占位项与贴底索引偏移~~ → `ConversationScreen` 贴底滚动改用显式 `headerCount`
  修正（原 `rows.lastIndex` 漏算占位项，停在倒数第二行）；0 行时会话有 `rows.isNotEmpty()` 守卫。
- ✅ ~~elicitation 仅能在会话页应答~~ → D-3 通知栏快捷应答已实现。

**仍待处理（2026-10-09 按 D-1 核查结果重写）**：

> 原条目「会话流 `v4/conversation/frame` 二进制细节字段未穷举」经**有界核查**后收窄为下面两条。
> 核查结论先说：**delta op 层三方已完全对齐、无差集** —— 桌面端 host bundle
> （`research/asar/out/host/chunk-BG4MS6RN.js` 的 `mr=discriminatedUnion("op",[...])`，字节 ~2018/2264）
> 的 5 个 op `row.appended` / `row.upserted` / `row.removed` / `row.delta`（path 枚举 `text|inputText|output.text|summaryText`）
> / `state.updated` ↔ 文档 `CONVERSATION-PROTOCOL.md:142-146` ↔ App `relay/ConversationFrames.kt:515-538` 三方一致；
> 未知 op 走 `Delta.Unknown`（`ConversationChannel.kt:387-388` 仅记日志）。

- **① 逻辑帧分片未实现（新登记，此前没写在任何待办里）**：wireVersion 3 的 logical frame 有两种信封，
  `kind:"fragment"` 形态（`logicalFrameId` / `logicalFrameOrdinal` …，见 `FRAME-CODEC.md:681-686`）
  在 App 侧**被静默丢弃**（`relay/ConversationChannel.kt:257-259` 只记一条日志）。若真机出现该分支，
  表现为**会话流缺帧 / 卡在旧状态**，且 UI 无任何提示。
  - 现状判定：**无任何抓包证据表明该分支会被触发**（历史所有真机会话流均为 `kind:"complete"`）。
  - 开工前置：真机 + 桌面端在线抓一次 `kind:"fragment"` 的真实帧，拿到字段规格再实现重组；
    在拿到证据前**不盲目实现**（协议未验证的代码只会在坏路径上更难排查）。
- **② 桌面端 frame 业务 schema / 完整 row kind 联合仍未还原**：`FRAME-CODEC.md:773` 自认该表置信度低；
  本轮在 bundle 里只抓到 3 个 kind literal（`turnHeader` / `hookInvocation` / `timelineMarker`，见
  `research/asar/out/preload/index.cjs:40` 一带），未取全。风险可控：App 已渲染 8 种 kind
  （`ui/screens/ConversationScreen.kt:1493-1502`），其余落到 `PlainRow` 显示 `"{kind} #{rowId}"` 原文，
  **最坏是样式降级，不崩、不丢行**。若要继续穷举，需在 preload 的 zod 定义处按 `kind:e.literal(` 全量提取。

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
- **「协议做不到」类结论必须标注依据**（bundle 静态分析 or 真机实测），两者冲突以实测为准并回改文档
  （2026-10-02 新增，教训见 §6.0 勘误）。
- **App 侧永不提供文件写能力**（2026-10-05 采纳自八周计划评审）：远程读文件（`file.readTextFile`）
  只为查看 Agent 产物；写路径等价于把整台桌面交给手机——配对链接一旦泄露即远程任意写。
  若确有需求，单独走一轮安全评审再排期。
- **会话状态面板严格只读**（2026-10-08 beta17）：不新增任何控制类命令（cancelBackgroundWork / queueEdit / pauseGoal / resumeGoal / fork / compact），**不为面板新开查询 RPC**（因此不做「计划」分区，见 §5.11）。面板所需数据只能来自既有会话快照帧。
- **排版真机验收**（2026-10-08，beta17 已满足）：beta17 的排版对齐与状态面板已在小米 15 Pro（Android 17 · HyperOS）真机验收全通过，见 §4 基线表与 CHANGELOG。上机前须先用 `adb devices` 确认设备在线（adb 属常驻服务，操作受硬约束）。
- **模拟器验证 ≠ 真机验收**（2026-10-09 beta18 实践）：仪器化渲染测试跑在 AVD 上，证明的是「该渲染路径在 Android 15 + Compose 该版本下按预期绘制」，**不能**替代真机（Android 17 / HyperOS、真实字体与密度）验收。beta18 已按此原则走完两步：先模拟器（修复前 token 色命中 0 → 修复后 1789），再真机复核（2026-10-09，小米 15 Pro：token 色命中 2365、目视分色正常）——**两步都完成才算验收通过**。今后凡「模拟器/仪器化测试通过」的结论，一律走同样的补验流程，不得直接写成「真机已验证」。
