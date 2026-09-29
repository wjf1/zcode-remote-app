#!/usr/bin/env bash
# ZCode Remote 一键构建 / 安装 / 启动（本机自带工具链，无需 Android Studio）
# 用法:
#   ./build.sh            构建 APK
#   ./build.sh install    构建 + 安装到已连接的设备/模拟器 + 启动
#   ./build.sh log        抓取 App 的中继日志
set -e

ROOT="F:/AI/Zcode/zcode-remote-app"
TC="$ROOT/toolchain"

# 工具链优先用仓库内 toolchain/（原开发环境）；缺失（换机/重新克隆）时回退到系统安装。
if [ -d "$TC/jdk-17.0.20.1+1" ]; then
  export JAVA_HOME="$TC/jdk-17.0.20.1+1"
else
  export JAVA_HOME="${JAVA_HOME:-F:/AndroidTools/jdk/jdk-17.0.20.1+1}"
fi
if [ -d "$TC/android-sdk" ]; then
  export ANDROID_HOME="$TC/android-sdk"
  export ANDROID_SDK_ROOT="F:\\AI\\Zcode\\zcode-remote-app\\toolchain\\android-sdk"
else
  export ANDROID_HOME="${ANDROID_HOME:-F:/AndroidTools/Sdk}"
  export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-F:\\AndroidTools\\Sdk}"
fi
ADB="$TC/platform-tools/adb.exe"
[ -x "$ADB" ] || ADB="$ANDROID_HOME/platform-tools/adb.exe"
GRADLE="$TC/gradle-8.7/bin/gradle.bat"
[ -f "$GRADLE" ] || GRADLE="F:/AndroidTools/gradle-8.11.1/bin/gradle.bat"
APK="$ROOT/app-android/app/build/outputs/apk/debug/app-debug.apk"

# 模拟器（无头，AEHD 加速）：
#   export ANDROID_AVD_HOME="F:\\AI\\Zcode\\zcode-remote-app\\toolchain\\avd"
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
