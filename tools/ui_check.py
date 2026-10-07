#!/usr/bin/env python3
"""B-1 真机断言助手：从 uiautomator dump 中可靠判定界面状态。

## 为什么需要专门脚本（踩过的坑）

直接把「回到底部」当字符串在整份 dump 里搜会**假阳性**：本会话的消息正文与
toolCall 的 inputText（我们自己的命令源码）都会被渲染进会话行，里面自然包含
「回到底部」「content-desc="回到底部"」这类字面量。两次误判都源于此。

可靠判据：**节点的 text 属性整体等于「回到底部」** —— 长文本行与命令源码
不可能整体等于这四个字。同时校验节点宽度 < 200px（胶囊是窄chip）。

用法：python tools/ui_check.py <dump.xml>
"""
import re
import sys

EXACT_PILL_TEXT = re.compile(r'<node[^>]*\btext="回到底部"[^>]*/?>')
BOUNDS = re.compile(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
STATUS = re.compile(r'text="([^"]*(?:已连接|握手中|订阅中|未订阅|异常)[^"]*)"')


def pill_present(xml: str) -> bool:
    """胶囊是否可见（判据见模块 docstring）。"""
    for m in EXACT_PILL_TEXT.finditer(xml):
        node = m.group(0)
        b = BOUNDS.search(node)
        if not b:
            continue
        x1, y1, x2, y2 = map(int, b.groups())
        if (x2 - x1) < 200 and (y2 - y1) > 0:
            return True
    return False


def status_line(xml: str):
    m = STATUS.search(xml)
    return m.group(1) if m else None


def main():
    path = sys.argv[1]
    xml = open(path, encoding="utf-8", errors="replace").read()
    pill = pill_present(xml)
    print(f"dump          : {path}")
    print(f"回到底部胶囊  : {'可见' if pill else '不可见'}")
    print(f"  → 视口推断  : {'不在底部（用户在上翻）' if pill else '在底部/接近底部'}")
    print(f"状态行        : {status_line(xml)}")


if __name__ == "__main__":
    main()
