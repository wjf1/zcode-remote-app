#!/usr/bin/env python3
"""Macrobenchmark（长会话基准）结果解析与门禁 —— §11.8 第二层门禁最后一项。

把「某次真机实测 1336 帧 0 janky」变成可重复、可对比、可自动发现回退的口径：
从 androidx.benchmark 输出的 `*BenchmarkData.json` 读取三场景（打开长会话 / 滚动 / 流式追加）
的启动耗时、帧时与 janky 帧比例，做结构性硬门禁，并可与基线文件比对（超阈值即失败）。

用法：
  # 1) 结构性门禁（CI 常跑）：场景齐备 / 帧数非零 / 数值有限
  python tools/bench_check.py --dir <结果目录或 json 文件>

  # 2) 与基线比对（趋势回退探测；模拟器与真机各用各的基线文件）
  python tools/bench_check.py --dir <目录> --baseline tools/bench_baseline_emulator.json --factor 3.0

  # 3) 记录/更新基线（跑完一轮后写盘，人工复核后提交）
  python tools/bench_check.py --dir <目录> --record tools/bench_baseline_emulator.json

  # 4) 把表格写进 GitHub Step Summary
  python tools/bench_check.py --dir <目录> --summary "$GITHUB_STEP_SUMMARY"

口径与判读（防误读）：
- janky 帧 = `frameOverrunMs > 0` 的帧（错过帧期限）；比例按全部帧统计（与真机口径一致）。
- CompilationMode.None 下数值是「未 AOT 编译」的最坏情况，模拟器与真机不可直接比绝对值；
  回退探测比对的是**同一环境、同一口径**的历史基线（倍数为阈值）。
"""

from __future__ import annotations

import argparse
import glob
import json
import math
import os
import sys

EXPECTED_SCENARIOS = (
    "openLongConversation",
    "scrollLongConversation",
    "streamingAppendLongConversation",
)
# 比对的指标键（场景 → [(指标名, 取值字段)]）；启动为单值指标，帧时为采样指标。
COMPARED_METRICS = (
    ("timeToInitialDisplayMs", "median"),
    ("frameDurationCpuMs", "P50"),
    ("frameDurationCpuMs", "P90"),
    ("frameDurationCpuMs", "P99"),
    ("frameOverrunMs", "P50"),
    ("frameOverrunMs", "P90"),
    ("frameOverrunMs", "P99"),
)


def fail(msg: str) -> None:
    print(f"✗ {msg}")
    sys.exit(1)


def find_result_files(path: str) -> list[str]:
    if os.path.isfile(path):
        return [path]
    hits = sorted(glob.glob(os.path.join(path, "**", "*BenchmarkData.json"), recursive=True))
    return hits


def load_benchmarks(paths: list[str]) -> tuple[dict, list[dict]]:
    """合并多个结果文件；同名场景保留最后一次（重跑覆盖）。返回 (context, benchmarks)。"""
    context: dict = {}
    by_name: dict[str, dict] = {}
    for p in paths:
        with open(p, "r", encoding="utf-8") as f:
            data = json.load(f)
        context = context or data.get("context", {})
        for b in data.get("benchmarks", []):
            by_name[b.get("name", "?")] = b
    return context, list(by_name.values())


def jank_ratio(bench: dict) -> tuple[float | None, int]:
    """从 frameOverrunMs.runs（每轮的帧数组）统计 janky 帧比例与总帧数。"""
    metric = bench.get("sampledMetrics", {}).get("frameOverrunMs")
    if not metric:
        return None, 0
    frames = [v for run in metric.get("runs", []) for v in run]
    if not frames:
        return None, 0
    janky = sum(1 for v in frames if v > 0)
    return janky / len(frames), len(frames)


def metric_value(bench: dict, name: str, field: str) -> float | None:
    if name in bench.get("metrics", {}):
        return bench["metrics"][name].get(field)
    if name in bench.get("sampledMetrics", {}):
        return bench["sampledMetrics"][name].get(field)
    return None


def scenario_rows(bench: dict) -> list[tuple[str, str, float | None]]:
    rows = []
    for name, field in COMPARED_METRICS:
        rows.append((name, field, metric_value(bench, name, field)))
    return rows


def fmt(v: float | None) -> str:
    if v is None:
        return "—"
    if not math.isfinite(v):
        return "非有限值"
    return f"{v:.1f}"


