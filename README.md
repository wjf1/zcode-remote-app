# ZCode Remote

> [中文](#中文) | [English](#english)

<a name="中文"></a>

## 中文

ZCode 官方远程控制（`zcode.z.ai/remote`）的**原生安卓增强客户端**——Kotlin + Jetpack Compose 实现，深度对标 [zai-org/ZCode](https://github.com/zai-org/ZCode) 工业级设计规范与现代移动端交互架构（GitHub Mobile / Claude Mobile），复用官方中继与配对协议（逆向实证，见 [PROTOCOL.md](PROTOCOL.md)），提供比官方 Web 版更强的通知、审批、流式会话与移动端操控体验。

### 核心架构与功能一览

```
                  ┌──────────────────────────────────────────────┐
                  │           ZCode Remote (Scaffold)            │
                  └──────────────────────┬───────────────────────┘
                                         │
       ┌─────────────────────────────────┼─────────────────────────────────┐
       │ (Tab 1: 会话工作台)              │ (Tab 2: 待办审批 Inbox)         │ (Tab 3: 控制中心 Settings)
┌──────▼───────────┐              ┌──────▼───────────┐              ┌──────▼───────────┐
│   会话工作台     │              │  待办与审批看板  │              │  系统与设备设置  │
├──────────────────┤              ├──────────────────┤              ├──────────────────┤
│ • 设备状态呼吸灯 │              │ • 权限审批操作流 │              │ • 当前连接卡片   │
│ • 「+ 新建会话」 │              │ • 表单提问应答   │              │ • 多设备管理切换 │
│ • 实时搜索/过滤  │              │ • 实施计划确认   │              │ • 协议线路选择   │
│ • 流式会话卡片   │              │ • 倒计时进度条   │              │ • HyperOS 保活   │
│ • 运行中状态高亮 │              │ • 历史待办留档   │              │ • 在线检查更新   │
└──────────────────┘              └──────────────────┘              └──────────────────┘
```

- **现代化 3-Tab 移动端架构**：彻底告别单页混乱堆叠，采用「会话工作台 / 待办审批 / 设置中心」标准底部导航架构；全面接入系统级物理返回键拦截（`BackHandler`），杜绝手势误杀退出的问题。
- **深度对标官方 ZCode 工业级设计**：
  - 全量接入官方 `theme-zai-dark`（`#161616`）与 `theme-zai-light`（`#F8F8FA`）色彩体系，搭配官方 15% 微边框（Hairline Border）；
  - 引入官方 Trajectory 四色轨迹语义：用户消息蓝（`#60A5FA`）、助手响应青（`#2DD4BF`）、思考轨迹紫（`#A78BFA`）、工具调用琥珀橙（`#F59E0B`）；
  - 全面淘汰杂乱的系统 Emoji，统一采用规范的 Material 矢量图标；
  - 官方同款深度思考组件（`ReasoningBlock`）：紧凑紫色折叠胶囊 + 平滑展开动画 + 标志性左侧弱引导竖线 + 一键复制；
  - 官方紧凑工具卡片（`ToolCallCard`）：类型标签徽章 + 状态徽标 + 终端代码框格式化展开。
- **移动端一键发起新会话**：会话工作台顶栏直接「+ 新建会话」，基于官方 V4 原生信封链路（`sendConversationCommandV4(type: "createSession", sessionId: null)`），支持首条 Prompt 输入与实时语音识别填充，创建后直接切入流式对话窗口。
- **集中式审批与待办看板**：底部导航栏实时角标（Badge）提醒，集中看板统一处理权限审批（允许一次 / 总是允许 / 拒绝）与表单交互（AskUserQuestion / 计划确认），带桌面端决议倒计时。
- **通知栏锁屏审批（核心差异点）**：桌面端请求权限时，锁屏状态下收到高优先级系统通知，**通知栏直接批准/拒绝**。
- **扫码配对**：扫描桌面端二维码即完成配对，凭据经 AES-256-GCM + Android Keystore 加密存储；支持手动粘贴链接兜底；支持多台设备管理（切换 / 移除）。
- **会话流式渲染与代码块自适应**：原生 Markdown 渲染支持各级标题、粗体、列表、引用，代码块自动适配深浅双色主题并配备一键复制反馈；滚到顶部自动翻页加载更早历史。
- **发送消息与停止**：会话页底部悬浮药丸输入栏直接向桌面端发消息；会话运行中显示平滑变形的「停止」按钮，一键中断。
- **附件上传**：会话页选择文件（≤20MiB）→ 分片上传 → 随消息发送，桌面端模型可直接读取内容；走官方 Web 同款 `sendText` 附件链路。
- **语音输入**：输入栏 🎤 系统语音识别转文字（识别中实时上屏），一键追加到消息草稿，零协议改动。
- **桌面 Widget**：主屏卡片实时显示待处理总数（审批 + 表单交互），点按直达 App；连接断开时明示「未连接」。
- **可靠连接**：完整官方握手（HMAC proof）、心跳、指数退避重连、断线出站缓冲，单端在线互踢提示。

### 界面一览

| 会话工作台 | 待办与审批看板 | 沉浸式对话控制台 | 独立系统设置 |
|:---:|:---:|:---:|:---:|
| ![会话列表](docs/screenshots/app-sessions.png) | ![待办看板](docs/screenshots/app-approval-notification.png) | ![会话流](docs/screenshots/app-conversation.png) | ![运行中](docs/screenshots/app-working.png) |

### 里程碑状态

| 里程碑 | 内容 | 状态 |
|---|---|---|
| M0 协议逆向 | PC/手机两侧 bundle 交叉实证，产出 [PROTOCOL.md](PROTOCOL.md) | ✅ 完成 |
| M1 骨架+配对+会话 | 扫码配对、中继连接、会话列表、事件流 | ✅ 模拟器验收通过 |
| M2 审批与推送 | 会话流实时渲染、权限审批（会话内 + 通知栏）、双源审批接收 | ✅ 端到端验收通过（2026-09-28） |
| M3 打磨与内测 | 多机管理、线路切换、HyperOS 保活引导、异常兜底 | ✅ 主体完成 |
| M3+ 交互增强 | 发送/停止、表单类交互应答、多会话看板与控制权提示、附件上传（全链路实测）、语音输入、桌面 Widget、会话搜索 | ✅ 协议层端到端验收通过 |
| M3++ 移动端全面重构 | 底部 3-Tab 移动架构、官方 ZCode 设计系统对齐（zai-dark/zai-light）、集中待办看板、独立设置中心、原生新建会话、BackHandler 拦截与深浅主题自适应 | ✅ 完成（v0.5.0-beta1，2026-10-01） |
| ~~M4/M5~~ | ~~VPS 备用 Runner、E2E 高级模式~~ | ❌ 已取消（2026-09-30 决策：单人自用下成本收益不划算，详见 HANDOVER） |

### 接力开发 / Handover

剩余开发计划与交接文档见 [HANDOVER.md](HANDOVER.md)（自包含，面向 AI agent 直接接手）。

### 安装包 / Releases

签名 APK 从 [GitHub Releases](https://github.com/wjf1/zcode-remote-app/releases) 下载
（如 `ZCodeRemote-0.5.0-beta1.apk`，minSdk 31，Android 12+）。

### 快速开始

无需 Android Studio，本机工具链（JDK 17 + Gradle + Android SDK）就绪于 `toolchain/`：

```bash
./build.sh           # 构建 APK
./build.sh install   # 构建 + 安装到设备/模拟器 + 启动
./build.sh log       # 查看中继日志
```

详见 [app-android/README.md](app-android/README.md)。

### 安全边界

本项目仅连接**本人自己的** ZCode 账号/设备（暂不开源、不分发）。走官方中继无端到端加密，凭据与消息对中继服务器可见；二维码泄露等于控制权泄露，App 端已做凭据加密存储。

---

<a name="english"></a>

## English

A **native Android client** for the official ZCode Remote Control relay (`zcode.z.ai/remote`), built with Kotlin + Jetpack Compose. Deeply aligned with the [zai-org/ZCode](https://github.com/zai-org/ZCode) design specifications and modern mobile interaction patterns (GitHub Mobile / Claude Mobile), it reuses the official pairing protocol and relay (reverse-engineered and verified, see [PROTOCOL.md](PROTOCOL.md)) to deliver a far superior notification, approval, live streaming, and session experience.

### Architecture & Features

```
                  ┌──────────────────────────────────────────────┐
                  │           ZCode Remote (Scaffold)            │
                  └──────────────────────┬───────────────────────┘
                                         │
       ┌─────────────────────────────────┼─────────────────────────────────┐
       │ (Tab 1: Sessions)               │ (Tab 2: Approvals / Inbox)      │ (Tab 3: Settings)
┌──────▼───────────┐              ┌──────▼───────────┐              ┌──────▼───────────┐
│   Sessions Tab   │              │  Approvals Inbox │              │   Settings Tab   │
├──────────────────┤              ├──────────────────┤              ├──────────────────┤
│ • Pulse status   │              │ • Permission flow│              │ • Active device  │
│ • "+ New Session"│              │ • Form questions │              │ • Multi-device   │
│ • Search/Filter  │              │ • Plan approval  │              │ • Relay endpoint │
│ • Live stream    │              │ • Countdown bar  │              │ • Keep-alive guide│
│ • Running status │              │ • History archive│              │ • Update checker │
└────────────────┘              └──────────────────┘              └──────────────────┘
```

- **Modern 3-Tab Mobile Architecture**: Say goodbye to crowded single-page layouts. Clean navigation across **Sessions Workbench**, **Approvals Inbox**, and **Settings Center**; full integration with system `BackHandler` prevents accidental exits.
- **Aligned with Official ZCode Industrial Design**:
  - Full support for `theme-zai-dark` (`#161616`) and `theme-zai-light` (`#F8F8FA`), paired with 15% hairline borders for clean, calm contrast;
  - Official Trajectory colors: User Blue (`#60A5FA`), Assistant Teal (`#2DD4BF`), Reasoning Purple (`#A78BFA`), and ToolCall Amber (`#F59E0B`);
  - Replaced ad-hoc emojis with crisp, standard Material vector icons;
  - Official `ReasoningBlock`: Sleek collapsible purple pill + smooth expand animation + vertical guide line + one-tap copy;
  - Official `ToolCallCard`: Compact tool badge + status chip + formatted terminal code block.
- **Create New Sessions from Mobile**: One-tap "+ New Session" on the workbench header powered by the official V4 envelope command (`sendConversationCommandV4(type: "createSession", sessionId: null)`), with text or speech recognition input.
- **Centralized Approvals Inbox**: Dedicated tab with live Badge counter for pending permissions and form interactions (`AskUserQuestion`, plan approvals) with desktop auto-resolution countdown.
- **Lock-screen Notifications (Key Differentiator)**: Approve/deny directly from system notifications without unlocking the screen.
- **QR Pairing & Multi-device Management**: AES-256-GCM + Android Keystore encrypted credential storage, manual link fallback, and multi-device switching/deletion.
- **Markdown & Code Rendering**: Adaptive light/dark code blocks with one-tap copy confirmation; auto-pagination when scrolling to top.
- **Send & Stop**: Pill-shaped floating composer sends prompts; morphing Stop button interrupts running turns.
- **Chunked Attachments**: Pick files (≤20 MiB) in conversation, chunked stream upload, sent via official `sendText` attachment path.
- **Voice Input**: Tap-to-talk speech recognition with live partial results appended to drafts.
- **Home-Screen Widget**: Live card showing total pending items, tap to jump into the app.
- **Reliable Connectivity**: HMAC handshake proof, 30s heartbeat, exponential backoff, and offline outbound queue.

### Milestones

| Milestone | Scope | Status |
|---|---|---|
| M0 protocol RE | Cross-verified both desktop & mobile bundles → [PROTOCOL.md](PROTOCOL.md) | ✅ Done |
| M1 skeleton+pairing+sessions | QR pairing, relay connection, session list, event stream | ✅ Verified on emulator |
| M2 approvals & push | Live conversation streaming, permission approvals (in-app + notification shade), dual-source approval intake | ✅ E2E verified (2026-09-28) |
| M3 polish & beta | Multi-device, endpoint switching, HyperOS keep-alive guide | ✅ Core done |
| M3+ interactions | Send & stop, form-style interaction responses, multi-session board, attachments, voice input, home-screen widget, search | ✅ E2E verified at protocol level |
| M3++ Mobile UI/UX Overhaul | Modern 3-Tab architecture, official ZCode design system alignment (zai-dark/zai-light), dedicated inbox, native createSession, BackHandler & adaptive theme | ✅ Done (v0.5.0-beta1, 2026-10-01) |
| ~~M4/M5~~ | ~~VPS backup runner, E2E advanced mode~~ | ❌ Cancelled (2026-09-30: cost/benefit not worth it for single-user, see HANDOVER) |

### Releases

Download signed APKs from [GitHub Releases](https://github.com/wjf1/zcode-remote-app/releases)
(e.g., `ZCodeRemote-0.5.0-beta1.apk`, minSdk 31, Android 12+).

### Quick Start

No Android Studio required — toolchain (JDK 17 + Gradle + Android SDK) is in `toolchain/`:

```bash
./build.sh           # build APK
./build.sh install   # build + install to device/emulator + launch
./build.sh log       # tail relay logs
```
