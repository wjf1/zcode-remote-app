#!/usr/bin/env bash
# ZCode Remote 一键构建 / 安装 / 启动（本机自带工具链，无需 Android Studio）
# 用法:
#   ./build.sh            构建 APK
#   ./build.sh install    构建 + 安装到已连接的设备/模拟器 + 启动
#   ./build.sh log        抓取 App 的中继日志
#
# Sprint 6：仓库根与工具链路径全部改为自脚本位置/环境变量推导——
# clone 到任意目录、任意机器，./build.sh 直接可构建（不再硬编码绝对路径）。
set -e

# 仓库根 = 本脚本所在目录
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$SCRIPT_DIR"
TC="$ROOT/toolchain"

# JDK：优先仓库内 toolchain/jdk-*；否则沿用外部 JAVA_HOME（CI 上由 setup-java 提供）
JDK_DIR="$(ls -d "$TC"/jdk-* 2>/dev/null | head -1 || true)"
if [ -n "$JDK_DIR" ] && [ -d "$JDK_DIR" ]; then
  export JAVA_HOME="$JDK_DIR"
fi

# Android SDK：优先仓库内 toolchain/android-sdk；否则沿用 ANDROID_HOME/ANDROID_SDK_ROOT
#（ubuntu CI runner 自带）。两者都无时交给 gradle 自行报错，信息更明确。
if [ -d "$TC/android-sdk" ]; then
  export ANDROID_HOME="$TC/android-sdk"
  export ANDROID_SDK_ROOT="$ANDROID_HOME"
elif [ -z "${ANDROID_HOME:-}" ] && [ -n "${ANDROID_SDK_ROOT:-}" ]; then
  export ANDROID_HOME="$ANDROID_SDK_ROOT"
fi

ADB="$TC/platform-tools/adb.exe"
if [ ! -x "$ADB" ]; then
  ADB="$ANDROID_HOME/platform-tools/adb.exe"
fi
if [ ! -x "$ADB" ] && command -v adb >/dev/null 2>&1; then
  ADB=adb
fi

GRADLE="$TC/gradle-8.7/bin/gradle.bat"
if [ ! -f "$GRADLE" ]; then
  # 无仓库工具链（换机/CI）：走 PATH 里的 gradle
  GRADLE="$(command -v gradle.bat || command -v gradle)"
fi
APK="$ROOT/app-android/app/build/outputs/apk/debug/app-debug.apk"

# 模拟器（无头，AEHD 加速，仅本机有 toolchain 时可用）：
#   export ANDROID_AVD_HOME="$TC/avd"
#   "$TC/android-sdk/emulator/emulator.exe" -avd test35 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot &

cd "$ROOT/app-android"

# 子命令名不是 gradle 任务（AGP 没有裸 install/log 任务），必须剔掉，
# 否则 gradle 报 "Task not found"、构建静默失败却接着安装上一次的旧 APK。
TASKS=()
case "${1:-}" in
  install|log) ;;
  "") ;;
  *) TASKS=("$@") ;;
esac

LOGF="$ROOT/_tmp/build.log"
mkdir -p "$ROOT/_tmp"
"$GRADLE" assembleDebug "${TASKS[@]+"${TASKS[@]}"}" > "$LOGF" 2>&1 || true
grep -E "BUILD SUCCESSFUL|BUILD FAILED|^e:|error:" "$LOGF" || true
if ! grep -q "BUILD SUCCESSFUL" "$LOGF"; then
  echo "构建失败（完整日志: $LOGF），已中止，不会安装旧 APK"
  exit 1
fi

case "${1:-}" in
  install)
    "$ADB" install -r "$APK"
    "$ADB" shell pm grant com.zcode.remote android.permission.CAMERA 2>/dev/null || true
    # Android 13+ 通知是运行时权限，没给的话审批通知栏不会弹
    "$ADB" shell pm grant com.zcode.remote android.permission.POST_NOTIFICATIONS 2>/dev/null || true
    "$ADB" logcat -c
    "$ADB" shell am force-stop com.zcode.remote
    "$ADB" shell am start -n com.zcode.remote/.MainActivity
    ;;
  log)
    # ConvChannel = 会话流/审批应答，AppViewModel = 审批状态与 ack 结果
    "$ADB" logcat -d -s RelayClient RpcChannel ConvChannel AppViewModel
    ;;
esac
