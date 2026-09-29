#!/usr/bin/env python3
"""P1-1 预研（基于 _p01_async.py 的已验证骨架）：触发 AskUserQuestion，
把订阅会话后收到的 204 帧全部落盘，观测 pendingInteractions 的 userInput
条目与任务事件 elicitation_request 的真实字段。"""
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
        self.dead = None

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
            self.dead = repr(e)
            print("[reader died]", repr(e)[:200], flush=True)


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

    await s.send_payload({"zcode_type": "bootstrap-request", "requestId": "boot-p11"})
    boot = await wait_payload_event(s, "bootstrap-response", 15)
    result = (boot or {}).get("result") or {}
    ws_key = (result.get("initialViewState") or {}).get("activeWorkspaceKey")
    target = next((t for t in result.get("tasks") or []
                   if t.get("taskId", "").startswith(prefix) or prefix in (t.get("title") or "")), None)
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
    client_id = f"probe-p11-{uuid.uuid4().hex[:8]}"
    rid, fut = await s.call("zcode-agent", "initializeConversationV4", [{
        "kind": "clientHello", "protocolVersion": hello.get("protocolVersion", 3),
        "clientId": client_id, "clientKind": "mobileApp", "appVersion": "1.0.0"}])
    await asyncio.wait_for(fut, timeout=15)
    await s.listen("zcode-agent", "onDynamicConversationFrame", dict(tw))
    rid, fut = await s.call("zcode-agent", "subscribeConversationV4", [dict(tw, sessionId=target["taskId"])])
    sub = await asyncio.wait_for(fut, timeout=15)
    print("[ok] subscribed", json.dumps(_jsonable((sub or {}).get("ack")), ensure_ascii=False)[:120], flush=True)
    await asyncio.sleep(5)

    msg = ("P1-1 协议观测：请立即调用 AskUserQuestion 工具，向我提一个问题——"
           "问题：「表单交互观测测试：你希望下一个功能做什么？」"
           "选项：A=文件上传、B=语音输入、C=多会话看板。header 写「功能选择」。"
           "调用工具后停下等待回答，不要自己编造答案，也不要做其他事。")
    rid, fut = await s.call("zcode-agent", "sendPrompt",
                            [{"workspacePath": target.get("workspacePath") or ws_key,
                              "sessionId": target["taskId"], "inputId": f"inp-p11-{uuid.uuid4()}",
                              "content": msg}])
    reply = await asyncio.wait_for(fut, timeout=20)
    print("[ok] sendPrompt:", json.dumps(_jsonable(reply), ensure_ascii=False)[:150], flush=True)

    # 结构化检测：pendingInteractions 非空（快照/patch）或任务事件 elicitation_request
    def hit_interactions(frames):
        for typ, i, body in frames:
            if typ != 204:
                continue
            pl = ((body or {}).get("frame") or {}).get("payload") or {}
            if pl.get("kind") == "snapshot":
                if (pl.get("snapshot") or {}).get("pendingInteractions"):
                    return True
            elif pl.get("kind") == "deltas":
                for d in pl.get("deltas") or []:
                    if d.get("op") == "state.updated" and (d.get("patch") or {}).get("pendingInteractions"):
                        return True
        return False

    hit = False
    end = time.time() + 150
    while time.time() < end and not hit:
        await asyncio.sleep(5)
        if hit_interactions(s.frames):
            hit = True
            break
        # 任务事件流路径（reader 把非 rpc-frame 的 zcode_type 帧放进了 events）
        while not s.events.empty():
            ev = s.events.get_nowait()
            if ev.get("type") == "elicitation_request":
                hit = True
    print(f"[{'ok' if hit else '!!'}] interaction seen={hit} frames={len(s.frames)}", flush=True)
    with open("../_tmp/p11_frames.json", "w", encoding="utf-8") as fh:
        for typ, i, body in s.frames:
            fh.write(f"### type={typ} id={i}\n" + json.dumps(_jsonable(body), ensure_ascii=False, indent=1) + "\n")
    print("[done] frames:", len(s.frames), "→ _tmp/p11_frames.json", flush=True)
    await ws.close()


asyncio.run(main())
