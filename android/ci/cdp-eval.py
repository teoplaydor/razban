#!/usr/bin/env python3
"""Evaluate one JS expression in the WebView and print its value (DOM probing
from the shell without UI taps). Requires `adb forward tcp:9222 ...` first.
Usage: python cdp-eval.py '<js-expression>'"""
import sys
import json
import urllib.request
import asyncio
import websockets


async def run(expr):
    targets = json.load(urllib.request.urlopen("http://127.0.0.1:9222/json", timeout=6))
    page = next((t for t in targets
                 if t.get("type") == "page" and t.get("webSocketDebuggerUrl")), None)
    if not page:
        print("NO PAGE")
        return
    async with websockets.connect(page["webSocketDebuggerUrl"], max_size=None) as ws:
        await ws.send(json.dumps({"id": 1, "method": "Runtime.enable"}))
        await ws.recv()
        await ws.send(json.dumps({"id": 2, "method": "Runtime.evaluate",
                                  "params": {"expression": expr, "awaitPromise": True,
                                             "returnByValue": True}}))
        while True:
            msg = json.loads(await asyncio.wait_for(ws.recv(), timeout=25))
            if msg.get("id") == 2:
                r = msg.get("result", {})
                if "exceptionDetails" in r:
                    print("JS-ERROR:", json.dumps(r["exceptionDetails"])[:400])
                else:
                    print(r.get("result", {}).get("value"))
                return


asyncio.run(run(sys.argv[1]))
