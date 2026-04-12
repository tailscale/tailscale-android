#!/usr/bin/env python3
#
# Copyright (c) Tailscale Inc & AUTHORS
# SPDX-License-Identifier: BSD-3-Clause
#

import argparse
import http.server
import os
import socket
import socketserver
import struct
import threading
import time
import urllib.parse


class ThreadedTCPServer(socketserver.ThreadingMixIn, socketserver.TCPServer):
    allow_reuse_address = True
    daemon_threads = True


class ThreadedHTTPServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
    allow_reuse_address = True
    daemon_threads = True


class TsocksHTTPHandler(http.server.BaseHTTPRequestHandler):
    server_version = "TSocksTestHTTP/1.0"

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        query = urllib.parse.parse_qs(parsed.query)
        if parsed.path == "/healthz":
            body = b"ok\n"
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if parsed.path == "/close":
            body = b"server_close\n"
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.send_header("Connection", "close")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            self.wfile.flush()
            self.close_connection = True
            return
        if parsed.path == "/stream":
            chunks = int(query.get("chunks", ["32"])[0])
            chunk_size = int(query.get("chunk_size", ["256"])[0])
            delay_ms = int(query.get("delay_ms", ["25"])[0])
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.send_header("Connection", "close")
            self.end_headers()
            payload = (b"x" * chunk_size) + b"\n"
            for _ in range(chunks):
                self.wfile.write(payload)
                self.wfile.flush()
                time.sleep(delay_ms / 1000.0)
            self.close_connection = True
            return
        body = f"path={parsed.path}\n".encode()
        self.send_response(200)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        return


class TsocksTCPHandler(socketserver.BaseRequestHandler):
    def handle(self):
        conn = self.request
        data = b""
        conn.settimeout(10)
        try:
            while b"\n" not in data and len(data) < 4096:
                chunk = conn.recv(1024)
                if not chunk:
                    break
                data += chunk
        except socket.timeout:
            return
        command = data.decode(errors="ignore").strip().upper()
        if not command:
            return
        if command == "PING":
            conn.sendall(b"PONG\n")
            return
        if command == "CLOSE":
            conn.sendall(b"BYE\n")
            try:
                conn.shutdown(socket.SHUT_WR)
            except OSError:
                pass
            return
        if command == "RST":
            linger = struct.pack("ii", 1, 0)
            conn.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, linger)
            return
        if command.startswith("STREAM"):
            parts = command.split()
            count = int(parts[1]) if len(parts) > 1 else 64
            delay_ms = int(parts[2]) if len(parts) > 2 else 25
            for idx in range(count):
                conn.sendall(f"chunk-{idx}\n".encode())
                time.sleep(delay_ms / 1000.0)
            return
        conn.sendall(b"UNKNOWN\n")


def start_http(host: str, port: int):
    server = ThreadedHTTPServer((host, port), TsocksHTTPHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server


def start_tcp(host: str, port: int):
    server = ThreadedTCPServer((host, port), TsocksTCPHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--lan-host", required=True)
    parser.add_argument("--tailnet-host", required=True)
    parser.add_argument("--lan-http-port", type=int, default=18080)
    parser.add_argument("--lan-tcp-port", type=int, default=19080)
    parser.add_argument("--tailnet-http-port", type=int, default=18081)
    parser.add_argument("--tailnet-tcp-port", type=int, default=19081)
    args = parser.parse_args()

    servers = [
        start_http(args.lan_host, args.lan_http_port),
        start_tcp(args.lan_host, args.lan_tcp_port),
        start_http(args.tailnet_host, args.tailnet_http_port),
        start_tcp(args.tailnet_host, args.tailnet_tcp_port),
    ]
    print(
        f"TSOCKS_TEST_SERVICES lan={args.lan_host}:{args.lan_http_port}/{args.lan_tcp_port} "
        f"tailnet={args.tailnet_host}:{args.tailnet_http_port}/{args.tailnet_tcp_port}",
        flush=True,
    )
    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        pass
    finally:
        for server in servers:
            server.shutdown()
            server.server_close()


if __name__ == "__main__":
    main()
