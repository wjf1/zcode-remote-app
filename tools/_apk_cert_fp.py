"""从 APK 尾部字节解析 APK Signing Block，提取签名证书 SHA-256 指纹。

用途（X-1）：核对本机 keystore 是否就是签发已发布 APK 的那把——无需下载整包，
只要 APK 最后若干 MB（含 Central Directory 与其前的 Signing Block）。

用法： python tools/_apk_cert_fp.py <apk 或 尾部片段> [该片段在整包中的起始偏移]
"""
import hashlib
import struct
import sys

MAGIC = b"APK Sig Block 42"
ID_V2 = 0x7109871A
ID_V3 = 0xF05368C0


def u32(b, p):
    return struct.unpack_from("<I", b, p)[0]


def u64(b, p):
    return struct.unpack_from("<Q", b, p)[0]


def find_sig_block(buf):
    """返回 (pairs_start, pairs_end) —— Signing Block 内 ID-value 对区间（绝对于 buf）。"""
    magic_pos = buf.rfind(MAGIC)
    if magic_pos < 0:
        raise SystemExit("找不到 APK Sig Block magic（片段太小或非 APK）")
    # 布局： [uint64 块大小][ID-value 对…][uint64 块大小][magic 16B]
    # 块总长 = 8 + size，其中 size 含「尾部 size 字段 + magic + 各对」。
    second_size_pos = magic_pos - 8
    size2 = u64(buf, second_size_pos)
    block_start = magic_pos + 16 - 8 - size2
    if block_start < 0:
        raise SystemExit("片段太小：Signing Block 头部不在窗口内（多取一点尾部字节）")
    size1 = u64(buf, block_start)
    if size1 != size2:
        raise SystemExit(f"Signing Block 两个 size 字段不一致：{size1} != {size2}")
    return block_start + 8, second_size_pos


def iter_pairs(buf, start, end):
    p = start
    while p + 12 <= end:
        ln = u64(buf, p)
        pid = u32(buf, p + 8)
        value = buf[p + 12:p + 8 + ln]
        yield pid, value
        p += 8 + ln


def first_cert_der(v2_value):
    """v2 值：signers(带长度) → signer → signed data → digests, certificates, attrs。"""
    p = 0
    signers_len = u32(v2_value, p); p += 4
    signers_end = p + signers_len
    while p + 4 <= signers_end:
        signer_len = u32(v2_value, p); p += 4
        signer = v2_value[p:p + signer_len]; p += signer_len
        q = 0
        sd_len = u32(signer, q); q += 4
        sd = signer[q:q + sd_len]
        r = 0
        digests_len = u32(sd, r); r += 4 + digests_len       # 跳过 digests
        certs_len = u32(sd, r); r += 4
        certs_end = r + certs_len
        while r + 4 <= certs_end:
            c_len = u32(sd, r); r += 4
            yield sd[r:r + c_len]
            r += c_len
        return
    raise SystemExit("v2 块里没找到签名者")


def main():
    path = sys.argv[1]
    base = int(sys.argv[2]) if len(sys.argv) > 2 else 0   # 片段在整包中的起始偏移
    buf = open(path, "rb").read()
    pairs_start, pairs_end = find_sig_block(buf)
    for pid, value in iter_pairs(buf, pairs_start, pairs_end):
        label = {ID_V2: "v2", ID_V3: "v3"}.get(pid, f"0x{pid:08x}")
        if pid != ID_V2:
            print(f"[{label}] 跳过")
            continue
        for i, der in enumerate(first_cert_der(value)):
            fp = hashlib.sha256(der).hexdigest().upper()
            pretty = ":".join(fp[j:j + 2] for j in range(0, len(fp), 2))
            print(f"[v2] 证书 #{i} DER={len(der)}B  SHA-256={pretty}")


if __name__ == "__main__":
    main()
