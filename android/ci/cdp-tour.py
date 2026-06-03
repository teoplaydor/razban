#!/usr/bin/env python3
"""Drive the Razban WebView UI over CDP from CI: visit every HashRouter route,
save a PNG screenshot of the web content AND dump its innerText for each screen.

The innerText dump is the PRIMARY assertion surface — a blank/short dump means
React crashed/unmounted (the 0.1.12 Settings-crash class of bug), and the CI log
shows exactly what text each screen rendered without needing to open an image.
The PNGs are the secondary visual record (uploaded as artifacts + montaged).

Prereq: `adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>` and
`pip install websockets`.
Usage: python cdp-tour.py <outdir>
"""
import sys
import os
import json
import base64
import urllib.request
import asyncio
import websockets

OUT = sys.argv[1] if len(sys.argv) > 1 else "shots"
os.makedirs(OUT, exist_ok=True)

# HashRouter routes the mobile shell exposes (ROUTE_ORDER in App.tsx).
ROUTES = [
    ("/home", "01-home"),
    ("/servers", "02-servers"),
    ("/stats", "03-stats"),
    ("/settings", "04-settings"),
    ("/about", "05-about"),
]


async def call(ws, _id, method, params=None):
    await ws.send(json.dumps({"id": _id, "method": method, "params": params or {}}))
    while True:
        msg = json.loads(await asyncio.wait_for(ws.recv(), timeout=30))
        if msg.get("id") == _id:
            return msg.get("result", {})


async def evaluate(ws, _id, expr):
    r = await call(ws, _id, "Runtime.evaluate",
                   {"expression": expr, "awaitPromise": True, "returnByValue": True})
    if "exceptionDetails" in r:
        return {"__jserror": json.dumps(r["exceptionDetails"])[:400]}
    return r.get("result", {}).get("value")


async def main():
    targets = json.load(urllib.request.urlopen("http://127.0.0.1:9222/json", timeout=10))
    page = next((t for t in targets
                 if t.get("type") == "page" and t.get("webSocketDebuggerUrl")), None)
    if not page:
        print("NO PAGE TARGET FOUND:", json.dumps(targets)[:300])
        sys.exit(2)
    print(f"[cdp] page target: {page.get('url')}")
    async with websockets.connect(page["webSocketDebuggerUrl"], max_size=None) as ws:
        await call(ws, 1, "Runtime.enable")
        await call(ws, 2, "Page.enable")
        i = 100
        for hash_, label in ROUTES:
            await evaluate(ws, i, f"location.hash = '#{hash_}'; '{hash_}'")
            i += 1
            await asyncio.sleep(3.0)  # let framer-motion transition + data poll settle
            txt = await evaluate(ws, i, "(document.querySelector('#root')||document.body).innerText")
            i += 1
            print(f"\n===== {label}  ({hash_}) =====")
            if isinstance(txt, str):
                print(txt[:900])
                if len(txt.strip()) < 5:
                    print("⚠️  EMPTY SCREEN — possible React crash/unmount")
            else:
                print("⚠️  non-string innerText:", json.dumps(txt)[:300])
            shot = await call(ws, i, "Page.captureScreenshot", {"format": "png", "captureBeyondViewport": False})
            i += 1
            data = shot.get("data")
            if data:
                with open(os.path.join(OUT, f"{label}.png"), "wb") as f:
                    f.write(base64.b64decode(data))
                print(f"[shot] {label}.png ({len(data)} b64 bytes)")
            else:
                print(f"[shot] FAILED for {label}: {json.dumps(shot)[:200]}")


if __name__ == "__main__":
    asyncio.run(main())
