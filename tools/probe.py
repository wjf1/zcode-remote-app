#!/usr/bin/env python3
"""
ZCode 远程控制协议探针（独立于 Android App，用于快速验证链路与 RPC 开发）。

用法：
    python tools/probe.py auth        # 仅验证中继认证（challenge/proof/auth_ack）
    python tools/probe.py boot        # 认证 + bootstrap（拉会话列表）
    python tools/probe.py bridge      # 认证 + bootstrap + 开桥（等 ready）
    python tools/probe.py sub <会话ID前缀>   # 上述全都做 + 订阅该会话流

凭据来源：优先读本机 ZCode 凭据库（自动解密），也可用环境变量覆盖：
    ZCODE_SID / ZCODE_HASH / ZCODE_MID
"""
import base64
import hashlib
import hmac
import json
import os
import platform
import getpass
import struct
import sys
import time
import uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
try:
    from websockets.sync.client import connect
except ImportError:
    from wsclient import connect  # 自带标准库实现（环境无法 pip 安装）

RELAY = "wss://zcode.z.ai/ws"
ORIGIN = "https://zcode.z.ai"
ROLE = "terminal"

# ---------- 凭据 ----------

def load_credentials():
    sid = os.environ.get("ZCODE_SID")
    h = os.environ.get("ZCODE_HASH")
    mid = os.environ.get("ZCODE_MID")
    if sid and h:
        return sid, h, mid
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    cred_path = os.path.expanduser("~/.zcode/v2/credentials.json")
    data = json.load(open(cred_path, encoding="utf-8"))
    # sid / mid 在设置里
    enc = data["web-remote-control:external-relay:pass_hash"]
    secret = os.environ.get("ZCODE_CREDENTIAL_SECRET") or \
        f"zcode-credential-fallback:{sys.platform if sys.platform!='win32' else 'win32'}:{os.path.expanduser('~')}:{getpass.getuser()}"
    key = hashlib.sha256(secret.encode()).digest()
    d = lambda s: base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))
    iv_b, tag_b, ct_b = enc[len("enc:v1:"):].split(".")
    plain = AESGCM(key).decrypt(d(iv_b), d(ct_b) + d(tag_b), None).decode()
    dev_sid = None
    for cand in ("~/.zcode/v2/setting.json", "~/.zcode/v2/config.json"):
        fp = os.path.expanduser(cand)
        if os.path.exists(fp):
            try:
                cfg = json.load(open(fp, encoding="utf-8"))
            except Exception:
                continue
            dev = cfg.get("webRemoteControlExternalRelayDevice") or {}
            if dev.get("deviceSid"):
                dev_sid = dev["deviceSid"]
                break
    if mid is None:
        mid = os.environ.get("ZCODE_MID")
    return dev_sid, plain, mid

# ---------- Vql 编解码（对应 FRAME-CODEC.md §3.3） ----------

T_UNDEF, T_STR, T_BUF, T_VSBUF, T_ARR, T_OBJ, T_INT = range(7)


def write_varint(n: int) -> bytes:
    if n == 0:
        return b"\x00"
    out = bytearray()
    while n:
        b = n & 0x7F
        n >>= 7
        out.append(b | 0x80 if n else b)
    return bytes(out)


def read_varint(buf: bytes, pos: int):
    result = shift = 0
    while True:
        b = buf[pos]; pos += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            return result, pos
        shift += 7


def vql_encode(v) -> bytes:
    if v is None:
        return bytes([T_UNDEF])
    if isinstance(v, str):
        b = v.encode()
        return bytes([T_STR]) + write_varint(len(b)) + b
    if isinstance(v, bytes):
        return bytes([T_BUF]) + write_varint(len(v)) + v
    if isinstance(v, (list, tuple)):
        out = bytes([T_ARR]) + write_varint(len(v))
        for e in v:
            out += vql_encode(e)
        return out
    if isinstance(v, bool):
        return bytes([T_OBJ]) + _j(v)
    if isinstance(v, int):
        if -2**31 <= v < 2**31:
            return bytes([T_INT]) + write_varint(v)
        return bytes([T_OBJ]) + _j(v)
    return bytes([T_OBJ]) + _j(v)


