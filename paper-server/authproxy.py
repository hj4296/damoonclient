#!/usr/bin/env python3
"""Mojang 인증 호스트용 로컬 중계기.

마인크래프트 서버는 인증 요청을 프록시 없이(Proxy.NO_PROXY) 직접 보낸다.
이 컨테이너는 HTTPS 프록시를 거쳐야만 밖으로 나갈 수 있으므로,
/etc/hosts 로 각 호스트를 127.0.0.x 로 돌리고 여기서 받은 TCP 연결을
프록시에 CONNECT 터널로 그대로 넘긴다 (TLS 는 끝단끼리 그대로 유지).
"""
import asyncio
import os
from urllib.parse import urlparse

HOSTS = {
    "127.0.0.2": "sessionserver.mojang.com",
    "127.0.0.3": "api.minecraftservices.com",
    "127.0.0.4": "api.mojang.com",
}

proxy = urlparse(os.environ.get("HTTPS_PROXY") or os.environ["https_proxy"])
PROXY_HOST, PROXY_PORT = proxy.hostname, proxy.port


async def pipe(reader, writer):
    try:
        while data := await reader.read(65536):
            writer.write(data)
            await writer.drain()
    except (ConnectionError, asyncio.CancelledError):
        pass
    finally:
        try:
            writer.close()
        except Exception:
            pass


def handler(target):
    async def handle(c_reader, c_writer):
        try:
            p_reader, p_writer = await asyncio.open_connection(PROXY_HOST, PROXY_PORT)
            p_writer.write(f"CONNECT {target}:443 HTTP/1.1\r\nHost: {target}:443\r\n\r\n".encode())
            await p_writer.drain()
            head = await p_reader.readuntil(b"\r\n\r\n")
            if b" 200" not in head.split(b"\r\n", 1)[0]:
                print(f"[authproxy] {target}: proxy refused: {head[:120]!r}", flush=True)
                c_writer.close()
                p_writer.close()
                return
        except Exception as e:
            print(f"[authproxy] {target}: {e!r}", flush=True)
            c_writer.close()
            return
        await asyncio.gather(pipe(c_reader, p_writer), pipe(p_reader, c_writer))

    return handle


async def main():
    servers = []
    for ip, host in HOSTS.items():
        servers.append(await asyncio.start_server(handler(host), ip, 443))
        print(f"[authproxy] {ip}:443 -> {host}:443 via {PROXY_HOST}:{PROXY_PORT}", flush=True)
    await asyncio.gather(*(s.serve_forever() for s in servers))


if __name__ == "__main__":
    asyncio.run(main())