def print_table(
    context: dict, benchmarks: list[dict], baseline: dict | None, factor: float
) -> tuple[list[str], list[str], list[str]]:
    lines: list[str] = []
    build = context.get("build", {})
    print("== 基准环境 ==")
    print(
        f"设备: {build.get('model', '?')} / {build.get('device', '?')}"
        f" | sdk={build.get('version', {}).get('sdk', '?')}"
        f" | 编译模式={context.get('compilationMode', '?')}"
        f" | CPU 核={context.get('cpuCoreCount', '?')}"
    )
    lines.append("## Macrobenchmark 长会话基准结果")
    lines.append("")
    lines.append(
        f"设备 `{build.get('model', '?')}` / sdk {build.get('version', {}).get('sdk', '?')}"
        f" / 编译模式 `{context.get('compilationMode', '?')}`"
    )
    lines.append("")
    lines.append("| 场景 | 启动首帧 median (ms) | 帧数 | frameDuration P50/P90/P99 (ms) | frameOverrun P50/P90/P99 (ms) | janky 帧 |")
    lines.append("|---|---|---|---|---|---|")

    problems: list[str] = []
    regressions: list[str] = []

    for bench in benchmarks:
        name = bench.get("name", "?")
        ttid = metric_value(bench, "timeToInitialDisplayMs", "median")
        frame_count = metric_value(bench, "frameCount", "median")
        dur = bench.get("sampledMetrics", {}).get("frameDurationCpuMs", {})
        over = bench.get("sampledMetrics", {}).get("frameOverrunMs", {})
        ratio, frames = jank_ratio(bench)

        dur_s = "/".join(fmt(dur.get(k)) for k in ("P50", "P90", "P99"))
        over_s = "/".join(fmt(over.get(k)) for k in ("P50", "P90", "P99"))
        jank_s = f"{ratio * 100:.2f}% ({frames} 帧)" if ratio is not None else "—"

        print(f"\n== 场景 {name} ==")
        print(f"  启动首帧 median: {fmt(ttid)} ms | 帧数: {fmt(frame_count)}")
        print(f"  frameDurationCpuMs P50/P90/P99: {dur_s} ms")
        print(f"  frameOverrunMs     P50/P90/P99: {over_s} ms")
        print(f"  janky 帧: {jank_s}")
        lines.append(
            f"| `{name}` | {fmt(ttid)} | {fmt(frame_count)} | {dur_s} | {over_s} | {jank_s} |"
        )

        # 结构性硬门禁：帧为空的场景 = 空跑假绿（必须失败）
        if frames == 0:
            problems.append(f"场景 {name} 没有任何帧数据（空跑或指标缺失）")
        if ttid is not None and not math.isfinite(ttid):
            problems.append(f"场景 {name} 的启动时间非有限值")

        # 基线比对（同一口径的历史数值 × factor 为上限）
        if baseline:
            base_bench = baseline.get("benchmarks", {}).get(name)
            if not base_bench:
                print(f"  （基线中无此场景，跳过比对）")
                continue
            for metric, field in COMPARED_METRICS:
                cur = metric_value(bench, metric, field)
                base = base_bench.get(metric, {}).get(field)
                if cur is None or base is None or base <= 0:
                    continue
                if cur > base * factor:
                    regressions.append(
                        f"场景 {name} 的 {metric}.{field} = {cur:.1f} 超过基线 {base:.1f} 的 {factor}×"
                        f"（{base * factor:.1f}）"
                    )

    return lines, problems, regressions


def write_record(path: str, context: dict, benchmarks: list[dict]) -> None:
    record = {
        "context": {
            "model": context.get("build", {}).get("model"),
            "sdk": context.get("build", {}).get("version", {}).get("sdk"),
            "compilationMode": context.get("compilationMode"),
            "cpuCoreCount": context.get("cpuCoreCount"),
        },
        "benchmarks": {},
    }
    for bench in benchmarks:
        entry = {}
        for metric, field in COMPARED_METRICS:
            v = metric_value(bench, metric, field)
            if v is not None:
                entry.setdefault(metric, {})[field] = round(v, 3)
        ratio, frames = jank_ratio(bench)
        if ratio is not None:
            entry["jankyRatio"] = round(ratio, 5)
            entry["frameCount"] = frames
        record["benchmarks"][bench.get("name", "?")] = entry
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(record, f, ensure_ascii=False, indent=2, sort_keys=True)
        f.write("\n")
    print(f"✓ 基线已写入 {path}（请人工复核后提交）")


def main() -> int:
    parser = argparse.ArgumentParser(description="Macrobenchmark 长会话基准结果解析与门禁")
    parser.add_argument("--dir", required=True, help="结果目录（递归找 *BenchmarkData.json）或单个 json 文件")
    parser.add_argument("--baseline", help="基线 json（与此文件比对，超阈值即失败）")
    parser.add_argument("--factor", type=float, default=2.5, help="比对倍数阈值（默认 2.5）")
    parser.add_argument("--record", help="把本次结果写为基线 json")
    parser.add_argument("--summary", help="把结果表格追加写入该文件（如 $GITHUB_STEP_SUMMARY）")
    parser.add_argument(
        "--expect-scenarios",
        default=",".join(EXPECTED_SCENARIOS),
        help="期望跑到的场景名（逗号分隔；缺一即失败，防空跑假绿）",
    )
    args = parser.parse_args()

    files = find_result_files(args.dir)
    if not files:
        fail(f"在 {args.dir} 下找不到 *BenchmarkData.json（benchmark 未产出结果？）")
    print(f"读取 {len(files)} 个结果文件：")
    for f in files:
        print(f"  - {f}")

    context, benchmarks = load_benchmarks(files)
    names = {b.get("name") for b in benchmarks}
    expected = [s.strip() for s in args.expect_scenarios.split(",") if s.strip()]
    missing = [s for s in expected if s not in names]
    if missing:
        fail(f"结果缺少场景：{missing}（已跑：{sorted(names)}）")

    baseline = None
    if args.baseline:
        if not os.path.isfile(args.baseline):
            print(f"⚠ 基线文件 {args.baseline} 不存在——本轮只记录、不比对")
        else:
            with open(args.baseline, "r", encoding="utf-8") as f:
                baseline = json.load(f)
            print(f"与基线 {args.baseline} 比对（倍数阈值 {args.factor}×）")

    lines, problems, regressions = print_table(context, benchmarks, baseline, args.factor)

    if args.record:
        write_record(args.record, context, benchmarks)

    if args.summary:
        with open(args.summary, "a", encoding="utf-8") as f:
            f.write("\n".join(lines) + "\n")

    if problems:
        for p in problems:
            print(f"✗ {p}")
        return 1
    if regressions:
        for r in regressions:
            print(f"✗ 性能回退：{r}")
        return 1

    print("\n✓ 结构性门禁通过" + ("；基线比对通过" if baseline else "（未做基线比对）"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
