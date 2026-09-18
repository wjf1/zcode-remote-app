"""一次性分析脚本：解析 probe 落盘的 type=204 conversation 帧，输出结构骨架。

用法: python tools/_dump_frame.py <frames.json> [输出骨架文件]
"""
import json
import re
import sys


def extract_blocks(raw):
    """dump 文件里每帧以行首 '### type=' 开头；帧体本身可能含 '###'，故锚定行首。"""
    marks = [m.start() for m in re.finditer(r"(?m)^### type=", raw)]
    marks.append(len(raw))
    out = []
    for i in range(len(marks) - 1):
        chunk = raw[marks[i]:marks[i + 1]]
        header, _, body = chunk.partition("\n")
        out.append((header.strip(), body))
    return out


def skeleton(v, depth=0, path="$", maxdepth=4, out=None, seen_keys=None):
    pad = "  " * depth
    if out is None:
        out = []
    if depth > maxdepth:
        return out
    if isinstance(v, dict):
        for k, val in v.items():
            t = type(val).__name__
            if isinstance(val, (dict, list)):
                n = len(val)
                out.append(f"{pad}{k}: {t}({n})")
                skeleton(val, depth + 1, f"{path}.{k}", maxdepth, out, seen_keys)
            else:
                s = str(val)
                s = s[:90] + ("…" if len(s) > 90 else "")
                out.append(f"{pad}{k}: {t} = {s}")
    elif isinstance(v, list):
        if v:
            out.append(f"{pad}[0/{len(v)}] 元素类型 {type(v[0]).__name__}")
            skeleton(v[0], depth + 1, f"{path}[0]", maxdepth, out, seen_keys)
    return out


def main():
    raw = open(sys.argv[1], encoding="utf-8").read()
    blocks = extract_blocks(raw)
    f204 = [b for h, b in blocks if h.startswith("### type=204")]
    if not f204:
        print("未找到 type=204 帧")
        return 1
    # 取最大的那条（重放里同一帧会重复）
    body = max(f204, key=len)
    frame = json.loads(body)
    print("204 帧顶层字段：")
    for k, v in frame.items():
        t = type(v).__name__
        n = f"({len(v)})" if isinstance(v, (dict, list, str)) else ""
        print(f"  {k}: {t}{n}")

    print("\n===== 结构骨架（深度 4） =====")
    lines = skeleton(frame)
    text = "\n".join(lines)
    print(text)

    if len(sys.argv) > 2:
        with open(sys.argv[2], "w", encoding="utf-8") as fh:
            fh.write(text)
        print("\n骨架已写入", sys.argv[2])
    return 0


if __name__ == "__main__":
    sys.exit(main())
