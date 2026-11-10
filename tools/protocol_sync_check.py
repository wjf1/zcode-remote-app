#!/usr/bin/env python3
"""官方 sync 协议契约 diff 跟踪 —— 把「官方单方面演进协议」的结构性风险变成例行检查。

官方仓库（zai-org/ZCode）以快照式 sync 推进、无 CHANGELOG、中继服务端闭源，远控 Relay V2
协议行为可能随任意一次 sync 改变（先例：V1→V2 一夜硬切、legacy 单片分支删除、重放缓存
8→32 MiB）。本脚本对官方镜像内的**契约文件**做内容哈希对比 + zcode_type 消息类型集合对比，
把「官方变了什么」收敛为一份可人工复核的增量清单。

用法：
  # 1) 官方镜像浅克隆（git 已配置代理；镜像放 .tmp 下专属新目录，勿混入工作区）
  git clone --depth 1 https://github.com/zai-org/ZCode ../.tmp/zai-zcode-mirror

  # 2) 首次建立基线 / 之后例行 diff（镜像路径可省，默认 <仓库父目录>/.tmp/zai-zcode-mirror）
  python tools/protocol_sync_check.py
  python tools/protocol_sync_check.py --mirror <路径>

  # 3) 对照输出清单人工复核：新增类型 → 评估能力位跟进；移除类型 → 排查 APP 是否在用
  #    （对照 PROTOCOL.md）；契约常量变化 → 逐项核对 ProtocolLimits.kt / 重建连时序。

口径与判读（防误读）：
- WATCHED 是「契约面」文件清单：shared 消息契约、RPC 传输常量、重放适配器、pairing 契约
  文档、架构文档、官方手机端恢复策略入口、官方 Android 壳。任何一项哈希变化都值得看 git diff。
- zcode_type 集合 diff 只看 contract.ts 里的 kebab-case 消息类型名：**移除**最高优先级
  （APP 在用即断），**新增**=官方扩展业务面（可能是可跟进的能力位，如 task-pin /
  workspace-create / mobile-log）。
- exit code：无变化 0；有变化 1；镜像缺失 2。基线写 research/protocol-sync/snapshot.json，
  人工复核通过后随代码一起提交（下轮 diff 才有意义）。
- 本脚本只做「发现变化」，不做「判断兼容性」——兼容性结论必须按 AGENTS.md 硬约束标注依据
  （bundle 静态分析 or 真机实测），冲突以实测为准并回改文档。
"""

from __future__ import annotations

import argparse
import datetime
import hashlib
import json
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
SNAPSHOT_PATH = REPO_ROOT / "research" / "protocol-sync" / "snapshot.json"
DEFAULT_MIRROR = REPO_ROOT.parent / ".tmp" / "zai-zcode-mirror"

# 契约面文件清单（官方镜像内相对路径）。增删条目 = 跟踪面变化，需在 PR 描述里说明理由。
WATCHED: list[str] = [
    "packages/shared/src/web-remote-relay/contract.ts",
    "packages/shared/src/web-remote-relay/domain/webRemoteControlActions.ts",
    "packages/shared/src/web-remote-relay/mobileLog.ts",
    "packages/shared/src/web-remote-control-rpc-transport.ts",
    "packages/client/src/webRemoteControlAcknowledgedRelayProtocolTypes.ts",
    "packages/client/src/webRemoteControlAcknowledgedRelayProtocol.ts",
    "docs/web-remote-control/relay-v2-pairing.md",
    "docs/web-remote-control/web-remote-control-architecture.md",
    "packages/web/src/web-remote-control/app/webRemoteControlEntry.ts",
    # 官方 Android 壳（v3.17.2 包名 com.example.zcode_mobile）：入口与 URL 白名单策略
    "packages/android/app/src/main/java/com/example/zcode_mobile/MainActivity.kt",
    "packages/android/app/src/main/java/com/example/zcode_mobile/remote/RemoteControlUrlPolicy.kt",
]

