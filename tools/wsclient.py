"""极简 WebSocket 客户端（仅用标准库，支持 wss + 文本帧 + ping/pong）。
用于 tools/probe.py —— 环境无法 pip 安装 websockets，故自带实现。
"""
import base64
import json
import os
import socket
import ssl
import struct
import time
from urllib.parse import urlparse


class WSError(Exception):
    pass


class WebSocket:
    def __init__(self, url, headers=None, timeout=20):
        u = urlparse(url)
        self.host = u.hostname
        self.path = u.path or "/"
        if u.query:
            self.path += "?" + u.query
        self.port = u.port or (443 if u.scheme == "wss" else 80)
        self.secure = u.scheme == "wss"
        self.timeout = timeout
        self.sock = None
        self.buf = b""

    def connect(self):
        raw = socket.create_connection((self.host, self.port), timeout=self.timeout)
        if self.secure:
            ctx = ssl.create_default_context()
            raw = ctx.wrap_socket(raw, server_hostname=self.host)
        self.sock = raw
        key = base64.b64encode(os.urandom(16)).decode()
        req = (
            f"GET {self.path} HTTP/1.1\r\n"
            f"Host: {self.host}\r\n"
            f"Upgrade: websocket\r\n"
            f"Connection: Upgrade\r\n"
            f"Sec-WebSocket-Key: {key}\r\n"
            f"Sec-WebSocket-Version: 13\r\n"
            f"Origin: https://zcode.z.ai\r\n"
            f"User-Agent: zcode-remote-probe/0.1\r\n"
            f"\r\n"
        )
        self.sock.sendall(req.encode())
        head = b""
        while b"\r\n\r\n" not in head:
            chunk = self.sock.recv(4096)
            if not chunk:
                raise WSError("handshake closed")
            head += chunk
        header, _, rest = head.partition(b"\r\n\r\n")
        self.buf = rest
        status = header.split(b"\r\n", 1)[0].decode(errors="replace")
        if "101" not in status:
            raise WSError(f"handshake failed: {status}")

    # ---- frame io ----
    def _recv_exact(self, n):
        while len(self.buf) < n:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise WSError("connection closed")
            self.buf += chunk
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def send(self, text: str):
        payload = text.encode()
        header = bytearray([0x81])  # FIN + text
        n = len(payload)
        if n < 126:
            header.append(0x80 | n)
        elif n < 65536:
            header.append(0x80 | 126)
            header += struct.pack(">H", n)
        else:
            header.append(0x80 | 127)
            header += struct.pack(">Q", n)
        mask = os.urandom(4)
        header += mask
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        self.sock.sendall(bytes(header) + masked)

    def recv(self, timeout=None):
        """返回文本帧内容；None 表示超时。"""
        if timeout is not None:
            self.sock.settimeout(timeout)
        try:
            while True:
                b0, b1 = self._recv_exact(2)
                opcode = b0 & 0x0F
                masked = b1 & 0x80
                length = b1 & 0x7F
                if length == 126:
                    length = struct.unpack(">H", self._recv_exact(2))[0]
                elif length == 127:
                    length = struct.unpack(">Q", self._recv_exact(8))[0]
                mask = self._recv_exact(4) if masked else None
                data = self._recv_exact(length) if length else b""
                if mask:
                    data = bytes(b ^ mask[i % 4] for i, b in enumerate(data))
                if opcode == 0x1:          # text
                    return data.decode("utf-8", "replace")
                if opcode == 0x8:          # close
                    raise WSError("server closed")
                if opcode == 0x9:          # ping -> pong
                    self._send_control(0xA, data)
                    continue
                if opcode == 0xA:          # pong
                    continue
                # 0x0 continuation / 0x2 binary: 本项目只期望文本
                continue
        except (socket.timeout, TimeoutError):
            return None

    def _send_control(self, opcode, payload=b""):
        header = bytearray([0x80 | opcode])
        n = len(payload)
        header.append(0x80 | n)
        mask = os.urandom(4)
        header += mask
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
        self.sock.sendall(bytes(header) + masked)

    def close(self):
        try:
            self._send_control(0x8)
        except Exception:
            pass
        try:
            self.sock.close()
        except Exception:
            pass


def connect(url, additional_headers=None, open_timeout=20):
    ws = WebSocket(url, timeout=open_timeout)
    ws.connect()
    return ws
