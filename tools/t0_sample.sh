#!/bin/bash
# T0 采样：每次冷启动 = 一次完整握手（listen→hello→initialize→subscribe→snapshot）
# 用法：bash tools/t0_sample.sh <次数> <输出文件>
set -u
ROOT="F:/AI/Zcode/zcode-remote-app"
ADB="$ROOT/toolchain/platform-tools/adb.exe"
N="${1:-5}"
OUT="${2:-$ROOT/_tmp/t0_samples.txt}"
: > "$OUT"

for i in $(seq 1 "$N"); do
  echo "===== SAMPLE $i =====" >> "$OUT"
  timeout 20 "$ADB" shell am force-stop com.zcode.remote >/dev/null 2>&1
  timeout 20 "$ADB" logcat -c >/dev/null 2>&1
  sleep 1
  timeout 20 "$ADB" shell am start -n com.zcode.remote/.MainActivity >/dev/null 2>&1
  sleep 10
  # 只取结构化日志行（时间戳 + pid + tag），排除回显命令的 payload 行
  timeout 25 "$ADB" logcat -d 2>/dev/null \
    | grep -oE '^[0-9]{2}-[0-9]{2} [0-9:.]+ +[0-9]+ +[0-9]+ I (RpcChannel|ConvChannel): [^{]*' \
    | grep -E 'method=(helloConversationV4|initializeConversationV4|subscribeConversationV4)|rpc recv type=201|subscribed sub=|snapshot: ' \
    | grep -vE 'dataBase64|inputText' >> "$OUT"
  echo "" >> "$OUT"
done
echo "DONE" >> "$OUT"
