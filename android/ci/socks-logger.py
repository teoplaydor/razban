#!/usr/bin/env python3
"""Minimal SOCKS5 proxy that LOGS every CONNECT target to a file, then relays.

Used in CI as the fake "VPN exit": the Android emulator's sing-box proxy
outbound dials this (via 10.0.2.2:1080); every domain the app tunnels shows up
in the log file, proving traffic took the proxy path. No auth, CONNECT only.
"""
import asyncio
import os
import socket
import struct
import sys

LOG = os.environ.get("SOCKS_LOG", "/tmp/socks.log")
PORT = int(os.environ.get("SOCKS_PORT", "1080"))


def log(target: str):
    with open(LOG, "a") as f:
        f.write(target + "\n")
    print("[socks] CONNECT", target, flush=True)


async def relay(r, w):
    try:
        while True:
            data = await r.read(65536)
            if not data:
                break
            w.write(data)
            await w.drain()
    except Exception:
        pass
    finally:
        try:
            w.close()
        except Exception:
            pass


async def handle(reader, writer):
    try:
        # greeting
        ver, nmethods = struct.unpack("!BB", await reader.readexactly(2))
        await reader.readexactly(nmethods)
        writer.write(b"\x05\x00")  # no auth
        await writer.drain()

        # request
        ver, cmd, _rsv, atyp = struct.unpack("!BBBB", await reader.readexactly(4))
        if atyp == 1:       # IPv4
            host = socket.inet_ntoa(await reader.readexactly(4))
        elif atyp == 3:     # domain
            ln = (await reader.readexactly(1))[0]
            host = (await reader.readexactly(ln)).decode("idna", "replace")
        elif atyp == 4:     # IPv6
            host = socket.inet_ntop(socket.AF_INET6, await reader.readexactly(16))
        else:
            writer.close(); return
        port = struct.unpack("!H", await reader.readexactly(2))[0]

        if cmd != 1:        # only CONNECT
            writer.write(b"\x05\x07\x00\x01" + b"\x00" * 6)
            await writer.drain(); writer.close(); return

        log(f"{host}:{port}")

        try:
            up_r, up_w = await asyncio.wait_for(asyncio.open_connection(host, port), 10)
        except Exception:
            writer.write(b"\x05\x04\x00\x01" + b"\x00" * 6)  # host unreachable
            await writer.drain(); writer.close(); return

        writer.write(b"\x05\x00\x00\x01" + b"\x00" * 6)       # success
        await writer.drain()
        await asyncio.gather(relay(reader, up_w), relay(up_r, writer))
    except Exception:
        try:
            writer.close()
        except Exception:
            pass


async def main():
    open(LOG, "w").close()  # truncate
    server = await asyncio.start_server(handle, "0.0.0.0", PORT)
    print(f"[socks] listening on 0.0.0.0:{PORT}, log={LOG}", flush=True)
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        sys.exit(0)
