#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""发布一致性核对（任务书 §11.8 发布链）：

    Git tag = versionName = APK versionName = CHANGELOG 版本 = Release 标题版本

APK versionName 由构建产物决定（versionName 源自 build.gradle.kts，二者同一事实来源）；
本脚本核对 tag ↔ versionName(+versionCode) ↔ CHANGELOG 头条，并解析出 Release 标题
（`vX.Y.Z: 中文摘要`，摘要取 CHANGELOG 头条标题行）与 Release notes（CHANGELOG 头条正文）
写入 _release_meta/，供发布 workflow 直接引用。

用法：python tools/check_release_consistency.py <tag> [--build-dir app-android]
任一不一致即 exit 1（发布 job 阻断）。
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def main() -> int:
    tag = sys.argv[1] if len(sys.argv) > 1 else ""
    if not tag.startswith("v"):
        print(f"::error::tag 必须以 v 开头，实际：{tag!r}")
        return 1

    gradle = (ROOT / "app-android/app/build.gradle.kts").read_text(encoding="utf-8")
    m_ver = re.search(r'versionName\s*=\s*"([^"]+)"', gradle)
    m_code = re.search(r"versionCode\s*=\s*(\d+)", gradle)
    if not m_ver or not m_code:
        print("::error::无法从 build.gradle.kts 解析 versionName/versionCode")
        return 1
    version_name, version_code = m_ver.group(1), int(m_code.group(1))

    changelog = (ROOT / "CHANGELOG.md").read_text(encoding="utf-8")
    m_head = re.search(r"^## (v[0-9][^\s（(]*)(?:（[^）]*）)?\s*·\s*(.+)$", changelog, re.M)
    if not m_head:
        print("::error::CHANGELOG 头条格式不符（应为 `## vX.Y.Z（日期）· 摘要`）")
        return 1
    cl_version, cl_summary = m_head.group(1).strip(), m_head.group(2).strip()
    notes = changelog[m_head.end():].lstrip("\n")
    nxt = re.search(r"^## ", notes[notes.find("\n") + 1:], re.M) if "\n" in notes else None
    if nxt:
        notes = notes[: nxt.start() + notes.find("\n") + 1].rstrip() + "\n"

    errors = []
    if tag != f"v{version_name}":
        errors.append(f"tag({tag}) != versionName(v{version_name})")
    if tag != cl_version:
        errors.append(f"tag({tag}) != CHANGELOG 头条({cl_version})")

    # CI 里 versionCode 需要与 tag 对应的历史一致（本地 _release_meta/code 由 workflow 注入
    # 上一版值不可得，这里只做「存在且为正整数」的防呆；精确递增由人工 review 把关）。
    if version_code <= 0:
        errors.append(f"versionCode 非法：{version_code}")

    if errors:
        for e in errors:
            print(f"::error::发布一致性失败：{e}")
        return 1

    out = ROOT / "_release_meta"
    out.mkdir(exist_ok=True)
    (out / "title.txt").write_text(f"{tag}: {cl_summary}\n", encoding="utf-8")
    (out / "notes.md").write_text(notes.rstrip() + "\n", encoding="utf-8")
    (out / "version.txt").write_text(f"{version_name} {version_code}\n", encoding="utf-8")
    print(f"✓ 一致性通过：tag = versionName = CHANGELOG 头条 = {tag}（versionCode {version_code}）")
    print(f"✓ Release 标题：{tag}: {cl_summary}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
