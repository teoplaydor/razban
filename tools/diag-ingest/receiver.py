#!/usr/bin/env python3
"""Isolated HTTPS receiver for allowlisted Razban diagnostic events.

Binds only the dedicated ingest port. Does not touch xray, amnezia, 443, or 9444.
Stores per-device HMAC keys enrolled over TLS. Never logs raw payloads or keys.
"""
from __future__ import annotations

import argparse
import hmac
import json
import os
import re
import ssl
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ALLOWED_STAGES = {
    "vpn_start", "vpn_started", "vpn_stop", "vpn_failed",
    "xray_start", "xray_ok", "xray_failed",
    "direct_start", "direct_joined", "direct_armed", "direct_connected",
    "direct_stream_finished", "direct_stream_error", "direct_fail_closed",
    "import_ok", "import_failed", "profile_select", "smoke",
}
ALLOWED_ERRORS = {
    "no_config", "xray_start_failed", "no_underlying_network", "protect_failed",
    "plan_mismatch", "signal_failed", "timeout", "io_error", "unknown",
}
ALLOWED_NETS = {"wifi", "cellular", "ethernet", "none", "other"}
ALLOWED_FIELDS = {"stage", "error_code", "duration_ms", "app_version", "network_type", "session_id"}
FORBIDDEN_KEYS = {
    "uri", "url", "query", "config", "json", "key", "password", "secret", "token",
    "dns", "sni", "host", "hostname", "uuid", "pem", "hmac", "authorization",
    "cookie", "header", "payload", "body", "log", "logcat", "packet", "domain",
    "server", "peer", "bind",
}
FORBIDDEN_VALUE = re.compile(
    r"(?i)(vless://|https?://|dns:|BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY|BEGIN CERTIFICATE|[?&](uuid|password|token|key)=)"
)

MAX_BODY = 32 * 1024
MAX_BATCH = 10
MAX_DEVICES = 200
NONCE_CAP = 4096
TS_WINDOW_S = 120
ENROLL_PER_HOUR = 40


class Store:
    def __init__(self, root: Path):
        self.root = root
        self.devices = root / "devices"
        self.events = root / "events.jsonl"
        self.devices.mkdir(parents=True, exist_ok=True)
        self.lock = threading.Lock()
        self.nonces: dict[str, float] = {}
        self.enrolls: list[tuple[str, float]] = []

    def device_path(self, device_id: str) -> Path:
        return self.devices / f"{device_id}.key"

    def enroll(self, device_id: str, key_hex: str, ip: str) -> tuple[int, dict]:
        now = time.time()
        with self.lock:
            self.enrolls = [(a, t) for a, t in self.enrolls if now - t < 3600]
            if sum(1 for a, _ in self.enrolls if a == ip) >= ENROLL_PER_HOUR:
                return 429, {"error": "rate"}
            if len(list(self.devices.glob("*.key"))) >= MAX_DEVICES and not self.device_path(device_id).exists():
                return 429, {"error": "full"}
            path = self.device_path(device_id)
            path.write_text(key_hex + "\n")
            os.chmod(path, 0o600)
            self.enrolls.append((ip, now))
        return 200, {"ok": True}

    def load_key(self, device_id: str) -> bytes | None:
        path = self.device_path(device_id)
        if not path.exists():
            return None
        hexkey = path.read_text().strip()
        try:
            return bytes.fromhex(hexkey)
        except ValueError:
            return None

    def remember_nonce(self, device_id: str, nonce: str) -> bool:
        now = time.time()
        token = f"{device_id}:{nonce}"
        with self.lock:
            expired = [k for k, ts in self.nonces.items() if now - ts > TS_WINDOW_S]
            for k in expired:
                self.nonces.pop(k, None)
            if len(self.nonces) > NONCE_CAP:
                return False
            if token in self.nonces:
                return False
            self.nonces[token] = now
        return True

    def append(self, device_id: str, events: list[dict]) -> int:
        if not events:
            return 0
        line = json.dumps({
            "ts": int(time.time()),
            "device_prefix": device_id[:8],
            "events": events,
        }, separators=(",", ":"))
        with self.lock:
            with self.events.open("a") as fh:
                fh.write(line + "\n")
            os.chmod(self.events, 0o600)
        return len(events)


