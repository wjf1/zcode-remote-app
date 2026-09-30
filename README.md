# ZCode Remote

> [中文](#中文) | [English](#english)

<a name="中文"></a>

## 中文

ZCode 官方远程控制（`zcode.z.ai/remote`）的**原生安卓增强客户端**——Kotlin + Jetpack Compose 实现，复用官方中继与配对协议（逆向实证，见 [PROTOCOL.md](PROTOCOL.md)），提供比官方 Web 版更强的通知、审批与会话体验。

### 功能一览

- **扫码配对**：扫描桌面端二维码即完成配对，凭据经 AES-256-GCM + Android Keystore 加密存储；支持手动粘贴链接兜底；支持多台设备管理（切换 / 移除）。
- **会话列表与流式对话**：工作区分组的会话卡片（运行状态高亮），点开会话可实时查看流式输出（思考过程 / 工具调用 / 正文），滚到顶部自动翻页加载更早历史，支持按标题 / 工作区搜索过滤。
- **发送消息与停止**：会话页底部输入栏直接向桌面端发消息（入会话队列，当前 turn 结束后自动执行）；会话运行中显示「停止」按钮，一键中断。
- **权限审批（核心差异点）**：桌面端请求权限时，锁屏状态下收到高优先级通知，**通知栏直接批准/拒绝**（允许一次 / 总是允许 / 拒绝），并显示桌面端自动决议倒计时。
- **表单类交互应答**：`AskUserQuestion` / 计划批准（plan_approval）/ 确认框等 elicitation 在 App 内直接作答——单选、多选、自由文本、拒绝。
- **附件上传**：会话页 📎 选择文件（≤20MiB）→ 分片上传 → 随消息发送，桌面端模型可直接读取内容；走官方 Web 同款 `sendText` 附件链路，端到端实测通过。
- **语音输入**：输入栏 🎤 系统语音识别转文字（识别中实时上屏），一键追加到消息草稿，零协议改动。
- **多会话并行看板**：首页会话卡片显示「⏳ 待处理 N」角标并高亮，标出「当前」（订阅中）与「PC 在看」（桌面端打开）的会话；控制权被官方 Web 版/另一终端接管时顶部横幅提示。
- **桌面 Widget**：主屏卡片实时显示待处理总数（审批 + 表单交互），点按直达 App；连接断开时明示「未连接」。
- **可靠连接**：完整官方握手（HMAC proof）、心跳、指数退避重连、断线出站缓冲，单端在线互踢提示。

### 截图

| 会话列表 | 会话流 | 通知栏审批 | 运行中 |
|:---:|:---:|:---:|:---:|
| ![会话列表](docs/screenshots/app-sessions.png) | ![会话流](docs/screenshots/app-conversation.png) | ![通知栏审批](docs/screenshots/app-approval-notification.png) | ![运行中](docs/screenshots/app-working.png) |

### 里程碑状态

| 里程碑 | 内容 | 状态 |
|---|---|---|
| M0 协议逆向 | PC/手机两侧 bundle 交叉实证，产出 [PROTOCOL.md](PROTOCOL.md) | ✅ 完成 |
| M1 骨架+配对+会话 | 扫码配对、中继连接、会话列表、事件流 | ✅ 模拟器验收通过 |
| M2 审批与推送 | 会话流实时渲染、权限审批（会话内 + 通知栏）、双源审批接收 | ✅ 端到端验收通过（2026-09-28） |
| M3 打磨与内测 | 多机管理、线路切换、HyperOS 保活引导、异常兜底 | ✅ 主体完成 |
| M3+ 交互增强 | 发送/停止、表单类交互应答、多会话看板与控制权提示、附件上传（全链路实测）、语音输入、桌面 Widget、会话搜索 | ✅ 协议层端到端验收通过（UI 层待真机） |
| ~~M4/M5~~ | ~~VPS 备用 Runner、E2E 高级模式~~ | ❌ 已取消（2026-09-30 决策：单人自用下成本收益不划算，详见 HANDOVER） |

### 接力开发 / Handover

剩余开发计划与交接文档见 [HANDOVER.md](HANDOVER.md)（自包含，面向 AI agent 直接接手）。

### 安装包 / Releases

签名 APK 从 [GitHub Releases](https://github.com/wjf1/zcode-remote-app/releases) 下载
（如 `ZCodeRemote-0.4.0-beta3.apk`，minSdk 31，Android 12+）。

### 快速开始

无需 Android Studio，本机工具链（JDK 17 + Gradle + Android SDK）就绪于 `toolchain/`：

```bash
./build.sh           # 构建 APK
./build.sh install   # 构建 + 安装到设备/模拟器 + 启动
./build.sh log       # 查看中继日志
```

详见 [app-android/README.md](app-android/README.md)。

### 文档

- [方案文档（竞品/逆向/架构/里程碑）](ZCode远程控制安卓APP方案.md)
- [PROTOCOL.md（中继协议，含实测修正）](PROTOCOL.md)
- [research/CONVERSATION-PROTOCOL.md（会话流协议）](research/CONVERSATION-PROTOCOL.md)
- [tools/（probe 协议探针 / setmode 切模式 / 验收脚本）](tools/)

### 安全边界

本项目仅连接**本人自己的** ZCode 账号/设备（暂不开源、不分发）。走官方中继无端到端加密，凭据与消息对中继服务器可见；二维码泄露等于控制权泄露，App 端已做凭据加密存储。E2E 需求由 M5「高级模式」（自建 bridge + NaCl）满足。

---

<a name="english"></a>

## English

A **native Android client** for the official ZCode Remote Control relay (`zcode.z.ai/remote`), built with Kotlin + Jetpack Compose. It reuses the official pairing protocol and relay (reverse-engineered and verified, see [PROTOCOL.md](PROTOCOL.md)) and delivers a stronger notification / approval / session experience than the official web app.

### Features

- **QR pairing**: scan the desktop QR code and you're paired; credentials are stored with AES-256-GCM + Android Keystore, with manual link paste as fallback and multi-device management (switch / remove).
- **Sessions & live streaming**: session cards grouped by workspace with status highlight; tap in to watch streaming output (reasoning / tool calls / assistant text) in real time, with automatic pagination when you scroll to the top and search/filter by title or workspace.
- **Send & stop**: a composer at the bottom of the conversation sends prompts straight to the desktop (queued and executed after the current turn); while a session is running a **Stop** button interrupts it.
- **Permission approvals (key differentiator)**: when the desktop agent requests permission, a high-priority notification arrives even on the lock screen — **approve/deny right from the notification shade** (Allow once / Always allow / Deny), with the desktop auto-resolution countdown shown.
- **Form-style interactions**: `AskUserQuestion`, plan approvals (`plan_approval`) and confirmation prompts are answered right in the app — single choice, multi-select, free text, or decline.
- **Attachments**: pick a file (≤20 MiB) via 📎 in the conversation, chunked upload, then send it with the message — the desktop agent reads the content directly. Uses the same `sendText` attachment path as the official web app, verified end to end.
- **Voice input**: 🎤 tap-to-talk via the system speech recognizer with live partial results; recognized text is appended to the message draft, with zero protocol changes.
- **Multi-session board**: each session card shows a "⏳ N pending" badge and highlight, with "current" (subscribed) and "PC viewing" (open on desktop) markers; a banner appears when control is taken over by the official web app or another terminal.
- **Home-screen widget**: a live card showing the total pending count (approvals + form interactions), tap to jump into the app; an explicit "not connected" state when the relay is down.
- **Reliable connection**: full official handshake (HMAC proof), heartbeat, exponential-backoff reconnect, offline outbound buffering, and single-terminal kick handling.

### Milestones

| Milestone | Scope | Status |
|---|---|---|
| M0 protocol RE | Cross-verified both desktop & mobile bundles → [PROTOCOL.md](PROTOCOL.md) | ✅ Done |
| M1 skeleton+pairing+sessions | QR pairing, relay connection, session list, event stream | ✅ Verified on emulator |
| M2 approvals & push | Live conversation streaming, permission approvals (in-app + notification shade), dual-source approval intake | ✅ E2E verified (2026-09-28) |
| M3 polish & beta | Multi-device, endpoint switching, HyperOS keep-alive guide | ✅ Core done |
| M3+ interactions | Send & stop, form-style interaction responses, multi-session board, attachments (full path verified), voice input, home-screen widget, session search | ✅ E2E verified at protocol level (UI pending real device) |
| ~~M4/M5~~ | ~~VPS backup runner, E2E advanced mode~~ | ❌ Cancelled (2026-09-30: cost/benefit not worth it for single-user, see HANDOVER) |

### Handover

The remaining roadmap and handover notes live in [HANDOVER.md](HANDOVER.md) (self-contained, written for an AI agent to pick up directly).

### Install / Releases

Signed APKs are published on the [GitHub Releases](https://github.com/wjf1/zcode-remote-app/releases) page
(e.g. `ZCodeRemote-0.4.0-beta3.apk`, minSdk 31, Android 12+).

### Quick start

No Android Studio required — the local toolchain (JDK 17 + Gradle + Android SDK) lives in `toolchain/`:

```bash
./build.sh           # build APK
./build.sh install   # build + install to device/emulator + launch
./build.sh log       # tail relay logs
```

See [app-android/README.md](app-android/README.md) for details.

### Security notes

This project connects **only to the owner's own** ZCode account/devices (private, not distributed). The official relay has no end-to-end encryption — credentials and messages are visible to the relay server; a leaked QR code equals leaked control. The app encrypts credentials at rest; E2E will come with the M5 "advanced mode" (self-hosted bridge + NaCl).
