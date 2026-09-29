#!/usr/bin/env python3
"""P0-1 协议层端到端验收（asyncio + websockets 库版）。

probe.py 的手写 ws 在 Windows ssl 层读写偶发互相死锁，传输层换 websockets；
Vql 编解码 / CRC / 凭据解密 / auth proof 逻辑复用 probe.py。

流程：auth(重试) → bootstrap → bridge → hello → initialize → listen → subscribe
  A) sendPrompt 长任务消息 → 201 → 等 control.canStop=true
  B) envelope type='stop'（官方 web 同款，asar 实证）→ ack → 观察 canStop 清零
"""
import asyncio
import base64
import hashlib
import hmac as hmac_mod
import json
import sys
import time
import uuid

import websockets

import probe
from probe import (ROLE, RELAY, ORIGIN, load_credentials, crc32hex,
                   vql_encode, vql_decode, _jsonable)

RELAY_URL = RELAY + "?mid=" + (load_credentials()[2] or "")


class Session:
    def __init__(self, ws):
        self.ws = ws
        self.frames = []          # (typ, rid, body)
        self.next_req = 0
        self.next_seq = 0
        self.next_msg = 0
        self.last_ack = 0
        self.initialized = False
        self.bridge_sid = None
        self.bridge_gen = None
        self.pending = {}         # rid -> asyncio.Future (201/202)
        self.events = asyncio.Queue()

    def identity(self):
        ident = {"bridgeSessionId": self.bridge_sid}
        if self.bridge_gen is not None:
            ident["bridgeGeneration"] = self.bridge_gen
        return ident

    async def send_payload(self, payload):
        await self.ws.send(json.dumps(
            {"type": "data", "payload": payload, "client_ts": int(time.time() * 1000)},
            ensure_ascii=False))

    async def send_frame(self, body: bytes):
        self.next_seq += 1
        self.next_msg += 1
        await self.send_payload({
            "zcode_type": "rpc-frame", **self.identity(),
            "seq": self.next_seq, "messageSeq": self.next_msg,
            "fragmentIndex": 0, "fragmentCount": 1, "messageBytes": len(body),
            "checksum": {"algorithm": "crc32", "value": crc32hex(body)},
            "dataBase64": base64.b64encode(body).decode(),
        })

    async def rpc(self, channel, method, args=None, typ=100):
        self.next_req += 1
        rid = self.next_req
        fut = asyncio.get_event_loop().create_future()
        self.pending[rid] = fut
        body = vql_encode([typ, rid, channel, method]) + vql_encode(args or [])
        await self.send_frame(body)
        return rid, fut

    async def listen(self, channel, name, arg):
        self.next_req += 1
        rid = self.next_req
        body = vql_encode([102, rid, channel, name]) + vql_encode(arg)
        await self.send_frame(body)
        return rid

    def ack(self, ms):
        if ms is None or ms <= self.last_ack:
            return
        self.last_ack = ms
        asyncio.ensure_future(self.send_payload(
            {"zcode_type": "rpc-frame-ack", "ackMessageSeq": ms, **self.identity()}))

    async def reader(self):
        """后台任务：收中继帧，解 rpc-frame，分发 201/202 到 pending、204 到 events。"""
        async for raw in self.ws:
            f = json.loads(raw)
            payload = f.get("payload") if isinstance(f.get("payload"), dict) else f
            zt = payload.get("zcode_type")
            if zt == "rpc-frame":
                self.ack(payload.get("messageSeq"))
                b = base64.b64decode(payload["dataBase64"])
                head, pos = vql_decode(b, 0)
                body, _ = vql_decode(b, pos)
                typ = head[0] if isinstance(head, list) else -1
                rid = head[1] if isinstance(head, list) and len(head) > 1 else None
                self.frames.append((typ, rid, body))
                if typ in (201, 202) and rid in self.pending:
                    self.pending.pop(rid).set_result(body if typ == 201 else {"__err": body})
                elif typ == 204:
                    await self.events.put(body)
            elif zt == "rpc-frame-ack":
                pass
            elif zt:
                # bootstrap-response / workspace-bridge-* / 任务事件等业务帧全部入队
                await self.events.put(payload)
            elif f.get("type") in ("auth_ack", "pair_status_ack", "auth_challenge",
                                   "error", "pair_status_query"):
                await self.events.put(f)
            # 其余（workspace-list-updated 等广播）忽略


