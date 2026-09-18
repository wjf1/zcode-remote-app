# ZCode Remote（M1 已完成并在模拟器实测通过）

小米 15 Pro 上的 ZCode 官方远程控制原生增强客户端。
方案见 [../ZCode远程控制安卓APP方案.md](./ZCode远程控制安卓APP方案.md)（V0.2），协议见 [../PROTOCOL.md](./PROTOCOL.md)（v1.0，含真机实测修正）。

## 实测验证状态（M1 验收通过）

在 Android 15 模拟器（Pixel 7 / google_apis x86_64 / API 35）实测跑通：

```
WSS 建连(?mid=…, Origin: https://zcode.z.ai)
 → auth_init(role=terminal) → auth_challenge(nonce)
 → auth_response(proof=base64url(HMAC-SHA256)) → auth_ack + pair_status:"matched"
 → heartbeat(pair_status_query) → pair_status_ack:"matched"
 → data{ bootstrap-request } → data{ bootstrap-response } ✅ 真实会话列表渲染成功
```

实测结果：**已配对**（DESKTOP-DVFFB09），会话列表显示 `1 运行中 / 32`，标题/工作区/状态全部正确。

## 功能（M1）

- **扫码配对**：CameraX + ZXing 解析官方二维码（`sid/hash/mid/name`），支持手动粘贴链接兜底；凭据 AES-256-GCM + Android Keystore 加密存储。
- **中继连接**：完整握手（含 4 处实测修正：`mid` 查询参数、`Origin` 头、base64url proof、`data` 信封 + `client_ts`）、`waiting/matched` 状态机、30s 心跳、指数退避重连、KICKED/AUTH_FAILED 等错误分类提示。
- **会话列表**：bootstrap-response 解析，按运行状态高亮，显示工作区/模型/状态；过滤归档项。
- **事件流**：11 种任务事件（含权限审批 `permission_request`）实时渲染；审批选项分类器已内置。
- Material 3 深色主题，对齐官方 remote/v4 观感。

## 构建与运行（无需 Android Studio）

本机工具链已就绪于 `../toolchain/`（JDK 17 + Gradle 8.7 + Android SDK 35 + platform-tools + emulator）。

```bash
# 构建
./build.sh

# 构建 + 安装到设备/模拟器 + 启动
./build.sh install

# 查看中继握手日志
./build.sh log
```

启动模拟器（无头，需先装 AEHD 驱动，已装）：

```bash
export ANDROID_AVD_HOME="F:\\AI\\Zcode\\zcode-remote-app\\toolchain\\avd"
"../toolchain/android-sdk/emulator/emulator.exe" -avd test35 -no-window -no-audio \
  -no-boot-anim -gpu swiftshader_indirect -no-snapshot &
```

> 若用 Android Studio：直接打开 `app-android/` 目录即可（`local.properties` 已指向本机 SDK）。
> ⚠️ 构建时**务必确认 BUILD SUCCESSFUL**，只看 `tail` 会吞掉编译错误（`^e:` 开头的 Kotlin 错误行）。

## 已知限制（M1 边界）

- 会话内部对话流（`rpc-frame` 内层 `v4/conversation/frame` 的二进制解码）未完成——需进一步联调；当前以会话列表 + 事件流为主。
- 与官方 Web 版互踢（同 deviceSid 单端在线），使用本 App 时请关闭官方网页端。
- 连接生命周期跟随 Activity，前台服务保活在 M2 迁移（`service/ConnectionService.kt` 已占位）。

## 工程结构

```
app/src/main/java/com/zcode/remote/
├── relay/          RelayProtocol（握手/proof）· RelayClient（状态机/心跳/重连）
│                   BridgeFrames（data 信封/bootstrap 解析/SessionItem/审批分类）
├── storage/        QR 解析 + Keystore 凭据存储
├── service/        前台服务占位（M2）
├── ui/screens/     ScanScreen（扫码/手动配对）· HomeScreen（状态+会话列表+事件）
└── AppViewModel    状态中枢
```

自用软件，未开源分发；仅连接本人设备（见方案文档决策 #4）。