# contract.ts 里的消息类型名形态（bootstrap-request / rpc-frame / mobile-log …）
ZCODE_TYPE_RE = re.compile(r'"([a-z][a-z0-9]*(?:-[a-z0-9]+)+)"')


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def git_head(mirror: Path) -> str:
    """浅克隆的 HEAD 短哈希；拿不到（packed refs 变体等）返回 unknown，不阻断 diff。"""
    head = mirror / ".git" / "HEAD"
    try:
        text = head.read_text(encoding="utf-8").strip()
        if text.startswith("ref:"):
            ref = text.split(":", 1)[1].strip()
            ref_file = mirror / ".git" / ref
            if ref_file.exists():
                return ref_file.read_text(encoding="utf-8").strip()[:12]
            packed = mirror / ".git" / "packed-refs"
            if packed.exists():
                for line in packed.read_text(encoding="utf-8").splitlines():
                    if line.strip().endswith(ref):
                        return line.split()[0][:12]
        return text[:12]
    except OSError:
        return "unknown"


def extract_zcode_types(mirror: Path) -> set[str]:
    """从 contract.ts 提取 kebab-case 消息类型名集合；文件缺失返回空集（diff 里按移除报警）。"""
    contract = mirror / WATCHED[0]
    if not contract.exists():
        return set()
    text = contract.read_text(encoding="utf-8", errors="replace")
    return set(ZCODE_TYPE_RE.findall(text))


def load_snapshot() -> dict:
    if SNAPSHOT_PATH.exists():
        return json.loads(SNAPSHOT_PATH.read_text(encoding="utf-8"))
    return {}


def write_snapshot(head: str, files: dict[str, str], zcode_types: list[str]) -> None:
    SNAPSHOT_PATH.parent.mkdir(parents=True, exist_ok=True)
    SNAPSHOT_PATH.write_text(
        json.dumps(
            {
                "checked_at": datetime.datetime.now().isoformat(timespec="seconds"),
                "git_head": head,
                "files": files,
                "zcode_types": sorted(zcode_types),
            },
            ensure_ascii=False,
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )


def main() -> int:
    parser = argparse.ArgumentParser(description="官方 sync 协议契约 diff 跟踪")
    parser.add_argument("--mirror", type=Path, default=DEFAULT_MIRROR, help="官方镜像路径（默认 ../.tmp/zai-zcode-mirror）")
    args = parser.parse_args()

    mirror = args.mirror
    if not (mirror / "packages").is_dir():
        print(f"[miss] 官方镜像不存在或不完整：{mirror}")
        print("       先 git clone --depth 1 https://github.com/zai-org/ZCode <该目录>")
        return 2

    head = git_head(mirror)
    now_files = {p: sha256_of(mirror / p) for p in WATCHED if (mirror / p).exists()}
    missing = [p for p in WATCHED if p not in now_files]
    now_types = extract_zcode_types(mirror)

    prev = load_snapshot()
    prev_files: dict[str, str] = prev.get("files", {})
    prev_types: set[str] = set(prev.get("zcode_types", []))

    changed = sorted(p for p, h in now_files.items() if prev_files.get(p) != h)
    removed = sorted(p for p in prev_files if p not in now_files)
    types_added = sorted(now_types - prev_types)
    types_removed = sorted(prev_types - now_types)

    has_change = bool(changed or removed or types_added or types_removed or missing)
    print(f"官方镜像 HEAD: {head}（{len(now_files)}/{len(WATCHED)} 个契约文件在场）")
    if prev:
        print(f"上次基线 HEAD: {prev.get('git_head', 'unknown')} @ {prev.get('checked_at', '?')}")
    else:
        print("[init] 首次运行：当前内容写入基线，下轮开始 diff。")
    for p in changed:
        print(f"[CHANGED] {p}")
    for p in removed:
        print(f"[REMOVED] {p}")
    for p in missing:
        print(f"[ABSENT ] {p}（镜像里无此文件；若为路径改名，请同步 WATCHED 清单）")
    for t in types_removed:
        print(f"[TYPE-REMOVED] zcode_type '{t}' 已从 contract.ts 移除 —— 最高优先级：排查 APP 是否在用（对照 PROTOCOL.md）")
    for t in types_added:
        print(f"[TYPE-ADDED  ] zcode_type '{t}' 新增 —— 评估能力位跟进（置顶/新建工作区/推送/日志通道等）")

    if has_change:
        print("[done] 有增量，逐项人工复核后本脚本会刷新基线。")
    else:
        print("[ok] 契约面无变化。")

    write_snapshot(head, now_files, sorted(now_types))
    return 1 if has_change else 0


if __name__ == "__main__":
    sys.exit(main())
