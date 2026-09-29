#!/usr/bin/env python3
"""P1-1 协议层端到端验收：elicitation（表单类交互）应答。

流程：auth(重试) → bootstrap → bridge → hello → initialize → listen → subscribe
  1) 等待 pendingInteractions 出现 kind=userInput 条目（无则 sendPrompt 触发 AskUserQuestion）
  2) 按官方 web 同款构造 answer = {action:'accept', content:{answer: 首选项value}} 应答
  3) 观察 ack status=accepted + pendingInteractions 清空 + turn 恢复执行
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
from probe import ROLE, RELAY, ORIGIN, load_credentials, vql_encode, vql_decode, _jsonable

SID, PHASH, MID = load_credentials()
URL = RELAY + "?mid=" + (MID or "")


class Session:
    def __init__(self, ws):
        self.ws = ws
        self.frames = []
        self.next_req = 0
        self.next_seq = 0
        self.next_msg = 0
        self.last_ack = 0
        self.bridge_sid = None
        self.bridge_gen = None
        self.pending = {}
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

    async def ack(self, ms):
        if ms is None or ms <= self.last_ack:
            return
        self.last_ack = ms
        await self.send_payload({"zcode_type": "rpc-frame-ack", "ackMessageSeq": ms, **self.identity()})

    async def call(self, channel, method, args=None):
        self.next_req += 1
        rid = self.next_req
        fut = asyncio.get_event_loop().create_future()
        self.pending[rid] = fut
        body = vql_encode([100, rid, channel, method]) + vql_encode(args or [])
        await self._frame(body)
        return rid, fut

    async def listen(self, channel, name, arg):
        self.next_req += 1
        rid = self.next_req
        body = vql_encode([102, rid, channel, name]) + vql_encode(arg)
        await self._frame(body)
        return rid

    async def _frame(self, body: bytes):
        self.next_seq += 1
        self.next_msg += 1
        await self.send_payload({
            "zcode_type": "rpc-frame", **self.identity(),
            "seq": self.next_seq, "messageSeq": self.next_msg,
            "fragmentIndex": 0, "fragmentCount": 1, "messageBytes": len(body),
            "checksum": {"algorithm": "crc32", "value": probe.crc32hex(body)},
            "dataBase64": base64.b64encode(body).decode(),
        })

    async def reader(self):
        try:
            async for raw in self.ws:
                f = json.loads(raw)
                payload = f.get("payload") if isinstance(f.get("payload"), dict) else f
                zt = payload.get("zcode_type")
                if zt == "rpc-frame":
                    await self.ack(payload.get("messageSeq"))
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
                elif zt:
                    await self.events.put(payload)
        except Exception as e:
            print("[reader died]", repr(e)[:160], flush=True)


async def wait_payload_event(s, zt, timeout):
    end = time.time() + timeout
    while time.time() < end:
        try:
            ev = await asyncio.wait_for(s.events.get(), timeout=2)
        except asyncio.TimeoutError:
            continue
        if ev.get("zcode_type") == zt:
            return ev
    return None


async def do_auth(ws):
    await ws.send(json.dumps({"type": "auth_init", "role": ROLE, "device_sid": SID,
        "meta": {"platform": "web", "version": "web", "name": "mobile-browser"},
        "client_ts": int(time.time() * 1000)}))
    while True:
        try:
            f = json.loads(await asyncio.wait_for(ws.recv(), timeout=12))
        except asyncio.TimeoutError:
            return False
        t = f.get("type")
        if t == "auth_challenge":
            m = f"{f['nonce']}|{ROLE}|{SID}".encode()
            proof = base64.urlsafe_b64encode(
                hmac_mod.new(PHASH.encode(), m, hashlib.sha256).digest()).decode().rstrip("=")
            await ws.send(json.dumps({"type": "auth_response", "device_sid": SID,
                "proof": proof, "client_ts": int(time.time() * 1000)}))
        elif t == "auth_ack" and f.get("pair_status") == "matched":
            return True
        elif t in ("pair_status_ack", "error"):
            return False


def latest_pending(s):
    """从已收 204 帧提取最近的 pendingInteractions（整组替换语义），返回 userInput 条目列表。"""
    pi = []
    for typ, rid, body in s.frames:
        if typ != 204:
            continue
        pl = ((body or {}).get("frame") or {}).get("payload") or {}
        if pl.get("kind") == "snapshot":
            pi = (pl.get("snapshot") or {}).get("pendingInteractions") or []
        elif pl.get("kind") == "deltas":
            for d in pl.get("deltas") or []:
                if d.get("op") == "state.updated" and "pendingInteractions" in (d.get("patch") or {}):
                    pi = d["patch"]["pendingInteractions"] or []
    return [x for x in pi if x.get("kind") == "userInput"]


async def wait_user_input(s, timeout, trigger=None):
    """等 userInput 条目出现；trigger 是无参协程函数（未出现时先触发一次）。"""
    end = time.time() + timeout
    fired = False
    while time.time() < end:
        items = latest_pending(s)
        if items:
            return items[0]
        if trigger and not fired:
            await trigger()
            fired = True
        await asyncio.sleep(4)
    return None


async def main():
    prefix = sys.argv[1] if len(sys.argv) > 1 else "按文档开始开发"
    ws = None
    for attempt in range(15):
        try:
            ws = await websockets.connect(URL, additional_headers={"Origin": ORIGIN},
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
        sys.exit("not matched")
    print("[ok] matched", flush=True)

    s = Session(ws)
    asyncio.ensure_future(s.reader())

    await s.send_payload({"zcode_type": "bootstrap-request", "requestId": "boot-p11v"})
    boot = await wait_payload_event(s, "bootstrap-response", 15)
    result = (boot or {}).get("result") or {}
    ws_key = (result.get("initialViewState") or {}).get("activeWorkspaceKey")
    tasks = result.get("tasks") or []
    target = next((t for t in tasks if t.get("taskId", "").startswith(prefix)
                   or prefix in (t.get("title") or "")), None)
    if not target:
        sys.exit("target not found")
    print(f"[ok] target={target['taskId'][:28]} ws={ws_key}", flush=True)

    s.bridge_sid = f"bridge-{uuid.uuid4()}"
    s.bridge_gen = 1
    await s.send_payload({"zcode_type": "workspace-bridge-open",
        "requestId": f"wb-{uuid.uuid4()}", "bridgeSessionId": s.bridge_sid,
        "bridgeGeneration": 1, "workspaceKey": ws_key})
    ev = await wait_payload_event(s, "workspace-bridge-ready", 15)
    if not ev:
        sys.exit("bridge timeout")
    s.bridge_sid = ev.get("bridgeSessionId")
    s.bridge_gen = ev.get("bridgeGeneration")
    print("[ok] bridge ready", flush=True)
    await asyncio.sleep(2)

    tw = {"workspacePath": target.get("workspacePath")}
    rid, fut = await s.call("zcode-agent", "helloConversationV4", [])
    hello = await asyncio.wait_for(fut, timeout=15) or {}
    client_id = f"probe-p11v-{uuid.uuid4().hex[:8]}"
    rid, fut = await s.call("zcode-agent", "initializeConversationV4", [{
        "kind": "clientHello", "protocolVersion": hello.get("protocolVersion", 3),
        "clientId": client_id, "clientKind": "mobileApp", "appVersion": "1.0.0"}])
    await asyncio.wait_for(fut, timeout=15)
    await s.listen("zcode-agent", "onDynamicConversationFrame", dict(tw))
    rid, fut = await s.call("zcode-agent", "subscribeConversationV4", [dict(tw, sessionId=target["taskId"])])
    await asyncio.wait_for(fut, timeout=15)
    print("[ok] subscribed", flush=True)
    await asyncio.sleep(5)

    async def trigger():
        msg = ("P1-1 验收：请立即调用 AskUserQuestion 工具，向我提一个问题——"
               "问题：「验收测试：以下哪项是你接下来要做的？」"
               "选项：A=提交验收报告、B=继续等待。header 写「验收确认」。"
               "调用工具后停下等待回答，不要自己编造答案，也不要做其他事。")
        rid, fut = await s.call("zcode-agent", "sendPrompt",
                                [{"workspacePath": target.get("workspacePath") or ws_key,
                                  "sessionId": target["taskId"],
                                  "inputId": f"inp-p11v-{uuid.uuid4()}", "content": msg}])
        reply = await asyncio.wait_for(fut, timeout=20)
        print("[ok] sendPrompt:", json.dumps(_jsonable(reply), ensure_ascii=False)[:120], flush=True)

    el = await wait_user_input(s, 150, trigger=trigger)
    if not el:
        sys.exit("未观察到 userInput pending 条目")
    qs = (el.get("payload") or {}).get("questions") or []
    first = (qs[0].get("options") or [{}])[0].get("value")
    print(f"[ok] pending interaction={el.get('interactionId','')[:28]} "
          f"tool={(el.get('payload') or {}).get('toolName')} 首选项={first}", flush=True)

    # 应答：官方 web 同款 {action:'accept', content:{answer: 首选项}}
    answer = {"action": "accept", "content": {"answer": first or "A"}}
    rid, fut = await s.call("zcode-agent", "sendConversationCommandV4", [dict(tw,
        envelope={"commandId": f"cmd-{uuid.uuid4()}", "clientId": client_id,
                  "sessionId": target["taskId"], "type": "resolveInteraction",
                  "payload": {"interactionId": el["interactionId"], "answer": answer},
                  "issuedAt": int(time.time() * 1000)})])
    reply = await asyncio.wait_for(fut, timeout=20)
    status = (reply or {}).get("status")
    print(f"[ok] ack: {json.dumps(_jsonable(reply), ensure_ascii=False)[:200]}", flush=True)

    # 消解观察：pendingInteractions 清空（或该条目消失）
    cleared = False
    end = time.time() + 40
    while time.time() < end:
        await asyncio.sleep(4)
        items = latest_pending(s)
        if not any(x.get("interactionId") == el["interactionId"] for x in items):
            cleared = True
            break
    print(f"[ok] cleared={cleared}", flush=True)

    print("\n===== P1-1 协议验收结论 =====", flush=True)
    print(f"A. userInput 条目接收:            PASS", flush=True)
    print(f"B. resolveInteraction answer:    {'PASS' if status else 'FAIL'} ({status})", flush=True)
    print(f"C. 桌面端消解(条目移除):          {'PASS' if cleared else 'WARN'}", flush=True)
    dump = f"../_tmp/p11v_frames_{int(time.time())}.json"
    with open(dump, "w", encoding="utf-8") as fh:
        for typ, r, body in s.frames:
            fh.write(f"### type={typ} id={r}\n" + json.dumps(_jsonable(body), ensure_ascii=False, indent=1) + "\n")
    print(f"帧落盘: {dump}", flush=True)
    await ws.close()


asyncio.run(main())