def _j(v) -> bytes:
    b = json.dumps(v, ensure_ascii=False, separators=(",", ":")).encode()
    return write_varint(len(b)) + b


def vql_decode(buf: bytes, pos: int = 0):
    tag = buf[pos]; pos += 1
    if tag == T_UNDEF:
        return None, pos
    if tag in (T_STR, T_OBJ):
        n, pos = read_varint(buf, pos)
        raw = buf[pos:pos + n]; pos += n
        s = raw.decode("utf-8", "replace")
        return (s if tag == T_STR else json.loads(s)), pos
    if tag in (T_BUF, T_VSBUF):
        n, pos = read_varint(buf, pos)
        raw = buf[pos:pos + n]; pos += n
        return raw, pos
    if tag == T_ARR:
        n, pos = read_varint(buf, pos)
        out = []
        for _ in range(n):
            v, pos = vql_decode(buf, pos)
            out.append(v)
        return out, pos
    if tag == T_INT:
        return read_varint(buf, pos)
    raise ValueError(f"unknown vql tag {tag}")


# ---------- 传输 ----------

def crc32hex(b: bytes) -> str:
    import zlib
    return format(zlib.crc32(b) & 0xFFFFFFFF, "08x")


class Probe:
    def __init__(self, sid, phash, mid=None):
        self.sid, self.phash, self.mid = sid, phash, mid
        self.ws = None
        self.bridge_sid = None
        self.bridge_gen = None
        self.next_req = 1          # 官方 lastRequestId=0 先自增后使用，首个 id 为 1
        self.acks = []             # 收到的帧类型流水，便于事后分析
        self.frames = []           # (type, id, body)，供落盘做结构分析
        self.next_seq = 1
        self.next_msg = 1
        self.last_ack = 0
        self.pending = {}
        self.initialized = False
        self.queued_calls = []

    def log(self, *a):
        print("[probe]", *a, flush=True)

    # --- ws ---
    def open(self):
        url = RELAY + (f"?mid={self.mid}" if self.mid else "")
        self.log("connecting", url)
        try:
            # websockets 17：`proxy` 默认 True = 读「操作系统代理」（Windows 注册表）。
            # 本机系统代理指向已关闭的 Clash 端口 → ConnectionRefused；
            # 显式 None = 只读环境变量（本机为空）→ 直连（zcode.z.ai 直连实测可达）。
            self.ws = connect(url, open_timeout=15, proxy=None)
        except TypeError:
            # 自带 wsclient（标准库实现）无 proxy 参数
            self.ws = connect(url, open_timeout=15)
        self.log("ws open")

    def send_payload(self, payload):
        msg = {"type": "data", "payload": payload, "client_ts": int(time.time() * 1000)}
        self.ws.send(json.dumps(msg, ensure_ascii=False))
        return msg

    @staticmethod
    def unwrap(f):
        """剥掉中继外层 data 信封，返回内层 payload。"""
        if isinstance(f, dict) and "payload" in f and isinstance(f["payload"], dict):
            return f["payload"]
        return f

    def recv_payload(self, timeout=10):
        f = self.recv(timeout=timeout)
        return self.unwrap(f) if f is not None else None

    def recv(self, timeout=10):
        try:
            raw = self.ws.recv(timeout=timeout)
        except Exception as e:
            self.log("ws recv error:", e)
            return None
        if raw is None:
            return None
        if isinstance(raw, (bytes, bytearray)):
            raw = raw.decode("utf-8", "replace")
        try:
            return json.loads(raw)
        except Exception:
            self.log("non-json frame:", str(raw)[:120])
            return None

    # --- auth ---
    def auth(self):
        self.ws.send(json.dumps({
            "type": "auth_init", "role": ROLE, "device_sid": self.sid,
            "meta": {"platform": "web", "version": "web", "name": "mobile-browser"},
            "client_ts": int(time.time() * 1000),
        }))
        while True:
            f = self.recv()
            if f is None:
                self.log("auth timeout")
                return False
            t = f.get("type")
            if t == "auth_challenge":
                nonce = f["nonce"]
                msg = f"{nonce}|{ROLE}|{self.sid}".encode()
                proof = base64.urlsafe_b64encode(
                    hmac.new(self.phash.encode(), msg, hashlib.sha256).digest()
                ).decode().rstrip("=")
                self.ws.send(json.dumps({
                    "type": "auth_response", "device_sid": self.sid,
                    "proof": proof, "client_ts": int(time.time() * 1000),
                }))
                self.log("auth_challenge -> proof sent")
            elif t in ("auth_ack", "pair_status_ack"):
                self.log("recv", json.dumps(f, ensure_ascii=False)[:200])
                if f.get("pair_status"):
                    self.pair_status = f["pair_status"]
                if t == "auth_ack" and f.get("pair_status") == "matched":
                    return True
                if t == "pair_status_ack" and f.get("pair_status") == "waiting":
                    self.log("PC 端未开启远程控制（pair_status=waiting）")
                    return False
            elif t == "error":
                self.log("relay error:", f.get("code"), f.get("message"))
                return False

    # --- rpc ---
    def call(self, channel, method, args=None, typ=100):
        if not self.initialized:
            self.log(f"queue call {method}（等 Initialize(200)）")
            self.queued_calls.append((channel, method, args, typ))
            return None
        return self._do_call(channel, method, args, typ)

    def _do_call(self, channel, method, args=None, typ=100):
        # Promise 调用：参数段是「调用参数数组」，即 serialize([arg0, arg1, ...])
        return self._rpc(typ, channel, method, vql_encode(args or []))

    def listen(self, channel, name, arg=None):
        """注册动态事件监听（type=102 EventListen）。

        注意：与 Promise 调用不同，事件监听的参数段是单个裸值 serialize(arg)，
        不是包一层的数组（见 toService 代理的 F(o) 分支）。
        """
        return self._rpc(102, channel, name, vql_encode(arg))

    def send_dispose(self, listen_req_id):
        """发 103 EventDispose（A-3 探测用）。

        字段规格取自官方 web bundle 的 `sendCancelOrDispose`：
            zu(n,[e,t]); zu(n,void 0)
        即头部数组为 **两元素** `[103, 原监听请求 id]`（与 102 的四元素
        `[102, id, channel, event]` 不同），参数段为 undefined。
        """
        body = vql_encode([103, listen_req_id]) + vql_encode(None)
        self.log(f"rpc send 103 EventDispose listenId={listen_req_id} bytes={len(body)}")
        self._send_frame(body)
        return listen_req_id

    def _rpc(self, typ, channel, name, arg_bytes):
        req = self.next_req; self.next_req += 1
        body = vql_encode([typ, req, channel, name]) + arg_bytes
        self.log(f"rpc send ch={channel} m={name} type={typ} id={req} bytes={len(body)}")
        self._send_frame(body)
        return req

    def last_result(self, rid, expect=201):
        """取指定请求 id 的成功应答体（服务端可能重放，故倒序找最近一条）。"""
        for typ, i, body in reversed(self.frames):
            if typ == expect and i == rid:
                return body
        return None

    def _flush_calls(self):
        q, self.queued_calls = self.queued_calls, []
        for c in q:
            self._do_call(*c)

    def _send_frame(self, body: bytes):
        seq, msg = self.next_seq, self.next_msg
        self.next_seq += 1; self.next_msg += 1
        frame = {
            "zcode_type": "rpc-frame", **self._identity(),
            "seq": seq, "messageSeq": msg, "fragmentIndex": 0, "fragmentCount": 1,
            "messageBytes": len(body),
            "checksum": {"algorithm": "crc32", "value": crc32hex(body)},
            "dataBase64": base64.b64encode(body).decode(),
        }
        self.send_payload(frame)

    def _identity(self):
        """bridge 身份三元组。host 做全等比较（含 undefined）：只要帧里出现任一身份键，
        三个键就必须全部相等，否则整帧被静默丢弃。故凡带 bridgeSessionId 的帧都要带上
        bridgeGeneration。"""
        ident = {"bridgeSessionId": self.bridge_sid}
        if self.bridge_gen is not None:
            ident["bridgeGeneration"] = self.bridge_gen
        return ident

    def _ack(self, message_seq):
        if message_seq is None:
            return
        if message_seq <= self.last_ack:
            return
        self.last_ack = message_seq
        self.log(f"ack send ackMessageSeq={message_seq}")
        self.send_payload({"zcode_type": "rpc-frame-ack",
                           "ackMessageSeq": message_seq, **self._identity()})

    def _on_frame(self, payload):
        b = base64.b64decode(payload["dataBase64"])
        ms = payload.get("messageSeq")
        self.log(f"rpc frame env seq={payload.get('seq')} messageSeq={ms} "
                 f"frag={payload.get('fragmentIndex')}/{payload.get('fragmentCount')}")
        if ms:
            self._ack(ms)
        head, pos = vql_decode(b, 0)
        body, _ = vql_decode(b, pos)
        typ = head[0] if isinstance(head, list) else -1
        rid = head[1] if isinstance(head, list) and len(head) > 1 else None
        self.acks.append(typ)
        self.frames.append((typ, rid, body))
        self.log(f"rpc recv type={typ} id={rid} head={head} body={str(body)[:400]}")
        if typ == 200:                      # Initialize → 通道就绪，冲掉排队请求
            if not self.initialized:
                self.initialized = True
                self.log("channel initialized → flush queued calls")
                self._flush_calls()
        return typ, rid, body

    # --- 流程 ---
    def bootstrap(self):
        rid = f"boot-{int(time.time()*1000)}"
        self.send_payload({"zcode_type": "bootstrap-request", "requestId": rid})
        while True:
            p = self.recv_payload(timeout=15)
            if p is None:
                self.log("bootstrap timeout")
                return None
            zt = p.get("zcode_type")
            if zt == "bootstrap-response":
                tasks = (p.get("result") or {}).get("tasks") or []
                ivs = (p.get("result") or {}).get("initialViewState") or {}
                self.log(f"bootstrap ok: {len(tasks)} 会话, activeWorkspaceKey={ivs.get('activeWorkspaceKey')}")
                for t in tasks[:5]:
                    self.log(f"  - {t.get('displayStatus'):10} {t.get('title','')[:40]}")
                return p
            self.log("recv", zt or p.get("type"))

    def open_bridge(self, workspace_key, task_id=None):
        sid = f"bridge-{uuid.uuid4()}"
        gen = 1
        self.send_payload({
            "zcode_type": "workspace-bridge-open", "requestId": f"workspace-bridge-{uuid.uuid4()}",
            "bridgeSessionId": sid, "bridgeGeneration": gen, "workspaceKey": workspace_key,
            **({"taskId": task_id} if task_id else {}),
        })
        self.log("bridge-open sent ws=", workspace_key)
        while True:
            p = self.recv_payload(timeout=15)
            if p is None:
                self.log("bridge timeout")
                return False
            zt = p.get("zcode_type")
            if zt == "workspace-bridge-ready":
                self.bridge_sid = p.get("bridgeSessionId")
                self.bridge_gen = p.get("bridgeGeneration")
                self.log("bridge-ready", json.dumps(p, ensure_ascii=False)[:400])
                return True
            if zt in ("workspace-bridge-error", "bridge-degraded"):
                self.log("bridge error", json.dumps(p, ensure_ascii=False)[:300])
                return False

    def pump(self, seconds=15):
        end = time.time() + seconds
        while time.time() < end:
            p = self.recv_payload(timeout=max(1, end - time.time()))
            if p is None:
                continue
            zt = p.get("zcode_type")
            if zt in ("rpc-frame", "rpc-frame-ack"):
                if zt == "rpc-frame":
                    self._on_frame(p)
                continue
            self.log("ws recv", json.dumps(p, ensure_ascii=False)[:300])


