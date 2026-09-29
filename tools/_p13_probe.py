"""P1-3 协议实证：附件上传四件套（attachmentBeginV4 / ChunkV4 / CommitV4）实机试调。

背景：官方 web bundle（research/index-nOVzQNKW.js）的客户端实现 `TTe`：
  uploadId   = "upload-" + uuid
  chunkBytes = 384*1024（host 上限 attachmentChunkMaxBytes=512KiB）
  checksum   = "sha256:" + sha256(file).hex()（小写）
  dataBase64 = 标准 base64
  begin  → {state, ref, nextChunkIndex}      state=="committed" 时复用 ref
  chunk  → {nextChunkIndex}（必须 == 已发 index+1）
  commit → ref
  abort  → 失败时回滚
  args  = {workspacePath[, workspaceIdentity], sessionId, uploadId, fileName, mime,
           totalBytes, totalChunks, checksum}；host 侧 dr(m) 需能解析出连接（可能还要 connectionId）。

本脚本只做只读试调：先用候选参数打 attachmentBeginV4，读错误信息与响应结构定位真实参数，
成功后按四件套走一遍小文件上传。不发送 prompt（不污染会话）。
"""
import base64
import hashlib
import json
import sys
import time
import uuid

import probe
from probe import Probe, load_credentials

MIME = "text/plain"
CHUNK = 384 * 1024


def subscribe(p, target):
    """hello → initialize → listen → subscribe，返回 (connectionId, 订阅结果)。"""
    hello_rid = p.call("zcode-agent", "helloConversationV4", [])
    p.pump(3)
    hello = p.last_result(hello_rid) or {}
    print("[hello]", json.dumps(hello, ensure_ascii=False)[:240])
    client_id = f"probe-{uuid.uuid4()}"
    p.call("zcode-agent", "initializeConversationV4", [{
        "kind": "clientHello",
        "protocolVersion": hello.get("protocolVersion", 3),
        "clientId": client_id,
        "clientKind": "mobileApp",
        "appVersion": "1.0.0",
    }])
    p.pump(3)
    ws = {"workspacePath": target.get("workspacePath")}
    if target.get("workspaceIdentity"):
        ws["workspaceIdentity"] = target["workspaceIdentity"]
    p.listen("zcode-agent", "onDynamicConversationFrame", ws)
    p.pump(2)
    rid = p.call("zcode-agent", "subscribeConversationV4", [dict(ws, sessionId=target["taskId"])])
    p.pump(6)
    ack = p.last_result(rid) or {}
    print("[subscribe]", json.dumps(ack, ensure_ascii=False)[:240])
    return hello.get("connectionId"), ws, client_id


def try_begin(p, label, args):
    """打一发 attachmentBeginV4，返回 (是否 201, body)。"""
    before = len(p.frames)
    rid = p.call("zcode-agent", "attachmentBeginV4", [args])
    p.pump(4)
    tail = p.frames[before:]
    ok = p.last_result(rid)
    print(f"  [{label}] frames={[(t, i) for t, i, _ in tail]}")
    print(f"  [{label}] result={json.dumps(probe._jsonable(ok), ensure_ascii=False)[:300]}")
    for t, i, b in tail:
        if t not in (200, 201, 204):
            print(f"  [{label}] non-2xx type={t} body={json.dumps(probe._jsonable(b), ensure_ascii=False)[:300]}")
    return ok is not None, ok


