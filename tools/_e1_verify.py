#!/usr/bin/env python3
"""E-1 会话级权威角标验收（asyncio + websockets）。

协议依据（research/index-web.js bundle 实证）：
  - topic = `sessions-index/<workspaceId>`，独立 RPC `subscribeSessionsIndexV4`
    （args = {workspacePath[, workspaceIdentity], runtimePolicy:'existing-only', base?, visibility?}），
    listen 事件 `onDynamicSessionsIndexFrame`。
  - snapshot：payload.kind=='snapshot' → snapshot.sessions[]（条目含
    pendingInteractionSummary = {permissionCount, userInputCount}）；
  - 增量：op ∈ {session.upserted, session.removed}。

流程：
  A) 订阅 sessions-index → snapshot 基线（目标会话 summary）
  B) sendText 触发 AskUserQuestion → 观察 summary.userInputCount 变 ≥1
  C) resolveInteraction 消解 → 观察 summary 回落 0
（App 当前角标来源是任务事件流推导；本验证目标 = 服务端权威数据源可替代它。）
"""
import asyncio
import importlib.util
import json
import os
import sys
import time
import uuid

_here = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("p01", os.path.join(_here, "_p01_async.py"))
p01 = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(p01)


def si_events(s):
    """从 204 帧提取 sessions-index 事件：('snapshot', sessions) / ('op', delta)。"""
    out = []
    for typ, rid, body in s.frames:
        if typ != 204:
            continue
        payload = (body or {}).get("payload")
        if payload is None:
            payload = ((body or {}).get("frame") or {}).get("payload") or {}
        kind = payload.get("kind")
        snap = payload.get("snapshot")
        if kind == "snapshot" and isinstance(snap, dict) and "sessions" in snap:
            out.append(("snapshot", snap.get("sessions")))
        elif kind == "deltas":
            for d in payload.get("deltas") or []:
                if str(d.get("op", "")).startswith("session."):
                    out.append(("op", d))
    return out


def session_summary(s, task_id):
    """返回目标会话最新的 pendingInteractionSummary（无则 None）。"""
    latest = None
    for kind, data in si_events(s):
        if kind == "snapshot":
            for it in (data if isinstance(data, list) else (data or {}).values() or []):
                if isinstance(it, dict) and task_id in json.dumps(it.get("sessionId", "")) + json.dumps(it.get("taskId", "")):
                    latest = it.get("pendingInteractionSummary")
        else:
            sess = (data.get("session") or {}) if data.get("op") == "session.upserted" else {}
            if task_id in json.dumps(sess.get("sessionId", "")) + json.dumps(sess.get("taskId", "")):
                latest = sess.get("pendingInteractionSummary")
    return latest


