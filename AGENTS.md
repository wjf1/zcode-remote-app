# AGENTS.md（接手必读 · 硬约束）

接手或改动前先读 [HANDOVER.md](HANDOVER.md)——重点：§5 坑清单（浓缩技术结论）、§6.1 当期计划与验收状态、§8 红线。

## 硬约束

- 起后台进程一律用工具的 `run_in_background`，**禁止裸 `&` / `nohup`**（会杀 agent host，会话静默）。
- 本机兆芯 KX-7000 **无虚拟化扩展：跑不了模拟器**（qemu 静默退出）。UI 层验收一律真机（小米 15 Pro，serial 9f6241b4）。
- 构建必须看到 **`BUILD SUCCESSFUL`** 再继续：`./build.sh`（完整日志在 `_tmp/build.log`，终端 tail 会吞 `^e:` 编译错误）。
- 构建工具链在仓库内 `toolchain/`（gitignore），无需 Android Studio；详见 HANDOVER §3。
- 模拟器相关注意事项仅在其他机器适用：熄屏触发 Doze 断网（先 `dumpsys deviceidle disable`）；挖孔区 ~130px 吞 `input tap`（点击 y≥150）。
- **keystore 与 `keystore.properties` 不入库、不外传**（丢失则所有已安装设备无法升级）。
- 任何「协议做不到」的结论必须标注依据（bundle 静态分析 or 真机实测），冲突以实测为准并回改文档。
- **App 侧永不提供文件写能力**（详见 HANDOVER §8）。
- 发版走 HANDOVER §7 流程：升 versionName/versionCode → tag → `gh release create` 附签名 APK；push 用 `-c http.proxy=http://127.0.0.1:7897`。
