#!/usr/bin/env python3
"""解析 _tmp/t0_samples.txt，输出每次冷启动的三跳握手耗时与快照落地耗时。

行格式：MM-DD HH:MM:SS.mmm  pid  tid I Tag: message
"""
import re
import statistics as st
import sys

PATH = sys.argv[1] if len(sys.argv) > 1 else "_tmp/t0_samples.txt"
TS = re.compile(r"^(\d{2}-\d{2} (\d{2}):(\d{2}):(\d{2})\.(\d{3}))\s")

def secs(line):
    m = TS.match(line)
    if not m:
        return None
    _, h, mi, s, ms = m.groups()
    return int(h) * 3600 + int(mi) * 60 + int(s) + int(ms) / 1000.0

def main():
    samples, cur = [], []
    for raw in open(PATH, encoding="utf-8", errors="replace"):
        line = raw.rstrip("\n")
        if line.startswith("===== SAMPLE"):
            cur = []
            samples.append(cur)
            continue
        if line.strip() and samples:
            samples[-1].append(line)

    rows = []
    for idx, lines in enumerate(samples):
        sends = {}      # method -> (id, timestamp)
        recvs = {}      # id -> timestamp
        subscribed = snapshot = None
        for ln in lines:
            t = secs(ln)
            if t is None:
                continue
            m = re.search(r"method=(\w+) id=(\d+)", ln)
            if m:
                sends[m.group(1)] = (int(m.group(2)), t)
                continue
            m = re.search(r"rpc recv type=201 id=(\d+)", ln)
            if m:
                recvs[int(m.group(1))] = t
                continue
            if "subscribed sub=" in ln:
                subscribed = t
            elif "snapshot: " in ln:
                snapshot = t

        def rt(method):
            # 按请求 id 精确配对：并发 RPC（system/model-selection 等）不会串台
            send = sends.get(method)
            if send is None:
                return None
            reply = recvs.get(send[0])
            return None if reply is None else reply - send[1]

        hello_rt = rt("helloConversationV4")
        init_rt = rt("initializeConversationV4")
        sub_rt = rt("subscribeConversationV4")
        snap = (snapshot - subscribed) if (subscribed and snapshot) else None
        total = None
        if "helloConversationV4" in sends and snapshot is not None:
            total = snapshot - sends["helloConversationV4"][1]
        if hello_rt is None:
            print(f"样本 {idx+1}: 未抓到 hello（跳过）")
            continue
        rows.append((idx + 1, hello_rt, init_rt, sub_rt, snap, total))

    def fmt(v):
        return "  n/a " if v is None else f"{v*1000:7.0f}"

    print(f"{'样本':>4} {'hello':>8} {'init':>8} {'subscribe':>10} {'ack→快照':>10} {'总计':>8}   (ms)")
    for r in rows:
        print(f"{r[0]:>4} {fmt(r[1])} {fmt(r[2])} {fmt(r[3])} {fmt(r[4])} {fmt(r[5])}")

    cols = {name: [r[i] for r in rows if r[i] is not None]
            for name, i in (("hello", 1), ("init", 2), ("subscribe", 3), ("ack→快照", 4), ("总计", 5))}
    print()
    for name, vals in cols.items():
        if not vals:
            continue
        vs = sorted(vals)
        p95 = vs[min(len(vs) - 1, int(round(0.95 * (len(vs) - 1))))]
        print(f"{name:>10}: n={len(vals)}  min={min(vals)*1000:.0f}  median={st.median(vals)*1000:.0f}  "
              f"max={max(vals)*1000:.0f}  'p95近似'={p95*1000:.0f}")

if __name__ == "__main__":
    main()
