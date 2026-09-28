#!/usr/bin/env python3
"""
setmode.py — 通过中继桥切换 zcode 会话的权限模式（yolo ↔ build）。

用途：yolo（bypassPermissions）下不会产生 permission_request，
审批功能无法端到端验证；先用这个脚本把目标会话切到会询问的模式。

复用 probe.py 里已实测跑通的连接/编解码/Vql 封装。

  python setmode.py list                    # 列会话 + 当前状态
  python setmode.py set <taskId前缀> build   # 切到询问模式
  python setmode.py set <taskId前缀> yolo    # 切回全放行
"""
import json
import sys
import time

import probe
from probe import Probe, load_credentials

VALID = ("build", "plan", "edit", "auto", "yolo")


def connect():
    sid, phash, mid = load_credentials()
    if not sid or not phash:
        sys.exit("缺凭据：确认本机 ZCode 已配对")
    p = Probe(sid, phash, mid)
    p.open()
    if not p.auth():
        sys.exit("认证未完成（PC 端远程控制可能未开启）")
    boot = p.bootstrap()
    if not boot:
        sys.exit("bootstrap 无应答")
    result = boot.get("result") or {}
    tasks = result.get("tasks") or []
    ws_key = ((result.get("initialViewState") or {}).get("activeWorkspaceKey")
              or (tasks[0].get("workspacePath") if tasks else None))
    if not p.open_bridge(ws_key):
        sys.exit("开桥失败")
    # 显式等 Initialize(200)：未初始化时 call 会被排队并返回 None，导致"没等到 201"的误判
    deadline = time.time() + 12
    while not p.initialized and time.time() < deadline:
        p.pump(1)
    if not p.initialized:
        sys.exit("12s 内未等到 Initialize(200)，通道未就绪")
    return p, tasks, ws_key


def target_of(tasks, prefix):
    hit = [t for t in tasks if t.get("taskId", "").startswith(prefix)]
    if not hit:
        return None
    return hit[0]


def main():
    action = sys.argv[1] if len(sys.argv) > 1 else "list"
    p, tasks, ws_key = connect()
    print(f"[info] activeWorkspaceKey={ws_key}  会话数={len(tasks)}")

    if action == "list":
        for t in tasks:
            print(f"  {t.get('taskId','')[:40]:<40} {t.get('displayStatus',''):<18} "
                  f"{(t.get('title') or '')[:30]:<30} {t.get('workspacePath','')}")
        return 0

    if action == "set":
        prefix = sys.argv[2] if len(sys.argv) > 2 else None
        mode = sys.argv[3] if len(sys.argv) > 3 else "build"
        if mode not in VALID:
            sys.exit(f"mode 需为 {VALID} 之一")
        target = target_of(tasks, prefix or "")
        if not target:
            sys.exit("未找到目标会话，先跑 list")

        sid = target["taskId"]
        arg = {"workspacePath": target.get("workspacePath"), "sessionId": sid, "mode": mode}
        if target.get("workspaceIdentity"):
            arg["workspaceIdentity"] = target["workspaceIdentity"]
        print(f"[set] {sid} → mode={mode}")
        rid = p.call("zcode-agent", "setMode", [arg])
        p.pump(6)
        reply = p.last_result(rid) if rid is not None else None
        if reply is None:
            print("[结果] 没等到 201 应答，看下面的错误帧")
            for typ, i, body in p.frames:
                if typ in (202, 203):
                    print("  err:", json.dumps(probe._jsonable(body), ensure_ascii=False)[:600])
            return 1
        txt = json.dumps(probe._jsonable(reply), ensure_ascii=False)
        print("[结果] 成功:", txt[:900])
        return 0

    sys.exit(__doc__)


if __name__ == "__main__":
    sys.exit(main())