async def wait_for(ws_session, pred, timeout, pump_s=2.0):
    """从事件流里等满足 pred 的帧（同时把 204 全部记入 frames）。"""
    end = time.time() + timeout
    while time.time() < end:
        try:
            ev = await asyncio.wait_for(ws_session.events.get(), timeout=pump_s)
        except asyncio.TimeoutError:
            continue
        if pred(ev):
            return ev
    return None


async def do_auth(ws):
    await ws.send(json.dumps({
        "type": "auth_init", "role": ROLE, "device_sid": SID,
        "meta": {"platform": "web", "version": "web", "name": "mobile-browser"},
        "client_ts": int(time.time() * 1000)}))
    while True:
        try:
            f = json.loads(await asyncio.wait_for(ws.recv(), timeout=12))
        except asyncio.TimeoutError:
            return False
        t = f.get("type")
        if t == "auth_challenge":
            msg = f"{f['nonce']}|{ROLE}|{SID}".encode()
            proof = base64.urlsafe_b64encode(
                hmac_mod.new(PHASH.encode(), msg, hashlib.sha256).digest()
            ).decode().rstrip("=")
            await ws.send(json.dumps({
                "type": "auth_response", "device_sid": SID,
                "proof": proof, "client_ts": int(time.time() * 1000)}))
        elif t == "auth_ack" and f.get("pair_status") == "matched":
            return True
        elif t == "pair_status_ack" and f.get("pair_status") == "waiting":
            return False
        elif t == "error":
            return False


