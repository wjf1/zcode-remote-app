#!/usr/bin/env python3
"""M2 端到端验收辅助 v2：等待 ZCode Remote 的审批通知，解锁并点「允许一次」。

v2 变更：起步等待 3s、轮询间隔 1.5s（新 turn 的审批出现后要抢在桌面端自动决议前点掉），
并保留点击后回读 App logcat 的证据输出。
"""
import re
import subprocess
import sys
import time

ADB = r"F:/AI/Zcode/zcode-remote-app/toolchain/platform-tools/adb.exe"
DUMP = "/sdcard/e2e_uidump.xml"
LOCAL = r"F:/AI/Zcode/zcode-remote-app/_tmp/e2e_uidump.xml"
BUTTON_TEXTS = ("允许一次", "总是允许")


def adb(*args, timeout=20):
    r = subprocess.run([ADB, *args], capture_output=True, text=True,
                       timeout=timeout, errors="ignore")
    return r.stdout + r.stderr


def sh(cmd, timeout=20):
    return adb("shell", cmd, timeout=timeout)


def notification_ready():
    out = sh("dumpsys notification --noredact", timeout=25)
    return "com.zcode.remote" in out and "需要审批" in out


def center(bounds):
    m = re.search(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds or "")
    if not m:
        return None
    x1, y1, x2, y2 = map(int, m.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2


def find_button(xml_text):
    for node in re.finditer(r'<node[^>]*?text="([^"]*)"[^>]*?bounds="(\[[^"]*\])"', xml_text):
        if node.group(1) in BUTTON_TEXTS:
            return node.group(2)
    for node in re.finditer(r'<node[^>]*?bounds="(\[[^"]*\])"[^>]*?text="([^"]*)"', xml_text):
        if node.group(2) in BUTTON_TEXTS:
            return node.group(1)
    return None


def tap_button():
    for attempt in range(4):
        sh(f"uiautomator dump {DUMP}", timeout=30)
        adb("pull", DUMP, LOCAL, timeout=25)
        try:
            xml_text = open(LOCAL, encoding="utf-8", errors="ignore").read()
        except OSError:
            xml_text = ""
        pos = center(find_button(xml_text))
        if pos:
            sh(f"input tap {pos[0]} {pos[1]}")
            return pos
        sh("cmd statusbar expand-notifications")
        time.sleep(1.5)
    return None


def main():
    deadline = time.time() + 600
    print("[e2e] v2 启动：等待审批通知…", flush=True)
    time.sleep(3)

    seen = False
    while time.time() < deadline:
        if notification_ready():
            seen = True
            break
        time.sleep(1.5)
    if not seen:
        print("[e2e] FAIL: 240s 内未出现 ZCode Remote 审批通知", flush=True)
        return 1
    print("[e2e] 审批通知已到达 ✅", flush=True)

    sh("input keyevent KEYCODE_WAKEUP")
    time.sleep(1)
    sh("wm dismiss-keyguard")
    time.sleep(1.5)
    sh("cmd statusbar expand-notifications")
    time.sleep(1.5)

    pos = tap_button()
    if not pos:
        print("[e2e] FAIL: 通知栏里没找到「允许一次/总是允许」按钮", flush=True)
        return 1
    print(f"[e2e] 已点击「允许一次」按钮 {pos}", flush=True)

    time.sleep(6)
    log = adb("logcat", "-d", "-s", "ConvChannel", "AppViewModel", timeout=25)
    hit = [ln.split("I ")[-1] for ln in log.splitlines()
           if "resolve" in ln or "accepted" in ln or "已批准" in ln or "pendingInteractions" in ln]
    print("[e2e] App 日志关键行：", flush=True)
    for ln in hit[-12:]:
        print("   ", ln.strip()[:220], flush=True)
    ok = any("resolve interaction" in ln or "已批准" in ln for ln in hit)
    print("[e2e] " + ("PASS: 应答已发出" if ok else "WARN: 未在日志中看到 resolve 发出记录"), flush=True)
    return 0 if ok else 2


if __name__ == "__main__":
    sys.exit(main())
