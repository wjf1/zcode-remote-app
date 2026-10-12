#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""gen_rpc_frame_fixtures.py — 生成 rpc-frame 回放 fixture（任务书 §11.8「协议帧回放」）。

定位：与 tools/probe.py 同源的**独立 Python 实现**，按官方切分算法构造完整 rpc-frame 信封
（等长分片、fragmentCount ≤ 64、整条消息 CRC32、标准 base64 带 padding；算法依据见
research/FRAME-CODEC.md §3.6/§6），并把每个用例的期望结果写进 fixture。
Kotlin 侧 `RpcFrameReplayTest` 用同一组帧驱动纯逻辑层（身份判定 / base64 形状 / 元数据校验 /
分片组装）做回放断言——两份独立实现互为校验，上游协议若变，先动这里，CI 立刻变红。

用法： python tools/gen_rpc_frame_fixtures.py
产出： app-android/app/src/test/resources/rpc_frame_fixtures.json
"""
import base64
import json
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from probe import crc32hex, vql_encode  # noqa: E402

OUT = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..", "app-android", "app", "src", "test", "resources", "rpc_frame_fixtures.json",
)

BRIDGE = "bridge-1"
GENERATION = "1"
RECOVERY = "rec-1"


def b64(data: bytes) -> str:
    """标准 base64（带 padding，不换行）——官方 isCanonicalBase64 接受的规范形态。"""
    return base64.b64encode(data).decode("ascii")


def frame(
    payload: bytes,
    index: int,
    count: int,
    *,
    seq: int = 1,
    message_seq: int = 1,
    message: bytes = None,
    declared_bytes: int = None,
    checksum: str = None,
    fragment_count_declared: int = None,
    index_declared: int = None,
    with_identity: bool = True,
    identity_override: dict = None,
    data_base64: str = None,
) -> dict:
    """构造一帧 rpc-frame 信封（JSON）。默认字段全部取自官方 zod 要求的最小集。"""
    whole = message if message is not None else payload
    f = {
        "zcode_type": "rpc-frame",
        "seq": seq,
        "messageSeq": message_seq,
        "fragmentIndex": index_declared if index_declared is not None else index,
        "fragmentCount": fragment_count_declared if fragment_count_declared is not None else count,
        "messageBytes": declared_bytes if declared_bytes is not None else len(whole),
        "checksum": {"algorithm": "crc32", "value": checksum if checksum else crc32hex(whole)},
        "dataBase64": data_base64 if data_base64 is not None else b64(payload),
    }
    if identity_override is not None:
        f.update(identity_override)
    elif with_identity:
        f.update({"bridgeSessionId": BRIDGE, "bridgeGeneration": GENERATION, "recoveryId": RECOVERY})
    return f


def slices(message: bytes, per: int):
    """官方等长切分：每片 per 字节，最后一片可能更短。"""
    return [message[i:i + per] for i in range(0, len(message), per)]


def case(name, frames, outcomes, *, message=None, head=None, args=None, note="",
         metadata_violation=None, canonical=True, identity="match", completed_seq=None):
    completes = sum(1 for o in outcomes if o == "complete")
    c = {
        "name": name,
        "frames": frames,
        "outcomes": outcomes,          # buffered | complete | rejected:<官方 fault code>
        "identityVerdict": identity,   # match | absent | partial | mismatch
        "canonicalBase64": canonical,
        "metadataViolation": metadata_violation,  # null 或固定错误码
        "completedTimes": completes,   # 从 outcomes 推导，避免与期望结果脱节
        "note": note,
    }
    if message is not None:
        c["messageHex"] = message.hex()
    if completed_seq is not None:
        c["completedSeq"] = completed_seq
    if head is not None:
        c["head"] = head
        c["args"] = args
    return c


def build():
    cases = []

    # ---------- 官方文档逐字示例（FRAME-CODEC.md §4.4）：小报文 = 1 片 ----------
    head = [100, 0, "zcode-agent", "subscribeConversationV4"]
    args = [{"workspacePath": "/w", "sessionId": "abc"}]
    doc_msg = vql_encode(head) + vql_encode(args)
    cases.append(case(
        "doc_example_single_fragment",
        [frame(doc_msg, 0, 1)],
        ["complete"],
        message=doc_msg, head=head, args=args, completed_seq=1,
        note="官方 §4.4 逐字节手算示例的等价报文（VQL 两段拼接）；Kotlin 侧 bill 对 Vql 编码字节。",
    ))

    # ---------- 身份三元组 ----------
    msg = b"identity-case"
    cases.append(case(
        "identity_keys_absent",
        [frame(msg, 0, 1, with_identity=False)],
        ["complete"], message=msg, identity="absent", completed_seq=1,
        note="三个身份键都不出现：不做全等判定（官方 schema 会话键必带，此为兜底）。",
    ))
    cases.append(case(
        "identity_partial_optional_missing",
        [frame(msg, 0, 1, identity_override={"bridgeSessionId": BRIDGE, "bridgeGeneration": GENERATION})],
        ["complete"], message=msg, identity="partial", completed_seq=1,
        note="可选键 recoveryId 缺席：宽容方向（出现才比较），不误拒正常帧。",
    ))
    cases.append(case(
        "identity_session_mismatch",
        [frame(msg, 0, 1, identity_override={"bridgeSessionId": "bridge-other",
                                            "bridgeGeneration": GENERATION, "recoveryId": RECOVERY})],
        ["complete"], message=msg, identity="mismatch", completed_seq=1,
        note="旧桥迟到帧：sessionId 是桥的唯一强区分符，出现即必须全等（判定在上层，不 ack）。",
    ))

    # ---------- 单分片校验（与多分片同一套：声明长度 + 整条 CRC） ----------
    cases.append(case(
        "single_crc_mismatch",
        [frame(msg, 0, 1, checksum="deadbeef")],
        ["rejected:remote.rpcFrame.checksumMismatch"], message=msg,
        note="旧实现单分片完全不校验 CRC，是 beta33 修复点。",
    ))
    cases.append(case(
        "single_declared_bytes_mismatch",
        [frame(msg, 0, 1, declared_bytes=len(msg) + 1)],
        ["rejected:remote.rpcFrame.lengthMismatch"], message=msg,
    ))
    cases.append(case(
        "single_message_too_large",
        [frame(msg, 0, 1, declared_bytes=16 * 1024 * 1024 + 1)],
        ["rejected:remote.rpcFrame.messageTooLarge"], message=msg,
        metadata_violation="remote.rpcFrame.messageTooLarge",
        note="声明超 16 MiB：元数据校验（预算）在前，不分配任何大缓冲。",
    ))
    cases.append(case(
        "single_fragment_count_over_limit",
        [frame(msg, 0, 65, fragment_count_declared=65)],
        ["rejected:remote.rpcFrame.fragmentLimitExceeded"], message=msg,
        metadata_violation="remote.rpcFrame.fragmentLimitExceeded",
        note="官方 maxFragments=64：65 即拒。",
    ))
    cases.append(case(
        "single_negative_index",
        [frame(msg, 0, 2, index_declared=-1)],
        ["rejected:remote.rpcFrame.invalidMetadata"], message=msg,
        metadata_violation="remote.rpcFrame.invalidMetadata",
    ))
    cases.append(case(
        "single_non_canonical_base64",
        [frame(msg, 0, 1, data_base64=b64(msg) + "=")],
        [],
        message=msg,
        canonical=False,
        note="填充位多余（长度 %4 != 0）：base64 规范性校验即拒（上层还会做解码-重编码往返校验），"
             "故不进入组装阶段（outcomes 为空）。",
    ))
    # 尾部悬空位非零：能解码、但重编码与原串不等价 → 官方 isCanonicalBase64 拒绝。
    # （2026-10-12 fuzz 测试实测抓到这类串曾被形状校验放过，已改为完整规范性判定。）
    # msg 长 13 字节（≡1 mod 3）→ 规范 base64 以 "==" 收尾，末字符低 4 位必须为零；
    # 换成 'B'（=1）即悬空位非零，长度与形状仍合法。
    tail_bits = b64(msg)
    noncanon_tail = tail_bits[:-3] + "B=="
    assert len(noncanon_tail) == len(tail_bits) and tail_bits.endswith("==")
    cases.append(case(
        "single_non_canonical_trailing_bits",
        [frame(msg, 0, 1, data_base64=noncanon_tail)],
        [],
        message=msg,
        canonical=False,
        note="末字符低 4 位非零（悬空位未清零）：规范 base64 要求未使用比特为零。",
    ))

    # ---------- 多分片：官方等长切分 ----------
    whole = bytes((i * 7 + 3) % 256 for i in range(300))
    parts = slices(whole, 100)
    cases.append(case(
        "multi_three_equal_fragments",
        [frame(p, i, 3, seq=i + 1, message=whole) for i, p in enumerate(parts)],
        ["buffered", "buffered", "complete"], message=whole, completed_seq=1,
        note="官方等长切分（每片 100B），整条 CRC 只算一次。",
    ))

    whole2 = bytes((i * 11 + 5) % 256 for i in range(200))
    parts2 = slices(whole2, 128)
    cases.append(case(
        "multi_uneven_last_fragment",
        [frame(p, i, 2, seq=i + 1, message=whole2) for i, p in enumerate(parts2)],
        ["buffered", "complete"], message=whole2, completed_seq=1,
        note="最后一片更短（128 + 72）：官方允许。",
    ))

    whole3 = bytes((i * 13 + 1) % 256 for i in range(640))
    parts3 = slices(whole3, 10)
    cases.append(case(
        "multi_max_fragments_64",
        [frame(p, i, 64, seq=i + 1, message=whole3) for i, p in enumerate(parts3)],
        ["buffered"] * 63 + ["complete"], message=whole3, completed_seq=1,
        note="fragmentCount 上界 64：完全合法且必须组装成功。",
    ))

    cases.append(case(
        "multi_fragment_gap",
        [frame(parts[0], 0, 3, seq=1, message=whole), frame(parts[2], 2, 3, seq=3, message=whole)],
        ["buffered", "rejected:remote.rpcFrame.fragmentGap"], message=whole,
        note="缺中间片：官方严格顺序语义，拒绝且不 ack（上层重同步）。",
    ))
    cases.append(case(
        "multi_first_fragment_missing",
        [frame(parts[1], 1, 3, seq=2, message=whole)],
        ["rejected:remote.rpcFrame.fragmentGap"], message=whole,
        note="首片缺失：无法补齐，直接拒绝。",
    ))

    tampered = parts[2][:-1] + bytes([(parts[2][-1] + 1) % 256])
    cases.append(case(
        "multi_checksum_mismatch_on_join",
        [frame(parts[0], 0, 3, seq=1, message=whole),
         frame(parts[1], 1, 3, seq=2, message=whole),
         frame(tampered, 2, 3, seq=3, message=whole, message_seq=1)],
        ["buffered", "buffered", "rejected:remote.rpcFrame.checksumMismatch"], message=whole,
        note="末片内容被篡改（长度不变）：拼装后整条 CRC 不符。",
    ))

    cases.append(case(
        "multi_duplicate_identical_fragment",
        [frame(parts[0], 0, 3, seq=1, message=whole),
         frame(parts[0], 0, 3, seq=1, message=whole),
         frame(parts[1], 1, 3, seq=2, message=whole),
         frame(parts[2], 2, 3, seq=3, message=whole)],
        ["buffered", "buffered", "buffered", "complete"], message=whole, completed_seq=1,
        note="同指纹重复分片（服务端重传）：幂等忽略，不改变状态。",
    ))

    conflicting = parts[0][:-1] + bytes([(parts[0][-1] + 1) % 256])
    cases.append(case(
        "multi_conflicting_duplicate",
        [frame(parts[0], 0, 3, seq=1, message=whole),
         frame(conflicting, 0, 3, seq=1, message=whole)],
        ["buffered", "rejected:remote.rpcFrame.conflictingDuplicate"], message=whole,
        note="同一位点不同内容：官方 conflictingDuplicate，拒绝并丢弃该消息缓冲。",
    ))

    cases.append(case(
        "multi_metadata_drift_mid_message",
        [frame(parts[0], 0, 3, seq=1, message=whole),
         frame(parts[1], 1, 3, seq=2, message=whole, fragment_count_declared=4)],
        ["buffered", "rejected:remote.rpcFrame.invalidMetadata"], message=whole,
        note="同一条消息中途 fragmentCount 变化：官方 metadataMismatch 语义。",
    ))

    return {
        "note": (
            "由 tools/gen_rpc_frame_fixtures.py 生成（独立 Python 实现官方切分算法；算法依据 "
            "research/FRAME-CODEC.md §3.6/§6）。Kotlin 侧 RpcFrameReplayTest 回放断言；"
            "outcomes 取值：buffered|complete|rejected:<官方 fault code>。"
        ),
        "identity": {"sessionId": BRIDGE, "generation": GENERATION, "recoveryId": RECOVERY},
        "cases": cases,
    }


def main():
    data = build()
    with open(os.path.abspath(OUT), "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print(f"wrote {os.path.abspath(OUT)}: {len(data['cases'])} cases")
    for c in data["cases"]:
        print(f"  - {c['name']}: {len(c['frames'])} frames -> {c['outcomes']}")


if __name__ == "__main__":
    main()
