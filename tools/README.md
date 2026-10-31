# tools/ 脚本索引

本目录是仓库的**辅助脚本区**，只保留**当前仍在用**的脚本（CI 门禁、构建辅助、协议探针）。

> 一次性验证/取证脚本的去向：2026-10-31 已把 11 个 `_` 前缀一次性脚本从工作区删除
> （`_16k_check.py` / `_apk_cert_fp.py` / `_dump_frame.py` / `_e1_verify.py` / `_e2e_send.py` /
> `_e2e_tap_approve.py` / `_p01_async.py` / `_p11_probe.py` / `_p11_verify.py` / `_p13_probe.py` /
> `_p13_send_verify.py`，均引入于提交 f4af4c6）。它们作为各里程碑（M1–M3 / P0–P3 / E-1 等）
> 的验收证据**归档于 git 历史**，按需追溯：
>
> ```bash
> git log --follow -- tools/_<name>.py        # 找到脚本的增删轨迹
> git show f4af4c6:tools/_<name>.py           # 取回脚本内容
> ```
>
> `CHANGELOG.md` / `HANDOVER.md` / `PROTOCOL.md` 中「经 `tools/_xxx.py` 核实」类引用指的正是
> 这些归档脚本，含义不变；正文与 Kotlin 源码中的活引用已同步改为指向 git 历史。

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

## 仍在工作区的历史一次性脚本

以下三个是一次性用途但**未被本轮清理删除**（引用少 / 偶发重跑），暂留工作区：

| 脚本 | 当时的用途 |
|---|---|
| `t0_analyze.py` | 解析 `_tmp/t0_samples.txt`，输出握手耗时与快照落地时延 |
| `render_screenshots.py` | 生成 README 用截图（一次性渲染） |
| `wsclient.py` | 早期临时 WebSocket 客户端（被 `probe.py` 取代） |

## 备注

- `__pycache__/` 已被 `.gitignore` 忽略，不入库。
- 新增辅助脚本约定：**长期使用的直接登记到「当前使用」表**；一次性取证脚本**不再入工作区**
  ——跑完即弃、结论写入文档，需要留证据时由 git 历史承载。
