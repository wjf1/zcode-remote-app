#!/bin/bash
# 网络恢复耗时实测（beta15 验收 ②）
#   断网 N 秒（足以让旧版退避烧到 24s/48s 封顶）→ 恢复 → 记录从「网络恢复」到「ws open」的延迟。
# 判据：恢复延迟应 ≤9s（旧版实测 ~47s）。
# 用法：bash tools/net_recover_test.sh [断网秒数]
set -u
ROOT="F:/AI/Zcode/zcode-remote-app"
ADB="$ROOT/toolchain/platform-tools/adb.exe"
DOWN="${1:-80}"
OUT="$ROOT/_tmp/netrecover.log"
FULL="$ROOT/_tmp/netrecover_full.log"
: > "$OUT"

"$ADB" logcat -c >/dev/null 2>&1
T_ON=$(date +%s)
echo "T_AIRPLANE_ON=$T_ON" >> "$OUT"
"$ADB" shell cmd connectivity airplane-mode enable >> "$OUT" 2>&1
sleep "$DOWN"
T_OFF=$(date +%s)
echo "T_AIRPLANE_OFF=$T_OFF   (断网 ${DOWN}s)" >> "$OUT"
"$ADB" shell cmd connectivity airplane-mode disable >> "$OUT" 2>&1

# 轮询等待自动重连（最多 90s）
REC=""
for i in $(seq 1 45); do
  sleep 2
  if "$ADB" logcat -d 2>/dev/null | grep -q "ws open http=101"; then
    REC=$(date +%s)
    echo "RECOVERED_AT=$REC" >> "$OUT"
    echo "恢复延迟 = $((REC - T_OFF)) 秒" >> "$OUT"
    break
  fi
done
[ -z "$REC" ] && echo "RECOVERED_AT=NONE（90s 内未重连）" >> "$OUT"

"$ADB" logcat -d > "$FULL" 2>/dev/null
echo DONE >> "$OUT"
