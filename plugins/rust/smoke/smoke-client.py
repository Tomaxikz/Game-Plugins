#!/usr/bin/env python3
"""Smoke-test client (stdlib only). Connects to the Unix socket, sends a 4-byte
big-endian length plus a 5-byte body and expects the identical frame echoed.

  python3 smoke-client.py [path-to-socket]          # default .playermanager/smoke.sock
  python3 smoke-client.py --tcp HOST:PORT           # for a tunnel that exposes a TCP port
"""
import socket
import struct
import sys


def recv_exact(s, n):
    buf = b""
    while len(buf) < n:
        chunk = s.recv(n - len(buf))
        if not chunk:
            raise SystemExit("FAIL: connection closed after %d of %d bytes" % (len(buf), n))
        buf += chunk
    return buf


def main():
    args = sys.argv[1:]
    if args and args[0] == "--tcp":
        host, port = args[1].rsplit(":", 1)
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.settimeout(5)
        s.connect((host, int(port)))
    else:
        path = args[0] if args else ".playermanager/smoke.sock"
        s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        s.settimeout(5)
        s.connect(path)
    body = b"hello"
    s.sendall(struct.pack(">I", len(body)) + body)
    (n,) = struct.unpack(">I", recv_exact(s, 4))
    echoed = recv_exact(s, n)
    if n == 5 and echoed == body:
        print("PASS: echoed %r" % echoed)
        return 0
    print("FAIL: got length %d body %r" % (n, echoed))
    return 1


if __name__ == "__main__":
    sys.exit(main())