def main():
    sid, phash, mid = load_credentials()
    p = Probe(sid, phash, mid)
    p.open()
    if not p.auth():
        print("认证失败"); return 1
    boot = p.bootstrap()
    if not boot:
        return 1
    result = boot.get("result") or {}
    ws_key = ((result.get("initialViewState") or {}).get("activeWorkspaceKey")
              or (result.get("tasks") or [{}])[0].get("workspacePath"))
    if not p.open_bridge(ws_key):
        print("开桥失败"); return 1
    p.pump(4)

    tasks = result.get("tasks") or []
    prefix = sys.argv[1] if len(sys.argv) > 1 else None
    target = next((t for t in tasks if not prefix or t.get("taskId", "").startswith(prefix)), None)
    if not target:
        print("找不到目标会话"); return 1
    print("[target]", target.get("taskId"), (target.get("title") or "")[:40])

    conn_id, ws, client_id = subscribe(p, target)

    data = b"zcode-remote attachment probe\n"
    upload_id = f"upload-{uuid.uuid4()}"
    base = dict(ws, sessionId=target["taskId"], uploadId=upload_id, fileName="probe.txt",
                mime=MIME, totalBytes=len(data), totalChunks=1,
                checksum="sha256:" + hashlib.sha256(data).hexdigest())
    print("[begin 试参]")
    # A) 官方 web 形态：scope + sessionId + uploadId + 文件元信息
    if try_begin(p, "A: 无 connectionId", base)[0]:
        run_upload(p, base, data, target["taskId"])
    elif conn_id and try_begin(p, "B: 带 connectionId", dict(base, connectionId=conn_id))[0]:
        run_upload(p, dict(base, connectionId=conn_id), data, target["taskId"])
    else:
        print("begin 未成功，看上面错误信息定位参数")
        return 1

    # 多分片用例：覆盖 Kotlin 的分片循环（384KiB/片）与 nextChunkIndex 进度语义
    print("\n[多分片] 900KiB → 3 片")
    big = bytes(900 * 1024)
    up2 = f"upload-{uuid.uuid4()}"
    base2 = dict(ws, sessionId=target["taskId"], uploadId=up2, fileName="big.bin",
                 mime="application/octet-stream", totalBytes=len(big),
                 totalChunks=(len(big) + CHUNK - 1) // CHUNK,
                 checksum="sha256:" + hashlib.sha256(big).hexdigest())
    rid = p.call("zcode-agent", "attachmentBeginV4", [base2])
    p.pump(4)
    begin2 = p.last_result(rid)
    print("  begin ->", json.dumps(probe._jsonable(begin2), ensure_ascii=False)[:200])
    nxt = (begin2 or {}).get("nextChunkIndex", 0)
    for i in range(nxt, base2["totalChunks"]):
        part = big[i * CHUNK:(i + 1) * CHUNK]
        rid = p.call("zcode-agent", "attachmentChunkV4",
                     [dict(base2, chunkIndex=i, dataBase64=base64.b64encode(part).decode())])
        p.pump(4)
        got = probe._jsonable(p.last_result(rid))
        print(f"  chunk[{i}] {len(part)}B -> {json.dumps(got, ensure_ascii=False)[:120]}")
        if (got or {}).get("nextChunkIndex") != i + 1:
            print("  !! 分片进度异常"); return 1
    rid = p.call("zcode-agent", "attachmentCommitV4",
                 [{k: base2[k] for k in ("workspacePath", "workspaceIdentity", "sessionId", "uploadId")
                   if k in base2}])
    p.pump(5)
    print("  commit ->", json.dumps(probe._jsonable(p.last_result(rid)), ensure_ascii=False)[:200])
    return 0


def run_upload(p, base, data, session_id):
    print("[四件套] begin ok，继续 chunk/commit")
    rid = p.call("zcode-agent", "attachmentChunkV4", [dict(base, chunkIndex=0,
                 dataBase64=base64.b64encode(data).decode())])
    p.pump(4)
    print("  chunk ->", json.dumps(probe._jsonable(p.last_result(rid)), ensure_ascii=False)[:200])
    commit_args = {k: base[k] for k in ("workspacePath", "workspaceIdentity", "sessionId",
                                        "uploadId", "connectionId") if k in base}
    rid = p.call("zcode-agent", "attachmentCommitV4", [commit_args])
    p.pump(5)
    print("  commit ->", json.dumps(probe._jsonable(p.last_result(rid)), ensure_ascii=False)[:400])
    return 0


if __name__ == "__main__":
    sys.exit(main())