def redact(raw: object) -> dict | None:
    if not isinstance(raw, dict):
        return None
    if any(str(k).lower() in FORBIDDEN_KEYS for k in raw):
        return None
    if set(raw) - ALLOWED_FIELDS:
        return None
    stage = raw.get("stage")
    if stage not in ALLOWED_STAGES:
        return None
    out: dict = {"stage": stage}
    if "error_code" in raw and raw["error_code"] not in (None, ""):
        if raw["error_code"] not in ALLOWED_ERRORS:
            return None
        out["error_code"] = raw["error_code"]
    if "duration_ms" in raw and raw["duration_ms"] not in (None, ""):
        try:
            dur = int(raw["duration_ms"])
        except (TypeError, ValueError):
            return None
        if dur < 0 or dur > 3_600_000:
            return None
        out["duration_ms"] = dur
    version = raw.get("app_version")
    if not isinstance(version, str) or not version or len(version) > 32:
        return None
    net = raw.get("network_type")
    if net not in ALLOWED_NETS:
        return None
    session = raw.get("session_id")
    if not isinstance(session, str) or not re.fullmatch(r"[0-9a-f]{16,32}", session):
        return None
    out["app_version"] = version
    out["network_type"] = net
    out["session_id"] = session
    for value in out.values():
        if isinstance(value, str) and FORBIDDEN_VALUE.search(value):
            return None
    return out


class Handler(BaseHTTPRequestHandler):
    store: Store

    def log_message(self, fmt: str, *args) -> None:
        sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))

    def _read_body(self) -> bytes | None:
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            return None
        if length < 0 or length > MAX_BODY:
            return None
        return self.rfile.read(length)

    def _send(self, code: int, payload: dict) -> None:
        raw = json.dumps(payload).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self) -> None:  # noqa: N802
        if self.path == "/health":
            self._send(200, {"ok": True, "service": "razban-diag-ingest"})
            return
        self._send(404, {"error": "path"})

    def do_POST(self) -> None:  # noqa: N802
        body = self._read_body()
        if body is None:
            self._send(413, {"error": "size"})
            return
        if self.path == "/v1/enroll":
            self._enroll(body)
            return
        if self.path == "/v1/events":
            self._events(body)
            return
        self._send(404, {"error": "path"})

    def _enroll(self, body: bytes) -> None:
        try:
            data = json.loads(body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            self._send(400, {"error": "json"})
            return
        device_id = str(data.get("device_id", ""))
        key_hex = str(data.get("hmac_key_hex", "")).lower()
        header_id = self.headers.get("X-Device-Id", device_id)
        if not re.fullmatch(r"[0-9a-f]{32}", device_id) or not re.fullmatch(r"[0-9a-f]{64}", key_hex):
            self._send(400, {"error": "enroll"})
            return
        if header_id and header_id != device_id:
            self._send(400, {"error": "device"})
            return
        code, payload = self.store.enroll(device_id, key_hex, self.client_address[0])
        self._send(code, payload)

    def _events(self, body: bytes) -> None:
        device_id = (self.headers.get("X-Device-Id") or "").lower()
        ts = self.headers.get("X-Ts") or ""
        nonce = (self.headers.get("X-Nonce") or "").lower()
        mac = (self.headers.get("X-MAC") or "").lower()
        if not re.fullmatch(r"[0-9a-f]{32}", device_id):
            self._send(401, {"error": "device"})
            return
        if not re.fullmatch(r"-?\d+", ts) or not re.fullmatch(r"[0-9a-f]{16,64}", nonce):
            self._send(401, {"error": "headers"})
            return
        try:
            ts_i = int(ts)
        except ValueError:
            self._send(401, {"error": "ts"})
            return
        if abs(int(time.time()) - ts_i) > TS_WINDOW_S:
            self._send(401, {"error": "skew"})
            return
        key = self.store.load_key(device_id)
        if key is None:
            self._send(401, {"error": "unknown_device"})
            return
        material = f"{ts}.{nonce}.".encode() + body
        expect = hmac.new(key, material, "sha256").hexdigest()
        if not hmac.compare_digest(expect, mac):
            self._send(401, {"error": "mac"})
            return
        if not self.store.remember_nonce(device_id, nonce):
            self._send(401, {"error": "replay"})
            return
        try:
            data = json.loads(body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            self._send(400, {"error": "json"})
            return
        raw_events = data.get("events")
        if not isinstance(raw_events, list) or len(raw_events) > MAX_BATCH:
            self._send(400, {"error": "events"})
            return
        cleaned = []
        for item in raw_events:
            ev = redact(item)
            if ev:
                cleaned.append(ev)
        accepted = self.store.append(device_id, cleaned)
        self._send(200, {"accepted": accepted})


def main() -> int:
    parser = argparse.ArgumentParser(description="Razban isolated diagnostic ingest")
    parser.add_argument("--listen", default="127.0.0.1:9446")
    parser.add_argument("--cert", required=True)
    parser.add_argument("--key", required=True)
    parser.add_argument("--data", required=True)
    args = parser.parse_args()
    host, port_s = args.listen.rsplit(":", 1)
    port = int(port_s)
    store = Store(Path(args.data))
    Handler.store = store
    httpd = ThreadingHTTPServer((host, port), Handler)
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.minimum_version = ssl.TLSVersion.TLSv1_2
    ctx.load_cert_chain(args.cert, args.key)
    httpd.socket = ctx.wrap_socket(httpd.socket, server_side=True)
    print(f"razban-diag-ingest listening https://{host}:{port}", flush=True)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
