#!/usr/bin/env python3
"""M2 验收触发：以手机端身份向目标会话发送一条排队消息（zcode-agent 通道 send）。

消息会进入会话队列（autoDrain），当前 agent turn 结束后自动开新 turn 执行；
新 turn 处于 build 模式，其 Bash 调用将产生真实 permission_request。
"""
import json
import sys

import probe
from probe import Probe, load_credentials


def main():
    sid, phash, mid = load_credentials()
    if not sid or not phash:
        sys.exit("缺凭据")
    p = Probe(sid, phash, mid)
    p.open()
    if not p.auth():
        sys.exit("认证未完成")
    boot = p.bootstrap()
    result = (boot or {}).get("result") or {}
    ws_key = (result.get("initialViewState") or {}).get("activeWorkspaceKey") or "F:\\AI\\Zcode"
    tasks = result.get("tasks") or []
    target = next((t for t in tasks if t.get("taskId", "").startswith("sess_4eee8f98")), None)
    if not target:
        sys.exit("目标会话不在列表")
    if not p.open_bridge(ws_key):
        sys.exit("开桥失败")
    p.pump(4)

    arg = {
        "workspacePath": target.get("workspacePath") or ws_key,
        "sessionId": target["taskId"],
        "inputId": f"inp-e2e-{probe.uuid.uuid4()}",
        "content": "请读取并严格按 F:/AI/Zcode/_tmp/E2E_INSTRUCTION.md 执行（M2 验收触发指令）。",
    }
    print(f"[send] → {target['taskId'][:24]}…")
    rid = p.call("zcode-agent", "sendPrompt", [arg])
    p.pump(8)
    reply = p.last_result(rid) if rid is not None else None
    if reply is None:
        print("[send] 未等到 201 应答，错误帧：")
        for typ, i, body in p.frames:
            if typ in (202, 203):
                print("  err:", json.dumps(probe._jsonable(body), ensure_ascii=False)[:500])
        return 1
    print("[send] 成功:", json.dumps(probe._jsonable(reply), ensure_ascii=False)[:400])
    return 0


if __name__ == "__main__":
    sys.exit(main())
