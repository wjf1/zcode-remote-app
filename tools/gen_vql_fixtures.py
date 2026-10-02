#!/usr/bin/env python3
"""
gen_vql_fixtures.py — 生成 VQL 编解码金标准对拍 fixture（Sprint 6）。

本脚本与 tools/probe.py（Python 侧独立实现）同源，产出的向量被
app-android/app/src/test/.../VqlCrossVectorTest.kt 消费：Kotlin 侧 Vql.kt 对每个向量
做「编码字节级一致 + 解码语义一致」双向对拍。两份独立实现互为校验（HANDOVER §2.4），
上游协议若变，先动这里，CI 立刻变红。

用法： python tools/gen_vql_fixtures.py   （产出 app-android/app/src/test/resources/vql_fixtures.json）
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from probe import vql_encode, vql_decode  # noqa: E402

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                   "..", "app-android", "app", "src", "test", "resources", "vql_fixtures.json")

# (用例名, Kotlin 构造 kind, Python 值)。kind 告诉 Kotlin 测试按哪个分支构造输入值。
# 注意：不含负整数 —— 协议 varint 是 uint32 语义，Python 侧 write_varint 对负数死循环，
# 官方 JS 侧同样只编 uint32（负数走 JSON tag），两端行为一致地不支持。
CASES = [
    ("null", "null", None),
    ("empty string", "string", ""),
    ("ascii string", "string", "hello zcode"),
    ("chinese string", "string", "会话审批通过"),
    ("long string 300B (2-byte varint)", "string", "x" * 300),
    ("long chinese string (multi-byte len)", "string", "审批" * 200),
    ("int 0", "int", 0),
    ("int 127 (varint 1B)", "int", 127),
    ("int 128 (varint 2B)", "int", 128),
    ("int 16383 (varint 2B max)", "int", 16383),
    ("int 16384 (varint 3B)", "int", 16384),
    ("int32 max", "int", 2**31 - 1),
    ("bool true (JSON tag)", "bool", True),
    ("bool false (JSON tag)", "bool", False),
    ("buffer", "buffer", bytes(range(64))),
    ("empty array", "array", []),
    ("mixed array", "array", ["a", 1, True, None, {"k": "v"}]),
    ("nested object", "object", {
        "sessionId": "abc-123", "mode": "build", "中文键": "中文值",
        "count": 3, "flag": False,
        "nested": {"deep": [1, 2, "three"], "empty": {}},
    }),
    ("object with unicode values", "object", {"命令": "git push --force", "行数": 848}),
    ("empty object", "object", {}),
]

# 连续多值编码（RPC 报文 = 头部数组 ++ 参数，对应 Kotlin serialize(vararg)）
MULTI_CASES = [
    ("rpc head + args", ["zcode-agent", "setMode",
                         [{"workspacePath": "F:/x/y", "sessionId": "t1", "mode": "build"}]]),
    ("mixed scalars", ["str", 42, True, None]),
]


def norm(v):
    """Python 解码结果 → 可与 kotlinx JSON 输出对拍的 JSON 值。"""
    if isinstance(v, (bytes, bytearray)):
        return {"__buffer_hex__": bytes(v).hex()}
    if isinstance(v, list):
        return [norm(e) for e in v]
    return v


def main():
    cases = []
    for name, kind, v in CASES:
        enc = vql_encode(v)
        back, _ = vql_decode(enc)
        assert back == v or (isinstance(v, (bytes, bytearray)) and bytes(back) == bytes(v)), name
        value_json = v.hex() if kind == "buffer" else v
        cases.append({"name": name, "kind": kind, "value": value_json, "hex": enc.hex()})

    multis = []
    for name, vs in MULTI_CASES:
        blob = b"".join(vql_encode(v) for v in vs)
        multis.append({"name": name, "values": vs, "hex": blob.hex()})

    doc = {"cases": cases, "multi": multis}
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, indent=2)
    print(f"OK: {len(cases)} cases + {len(multis)} multi → {os.path.normpath(OUT)}")


if __name__ == "__main__":
    main()
