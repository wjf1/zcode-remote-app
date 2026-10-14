# tools/ 脚本索引

本目录是仓库的**辅助脚本区**，分两类。约定：**`_` 前缀 = 历史一次性验证/取证脚本**（保留作证据链，不再日常使用）；无前缀 = **当前仍在用**（CI 门禁、构建辅助、协议探针）。

> 为什么要分类而不是删除：`_` 前缀脚本被 `CHANGELOG.md` / `HANDOVER.md` 作为**历史验收证据**引用
> （如「经 `tools/_16k_check.py` 核实」「`tools/_p13_send_verify.py` 对照实测」），物理移动或删除会
> 破坏这些引用链。故**就地保留 + 本文索引**，新会话不必再逐个猜用途。

## 当前使用（CI / 日常）

| 脚本 | 用途 | 被谁调用 |
|---|---|---|
| `check_test_count.py` | 「声明的 `@Test` 是否都被执行」集合比对 + 数量下限门禁 | `ci.yml`（`--min`） |
| `check_release_consistency.py` | 发布一致性核对：tag = versionName = CHANGELOG 头条 = Release 标题 | `release.yml` |
| `bench_check.py` | Macrobenchmark 结果解析 + 结构性门禁 + 基线比对 | `ci.yml`（benchmark job） |
| `bench_baseline_emulator.json` | Macrobenchmark 模拟器基线（回退探测用） | `bench_check.py --baseline` |
| `gen_vql_fixtures.py` | 生成 VQL 编解码金标准对拍 fixture（Python↔Kotlin 共享向量） | `ci.yml` |
| `gen_rpc_frame_fixtures.py` | 生成 rpc-frame 分片回放 fixture（独立实现官方等长切分 + CRC32） | 本地/审查时 |
| `probe.py` | 中继协议探针（真实帧观测 / 协议实证，建 fixture 用） | 人工（协议核查） |
| `enumerate_rpc.py` | 枚举 RPC 通道/方法（协议盘点） | 人工 |
| `ui_check.py` | 真机 uiautomator dump 判定界面状态（B-1 断言助手） | 人工（真机走查） |
| `setmode.py` | 切换会话执行模式（plan/build/yolo）辅助 | 人工 |
| `net_recover_test.sh` | 断网恢复耗时实测（beta15 验收脚本） | 人工 |
| `t0_sample.sh` | T0 冷启动握手耗时采样 | 人工 |

## 历史一次性验证脚本（`_` 前缀，保留作证据）

这些是各里程碑（M1–M3 / P0–P3 / E-1 等）当时的端到端验收与取证脚本，**依赖当时的设备/凭据/环境，现在不再日常运行**；保留是因为 `CHANGELOG.md` 直接引用了它们作为实测证据。

| 脚本 | 当时的用途 |
|---|---|
| `_16k_check.py` | 核实 zip 条目层 16 KB 页对齐（Android 15 兼容性） |
| `_apk_cert_fp.py` | 从 APK Signing Block 解析签名证书指纹 |
| `_dump_frame.py` | dump 一条 rpc-frame 原始内容（早期协议侦查） |
| `_e1_verify.py` | E-1 会话级权威角标端到端验收 |
| `_e2e_send.py` | 以手机端身份向会话发送排队消息（发送链路验收） |
| `_e2e_tap_approve.py` | 端到端：等审批通知 → 解锁 → 点「允许」 |
| `_p01_async.py` | P0-1 协议层端到端验收（asyncio + websockets） |
| `_p11_probe.py` | P1-1 预研：触发 `AskUserQuestion` 观测帧 |
| `_p11_verify.py` | P1-1 elicitation（表单类交互）应答验收 |
| `_p13_probe.py` | P1-3 附件协议实证（begin/chunk/commit/abort） |
| `_p13_send_verify.py` | P1-3 附件「发送侧」协议层验收 |
| `t0_analyze.py` | 解析 `_tmp/t0_samples.txt`，输出握手耗时与快照落地时延 |
| `render_screenshots.py` | 生成 README 用截图（一次性渲染） |
| `wsclient.py` | 早期临时 WebSocket 客户端（被 `probe.py` 取代） |

## 备注

- `__pycache__/` 已被 `.gitignore` 忽略，不入库。
- 新增辅助脚本请遵循同一约定：**长期使用的去 `_` 前缀并登记到「当前使用」表**；一次性取证的加 `_` 前缀。