async def main():
    sid, phash, mid = p01.load_credentials()
    if not sid or not phash:
        sys.exit("缺凭据")
    p01.SID, p01.PHASH = sid, phash

    ws = None
    for attempt in range(15):
        try:
            ws = await p01.websockets.connect(p01.RELAY_URL, additional_headers={"Origin": p01.ORIGIN},
                                              open_timeout=15, close_timeout=5)
            if await p01.do_auth(ws):
                break
            await ws.close()
        except Exception as e:
            print(f"[retry] {attempt+1}: {str(e)[:80]}", flush=True)
            await asyncio.sleep(6)
            continue
        print(f"[retry] {attempt+1} waiting", flush=True)
        await asyncio.sleep(6)
        ws = None
    else:
        sys.exit("not matched: 桌面端 device 未在线")
    print("[ok] matched", flush=True)

    s = p01.Session(ws)
    asyncio.ensure_future(s.reader())

    await s.send_payload({"zcode_type": "bootstrap-request", "requestId": f"boot-{int(time.time()*1000)}"})
    boot = await p01.wait_for(s, lambda e: e.get("zcode_type") == "bootstrap-response", 15)
    if not boot:
        sys.exit("bootstrap timeout")
    result = boot.get("result") or {}
    ws_key = (result.get("initialViewState") or {}).get("activeWorkspaceKey")
    tasks = result.get("tasks") or []
    prefix = sys.argv[1] if len(sys.argv) > 1 else "按文档开始开发"
    target = next((t for t in tasks if t.get("taskId", "").startswith(prefix)
                   or prefix in (t.get("title") or "")), None)
    if not target:
        sys.exit("target not found")
    if target.get("displayStatus") == "running":
        sys.exit("target 会话 running，避免串扰")
    task_id = target["taskId"]
    print(f"[ok] target={task_id[:28]} ws={ws_key}", flush=True)

    s.bridge_sid = f"bridge-{uuid.uuid4()}"
    s.bridge_gen = 1
    await s.send_payload({
        "zcode_type": "workspace-bridge-open", "requestId": f"workspace-bridge-{uuid.uuid4()}",
        "bridgeSessionId": s.bridge_sid, "bridgeGeneration": 1,
        "workspaceKey": ws_key})
    ev = await p01.wait_for(s, lambda e: e.get("zcode_type") == "workspace-bridge-ready", 15)
    if not ev:
        sys.exit("bridge timeout")
    s.bridge_sid = ev.get("bridgeSessionId")
    s.bridge_gen = ev.get("bridgeGeneration")
    await asyncio.sleep(2)

    tw = {"workspacePath": target.get("workspacePath")}
    if target.get("workspaceIdentity"):
        tw["workspaceIdentity"] = target["workspaceIdentity"]

    rid, fut = await s.rpc("zcode-agent", "helloConversationV4", [])
    hello = await asyncio.wait_for(fut, timeout=15) or {}
    client_id = f"probe-e1-{uuid.uuid4().hex[:8]}"
    rid, fut = await s.rpc("zcode-agent", "initializeConversationV4", [{
        "kind": "clientHello", "protocolVersion": hello.get("protocolVersion", 3),
        "clientId": client_id, "clientKind": "mobileApp", "appVersion": "1.0.0"}])
    await asyncio.wait_for(fut, timeout=15)

    # ---- A) 订阅 sessions-index（+ 会话流用于拿 interactionId）----
    await s.listen("zcode-agent", "onDynamicSessionsIndexFrame", dict(tw))
    rid, fut = await s.rpc("zcode-agent", "subscribeSessionsIndexV4",
                           [dict(tw, runtimePolicy="existing-only")])
    sub = await asyncio.wait_for(fut, timeout=15)
    print(f"[A] subscribeSessionsIndexV4 -> {json.dumps(p01._jsonable(sub), ensure_ascii=False)[:250]}", flush=True)
    await s.listen("zcode-agent", "onDynamicConversationFrame", dict(tw))
    rid, fut = await s.rpc("zcode-agent", "subscribeConversationV4", [dict(tw, sessionId=task_id)])
    await asyncio.wait_for(fut, timeout=15)
    await asyncio.sleep(6)

    base = session_summary(s, task_id)
    snap_seen = bool(si_events(s))
    print(f"[A] snapshot 收到={snap_seen} 目标会话 summary 基线={base}", flush=True)
    if not snap_seen:
        # 打一条原始 204 body 顶层键帮助定位形态
        for typ, rid, body in s.frames:
            if typ == 204:
                print(f"[A] 204 body keys={list((body or {}).keys())} "
                      f"{json.dumps(p01._jsonable(body), ensure_ascii=False)[:400]}", flush=True)
                break

    # ---- B) sendText 触发 AskUserQuestion → summary.userInputCount 变化 ----
    msg = ("E-1 角标验收：请立即调用 AskUserQuestion 工具，向我提一个问题——"
           "问题：「权威角标验收：以下哪项是下一步？」选项：A=结束、B=继续。"
           "header 写「角标验收」。调用工具后停下等待回答，不要自己编造答案，也不要做其他事。")
    env_args = dict(tw)
    env_args["envelope"] = {
        "commandId": f"cmd-{uuid.uuid4()}", "clientId": client_id,
        "sessionId": task_id, "type": "sendText",
        "payload": {"text": msg}, "issuedAt": int(time.time() * 1000)}
    rid, fut = await s.rpc("zcode-agent", "sendConversationCommandV4", [env_args])
    reply = await asyncio.wait_for(fut, timeout=20)
    print(f"[B] sendText ack: {json.dumps(p01._jsonable(reply), ensure_ascii=False)[:160]}", flush=True)

    bump = None
    interaction_id = None
    end = time.time() + 150
    while time.time() < end:
        await asyncio.sleep(4)
        now = session_summary(s, task_id)
        if now and (now.get("userInputCount") or 0) >= 1:
            bump = now
            break
        # 从会话流拿 interactionId 备用（summary 可能不更新）
        items = latest_pending(s)
        if items and interaction_id is None:
            interaction_id = items[0].get("interactionId")
    if bump:
        print(f"[B] 权威角标变化 -> {json.dumps(bump)}", flush=True)
    else:
        print(f"[B] !! 150s 内未观察到 userInputCount 变化（interactionId={interaction_id}）", flush=True)

    # ---- C) 消解 → summary 回落 ----
    if interaction_id is None:
        # 再等一次会话流条目
        end = time.time() + 30
        while time.time() < end and interaction_id is None:
            await asyncio.sleep(4)
            items = latest_pending(s)
            if items:
                interaction_id = items[0].get("interactionId")
    if interaction_id:
        rid, fut = await s.rpc("zcode-agent", "sendConversationCommandV4", [dict(tw,
            envelope={"commandId": f"cmd-{uuid.uuid4()}", "clientId": client_id,
                      "sessionId": task_id, "type": "resolveInteraction",
                      "payload": {"interactionId": interaction_id,
                                  "answer": {"action": "accept", "content": {"answer": "A"}}},
                      "issuedAt": int(time.time() * 1000)})])
        reply = await asyncio.wait_for(fut, timeout=20)
        status = (reply or {}).get("status") or ((reply or {}).get("ack") or {}).get("status")
        print(f"[C] resolveInteraction ack={status}", flush=True)
        cleared = False
        end = time.time() + 45
        while time.time() < end:
            await asyncio.sleep(4)
            now = session_summary(s, task_id)
            if not now or not ((now.get("userInputCount") or 0) >= 1 or (now.get("permissionCount") or 0) >= 1):
                cleared = True
                break
        print(f"[C] summary 回落={cleared} 当前={session_summary(s, task_id)}", flush=True)
    else:
        cleared = False
        print("[C] 未拿到 interactionId，跳过消解观察", flush=True)

    print("\n===== E-1 权威角标验收结论 =====", flush=True)
    print(f"A. sessions-index 订阅+snapshot:   {'PASS' if snap_seen else 'FAIL'}", flush=True)
    print(f"A2. 目标会话基线 summary 可读:     {'PASS' if base is not None else 'WARN'} ({base})", flush=True)
    print(f"B. 触发后 userInputCount≥1:        {'PASS' if bump else 'FAIL'}", flush=True)
    print(f"C. 消解后 summary 回落:            {'PASS' if cleared else 'WARN'}", flush=True)
    dump = f"../_tmp/e1_frames_{int(time.time())}.json"
    with open(dump, "w", encoding="utf-8") as fh:
        for typ, r, body in s.frames:
            fh.write(f"### type={typ} id={r}\n" + json.dumps(p01._jsonable(body), ensure_ascii=False, indent=1) + "\n")
    print(f"帧落盘: {dump}", flush=True)
    await ws.close()
    return 0


def latest_pending(s):
    """会话流 pendingInteractions 里的 userInput 条目（照搬 P1-1 判据，宽容版）。"""
    out = []
    for typ, rid, body in s.frames:
        if typ != 204:
            continue
        payload = (body or {}).get("payload") or ((body or {}).get("frame") or {}).get("payload") or {}
        cand = []
        if payload.get("kind") == "snapshot":
            cand = [(payload.get("snapshot") or {}).get("pendingInteractions") or []]
        elif payload.get("kind") == "deltas":
            cand = [d.get("patch", {}).get("pendingInteractions") or []
                    for d in payload.get("deltas") or [] if d.get("op") == "state.updated"]
        for group in cand:
            for it in group:
                if isinstance(it, dict) and it.get("kind") == "userInput":
                    out.append(it)
    return out


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
