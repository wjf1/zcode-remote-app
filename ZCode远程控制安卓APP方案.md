# ZCode 远程控制管理 Android APP 方案（V0.2 待审核）

> 目标设备：小米 15 Pro（HyperOS 2 / Android 15，120Hz，6.73" 2K）
> 被控端：PC 上的 ZCode（`E:\ZCode`，Electron 桌面端 3.11.2，内置官方远程控制与 Bot Channel）
> V0.2 变更：逆向了 ZCode 内置远程控制机制，技术路线改为 **A（官方中继兼容客户端）起步**，里程碑重排。

---

## 一、竞品分析（GitHub 对标）

| 项目 | Stars / 许可 | 形态 | 连接方式 | 核心功能 | 界面特点 |
|---|---|---|---|---|---|
| [slopus/happy](https://github.com/slopus/happy) | ~23.8k★ / MIT | Expo(RN) 原生 App + CLI 包装器 + 同步服务端 | `happy claude` 包装 CLI，加密同步到手机 | 会话同步、权限/出错推送通知、手机↔电脑一键切换控制权、端到端加密、可自托管 | 会话卡片列表 + 对话流视图；桌面版带文件/diff/终端并排 |
| [siteboon/claudecodeui](https://github.com/siteboon/claudecodeui) (CloudCLI) | ~13.7k★ / AGPL-3.0 | Web UI（响应式）+ Node 后端 + Electron 伴侣 | Node 后端直接发现 `~/.claude` 会话，读写同一份配置 | 聊天、内置 Shell 终端、文件浏览器（语法高亮/实时编辑）、Git 浏览器（暂存/提交/切分支）、会话恢复、插件系统、MCP 配置 | 桌面：项目总览+聊天；移动：触摸导航；工具权限默认关闭手动开 |
| [omnara-ai/omnara](https://github.com/omnara-ai/omnara) | ~2.8k★ / Apache-2.0 | Agent 运行平台（Go + Postgres + React），REST API/CLI | 托管 Agent 平台，机器/沙箱动态接入，RBAC | 持久化 Agent（崩溃自动恢复）、多机管理、自选模型/工具、组织级权限 | 面向团队控制台，移动端非其重心 |
| Claude Code 官方 Remote Control | 官方 | 官方手机 App + CLI `/remote-control` 配对 | 官方通道 | 手机上控制 CLI 会话 | 已知缺陷：会话易失联、缺取消按钮（issue #52796 / #47127）——是差异化机会 |
| CC Pocket（[作者复盘](https://dev.to/k9i/i-built-a-mobile-app-to-control-claude-code-and-codex-from-my-phone-4d84)） | 闭源 | 移动 App | — | 手机上控制 Claude Code / Codex | 验证了"手机控 Agent"的真实需求 |

**关键结论：**
1. 主流成熟路线是 **"PC 端桥接守护进程 + 手机 App + 中继"**；Web-UI 响应式方案省开发但推送弱于原生。
2. 差异化价值点：**权限审批推送 + 通知栏一键批准/拒绝**、**断线自动恢复**、**手机↔PC 控制权无缝切换**。
3. ZCode 用户比别人多一个起点：**官方已内置远程控制和中继**（见第二节），我们的 App 站在它肩膀上做原生体验即可。

---

## 二、ZCode 内置远程控制机制剖析（逆向 `app.asar` 实证）

> 分析对象：`E:\ZCode\resources\app.asar`（main/host/renderer 包），以下均为代码实证，非猜测。

### 2.1 总体链路

```
PC ZCode ──(出站 WSS，路径 /ws/host)──▶ wss://zcode.z.ai/ws ◀──(WSS，路径 /ws)── 手机
                                          │
     手机端为官方托管 Web App：https://zcode.z.ai/remote/v4（旧版 v3）
```

- 本质是 **Z.ai 官方运营的中继**：PC 主动向外连 WebSocket，天然无 NAT/端口暴露问题；"扫码即用"是因为中继和手机端 Web App 都是现成的。
- 主线路 `wss://zcode.z.ai/ws`，备用线路 `wss://zcode.chatglm.site/ws`（按 `endpointOrigin` 切换）。
- 端点族：`/api/server-info`（服务发现）、`/api/rpc-host-capability`（能力协商）、`/ws`（手机侧）、`/ws/host`（PC 侧）。

### 2.2 配对与凭据

- 首次开启生成 `deviceSid`（设备会话 ID）+ `passHash`，持久化在设置项 `webRemoteControlExternalRelayDevice` + 凭据库（本地落盘用 AES-256-GCM 加密，`enc:v1:` 前缀）。
- **二维码 = 一个 URL**（`buildWebRemoteControlExternalQrUrl`）：
  `https://zcode.z.ai/remote/v4?sid=<deviceSid>&hash=<passHash>&t=<时间戳>&mid=<机器ID>&name=<PC名>&app_version=..&theme=..`
  扫码即把 sid/hash 交给手机端 → **扫到码 = 拿到控制权**。

### 2.3 传输与可靠性

- 传输加密 **仅 TLS，无端到端加密**：中继（官方服务器）理论上可见全部明文。
- 断线缓冲：出站消息队列 `pendingOutboundPayloads`（上限 50 条，溢出整批丢弃 + 告警日志），手机短暂断连不丢消息。
- 手机连接状态机：idle / starting / waiting（等待手机连接）/ active / error，UI 文案与状态字段一一对应。

### 2.4 备选通道：Bot Channel

- 走聊天平台路线：微信（`ilinkai.weixin.qq.com` 轮询，本地有 `weixin-polling` 运行锁）、飞书/Lark（OpenAPI）、Telegram Bot API。
- 聊天命令驱动会话：`status / new / workspace / model / mode / thoughtLevel / reply`（见 `bot-config.json` 的 `allowedCommands`），支持任务创建/恢复/后台执行（`createTask` / `resumeTask` / `sendPrompt`）。
- 定位是"轻命令交互"，适合长时间挂着，但不是完整 GUI。

### 2.5 可替换性（对我们最重要的发现）

客户端支持环境变量把官方组件指到第三方服务器：
- `ZCODE_WEB_REMOTE_CONTROL_RELAY_WS_URL` → 自建中继 WS 地址
- `ZCODE_WEB_REMOTE_CONTROL_URL` → 自建手机端 Web 入口

---

## 三、技术路线决策

| 路线 | 说明 | 优点 | 缺点 |
|---|---|---|---|
| **A. 官方中继兼容客户端（本版主路线）** | App 直连 `wss://zcode.z.ai/ws`，复用官方配对协议（扫码提取 sid/hash），做原生 UI + 通知 | 零中继成本、零端口暴露、内外网天然全通、与官方 Web 版共存、开发量最小 | 协议靠逆向、官方升级可能破坏（需适配层）；明文过官方服务器（无 E2E）；非官方客户端的合规/风控风险 |
| B. 自建 bridge + 自有加密协议 | 独立守护进程包装 ZCode，NaCl E2E，自建中继（VPS） | 完全可控、可 E2E、不依赖官方 | 需自运营中继；开发量大一倍；首版无法快速验证 |
| A→B 演进 | A 起步验证产品，后期把"B"作为高级模式（利用 2.5 的环境变量把官方链路指到自建服务器） | 快速上市 + 长期可控 | 双协议维护成本 |

**决定：按路线 A 起步**（用户已拍板），B 作为 P2 演进项。

---

## 四、产品定位与功能方案

**定位**：ZCode 官方远程控制的原生安卓增强客户端——比官方 Web 版更强的通知、审批、会话管理体验；进阶后覆盖"PC 关机也能跑任务"。

### MVP（P0，路线 A）
| 模块 | 功能 |
|---|---|
| 配对 | 内置扫码器扫官方二维码（解析 `sid/hash/mid/name`），凭据存 Android Keystore；支持多 PC（多机列表） |
| 会话 | 工作区/会话列表（状态：运行中/等待审批/已完成）、新建会话、续跑历史会话 |
| 对话 | 流式输出渲染（Markdown + 代码高亮）、发送消息、**中断/停止按钮**（补官方缺失去的能力） |
| 审批 | 权限请求高优先级推送，**通知栏直接 批准/拒绝** |
| 状态 | 任务完成/报错推送；WS 长连接前台服务 + 指数退避自动重连 + 断线出站缓冲（对标官方 50 条队列） |
| 设置 | 线路选择（z.ai 主线 / chatglm.site 备线 / 自定义）、通知、深色模式 |

### P1
- **PC 关机跑任务**：添加 VPS 作为备用 Runner（同一路径接入，会话服务端可恢复；任务需基于 Git 仓库或无文件依赖，VPS 月成本 ¥10~40）
- 多会话并行看板；手机↔PC 控制权切换提示
- 文件上传（拍照/选文件喂给会话）、语音输入

### P2
- 自有加密协议"高级模式"（路线 B：自建 bridge + NaCl E2E + 自建中继，通过官方环境变量兼容接入）
- 文件/diff/Git 浏览（参考 CloudCLI）
- Wear OS 快捷审批、桌面 Widget

---

## 五、架构方案（路线 A）

```
┌─ 小米15Pro ─────────────┐          ┌─ Z.ai 官方云 ──────────┐          ┌─ PC（Windows）─┐
│ ZCode Remote (Kotlin+    │◀─WSS /ws▶│  wss://zcode.z.ai/ws   │◀─/ws/host─│ ZCode 桌面端    │
│ Compose)                 │  TLS     │  配对路由 + 消息中继      │  (出站连接) │ (无需任何改造)  │
│ 原生UI/审批通知/前台服务    │          └────────────────────────┘           └───────────────┘
└──────────┬───────────────┘
           │ P1: 同一 App 再挂一台 VPS Runner（PC 关机时的备用执行机，走 Git 同步代码）
```

**要点：**
1. **零 PC 端开发**：PC 上不加任何进程，完全复用官方远程控制开关与中继——这是路线 A 最大的工程红利。
2. **App 技术栈：Kotlin + Jetpack Compose 原生**：目标机型单一；对 HyperOS 的前台服务、通知、电池白名单控制最好（推送可靠性命门）。
3. **协议适配层**：所有中继消息解析集中在一个 `RelayProtocol` 模块 + schema 版本探测，官方升级时只改这一层。
4. **国内推送**：不依赖 FCM；App 内 WS 长连接前台服务保活，引导页带用户完成 HyperOS 自启动 + 省电白名单设置；可选接小米推送。
5. **安全边界（如实告知）**：走官方中继无 E2E，凭据（sid/hash）与消息明文对官方服务器可见；二维码泄露 = 控制权泄露，App 内做凭据加密存储 + 会话有效期提示。E2E 需求由 P2 路线 B 满足。

---

## 六、界面设计（对标 happy + CloudCLI + 官方 Web 版）

底部 4 Tab（Material 3，深色优先）：

1. **会话**（首页）：工作区分组的会话卡片（状态圆点+摘要），悬浮 "+" 新建；对话流（流式 Markdown、代码块横滚、底部输入栏 + 停止按钮），等待审批时输入栏上方出现**醒目审批条（批准/拒绝）**。
2. **机器**：PC 列表（对应官方 deviceSid/mid/name）、在线状态、扫码添加新机、线路选择。
3. **Runner**（P1）：VPS 备用执行机管理、任务队列、PC 关机时的任务落点显示。
4. **设置**：通知与保活引导（HyperOS 自启动/省电白名单分步图文）、深色模式、协议线路、关于/协议版本。

系统通知：权限请求/任务完成 → 高优先级通知 + 通知栏双按钮（批准/拒绝），点进直达会话。

---

## 七、里程碑（按路线 A 重排）

| 阶段 | 内容 | 预估 | 关键产出/验收 |
|---|---|---|---|
| **M0 协议逆向** | 抓包 PC↔官方中继 WS 流量（mitmproxy + 自签 CA，PC 侧流量可解），还原：握手鉴权、心跳、配对、会话/审批/输出事件、命令下发，输出 `PROTOCOL.md` + 消息 schema 定义 | 3~5 天 | 用脚本模拟手机端完成一次"配对→发消息→收流式输出→批准权限"闭环 |
| **M1 App 骨架 + 配对 + 会话** | Compose 骨架、扫码配对、Keystore 存储、连接中继、会话列表 + 流式对话 + 发送/停止 | 1.5 周 | 手机上完整用一次 ZCode（内外网各测一次） |
| **M2 审批与推送** | 权限审批事件监听、高优先级通知、通知栏批准/拒绝、完成/报错通知、前台服务 + 自动重连 | 1 周 | 锁屏状态下收到审批并成功批准 |
| **M3 打磨与内测** | 多机管理、线路切换、深色模式、HyperOS 保活引导页、异常兜底（协议变更检测） | 1 周 | 小米 15 Pro 日常可用，发内测 APK |
| **M4 P1：VPS 备用 Runner** | Runner 接入（同协议）、PC 关机任务落 VPS、Git 仓库同步引导 | 1.5 周 | PC 关机，手机新建任务并在 VPS 跑完 |
| **M5 P2：高级模式** | 自建 bridge + NaCl E2E + 自建中继，官方环境变量兼容接入；文件/diff/Git | 2~3 周 | E2E 链路演示 |

> M0 是全方案的最大技术风险收敛点：**如果 M0 证实协议有签名/加密保护无法模拟，立即降级回路线 B**（自建 bridge），前序结论可复用。

---

## 八、风险清单

| 风险 | 等级 | 对策 |
|---|---|---|
| 官方协议随版本升级破坏（混淆代码 3.11.2 为准） | 高 | 协议适配层 + schema 版本探测 + 兼容性测试用例；锁版本建议写进用户文档 |
| 非官方客户端连官方服务的风控/合规 | 中 | 仅个人自用默认不开源分发凭据；不提供多云账号聚合；如需商业化先与 Z.ai 沟通 |
| 无 E2E，明文过官方服务器 | 中 | 文档明示安全边界；P2 提供 E2E 高级模式 |
| HyperOS 杀后台导致推送失灵 | 中 | 前台服务 + 自启动/省电白名单引导页 + 可选小米推送通道 |
| 二维码/凭据泄露 = 控制权泄露 | 中 | Keystore 加密存储、配对后可引导用户在 PC 端"刷新二维码"使旧码失效（官方有刷新按钮对应的能力） |
| VPS 跑任务需要代码在 Git | 低 | M4 提供 Git 同步引导；非 Git 任务明确提示限制 |

---

## 九、决策记录

1. 技术栈：**Kotlin 原生**（已定）；技术路线：**A 起步，B 为 P2 高级模式**（已定）。
2. **已确认（2026-09-13）：M0 逆向产物 `PROTOCOL.md` 须单独审核通过后，才启动 M1 App 开发。**
3. **已确认（2026-09-13）：VPS 备用 Runner（M4）进首版排期**（PC 关机跑任务为正式交付项，含 VPS 采购与 Git 同步引导）。
4. **已确认（2026-09-13）：暂不开源，纯自用**。合规边界：仅连接本人自己的 ZCode 账号/设备，不分发、不聚合他人凭据；若未来转开源或分发，需重新评估与 Z.ai 的协议合规。