def probe_channels(p, candidates=None):
    """在候选通道上各打一发无参的 helloConversationV4，谁回 201 谁就是对话门面所在通道。

    对话门面（createZCodeAgentConnectionScope 产出的 service）跨通道暴露，
    但通道名无法从压缩代码可靠推断，故直接实测。
    """
    candidates = candidates or [
        "zcode-agent", "zcode-session", "zcode-task", "client-scenes",
        "window-controller", "broadcast", "subagents",
    ]
    for ch in candidates:
        before = len(p.acks)
        rid = p.call(ch, "helloConversationV4", [])
        p.pump(3)
        got = p.acks[before:]
        print(f"  [chan] {ch:18} -> {got}")


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "auth"
    session_prefix = sys.argv[2] if len(sys.argv) > 2 else None
    sid, phash, mid = load_credentials()
    print("sid =", sid, "| hash len =", len(phash or ""), "| mid =", mid)
    if not sid or not phash:
        print("缺凭据：设置 ZCODE_SID/ZCODE_HASH 或确保本机 ZCode 已配对")
        return 2

    p = Probe(sid, phash, mid)
    p.open()
    if not p.auth():
        print("认证未完成（PC 端远程控制可能未开启）")
        return 1
    if mode == "auth":
        return 0
    boot = p.bootstrap()
    if mode == "boot" or not boot:
        return 0
    result = boot.get("result") or {}
    ws_key = ((result.get("initialViewState") or {}).get("activeWorkspaceKey")
              or (result.get("tasks") or [{}])[0].get("workspacePath"))
    if not p.open_bridge(ws_key):
        return 1
    if mode == "bridge":
        p.pump(5)
        return 0
    p.pump(4)   # 收 RPC 通道的 Initialize(200)（未初始化前请求会被服务端丢弃）
    if mode == "chan":
        probe_channels(p)
        return 0
    if mode == "dispose":
        # A-3 探测：发 103 EventDispose，验证服务端行为与事件流是否停止。
        # 优先选 running 会话（有持续 delta 才能做「前后对比」；静态会话基线为 0 无意义）。
        tasks = result.get("tasks") or []
        target = next((t for t in tasks if not session_prefix or t.get("taskId", "").startswith(session_prefix)), None)
        if target is None and session_prefix is None:
            target = next((t for t in tasks if t.get("displayStatus") == "running"), None)
        if not target:
            print("找不到目标会话（可直接看 bootstrap 列表或指定前缀）")
            return 1
        print("观察会话:", target.get("taskId"), target.get("displayStatus"), target.get("title"))
        target_ws = {"workspacePath": target.get("workspacePath")}
        if target.get("workspaceIdentity"):
            target_ws["workspaceIdentity"] = target["workspaceIdentity"]

        hello_rid = p.call("zcode-agent", "helloConversationV4", [])
        p.pump(3)
        hello = p.last_result(hello_rid) or {}
        client_id = f"probe-{uuid.uuid4()}"
        p.call("zcode-agent", "initializeConversationV4", [{
            "kind": "clientHello",
            "protocolVersion": hello.get("protocolVersion", 3),
            "clientId": client_id,
            "clientKind": "mobileApp",
            "appVersion": "1.0.0",
        }])
        p.pump(3)
        listen_rid = p.listen("zcode-agent", "onDynamicConversationFrame", target_ws)
        p.pump(3)
        p.call("zcode-agent", "subscribeConversationV4", [dict(target_ws, sessionId=target.get("taskId"))])

        def events_since(from_idx):
            return sum(1 for typ, rid, _ in p.frames[from_idx:] if typ == 204 and rid == listen_rid)

        print(f"—— 阶段 1：订阅后观察 12s（listen_id={listen_rid}）")
        base1 = len(p.frames)
        p.pump(12)
        n1 = events_since(base1)
        print(f"    基线：204 事件 {n1} 帧")

        print("—— 阶段 2：发送 103 EventDispose")
        base2 = len(p.frames)
        p.send_dispose(listen_rid)
        p.pump(6)
        replies = [(typ, rid, body) for typ, rid, body in p.frames[base2:] if typ in (201, 202, 203)]
        if replies:
            for typ, rid, body in replies:
                print(f"    服务端应答：type={typ} id={rid} body={str(body)[:200]}")
        else:
            print("    服务端未对 103 返回 201/202/203（静默）")

        print("—— 阶段 3：dispose 后再观察 12s")
        base3 = len(p.frames)
        p.pump(12)
        n2 = events_since(base3)
        print(f"    同一 listen 的 204 事件：{n2} 帧")

        print("—— 结论")
        if n1 == 0 and n2 == 0:
            print("    ⚠️ 基线为 0（该会话当前无事件流）——本次探测无法判定，请换 running 会话重试")
        elif n1 > 0 and n2 == 0:
            print("    ✅ 103 生效：dispose 后事件流停止（可据此启用 App 侧 SEND_EVENT_DISPOSE）")
        else:
            print("    ❌ 103 未生效：dispose 后事件仍在推送（服务端不认该帧或字段不符）")
        return 0
    if mode == "dispose-stress":
        # A-3 验收口径的探针版：「切 N 次会话入站量不随 N 增长」——
        # 循环 listen→subscribe→dispose N 次，最后留一个监听，统计观察窗内 204 帧的
        # listen_id 分布：只有 1 个 id 在收 = 旧监听已释放；多个 id 重复推同一事件 = 泄漏。
        n = int(sys.argv[2]) if len(sys.argv) > 2 and sys.argv[2].isdigit() else 10
        tasks = result.get("tasks") or []
        target = next((t for t in tasks if not session_prefix or t.get("taskId", "").startswith(session_prefix)), None)
        if target is None:
            target = next((t for t in tasks if t.get("displayStatus") == "running"), None)
        if not target:
            print("找不到目标会话")
            return 1
        print("观察会话:", target.get("taskId"), target.get("displayStatus"))
        target_ws = {"workspacePath": target.get("workspacePath")}
        if target.get("workspaceIdentity"):
            target_ws["workspaceIdentity"] = target["workspaceIdentity"]
        hello_rid = p.call("zcode-agent", "helloConversationV4", [])
        p.pump(3)
        hello = p.last_result(hello_rid) or {}
        p.call("zcode-agent", "initializeConversationV4", [{
            "kind": "clientHello", "protocolVersion": hello.get("protocolVersion", 3),
            "clientId": f"probe-{uuid.uuid4()}", "clientKind": "mobileApp", "appVersion": "1.0.0",
        }])
        p.pump(3)

        print(f"—— 循环 {n} 次：listen → subscribe → dispose")
        for i in range(n):
            lid = p.listen("zcode-agent", "onDynamicConversationFrame", target_ws)
            p.pump(2)
            p.call("zcode-agent", "subscribeConversationV4", [dict(target_ws, sessionId=target.get("taskId"))])
            p.pump(2)
            p.send_dispose(lid)
            p.pump(1)
            print(f"    第 {i + 1}/{n} 次完成（listen_id={lid} 已 dispose）")

        print("—— 末次订阅（保留监听）后观察 15s：统计 204 帧的 listen_id 分布")
        last_lid = p.listen("zcode-agent", "onDynamicConversationFrame", target_ws)
        p.pump(2)
        p.call("zcode-agent", "subscribeConversationV4", [dict(target_ws, sessionId=target.get("taskId"))])
        base = len(p.frames)
        p.pump(15)
        from collections import Counter
        dist = Counter(rid for typ, rid, _ in p.frames[base:] if typ == 204)
        print(f"    末次 listen_id={last_lid}；观察窗内 204 帧分布：{dict(dist)}")
        live_ids = [k for k, v in dist.items() if v > 0]
        if not live_ids:
            print("    ⚠️ 观察窗内无事件（无法判定）——请在窗口内让会话产生事件后重试")
        elif live_ids == [last_lid]:
            print("    ✅ 只有最后一个监听在收：旧监听全部释放（无 N 倍放大）")
        else:
            print(f"    ❌ 检测到 {len(live_ids)} 个监听同时在收（旧监听泄漏，流量随 N 放大）")
        return 0
    tasks = result.get("tasks") or []
    target = next((t for t in tasks if not session_prefix or t.get("taskId", "").startswith(session_prefix)), None)
    if not target:
        print("找不到目标会话")
        return 1
    print("订阅会话:", target.get("taskId"), target.get("title"))
    target_ws = {"workspacePath": target.get("workspacePath")}
    if target.get("workspaceIdentity"):
        target_ws["workspaceIdentity"] = target["workspaceIdentity"]

    # 对话门面挂在 zcode-agent 通道（不是 zcode-session），且必须先 hello → initialize。
    # 官方 renderer 序列：
    #   helloConversationV4()                      → 201 {connectionId, clientMode, protocolVersion}
    #   initializeConversationV4({clientId})       → 201
    #   onDynamicConversationFrame(target)(cb)     → 102 事件监听（参数段是裸对象）
    #   subscribeConversationV4(target+sessionId)  → 201 {ack:{subscriptionId,...}}
    print("[flow] 1) helloConversationV4")
    hello_rid = p.call("zcode-agent", "helloConversationV4", [])
    p.pump(3)
    hello = p.last_result(hello_rid) or {}
    print("[flow]    hello ->", json.dumps(hello, ensure_ascii=False)[:200])

    # initializeConversationV4 的入参是 zod .strict() 的 clientHello：
    # {kind:"clientHello", protocolVersion:<回显 hello 的版本>, clientId, clientKind?, appVersion}
    print("[flow] 2) initializeConversationV4")
    client_id = f"probe-{uuid.uuid4()}"
    p.call("zcode-agent", "initializeConversationV4", [{
        "kind": "clientHello",
        "protocolVersion": hello.get("protocolVersion", 3),
        "clientId": client_id,
        "clientKind": "mobileApp",
        "appVersion": "1.0.0",
    }])
    p.pump(3)

    print("[flow] 3) onDynamicConversationFrame 监听注册")
    p.listen("zcode-agent", "onDynamicConversationFrame", target_ws)
    p.pump(3)

    print("[flow] 4) subscribeConversationV4")
    args = dict(target_ws, sessionId=target.get("taskId"))
    p.call("zcode-agent", "subscribeConversationV4", [args])
    p.pump(25)

    print("收帧类型流水:", p.acks)
    dump = sys.argv[3] if len(sys.argv) > 3 else None
    if dump:
        with open(dump, "w", encoding="utf-8") as fh:
            for typ, rid, body in p.frames:
                fh.write(f"### type={typ} id={rid}\n")
                fh.write(json.dumps(_jsonable(body), ensure_ascii=False, indent=1))
                fh.write("\n")
        print("已落盘帧内容:", dump)
    return 0


def _jsonable(v):
    if isinstance(v, bytes):
        return {"__bytes_hex": v.hex(), "__len": len(v)}
    if isinstance(v, list):
        return [_jsonable(x) for x in v]
    if isinstance(v, dict):
        return {k: _jsonable(x) for k, x in v.items()}
    return v


if __name__ == "__main__":
    sys.exit(main())
