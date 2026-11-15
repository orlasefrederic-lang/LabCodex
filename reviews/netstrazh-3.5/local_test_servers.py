#!/usr/bin/env python3
"""Local-only, bounded fixtures for LocalVpnTest. No forwarding or external I/O."""

import http.server
import json
import re
import signal
import socket
import threading
import time

HOST = "127.0.0.1"
HTTP_PORT = 18080
UDP_PORT = 18081
STOP = threading.Event()
TOKEN = re.compile(r"/echo/([A-Za-z0-9-]{1,96})\Z")


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "NetStrazhLocalFixture/1"
    sys_version = ""

    def setup(self):
        super().setup()
        self.connection.settimeout(20)

    def do_GET(self):
        match = TOKEN.fullmatch(self.path)
        if self.path == "/health":
            status, body = 200, b"netstrazh-local-fixture-ready"
        elif match:
            status, body = 200, ("netstrazh-http:" + match.group(1)).encode("ascii")
        else:
            status, body = 404, b"not found"
        self.send_response(status)
        self.send_header("Content-Type", "text/plain; charset=us-ascii")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)
        self.close_connection = True

    def log_message(self, fmt, *args):
        print(json.dumps({"kind": "http", "message": fmt % args}), flush=True)


def udp_loop(sock):
    sock.settimeout(0.5)
    while not STOP.is_set():
        try:
            data, source = sock.recvfrom(2048)
        except socket.timeout:
            continue
        if not data.startswith(b"ns-test:") or len(data) > 128:
            continue
        token = data[8:]
        if re.fullmatch(rb"[A-Za-z0-9-]{1,96}", token) is None:
            continue
        sock.sendto(b"netstrazh-udp:" + data, source)
        print(json.dumps({"kind": "udp", "token": token.decode("ascii")}), flush=True)


def main():
    httpd = http.server.ThreadingHTTPServer((HOST, HTTP_PORT), Handler)
    httpd.daemon_threads = True
    udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    udp.bind((HOST, UDP_PORT))
    threads = [
        threading.Thread(target=httpd.serve_forever, daemon=True),
        threading.Thread(target=udp_loop, args=(udp,), daemon=True),
    ]
    for thread in threads:
        thread.start()
    for sig in (signal.SIGTERM, signal.SIGINT):
        signal.signal(sig, lambda *_: STOP.set())
    print(json.dumps({"ready": True, "bind": HOST, "http": HTTP_PORT, "udp": UDP_PORT}), flush=True)
    try:
        while not STOP.wait(0.5):
            pass
    finally:
        httpd.shutdown()
        httpd.server_close()
        for thread in threads:
            thread.join(timeout=2)
        udp.close()


if __name__ == "__main__":
    main()
