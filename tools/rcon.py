"""Source RCON 客户端：用于从命令行驱动 dev 服务端做 spike 验证。

用法：
    python tools/rcon.py "spike build 5"
    python tools/rcon.py --batch "spike verify" "spike move 1 0" "spike verify"
"""
import os
import socket
import struct
import sys
import time

HOST = "127.0.0.1"
PORT = int(os.environ.get("RCON_PORT", "25575"))
PASSWORD = os.environ.get("RCON_PASSWORD", "cwwspike")


def _send(sock, req_id, ptype, payload):
    body = struct.pack("<ii", req_id, ptype) + payload.encode("utf-8") + b"\x00\x00"
    sock.sendall(struct.pack("<i", len(body)) + body)


def _recv(sock):
    raw = b""
    while len(raw) < 4:
        chunk = sock.recv(4 - len(raw))
        if not chunk:
            # 对端已关闭。原来这里会永远自旋（recv 一直返回 b""），
            # 于是"连接被服务端掐掉"变成"探针无声卡死"——排查成本高到离谱。
            raise ConnectionError("rcon connection closed by server")
        raw += chunk
    size = struct.unpack("<i", raw)[0]
    data = b""
    while len(data) < size:
        chunk = sock.recv(min(4096, size - len(data)))
        if not chunk:
            raise ConnectionError("rcon connection closed mid-packet")
        data += chunk
    req_id, ptype = struct.unpack("<ii", data[:8])
    return req_id, ptype, data[8:].decode("utf-8", "replace").strip("\x00")


def connect():
    sock = socket.create_connection((HOST, PORT), timeout=20)
    sock.settimeout(20)
    _send(sock, 1, 3, PASSWORD)
    req_id, ptype, body = _recv(sock)
    if req_id == -1:
        raise RuntimeError("rcon auth failed")
    return sock


def run(sock, command, req_id=2):
    _send(sock, req_id, 2, command)
    _, _, body = _recv(sock)
    return body


def main():
    args = sys.argv[1:]
    batch = args and args[0] == "--batch"
    commands = args[1:] if batch else args
    if not commands:
        print("usage: python tools/rcon.py \"cmd\" | --batch \"cmd1\" \"cmd2\" ...")
        return 2

    for attempt in range(30):
        try:
            sock = connect()
            break
        except OSError:
            time.sleep(2)
    else:
        print("cannot reach rcon at %s:%s" % (HOST, PORT))
        return 1

    with sock:
        for i, cmd in enumerate(commands):
            out = run(sock, cmd, i + 2)
            print("### > %s\n%s" % (cmd, out))
            if batch:
                time.sleep(0.6)
    return 0


if __name__ == "__main__":
    sys.exit(main())