async def main():
    global SID, PHASH
    SID, PHASH, MID = load_credentials()
    if not SID or not PHASH:
        sys.exit("缺凭据")

    ws = None
    for attempt in range(15):
        try:
            ws = await websockets.connect(RELAY_URL, additional_headers={"Origin": ORIGIN},
                                          open_timeout=15, close_timeout=5)
            if await do_auth(ws):
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

    s = Session(ws)
    reader_task = asyncio.ensure_future(s.reader())

    # bootstrap
    await s.send_payload({"zcode_type": "bootstrap-request", "requestId": f"boot-{int(time.time()*1000)}"})
    boot = await wait_for(s, lambda e: e.get("zcode_type") == "bootstrap-response", 15)
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

    # 开桥
    s.bridge_sid = f"bridge-{uuid.uuid4()}"
    s.bridge_gen = 1
    await s.send_payload({
        "zcode_type": "workspace-bridge-open", "requestId": f"workspace-bridge-{uuid.uuid4()}",
        "bridgeSessionId": s.bridge_sid, "bridgeGeneration": 1,
        "workspaceKey": ws_key})
    ev = await wait_for(s, lambda e: e.get("zcode_type") == "workspace-bridge-ready", 15)
    if not ev:
        sys.exit("bridge timeout")
    s.bridge_sid = ev.get("bridgeSessionId")
    s.bridge_gen = ev.get("bridgeGeneration")
    print("[ok] bridge ready", flush=True)
    await asyncio.sleep(2)   # 等 Initialize(200)

    tw = {"workspacePath": target.get("workspacePath")}
    if target.get("workspaceIdentity"):
        tw["workspaceIdentity"] = target["workspaceIdentity"]

    rid, fut = await s.rpc("zcode-agent", "helloConversationV4", [])
    hello = await asyncio.wait_for(fut, timeout=15) or {}
    if not hello.get("protocolVersion"):
        sys.exit(f"hello fail: {json.dumps(_jsonable(hello), ensure_ascii=False)[:200]}")
    client_id = f"probe-p01-{uuid.uuid4().hex[:8]}"
    rid, fut = await s.rpc("zcode-agent", "initializeConversationV4", [{
        "kind": "clientHello", "protocolVersion": hello["protocolVersion"],
        "clientId": client_id, "clientKind": "mobileApp", "appVersion": "1.0.0"}])
    await asyncio.wait_for(fut, timeout=15)
    await s.listen("zcode-agent", "onDynamicConversationFrame", dict(tw))
    rid, fut = await s.rpc("zcode-agent", "subscribeConversationV4", [dict(tw, sessionId=target["taskId"])])
    sub = await asyncio.wait_for(fut, timeout=15)
    ack = (sub or {}).get("ack") or {}
    print(f"[ok] subscribed sub={ack.get('subscriptionId', '?')} mode={ack.get('mode')}", flush=True)
    await asyncio.sleep(8)   # 收快照

    # ---- A) sendPrompt ----
    msg = ("P0-1 协议验收测试消息：请连续输出 120 行文本，每行内容为"
           "「验收行 <序号>」，除此之外不要做任何其他事情，不要调用任何工具。")
    print("[A] sendPrompt →", flush=True)
    rid, fut = await s.rpc("zcode-agent", "sendPrompt",
                           [{"workspacePath": target.get("workspacePath") or ws_key,
                             "sessionId": target["taskId"],
                             "inputId": f"inp-p01-{uuid.uuid4()}", "content": msg}])
    reply = await asyncio.wait_for(fut, timeout=20)
    send_ok = reply is not None
    print(f"[A] 201 reply: {json.dumps(_jsonable(reply), ensure_ascii=False)[:250]}", flush=True)

    can_stop_seen = False
    end = time.time() + 120
    while time.time() < end:
        await asyncio.sleep(2)   # 让出事件循环给 reader（不可 wait_for(reader_task)，会把它 cancel 掉）
        control, rows = extract_frames(s.frames)
        if control.get("canStop") or control.get("stopState") in ("stoppable", "stopping"):
            can_stop_seen = True
            break
    print(f"[A] canStop_seen={can_stop_seen} control={json.dumps(control, ensure_ascii=False)} rows={len(rows)}", flush=True)

    # ---- B) stop envelope ----
    env_args = dict(tw)
    env_args["envelope"] = {
        "commandId": f"cmd-{uuid.uuid4()}", "clientId": client_id,
        "sessionId": target["taskId"], "type": "stop",
        "payload": {}, "issuedAt": int(time.time() * 1000)}
    print("[B] stop command →", flush=True)
    rid, fut = await s.rpc("zcode-agent", "sendConversationCommandV4", [env_args])
    reply = await asyncio.wait_for(fut, timeout=20)
    status = (reply or {}).get("status") or ((reply or {}).get("ack") or {}).get("status")
    print(f"[B] ack: {json.dumps(_jsonable(reply), ensure_ascii=False)[:250]}", flush=True)

    pre = control.get("phase")
    stopped = False
    end = time.time() + 50
    while time.time() < end:
        await asyncio.sleep(2)
        control, rows = extract_frames(s.frames)
        if not control.get("canStop") and control.get("stopState") != "stopping":
            stopped = True
            break
    print(f"[B] phase {pre} → {control.get('phase')} canStop={control.get('canStop')} "
          f"stopState={control.get('stopState')} stopped={stopped}", flush=True)

    print("\n===== P0-1 协议验收结论 =====", flush=True)
    print(f"A. sendPrompt 201 应答:        {'PASS' if send_ok else 'FAIL'}", flush=True)
    print(f"B. canStop 状态流转:          {'PASS' if can_stop_seen else 'WARN'}", flush=True)
    print(f"C. stop 命令 ack (status):    {'PASS' if status else 'FAIL'} ({status})", flush=True)
    print(f"D. 桌面端中断(canStop 清零):  {'PASS' if stopped else 'WARN'}", flush=True)
    dump = f"../_tmp/p01_frames_{int(time.time())}.json"
    with open(dump, "w", encoding="utf-8") as fh:
        for typ, r, body in s.frames:
            fh.write(f"### type={typ} id={r}\n" + json.dumps(_jsonable(body), ensure_ascii=False, indent=1) + "\n")
    print(f"帧落盘: {dump}", flush=True)
    await ws.close()


def extract_frames(frames):
    control, rows = {}, {}
    for typ, rid, body in frames:
        if typ != 204:
            continue
        frame = (body or {}).get("frame") or {}
        payload = frame.get("payload") or {}
        kind = payload.get("kind")
        snap = payload.get("snapshot") or {}
        if kind == "snapshot":
            c = snap.get("control") or {}
            control.update({k: c.get(k) for k in ("phase", "canStop", "stopState") if c.get(k) is not None})
            for r in ((snap.get("rows") or {}).get("window") or []):
                rows[r.get("rowId")] = r.get("kind")
        elif kind == "deltas":
            for d in payload.get("deltas") or []:
                if d.get("op") == "state.updated":
                    c = (d.get("patch") or {}).get("control") or {}
                    control.update({k: c.get(k) for k in ("phase", "canStop", "stopState") if c.get(k) is not None})
                elif d.get("op") in ("row.appended", "row.upserted"):
                    r = d.get("row") or {}
                    rows[r.get("rowId")] = r.get("kind")
    return control, rows


if __name__ == "__main__":
    asyncio.run(main())
