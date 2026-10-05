#!/usr/bin/env python3
"""
enumerate_rpc.py — 远程可达 RPC 面探测（Sprint 3 第二步前置，吸收自八周计划 W3B）。

回答四个决定性问题：
  1. file.readTextFile 的路径边界（绝对路径能否越出 workspace 沙箱？）
  2. 有无目录列举方法（决定文件浏览器形态）
  3. 有无 git/命令执行通道（决定 diff 数据源）
  4. 有无文件写能力（安全边界决定性事实，期望：无）

静态线索（asar/out/host/index.js，2026-10-05 枚举）：
  file 服务方法族：resolvePath / stat / readTextFile / readFile(startLine,endLine) /
  readFileRange / readMediaPreview / readBinaryPreview；路径链 resolveAllowedFilePath →
  ow(workspacePath, ...)（path.resolve 语义 → 绝对路径覆盖 base）→ assertContainedPath →
  Repo Wiki ignore 过滤（DG/getIgnorePatterns）。

用法： python tools/enumerate_rpc.py   （走 probe 本体凭据；面板/桌面端可能被瞬时互踢后自恢复）
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from probe import Probe, load_credentials  # noqa: E402

WIN_INI = r"C:/Windows/win.ini"


def try_call(p, channel, method, args, wait=3):
    """单次探测：返回 (ok, reply_or_error摘要)。"""
    try:
        rid = p.call(channel, method, args)
    except Exception as e:  # noqa: BLE001
        return False, f"call异常: {e}"
    p.pump(wait)
    reply = p.last_result(rid) if rid is not None else None
    if reply is None:
        return False, "无应答（方法不存在或被丢弃）"
    s = json.dumps(reply, ensure_ascii=False, default=str)
    return True, s[:400]


def main():
    sid, phash, mid = load_credentials()
    if not sid or not phash:
        print("缺凭据")
        return 2
    p = Probe(sid, phash, mid)
    p.open()
    if not p.auth():
        # App 同款补救：auth_ack(waiting) 可能只是旧 terminal 槽未释放（force-stop 后 TCP
        # 断开检测延迟）——中继不推送 pair_status 变化，主动发 pair_status_query 轮询。
        import time as _t
        matched = False
        for _ in range(6):
            _t.sleep(2)
            try:
                p.ws.send(json.dumps({"type": "pair_status_query", "device_sid": sid,
                                      "client_ts": int(_t.time() * 1000)}))
                f = p.recv(4)
            except Exception:  # noqa: BLE001
                f = None
            if f and f.get("pair_status") == "matched":
                print("[probe] 轮询后 matched\n")
                matched = True
                break
            if f and f.get("type") == "error":
                print(f"[probe] error: {f.get('code')} {f.get('message')}\n")
                break
        if not matched:
            print("[warn] 轮询后仍 waiting——继续尝试 bootstrap（预期失败，记录边界）\n")
    boot = p.bootstrap()
    if not boot:
        print("bootstrap 无应答")
        return 1
    result = boot.get("result") or {}
    tasks = result.get("tasks") or []
    ws_key = ((result.get("initialViewState") or {}).get("activeWorkspaceKey")
              or (tasks[0].get("workspacePath") if tasks else None))
    if not p.open_bridge(ws_key):
        print("开桥失败")
        return 1
    deadline = __import__("time").time() + 12
    while not p.initialized and __import__("time").time() < deadline:
        p.pump(1)
    print(f"[bridge] ws_key={ws_key} initialized={p.initialized}\n")

    real_path = None

    print("== 问题 1a：resolvePath 展开 ~ ==")
    ok, msg = try_call(p, "file", "resolvePath", [{"path": "~/.zcode/v2/setting.json"}])
    print(f"  resolvePath(~/.zcode/v2/setting.json) ok={ok}\n  -> {msg}\n")
    if ok:
        try:
            data = json.loads(json.dumps([msg])[1:-1] and json.loads(msg))
            real_path = (data.get("path") or data.get("result", {}).get("path") or
                         data.get("absolutePath"))
        except Exception:  # noqa: BLE001
            m = __import__("re").search(r'"path"\s*:\s*"([^"]+)"', msg)
            real_path = m.group(1) if m else None
    print(f"  解析 real_path = {real_path}\n")

    print("== 问题 1b：readTextFile 读工作区外文件（~ 展开）==")
    if real_path:
        ok, msg = try_call(p, "file", "readTextFile", [{"path": real_path}])
        head = msg[:120].replace("\\n", " ")
        print(f"  ok={ok} -> {head}\n")

    print("== 问题 1c：readTextFile 读任意绝对路径（沙箱边界）==")
    for target in (WIN_INI, os.path.expanduser("~/.ssh/config").replace("\\", "/")):
        ok, msg = try_call(p, "file", "readTextFile", [{"path": target}])
        head = msg[:100].replace("\\n", " ")
        print(f"  readTextFile({target}) ok={ok} -> {head}\n")

    print("== 问题 2：目录列举方法探测 ==")
    for m_name in ("readdir", "readDirectory", "listDir", "listFiles", "stat"):
        ok, msg = try_call(p, "file", m_name, [{"path": ws_key or "F:/AI/Zcode"}])
        head = msg[:100]
        print(f"  file.{m_name} ok={ok} -> {head}\n")

    print("== 问题 2b：分段读 readFile/readFileRange ==")
    if real_path:
        for m_name, args in (("readFile", [{"path": real_path, "startLine": 1, "endLine": 3}]),
                             ("readFileRange", [{"path": real_path, "startLine": 1, "endLine": 3}])):
            ok, msg = try_call(p, "file", m_name, args)
            print(f"  file.{m_name} ok={ok} -> {msg[:100]}\n")

    print("== 问题 4：写能力探测（期望全部不存在）==")
    for m_name, args in (("writeTextFile", [{"path": ws_key or "F:/AI/Zcode/x.txt", "content": "probe"}]),
                         ("write", [{"path": ws_key or "F:/AI/Zcode/x.txt", "content": "probe"}]),
                         ("createFile", [{"path": ws_key or "F:/AI/Zcode/x.txt"}]),
                         ("deleteFile", [{"path": ws_key or "F:/AI/Zcode/x.txt"}])):
        ok, msg = try_call(p, "file", m_name, args)
        print(f"  file.{m_name} ok={ok} -> {msg[:90]}\n")

    print("== 问题 3：git/命令通道归属探测（getStatus 多通道试探）==")
    for ch in ("zcode-agent", "zcode-task", "workspace", "zcode-git", "git"):
        ok, msg = try_call(p, ch, "getStatus", [{"workspacePath": ws_key}], wait=2)
        head = msg[:80]
        print(f"  {ch}.getStatus ok={ok} -> {head}\n")

    print("== 补充：file 通道其余静态方法可达性 ==")
    for m_name in ("readBinaryPreview", "readMediaPreview"):
        ok, msg = try_call(p, "file", m_name, [{"path": ws_key or "F:/AI/Zcode"}], wait=2)
        print(f"  file.{m_name} ok={ok} -> {msg[:80]}\n")

    print("[done] 结论整理见 PROTOCOL.md 新章节")
    return 0


if __name__ == "__main__":
    sys.exit(main())
