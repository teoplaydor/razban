#!/usr/bin/env python3
"""One-shot allowlisted smoke against a diagnostic ingest endpoint."""
from __future__ import annotations

import argparse
import hmac
import json
import secrets
import ssl
import time
import urllib.error
import urllib.request


def post(url: str, body: bytes, headers: dict, ctx: ssl.SSLContext) -> tuple[int, dict]:
    req = urllib.request.Request(url, data=body, method="POST")
    for k, v in headers.items():
        req.add_header(k, v)
    req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, context=ctx, timeout=15) as resp:
            return resp.status, json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        raw = e.read().decode(errors="replace")
        try:
            payload = json.loads(raw)
        except json.JSONDecodeError:
            payload = {"error": raw[:200]}
        return e.code, payload


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--endpoint", required=True)
    p.add_argument("--ca", required=True)
    p.add_argument("--marker", default="")
    args = p.parse_args()
    ctx = ssl.create_default_context()
    ctx.load_verify_locations(cafile=args.ca)
    ctx.check_hostname = False  # IP SAN; we still verify the pinned cert chain
    ctx.verify_mode = ssl.CERT_REQUIRED
    device_id = secrets.token_hex(16)
    key = secrets.token_bytes(32)
    session_id = secrets.token_hex(16)
    enroll = json.dumps({"device_id": device_id, "hmac_key_hex": key.hex()}).encode()
    code, payload = post(
        args.endpoint.rstrip("/") + "/v1/enroll",
        enroll,
        {"X-Device-Id": device_id},
        ctx,
    )
    if code != 200:
        print(f"enroll_failed http={code} body={payload}")
        return 1
    event = {
        "stage": "smoke",
        "app_version": "0.1.44",
        "network_type": "other",
        "session_id": session_id,
        "duration_ms": 1,
    }
    body = json.dumps({"events": [event]}).encode()
    ts = str(int(time.time()))
    nonce = secrets.token_hex(16)
    mac = hmac.new(key, f"{ts}.{nonce}.".encode() + body, "sha256").hexdigest()
    code, payload = post(
        args.endpoint.rstrip("/") + "/v1/events",
        body,
        {
            "X-Device-Id": device_id,
            "X-Ts": ts,
            "X-Nonce": nonce,
            "X-MAC": mac,
        },
        ctx,
    )
    print(json.dumps({
        "enrolled_prefix": device_id[:8],
        "session_prefix": session_id[:8],
        "events_http": code,
        "accepted": payload.get("accepted"),
        "marker": args.marker,
    }))
    return 0 if code == 200 and payload.get("accepted") == 1 else 2


if __name__ == "__main__":
    raise SystemExit(main())
