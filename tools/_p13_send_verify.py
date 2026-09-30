#!/usr/bin/env python3
"""P1-3 附件「发送侧」协议层端到端验收（asyncio + websockets）。

前置：tools/_p13_probe.py 已实证上传四件套（begin/chunk/commit，PROTOCOL.md §6.6）；
本脚本补上最后一环——附件随 sendPrompt 到达桌面端会话：
  auth → bootstrap → bridge → hello → initialize → listen → subscribe
  A) 上传 probe.txt（四件套）拿 ref
  B) sendPrompt 带 attachments=[{ref,fileName,mime,bytes}] → 201
  C) 会话流推回 userInput 行（观察 attachments 回显）+ turn canStop=true
  D) envelope type='stop' 收尾，canStop 清零

传输层 / auth / Vql 复用 _p01_async.py（数字开头不可 import，用 importlib 按路径加载）。
"""
import asyncio
import base64
import hashlib
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

MIME = "text/plain"
CHUNK = 384 * 1024

ATTACH = b"zcode-remote P1-3 attachment e2e verify\nline2: hello from probe\n"


async def upload(p01s, tw, session_id, data, label="probe.txt"):
    """四件套上传，返回 ref 或 None。"""
    up = f"upload-{uuid.uuid4()}"
    base = dict(tw, sessionId=session_id, uploadId=up, fileName=label,
                mime=MIME, totalBytes=len(data), totalChunks=1,
                checksum="sha256:" + hashlib.sha256(data).hexdigest())
    rid, fut = await p01s.rpc("zcode-agent", "attachmentBeginV4", [base])
    begin = await asyncio.wait_for(fut, timeout=20)
    print(f"[A] begin -> {json.dumps(p01._jsonable(begin), ensure_ascii=False)[:200]}", flush=True)
    nxt = (begin or {}).get("nextChunkIndex", 0)
    for i in range(nxt, base["totalChunks"]):
        part = data[i * CHUNK:(i + 1) * CHUNK]
        rid, fut = await p01s.rpc("zcode-agent", "attachmentChunkV4",
                            [dict(base, chunkIndex=i, dataBase64=base64.b64encode(part).decode())])
        got = await asyncio.wait_for(fut, timeout=20)
        ni = (got or {}).get("nextChunkIndex")
        print(f"[A] chunk[{i}] {len(part)}B nextChunkIndex={ni}", flush=True)
        if ni != i + 1:
            print("[A] !! 分片进度异常", flush=True)
            return None
    commit_args = {k: base[k] for k in ("workspacePath", "workspaceIdentity", "sessionId", "uploadId")
                   if k in base}
    rid, fut = await p01s.rpc("zcode-agent", "attachmentCommitV4", [commit_args])
    commit = await asyncio.wait_for(fut, timeout=20)
    print(f"[A] commit -> {json.dumps(p01._jsonable(commit), ensure_ascii=False)[:300]}", flush=True)
    ref = (commit or {}).get("ref")
    if not ref:
        # committed 幂等：begin 直接返回 ref 的形态也接受
        ref = (begin or {}).get("ref")
    return ref


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
    print(f"[ok] target={target['taskId'][:28]} ws={ws_key}", flush=True)

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
    print("[ok] bridge ready", flush=True)
    await asyncio.sleep(2)

    tw = {"workspacePath": target.get("workspacePath")}
    if target.get("workspaceIdentity"):
        tw["workspaceIdentity"] = target["workspaceIdentity"]

    rid, fut = await s.rpc("zcode-agent", "helloConversationV4", [])
    hello = await asyncio.wait_for(fut, timeout=15) or {}
    if not hello.get("protocolVersion"):
        sys.exit(f"hello fail: {json.dumps(p01._jsonable(hello), ensure_ascii=False)[:200]}")
    client_id = f"probe-p13-{uuid.uuid4().hex[:8]}"
    rid, fut = await s.rpc("zcode-agent", "initializeConversationV4", [{
        "kind": "clientHello", "protocolVersion": hello["protocolVersion"],
        "clientId": client_id, "clientKind": "mobileApp", "appVersion": "1.0.0"}])
    await asyncio.wait_for(fut, timeout=15)
    await s.listen("zcode-agent", "onDynamicConversationFrame", dict(tw))
    rid, fut = await s.rpc("zcode-agent", "subscribeConversationV4", [dict(tw, sessionId=target["taskId"])])
    sub = await asyncio.wait_for(fut, timeout=15)
    ack = (sub or {}).get("ack") or {}
    print(f"[ok] subscribed sub={ack.get('subscriptionId', '?')} mode={ack.get('mode')}", flush=True)
    await asyncio.sleep(6)

    rows_before = set(p01.extract_frames(s.frames)[1])

    # ---- A) 上传附件 ----
    ref = await upload(s, tw, target["taskId"], ATTACH)
    upload_ok = bool(ref)
    print(f"[A] ref={ref}", flush=True)
    if not upload_ok:
        print("\n===== 结论：上传失败，发送侧验收中止 =====", flush=True)
        await ws.close()
        return 1

    # ---- B) sendText envelope 带附件（官方 web 远程页同款路径）----
    # 实证：sendPrompt RPC 的 schema 只有 {workspacePath, sessionId, inputId, content}，
    # 多传的 attachments 被 zod strip（2026-09-30 实测 201 但附件不到模型侧）；
    # 官方 web 走 sendConversationCommandV4 envelope type='sendText'，
    # payload {text, attachments:[{ref,fileName,mime,bytes}], requestedDelivery?}
    msg = ("P1-3 附件发送侧验收测试消息：请读取本条消息附带附件 probe.txt 的内容并原样复述其第一行，"
           "复述完成后立即结束，除此之外不要做任何其他事情，不要调用任何工具。")
    attachments = [{"ref": ref, "fileName": "probe.txt", "mime": MIME, "bytes": len(ATTACH)}]
    print("[B] sendText envelope(attachments) →", flush=True)
    env_args = dict(tw)
    env_args["envelope"] = {
        "commandId": f"cmd-{uuid.uuid4()}", "clientId": client_id,
        "sessionId": target["taskId"], "type": "sendText",
        "payload": {"text": msg, "attachments": attachments},
        "issuedAt": int(time.time() * 1000)}
    rid, fut = await s.rpc("zcode-agent", "sendConversationCommandV4", [env_args])
    reply = await asyncio.wait_for(fut, timeout=20)
    send_ok = ((reply or {}).get("status") or ((reply or {}).get("ack") or {}).get("status")) in ("accepted", "duplicate")
    print(f"[B] ack: {json.dumps(p01._jsonable(reply), ensure_ascii=False)[:250]}", flush=True)

    # ---- C) 观察会话流：新 userInput 行 + canStop + assistant 是否复述附件内容 ----
    can_stop_seen = False
    new_user_row = None
    attach_echo = False
    end = time.time() + 90
    while time.time() < end:
        await asyncio.sleep(3)
        control, rows = p01.extract_frames(s.frames)
        fresh = [r for r in rows if r not in rows_before]
        if fresh and new_user_row is None:
            new_user_row = fresh
            print(f"[C] 新增行 kind={fresh}", flush=True)
        if control.get("canStop") or control.get("stopState") in ("stoppable", "stopping"):
            can_stop_seen = True
        # 附件到达的直接证据：assistant 文本复述出附件首行/第二行内容
        for rid_, (kind_, text_) in row_texts(s.frames).items():
            if rid_ not in rows_before and kind_ == "assistantText" and text_ and \
                    ("zcode-remote" in text_ or "hello from probe" in text_):
                attach_echo = True
                print(f"[C] 附件内容复述证据: {text_[:200]}", flush=True)
        if attach_echo:
            break
    # 打印本轮 turn 新增 userInput / assistant 行原文（截断）
    for rid_, (kind_, text_) in row_texts(s.frames).items():
        if rid_ not in rows_before and kind_ in ("userInput", "assistantText"):
            print(f"[C] new row[{kind_}] id={rid_}: {text_[:300]}", flush=True)
    # 打印 userInput / assistant 行原文（截断），确认 attachments 回显与到达证据
    for typ, r, body in s.frames:
        if typ != 204:
            continue
        frame = (body or {}).get("frame") or {}
        payload = frame.get("payload") or {}
        if payload.get("kind") == "snapshot":
            for row in ((payload.get("snapshot", {}).get("rows") or {}).get("window") or []):
                if row.get("kind") in ("userInput", "assistantText", "assistant"):
                    print(f"[C] row[{row.get('kind')}] {json.dumps(p01._jsonable(row), ensure_ascii=False)[:600]}", flush=True)
        elif payload.get("kind") == "deltas":
            for d in payload.get("deltas") or []:
                if d.get("op") in ("row.appended", "row.upserted"):
                    row = d.get("row") or {}
                    if row.get("kind") in ("userInput", "assistantText", "assistant"):
                        print(f"[C] delta row[{row.get('kind')}] {json.dumps(p01._jsonable(row), ensure_ascii=False)[:600]}", flush=True)

    # ---- D) stop 收尾（turn 仍在跑才发）----
    control, rows = p01.extract_frames(s.frames)
    if control.get("canStop") or control.get("stopState") in ("stoppable", "stopping"):
        env_args = dict(tw)
        env_args["envelope"] = {
            "commandId": f"cmd-{uuid.uuid4()}", "clientId": client_id,
            "sessionId": target["taskId"], "type": "stop",
            "payload": {}, "issuedAt": int(time.time() * 1000)}
        print("[D] stop command →", flush=True)
        rid, fut = await s.rpc("zcode-agent", "sendConversationCommandV4", [env_args])
        reply = await asyncio.wait_for(fut, timeout=20)
        status = (reply or {}).get("status") or ((reply or {}).get("ack") or {}).get("status")
        print(f"[D] ack: {json.dumps(p01._jsonable(reply), ensure_ascii=False)[:200]}", flush=True)
        stopped = False
        end = time.time() + 40
        while time.time() < end:
            await asyncio.sleep(2)
            control, rows = p01.extract_frames(s.frames)
            if not control.get("canStop") and control.get("stopState") != "stopping":
                stopped = True
                break
    else:
        status, stopped = "n/a(turn已结束)", True

    print("\n===== P1-3 附件发送侧验收结论 =====", flush=True)
    print(f"A. 附件上传四件套(commit 返回 ref): {'PASS' if upload_ok else 'FAIL'}", flush=True)
    print(f"B. sendText envelope 201 accepted:  {'PASS' if send_ok else 'FAIL'}", flush=True)
    print(f"C. 会话流推回新行(含 userInput):    {'PASS' if new_user_row else 'WARN'} ({new_user_row})", flush=True)
    print(f"C2. turn canStop=true:              {'PASS' if can_stop_seen else 'WARN'}", flush=True)
    print(f"C3. assistant 复述附件内容:         {'PASS' if attach_echo else 'WARN'}", flush=True)
    print(f"D. stop 收尾(canStop 清零):         {'PASS' if stopped else 'WARN'} ({status})", flush=True)
    dump = f"../_tmp/p13_send_frames_{int(time.time())}.json"
    with open(dump, "w", encoding="utf-8") as fh:
        for typ, r, body in s.frames:
            fh.write(f"### type={typ} id={r}\n" + json.dumps(p01._jsonable(body), ensure_ascii=False, indent=1) + "\n")
    print(f"帧落盘: {dump}", flush=True)
    await ws.close()
    return 0


def row_texts(frames):
    """从 204 帧提取 {rowId: (kind, text)}：snapshot 窗口 + row.appended/upserted 增量。"""
    out = {}
    for typ, rid, body in frames:
        if typ != 204:
            continue
        payload = ((body or {}).get("frame") or {}).get("payload") or {}
        if payload.get("kind") == "snapshot":
            for row in ((payload.get("snapshot", {}).get("rows") or {}).get("window") or []):
                out[row.get("rowId")] = (row.get("kind"), row.get("text") or "")
        elif payload.get("kind") == "deltas":
            for d in payload.get("deltas") or []:
                if d.get("op") in ("row.appended", "row.upserted"):
                    row = d.get("row") or {}
                    out[row.get("rowId")] = (row.get("kind"), row.get("text") or "")
    return out


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))