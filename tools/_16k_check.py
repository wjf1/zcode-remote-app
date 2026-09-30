"""检查 APK 内原生库是否符合 Android 15 的 16 KB 页对齐要求。

HyperOS 的「Android 应用兼容性」弹窗列出 lib/*.so LOAD 区段未对齐即此问题。
判定：每个 PT_LOAD 段的 p_align >= 16384（arm64 要求 16K 对齐；32 位 ABI 仅需
4K 但 16K 亦合规）。zip 条目对齐（AGP 8.5.1+ 自动 -P 16）不在此检查范围。

用法：python tools/_16k_check.py <apk>
"""
import struct
import sys
import zipfile

PT_LOAD = 1


def check_so(name: str, data: bytes):
    if data[:4] != b"\x7fELF":
        return None
    is64 = data[4] == 2
    if is64:
        e_phoff = struct.unpack_from("<Q", data, 0x20)[0]
        e_phentsize = struct.unpack_from("<H", data, 0x36)[0]
        e_phnum = struct.unpack_from("<H", data, 0x38)[0]
        fmt = "<IIQQQQQQ"
    else:
        e_phoff = struct.unpack_from("<I", data, 0x1C)[0]
        e_phentsize = struct.unpack_from("<H", data, 0x2A)[0]
        e_phnum = struct.unpack_from("<H", data, 0x2C)[0]
        fmt = "<IIIIIIII"
    aligns = []
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        p_type, _flags, *_rest = struct.unpack_from(fmt, data, off)
        if p_type != PT_LOAD:
            continue
        p_align = struct.unpack_from("<Q", data, off + 0x30)[0] if is64 else \
            struct.unpack_from("<I", data, off + 0x1C)[0]
        aligns.append(p_align)
    ok = bool(aligns) and all(a >= 16384 for a in aligns)
    return name, is64, aligns, ok


def zip_data_offset(f, info):
    """so 在 zip 中的数据区绝对偏移：local header 30B + 实际 fnlen/extralen。"""
    f.seek(info.header_offset)
    hdr = f.read(30)
    fnlen, extralen = struct.unpack_from("<HH", hdr, 26)
    return info.header_offset + 30 + fnlen + extralen


def main(apk):
    bad, good = [], []
    with zipfile.ZipFile(apk) as z, open(apk, "rb") as f:
        for info in z.infolist():
            if not info.filename.startswith("lib/") or not info.filename.endswith(".so"):
                continue
            r = check_so(info.filename, z.read(info.filename))
            if r is None:
                continue
            # zip 条目数据区对齐：AGP 8.5.1+ 对未压缩 so 做 -P 16（HyperOS 弹窗的
            # 「未知错误」多为此项——ELF 段对齐了但 zip data offset 未 16K 对齐）
            data_off = zip_data_offset(f, info)
            zip_aligned = data_off % 16384 == 0
            ok = r[3] and zip_aligned
            (good if ok else bad).append((r[0], r[1], r[2], ok, data_off))
    print(f"{'SO':<56} {'ABI':<12} {'LOAD p_align':<18} {'zipOff%16K':<11} 结果")
    for name, is64, aligns, ok, off in sorted(bad + good):
        abi = "arm64-v8a" if is64 else "armeabi/x86"
        print(f"{name:<56} {abi:<12} {','.join(map(str, aligns)):<18} {off % 16384:<11} {'PASS' if ok else 'FAIL'}")
    print(f"\n未对齐: {len(bad)} 个 / 共 {len(bad) + len(good)} 个")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main(sys.argv[1])
