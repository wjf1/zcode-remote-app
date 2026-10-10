#!/usr/bin/env python3
"""防呆检查：声明的 @Test 是否都被真正执行。

## 为什么需要它

2026-10-07 实测踩坑：一次「删空行」的编辑把 `// ---------- 注释 ----------` 与 `@Test`
挤到了同一行，`@Test` 落进行注释 —— 方法从此**没有注解**，JUnit 静默跳过、构建照样全绿，
测试数从 39 悄悄变成 38，直到逐用例比对才发现。

本脚本把「声明」与「实际执行」做集合比对，任何声明了却没跑的用例都会让 CI 变红。

## 判据

- 声明：`@Test` 必须是**所在行唯一的非空内容**（`^\\s*@Test\\s*$`），其下第一个非空行
  必须是 `fun <name>`。这正是 Kotlin 的正常写法，也天然排除「注释掉/粘连」的伪注解。
- 执行：解析 Gradle 的 JUnit XML（`app-android/app/build/test-results/testDebugUnitTest/*.xml`）
  里的 `<testcase name="...">`。
- 期望值门禁（可选）：`--min N` 或 `--expected N` 把「实际执行数」与阈值比对，
  低于阈值即失败（`--expected` 要求完全相等）。用于挡住「整文件被删 / 整类被跳过」的
  数量级回退——集合比对只保证「声明的都跑了」，管不住「声明本身被删光」。

用法：
    python tools/check_test_count.py                 # 仅做声明/执行集合比对
    python tools/check_test_count.py --min 162       # 并断言实际执行数 >= 162（CI 用）
    python tools/check_test_count.py --expected 162  # 并断言实际执行数 == 162
"""
import argparse
import glob
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TEST_SRC = os.path.join(ROOT, "app-android", "app", "src", "test")
XML_GLOB = os.path.join(ROOT, "app-android", "app", "build", "test-results", "testDebugUnitTest", "*.xml")

ANNOT = re.compile(r"^\s*@Test\s*$")
FUN = re.compile(r"^\s*fun\s+(\w+)\s*\(")


def declared_tests():
    names = {}
    for path in glob.glob(os.path.join(TEST_SRC, "**", "*.kt"), recursive=True):
        lines = open(path, encoding="utf-8").read().splitlines()
        for i, line in enumerate(lines):
            if not ANNOT.match(line):
                continue
            for nxt in lines[i + 1:]:
                if not nxt.strip():
                    continue
                m = FUN.match(nxt)
                if m:
                    names[m.group(1)] = os.path.basename(path)
                break
    return names


def executed_tests():
    names = set()
    files = glob.glob(XML_GLOB)
    for path in files:
        xml = open(path, encoding="utf-8", errors="replace").read()
        names.update(re.findall(r'<testcase name="([^"]+)"', xml))
    return names, files


def main():
    ap = argparse.ArgumentParser(description="单测计数门禁：声明 vs 执行 + 期望值阈值")
    ap.add_argument("--min", type=int, default=None,
                    help="实际执行用例数下限，低于该值即失败（CI 期望值）")
    ap.add_argument("--expected", type=int, default=None,
                    help="实际执行用例数期望值，必须完全相等")
    args = ap.parse_args()

    declared = declared_tests()
    executed, files = executed_tests()
    if not files:
        print("✗ 未找到测试结果 XML —— 请先运行 testDebugUnitTest", file=sys.stderr)
        return 1

    missing = sorted(n for n in declared if n not in executed)
    print(f"声明 @Test : {len(declared)}")
    print(f"实际执行   : {len(executed)}  （来自 {len(files)} 个结果文件）")

    failed = False

    if missing:
        print("\n✗ 以下用例声明了 @Test 却未被执行（注解被注释掉/粘连，或方法非 public):")
        for n in missing:
            print(f"   - {n}   （{declared[n]}）")
        failed = True
    else:
        print("✓ 所有声明的 @Test 均已执行")

    # 期望值门禁：集合比对管不住「声明被整段删除」，这里用数量下限兜底。
    actual = len(executed)
    if args.expected is not None:
        if actual != args.expected:
            print(f"\n✗ 实际执行 {actual} 项，期望 {args.expected} 项（不相等）")
            failed = True
        else:
            print(f"✓ 实际执行数 == 期望值 {args.expected}")
    elif args.min is not None:
        if actual < args.min:
            print(f"\n✗ 实际执行 {actual} 项，低于下限 {args.min}（疑似用例被删/整类被跳过）")
            failed = True
        else:
            print(f"✓ 实际执行数 {actual} >= 下限 {args.min}")

    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
